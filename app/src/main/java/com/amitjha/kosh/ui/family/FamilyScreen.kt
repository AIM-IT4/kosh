package com.amitjha.kosh.ui.family

import com.amitjha.kosh.ui.common.Fmt
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.amitjha.kosh.sms.FamilyCandidate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.amitjha.kosh.data.FamilyMemberEntity
import com.amitjha.kosh.designsystem.components.AmountText
import com.amitjha.kosh.designsystem.components.EmptyState
import com.amitjha.kosh.designsystem.components.KoshCard
import com.amitjha.kosh.designsystem.components.PrimaryButton
import com.amitjha.kosh.designsystem.components.SecondaryButton
import com.amitjha.kosh.designsystem.components.tr
import com.amitjha.kosh.designsystem.theme.AmountStyles
import com.amitjha.kosh.designsystem.theme.KoshTheme
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.repo.AccountWithBalance
import com.amitjha.kosh.repo.FamilyRepository
import com.amitjha.kosh.sms.FamilyForward
import com.amitjha.kosh.ui.common.ConfirmDialog
import com.amitjha.kosh.ui.common.KoshScreen
import com.amitjha.kosh.ui.common.KoshTextField
import com.amitjha.kosh.ui.common.LocalHideAmounts
import com.amitjha.kosh.ui.common.LocalSettings
import com.amitjha.kosh.ui.common.Pill
import com.amitjha.kosh.ui.common.SwitchRow
import com.amitjha.kosh.ui.common.TextInputDialog
import com.amitjha.kosh.ui.common.koshViewModel
import com.amitjha.kosh.ui.household.qrBitmap
import com.amitjha.kosh.ui.nav.Routes
import com.amitjha.kosh.ui.nav.popBackSafely
import com.amitjha.kosh.util.Period
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One family member with their accounts and what they spent this month. */
data class FamilyRow(
    val member: FamilyMemberEntity,
    val accounts: List<AccountWithBalance>,
    val spent: Long,
    val transactions: Int,
)

