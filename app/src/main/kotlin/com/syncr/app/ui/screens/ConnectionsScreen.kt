package com.syncr.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.syncr.app.data.SmbConnection
import com.syncr.app.ui.theme.*
import com.syncr.app.ui.viewmodel.SyncRViewModel
import com.syncr.app.ui.viewmodel.TestResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    viewModel: SyncRViewModel,
    onConnectionSaved: () -> Unit
) {
    val connections by viewModel.connections.collectAsState()
    val context = LocalContext.current
    var showForm by remember { mutableStateOf(connections.isEmpty()) }
    var editing by remember { mutableStateOf<SmbConnection?>(null) }
    var pendingConnectionTest by remember { mutableStateOf<(() -> Unit)?>(null) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val test = pendingConnectionTest
        pendingConnectionTest = null
        if (granted) test?.invoke()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMB Connections") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    if (!showForm) {
                        IconButton(onClick = { editing = null; viewModel.resetTestResult(); showForm = true }) {
                            Icon(Icons.Default.Add, "Add connection")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            if (showForm) {
                ConnectionForm(
                    initial = editing,
                    lastConnection = connections.lastOrNull(),
                    existingPassword = editing?.let { viewModel.getPassword(it.id) } ?: "",
                    testResult = viewModel.testResult.collectAsState().value,
                    onTest = { host, port, user, pass, domain, share ->
                        val testConnection = {
                            val cleanHost = host.trim('\\', '/', ' ')
                            val cleanShare = share.trim('\\', '/', ' ')
                            val conn = (editing ?: SmbConnection(
                                name = "", host = cleanHost, port = port,
                                username = user, domain = domain, share = cleanShare
                            )).copy(
                                host = cleanHost, port = port, username = user,
                                domain = domain, share = cleanShare
                            )
                            viewModel.testAndSaveConnection(conn, pass)
                        }
                        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            testConnection()
                        } else {
                            pendingConnectionTest = testConnection
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    },
                    onSave = { conn, password ->
                        showForm = false
                        editing = null
                        viewModel.resetTestResult()
                        if (viewModel.connections.value.isNotEmpty()) onConnectionSaved()
                    },
                    onCancel = { showForm = false; editing = null; viewModel.resetTestResult() }
                )
            } else {
                if (connections.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Cloud, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Text("No connections yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { showForm = true }) { Text("Add Connection") }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(connections, key = { it.id }) { conn ->
                            ConnectionCard(
                                connection = conn,
                                onEdit = { editing = conn; showForm = true },
                                onDelete = { viewModel.deleteConnection(conn.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionCard(
    connection: SmbConnection,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(connection.name.ifBlank { connection.host }, style = MaterialTheme.typography.titleMedium)
                Text("${connection.host}:${connection.port} / ${connection.share}  ·  ${connection.username}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete connection?") },
            text = { Text("\"${connection.name.ifBlank { connection.host }}\" will be removed.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Connection form with full keyboard Next/Done traversal.
 * Tab order: Name → Host → Port → Share → Username → Password → Domain → [Test / Save]
 */
@Composable
fun ConnectionForm(
    initial: SmbConnection?,
    lastConnection: SmbConnection?,
    existingPassword: String,
    testResult: TestResult,
    onTest: (String, Int, String, String, String, String) -> Unit,  // host, port, user, pass, domain, share
    onSave: (SmbConnection, String) -> Unit,
    onCancel: () -> Unit
) {
    var name     by remember { mutableStateOf(initial?.name ?: "") }
    var host     by remember { mutableStateOf(initial?.host ?: lastConnection?.host ?: "") }
    var port     by remember { mutableStateOf((initial?.port ?: lastConnection?.port ?: 445).toString()) }
    var share    by remember { mutableStateOf(initial?.share ?: "") }
    var user     by remember { mutableStateOf(initial?.username ?: lastConnection?.username ?: "") }
    var password by remember { mutableStateOf(existingPassword) }
    var domain   by remember { mutableStateOf(initial?.domain ?: lastConnection?.domain ?: "") }
    var pwVisible by remember { mutableStateOf(false) }

    // One FocusRequester per field
    val focusRequesters = remember { List(7) { FocusRequester() } }
    val (frName, frHost, frPort, frShare, frUser) = focusRequesters
    val frPass   = focusRequesters[5]
    val frDomain = focusRequesters[6]

    val focusManager = LocalFocusManager.current

    val canSave = host.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    fun save() {
        if (!canSave) return
        focusManager.clearFocus()
        onTest(host, port.toIntOrNull() ?: 445, user, password, domain, share)
    }

    LaunchedEffect(testResult) {
        if (testResult is TestResult.Success) {
            val cleanHost = host.trim('\\', '/', ' ')
            val cleanShare = share.trim('\\', '/', ' ')
            val conn = (initial ?: SmbConnection(
                name = name, host = cleanHost, port = port.toIntOrNull() ?: 445,
                username = user, domain = domain, share = cleanShare
            )).copy(
                name = name.ifBlank { cleanHost }, host = cleanHost,
                port = port.toIntOrNull() ?: 445,
                username = user, domain = domain, share = cleanShare
            )
            onSave(conn, password)
        }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (initial == null) "Add Connection" else "Edit Connection",
            style = MaterialTheme.typography.titleLarge
        )

        // ── Name ──────────────────────────────────────────────────────────
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Display Name") },
            placeholder = { Text("Home SMB") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { frHost.requestFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(frName)
        )

        // ── Host ──────────────────────────────────────────────────────────
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Host / IP") },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { frPort.requestFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(frHost)
        )

        // ── Port + Share ───────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port, onValueChange = { port = it },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { frShare.requestFocus() }),
                modifier = Modifier.width(100.dp).focusRequester(frPort)
            )
            OutlinedTextField(
                value = share, onValueChange = { share = it },
                label = { Text("Share") },
                placeholder = { Text("Multimedia") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { frUser.requestFocus() }),
                modifier = Modifier.weight(1f).focusRequester(frShare)
            )
        }

        // ── Username ──────────────────────────────────────────────────────
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { frPass.requestFocus() }),
            modifier = Modifier.fillMaxWidth().focusRequester(frUser)
        )

        // ── Password ──────────────────────────────────────────────────────
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { frDomain.requestFocus() }),
            trailingIcon = {
                IconButton({ pwVisible = !pwVisible }) {
                    Icon(if (pwVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(frPass)
        )

        // ── Domain ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = domain, onValueChange = { domain = it },
            label = { Text("Domain (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                // Trigger test if all required fields filled, otherwise just dismiss kb
                if (canSave) onTest(host, port.toIntOrNull() ?: 445, user, password, domain, share)
            }),
            modifier = Modifier.fillMaxWidth().focusRequester(frDomain)
        )

        // ── Feedback ──────────────────────────────────────────────────────
        when (testResult) {
            is TestResult.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = NightSuccess, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connected successfully", color = NightSuccess, style = MaterialTheme.typography.bodyMedium)
            }
            is TestResult.Failure -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(testResult.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            else -> {}
        }

        // ── Buttons ───────────────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Cancel")
            }
            Button(
                onClick = { save() },
                enabled = canSave && testResult !is TestResult.Testing,
                modifier = Modifier.weight(1f)
            ) {
                if (testResult is TestResult.Testing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Testing…")
                } else {
                    Text("Save")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
