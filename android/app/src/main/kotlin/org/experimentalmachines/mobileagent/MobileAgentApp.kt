package org.experimentalmachines.mobileagent

import android.app.Application
import android.content.Context
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.experimentalmachines.mobileagent.agent.AgentController
import org.experimentalmachines.mobileagent.agent.RunState
import org.experimentalmachines.mobileagent.model.ModelInstaller
import org.experimentalmachines.mobileagent.model.ModelManager
import org.experimentalmachines.mobileagent.run.Notifier
import java.io.File

class MobileAgentApp : Application() {
    val settings: Settings by lazy { Settings(this) }
    val models: ModelManager by lazy { ModelManager(this, settings) }
    val installer: ModelInstaller by lazy { ModelInstaller(this, models, settings) }
    val controller: AgentController by lazy { AgentController(this, settings, models) }

    override fun onCreate() {
        super.onCreate()
        // The app makes its own external folders first: made by adb's shell they would belong to shell, and the app
        // could not read a model copied into them.
        listOf("models", "runs", "bench").forEach { File(getExternalFilesDir(null), it).mkdirs() }
        models.refresh()
        installer // recover any transfer started before process death
        // The run's notification follows the run state (RunService makes it a foreground notification when it can).
        // Android sheds updates above 5 per second, and the intent streams token by token: post only visible changes,
        // at most one per NOTIFY_GAP_MS, always the latest.
        MainScope().launch {
            controller.state.filterIsInstance<RunState.Running>()
                .distinctUntilChangedBy { listOf(it.step, it.phase, it.paused, it.confirm, it.intent) }
                .conflate()
                .collect { s ->
                    Notifier.update(this@MobileAgentApp, s)
                    delay(NOTIFY_GAP_MS)
                }
        }
    }

    companion object {
        private const val NOTIFY_GAP_MS = 600L

        fun get(context: Context): MobileAgentApp = context.applicationContext as MobileAgentApp
    }
}
