package org.experimentalmachines.mobileagent.run

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.experimentalmachines.mobileagent.agent.AgentController
import org.experimentalmachines.mobileagent.agent.RunState
import org.experimentalmachines.mobileagent.ui.MobileAgentTheme
import org.experimentalmachines.mobileagent.ui.RunControl

/**
 * The small floating control over other apps while a run is going: step counter, the current intent, Pause / Resume
 * and Stop, and the safety guard's question. It needs "Display over other apps"; without it the run still works
 * (Stop stays in the notification). Hidden while the agent takes a screenshot or acts, so the model never sees it
 * and a tap never lands on it.
 */
class RunOverlay(private val context: Context, private val controller: AgentController) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var autoClose: Job? = null

    val allowed: Boolean get() = Settings.canDrawOverlays(context)

    init {
        scope.launch {
            controller.state.collect { s ->
                autoClose?.cancel()
                when (s) {
                    is RunState.Running -> Unit
                    is RunState.Finished -> autoClose = scope.launch {
                        delay(RESULT_MS)
                        remove()
                    }
                    RunState.Idle -> remove()
                }
            }
        }
    }

    fun show() = main.post {
        if (!allowed) return@post
        view?.let {
            it.visibility = View.VISIBLE
            return@post
        }
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                MobileAgentTheme {
                    val state by controller.state.collectAsState()
                    RunControl(
                        state = state,
                        onPause = { controller.setPaused(it) },
                        onStop = { controller.stop() },
                        onDecide = { controller.decide(it) },
                        onClose = { remove() },
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                moveBy(drag.x.toInt(), drag.y.toInt())
                            }
                        },
                    )
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (context.resources.displayMetrics.density * 48).toInt()
        }
        runCatching { wm.addView(v, lp) }.onSuccess {
            view = v
            owner = o
            params = lp
        }.onFailure { o.stop() }
    }

    fun remove() = main.post {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null
        owner = null
        params = null
    }

    private fun moveBy(dx: Int, dy: Int) {
        val v = view ?: return
        val lp = params ?: return
        lp.x += dx
        lp.y += dy
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /** Runs [block] with the control off screen (invisible and not touchable), then shows it again. */
    suspend fun <T> whileHidden(block: suspend () -> T): T {
        val hidden = withContext(Dispatchers.Main) {
            val v = view ?: return@withContext false
            v.visibility = View.INVISIBLE
            params?.let { lp ->
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(v, lp) }
            }
            true
        }
        if (hidden) delay(HIDE_SETTLE_MS) // let the compositor drop the window before the screenshot or gesture
        try {
            return block()
        } finally {
            if (hidden) withContext(NonCancellable + Dispatchers.Main) {
                view?.let { v ->
                    v.visibility = View.VISIBLE
                    params?.let { lp ->
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
            }
        }
    }

    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private companion object {
        const val HIDE_SETTLE_MS = 80L
        const val RESULT_MS = 8_000L
    }
}
