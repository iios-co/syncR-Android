package com.syncr.app.smb

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Utility for browsing SMB shares and directories.
 * Connection is established lazily on first use.
 * All operations run on [Dispatchers.IO] and are safe to call from coroutines.
 */
class SmbBrowser(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val domain: String = ""
) {
    private val client = SMBClient(
        SmbConfig.builder()
            .withReadTimeout(10, TimeUnit.SECONDS)
            .withWriteTimeout(10, TimeUnit.SECONDS)
            .withTimeout(5, TimeUnit.SECONDS)
            .withSecurityProvider(com.hierynomus.security.bc.BCSecurityProvider())
            .build()
    )

    private var connection: Connection? = null
    private var session: Session? = null

    /**
     * Connect and authenticate. Must be called (on IO dispatcher) before listing.
     * Throws with a descriptive message on failure.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        try {
            connection = client.connect(host, port)
        } catch (e: Exception) {
            throw RuntimeException("Cannot reach $host:$port — ${e.message ?: e.javaClass.simpleName}", e)
        }
        try {
            val auth = AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
            session = connection!!.authenticate(auth)
        } catch (e: Exception) {
            runCatching { connection?.close() }
            connection = null
            throw RuntimeException("Authentication failed for '$username' — ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private suspend fun ensureConnected() {
        if (connection == null || !connection!!.isConnected) {
            connect()
        }
    }

    /**
     * List subdirectories at the given path within a share.
     */
    suspend fun listFolders(shareName: String, path: String = ""): List<String> = withContext(Dispatchers.IO) {
        try {
            doListFolders(shareName, path)
        } catch (e: Exception) {
            runCatching { session?.close() }
            runCatching { connection?.close() }
            connection = null
            session = null
            doListFolders(shareName, path)
        }
    }

    private suspend fun doListFolders(shareName: String, path: String): List<String> {
        ensureConnected()
        val share = session!!.connectShare(shareName) as DiskShare
        try {
            val searchPath = path.trimEnd('\\', '/').replace('/', '\\')
            return share.list(searchPath)
                .filter { isDirectory(it) && !isSpecial(it.fileName) }
                .map { it.fileName }
                .sorted()
        } finally {
            share.close()
        }
    }

    /**
     * List all entries (files + folders) at the given path.
     */
    suspend fun listEntries(shareName: String, path: String = ""): List<SmbEntry> = withContext(Dispatchers.IO) {
        try {
            doListEntries(shareName, path)
        } catch (e: Exception) {
            // Force reconnect and retry once
            runCatching { session?.close() }
            runCatching { connection?.close() }
            connection = null
            session = null
            doListEntries(shareName, path)
        }
    }

    private suspend fun doListEntries(shareName: String, path: String): List<SmbEntry> {
        ensureConnected()
        val share = session!!.connectShare(shareName) as DiskShare
        try {
            val searchPath = path.trimEnd('\\', '/').replace('/', '\\')
            return share.list(searchPath)
                .filter { !isSpecial(it.fileName) }
                .map { entry ->
                    SmbEntry(
                        name = entry.fileName,
                        isDirectory = isDirectory(entry),
                        size = entry.endOfFile
                    )
                }
                .sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name })
        } finally {
            share.close()
        }
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client.close() }
    }

    private fun isDirectory(entry: FileIdBothDirectoryInformation): Boolean {
        return (entry.fileAttributes and 0x10L) != 0L
    }

    private fun isSpecial(name: String): Boolean {
        return name == "." || name == ".."
    }

    data class SmbEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long = 0L
    )
}
