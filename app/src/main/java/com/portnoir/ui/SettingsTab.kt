package com.portnoir.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portnoir.PortNoirApp

enum class SettingsItem(val key: String, val title: String, val icon: ImageVector) {
    DEVICE_NAME("device_name", "Device Name", Icons.Outlined.Badge),
    FEATURES("features", "Features and Permissions", Icons.Outlined.AdminPanelSettings),
    ADVANCED("advanced", "Advanced", Icons.Outlined.Tune),
    ABOUT("about", "About", Icons.Outlined.Info),
    LICENCES("licences", "Licences", Icons.Outlined.Description),
}

@Composable
fun SettingsTab(onOpenSetting: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PortNoirApp
    val deviceName by app.repo.deviceName.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
        SettingsItem.entries.forEach { item ->
            ListItem(
                modifier = Modifier.clickable {
                    if (item == SettingsItem.DEVICE_NAME) editing = true else onOpenSetting(item.key)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = { Icon(item.icon, contentDescription = null) },
                headlineContent = { Text(item.title) },
                supportingContent = if (item == SettingsItem.DEVICE_NAME) ({ Text(deviceName) }) else null,
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            )
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(deviceName) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Device name") },
            text = {
                Column {
                    Text("This is how your phone appears on your Mac.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { app.repo.setDeviceName(text); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}
