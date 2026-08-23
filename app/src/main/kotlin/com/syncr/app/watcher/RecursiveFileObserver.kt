package com.syncr.app.watcher

import android.os.Build
import android.os.FileObserver
import android.util.Log
import com.syncr.app.service.SyncState
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Recursively monitors a directory tree using Linux inotify (via [FileObserver]).
 *
 * Watches are registered on every subdirectory at startup, and auto-registered
 * when new subdirectories are created at runtime.
 *
 * Only two event types are forwarded to [onFileReady]:
 *   - IN_CLOSE_WRITE — file descriptor closed after write (camera flush complete)
 *   - IN_MOVED_TO    — file atomically renamed into the watched directory
 *
 * IN_CREATE and IN_MODIFY are suppressed to avoid emitting events for
 * incomplete or zero-byte files still being written.
 *
 * Watch limit safety: logs a warning and stops registering new subdirectory
 * watches once 90% of inotify_max_user_watches is consumed.
 *
 * Dead-watch cleanup: watches DELETE_SELF events and removes observers for
 * directories that no longer exist.
 *
 * @param rootPath   Absolute path to the top-level source directory.
 * @param onFileReady Callback invoked with the absolute path of a ready-to-sync file.
 *                   Called on the FileObserver event thread — must be non-blocking.
 */
