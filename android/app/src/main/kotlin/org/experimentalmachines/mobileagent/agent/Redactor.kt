package org.experimentalmachines.mobileagent.agent

/**
 * Typed secrets kept out of logs: text typed into a password field, or by a step the guard flagged, is replaced
 * everywhere it appears in the run's records (goal, intents, model replies, actions, outcomes, answer) and logcat.
 * The model's own history keeps the real text (the prompt must stay as in the benchmarks); only what is written down
 * is scrubbed.
 */
class Redactor {
    private val secrets = linkedSetOf<String>()

    fun add(secret: String?) {
        if (secret != null && secret.length >= MIN_LENGTH) secrets += secret
    }

    val active: Boolean get() = secrets.isNotEmpty()

    /** [s] with every secret replaced, longest first (also their JSON-escaped forms, as in model replies). */
    fun scrub(s: String?): String? {
        if (s == null || secrets.isEmpty()) return s
        var out: String = s
        for (secret in secrets.sortedByDescending { it.length }) {
            out = out.replace(secret, MASK)
            val escaped = org.experimentalmachines.mobileagent.protocol.PyJsonWriter.string(secret).removeSurrounding("\"")
            if (escaped != secret) out = out.replace(escaped, MASK)
        }
        return out
    }

    companion object {
        const val MASK = "[redacted]"
        /** Shorter strings would mask ordinary words. */
        const val MIN_LENGTH = 2
    }
}
