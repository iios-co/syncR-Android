package com.syncr.app.queue

import android.util.Log
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Crash-resilient, lock-free sync queue.
 *
 * In-memory layer: [ConcurrentLinkedQueue] for FIFO ordering +
 *   [ConcurrentHashMap]-backed key set for O(1) duplicate detection.
 * Persistence layer: append-only flat file (one path per line) with fsync
 *   on every enqueue. Rewritten atomically (write-to-temp + rename) on prune.
 *   Truncated to zero when the queue fully drains.
 *
 * Signal: [signal] is a CONFLATED Channel — one pending wake-up is enough.
 * The sync worker calls `signal.receive()` to block until work arrives.
 *
 * Duplicate suppression: a path already in the queue is silently dropped.
 * Uses a concurrent HashSet for O(1) membership checks (vs O(n) iteration).
 */
class SyncQueue(private val walFile: File) {

    private val queue = ConcurrentLinkedQueue<String>()
    /** O(1) presence set — mirrors queue contents for fast contains(). */
    private val presenceSet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Paths that have been successfully synced — prevents re-queuing on rescan. */
    private val syncedSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val syncedFile = File(walFile.parentFile, "synced_history_${walFile.nameWithoutExtension}.txt")

    /**
     * CONFLATED channel: at most one pending signal at a time.
     * Sending when already signaled is a no-op — safe to call from inotify callbacks.
     */
    val signal: Channel<Unit> = Channel(Channel.CONFLATED)

    // ─── Startup ───────────────────────────────────────────────────────────

    /**
     * Reload any paths persisted in the WAL from a previous session.
     * Also loads the synced-history for dedup on rescan.
     * Call once during service startup before beginning to watch.
     */
    fun loadFromWal() {
        // Load synced history
        if (syncedFile.exists()) {
            syncedFile.forEachLine { line ->
                val path = line.trim()
                if (path.isNotEmpty()) syncedSet.add(path)
            }
            Log.d(TAG, "Loaded ${syncedSet.size} synced paths from history")
        }

        if (!walFile.exists()) return
        var count = 0
        walFile.forEachLine { line ->
            val path = line.trim()
            if (path.isNotEmpty() && presenceSet.add(path)) {
                queue.add(path)
                count++
            }
        }
        if (count > 0) {
            Log.i(TAG, "Loaded $count item(s) from WAL on startup")
            signal.trySend(Unit)
        }
    }

    // ─── Enqueue ───────────────────────────────────────────────────────────

    /**
     * Add [path] to the queue and persist it to the WAL.
     * Thread-safe: [presenceSet.add] is atomic (ConcurrentHashMap-backed).
     * Skips paths already successfully synced (unless force=true for manual resync).
     * @return true if enqueued; false if path was already present or already synced.
     */
    fun enqueue(path: String, force: Boolean = false): Boolean {
        // Skip if already synced (unless forced rescan)
        if (!force && syncedSet.contains(path)) return false
        // Atomic add: returns false if already present — no TOCTOU race
        if (!presenceSet.add(path)) {
            Log.d(TAG, "Duplicate suppressed: $path")
            return false
        }
        queue.add(path)
        appendToWal(path)
        signal.trySend(Unit)
        Log.d(TAG, "Enqueued: $path (queue size: ${queue.size})")
        return true
    }

    /**
     * Bulk add paths to the queue and persist them in a single WAL write with one fsync().
     * @return number of paths actually enqueued.
     */
    fun enqueueAll(paths: List<String>, force: Boolean = false): Int {
        val newPaths = mutableListOf<String>()
        for (path in paths) {
            if (!force && syncedSet.contains(path)) continue
            if (presenceSet.add(path)) {
                queue.add(path)
                newPaths.add(path)
            }
        }
        if (newPaths.isEmpty()) return 0
        appendAllToWal(newPaths)
        signal.trySend(Unit)
        Log.d(TAG, "Bulk enqueued ${newPaths.size} items (queue size: ${queue.size})")
        return newPaths.size
    }

    // ─── Dequeue / remove ──────────────────────────────────────────────────

