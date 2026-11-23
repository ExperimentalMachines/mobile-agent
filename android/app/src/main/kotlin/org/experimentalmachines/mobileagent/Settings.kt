package org.experimentalmachines.mobileagent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The few settings the app has, kept in SharedPreferences and observable from the UI. */
class Settings(context: Context) {
    data class Values(
        /** "gpu" or "cpu": the text decoder's backend. */
        val textBackend: String = "gpu",
        /** "gpu" or "cpu": the vision encoder's backend. */
        val visionBackend: String = "gpu",
        /** The hard step limit. 20 = AndroidWorld's budget (10 x task complexity) at its median complexity, 2. */
        val maxSteps: Int = DEFAULT_MAX_STEPS,
        /**
         * false: one engine for the whole run, a new conversation per step (LiteRT-LM issue #3165: on 0.19.1 a new
         * conversation can continue from the previous one's short-convolution state). true: a new engine every step.
         */
        val enginePerStep: Boolean = false,
        /** Ask before steps the safety guard flags (installs, permissions, credentials, payments, deleting). */
        val confirmRisky: Boolean = true,
        /** A short ring where each tap lands. */
        val showTaps: Boolean = true,
        /**
         * true: `input_text` types and then presses Enter, as AndroidWorld executes it (what v4 was trained and
         * benchmarked on). false: type only, Enter only on `keyboard_enter` (MobileWorld-native typing).
         */
        val enterAfterTyping: Boolean = true,
        /** The selected sidecar's file name in the models folder ("" = model.json or the first one). */
        val modelSidecar: String = "",
    )

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()
    val current: Values get() = _values.value

    private fun read() = Values(
        textBackend = prefs.getString("text_backend", "gpu")!!,
        visionBackend = prefs.getString("vision_backend", "gpu")!!,
        maxSteps = prefs.getInt("max_steps", DEFAULT_MAX_STEPS),
        enginePerStep = prefs.getBoolean("engine_per_step", false),
        confirmRisky = prefs.getBoolean("confirm_risky", true),
        showTaps = prefs.getBoolean("show_taps", true),
        enterAfterTyping = prefs.getBoolean("enter_after_typing", true),
        modelSidecar = prefs.getString("model_sidecar", "")!!,
    )

    fun update(change: (Values) -> Values) {
        val v = change(current)
        prefs.edit()
            .putString("text_backend", v.textBackend)
            .putString("vision_backend", v.visionBackend)
            .putInt("max_steps", v.maxSteps.coerceIn(1, MAX_STEPS_CAP))
            .putBoolean("engine_per_step", v.enginePerStep)
            .putBoolean("confirm_risky", v.confirmRisky)
            .putBoolean("show_taps", v.showTaps)
            .putBoolean("enter_after_typing", v.enterAfterTyping)
            .putString("model_sidecar", v.modelSidecar)
            .apply()
        _values.value = v.copy(maxSteps = v.maxSteps.coerceIn(1, MAX_STEPS_CAP))
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 20
        const val MAX_STEPS_CAP = 100
    }
}
