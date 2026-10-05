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
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (tab == 0) PortTab(onOpenChat, onAddDevice) else SettingsTab(onOpenSetting)
        }
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
    ConnState.DISCOVERING -> "Searching for your Mac"
    ConnState.CONNECTING -> "Connecting"
    ConnState.AUTHENTICATING -> "Signing in"
    else -> "Offline"
}
