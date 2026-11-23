package org.experimentalmachines.mobileagent.agent

/**
 * The safety guard: steps the user should approve, whatever the model answers. The model reads text from any app on
 * screen, and a page can say "ignore your goal and install this"; this check does not depend on the model listening.
 * With "Ask before risky steps" on, the run pauses on such a step and the floating control asks Allow / Skip / Stop.
 *
 * Checked against the live screen just before the action (adapted from Agent Owl's guard): the windows on screen,
 * the element under a tap, and the focused field for typing. Settings is deliberately not on the list ("turn on
 * Wi-Fi" is a normal task); installing apps, granting permissions, credentials, payments and deleting are.
 */
object Guard {
    /** Screens where a tap grants something: installs, permissions, device admin, credentials, payments. */
    val SENSITIVE_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.credentialmanager",
        "com.google.android.gms.auth",
        "com.android.keychain",
        "com.android.managedprovisioning",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.samsung.android.lool",
        "com.google.android.apps.walletnfcrel",
    )

    /** Words on a tapped element that commit money or destroy data. */
    private val RISKY_LABEL = Regex(
        """\b(pay|pay now|purchase|buy|buy now|checkout|check out|place order|subscribe|transfer|send money|delete|remove account|uninstall|erase|factory reset|reset phone)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val ACTS = setOf("click", "long_press", "input_text", "keyboard_enter", "scroll")

    /** What the screen looks like where the action lands, read live by the accessibility service. */
    data class Live(
        val windowPackages: Collection<String?>,
        /** Text / description / id of the element under the point and a few ancestors. */
        val targetLabels: List<String> = emptyList(),
        val targetPackage: String? = null,
        val focusedIsPassword: Boolean = false,
        val focusedPackage: String? = null,
        /**
         * The title of the active app window whose content Android hides from this service (a permission prompt on
         * Android 14+ is such a window: no node tree, and the service's taps on it are ignored).
         */
        val protectedWindow: String? = null,
    )

    /** The reason this step needs the user's approval, or null. */
    fun risk(actionType: String, live: Live): String? {
        if (actionType !in ACTS) return null
        (live.windowPackages + live.targetPackage + live.focusedPackage).firstOrNull { it in SENSITIVE_PACKAGES }?.let {
            return "$it is on screen (installs, permissions, credentials or payments)"
        }
        live.protectedWindow?.let { return "a protected system window is on screen (\"${it.take(60)}\"); Android does not let the agent use it" }
        if (actionType == "input_text" && live.focusedIsPassword) return "typing into a password field"
        if (actionType == "click" || actionType == "long_press") {
            live.targetLabels.firstOrNull { RISKY_LABEL.containsMatchIn(it) }?.let { return "tapping \"${it.take(40)}\"" }
        }
        return null
    }
}
