package com.portnoir.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portnoir.PortNoirApp
import com.portnoir.data.PairedMac

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PortTab(onOpenChat: (String) -> Unit, onAddDevice: () -> Unit) {
    val app = LocalContext.current.applicationContext as PortNoirApp
    val macs by app.repo.macs.collectAsStateWithLifecycle()
    val states by app.connections.states.collectAsStateWithLifecycle()
    var forgetTarget by remember { mutableStateOf<PairedMac?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text("Port", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
            if (macs.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("No devices yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Open PortNoir on your Mac, choose Add device, then scan the code.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(macs, key = { it.id }) { mac ->
                        val state = states[mac.id]
                        ListItem(
                            modifier = Modifier.combinedClickable(
                                onClick = { onOpenChat(mac.id) },
                                onLongClick = { forgetTarget = mac }),
                            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            leadingContent = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                            headlineContent = { Text(mac.name) },
                            supportingContent = { Text(state.label()) },
                            trailingContent = { StatusDot(state) },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAddDevice,
            icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
            text = { Text("Add device") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    forgetTarget?.let { mac ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${mac.name}?") },
            text = { Text("You will need to scan a new code to connect again.") },
            confirmButton = { TextButton(onClick = { app.connections.forget(mac.id); forgetTarget = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Cancel") } },
        )
    }
}
