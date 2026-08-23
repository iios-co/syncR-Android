package com.syncr.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncr.app.smb.SmbBrowser
import kotlinx.coroutines.launch

/**
 * Full-screen SMB folder browser.
 * Connects to the specified server, lets the user navigate into folders,
 * and returns the selected share + path.
 *
 * @param host SMB server hostname/IP
 * @param port SMB port (usually 445)
 * @param username SMB username
 * @param password SMB password
 * @param domain SMB domain (can be blank)
 * @param initialShare Pre-selected share (if known). Empty = show share input first.
 * @param initialPath Pre-selected path within the share.
 * @param onSelected Callback with (share, path) when user confirms selection.
 * @param onBack Cancel / navigate back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbBrowserScreen(
    host: String,
    port: Int,
    username: String,
    password: String,
    domain: String,
    initialShare: String = "",
    initialPath: String = "",
    onSelected: (share: String, path: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // State
    var share by remember { mutableStateOf(initialShare) }
    var currentPath by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<SmbBrowser.SmbEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var browser by remember { mutableStateOf<SmbBrowser?>(null) }

    // Path segments for breadcrumb
    val pathSegments = remember(currentPath) {
        if (currentPath.isBlank()) emptyList()
        else currentPath.replace('/', '\\').split('\\').filter { it.isNotBlank() }
    }

    // Connect and browse share root on first composition
    LaunchedEffect(Unit) {
        loading = true
        error = null
        try {
            val b = SmbBrowser(host, port, username, password, domain)
            b.connect()
            browser = b
            if (share.isNotBlank()) {
                entries = b.listEntries(share, currentPath)
            }
        } catch (e: Exception) {
            error = e.message ?: "Connection failed: ${e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose { browser?.close() }
    }

    fun navigateInto(folderName: String) {
        scope.launch {
            loading = true
            error = null
            val newPath = if (currentPath.isBlank()) folderName else "$currentPath\\$folderName"
            try {
                entries = browser!!.listEntries(share, newPath)
                currentPath = newPath
            } catch (e: Exception) {
                error = "Cannot open '$folderName': ${e.message?.take(60)}"
            } finally {
                loading = false
            }
        }
    }

    fun navigateUp() {
        if (pathSegments.isEmpty()) return  // Already at share root
        val parentPath = pathSegments.dropLast(1).joinToString("\\")
        scope.launch {
            loading = true
            error = null
            try {
                entries = browser!!.listEntries(share, parentPath)
                currentPath = parentPath
            } catch (e: Exception) {
                error = "Navigation failed: ${e.message?.take(60)}"
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (share.isNotBlank()) {
                        Button(
                            onClick = { onSelected(share, currentPath) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Select")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            // ── Breadcrumb ────────────────────────────────────────────
            if (share.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Up button
                        IconButton(onClick = { navigateUp() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.ArrowBack, "Up", modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        // Path display
                        Text(
                            text = "\\\\$host\\$share" + (if (currentPath.isNotBlank()) "\\$currentPath" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Error ─────────────────────────────────────────────────
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Loading ───────────────────────────────────────────────
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            // ── Folder/file listing ───────────────────────────────────
            if (entries.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { it.name }) { entry ->
                        SmbEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) navigateInto(entry.name)
                            }
                        )
                    }
                }
            } else if (!loading && entries.isEmpty() && error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SmbEntryRow(entry: SmbBrowser.SmbEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDirectory, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        if (!entry.isDirectory && entry.size > 0) {
            Text(
                formatSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.isDirectory) {
            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}
