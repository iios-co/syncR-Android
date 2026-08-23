package com.syncr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncr.app.data.SyncDirection
import com.syncr.app.ui.theme.NightAccent
import com.syncr.app.ui.theme.NightPrimary

/**
 * Direction picker — shown when the user taps "New sync task".
 * Phone → SMB  or  SMB → Phone, using icons.
 */
@Composable
fun DirectionPickerScreen(
    onDirectionSelected: (SyncDirection) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("New Sync Task", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Which direction should files be synced?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(40.dp))

            // Phone → SMB
            DirectionCard(
                icon1 = { Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(36.dp), tint = NightPrimary) },
                icon2 = { Icon(Icons.Default.Storage, null, modifier = Modifier.size(36.dp), tint = NightAccent) },
                label = "Phone → SMB",
                description = "Watch a local folder and upload new files to your SMB share",
                onClick = { onDirectionSelected(SyncDirection.PHONE_TO_SMB) }
            )

            Spacer(Modifier.height(16.dp))

            // SMB → Phone
            DirectionCard(
                icon1 = { Icon(Icons.Default.Storage, null, modifier = Modifier.size(36.dp), tint = NightAccent) },
                icon2 = { Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(36.dp), tint = NightPrimary) },
                label = "SMB → Phone",
                description = "Pull files from your SMB share to a local folder on the phone",
                onClick = { onDirectionSelected(SyncDirection.SMB_TO_PHONE) }
            )
        }
    }
}

@Composable
fun DirectionCard(
    icon1: @Composable () -> Unit,
    icon2: @Composable () -> Unit,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon1()
            Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            icon2()
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
