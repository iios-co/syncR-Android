package com.syncr.app.transfer

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.syncr.app.service.SyncState
import com.syncr.app.smb.SmbSessionManager
import com.syncr.app.watcher.RecursiveFileObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads files from an SMB share to local storage (SMB → Phone).
 *
 * Features:
 *   - Recursive folder scanning (mirrors remote directory structure locally)
 *   - Skips files that match locally by name + size + modification time
 *   - Tracks synced files in a history map (remotePath → size:mtime)
 *   - Clean logging: one line per file, batch summary at end
 *   - Retry with exponential backoff (3 attempts per file)
 */
class PullEngine(
    private val remoteBasePath: String,
    private val localBasePath: String,
    private val taskId: String? = null
) {
    private val attempts = ConcurrentHashMap<String, Int>()

    /** remotePath → "size:lastWriteTime" for dedup across polls */
    private val syncedMap = ConcurrentHashMap<String, String>()

    /**
     * Scan the remote directory recursively and download new/changed files.
     * @return number of files successfully downloaded.
     */
    suspend fun pullNewFiles(sessionManager: SmbSessionManager): Int = withContext(Dispatchers.IO) {
        val share = sessionManager.getShare()

        // Ensure local root exists
        val localDir = File(localBasePath)
        if (!localDir.exists()) localDir.mkdirs()

        // Recursively collect remote files
        val remoteFiles = mutableListOf<RemoteFileEntry>()
        collectRemoteFiles(share, remoteBasePath, "", remoteFiles)

        // Prevent memory leak by removing deleted remote files from history
        val seenPaths = remoteFiles.map { it.remotePath }.toSet()
        syncedMap.keys.retainAll(seenPaths)

        if (remoteFiles.isEmpty()) {
            Log.d(TAG, "No files found in remote path: $remoteBasePath")
            return@withContext 0
        }

        var downloaded = 0
        var failed = 0
        var totalBytes = 0L

        for (entry in remoteFiles) {
            val localFile = File(localDir, entry.relativePath)

            // Skip if already synced with same fingerprint
            val fingerprint = "${entry.size}:${entry.lastWriteTime}"
            val prevFingerprint = syncedMap[entry.remotePath]
            
            val alreadySynced = (prevFingerprint == fingerprint && localFile.exists() && localFile.length() == entry.size)
            val alreadyExists = (prevFingerprint == null && localFile.exists() && localFile.length() == entry.size)

            if (alreadySynced || alreadyExists) {
                if (prevFingerprint == null) {
                    syncedMap[entry.remotePath] = fingerprint
                }
                continue
            }

            // Ensure local subdirectory exists
            localFile.parentFile?.mkdirs()

            when (val result = downloadFile(share, entry.remotePath, localFile, entry.size)) {
                is TransferResult.Success -> {
                    downloaded++
                    totalBytes += entry.size
                    syncedMap[entry.remotePath] = fingerprint
                    attempts.remove(entry.remotePath)
                    val sizeStr = RecursiveFileObserver.formatSize(entry.size)
                    logEntry(SyncState.Level.OK, "${entry.relativePath} ← SMB ($sizeStr)")
                }
                is TransferResult.Failure -> {
                    if (!result.retryable) {
                        failed++
                        attempts.remove(entry.remotePath)
                        logEntry(SyncState.Level.ERROR, "${entry.relativePath} failed: ${result.reason}")
                    }
                }
            }
        }

        // Batch summary
        if (downloaded > 0 || failed > 0) {
            val totalStr = RecursiveFileObserver.formatSize(totalBytes)
            if (failed == 0) {
                logEntry(SyncState.Level.OK, "Downloaded $downloaded file${if (downloaded > 1) "s" else ""} ($totalStr)")
            } else {
                logEntry(SyncState.Level.WARN, "Downloaded $downloaded, failed $failed")
            }
        }

        downloaded
    }

    /**
     * Recursively list files in the remote directory tree.
     * @param basePath absolute remote path (e.g. "Photos")
     * @param relativePath path relative to basePath (for local mirroring)
     */
    private fun collectRemoteFiles(
        share: DiskShare,
        basePath: String,
        relativePath: String,
        out: MutableList<RemoteFileEntry>
    ) {
        val remotePath = if (relativePath.isBlank()) basePath
            else "${basePath.trimEnd('\\')}\\$relativePath"
        val searchPath = remotePath.trimEnd('\\', '/').replace('/', '\\')

        try {
            val entries = share.list(searchPath)
            for (entry in entries) {
                if (isSpecial(entry.fileName)) continue

                val childRelative = if (relativePath.isBlank()) entry.fileName
                    else "$relativePath/${entry.fileName}"

                if (isDirectory(entry)) {
                    collectRemoteFiles(share, basePath, childRelative, out)
                } else {
                    val fullRemote = "${searchPath}\\${entry.fileName}"
                    out.add(RemoteFileEntry(
                        remotePath = fullRemote,
                        relativePath = childRelative,
                        name = entry.fileName,
                        size = entry.endOfFile,
                        lastWriteTime = entry.lastWriteTime.toEpochMillis()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list '$searchPath': ${e.message}")
            logEntry(SyncState.Level.ERROR, "Cannot list: $searchPath")
        }
    }

    private suspend fun downloadFile(
        share: DiskShare,
        remotePath: String,
        localFile: File,
        expectedSize: Long
    ): TransferResult = withContext(Dispatchers.IO) {
        val attempt = (attempts[remotePath] ?: 0) + 1
        if (attempt > MAX_RETRIES) {
            attempts.remove(remotePath)
            return@withContext TransferResult.Failure("Max retries exceeded", retryable = false)
        }
        attempts[remotePath] = attempt

        Log.d(TAG, "Download attempt $attempt/$MAX_RETRIES: $remotePath")

        try {
            share.openFile(
                remotePath,
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.noneOf(FileAttributes::class.java),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java)
            ).use { smbFile ->
                val tempFile = File(localFile.parentFile, ".${localFile.name}.tmp")
                var transferSuccess = false
                try {
                    FileOutputStream(tempFile).use { fos ->
                        smbFile.inputStream.copyTo(fos, bufferSize = CHUNK_SIZE)
                        fos.fd.sync()
                    }

                    if (tempFile.length() != expectedSize) {
                        throw IllegalStateException(
                            "Size mismatch: expected=$expectedSize, got=${tempFile.length()}"
                        )
                    }

                    if (localFile.exists()) localFile.delete()
                    if (!tempFile.renameTo(localFile)) {
                        tempFile.copyTo(localFile, overwrite = true)
                        tempFile.delete()
                    }
                    transferSuccess = true
                } finally {
                    if (!transferSuccess && tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            }

            Log.i(TAG, "Download OK: $remotePath ($expectedSize bytes)")
            TransferResult.Success
        } catch (e: Exception) {
            val backoffMs = BACKOFF_BASE_MS shl (attempt - 1)
            Log.w(TAG, "Download failed (attempt $attempt): ${e.message} — backoff ${backoffMs}ms")
            if (attempt >= MAX_RETRIES) {
                logEntry(SyncState.Level.ERROR, "${localFile.name} download failed: ${e.message?.take(60)}")
            }
            delay(backoffMs)
            TransferResult.Failure(e.message ?: "Unknown error", retryable = attempt < MAX_RETRIES)
        }
    }

    private fun isDirectory(entry: FileIdBothDirectoryInformation): Boolean =
        (entry.fileAttributes and 0x10L) != 0L

    private fun isSpecial(name: String): Boolean =
        name == "." || name == ".."

    private fun logEntry(level: SyncState.Level, message: String) {
        if (taskId != null) SyncState.logTask(taskId, level, "Sync", message)
        else SyncState.log(level, "Sync", message)
    }

    data class RemoteFileEntry(
        val remotePath: String,
        val relativePath: String,
        val name: String,
        val size: Long,
        val lastWriteTime: Long
    )

    companion object {
        private const val TAG = "PullEngine"
        private const val CHUNK_SIZE = 1024 * 1024
        private const val MAX_RETRIES = 3
        private const val BACKOFF_BASE_MS = 1_000L
    }
}
