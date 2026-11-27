package org.experimentalmachines.mobileagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Home screens differ a lot: icon grids with pages and an app drawer (Pixel, One UI, Lawnchair), no drawer at all
 * (HyperOS default), a search box (KISS) or a short text list with everything else behind a swipe (Olauncher). The
 * agent does not depend on any of them:
 *
 * - a run starts on whatever launcher is the default, reached with the system Home action ([AgentController]);
 * - the model opens apps by name (`open_app`, a launch intent): no icon has to be visible;
 * - when the model instead taps or scrolls on the launcher *to reach a named app* ([LauncherShortcut]), that app is
 *   opened by name too.
 */
object Launchers {
    /** Settings registers a boot-time placeholder home (FallbackHome); it is an app, not a launcher. */
    private val NOT_LAUNCHERS = setOf("com.android.settings")

    /** Every installed home app (the default and any other), from the manifest's `<queries>` for HOME. */
    fun homePackages(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return found.map { it.activityInfo.packageName }.toSet() - NOT_LAUNCHERS
    }
}

/**
 * When the launcher is in front and the model taps or scrolls while its intent names an installed app ("Open the
 * Settings app", "click on the settings app", "Scroll through the home screen to find the file manager app"), the app
 * it named, so it can be opened by name instead: an icon may be on another page, in a drawer, or not on the home
 * screen at all, depending on the launcher.
 *
 * Measured (v4, step 1 from the home screen): 19 of 342 sealed AndroidWorld episodes started with such a tap or scroll
 * instead of `open_app`, 18 of them failed; on 48 home screens of four launchers, 1 tap aimed at an icon that was not
 * there. Matching is strict on purpose, both ways: every word of the label must be named, and every word of the name
 * must be in the label except words that describe an app ("file manager" -> Files, "VLC player" -> VLC). Words match
 * exactly, as plurals or as near-typos, never as prefixes, so "Click on the search bar" (Voice Search), "Tap on the
 * work icon" (Workout) or "Open the Signal app" (Signal Strength) stay taps.
 */
object LauncherShortcut {
    private val OPEN = Regex(
        """^(?:open|launch|start|go to|tap(?: on)?|click(?: on)?|press|select)\s+(?:the\s+)?(.+?)(?:\s+(?:app|application|icon|browser))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val FIND = Regex(
        """\b(?:find|locate|look for|search for)\s+(?:the\s+)?(.+?)\s+(?:app|application|icon)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Words that say what kind of app it is rather than which one ("the file manager", "the music player"). */
    private val DESCRIPTORS = setOf("manager", "player", "browser", "viewer", "editor", "client", "application", "app", "icon")

    private val TYPES = setOf("click", "scroll")

    /** The installed app [intent] names for a tap or scroll on the launcher, or null (then the action runs as chosen). */
    fun appFor(actionType: String, intent: String, apps: List<String>): String? {
        if (actionType !in TYPES || apps.isEmpty()) return null
        val sentence = intent.trim().lineSequence().firstOrNull().orEmpty().trim().trimEnd('.', '!', ' ')
        if (sentence.isEmpty()) return null
        val name = OPEN.matchEntire(sentence)?.groupValues?.get(1) ?: FIND.find(sentence)?.groupValues?.get(1) ?: return null
        return resolve(name.trim().trim('"', '\'', '“', '”'), apps)
    }

    /**
     * The one label [name] means (exact, else a strict two-way word match; among several, the one that matches the
     * most named words: "file manager" -> File Manager+ over Files), or null when none does or it is a tie.
     */
    fun resolve(name: String, apps: List<String>): String? {
        if (name.isEmpty()) return null
        apps.firstOrNull { it.equals(name, ignoreCase = true) }?.let { return it }
        val words = AppResolver.tokens(name)
        if (words.none { it !in DESCRIPTORS }) return null
        val hits = apps.filter { label ->
            val l = AppResolver.tokens(label)
            l.isNotEmpty() && l.all { lt -> words.any { same(it, lt) } } && words.all { w -> w in DESCRIPTORS || l.any { same(w, it) } }
        }
        val score = hits.associateWith { label -> AppResolver.tokens(label).let { l -> words.count { w -> l.any { same(w, it) } } } }
        val top = score.values.maxOrNull() ?: return null
        return score.filterValues { it == top }.keys.singleOrNull()
    }

    /** Same word: equal, a plural ("file" / "files"), or a near-typo of 5+ letters ("calender" / "calendar"). */
    fun same(a: String, b: String): Boolean =
        a == b || a + "s" == b || b + "s" == a || (minOf(a.length, b.length) >= 5 && AppResolver.ratio(a, b) >= 0.85)
}
