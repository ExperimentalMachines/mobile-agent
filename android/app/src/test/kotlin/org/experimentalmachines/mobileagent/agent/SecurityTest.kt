package org.experimentalmachines.mobileagent.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The guard's time-of-use re-check, the new risk rules, redaction and the non-verbose log lines. */
class SecurityTest {
    private val launcher = Guard.Live(windowPackages = listOf("com.android.launcher3"), foregroundPackage = "com.android.launcher3")
    private val clock = Guard.Live(
        windowPackages = listOf("com.android.deskclock"), targetLabels = listOf("7:30 AM"),
        targetPackage = "com.android.deskclock", foregroundPackage = "com.android.deskclock",
    )

    @Test
    fun sendCallAndEnterInMessagingAppsAreRisky() {
        assertThat(Guard.risk("click", clock.copy(targetLabels = listOf("Send")))).isNotNull()
        assertThat(Guard.risk("click", clock.copy(targetLabels = listOf("Call")))).isNotNull()
        assertThat(Guard.risk("click", clock.copy(targetLabels = listOf("Calls", "Sender settings")))).isNull()
        val composer = clock.copy(focusedPackage = "com.android.mms", foregroundPackage = "com.android.mms")
        assertThat(Guard.risk("keyboard_enter", composer)).contains("com.android.mms")
        assertThat(Guard.risk("input_text", composer, enterAfterTyping = true)).contains("Enter")
        assertThat(Guard.risk("input_text", composer, enterAfterTyping = false)).isNull()
        assertThat(Guard.risk("keyboard_enter", clock.copy(focusedPackage = "com.android.chrome"))).isNull()
        // Account sign-in (Google Play services) and Play Store installs.
        assertThat(Guard.risk("click", clock.copy(windowPackages = listOf("com.google.android.gms")))).isNotNull()
        assertThat(Guard.risk("click", clock.copy(windowPackages = listOf("com.android.vending")))).isNotNull()
        for (label in listOf("Add an account to get started", "Sign in", "Share", "Install", "Allow", "Accept all", "Confirm", "Post")) {
            assertThat(Guard.risk("click", clock.copy(targetLabels = listOf(label)))).isNotNull()
        }
    }

    @Test
    fun settingsAccessPagesAndUnreadableTargets() {
        val settings = clock.copy(foregroundPackage = "com.android.settings", targetPackage = "com.android.settings", targetLabels = listOf("Use Mobile Agent"))
        for (title in listOf("Accessibility", "Device & app notifications", "Special app access", "Device admin apps", "Install unknown apps")) {
            assertThat(Guard.risk("click", settings.copy(screenTitle = title))).contains("Settings page")
        }
        assertThat(Guard.risk("scroll", settings.copy(screenTitle = "Accessibility"))).isNull()      // reading is fine
        assertThat(Guard.risk("click", settings.copy(screenTitle = "Settings | Network & internet"))).isNull()
        assertThat(Guard.risk("click", settings.copy(screenTitle = "About phone"))).isNull()
        // Nothing readable under a tap: ask; navigation-only actions do not.
        assertThat(Guard.risk("click", clock.copy(targetReadable = false, targetLabels = emptyList()))).contains("nothing readable")
        assertThat(Guard.risk("long_press", clock.copy(targetReadable = false, targetLabels = emptyList()))).isNotNull()
        for (t in listOf("scroll", "navigate_back", "navigate_home", "open_app", "wait")) {
            assertThat(Guard.risk(t, clock.copy(targetReadable = false))).isNull()
        }
    }

