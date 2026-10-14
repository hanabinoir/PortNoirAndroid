package com.portnoir.ui

import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portnoir.PortNoirApp
import com.portnoir.data.ChatMessage
import java.io.File
import android.widget.Toast

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
@OptIn(ExperimentalMaterial3Api::class)
fun ChatHistoryScreen(macId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PortNoirApp
    val context = LocalContext.current
    val name = app.repo.mac(macId)?.name ?: "Mac"
    val messages by app.chatDatabase.messages().observe(macId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val fileName = cursor?.use {
                val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && it.moveToFirst()) it.getString(column) else null
            } ?: "file"
            app.connections.sendFile(
                macId, uri, fileName,
                context.contentResolver.getType(uri) ?: "application/octet-stream",
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach file")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                )
                IconButton(onClick = {
                    val body = draft.trim()
                    if (body.isNotEmpty()) {
                        app.connections.sendText(macId, body)
                        draft = ""
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(
                    "Messages and files shared with $name will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message -> ChatBubble(message) }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val context = LocalContext.current
    val outgoing = message.direction == "outgoing"
    val openable = !outgoing && message.transferState == "complete" && message.localPath.isNotEmpty()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .clickable(enabled = openable) {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.files", File(message.localPath),
                    )
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, message.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
                    }
                }
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.type == "chat.file") {
                Text(message.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${message.size} bytes · ${message.transferState.ifEmpty { if (message.delivered) "sent" else "sending" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(message.body)
                if (outgoing && !message.delivered) {
                    Text("Sending", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
