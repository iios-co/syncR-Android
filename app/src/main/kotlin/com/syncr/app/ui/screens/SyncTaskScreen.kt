package com.syncr.app.ui.screens

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.syncr.app.data.SyncDirection
import com.syncr.app.data.SyncTask
import com.syncr.app.ui.viewmodel.SyncRViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncTaskScreen(
    viewModel: SyncRViewModel,
    direction: SyncDirection,
    existingTask: SyncTask? = null,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    onBrowseSmb: (connectionId: String) -> Unit = {}
) {
    val connections by viewModel.connections.collectAsState()
    val focusManager = LocalFocusManager.current

    var taskName            by rememberSaveable { mutableStateOf(existingTask?.name ?: "") }
    var localPath           by rememberSaveable { mutableStateOf(existingTask?.localPath ?: "") }
    var selectedConnectionId by rememberSaveable { mutableStateOf(existingTask?.connectionId ?: connections.firstOrNull()?.id ?: "") }
    var smbShare            by rememberSaveable { mutableStateOf(existingTask?.smbShare ?: connections.firstOrNull()?.share ?: "") }
    var remotePath          by rememberSaveable { mutableStateOf(existingTask?.smbRemotePath ?: "") }
    var connectionExpanded  by rememberSaveable { mutableStateOf(false) }
    var pollIntervalMinutes by rememberSaveable { mutableIntStateOf(existingTask?.pollIntervalMinutes ?: 15) }

    // When connection changes, inherit its share
    val selectedConnection = connections.firstOrNull { it.id == selectedConnectionId }
    LaunchedEffect(selectedConnectionId) {
        val connShare = selectedConnection?.share ?: ""
        if (connShare.isNotBlank() && smbShare.isBlank()) smbShare = connShare
    }

    // Consume browse result from SmbBrowserScreen
    val browseResult by viewModel.browseResult.collectAsState()
    LaunchedEffect(browseResult) {
        browseResult?.let { result ->
            smbShare = result.share
            remotePath = result.path
            viewModel.clearBrowseResult()
        }
    }

    val frName = remember { FocusRequester() }
    val frLocalPath = remember { FocusRequester() }
    val frSsids = remember { FocusRequester() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val path = extractFilesystemPath(uri)
            Log.d("SyncTaskScreen", "SAF URI: $uri → path: $path")
            if (path != null) localPath = path
            else {
                localPath = uri.toString()
                Log.w("SyncTaskScreen", "Could not convert SAF URI to filesystem path: $uri")
            }
        }
    }

    val canSave = localPath.isNotBlank() && selectedConnectionId.isNotBlank() && smbShare.isNotBlank()

    fun save() {
        if (!canSave) return
        focusManager.clearFocus()
        val cleanShare = smbShare.trim('\\', '/', ' ')
        val cleanRemotePath = remotePath.trim('\\', '/', ' ')
        val task = (existingTask ?: SyncTask(
            name = "", direction = direction, localPath = localPath,
            connectionId = selectedConnectionId, smbShare = cleanShare,
            smbRemotePath = cleanRemotePath,
            pollIntervalMinutes = pollIntervalMinutes
        )).copy(
            name = taskName.ifBlank { if (direction == SyncDirection.PHONE_TO_SMB) "Phone → SMB" else "SMB → Phone" },
            localPath = localPath, connectionId = selectedConnectionId,
            smbShare = cleanShare, smbRemotePath = cleanRemotePath,
            pollIntervalMinutes = pollIntervalMinutes
        )
        viewModel.saveTask(task)
        onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (direction == SyncDirection.PHONE_TO_SMB) "Phone → SMB" else "SMB → Phone") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Task name ─────────────────────────────────────────────────
            OutlinedTextField(
                value = taskName, onValueChange = { taskName = it },
                label = { Text("Task Name (optional)") },
                placeholder = { Text(if (direction == SyncDirection.PHONE_TO_SMB) "Camera to SMB" else "SMB to Downloads") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { frLocalPath.requestFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(frName)
            )

            HorizontalDivider()
            Text(if (direction == SyncDirection.PHONE_TO_SMB) "Phone (source)" else "SMB (source)",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            if (direction == SyncDirection.PHONE_TO_SMB) {
                LocalFolderField(
                    localPath, { localPath = it },
                    onBrowse = { focusManager.clearFocus(); folderPicker.launch(null) },
                    imeAction = ImeAction.Done,
                    onNext = { focusManager.clearFocus() },
                    focusRequester = frLocalPath
                )
            } else {
                SmbSelector(
                    connections = connections,
                    selectedId = selectedConnectionId,
                    share = smbShare,
                    remotePath = remotePath,
                    expanded = connectionExpanded,
                    onExpandedChange = { connectionExpanded = it },
                    onConnectionSelected = { selectedConnectionId = it },
                    onShareChange = { smbShare = it },
                    onPathChange = { remotePath = it },
                    onBrowse = { if (selectedConnectionId.isNotBlank()) onBrowseSmb(selectedConnectionId) }
                )
            }

            HorizontalDivider()
            Text(if (direction == SyncDirection.PHONE_TO_SMB) "SMB (target)" else "Phone (target)",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            if (direction == SyncDirection.PHONE_TO_SMB) {
                SmbSelector(
                    connections = connections,
                    selectedId = selectedConnectionId,
                    share = smbShare,
                    remotePath = remotePath,
                    expanded = connectionExpanded,
                    onExpandedChange = { connectionExpanded = it },
                    onConnectionSelected = { selectedConnectionId = it },
                    onShareChange = { smbShare = it },
                    onPathChange = { remotePath = it },
                    onBrowse = { if (selectedConnectionId.isNotBlank()) onBrowseSmb(selectedConnectionId) }
                )
            } else {
                LocalFolderField(
                    localPath, { localPath = it },
                    onBrowse = { focusManager.clearFocus(); folderPicker.launch(null) },
                    imeAction = ImeAction.Done,
                    onNext = { focusManager.clearFocus() },
                    focusRequester = frLocalPath
                )
            }

            // ── Poll interval (pull tasks only) ──────────────────────────
            if (direction == SyncDirection.SMB_TO_PHONE) {
                PollIntervalPicker(
                    minutes = pollIntervalMinutes,
                    onChanged = { pollIntervalMinutes = it }
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = { save() },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text("Save & Start Sync")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── Reusable sub-composables ─────────────────────────────────────────────

@Composable
fun LocalFolderField(
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    imeAction: ImeAction,
    onNext: () -> Unit,
    focusRequester: FocusRequester
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Local folder") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { onNext() },
            onDone = {}
        ),
        trailingIcon = {
            IconButton(onClick = onBrowse) { Icon(Icons.Default.FolderOpen, "Browse") }
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
    )
}

/**
 * Simplified SMB location selector.
 * Connection dropdown + "Browse Server" button + current selection display.
 * Share and path are set exclusively via the SMB browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbSelector(
    connections: List<com.syncr.app.data.SmbConnection>,
    selectedId: String,
    share: String,
    remotePath: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onConnectionSelected: (String) -> Unit,
    onShareChange: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onBrowse: () -> Unit
) {
    val selected = connections.firstOrNull { it.id == selectedId }

    // Connection dropdown
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = selected?.let { it.name.ifBlank { it.host } } ?: "Select connection…",
            onValueChange = {},
            readOnly = true,
            label = { Text("Connection") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            connections.forEach { conn ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(conn.name.ifBlank { conn.host })
                            Text("${conn.host}:${conn.port}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onConnectionSelected(conn.id); onExpandedChange(false) }
                )
            }
        }
    }

    if (selectedId.isNotBlank()) {
        OutlinedTextField(
            value = share,
            onValueChange = onShareChange,
            label = { Text("Share Name") },
            placeholder = { Text("e.g. Apps, multimedia") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = remotePath,
            onValueChange = onPathChange,
            label = { Text("Remote Path (optional)") },
            placeholder = { Text("e.g. folder/subfolder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    // Browse button
    if (selectedId.isNotBlank()) {
        Button(
            onClick = onBrowse,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Browse Server")
        }
    }
}

/**
 * Poll interval input for pull tasks. 0 = manual only (no polling).
 */
@Composable
fun PollIntervalPicker(minutes: Int, onChanged: (Int) -> Unit) {
    OutlinedTextField(
        value = if (minutes == 0) "0" else minutes.toString(),
        onValueChange = { text ->
            val v = text.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
            onChanged(v.coerceIn(0, 9999))
        },
        label = { Text("Poll interval (minutes)") },
        supportingText = { Text("0 = manual only (Sync Now button). Max 9999.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth()
    )
}

// ─── Helpers ─────────────────────────────────────────────────────────────

/**
 * Extract a real filesystem path from a SAF tree URI.
 * Handles:
 *   - ExternalStorageProvider: content://com.android.externalstorage.documents/tree/primary:DCIM
 *   - Samsung My Files: content://com.sec.android.app.myfiles.FileProvider/...
 *   - Downloads provider: content://com.android.providers.downloads.documents/tree/...
 *
 * Uses DocumentsContract.getTreeDocumentId() first (most reliable),
 * then falls back to URL-decode heuristics.
 */
private fun extractFilesystemPath(uri: Uri): String? {
    // Method 1: DocumentsContract (works for all providers that follow the contract)
    try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        // docId format for external storage: "primary:DCIM/Camera" or "primary:Download"
        if (docId != null) {
            // The Downloads provider exposes its root as "downloads" rather than
            // primary:Download, even though it maps to the same filesystem folder.
            if (docId == "downloads" || docId == "Download") {
                return "/storage/emulated/0/Download"
            }
            if (docId.startsWith("primary:")) {
                return "/storage/emulated/0/" + docId.removePrefix("primary:")
            }
            // SD card: "XXXX-XXXX:path"
            val colonIdx = docId.indexOf(':')
            if (colonIdx > 0) {
                val volumeId = docId.substring(0, colonIdx)
                val path = docId.substring(colonIdx + 1)
                // Try common SD card mount points
                val sdPath = "/storage/$volumeId/$path"
                if (java.io.File(sdPath).exists()) return sdPath
                // Fall through to other methods
            }
        }
    } catch (_: Exception) {}

    // Method 2: Parse the URI path directly
    val uriStr = uri.toString()
    val decoded = try { java.net.URLDecoder.decode(uriStr, "UTF-8") } catch (_: Exception) { uriStr }

    if (uri.authority == "com.android.providers.downloads.documents" &&
        (decoded.contains("/tree/downloads") || decoded.contains("/tree/Download"))) {
        return "/storage/emulated/0/Download"
    }

    // ExternalStorageProvider pattern
    val primaryIdx = decoded.indexOf("primary:")
    if (primaryIdx >= 0) {
        return "/storage/emulated/0/" + decoded.substring(primaryIdx + "primary:".length)
    }

    // Samsung My Files pattern: .../external_storage/DCIM or .../storage/emulated/0/DCIM
    val extStorageIdx = decoded.indexOf("/external_storage/")
    if (extStorageIdx >= 0) {
        return "/storage/emulated/0/" + decoded.substring(extStorageIdx + "/external_storage/".length)
    }

    // Direct path in URI (some file managers)
    val storageIdx = decoded.indexOf("/storage/emulated/0/")
    if (storageIdx >= 0) {
        return decoded.substring(storageIdx)
    }

    return null
}

private fun uriToFilesystemPath(uriString: String): String? =
    extractFilesystemPath(Uri.parse(uriString))
