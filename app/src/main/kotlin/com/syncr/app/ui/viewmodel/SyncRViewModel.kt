package com.syncr.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syncr.app.crypto.CredentialManager
import com.syncr.app.data.ConnectionRepository
import com.syncr.app.data.SmbConnection
import com.syncr.app.data.SyncTask
import com.syncr.app.data.SyncTaskRepository
import com.syncr.app.service.SyncService
import com.syncr.app.service.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/** Sealed result for SMB connection test */
sealed class TestResult {
    object Idle : TestResult()
    object Testing : TestResult()
    object Success : TestResult()
    data class Failure(val reason: String) : TestResult()
}

/**
 * ViewModel for the full syncR app.
 * Follows the pattern from Clean Android Architecture (Dumbravan) and
 * Simplifying Android Development with Coroutines and Flows (Tigcal):
 *   - private MutableStateFlow exposed as public StateFlow
 *   - viewModelScope.launch for async operations
 *   - Repository as the single source of truth for data
 */
class SyncRViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    val connectionRepo = ConnectionRepository(application.filesDir)
    val taskRepo = SyncTaskRepository(application.filesDir)
    val credentialManager = CredentialManager(application.filesDir)

    // ─── Connections ──────────────────────────────────────────────────────
    private val _connections = MutableStateFlow<List<SmbConnection>>(emptyList())
    val connections: StateFlow<List<SmbConnection>> = _connections.asStateFlow()

    // ─── Sync tasks ───────────────────────────────────────────────────────
    private val _tasks = MutableStateFlow<List<SyncTask>>(emptyList())
    val tasks: StateFlow<List<SyncTask>> = _tasks.asStateFlow()

    // ─── Connection test result ───────────────────────────────────────────
    private val _testResult = MutableStateFlow<TestResult>(TestResult.Idle)
    val testResult: StateFlow<TestResult> = _testResult.asStateFlow()

    // ─── SMB browse result (shared between SmbBrowserScreen and SyncTaskScreen) ──
    data class BrowseResult(val share: String, val path: String)
    private val _browseResult = MutableStateFlow<BrowseResult?>(null)
    val browseResult: StateFlow<BrowseResult?> = _browseResult.asStateFlow()
    fun setBrowseResult(share: String, path: String) { _browseResult.value = BrowseResult(share, path) }
    fun clearBrowseResult() { _browseResult.value = null }

    // ─── Service state (from SyncState singleton) ─────────────────────────
    val serviceStatus = SyncState.status

    init {
        loadConnections()
        loadTasks()
    }

    fun loadConnections() { _connections.value = connectionRepo.getAll() }
    fun loadTasks() { _tasks.value = taskRepo.getAll() }

    // ─── Connection CRUD ──────────────────────────────────────────────────

    fun deleteConnection(id: String) {
        connectionRepo.delete(id)
        credentialManager.deleteCredential(id)
        loadConnections()
    }

    fun getPassword(connectionId: String): String =
        credentialManager.retrievePassword(connectionId) ?: ""

    // ─── Sync task CRUD ───────────────────────────────────────────────────

    fun saveTask(task: SyncTask) {
        taskRepo.save(task)
        loadTasks()
        reloadService()
    }

    fun deleteTask(id: String) {
        taskRepo.delete(id)
        loadTasks()
    }

    fun syncNow(taskId: String? = null) {
        val intent = Intent(app, SyncService::class.java).apply {
            action = SyncService.ACTION_SYNC_NOW
            if (taskId != null) putExtra(SyncService.EXTRA_TASK_ID, taskId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun togglePause(taskId: String? = null) {
        val isPaused = if (taskId != null) {
            SyncState.status.value.taskStatuses[taskId]?.paused == true
        } else {
            SyncState.status.value.paused
        }
        val intent = Intent(app, SyncService::class.java).apply {
            action = if (isPaused) SyncService.ACTION_RESUME else SyncService.ACTION_PAUSE
            if (taskId != null) putExtra(SyncService.EXTRA_TASK_ID, taskId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }



    fun clearLedger(taskId: String) {
        val intent = Intent(app, SyncService::class.java).apply {
            action = SyncService.ACTION_CLEAR_LEDGER
            putExtra(SyncService.EXTRA_TASK_ID, taskId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    private fun reloadService() {
        val intent = Intent(app, SyncService::class.java).apply {
            action = SyncService.ACTION_RELOAD_CONFIG
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    // ─── Connection test ──────────────────────────────────────────────────

    fun testAndSaveConnection(conn: SmbConnection, password: String) {
        _testResult.value = TestResult.Testing
        val cleanHost = conn.host.trim('\\', '/', ' ')
        val cleanShare = conn.share.trim('\\', '/', ' ')
        val cleanUser = conn.username.trim()
        val cleanDomain = conn.domain.trim().ifBlank { null }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Register BouncyCastle for NTLM crypto
                val bcProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
                if (java.security.Security.getProvider(bcProvider.name) == null) {
                    java.security.Security.insertProviderAt(bcProvider, 1)
                }
                
                Socket().use { s -> s.connect(InetSocketAddress(cleanHost, conn.port), 3000) }
                
                val config = com.hierynomus.smbj.SmbConfig.builder()
                    .withTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .withSecurityProvider(com.hierynomus.security.bc.BCSecurityProvider())
                    .build()
                val client = com.hierynomus.smbj.SMBClient(config)
                
                try {
                    val c = client.connect(cleanHost, conn.port)
                    val auth = com.hierynomus.smbj.auth.AuthenticationContext(
                        cleanUser, password.toCharArray(), cleanDomain
                    )
                    val session = try {
                        c.authenticate(auth)
                    } catch (e: Exception) {
                        _testResult.value = TestResult.Failure("${e.message}")
                        runCatching { c.close() }
                        return@launch
                    }
                    try {
                        if (cleanShare.isNotBlank()) {
                            session.connectShare(cleanShare).use { /* verify */ }
                        }
                        
                        val currentSsid = com.syncr.app.network.NetworkStateManager.getCurrentSsidLegacy(app) ?: ""
                        val finalConn = conn.copy(host = cleanHost, share = cleanShare, ssid = currentSsid, username = cleanUser, domain = cleanDomain ?: "")
                        
                        connectionRepo.save(finalConn)
                        if (password.isNotEmpty()) {
                            credentialManager.storePassword(finalConn.id, password)
                        }
                        loadConnections()
                        _testResult.value = TestResult.Success
                    } catch (e: Exception) {
                        _testResult.value = TestResult.Failure("${e.message}")
                    } finally {
                        session.close()
                        c.close()
                    }
                } finally {
                    runCatching { client.close() }
                }
            } catch (e: java.net.ConnectException) {
                _testResult.value = TestResult.Failure("Cannot reach $cleanHost:${conn.port}")
            } catch (e: java.net.SocketTimeoutException) {
                _testResult.value = TestResult.Failure("Timed out — SMB unreachable")
            } catch (e: Exception) {
                _testResult.value = TestResult.Failure("${e.message}")
            }
        }
    }

    fun resetTestResult() { _testResult.value = TestResult.Idle }
}