data class FamilyState(val rows: List<FamilyRow> = emptyList(), val loaded: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyViewModel(private val c: AppContainer) : ViewModel() {
    private val period = c.settings.settings.map { Period.containing(Time.today(), it.payCycle) }.distinctUntilChanged()

    val state: StateFlow<FamilyState> = combine(
        c.family.observeMembers(),
        c.accounts.observeAccounts(),
        period.flatMapLatest { p -> c.family.observeSpending(p.startMillis, p.endMillis) },
    ) { members, accounts, spending ->
        val spent = spending.associateBy { it.memberId }
        FamilyState(
            members.map { m ->
                FamilyRow(m, accounts.filter { it.account.memberId == m.id }, spent[m.id]?.spent ?: 0, spent[m.id]?.count ?: 0)
            },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FamilyState())

    fun add(name: String, count: Boolean, onAdded: (FamilyMemberEntity) -> Unit) = viewModelScope.launch {
        val m = c.family.add(name)
        if (!count) c.family.setCountInTotals(m.id, false)
        onAdded(m.copy(countInTotals = count))
    }

    /** What "Look for their SMS" found: numbers not linked yet that sent a code or bank alerts. */
    data class Check(
        val running: Boolean = false,
        val done: Boolean = false,
        val candidates: List<FamilyCandidate> = emptyList(),
        val smsAccess: Boolean = true,
        /** SMS Kosh could read, and the newest one's time: proof that reading works when no family SMS turns up. */
        val smsRead: Int = 0,
        val newestAt: Long? = null,
    )

    private val _check = MutableStateFlow(Check())
    val check: StateFlow<Check> = _check

    private val _forwards = MutableStateFlow<Map<String, FamilyForward.Status>?>(null)

    /**
     * What each linked number sent lately (by phone key), so "linked" can say whether forwards actually arrive; null
     * until read, or when SMS can't be read at all.
     */
    val forwards: StateFlow<Map<String, FamilyForward.Status>?> = _forwards

    fun refreshForwards() = viewModelScope.launch {
        val phones = c.family.members().mapNotNull { it.phone }.toSet()
        _forwards.value = if (!c.smsImporter.hasPermission()) null
        else runCatching { c.smsImporter.forwardStatus(phones) }.getOrNull()
    }

    /** One-line results for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * Reads SMS that arrived while Kosh wasn't listening (link codes are applied there), then lists numbers that
     * sent a link code or bank alerts but aren't linked.
     */
    fun lookForSms() = viewModelScope.launch {
        _check.value = _check.value.copy(running = true)
        runCatching { com.amitjha.kosh.sms.SmsCatchUp.run(c, alert = false) }
        val scan = runCatching { c.smsImporter.familyScan() }.getOrNull()
        _check.value = Check(
            running = false,
            done = true,
            candidates = scan?.candidates.orEmpty(),
            smsAccess = c.smsImporter.hasPermission(),
            smsRead = scan?.smsRead ?: 0,
            newestAt = scan?.newestAt,
        )
        refreshForwards()
    }

    fun rename(id: Long, name: String) = viewModelScope.launch { c.family.rename(id, name) }

    /** Links [phone] to the member and files what it forwarded before under their accounts. */
    fun linkNumber(id: Long, phone: String) = viewModelScope.launch {
        if (!c.family.linkNumber(id, phone)) {
            messages.tryEmit("That doesn't look like a mobile number")
            return@launch
        }
        val r = runCatching { c.smsImporter.adoptForwards(id) }.getOrNull()
        val name = c.family.members().firstOrNull { it.id == id }?.name.orEmpty()
        val moved = (r?.adopted ?: 0) + (r?.inserted ?: 0)
        messages.tryEmit(if (moved > 0) "$name linked · $moved earlier SMS added to their accounts" else "$name linked")
        _check.value = _check.value.copy(candidates = _check.value.candidates.filter { it.phone != com.amitjha.kosh.sms.FamilyForward.phoneKey(phone) })
    }

    /** A forwarded bank SMS pasted or shared into Kosh, for [memberId] (null: the user's own). */
    fun paste(memberId: Long?, text: String) = viewModelScope.launch {
        val r = runCatching { c.smsImporter.importPasted(memberId, text) }.getOrNull()
        val t = r?.insertedTransactions?.firstOrNull()
        messages.tryEmit(
            when {
                t != null -> "Added " + com.amitjha.kosh.model.Money.format(t.amount) + (if (t.type == com.amitjha.kosh.model.TxnType.INCOME) " received" else " spent") +
                    (t.merchant?.let { " · $it" } ?: "")
                r != null && r.duplicates > 0 -> "Already in Kosh"
                r != null && r.bills > 0 -> "Card bill added"
                else -> "Kosh couldn't find a bank transaction in that text"
            },
        )
    }
    fun unlink(id: Long) = viewModelScope.launch { c.family.unlink(id) }
    fun setCount(id: Long, count: Boolean) = viewModelScope.launch { c.family.setCountInTotals(id, count) }
    fun remove(id: Long) = viewModelScope.launch { c.family.remove(id) }
    fun setMyPhone(phone: String) = viewModelScope.launch { c.settings.setMyPhone(phone) }
}

/**
 * Family members whose bank SMS Kosh reads without them installing it: they scan a QR code once (which links their
 * number), then forward their bank SMS to the user's phone.
 */
@Composable
fun FamilyScreen(nav: NavController) {
    val vm = koshViewModel { FamilyViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    val hidden = LocalHideAmounts.current
    var adding by remember { mutableStateOf(false) }
    var editingPhone by remember { mutableStateOf(false) }
    var qrFor by remember { mutableStateOf<Long?>(null) }
    var stepsFor by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var renaming by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var typingNumber by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var removing by remember { mutableStateOf<FamilyRow?>(null) }
    // Showing the QR code needs the user's number in it, so ask for that first.
    var qrAfterPhone by remember { mutableStateOf<Long?>(null) }
    fun showQr(id: Long) {
        if (FamilyForward.phoneKey(settings.myPhone) == null) {
            qrAfterPhone = id
            editingPhone = true
        } else {
            qrFor = id
        }
    }

    val check by vm.check.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var pasting by remember { mutableStateOf<String?>(null) }
    // Text shared into Kosh from another app (a forward that came as a chat message).
    val shared by SharedSms.pending.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        shared?.let {
            pasting = it
            SharedSms.pending.value = null
        }
    }
    val forwards by vm.forwards.collectAsStateWithLifecycle()
    // Whether linked members' forwards actually arrive, read once per visit (and after "Look for their SMS").
    LaunchedEffect(state.rows.count { it.member.phone != null }) { vm.refreshForwards() }
    val waiting = state.rows.filter { it.member.phone == null }
    // Someone still to link: look through the inbox once on opening.
    LaunchedEffect(waiting.isNotEmpty()) { if (waiting.isNotEmpty() && !check.done && !check.running) vm.lookForSms() }

    KoshScreen(title = "Family members", onBack = { nav.popBackSafely() }, snackbar = snackbar) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp)) {
            item(key = "intro") {
                KoshCard(Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Text("Their bank SMS, without installing Kosh", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "They scan a QR code once with their camera, then forward their bank SMS to your phone. Kosh adds each " +
                            "one to their accounts here. It's all normal SMS: nothing goes over the internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoshTheme.colors.subtleText,
                    )
                }
            }
            item(key = "phone") {
                val key = FamilyForward.phoneKey(settings.myPhone)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editingPhone = true }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Your mobile number"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (key == null) tr("They forward their SMS to it. Tap to add.") else "+91 ${key.take(5)} ${key.drop(5)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (key == null) KoshTheme.colors.warning else KoshTheme.colors.subtleText,
                        )
                    }
                }
            }
            if (waiting.isNotEmpty()) {
                item(key = "check") {
                    LinkHelp(
                        check,
                        waiting.map { it.member },
                        onLook = vm::lookForSms,
                        onLink = { member, phone -> vm.linkNumber(member.id, phone) },
                        onPaste = { pasting = "" },
                        onType = { typingNumber = it },
                    )
                }
            }
            if (state.loaded && state.rows.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        "No family members yet",
                        "Add someone to track their bank accounts here.",
                        Modifier.padding(top = 24.dp),
                        icon = Icons.Outlined.FamilyRestroom,
                    )
                }
            }
            items(state.rows, key = { it.member.id }) { row ->
                MemberCard(
                    row,
                    hidden,
                    forwards = row.member.phone?.let { forwards?.get(it) },
                    forwardsKnown = forwards != null,
                    onQr = { showQr(row.member.id) },
                    onSteps = { stepsFor = row.member },
                    onCount = { vm.setCount(row.member.id, it) },
                    onAccount = { nav.navigate(Routes.account(it)) },
                    onRename = { renaming = row.member },
                    onTypeNumber = { typingNumber = row.member },
                    onPaste = { pasting = "" },
                    onUnlink = { vm.unlink(row.member.id) },
                    onRemove = { removing = row },
                )
            }
            item(key = "add") {
                PrimaryButton("Add family member", onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
    }

    if (adding) {
        AddMemberDialog(
            onAdd = { name, count ->
                adding = false
                vm.add(name, count) { showQr(it.id) }
            },
            onDismiss = { adding = false },
        )
    }
    if (editingPhone) {
        TextInputDialog(
            title = "Your mobile number",
            label = "Mobile number",
            initial = settings.myPhone,
            supporting = "The number on this phone. Family members forward their bank SMS to it.",
            keyboardType = KeyboardType.Phone,
            valid = { FamilyForward.phoneKey(it) != null },
            onSave = {
                vm.setMyPhone(it)
                editingPhone = false
                qrAfterPhone?.let { id -> qrFor = id }
                qrAfterPhone = null
            },
            onDismiss = {
                editingPhone = false
                qrAfterPhone = null
            },
        )
    }
    qrFor?.let { id ->
        // The live row, so the sheet turns to "linked" the moment their message arrives.
        val member = state.rows.firstOrNull { it.member.id == id }?.member
        val phone = FamilyForward.phoneKey(settings.myPhone)
        if (member != null && phone != null) {
            LinkSheet(member, settings.myPhone, onSteps = { qrFor = null; stepsFor = member }, onDismiss = { qrFor = null })
        }
    }
    stepsFor?.let { m -> StepsSheet(m, settings.userName, settings.myPhone, onDismiss = { stepsFor = null }) }
    renaming?.let { m ->
        TextInputDialog(
            title = "Rename",
            label = "Name",
            initial = m.name,
            valid = { it.isNotBlank() && it.length <= FamilyRepository.MAX_NAME },
            onSave = { vm.rename(m.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    typingNumber?.let { m ->
        TextInputDialog(
            title = "${m.name}'s mobile number",
            label = "Mobile number",
            initial = "",
            supporting = "Use this if their phone can't scan the QR code. SMS from this number will be read as ${m.name}'s.",
            keyboardType = KeyboardType.Phone,
            valid = { FamilyForward.phoneKey(it) != null },
            onSave = { vm.linkNumber(m.id, it); typingNumber = null },
            onDismiss = { typingNumber = null },
        )
    }
    pasting?.let { text ->
        PasteDialog(
            initial = text,
            members = state.rows.map { it.member },
            onAdd = { memberId, body ->
                vm.paste(memberId, body)
                pasting = null
            },
            onDismiss = { pasting = null },
        )
    }
    removing?.let { row ->
        ConfirmDialog(
            "Remove ${row.member.name}?",
            "Their ${row.accounts.size} account(s) and every transaction on them are deleted from Kosh. If they keep " +
                "forwarding, their messages will show up as accounts for you to review.",
            "Remove",
            onConfirm = { vm.remove(row.member.id); removing = null },
            onDismiss = { removing = null },
            destructive = true,
        )
    }
}

@Composable
private fun MemberCard(
    row: FamilyRow,
    hidden: Boolean,
    forwards: FamilyForward.Status?,
    forwardsKnown: Boolean,
    onQr: () -> Unit,
    onSteps: () -> Unit,
    onCount: (Boolean) -> Unit,
    onAccount: (Long) -> Unit,
    onRename: () -> Unit,
    onTypeNumber: () -> Unit,
    onPaste: () -> Unit,
    onUnlink: () -> Unit,
    onRemove: () -> Unit,
) {
    val m = row.member
    var menu by remember { mutableStateOf(false) }
    KoshCard(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (m.phone != null) {
                    Pill("Linked · ••${m.phone.takeLast(4)}", KoshTheme.colors.income)
                    if (forwardsKnown) ForwardLine(forwards, m.name)
                } else {
                    Pill(tr("Not linked yet"), KoshTheme.colors.warning)
                }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(tr("Rename")) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(tr("Type their number instead")) }, onClick = { menu = false; onTypeNumber() })
                DropdownMenuItem(text = { Text(tr("Paste a forwarded SMS")) }, onClick = { menu = false; onPaste() })
                if (m.phone != null) DropdownMenuItem(text = { Text(tr("Stop reading their SMS")) }, onClick = { menu = false; onUnlink() })
                DropdownMenuItem(text = { Text(tr("Remove"), color = KoshTheme.colors.alert) }, onClick = { menu = false; onRemove() })
            }
        }
        if (row.transactions > 0 || row.accounts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(tr("Spent this month"), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            Row(verticalAlignment = Alignment.Bottom) {
                AmountText(row.spent, style = AmountStyles.large, hidden = hidden)
                Spacer(Modifier.width(8.dp))
                Text("${row.transactions} " + tr("transactions"), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
            }
        }
        row.accounts.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onAccount(a.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    a.account.name.removePrefix("${m.name} · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (a.isPending) {
                    Text(tr("to confirm"), style = MaterialTheme.typography.labelMedium, color = KoshTheme.colors.warning)
                } else {
                    AmountText(if (a.isCard) -a.outstanding else a.balance, style = AmountStyles.small, hidden = hidden)
                }
            }
        }
        if (row.accounts.isEmpty() && m.phone != null) {
            Text(
                tr("Their accounts appear here with the first SMS they forward. Nothing after they forwarded one? It came as an RCS chat: ask them to turn RCS off for you, or paste it here."),
                style = MaterialTheme.typography.bodySmall,
                color = KoshTheme.colors.subtleText,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        SwitchRow(
            "Count in my totals",
            if (m.countInTotals) "Their spending, income and balances add to yours" else "Shown here and on their accounts only",
            m.countInTotals,
            onCount,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (m.phone == null) "Show QR code" else "Link again", onClick = onQr, modifier = Modifier.weight(1f), icon = Icons.Outlined.QrCode2)
            SecondaryButton("How to forward", onClick = onSteps, modifier = Modifier.weight(1f), icon = Icons.Outlined.Share)
        }
    }
}

@Composable
private fun AddMemberDialog(onAdd: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add family member")) },
        text = {
            Column {
                KoshTextField(name, { name = it.take(FamilyRepository.MAX_NAME) }, "Name", supporting = "e.g. Mom, Priya")
                SwitchRow(
                    "Count in my totals",
                    "Their spending and balances add to yours. Turn off to see them separately.",
                    count,
                    { count = it },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name.trim(), count) }, enabled = name.isNotBlank()) { Text(tr("Next")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** The QR code a family member scans to link their phone; turns to a tick when their message arrives. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(member: FamilyMemberEntity, myPhone: String, onSteps: () -> Unit, onDismiss: () -> Unit) {
    val payload = FamilyForward.qrPayload(myPhone, member.linkCode)
    val bitmap = remember(payload) { qrBitmap(payload, 640) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Link ${member.name}'s phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (member.phone != null) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = KoshTheme.colors.income, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(8.dp))
                Text("${member.name}'s phone is linked (••${member.phone.takeLast(4)}).", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Now ask them to forward their bank SMS to you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("How to forward", onClick = onSteps, modifier = Modifier.fillMaxWidth())
            } else {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = "Link code",
                    modifier = Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ask ${member.name} to scan this with their phone camera and tap Send.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "It opens their Messages app with \"${FamilyForward.linkText(member.linkCode)}\" addressed to you. When it " +
                        "arrives, Kosh links their number. Keep this screen open to see it happen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Can't scan? They can text ${FamilyForward.linkText(member.linkCode)} to $myPhone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Helps link someone whose link message didn't arrive: numbers in the inbox that sent a code or bank alerts (pick who
 * each is), or, when there are none, why Kosh may not see their messages.
 */
@Composable
private fun LinkHelp(
    check: FamilyViewModel.Check,
    waiting: List<FamilyMemberEntity>,
    onLook: () -> Unit,
    onLink: (FamilyMemberEntity, String) -> Unit,
    onPaste: () -> Unit,
    onType: (FamilyMemberEntity) -> Unit,
) {
    KoshCard(Modifier.padding(vertical = 6.dp)) {
        when {
            check.running -> Text(tr("Looking for their SMS…"), style = MaterialTheme.typography.bodyMedium)
            check.candidates.isNotEmpty() -> {
                Text(tr("SMS from numbers not linked yet"), fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Is one of them theirs? Pick who sent it. Bank SMS they forwarded before move to their accounts."),
                    style = MaterialTheme.typography.bodySmall,
                    color = KoshTheme.colors.subtleText,
                )
                check.candidates.forEach { cand ->
                    Spacer(Modifier.height(10.dp))
                    Text(cand.number, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    val what = listOfNotNull(
                        cand.linkCode?.let { "sent link code $it" },
                        cand.forwards.takeIf { it > 0 }?.let { "$it bank SMS" },
                        com.amitjha.kosh.ui.common.Fmt.date(Time.toDate(cand.lastAt)),
                    ).joinToString(" · ")
                    Text(what, style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                    if (cand.snippet.isNotEmpty()) {
                        Text("“${cand.snippet}”", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    // The member whose code it sent first, then everyone else still waiting.
                    val order = waiting.sortedBy { if (it.linkCode == cand.linkCode) 0 else 1 }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        order.take(3).forEach { m ->
                            SecondaryButton("This is ${m.name}", onClick = { onLink(m, cand.number) })
                        }
                    }
                }
            }
            check.done -> {
                Text(tr("No SMS from them yet"), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (!check.smsAccess) {
                    Text(tr("Kosh can't read SMS: allow SMS access in Settings › Bank SMS."), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.alert)
                } else {
                    // Reading works (here is the newest SMS's time), so their message didn't arrive as an SMS.
                    check.newestAt?.let {
                        Text(
                            "Kosh can read your SMS (${check.smsRead} in the last 2 months, newest " +
                                com.amitjha.kosh.ui.common.Fmt.date(Time.toDate(it)) + " " +
                                java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH)
                                    .format(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())) +
                                "), but none from a family member's number.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(tr(NOT_SMS_HELP), style = MaterialTheme.typography.bodySmall, color = KoshTheme.colors.subtleText)
                }
            }
            else -> Text(tr("Their link SMS hasn't arrived yet."), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            SecondaryButton("Look again", onClick = onLook, enabled = !check.running, modifier = Modifier.weight(1f))
            SecondaryButton("Paste an SMS", onClick = onPaste, modifier = Modifier.weight(1f))
        }
        // Linking doesn't need any message: their number is enough.
        waiting.take(2).forEach { m ->
            SecondaryButton("Type ${m.name}'s number", onClick = { onType(m) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** Why a family member's messages may not reach Kosh, and what to do. */
const val NOT_SMS_HELP =
    "Their message most likely arrived as an RCS chat, not an SMS: Google Messages shows both in the same " +
        "conversation (and iPhones send RCS too), but Android only lets other apps read SMS. To fix it, the sender " +
        "turns off RCS for messages to you: in Google Messages, Settings › RCS chats › off; on an iPhone, Settings › " +
        "Apps › Messages › RCS Messaging › off. Or turn off RCS chats on your phone, and everyone's messages to you " +
        "come as SMS. Meanwhile, type their number below to link them, and paste or share (Share › Kosh) any " +
        "forward that comes as a chat."

/** A forwarded bank SMS typed, pasted or shared in, and whose it is. */
@Composable
private fun PasteDialog(initial: String, members: List<FamilyMemberEntity>, onAdd: (Long?, String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val mine = -1L
    var owner by remember { mutableStateOf(members.singleOrNull()?.id ?: members.firstOrNull()?.id ?: mine) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Add a forwarded bank SMS")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KoshTextField(text, { text = it }, "Bank SMS", singleLine = false, supporting = "Paste the message as the bank sent it")
                com.amitjha.kosh.ui.common.ChoiceField(
                    "Whose account",
                    members.map { it.id } + mine,
                    owner,
                    { id -> members.firstOrNull { it.id == id }?.name ?: "Mine" },
                    { owner = it },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(owner.takeIf { it != mine }, text) }, enabled = text.isNotBlank()) { Text(tr("Add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** How a family member forwards their bank SMS, on Android and iPhone, with a button to send them the steps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepsSheet(member: FamilyMemberEntity, userName: String, myPhone: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val me = userName.trim().substringBefore(' ').ifEmpty { myPhone.ifBlank { "me" } }
    val text = forwardingSteps(me)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
        ) {
            Text("How ${member.name} forwards bank SMS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                "Send these steps to ${member.name}",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.Share,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The forwarding steps, addressed to the family member; [me] is who they forward to. */
/**
 * Under "Linked": whether their bank SMS actually reach this phone. Linking only tells Kosh their number; on Android
 * each alert still has to be forwarded (as SMS, not chat) before Kosh can add it.
 */
@Composable
private fun ForwardLine(status: FamilyForward.Status?, name: String) {
    val week = 7L * 24 * 60 * 60 * 1000
    val lastAlert = status?.lastAlertAt
    val (text, warn) = when {
        status == null -> "No SMS from $name's number in the last 30 days. Their forwards aren't reaching this phone: " +
            "they need to forward each bank SMS to you as a text message, not as a chat (RCS)." to true
        lastAlert == null -> "Their last SMS (${Fmt.ago(status.lastAt)}) wasn't a bank alert. Nothing to add yet." to true
        System.currentTimeMillis() - lastAlert > week ->
            "Last bank SMS from them ${Fmt.ago(lastAlert)}. Newer ones haven't been forwarded to you." to true
        else -> "Last bank SMS from them ${Fmt.ago(lastAlert)} · ${status.alerts} in 30 days" to false
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) KoshTheme.colors.warning else KoshTheme.colors.subtleText,
        modifier = Modifier.padding(top = 6.dp),
    )
}

fun forwardingSteps(me: String): String =
    "To share your bank SMS with $me's Kosh app:\n\n" +
        "Android: open the bank SMS, press and hold it, tap Forward and choose $me. You can select several messages and " +
        "forward them together.\n\n" +
        "iPhone (automatic): open Shortcuts › Automation › New Automation › Message. Set \"Message Contains\" to Rs " +
        "(make a second one for INR), choose Run Immediately, then add the action Send Message: tap Message and pick " +
        "Shortcut Input, and set the recipient to $me.\n\n" +
        "Please send them as SMS: if your messages to $me show as chat (RCS), turn it off in Google Messages " +
        "(Settings › RCS chats) or on iPhone (Settings › Apps › Messages › RCS Messaging).\n\n" +
        "Only bank alerts are added; everything else is ignored."
