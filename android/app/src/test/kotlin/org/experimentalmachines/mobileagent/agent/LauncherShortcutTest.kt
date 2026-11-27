package org.experimentalmachines.mobileagent.agent

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Intents are the model's own, from the sealed AndroidWorld runs (v4, step 1) and the launcher probe. */
class LauncherShortcutTest {
    /** The emulator's launcher apps as the prompt lists them. */
    private val phone = listOf(
        "Calendar", "Camera", "Chrome", "Clock", "Contacts", "Drive", "Files", "Gmail", "Google", "Maps", "Messages",
        "Phone", "Photos", "Safety", "Settings", "TMoble", "Voice Search", "YouTube", "YT Music",
    )
    private val aw = phone + listOf("VLC", "Markor", "Simple Calendar Pro")

    private fun check(type: String, intent: String, apps: List<String>, want: String?) =
        assertWithMessage("$type \"$intent\"").that(LauncherShortcut.appFor(type, intent, apps)).isEqualTo(want)

    @Test
    fun tapsAndScrollsForANamedAppOpenIt() {
        check("click", "Open the Settings app", phone, "Settings")
        check("click", "click on the settings app", phone, "Settings")
        check("click", "Open the phone app.", phone, "Phone")
        check("click", "Open the Clock app", phone, "Clock")
        check("click", "Open Chrome browser", phone, "Chrome")
        check("click", "Tap on the Messages icon", phone, "Messages")
        check("click", "Open the Google Photos app", phone, "Photos")
        check("click", "Open the calender app", phone, "Calendar") // a typo
        check("click", "Open the contact app", phone, "Contacts") // singular
        check("scroll", "Scroll through the home screen to find the file manager app.", aw, "Files")
        check("scroll", "Scroll through the home screen to find the VLC player app.", aw, "VLC")
    }

    @Test
    fun everythingElseRunsAsChosen() {
        check("click", "Click on the search bar", phone, null) // "Voice Search" must not be launched
        check("click", "click on the three dots at the bottom right corner of the screen", phone, null)
        check("scroll", "Scroll down to see more content.", phone, null)
        check("click", "Open the app drawer", phone, null)
        check("click", "Open the YouTube Music app", phone, null) // ambiguous: YouTube or YT Music; the tap decides
        check("click", "Open the Spotify app", phone, null) // not installed
        check("long_press", "Open the Clock app", phone, null)
        check("open_app", "Open the Clock app", phone, null)
        check("click", "", phone, null)
        check("click", "Tap on the work icon", phone + "Workout", null) // Pixel's drawer has a Work tab
        check("click", "Open the Signal app", phone + "Signal Strength", null) // Signal is not installed
        check("click", "Open the file manager", phone + "File Manager+", "File Manager+")
        check("click", "Open the manager", phone + "File Manager+", null) // descriptors alone name nothing
        check("click", "Open the Clock app", emptyList(), null)
    }
}
