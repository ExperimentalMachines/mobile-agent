package org.experimentalmachines.mobileagent

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.experimentalmachines.mobileagent.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MobileAgentTheme {
                var screen by rememberSaveable { mutableStateOf("home") }
                BackHandler(enabled = screen != "home") { screen = if (screen.startsWith("run:")) "log" else "home" }
                Scaffold(containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            listOf(Triple("home", "Agent", Glyphs.Spark), Triple("log", "Activity", Glyphs.Clock), Triple("settings", "Settings", Glyphs.Settings)).forEach { (route, label, glyph) ->
                                NavigationBarItem(selected = screen == route || (route == "home" && screen == "setup") || (route == "log" && screen.startsWith("run:")),
                                    onClick = { screen = route }, icon = { Icon(glyph, label, Modifier.size(22.dp)) }, label = { Text(label) })
                            }
                        }
                    }) { padding ->
                    // Each screen owns its scroll position. System/keyboard insets keep controls reachable.
                    key(screen) {
                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState())) {
                            when {
                                screen == "setup" -> SetupScreen(onBack = { screen = "home" })
                                screen == "settings" -> SettingsScreen(onSetup = { screen = "setup" })
                                screen == "log" -> LogScreen(onOpen = { screen = "run:$it" })
                                screen.startsWith("run:") -> RunDetailScreen(screen.removePrefix("run:"), onBack = { screen = "log" })
                                else -> HomeScreen(onOpenLog = { id -> screen = if (id == null) "log" else "run:$id" }, onSetup = { screen = "setup" })
                            }
                        }
                    }
                }
            }
        }
    }
}