class RecursiveFileObserver(
    private val rootPath: String,
    private val onFileReady: (path: String) -> Unit
) {
    /** path -> active FileObserver for that directory */
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private var maxWatches: Int = DEFAULT_MAX_WATCHES
    @Volatile private var running = false

    init {
        maxWatches = readSystemWatchLimit()
    }

    /** Callback invoked when the root path cannot be watched (permission denied, doesn't exist). */
    var onWatchFailed: ((reason: String) -> Unit)? = null

    fun startWatching() {
        running = true
        Log.i(TAG, "Starting recursive watch on $rootPath (system limit: $maxWatches)")
        val rootDir = File(rootPath)
        if (!rootDir.exists()) {
            val msg = "Source path does not exist: $rootPath"
            Log.e(TAG, msg)
            SyncState.log(SyncState.Level.ERROR, "Watcher", msg,
                "The path was not found on the filesystem. Check the path is correct and that you have granted All Files Access.")
            onWatchFailed?.invoke(msg)
            return
        }
        if (!rootDir.isDirectory) {
            val msg = "Source path is not a directory: $rootPath"
            Log.e(TAG, msg)
            SyncState.log(SyncState.Level.ERROR, "Watcher", msg)
            onWatchFailed?.invoke(msg)
            return
        }
        if (!rootDir.canRead()) {
            val msg = "Cannot read: $rootPath"
            Log.e(TAG, msg)
            val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                "MANAGE_EXTERNAL_STORAGE not granted. Settings → Apps → syncR → All Files Access."
            else
                "READ_EXTERNAL_STORAGE not granted. Restart the app and accept the storage permission dialog."
            SyncState.log(SyncState.Level.ERROR, "Watcher", msg, hint)
            onWatchFailed?.invoke(msg)
            return
        }
        registerDirectory(rootDir)
        if (observers.isEmpty()) {
            val msg = "No watches registered for $rootPath"
            Log.e(TAG, msg)
            SyncState.log(SyncState.Level.ERROR, "Watcher", msg,
                "Filesystem access was denied. Grant All Files Access in app permissions.")
            onWatchFailed?.invoke(msg)
        } else {
            val count = observers.size
            Log.i(TAG, "Watching $rootPath ($count directories)")
        }
    }

    fun stopWatching() {
        running = false
        val count = observers.size
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        Log.i(TAG, "Stopped all inotify watches")
    }

    /** Number of directories currently watched. */
    fun watchCount(): Int = observers.size

    // ─── Private ───────────────────────────────────────────────────────────

    private fun registerDirectory(dir: File) {
        if (!running) return
        if (!dir.isDirectory) return
        val path = dir.absolutePath

        // Watch-limit guard: warn at 90% capacity
        val limit = (maxWatches * 0.90).toInt()
        if (observers.size >= limit) {
            Log.w(TAG, "Approaching inotify limit (${observers.size}/$maxWatches), skipping: $path")
            SyncState.log(SyncState.Level.WARN, "Watcher",
                "Watch limit approaching (${observers.size}/$maxWatches) — skipping $path")
            return
        }

        // Atomic putIfAbsent prevents duplicate registrations from concurrent callbacks
        val observer = buildObserver(path)
        val existing = observers.putIfAbsent(path, observer)
        if (existing != null) {
            // Another thread registered this directory first — discard ours
            return
        }

        observer.startWatching()
        Log.d(TAG, "Watching: $path (total: ${observers.size})")

        // Recursively register existing subdirectories
        dir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { registerDirectory(it) }
    }

    /**
     * Unregister and clean up an observer for a deleted/moved directory.
     */
    private fun unregisterDirectory(dirPath: String) {
        val keysToRemove = observers.keys.filter { it == dirPath || it.startsWith("$dirPath/") }
        for (key in keysToRemove) {
            observers.remove(key)?.stopWatching()
        }
    }

    @Suppress("DEPRECATION")
    private fun buildObserver(dirPath: String): FileObserver {
        // Include DELETE_SELF and MOVE_SELF to clean up dead watches
        val mask = FileObserver.CLOSE_WRITE or
                FileObserver.MOVED_TO or
                FileObserver.CREATE or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(File(dirPath), mask) {
                override fun onEvent(event: Int, relativePath: String?) =
                    handleEvent(event, dirPath, relativePath)
            }
        } else {
            object : FileObserver(dirPath, mask) {
                override fun onEvent(event: Int, relativePath: String?) =
                    handleEvent(event, dirPath, relativePath)
            }
        }
    }

    private fun handleEvent(event: Int, parentPath: String, relativePath: String?) {
        if (!running) return
        // Mask off the IN_ISDIR flag and other upper bits; keep only the event type
        val eventType = event and 0xFFFF

        // Directory self-deletion/move — clean up the dead observer
        if (eventType and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
            unregisterDirectory(parentPath)
            return
        }

        if (relativePath == null) return
        val fullPath = "$parentPath/$relativePath"

        when {
            eventType and FileObserver.CLOSE_WRITE != 0 -> {
                val file = File(fullPath)
                if (file.isFile) {
                    Log.d(TAG, "CLOSE_WRITE: $fullPath")
                    onFileReady(fullPath)
                }
            }
            eventType and FileObserver.MOVED_TO != 0 -> {
                val file = File(fullPath)
                when {
                    file.isFile -> {
                        Log.d(TAG, "MOVED_TO: $fullPath")
                        onFileReady(fullPath)
                    }
                    file.isDirectory -> {
                        registerDirectory(file)
                        // Enqueue existing files inside the moved folder
                        file.walk().filter { it.isFile }.forEach { onFileReady(it.absolutePath) }
                    }
                }
            }
            eventType and FileObserver.CREATE != 0 -> {
                // Only care about new directories — auto-register watch on them
                val file = File(fullPath)
                if (file.isDirectory) {
                    Log.d(TAG, "New subdirectory created: $fullPath")
                    registerDirectory(file)
                }
                // Files at IN_CREATE are still being written; ignore.
            }
        }
    }

    private fun readSystemWatchLimit(): Int = try {
        File("/proc/sys/fs/inotify/max_user_watches").readText().trim().toInt()
    } catch (_: Exception) {
        DEFAULT_MAX_WATCHES
    }

    companion object {
        private const val TAG = "RecursiveFileObserver"
        private const val DEFAULT_MAX_WATCHES = 8192

        fun formatSize(bytes: Long): String = when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
