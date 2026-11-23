package org.experimentalmachines.mobileagent.agent

/** Where typed text goes in a field: at the cursor, replacing a selection; appended when there is no cursor. */
object Typing {
    /** The field's new text and the cursor after the typed text. Selection indices as AccessibilityNodeInfo gives them (-1: none). */
    fun insert(existing: String, selStart: Int, selEnd: Int, text: String): Pair<String, Int> {
        val a = minOf(selStart, selEnd)
        val b = maxOf(selStart, selEnd)
        if (a < 0 || b > existing.length) return (existing + text) to (existing.length + text.length)
        return (existing.substring(0, a) + text + existing.substring(b)) to (a + text.length)
    }
}
