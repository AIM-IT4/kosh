package com.amitjha.kosh.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Working(val message: String) : SyncStatus
    data class Done(val summary: String) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * Household sync over the local network: a short-lived TCP server (only while the Household screen is open)
 * advertised over mDNS as `_kosh-sync._tcp`, and a client that pairs from a QR code or syncs with the
 * paired phone. Every connection runs [Handshake] and then the encrypted [SyncExchange].
 */
class HouseholdSync(
    context: Context,
    private val repo: HouseholdRepository,
    private val store: HouseholdStore,
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _pairingCode = MutableStateFlow<PairingCode?>(null)
    /** The code shown as a QR while pairing is open on this phone. */
    val pairingCode: StateFlow<PairingCode?> = _pairingCode.asStateFlow()

    private val opLock = Mutex()
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var pairingSecret: ByteArray? = null
    @Volatile private var pairingExpiresAt = 0L
    @Volatile private var failedPairAttempts = 0

    /** Starts listening for the paired phone (and for pairing when opened). Call when the screen opens. */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return@launch
            server = socket
            register(socket.localPort)
            while (isActive) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handleIncoming(client) }
            }
        }
    }

    /** Stops listening and closes pairing. Call when the screen closes. */
    fun stop() {
        closePairing()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
        scope?.cancel()
        scope = null
    }

    /** Opens pairing for [PAIRING_WINDOW_MS] and returns the code to show, or null without a local network. */
    fun openPairing(): PairingCode? {
        start()
        val ip = localIpv4()
        val port = server?.localPort
        if (ip == null || port == null) {
            _status.value = SyncStatus.Failed("Connect this phone to Wi-Fi (the same one as the other phone).")
            return null
        }
        val secret = SyncCrypto.randomBytes(32)
        pairingSecret = secret
        pairingExpiresAt = System.currentTimeMillis() + PAIRING_WINDOW_MS
        failedPairAttempts = 0
        val me = store.me
        return PairingCode(me.id, me.name, ip, port, secret).also { _pairingCode.value = it }
    }

    fun closePairing() {
        pairingSecret?.fill(0)
        pairingSecret = null
        _pairingCode.value = null
    }

    fun clearStatus() {
        _status.value = SyncStatus.Idle
    }

    /** Pairs with the phone whose QR code was scanned, then runs a first sync. */
    suspend fun pairWith(code: PairingCode) = runOp("Pairing with ${code.deviceName}…") {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(code.host, code.port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            val hs = Handshake.client(socket.getInputStream(), socket.getOutputStream(), store.me, SyncMode.PAIR, code.secret, code.deviceId)
            adoptPeer(hs)
            store.rememberAddress(code.host, code.port)
            exchange(hs, asClient = true)
        }
    }

    /** Syncs with the paired phone, which must have its Household screen open on the same network. */
    suspend fun syncNow() {
        val peer = store.state.value.peer ?: run {
            _status.value = SyncStatus.Failed("Pair a phone first.")
            return
        }
        runOp("Looking for ${peer.name}…") {
            val key = store.pairKey() ?: throw SyncProtocolException("The pairing key is unavailable. Unpair and pair again.")
            val candidates = listOfNotNull(discover(peer.id), store.lastAddress()).distinct()
            if (candidates.isEmpty()) throw SyncProtocolException("Couldn't find ${peer.name}. Open Household sync on it, on the same Wi-Fi.")
            var lastError: Exception? = null
            for ((host, port) in candidates) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        socket.soTimeout = IO_TIMEOUT_MS
                        _status.value = SyncStatus.Working("Syncing with ${peer.name}…")
                        val hs = Handshake.client(socket.getInputStream(), socket.getOutputStream(), store.me, SyncMode.SYNC, key, peer.id)
                        store.rememberAddress(host, port)
                        exchange(hs, asClient = true)
                    }
                    return@runOp
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: SyncProtocolException("Couldn't reach ${peer.name}.")
        }
    }

    private suspend fun runOp(message: String, block: suspend () -> Unit) {
        if (!opLock.tryLock()) return
        try {
            _status.value = SyncStatus.Working(message)
            withContext(Dispatchers.IO) { block() }
        } catch (e: Exception) {
            _status.value = SyncStatus.Failed(friendly(e))
        } finally {
            opLock.unlock()
        }
    }

    private fun handleIncoming(socket: Socket) {
        socket.use {
            runCatching { it.soTimeout = IO_TIMEOUT_MS }
            runBlocking {
                // One exchange at a time; a second connection while busy is simply dropped.
                if (!opLock.tryLock()) return@runBlocking
                try {
                    val peerBefore = store.state.value.peer
                    val hs = Handshake.server(it.getInputStream(), it.getOutputStream(), store.me) { mode, id -> authKeyFor(mode, id) }
                    _status.value = SyncStatus.Working("Syncing with ${hs.peer.name}…")
                    if (hs.pairKey != null) {
                        adoptPeer(hs)
                        closePairing()
                    } else if (peerBefore?.id != hs.peer.id) {
                        return@runBlocking
                    }
                    exchange(hs, asClient = false)
                } catch (e: Exception) {
                    if (pairingSecret != null && ++failedPairAttempts >= MAX_FAILED_PAIRINGS) closePairing()
                    if (e is SyncProtocolException && e.message != "not allowed") _status.value = SyncStatus.Failed(friendly(e))
                } finally {
                    opLock.unlock()
                }
            }
        }
    }

    private fun authKeyFor(mode: SyncMode, deviceId: String): ByteArray? = when (mode) {
        SyncMode.PAIR -> pairingSecret?.takeIf { System.currentTimeMillis() < pairingExpiresAt }
        SyncMode.SYNC -> if (deviceId == store.state.value.peer?.id) store.pairKey() else null
    }

    private suspend fun adoptPeer(hs: HandshakeResult) {
        val previous = store.state.value.peer
        if (previous != null && previous.id != hs.peer.id) repo.clearReceived()
        store.savePeer(hs.peer, hs.pairKey!!)
    }

    private suspend fun exchange(hs: HandshakeResult, asClient: Boolean) {
        repo.refreshOwn()
        val mine = repo.ownItems()
        val local = repo.allItems()
        val outcome = if (asClient) {
            SyncExchange.asClient(hs.channel, mine, local, hs.peer.id)
        } else {
            SyncExchange.asServer(hs.channel, mine, local, hs.peer.id)
        }
        repo.apply(outcome.merged)
        val summary = "Sent ${outcome.sent} · Received ${outcome.received}"
        store.recordSync(summary)
        _status.value = SyncStatus.Done(summary)
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_PREFIX + store.me.id
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    /** Finds the paired phone's service on the network; null if not seen within a few seconds. */
    @Suppress("DEPRECATION")
    private suspend fun discover(peerId: String): Pair<String, Int>? {
        val found = CompletableDeferred<Pair<String, Int>?>()
        val wanted = SERVICE_PREFIX + peerId
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith(wanted)) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        found.complete(host to info.port)
                    }
                })
            }
        }
        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { found.await() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private fun localIpv4(): String? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "The other phone didn't respond. Keep Household sync open on both phones."
        is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Couldn't connect. Make sure both phones are on the same Wi-Fi with Household sync open."
        is SyncProtocolException -> e.message ?: "Sync failed."
        else -> "Sync failed: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val SERVICE_TYPE = "_kosh-sync._tcp."
        const val SERVICE_PREFIX = "kosh-"
        const val PAIRING_WINDOW_MS = 5 * 60_000L
        const val MAX_FAILED_PAIRINGS = 5
        const val CONNECT_TIMEOUT_MS = 8_000
        const val IO_TIMEOUT_MS = 20_000
        const val DISCOVERY_TIMEOUT_MS = 6_000L
    }
}
