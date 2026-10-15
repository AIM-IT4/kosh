package com.amitjha.kosh.security

import android.net.Uri
import com.amitjha.kosh.KoshApp
import com.amitjha.kosh.di.AppContainer
import com.amitjha.kosh.repo.DataRepairs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Runs export and restore in the application scope, so leaving the screen, rotating the phone or switching tabs
 * mid-way can't cancel them, and finishes a restore by bringing the rest of the app in line with the restored data.
 */
class BackupFlow(private val app: KoshApp) {
    private val container: AppContainer get() = app.container

    suspend fun export(uri: Uri, password: CharArray): BackupStats = inAppScope {
        container.backup.export(uri, password, repairVersion = container.settings.repairVersion())
    }

    suspend fun restore(uri: Uri, password: CharArray): BackupStats = inAppScope {
        val result = container.backup.restore(uri, password)
        afterRestore(result.meta)
        result.stats
    }

    private suspend fun afterRestore(meta: BackupMeta) {
        val settings = container.settings
        // SMS that arrived after the backup was made are not in it: scan again from (a little before) its date.
        // Fingerprints and tombstones keep the re-scan from duplicating or reviving anything.
        val since = meta.exportedAt?.let { (it - SCAN_OVERLAP_MILLIS).coerceAtLeast(0) } ?: 0L
        runCatching { settings.setSmsLastScan(since) }
        // A backup made before a one-time data repair needs that repair applied to the restored data.
        val backupRepairs = meta.repairVersion ?: 0
        if (backupRepairs < settings.repairVersion()) {
            runCatching {
                settings.setRepairVersion(backupRepairs)
                DataRepairs.run(container.db, settings, container.netWorth)
            }
        }
        runCatching { container.insights.refresh() }
    }

    /** Runs [block] in the app scope and waits for it; the caller being cancelled doesn't stop the work. */
    private suspend fun <T> inAppScope(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        app.appScope.launch {
            runCatching { block() }.fold(onSuccess = { result.complete(it) }, onFailure = { result.completeExceptionally(it) })
        }
        return result.await()
    }

    companion object {
        private const val SCAN_OVERLAP_MILLIS = 2L * 24 * 60 * 60 * 1000
    }
}
