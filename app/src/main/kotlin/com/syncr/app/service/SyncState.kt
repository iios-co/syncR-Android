package com.syncr.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Singleton exposing live sync state to the UI via StateFlow.
 * All log entries are emitted here; the UI collects with collectAsState().
 *
 * Pattern from "Simplifying Android Development with Coroutines and Flows":
 *   private MutableStateFlow → public StateFlow
 *   hot stream updated by service, collected by Compose
 */
object SyncState {

    // ─── Log entry ────────────────────────────────────────────────────────

    enum class Level { INFO, OK, WARN, ERROR }

    data class LogEntry(
        val id: Long = System.nanoTime(),
        val timestamp: Long = System.currentTimeMillis(),
        val level: Level,
        val tag: String,
        val message: String,
        val detail: String? = null
    ) {
        fun timeStr(): String = TIME_FMT.format(Date(timestamp))
    }

    // ─── File sync event ──────────────────────────────────────────────────

    enum class EventStatus { QUEUED, SYNCING, DONE, FAILED }

    data class SyncEvent(
        val path: String,
        val fileName: String,
        val status: EventStatus,
        val timestamp: Long = System.currentTimeMillis(),
        val sizeBytes: Long = 0L,
        val detail: String? = null
    )

    // ─── Per-task status ─────────────────────────────────────────────────

    data class TaskStatus(
        val currentFile: String? = null,
        val currentFileSize: Long = 0L,
        val queueSize: Int = 0,
        val totalSynced: Int = 0,
        val syncing: Boolean = false,
        val paused: Boolean = false,
        val lastError: String? = null,
        val logs: List<LogEntry> = emptyList()
    )

    // ─── Service status ───────────────────────────────────────────────────

    data class Status(
        val serviceRunning: Boolean = false,
        val paused: Boolean = false,
        val networkState: String = "Offline",
        val currentFile: String? = null,
        val currentFileSize: Long = 0L,
        val queueSize: Int = 0,
        val totalSynced: Int = 0,
        val lastError: String? = null,
        val watchActive: Boolean = false,
        val watchPath: String? = null,
        val watchDirCount: Int = 0,
        val smbHost: String? = null,
        val recentFiles: List<SyncEvent> = emptyList(),
        val logs: List<LogEntry> = emptyList(),
        val taskStatuses: Map<String, TaskStatus> = emptyMap()
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // ─── Mutations ────────────────────────────────────────────────────────

    fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    /**
     * Append a log entry and keep the last MAX_LOGS.
     * Called from any thread — MutableStateFlow is thread-safe.
     */
    fun log(level: Level, tag: String, message: String, detail: String? = null) {
        val entry = LogEntry(level = level, tag = tag, message = message, detail = detail)
        _status.value = _status.value.copy(
            logs = (_status.value.logs + entry).takeLast(MAX_LOGS),
            lastError = if (level == Level.ERROR) message else _status.value.lastError
        )
    }

    fun addFileEvent(event: SyncEvent) {
        _status.value = _status.value.copy(
            recentFiles = (listOf(event) + _status.value.recentFiles).take(MAX_FILE_EVENTS)
        )
    }

    fun updateFileEvent(path: String, newStatus: EventStatus, detail: String? = null) {
        _status.value = _status.value.copy(
            recentFiles = _status.value.recentFiles.map {
                if (it.path == path) it.copy(status = newStatus, detail = detail) else it
            }
        )
    }

    fun addEvent(event: SyncEvent) {
        _status.value = _status.value.copy(
            recentFiles = (listOf(event) + _status.value.recentFiles).take(MAX_FILE_EVENTS)
        )
    }

    fun updateEvent(path: String, newStatus: EventStatus, detail: String? = null) {
        _status.value = _status.value.copy(
            recentFiles = _status.value.recentFiles.map {
                if (it.path == path) it.copy(status = newStatus, detail = detail) else it
            }
        )
    }

    fun clearError() {
        _status.value = _status.value.copy(lastError = null)
    }

    /** Update status for a specific task by ID. */
    fun updateTask(taskId: String, transform: (TaskStatus) -> TaskStatus) {
        val current = _status.value
        val existing = current.taskStatuses[taskId] ?: TaskStatus()
        val updated = transform(existing)
        _status.value = current.copy(
            taskStatuses = current.taskStatuses + (taskId to updated)
        )
    }

    /** Get status for a specific task. */
    fun taskStatus(taskId: String): TaskStatus =
        _status.value.taskStatuses[taskId] ?: TaskStatus()

    /**
     * Log to a specific task's log AND the global log (for notification).
     * This is the preferred method — use [log] only for service-level entries.
     */
    fun logTask(taskId: String, level: Level, tag: String, message: String, detail: String? = null) {
        val entry = LogEntry(level = level, tag = tag, message = message, detail = detail)
        val current = _status.value
        val taskStatus = current.taskStatuses[taskId] ?: TaskStatus()
        _status.value = current.copy(
            logs = (current.logs + entry).takeLast(MAX_LOGS),
            lastError = if (level == Level.ERROR) message else current.lastError,
            taskStatuses = current.taskStatuses + (taskId to taskStatus.copy(
                logs = (taskStatus.logs + entry).takeLast(MAX_TASK_LOGS),
                lastError = if (level == Level.ERROR) message else taskStatus.lastError
            ))
        )
    }

    /** Clear logs and error state for a specific task. */
    fun clearTaskLogs(taskId: String) {
        updateTask(taskId) { it.copy(logs = emptyList(), lastError = null) }
    }

    private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val MAX_LOGS = 200
    private const val MAX_TASK_LOGS = 50
    private const val MAX_FILE_EVENTS = 100
}
