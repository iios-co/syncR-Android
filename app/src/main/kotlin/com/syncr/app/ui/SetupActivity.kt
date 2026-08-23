package com.syncr.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.syncr.app.data.SyncDirection
import com.syncr.app.ui.screens.*
import com.syncr.app.ui.theme.SyncRTheme
import com.syncr.app.ui.viewmodel.SyncRViewModel

/**
 * Single-activity host. Navigation handled entirely by NavHost/NavController.
 *
 * Route structure (from Modern Android Cookbook — sealed Destination pattern):
 *   permission      → PermissionGateScreen (shown if MANAGE_EXTERNAL_STORAGE not granted)
 *   connections     → ConnectionsScreen   (add/edit/delete SMB servers)
 *   dashboard       → DashboardScreen     (live status + activity)
 *   direction       → DirectionPickerScreen
 *   task/{dir}      → SyncTaskScreen
 */
class SetupActivity : ComponentActivity() {

    private val viewModel: SyncRViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        // Start service if tasks exist (covers app launch after process death)
        if (viewModel.tasks.value.isNotEmpty()) {
            val intent = Intent(this, com.syncr.app.service.SyncService::class.java).apply {
                action = com.syncr.app.service.SyncService.ACTION_RELOAD_CONFIG
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }

        setContent {
            SyncRTheme {
                SyncRNavHost(viewModel)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()

        // Storage: on API 30+ we need MANAGE_EXTERNAL_STORAGE (handled by PermissionGateScreen)
        // On API 28-29, we need READ_EXTERNAL_STORAGE at runtime
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Notifications (API 33+) - Not required, so we just let the OS handle the default prompt if it wants to,
        // but we won't strictly enforce it here because the service can technically run without the UI 
        // notification visible to the user if they choose to deny it.
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        //    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        //    needed.add(Manifest.permission.POST_NOTIFICATIONS)
        // }

        // Location for SSID detection - only ask if any connection has a bound SSID
        val needsLocation = viewModel.connections.value.any { it.ssid.isNotEmpty() }
        if (needsLocation && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }

        // Request Battery Optimization Exemption
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w("SetupActivity", "Could not request battery optimization exemption: ${e.message}")
        }
    }
}

/**
 * Root navigation graph.
 * Start destination is determined by app state:
 *   - No MANAGE_EXTERNAL_STORAGE → permission gate
 *   - No connections → connections screen
 *   - Configured → dashboard
 */
@Composable
fun SyncRNavHost(viewModel: SyncRViewModel) {
    val navController = rememberNavController()
    val connections by viewModel.connections.collectAsState()

    // Determine start destination
    val storageGranted = remember {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }
    val startDest = when {
        !storageGranted -> "permission"
        connections.isEmpty() -> "connections"
        else -> "dashboard"
    }

    NavHost(navController = navController, startDestination = startDest) {

        // ── Permission gate ────────────────────────────────────────────────
        composable("permission") {
            PermissionGateScreen(
                onPermissionGranted = {
                    val dest = if (viewModel.connections.value.isEmpty()) "connections" else "dashboard"
                    navController.navigate(dest) {
                        popUpTo("permission") { inclusive = true }
                    }
                }
            )
        }

        // ── Connections ────────────────────────────────────────────────────
        composable("connections") {
            ConnectionsScreen(
                viewModel = viewModel,
                onConnectionSaved = {
                    navController.navigate("dashboard") {
                        popUpTo("connections") { inclusive = false }
                    }
                }
            )
        }

        // ── Dashboard ──────────────────────────────────────────────────────
        composable("dashboard") {
            DashboardScreen(
                viewModel = viewModel,
                onOpenConnections = { navController.navigate("connections") },
                onCreateTask = { dir -> navController.navigate("task/${dir.name}") },
                onEditTask = { task -> navController.navigate("task/${task.direction.name}/${task.id}") }
            )
        }

        // ── Sync task form (new) ───────────────────────────────────────────
        composable(
            route = "task/{direction}",
            arguments = listOf(navArgument("direction") { type = NavType.StringType })
        ) { backStackEntry ->
            val dir = SyncDirection.valueOf(
                backStackEntry.arguments?.getString("direction") ?: SyncDirection.PHONE_TO_SMB.name
            )
            SyncTaskScreen(
                viewModel = viewModel,
                direction = dir,
                existingTask = null,
                onSaved = {
                    navController.navigate("dashboard") { popUpTo("dashboard") { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
                onBrowseSmb = { connectionId ->
                    navController.navigate("smb_browser/$connectionId")
                }
            )
        }

        // ── Sync task form (edit existing) ────────────────────────────────
        composable(
            route = "task/{direction}/{taskId}",
            arguments = listOf(
                navArgument("direction") { type = NavType.StringType },
                navArgument("taskId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val dir = SyncDirection.valueOf(
                backStackEntry.arguments?.getString("direction") ?: SyncDirection.PHONE_TO_SMB.name
            )
            val taskId = backStackEntry.arguments?.getString("taskId") ?: ""
            val existingTask = viewModel.taskRepo.getById(taskId)
            SyncTaskScreen(
                viewModel = viewModel,
                direction = dir,
                existingTask = existingTask,
                onSaved = {
                    navController.navigate("dashboard") { popUpTo("dashboard") { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
                onBrowseSmb = { connectionId ->
                    navController.navigate("smb_browser/$connectionId")
                }
            )
        }

        // ── SMB Browser ───────────────────────────────────────────────────
        composable(
            route = "smb_browser/{connectionId}",
            arguments = listOf(navArgument("connectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val connId = backStackEntry.arguments?.getString("connectionId") ?: ""
            val conn = viewModel.connectionRepo.getById(connId)
            val password = viewModel.getPassword(connId)
            if (conn != null) {
                SmbBrowserScreen(
                    host = conn.host,
                    port = conn.port,
                    username = conn.username,
                    password = password,
                    domain = conn.domain,
                    initialShare = conn.share,
                    onSelected = { share, path ->
                        viewModel.setBrowseResult(share, path)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