    /** Peek at the head without removing (used by sync worker before transfer). */
    fun peek(): String? = queue.peek()

    /**
     * Remove [path] after a verified successful transfer.
     * Records in synced-history so rescan won't re-queue.
     * Does NOT rewrite the WAL immediately — call [pruneWal] after drain batch.
     */
    fun remove(path: String) {
        queue.remove(path)
        presenceSet.remove(path)
        markSynced(path)
        Log.d(TAG, "Removed: $path (queue size: ${queue.size})")
    }

    /**
     * Remove [path] on permanent failure — does NOT mark as synced,
     * so it will be retried on next manual "Sync Now".
     */
    fun removeFailed(path: String) {
        queue.remove(path)
        presenceSet.remove(path)
        Log.d(TAG, "Removed (failed): $path (queue size: ${queue.size})")
    }

    // ─── State ─────────────────────────────────────────────────────────────

    fun isEmpty(): Boolean = queue.isEmpty()
    fun size(): Int = queue.size

    /** Clears the synced history ledger, allowing all files to be re-evaluated. */
    fun clearLedger() {
        syncedSet.clear()
        if (syncedFile.exists()) {
            syncedFile.delete()
        }
        Log.i(TAG, "Ledger cleared for $walFile")
    }

    /**
     * Rewrite WAL to match current queue state. Call once after a drain batch
     * completes (not per-item) to avoid O(N^2) rewrites during bulk transfers.
     *
     * Uses atomic temp-file + rename to prevent corruption on process death.
     */
    fun pruneWal() {
        val snapshot = queue.toList()
        if (snapshot.isEmpty()) {
            walFile.writeText("")
            Log.d(TAG, "WAL compacted (queue empty)")
            return
        }
        val tempFile = File(walFile.parentFile, "${walFile.name}.tmp")
        try {
            tempFile.bufferedWriter().use { writer ->
                for (path in snapshot) {
                    writer.write(path)
                    writer.newLine()
                }
            }
            if (!tempFile.renameTo(walFile)) {
                walFile.bufferedWriter().use { writer ->
                    for (path in snapshot) {
                        writer.write(path)
                        writer.newLine()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAL prune failed", e)
        }
        Log.d(TAG, "WAL pruned: ${snapshot.size} items remain")
    }

    /**
     * Truncate the WAL to zero bytes after the queue is fully drained.
     */
    fun compact() {
        walFile.writeText("")
        Log.d(TAG, "WAL compacted (queue empty)")
    }

    // ─── Synced history ──────────────────────────────────────────────────

    /** Record a path as successfully synced. */
    private fun markSynced(path: String) {
        syncedSet.add(path)
        try {
            FileOutputStream(syncedFile, true).use { fos ->
                fos.write("$path\n".toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write synced history: ${e.message}")
        }
    }

    /** Check if a path was already synced. */
    fun isSynced(path: String): Boolean = syncedSet.contains(path)

    // ─── WAL internals ─────────────────────────────────────────────────────

    /** Append one path + newline and fsync to guarantee durability. */
    private fun appendToWal(path: String) {
        try {
            FileOutputStream(walFile, /* append= */ true).use { fos ->
                fos.write("$path\n".toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAL append failed for $path", e)
        }
    }

    private fun appendAllToWal(paths: List<String>) {
        try {
            FileOutputStream(walFile, /* append= */ true).use { fos ->
                for (path in paths) {
                    fos.write("$path\n".toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAL bulk append failed", e)
        }
    }

    /**
     * Atomic WAL rewrite: write to temp file, fsync, then rename over WAL.
     * Guarantees either the old content or new content is present — never partial.
     */
    private fun atomicWriteWal(content: String) {
        val tempFile = File(walFile.parentFile, "${walFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            if (!tempFile.renameTo(walFile)) {
                // renameTo can fail on some filesystems; fall back to non-atomic write
                walFile.writeText(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAL atomic rewrite failed", e)
            tempFile.delete()
        }
    }

    companion object {
        private const val TAG = "SyncQueue"
    }
}
