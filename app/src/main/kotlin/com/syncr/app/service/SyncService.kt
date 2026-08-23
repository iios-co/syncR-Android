package com.syncr.app.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.syncr.app.crypto.CredentialManager
import com.syncr.app.data.SyncDirection
import com.syncr.app.data.SyncTask
import com.syncr.app.data.SyncTaskRepository
import com.syncr.app.data.ConnectionRepository
import com.syncr.app.network.NetworkState
import com.syncr.app.network.NetworkStateManager
import com.syncr.app.queue.SyncQueue
import com.syncr.app.smb.SmbSessionManager
import com.syncr.app.transfer.PullEngine
import com.syncr.app.transfer.TransferEngine
import com.syncr.app.transfer.TransferResult
import com.syncr.app.watcher.RecursiveFileObserver
import kotlinx.coroutines.*
import java.io.File

class SyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var credentialManager: CredentialManager
    private lateinit var networkStateManager: NetworkStateManager
    private lateinit var taskRepo: SyncTaskRepository
    private lateinit var connRepo: ConnectionRepository

    private var pullWorkerJob: Job? = null
    private val pullJobs = mutableMapOf<String, Job>()
    private val pullEngines = mutableMapOf<String, PullEngine>()
    private var netStateJob: Job? = null
    @Volatile private var paused = false

    private class PushTaskContext(
        val task: SyncTask,
        val queue: SyncQueue,
        val observer: RecursiveFileObserver?, // nullable if init failed
        val sessionManager: SmbSessionManager?,
        val transferEngine: TransferEngine?,
        var workerJob: Job? = null
    ) {
        fun cleanup() {
            observer?.stopWatching()
            workerJob?.cancel()
            sessionManager?.destroy()
        }
    }

    private val pushTasks = mutableMapOf<String, PushTaskContext>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notificationHelper.buildInitial(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notificationHelper.buildInitial()
            )
        }
        Log.i(TAG, "SyncService created")
        SyncState.update { it.copy(serviceRunning = true) }

        credentialManager = CredentialManager(filesDir)
        networkStateManager = NetworkStateManager(this, scope)
        taskRepo = SyncTaskRepository(filesDir)
        connRepo = ConnectionRepository(filesDir)

        scope.launch {
            var lastNotifiedLog: SyncState.LogEntry? = null
            SyncState.status.collect { status ->
                val lastLog = status.logs.lastOrNull()
                if (lastLog != null && lastLog != lastNotifiedLog) {
                    notificationHelper.showLogEntry(lastLog)
                    lastNotifiedLog = lastLog
                    delay(500)
                }
            }
        }

        networkStateManager.onNetworkChanged = {
            val netState = networkStateManager.state.value
            val ssid = networkStateManager.currentSsid
            Log.i(TAG, "Network state changed: $netState (SSID: $ssid)")
            if (netState is NetworkState.Online) {
                // Signal all push workers whose SSID matches
                pushTasks.values.forEach { pt ->
                    if (isNetworkAllowed(pt.task, ssid)) {
                        pt.queue.signal.trySend(Unit)
                    }
                }
            }
        }

        initialize()
    }

    private fun isNetworkAllowed(task: SyncTask, currentSsid: String?): Boolean {
        val conn = connRepo.getById(task.connectionId) ?: return false
        if (conn.ssid.isBlank()) return true
        if (currentSsid == null) {
            val errorMsg = "Location permission missing or Wi-Fi offline (SSID unknown). Grant permission."
            val lastError = SyncState.status.value.taskStatuses[task.id]?.lastError
            if (lastError != errorMsg) {
                SyncState.logTask(task.id, SyncState.Level.ERROR, "Network", errorMsg)
            }
            return false
        }
        if (conn.ssid != currentSsid) {
            val lastError = SyncState.status.value.taskStatuses[task.id]?.lastError
            val errorMsg = "Wi-Fi changed to '$currentSsid'. Re-save connection."
            if (lastError != errorMsg) {
                SyncState.logTask(task.id, SyncState.Level.ERROR, "Network", errorMsg)
            }
            return false
        }
        return true
    }

    private fun initialize() {
        networkStateManager.start()
        netStateJob = scope.launch {
            networkStateManager.state.collect { netState ->
                SyncState.update { it.copy(networkState = netState.toString()) }
            }
        }

        val allTasks = taskRepo.getAll()
        val enabledPushTasks = allTasks.filter { it.direction == SyncDirection.PHONE_TO_SMB && it.enabled }

        for (task in enabledPushTasks) {
            val conn = connRepo.getById(task.connectionId)
            val password = credentialManager.retrievePassword(task.connectionId) ?: credentialManager.retrievePassword(SMB_CREDENTIAL_ALIAS) ?: ""
            
            var smbSessionManager: SmbSessionManager? = null
            var transferEngine: TransferEngine? = null
            var observer: RecursiveFileObserver? = null

            val queue = SyncQueue(File(filesDir, "pending_sync_${task.id}.log"))
            queue.loadFromWal()

            if (conn == null || password.isEmpty()) {
                SyncState.logTask(task.id, SyncState.Level.ERROR, "Sync", "Missing connection or password for task ${task.name}")
            } else {
                val shareToUse = task.smbShare.ifBlank { conn.share }
                if (shareToUse.isBlank()) {
                    SyncState.logTask(task.id, SyncState.Level.ERROR, "Sync", "Share name is empty. Edit task and specify a share.")
                } else {
                    smbSessionManager = SmbSessionManager(
                        host = conn.host,
                        port = conn.port,
                        shareName = shareToUse,
                        username = conn.username,
                        password = password,
                        domain = conn.domain
                    )
                    transferEngine = TransferEngine(smbSessionManager, task.localPath, task.smbRemotePath)

                    observer = RecursiveFileObserver(task.localPath) { path ->
                        if (queue.enqueue(path, force = true)) {
                            val fileName = File(path).name
                            SyncState.addEvent(SyncState.SyncEvent(path, fileName, SyncState.EventStatus.QUEUED))
                            SyncState.updateTask(task.id) { it.copy(queueSize = queue.size()) }
                        }
                    }
                    observer.onWatchFailed = { reason ->
                        SyncState.logTask(task.id, SyncState.Level.ERROR, "Sync", "Watch failed: $reason")
                    }
                    observer.startWatching()
                }
            }

            val ctx = PushTaskContext(task, queue, observer, smbSessionManager, transferEngine)
            pushTasks[task.id] = ctx
            startPushWorker(ctx)

            // Initial scan
            scope.launch { performInitialScan(ctx) }
        }

        val watchPathStr = enabledPushTasks.joinToString(", ") { t -> t.localPath }
        Log.i(TAG, "Updating SyncState: watchActive=${enabledPushTasks.isNotEmpty()}, watchPath=$watchPathStr")
        SyncState.update { 
            it.copy(
                watchActive = enabledPushTasks.isNotEmpty(), 
                watchPath = watchPathStr
            ) 
        }

        startPullWorker()
    }

    private fun reinitialize() {
        pushTasks.values.forEach { it.cleanup() }
        pushTasks.clear()
        pullEngines.clear()
        networkStateManager.stop()
        netStateJob?.cancel()
        pullWorkerJob?.cancel()
        pullJobs.values.forEach { it.cancel() }
        pullJobs.clear()
        initialize()
    }

    private fun startPushWorker(ctx: PushTaskContext) {
        ctx.workerJob = scope.launch {
            while (isActive) {
                ctx.queue.signal.receive()
                drainQueue(ctx)
            }
        }
    }

    private suspend fun drainQueue(ctx: PushTaskContext) {
        if (ctx.queue.isEmpty()) return
        val taskStatus = SyncState.status.value.taskStatuses[ctx.task.id]
        if (taskStatus?.paused == true || paused) {
            Log.d(TAG, "drainQueue skipped: paused (task=${ctx.task.id})")
            return
        }
        val engine = ctx.transferEngine
        if (engine == null) {
            Log.d(TAG, "drainQueue skipped: transferEngine is null (task=${ctx.task.id})")
            return
        }
        
        val netState = networkStateManager.state.value
        val ssid = networkStateManager.currentSsid
        if (netState == NetworkState.Offline) {
            Log.d(TAG, "drainQueue skipped: network Offline (task=${ctx.task.id})")
            return
        }
        if (!isNetworkAllowed(ctx.task, ssid)) {
            Log.d(TAG, "drainQueue skipped: SSID '$ssid' not allowed (task=${ctx.task.id})")
            return
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val taskWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_LOCK_TAG:${ctx.task.id}")
        taskWakeLock.acquire() // No timeout: large video files can take >10 mins

        var synced = 0
        var failed = 0
        var totalBytes = 0L
        val queueStart = ctx.queue.size()
        SyncState.updateTask(ctx.task.id) { it.copy(syncing = true, queueSize = queueStart) }

        try {
            while (ctx.queue.isNotEmpty() && !(SyncState.status.value.taskStatuses[ctx.task.id]?.paused == true || paused)) {
                val path = ctx.queue.peek() ?: break
                val localFile = File(path)
                val fileName = localFile.name
                val fileSize = localFile.length()
                SyncState.updateTask(ctx.task.id) { it.copy(currentFile = fileName, currentFileSize = fileSize, queueSize = ctx.queue.size()) }

                when (val result = engine.transfer(path, ctx.task.id)) {
                    is TransferResult.Success -> {
                        ctx.queue.remove(path)
                        synced++
                        totalBytes += fileSize
                        SyncState.updateTask(ctx.task.id) { ts ->
                            ts.copy(totalSynced = ts.totalSynced + 1, currentFile = null)
                        }
                    }
                    is TransferResult.Failure -> {
                        if (!result.retryable) {
                            ctx.queue.removeFailed(path)
                            failed++
                            SyncState.updateTask(ctx.task.id) { it.copy(lastError = result.reason) }
                        } else {
                            // If it's a retryable failure but attempt loop broke (e.g. max retries exceeded internally)
                            // We shouldn't infinitely spin. TransferEngine handles retries, so if it returns Failure,
                            // we should break the drain loop and wait for the next signal to avoid battery drain.
                        }
                        break
                    }
                }
            }

            if (synced > 0 || failed > 0) ctx.queue.pruneWal()

            SyncState.updateTask(ctx.task.id) { it.copy(syncing = false, currentFile = null, queueSize = ctx.queue.size()) }
            val totalStr = com.syncr.app.watcher.RecursiveFileObserver.formatSize(totalBytes)

            if (synced > 0 && failed == 0) {
                val msg = "Synced $synced file${if (synced > 1) "s" else ""} ($totalStr)"
                SyncState.logTask(ctx.task.id, SyncState.Level.OK, "Sync", msg)
            } else if (failed > 0) {
                SyncState.logTask(ctx.task.id, SyncState.Level.WARN, "Sync", "Synced $synced, failed $failed")
            }
        } finally {
            try {
                if (taskWakeLock.isHeld) taskWakeLock.release()
            } catch (_: Exception) {}
        }
    }

    private fun startPullWorker() {
        pullWorkerJob = scope.launch {
            delay(2000)
            val pullTasks = taskRepo.getAll().filter {
                it.direction == SyncDirection.SMB_TO_PHONE && it.enabled
            }
            for (task in pullTasks) {
                if (task.pollIntervalMinutes <= 0) continue
                pullJobs[task.id] = scope.launch {
                    val intervalMs = task.pollIntervalMinutes.coerceAtLeast(1) * 60_000L
                    while (isActive) {
                        val taskStatus = SyncState.status.value.taskStatuses[task.id]
                        if (!(taskStatus?.paused == true || paused)) {
                            val ssid = networkStateManager.currentSsid
                            if (networkStateManager.state.value == NetworkState.Online && isNetworkAllowed(task, ssid)) {
                                runSinglePullTask(task)
                            }
                        }
                        delay(intervalMs)
                    }
                }
            }
        }
    }

    private suspend fun runSinglePullTask(task: SyncTask) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val taskWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_LOCK_TAG:pull:${task.id}")
        taskWakeLock.acquire() // No timeout: large video files can take >10 mins

        try {
            val password = credentialManager.retrievePassword(task.connectionId) ?: credentialManager.retrievePassword(SMB_CREDENTIAL_ALIAS) ?: ""
            if (password.isEmpty()) {
                SyncState.logTask(task.id, SyncState.Level.ERROR, "Sync", "No password for '${task.name}'")
                return
            }

            val conn = connRepo.getById(task.connectionId)
            if (conn == null) {
                SyncState.logTask(task.id, SyncState.Level.ERROR, "Sync", "Connection not found for '${task.name}'")
                return
            }

            val shareToUse = task.smbShare.ifBlank { conn.share }
            if (shareToUse.isBlank()) {
                SyncState.logTask(task.id, SyncState.Level.ERROR, "Sync", "Share name is empty. Edit task and specify a share.")
                return
            }

            val pullSessionManager = SmbSessionManager(
                host = conn.host,
                port = conn.port,
                shareName = shareToUse,
                username = conn.username,
                password = password,
                domain = conn.domain
            )

            SyncState.updateTask(task.id) { it.copy(syncing = true) }

            try {
                val pullEngine = pullEngines.getOrPut(task.id) {
                    PullEngine(
                        remoteBasePath = task.smbRemotePath,
                        localBasePath = task.localPath,
                        taskId = task.id
                    )
                }

                val count = pullEngine.pullNewFiles(pullSessionManager)
                SyncState.updateTask(task.id) { ts ->
                    ts.copy(syncing = false, totalSynced = ts.totalSynced + count)
                }
            } finally {
                pullSessionManager.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pull task '${task.name}' failed: ${e.message}")
            SyncState.updateTask(task.id) { it.copy(syncing = false, lastError = e.message?.take(60)) }
            SyncState.logTask(task.id, SyncState.Level.ERROR, "Sync", "'${task.name}' failed: ${e.message?.take(60)}")
        } finally {
            try {
                if (taskWakeLock.isHeld) taskWakeLock.release()
            } catch (_: Exception) {}
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        when (intent?.action) {
            ACTION_RELOAD_CONFIG -> {
                Log.i(TAG, "Received reload config action")
                reinitialize()
            }
            ACTION_SYNC_NOW -> {
                Log.i(TAG, "Received sync now action")
                if (taskId != null) {
                    SyncState.updateTask(taskId) { it.copy(paused = false) }
                    pushTasks[taskId]?.let { ctx -> scope.launch { performInitialScan(ctx) } }
                    val pullTask = taskRepo.getAll().firstOrNull { it.id == taskId && it.direction == SyncDirection.SMB_TO_PHONE && it.enabled }
                    if (pullTask != null) scope.launch { runSinglePullTask(pullTask) }
                } else {
                    if (paused) {
                        paused = false
                        SyncState.update { it.copy(paused = false) }
                    }
                    taskRepo.getAll().forEach { t -> SyncState.updateTask(t.id) { it.copy(paused = false) } }
                    pushTasks.values.forEach { ctx ->
                        scope.launch { performInitialScan(ctx) }
                    }
                    val pullTasks = taskRepo.getAll().filter {
                        it.direction == SyncDirection.SMB_TO_PHONE && it.enabled
                    }
                    for (pt in pullTasks) {
                        scope.launch { runSinglePullTask(pt) }
                    }
                }
            }
            ACTION_CLEAR_LEDGER -> {
                if (taskId != null) {
                    pushTasks[taskId]?.queue?.clearLedger()
                    SyncState.logTask(taskId, SyncState.Level.INFO, "Sync", "History ledger cleared")
                }
            }
            ACTION_PAUSE -> {
                Log.i(TAG, "Sync paused for task $taskId")
                if (taskId != null) {
                    SyncState.updateTask(taskId) { it.copy(paused = true, currentFile = null) }
                    SyncState.logTask(taskId, SyncState.Level.INFO, "Sync", "Paused")
                } else {
                    paused = true
                    SyncState.update { it.copy(paused = true, currentFile = null) }
                    taskRepo.getAll().forEach { t -> SyncState.updateTask(t.id) { it.copy(paused = true, currentFile = null) } }
                    SyncState.log(SyncState.Level.INFO, "Sync", "All Paused")
                }
            }
            ACTION_RESUME -> {
                Log.i(TAG, "Sync resumed for task $taskId")
                if (taskId != null) {
                    SyncState.updateTask(taskId) { it.copy(paused = false) }
                    SyncState.logTask(taskId, SyncState.Level.INFO, "Sync", "Resumed")
                    pushTasks[taskId]?.queue?.signal?.trySend(Unit)
                } else {
                    paused = false
                    SyncState.update { it.copy(paused = false) }
                    taskRepo.getAll().forEach { t -> SyncState.updateTask(t.id) { it.copy(paused = false) } }
                    SyncState.log(SyncState.Level.INFO, "Sync", "All Resumed")
                    pushTasks.values.forEach { it.queue.signal.trySend(Unit) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "SyncService destroying — cleaning up")
        SyncState.update { it.copy(serviceRunning = false, watchActive = false) }
        pushTasks.values.forEach { it.cleanup() }
        pullEngines.clear()
        networkStateManager.stop()
        netStateJob?.cancel()
        pullWorkerJob?.cancel()
        pullJobs.values.forEach { it.cancel() }
        pullJobs.clear()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun performInitialScan(ctx: PushTaskContext) = withContext(Dispatchers.IO) {
        val root = File(ctx.task.localPath)
        if (!root.exists() || !root.canRead()) return@withContext

        var queued = 0
        root.walk().filter { it.isFile }.chunked(1000).forEach { chunk ->
            queued += ctx.queue.enqueueAll(chunk.map { it.absolutePath })
        }

        if (queued > 0) {
            Log.i(TAG, "Initial scan: queued $queued file(s) from ${ctx.task.localPath}")
            val msg = "Scan found $queued new file${if (queued > 1) "s" else ""}"
            SyncState.logTask(ctx.task.id, SyncState.Level.INFO, "Sync", msg)
            SyncState.updateTask(ctx.task.id) { it.copy(queueSize = ctx.queue.size()) }
            ctx.queue.signal.trySend(Unit)
        } else {
            Log.i(TAG, "Initial scan: no new files in ${ctx.task.localPath}")
        }
    }

    private fun SyncQueue.isNotEmpty(): Boolean = !isEmpty()

    companion object {
        private const val TAG = "SyncService"
        private const val WAKE_LOCK_TAG = "com.syncr.app:transfer"
        const val SMB_CREDENTIAL_ALIAS = "smb_password"
        const val ACTION_RELOAD_CONFIG = "com.syncr.app.RELOAD_CONFIG"
        const val ACTION_SYNC_NOW = "com.syncr.app.SYNC_NOW"
        const val ACTION_PAUSE = "com.syncr.app.PAUSE"
        const val ACTION_RESUME = "com.syncr.app.RESUME"
        const val ACTION_CLEAR_LEDGER = "com.syncr.app.CLEAR_LEDGER"
        const val EXTRA_TASK_ID = "com.syncr.app.EXTRA_TASK_ID"
    }
}