    @Test
    fun actionRunsOnlyOnTheScreenItWasDecidedOn() {
        // Same screen: runs.
        assertThat(Guard.changed("click", "com.android.deskclock", clock, clock, guarded = false)).isNull()
        // Another app came to the front after the screenshot (e.g. a dialog of another package).
        assertThat(Guard.changed("click", "com.android.deskclock", clock, clock.copy(foregroundPackage = "com.android.chrome", targetPackage = "com.android.chrome"), guarded = false))
            .contains("app in front changed")
        // The screenshot was of the launcher, the app opened during the model call: the tap was meant for the launcher.
        assertThat(Guard.changed("click", "com.android.launcher3", clock, clock, guarded = false)).contains("since the screenshot")
        // The user handled a permission prompt while the guard asked: the risk is gone, so the planned tap is dropped.
        val prompt = clock.copy(protectedWindow = "Allow Clock to send you notifications?", foregroundPackage = "protected: Allow Clock to send you notifications?")
        assertThat(Guard.changed("click", prompt.foregroundPackage, prompt, clock, guarded = true)).contains("risk changed")
        // A guarded step needs the same element; an unguarded one tolerates a changed label (dynamic text).
        val other = clock.copy(targetLabels = listOf("8:00 AM"))
        assertThat(Guard.changed("click", "com.android.deskclock", clock, other, guarded = true)).contains("element under the tap changed")
        assertThat(Guard.changed("click", "com.android.deskclock", clock, other, guarded = false)).isNull()
        // A label that turns risky is caught by the risk comparison.
        assertThat(Guard.changed("click", "com.android.deskclock", clock, clock.copy(targetLabels = listOf("Delete")), guarded = false)).contains("risk changed")
        // Typing: the focused field must stay the same, and not turn into a password field. A tap ignores focus changes.
        assertThat(Guard.changed("input_text", "com.android.deskclock", clock, clock.copy(focusedIsPassword = true), guarded = false)).isNotNull()
        assertThat(Guard.changed("input_text", "com.android.deskclock", clock, clock.copy(focusedPackage = "x"), guarded = false)).contains("focused")
        assertThat(Guard.changed("click", "com.android.deskclock", clock, clock.copy(focusedPackage = "x"), guarded = false)).isNull()
        // Actions without a screen target are never held back.
        assertThat(Guard.changed("open_app", "com.android.launcher3", launcher, clock, guarded = false)).isNull()
        assertThat(Guard.changed("navigate_back", "com.android.launcher3", launcher, clock, guarded = false)).isNull()
    }

    @Test
    fun redactorScrubsSecretsAndTheirJsonForms() {
        val r = Redactor()
        assertThat(r.scrub("type hunter2")).isEqualTo("type hunter2")
        r.add("hunter2")
        r.add("x") // too short to mask
        r.add("a\"b")
        assertThat(r.scrub("Type hunter2 into the password field")).isEqualTo("Type [redacted] into the password field")
        assertThat(r.scrub("""{"action_type":"input_text","text":"hunter2"}""")).isEqualTo("""{"action_type":"input_text","text":"[redacted]"}""")
        assertThat(r.scrub("""{"action_type":"input_text","text":"a\"b"}""")).isEqualTo("""{"action_type":"input_text","text":"[redacted]"}""")
        assertThat(r.scrub("x marks")).isEqualTo("x marks")
        assertThat(r.scrub(null)).isNull()
    }

    @Test
    fun briefLogLinesCarryNoText() {
        val step = StepRecord(
            step = 3, intent = "Type the PIN 1234", action = """{"action_type":"input_text","text":"1234"}""", outcome = "typed, pressed enter",
            attempts = emptyList(), screenshotMs = 1, resizeMs = 2, pngMs = 3, engineMs = null, modelMs = 2400, ttftMs = 1100,
            prefillTokens = 1310, prefillTokensPerS = 1200.0, decodeTokens = 20, decodeTokensPerS = 43.0, actMs = 100, settleMs = 1000,
            stepMs = 4000, screenWidth = 1440, screenHeight = 3200, guard = "typing into a password field: allow",
        )
        val brief = AgentController.StepLine.of(step, verbose = false)
        assertThat(brief.intent).isEmpty()
        assertThat(brief.action).isEqualTo("input_text")
        assertThat(brief.outcome).isEqualTo("typed,")
        assertThat(brief.guard).isEqualTo("asked")
        assertThat(brief.toString()).doesNotContain("1234")
        val full = AgentController.StepLine.of(step, verbose = true)
        assertThat(full.action).contains("1234")
    }
}
