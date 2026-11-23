package org.experimentalmachines.mobileagent.agent

import com.google.common.truth.Truth.assertThat
import org.experimentalmachines.mobileagent.protocol.AgentFormat
import org.junit.Test

/** The safety guard, the loop-guard diagnostics, and the app-name list / open_app resolution. */
class AgentLogicTest {
    private val none = Guard.Live(windowPackages = listOf("com.android.launcher3"))

    @Test
    fun guardFlagsSensitiveWindowsPasswordsRiskyLabelsAndProtectedWindows() {
        assertThat(Guard.risk("click", none)).isNull()
        assertThat(Guard.risk("click", none.copy(windowPackages = listOf("com.android.permissioncontroller")))).contains("permissioncontroller")
        assertThat(Guard.risk("input_text", none.copy(focusedIsPassword = true))).isEqualTo("typing into a password field")
        assertThat(Guard.risk("click", none.copy(targetLabels = listOf("Buy now")))).isEqualTo("tapping \"Buy now\"")
        assertThat(Guard.risk("click", none.copy(targetLabels = listOf("Delete")))).isNotNull()
        assertThat(Guard.risk("click", none.copy(targetLabels = listOf("Display", "Payment settings help")))).isNull()
        assertThat(Guard.risk("click", none.copy(protectedWindow = "Allow Clock to send you notifications?"))).contains("protected system window")
        // Actions that do not touch the screen are never asked about.
        assertThat(Guard.risk("navigate_back", none.copy(windowPackages = listOf("com.android.permissioncontroller")))).isNull()
        assertThat(Guard.risk("open_app", none.copy(protectedWindow = "x"))).isNull()
    }

    @Test
    fun loopGuardRulesG1AndG2() {
        val screen = FloatArray(72 * 160) { 100f }
        val other = FloatArray(72 * 160) { 140f }
        val g = LoopGuard()
        val wait = AgentFormat.Action("wait")
        assertThat(g.check(wait, "Click on Settings", screen)).containsExactly("G1")
        assertThat(g.check(wait, "Wait for the page to load", screen)).isEmpty()
        val tap = AgentFormat.Action("click", x = 500.0, y = 300.0)
        g.record(tap, screen)
        g.record(AgentFormat.Action("click", x = 520.0, y = 310.0), other)
        // Same tap (within 30 grid units) twice before, and the screen equals the one two steps ago.
        assertThat(g.check(AgentFormat.Action("click", x = 510.0, y = 290.0), "Tap it", screen)).containsExactly("G2")
        assertThat(g.check(AgentFormat.Action("click", x = 510.0, y = 290.0), "Tap it", other)).isEmpty()
        assertThat(g.check(AgentFormat.Action("click", x = 600.0, y = 290.0), "Tap it", screen)).isEmpty()
    }

    @Test
    fun appNamesSortedLikePythonAndResolved() {
        val apps = listOf(
            InstalledApps.App("clock", "a.clock2", "A"),
            InstalledApps.App("Clock", "com.android.deskclock", "B"),
            InstalledApps.App("APTMM_Camcorder2", "p1", "C"),
            InstalledApps.App("AONTest", "p2", "D"),
            InstalledApps.App("Ambient Service Sample", "p3", "E"),
            InstalledApps.App("Chrome", "com.android.chrome", "F"),
            InstalledApps.App("Clock", "dup.clock", "G"),
        )
        assertThat(InstalledApps.promptNames(apps)).containsExactly(
            "Ambient Service Sample", "AONTest", "APTMM_Camcorder2", "Chrome", "Clock", "clock",
        ).inOrder()
        assertThat(InstalledApps.resolve(apps, "Chrome")?.packageName).isEqualTo("com.android.chrome")
        assertThat(InstalledApps.resolve(apps, " chro ")?.packageName).isEqualTo("com.android.chrome")
        assertThat(InstalledApps.resolve(apps, "deskclock")?.packageName).isEqualTo("com.android.deskclock")
        assertThat(InstalledApps.resolve(apps, "Maps")).isNull()
        assertThat(InstalledApps.resolve(apps, "")).isNull()
    }
}
