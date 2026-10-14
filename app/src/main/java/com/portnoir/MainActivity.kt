package com.portnoir

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.portnoir.net.ConnectionService
import com.portnoir.ui.*
import com.portnoir.ui.theme.PortNoirTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { PortNoirTheme { PortNoirNav() } }
    }

    override fun onStart() {
        super.onStart()
        // Re-kick the connection loops every time the app comes to the foreground.
        if ((application as PortNoirApp).repo.macs.value.isNotEmpty()) ConnectionService.start(this)
    }
}

@Composable
fun PortNoirNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenChat = { nav.navigate("chat/$it") },
                onAddDevice = { nav.navigate("scan") },
                onOpenSetting = { nav.navigate("settings/$it") },
            )
        }
        composable("scan") { ScanScreen(onBack = { nav.popBackStack() }) }
        composable("chat/{macId}") {
            ChatHistoryScreen(it.arguments?.getString("macId").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("settings/{key}") {
            SettingsDetailScreen(it.arguments?.getString("key").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
