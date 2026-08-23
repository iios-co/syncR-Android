package com.syncr.app.smb

import android.util.Log
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages a single authenticated SMB2/3 session to a NAS share.
 *
 * Sessions are created lazily on first use and cached for subsequent transfers.
 * If the NAS closes an idle session (timeout), the next [getShare] call
 * transparently re-establishes it.
 *
 * Thread-safety: [ReentrantLock] protects session lifecycle; safe to call from
 * multiple coroutines (though typically only one transfer runs at a time).
 *
 * Resource disposal: [close] releases the share/session/connection.
 * [destroy] additionally shuts down the SMBClient's internal thread pool.
 * Call [destroy] only when the manager will not be reused (service teardown).
 */
class SmbSessionManager(
    private val host: String,
    private val port: Int,
    private val shareName: String,
    private val username: String,
    private val password: String,
    private val domain: String = ""
) {
    private val lock = ReentrantLock()
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var destroyed = false

    private val client: SMBClient = SMBClient(
        SmbConfig.builder()
            .withReadTimeout(30, TimeUnit.SECONDS)     // socket read timeout
            .withWriteTimeout(30, TimeUnit.SECONDS)    // socket write timeout
            .withTimeout(10, TimeUnit.SECONDS)         // connect/transact timeout
            .withSecurityProvider(com.hierynomus.security.bc.BCSecurityProvider())
            .build()
    )

    /**
     * Returns a live [DiskShare], establishing (or re-establishing) the session
     * as needed.  Throws on authentication failure or network error.
     *
     * Uses a 10-second lock timeout to prevent indefinite blocking if another
     * thread holds the lock during a slow network operation.
     */
    fun getShare(): DiskShare {
        if (!lock.tryLock(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting for SMB session lock")
        }
        try {
            check(!destroyed) { "SmbSessionManager has been destroyed" }
            if (isShareAlive()) return share!!
            Log.d(TAG, "Session not active, (re)connecting...")
            connect()
            return share!!
        } finally {
            lock.unlock()
        }
    }

    /**
     * Close the current session/connection but keep the SMBClient alive
     * for re-establishment. Called after transfer failures to force a fresh session.
     */
    fun close() = lock.withLock {
        closeSession()
    }

    /**
     * Permanently shut down this manager, releasing ALL resources including
     * the SMBClient's internal thread pools. Do NOT reuse after calling this.
     */
    fun destroy() = lock.withLock {
        closeSession()
        runCatching { client.close() }
        destroyed = true
        Log.d(TAG, "SmbSessionManager destroyed (client closed)")
    }

    // ─── Private ─────────────────────────────────────────────────────────

    private fun isShareAlive(): Boolean {
        return try {
            share?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun connect() {
        closeSession()        // ensure clean slate before reconnect
        try {
            Log.i(TAG, "Connecting to \\\\$host:$port\\$shareName as '$username'")
            connection = client.connect(host, port)
            val auth = AuthenticationContext(
                username,
                password.toCharArray(),
                domain.ifBlank { null }
            )
            session = connection!!.authenticate(auth)
            share = session!!.connectShare(shareName) as DiskShare
            Log.i(TAG, "SMB session established")
        } catch (e: Exception) {
            closeSession()    // don't leave half-open resources
            throw e
        }
    }

    private fun closeSession() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null
        session = null
        connection = null
    }

    companion object {
        private const val TAG = "SmbSessionManager"
    }
}
