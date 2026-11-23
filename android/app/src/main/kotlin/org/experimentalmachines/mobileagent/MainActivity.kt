package org.experimentalmachines.mobileagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import org.experimentalmachines.mobileagent.ui.HomeScreen
import org.experimentalmachines.mobileagent.ui.LogScreen
import org.experimentalmachines.mobileagent.ui.MobileAgentTheme
import org.experimentalmachines.mobileagent.ui.RunDetailScreen
import org.experimentalmachines.mobileagent.ui.SettingsScreen

/** One activity, four screens: home, settings, run log, one run. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MobileAgentTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // "home", "settings", "log", or "run:<id>"
                    var screen by rememberSaveable { mutableStateOf("home") }
                    BackHandler(enabled = screen != "home") { screen = if (screen.startsWith("run:")) "log" else "home" }
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState(), enabled = true)) {
                        when {
                            screen == "settings" -> SettingsScreen(onBack = { screen = "home" })
                            screen == "log" -> LogScreen(onBack = { screen = "home" }, onOpen = { screen = "run:$it" })
                            screen.startsWith("run:") -> RunDetailScreen(screen.removePrefix("run:"), onBack = { screen = "log" })
                            else -> HomeScreen(
                                onOpenSettings = { screen = "settings" },
                                onOpenLog = { id -> screen = if (id == null) "log" else "run:$id" },
                            )
                        }
                    }
                }
            }
        }
    }
}
