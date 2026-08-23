package com.syncr.app.transfer

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.syncr.app.service.SyncState
import com.syncr.app.watcher.RecursiveFileObserver
import com.syncr.app.smb.SmbSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

// ─── Result types ─────────────────────────────────────────────────────────

sealed class TransferResult {
    object Success : TransferResult()
    data class Failure(
        val reason: String,
        /** true = transient; false = permanent (max retries reached or file missing) */
        val retryable: Boolean
    ) : TransferResult()
}

/**
 * Transfers a local file to the configured SMB share using 64KB buffered streaming.
 *
 * After a successful write the remote file size is compared with local [File.length].
 * A mismatch causes the transfer to be re-queued.
 *
 * Retry policy: up to [MAX_RETRIES] attempts per path with exponential back-off
 * (1 s, 2 s, 4 s). After [MAX_RETRIES] failures the entry is removed from the queue
 * (permanent failure — avoids blocking the queue forever on a corrupt file).
 *
 * Remote path: [remoteBasePath]/filename, preserving only the filename (no local
 * directory structure mirroring by default). To mirror subdirs, adjust [buildRemotePath].
 */
class TransferEngine(
    private val sessionManager: SmbSessionManager,
    private val localBasePath: String,
    private val remoteBasePath: String
) {
    /** Attempt counter per local path; cleared on success or permanent failure. */
    private val attempts = ConcurrentHashMap<String, Int>()

    /** Cache of remote directories already verified to exist — avoids repeated folderExists() calls. */
    private val verifiedDirs = ConcurrentHashMap<String, Boolean>()

    /**
     * Transfer [localPath] to the SMB share.
     * This function is safe to call from a coroutine on [Dispatchers.IO].
     */
    suspend fun transfer(localPath: String, taskId: String? = null): TransferResult = withContext(Dispatchers.IO) {
        val localFile = File(localPath)
        if (!localFile.exists() || !localFile.isFile) {
            attempts.remove(localPath)
            return@withContext TransferResult.Failure("File not found", retryable = false)
        }

        val attempt = (attempts[localPath] ?: 0) + 1
        if (attempt > MAX_RETRIES) {
            Log.e(TAG, "Permanent failure: $localPath (exceeded $MAX_RETRIES retries)")
            attempts.remove(localPath)
            return@withContext TransferResult.Failure("Max retries exceeded", retryable = false)
        }
        attempts[localPath] = attempt

        val remotePath = buildRemotePath(localPath)
        Log.d(TAG, "Transfer attempt $attempt/$MAX_RETRIES: $localPath → $remotePath")

        return@withContext try {
            val localSize = localFile.length()
            val share = sessionManager.getShare()

            try {
                uploadFile(share, localFile, remotePath)
            } catch (e: Exception) {
                // If the path doesn't exist, create directories and retry
                ensureRemoteDirectories(share, remotePath)
                uploadFile(share, localFile, remotePath)
            }

            verifyTransfer(share, localSize, remotePath)
            attempts.remove(localPath)

            // Single success log line
            val sizeStr = RecursiveFileObserver.formatSize(localSize)
            if (taskId != null) {
                SyncState.logTask(taskId, SyncState.Level.OK, "Sync", "${localFile.name} → $remotePath ($sizeStr)")
            } else {
                SyncState.log(SyncState.Level.OK, "Sync", "${localFile.name} → $remotePath ($sizeStr)")
            }
            Log.i(TAG, "Transfer OK: $localPath ($localSize bytes)")
            TransferResult.Success
        } catch (e: Exception) {
            val backoffMs = BACKOFF_BASE_MS shl (attempt - 1)
            Log.w(TAG, "Transfer failed (attempt $attempt): ${e.message} — backoff ${backoffMs}ms")
            // Only log to UI on final retry or first failure
            if (attempt >= MAX_RETRIES) {
                val msg = "${localFile.name} failed: ${e.message?.take(60)}"
                if (taskId != null) SyncState.logTask(taskId, SyncState.Level.ERROR, "Sync", msg)
                else SyncState.log(SyncState.Level.ERROR, "Sync", msg)
            }
            SyncState.update { it.copy(currentFile = null) }
            runCatching { sessionManager.close() }
            delay(backoffMs)
            TransferResult.Failure(e.message ?: "Unknown error", retryable = attempt < MAX_RETRIES)
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────

    /** Map local path to remote path, preserving subdirectories relative to localBasePath */
    private fun buildRemotePath(localPath: String): String {
        val base = File(localBasePath).absolutePath
        val file = File(localPath).absolutePath
        
        val relativePath = if (file.startsWith(base)) {
            file.substring(base.length).trimStart('/', '\\')
        } else {
            File(localPath).name
        }

        return if (remoteBasePath.isBlank()) relativePath.replace('/', '\\')
        else "${remoteBasePath.trimEnd('/', '\\')}\\${relativePath.replace('/', '\\')}"
    }

    /**
     * Create all intermediate directories in [remotePath] if they don't exist.
     * Operates on the path segments before the final filename component.
     */
    private fun ensureRemoteDirectories(share: DiskShare, remotePath: String) {
        val normalized = remotePath.replace('/', '\\')
        val segments = normalized.split('\\').dropLast(1)   // directories only
        var current = ""
        for (seg in segments) {
            if (seg.isBlank()) continue
            current = if (current.isEmpty()) seg else "$current\\$seg"
            if (verifiedDirs.containsKey(current)) continue
            try {
                val exists = share.folderExists(current)
                Log.d(TAG, "folderExists('$current') = $exists")
                if (!exists) {
                    Log.i(TAG, "Creating remote dir: $current")
                    share.mkdir(current)
                    Log.i(TAG, "Created remote dir OK: $current")
                }
                verifiedDirs[current] = true
            } catch (e: Exception) {
                Log.w(TAG, "ensureRemoteDirectories('$current') failed: ${e.javaClass.simpleName}: ${e.message}")
                // If the folder already exists but we got an error checking, try to proceed anyway
                SyncState.log(SyncState.Level.WARN, "Transfer",
                    "Dir check/create issue: $current",
                    "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Open/overwrite remote file and stream [localFile] in 64KB chunks.
     * Uses smbj's OutputStream so SMB2 write request sizing is handled by the library.
     */
    private fun uploadFile(share: DiskShare, localFile: File, remotePath: String) {
        Log.d(TAG, "openFile('$remotePath') with FILE_WRITE_DATA | FILE_WRITE_ATTRIBUTES, FILE_OVERWRITE_IF")
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_WRITE_EA),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.noneOf(SMB2CreateOptions::class.java)
        ).use { smbFile ->
            Log.d(TAG, "File opened OK, streaming ${localFile.length()} bytes...")
            smbFile.outputStream.use { out ->
                localFile.inputStream().use { input ->
                    input.copyTo(out, bufferSize = CHUNK_SIZE)
                }
            }
            Log.d(TAG, "Stream complete: $remotePath")
        }
    }

    /**
     * Verify the transfer by comparing remote [FileStandardInformation.endOfFile]
     * with the pre-captured [expectedSize]. Throws if they diverge.
     */
    private fun verifyTransfer(share: DiskShare, expectedSize: Long, remotePath: String) {
        // Re-open in read mode to query size; ensures the write was flushed
        share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.noneOf(FileAttributes::class.java),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java)
        ).use { smbFile ->
            val remoteSize = smbFile.getFileInformation(FileStandardInformation::class.java).endOfFile
            if (expectedSize != remoteSize) {
                throw IllegalStateException(
                    "Size mismatch: expected=$expectedSize bytes, remote=$remoteSize bytes"
                )
            }
        }
        Log.d(TAG, "Verification OK: $remotePath ($expectedSize bytes)")
    }

    companion object {
        private const val TAG = "TransferEngine"
        private const val CHUNK_SIZE = 1024 * 1024       // 1MB
        private const val MAX_RETRIES = 3
        private const val BACKOFF_BASE_MS = 1_000L      // 1s, 2s, 4s
    }
}
