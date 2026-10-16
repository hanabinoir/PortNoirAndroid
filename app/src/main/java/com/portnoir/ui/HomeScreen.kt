package com.portnoir.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHorizontalCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.portnoir.net.ConnState
import com.portnoir.net.rememberWifiConnected
import com.portnoir.ui.theme.StatusBusy
import com.portnoir.ui.theme.StatusOffline
import com.portnoir.ui.theme.StatusOk

/** Main view: two bottom tabs, Port and Settings. */
@Composable
fun HomeScreen(onOpenChat: (String) -> Unit, onAddDevice: () -> Unit, onOpenSetting: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.SwapHorizontalCircle, contentDescription = null) },
                    label = { Text("Port") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            WifiOffBanner()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) PortTab(onOpenChat, onAddDevice) else SettingsTab(onOpenSetting)
            }
        }
    }
}

/** Shown while Wi-Fi is off, since pairing and connecting both need the same local network as the Mac. */
@Composable
fun WifiOffBanner(modifier: Modifier = Modifier) {
    val wifi by rememberWifiConnected()
    if (wifi) return
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "Wi-Fi is off. Connect to the same Wi-Fi network as your Mac to pair or connect.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun StatusDot(state: ConnState?) {
    val color: Color = when (state) {
        ConnState.CONNECTED -> StatusOk
        ConnState.DISCOVERING, ConnState.CONNECTING, ConnState.AUTHENTICATING -> StatusBusy
        else -> StatusOffline
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

fun ConnState?.label(): String = when (this) {
    ConnState.CONNECTED -> "Connected"
    ConnState.DISCOVERING -> "Searching for paired PCs"
    ConnState.CONNECTING -> "Connecting"
    ConnState.AUTHENTICATING -> "Signing in"
    else -> "Offline"
}
