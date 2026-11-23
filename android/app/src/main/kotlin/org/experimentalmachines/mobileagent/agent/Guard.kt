package org.experimentalmachines.mobileagent.agent

/**
 * The safety guard: steps the user should approve, whatever the model answers. The model reads text from any app on
 * screen, and a page can say "ignore your goal and install this"; this check does not depend on the model listening.
 * With "Ask before risky steps" on, the run pauses on such a step and the floating control asks Allow / Skip / Stop.
 *
 * Checked against the live screen just before the action: the windows on screen,
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
        // Google Play services in front: account sign-in, payments, device-security screens.
        "com.google.android.gms",
        // Play Store: installs and purchases.
        "com.android.vending",
        "com.android.keychain",
        "com.android.managedprovisioning",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.samsung.android.lool",
        "com.google.android.apps.walletnfcrel",
    )

    /** Words on a tapped element that commit money, contact someone, or destroy data. */
    private val RISKY_LABEL = Regex(
        """\b(pay|pay now|purchase|buy|buy now|checkout|check out|place order|subscribe|transfer|send|send money|call|share|post|install|confirm|accept|allow|sign in|log in|add account|add an account|delete|remove account|uninstall|erase|factory reset|reset phone)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Settings pages that grant special access: accessibility services, notification access, special app access,
     * device admin, install-unknown-apps and the like (matched on the page title; English titles).
     */
    private val SENSITIVE_SETTINGS = Regex(
        """accessibility|notification (read|access)|device & app notifications|special app access|device admin|""" +
            """install unknown apps|display over other apps|modify system settings|all files access|usage access|""" +
            """default apps|credential|passwords|vpn|developer options|reset options|erase all data|app pinning""",
        RegexOption.IGNORE_CASE,
    )

    /** Apps where Enter in a text field may send something (messaging, mail, phone). */
    private val SENDS_ON_ENTER = Regex("""mms|messag|email|mail|dialer|telecom|whatsapp|telegram|signal|chat""", RegexOption.IGNORE_CASE)

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
        /** The app in front: the active app window's package, or "protected: <title>" when its content is hidden. */
        val foregroundPackage: String? = null,
        /** The front screen's title (window title and headings), to recognise Settings pages that grant access. */
        val screenTitle: String? = null,
        /**
         * False when nothing readable is under the tap: no accessibility node there, or only an opaque surface
         * (WebView canvas, SurfaceView, TextureView) with no label.
         */
        val targetReadable: Boolean = true,
    )

    /** The reason this step needs the user's approval, or null. [enterAfterTyping]: `input_text` ends with Enter. */
    fun risk(actionType: String, live: Live, enterAfterTyping: Boolean = true): String? {
        if (actionType !in ACTS) return null
        (live.windowPackages + live.targetPackage + live.focusedPackage).firstOrNull { it in SENSITIVE_PACKAGES }?.let {
            return "$it is on screen (installs, permissions, credentials or payments)"
        }
        live.protectedWindow?.let { return "a protected system window is on screen (\"${it.take(60)}\"); Android does not let the agent use it" }
        if (live.foregroundPackage == "com.android.settings" && actionType != "scroll") {
            live.screenTitle?.let { t -> SENSITIVE_SETTINGS.find(t)?.let { return "a Settings page that grants access (\"${t.take(50)}\")" } }
        }
        if (actionType == "input_text" && live.focusedIsPassword) return "typing into a password field"
        val presses = actionType == "keyboard_enter" || (actionType == "input_text" && enterAfterTyping)
        if (presses && live.focusedPackage?.let { SENDS_ON_ENTER.containsMatchIn(it) } == true) {
            return "pressing Enter in ${live.focusedPackage} (it may send)"
        }
        if (actionType == "click" || actionType == "long_press") {
            live.targetLabels.firstOrNull { RISKY_LABEL.containsMatchIn(it) }?.let { return "tapping \"${it.take(40)}\"" }
            if (!live.targetReadable) return "nothing readable under the tap (a web canvas, game or drawing); the agent cannot check what it is"
        }
        return null
    }

    /**
     * Why an action must not run now although it was decided on (time of check vs time of use), or null. Compared
     * right before the gesture: [atShot] is the app in front when the screenshot was taken, [decided] the live screen
     * when the guard checked the action (before any question to the user), [now] the live screen now. A guarded step
     * also needs the same element under the tap; for other steps a changed label is fine unless it makes the step
     * risky, which the risk comparison catches.
     */
    fun changed(actionType: String, atShot: String?, decided: Live, now: Live, guarded: Boolean, enterAfterTyping: Boolean = true): String? {
        if (actionType !in ACTS) return null
        val before = risk(actionType, decided, enterAfterTyping)
        val after = risk(actionType, now, enterAfterTyping)
        if (after != before) return "the risk changed (${before ?: "none"} -> ${after ?: "none"})"
        if (atShot != null && now.foregroundPackage != atShot) return "the app in front changed since the screenshot ($atShot -> ${now.foregroundPackage})"
        if (now.foregroundPackage != decided.foregroundPackage) return "the app in front changed (${decided.foregroundPackage} -> ${now.foregroundPackage})"
        if (now.targetPackage != decided.targetPackage) return "the element under the tap now belongs to ${now.targetPackage}"
        val types = actionType == "input_text" || actionType == "keyboard_enter"
        if (types && (now.focusedPackage != decided.focusedPackage || now.focusedIsPassword != decided.focusedIsPassword)) return "the focused field changed"
        if (guarded && now.targetLabels != decided.targetLabels) return "the element under the tap changed"
        return null
    }
}
