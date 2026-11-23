package org.experimentalmachines.mobileagent.agent

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The open_app fallback against the research repo's app_resolver.py (golden), and typing at the cursor. */
class ResolverTypingTest {
    private val golden = Json.parseToJsonElement(javaClass.getResourceAsStream("/golden/app_resolver.json")!!.readBytes().toString(Charsets.UTF_8)).jsonObject

    @Test
    fun resolverMatchesPython() {
        val labels = golden["labels"]!!.jsonArray.map { it.jsonPrimitive.content }
        for ((name, v) in golden["cases"]!!.jsonObject) {
            val want = v.jsonArray[0].takeIf { it !is JsonNull }?.jsonPrimitive?.content
            assertWithMessage(name).that(AppResolver.resolve(name, labels)?.first).isEqualTo(want)
        }
        for (r in golden["ratios"]!!.jsonArray) {
            val (a, b, x) = r.jsonArray
            assertWithMessage("$a/$b").that(AppResolver.ratio(a.jsonPrimitive.content, b.jsonPrimitive.content)).isWithin(1e-12).of(x.jsonPrimitive.double)
        }
    }

    @Test
    fun openAppUsesTheFallbackLast() {
        val apps = listOf(
            InstalledApps.App("Calendar", "com.android.calendar", "A"), InstalledApps.App("Clock", "com.android.deskclock", "B"),
            InstalledApps.App("Messaging", "com.android.mms", "C"),
        )
        assertThat(InstalledApps.resolve(apps, "Clock app")?.label).isEqualTo("Clock")
        assertThat(InstalledApps.resolve(apps, "Calender")?.label).isEqualTo("Calendar")
        assertThat(InstalledApps.resolve(apps, "mms")?.label).isEqualTo("Messaging")
        assertThat(InstalledApps.resolve(apps, "Messages")).isNull()
    }

    @Test
    fun typingGoesToTheCursor() {
        assertThat(Typing.insert("", -1, -1, "abc")).isEqualTo("abc" to 3)
        assertThat(Typing.insert("hello", 5, 5, " world")).isEqualTo("hello world" to 11)
        assertThat(Typing.insert("hello", 0, 0, "> ")).isEqualTo("> hello" to 2)
        assertThat(Typing.insert("https://example.com/", 0, 20, "example.org")).isEqualTo("example.org" to 11)
        assertThat(Typing.insert("abcdef", 4, 2, "X")).isEqualTo("abXef" to 3)
        assertThat(Typing.insert("abc", 2, 9, "X")).isEqualTo("abcX" to 4)
    }
}
