package com.portnoir.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.portnoir.PortNoirApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding -> Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), content = content) }
}

@Composable
private fun Row2(title: String, subtitle: String) = ListItem(
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    headlineContent = { Text(title) },
    supportingContent = { Text(subtitle) },
)

@Composable
fun ChatHistoryScreen(macId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PortNoirApp
    val name = app.repo.mac(macId)?.name ?: "Mac"
    DetailScaffold(name, onBack) {
        Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) {
            Text("Messages and files shared with $name will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsDetailScreen(key: String, onBack: () -> Unit) {
    val item = SettingsItem.entries.firstOrNull { it.key == key }
    DetailScaffold(item?.title ?: "Settings", onBack) {
        when (item) {
            SettingsItem.FEATURES -> {
                Row2("Port", "Chat and file sharing. Needs no extra permission.")
                Row2("Pictures", "Photo access is requested when this feature ships.")
                Row2("SMS", "Optional. Needs SMS permissions.")
                Row2("Calls", "Optional. Needs phone permissions. Call audio stays on the phone.")
                Row2("Camera", "Used only to scan the pairing code.")
                Row2("Notifications", "Shows that PortNoir is keeping your Mac connected.")
            }
            SettingsItem.ADVANCED -> {
                Row2("Battery optimisation", "Exempting PortNoir keeps the connection alive in the background. Coming soon.")
                Row2("Connection", "Searches your Wi-Fi for the Mac, then retries every 1 to 30 seconds.")
            }
            SettingsItem.ABOUT -> {
                val ctx = LocalContext.current
                val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
                Row2("PortNoir", "Version $version")
                Row2("Protocol", "Version 1")
            }
            SettingsItem.LICENCES -> {
                Row2("AndroidX and Jetpack Compose", "Apache License 2.0")
                Row2("OkHttp", "Apache License 2.0")
                Row2("ML Kit Barcode Scanning", "Google ML Kit terms")
                Row2("Material Icons", "Apache License 2.0")
            }
            else -> {}
        }
    }
}
