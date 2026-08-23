package com.syncr.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncr.app.data.SyncDirection
import com.syncr.app.data.SyncTask
import com.syncr.app.service.SyncState
import com.syncr.app.ui.theme.*
import com.syncr.app.ui.viewmodel.SyncRViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: SyncRViewModel,
    onOpenConnections: () -> Unit,
    onCreateTask: (SyncDirection) -> Unit,
    onEditTask: (SyncTask) -> Unit
) {
    val status by viewModel.serviceStatus.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val connections by viewModel.connections.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showMenu = true }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Default.Add, "Add")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Connections") },
                        leadingIcon = { Icon(Icons.Default.Storage, null) },
                        onClick = { showMenu = false; onOpenConnections() }
                    )
                    if (connections.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Phone → SMB") },
                            leadingIcon = { Icon(Icons.Default.CloudUpload, null) },
                            onClick = { showMenu = false; onCreateTask(SyncDirection.PHONE_TO_SMB) }
                        )
                        DropdownMenuItem(
                            text = { Text("SMB → Phone") },
                            leadingIcon = { Icon(Icons.Default.CloudDownload, null) },
                            onClick = { showMenu = false; onCreateTask(SyncDirection.SMB_TO_PHONE) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp)) {

            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Sync, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        if (connections.isEmpty()) {
                            Text("Add a connection to get started", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onOpenConnections) { Text("Add Connection") }
                        } else {
                            Text("No sync tasks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(tasks, key = { it.id }) { task ->
                        val conn = connections.firstOrNull { it.id == task.connectionId }
                        SyncTaskCard(
                            task = task,
                            connectionName = conn?.name ?: conn?.host ?: "?",
                            status = status,
                            onEdit = { onEditTask(task) },
                            onDelete = { viewModel.deleteTask(task.id) },
                            onSyncNow = { viewModel.syncNow(task.id) },
                            onTogglePause = { viewModel.togglePause(task.id) },
                            onClearLedger = { viewModel.clearLedger(task.id) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) } // FAB clearance
                }
            }
        }
    }
}

// ─── Expandable sync task card with inline log ───────────────────────────

@Composable
fun SyncTaskCard(
    task: SyncTask,
    connectionName: String,
    status: SyncState.Status,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSyncNow: () -> Unit,
    onTogglePause: () -> Unit,
    onClearLedger: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val taskStatus = status.taskStatuses[task.id]
    val isSyncing = taskStatus?.syncing == true
    val isPull = task.direction == SyncDirection.SMB_TO_PHONE
    val isWatching = !isPull && status.watchActive && status.watchPath?.split(", ")?.contains(task.localPath) == true
    val taskLogs = taskStatus?.logs ?: emptyList()

    val isPaused = taskStatus?.paused == true
    val hasError = taskStatus?.lastError != null

    val (statusText, statusColor) = when {
        hasError -> "Error" to MaterialTheme.colorScheme.error
        isPaused -> "Paused" to NightWarning
        isSyncing -> "Syncing" to NightAccent
        isWatching -> "Watching" to NightSuccess
        isPull && status.serviceRunning && task.pollIntervalMinutes > 0 -> "Polling" to NightSuccess
        isPull && status.serviceRunning -> "Manual" to MaterialTheme.colorScheme.onSurfaceVariant
        status.serviceRunning -> "Idle" to NightWarning
        else -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    val cardColor = if (hasError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            // Row 1: Name + Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.name.ifBlank { if (isPull) "SMB → Phone" else "Phone → SMB" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(4.dp))
                    Text(statusText, style = MaterialTheme.typography.labelSmall, color = statusColor)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Row 2: Source → Target
            Row(Modifier.fillMaxWidth()) {
                fun formatPath(path: String): String {
                    val trimmed = path.trimEnd('/', '\\')
                    val parts = trimmed.split('/', '\\').filter { it.isNotEmpty() }
                    return if (parts.size >= 2) {
                        "/${parts[parts.size - 2]}/${parts.last()}"
                    } else if (parts.size == 1) {
                        "/${parts.last()}"
                    } else {
                        "/"
                    }
                }

                Column(Modifier.weight(1f)) {
                    Text("Source", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (!isPull) {
                        Text(formatPath(task.localPath.substringAfterLast("/storage/")),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(formatPath("$connectionName/${task.smbRemotePath}"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Target", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (!isPull) {
                        Text(formatPath("$connectionName/${task.smbRemotePath}"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(formatPath(task.localPath.substringAfterLast("/storage/")),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Row 3: Stats + Actions
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSyncing && taskStatus?.currentFile != null) {
                    Text(taskStatus.currentFile, style = MaterialTheme.typography.bodySmall, color = NightAccent, maxLines = 1, modifier = Modifier.weight(1f))
                } else if ((taskStatus?.totalSynced ?: 0) > 0) {
                    Text("${taskStatus!!.totalSynced} synced", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }

                // Pause / Resume
                IconButton(onClick = onTogglePause) {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (isPaused) "Resume" else "Pause",
                        tint = if (isPaused) NightWarning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Sync Now
                IconButton(onClick = onSyncNow) {
                    Icon(Icons.Default.Sync, "Sync Now", tint = NightAccent)
                }
                Spacer(Modifier.width(8.dp))
                // Edit
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit")
                }
                Spacer(Modifier.width(8.dp))
                // Delete
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            // ── Expandable per-task log ────────────────────────────────
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Log", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        IconButton(onClick = { SyncState.clearTaskLogs(task.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.DeleteSweep, "Clear", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (taskLogs.isEmpty()) {
                            Text("No activity yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            taskLogs.takeLast(20).forEach { entry -> LogEntryRow(entry) }
                        }
                    }
                    if (!isPull) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = onClearLedger,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Reset Sync History")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete sync task?") },
            text = { Text("\"${task.name.ifBlank { "Sync task" }}\" will be removed.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

// ─── Error banner ─────────────────────────────────────────────────────────

// ─── Log entry row ────────────────────────────────────────────────────────

@Composable
fun LogEntryRow(entry: SyncState.LogEntry) {
    val levelColor = when (entry.level) {
        SyncState.Level.OK    -> NightSuccess
        SyncState.Level.WARN  -> NightWarning
        SyncState.Level.ERROR -> NightDanger
        SyncState.Level.INFO  -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val levelChar = when (entry.level) {
        SyncState.Level.OK    -> "✓"
        SyncState.Level.WARN  -> "!"
        SyncState.Level.ERROR -> "✕"
        SyncState.Level.INFO  -> "·"
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Text(entry.timeStr(), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1, modifier = Modifier.padding(end = 6.dp))
        Text(levelChar, style = MaterialTheme.typography.labelSmall, color = levelColor, modifier = Modifier.width(14.dp))
        Text(entry.message, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = if (entry.level == SyncState.Level.INFO) MaterialTheme.colorScheme.onSurface else levelColor, modifier = Modifier.weight(1f))
    }
}
