package org.experimentalmachines.mobileagent.agent

/**
 * The research repo's deployment-style `open_app` resolution (eval/androidworld/app_resolver.py): when a name does
 * not match a launcher label directly, the label that matches it best *lexically* (token equality, a shared prefix of
 * 4+ letters, or difflib similarity >= 0.8 for 5+ letters; no hand-written synonyms), if at least half of the name's
 * tokens match. "Clock app" -> Clock, "Calender" -> Calendar; "Messages" does not become "Messaging".
 */
object AppResolver {
    private val GENERIC = setOf("google", "simple", "pro", "app", "the", "my", "android")

    fun tokens(s: String): List<String> =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").split(' ').filter { it.isNotEmpty() && it !in GENERIC }

    private fun tokMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a))) return true
        return minOf(a.length, b.length) >= 5 && ratio(a, b) >= 0.8
    }

    fun score(query: String, label: String): Double {
        val q = tokens(query)
        val l = tokens(label)
        if (q.isEmpty() || l.isEmpty()) return 0.0
        return q.count { a -> l.any { b -> tokMatch(a, b) } }.toDouble() / q.size
    }

    /** The best label and its score, or null below [threshold]; ties go to the shorter label (as `max((score, -len, l))`). */
    fun resolve(name: String, labels: List<String>, threshold: Double = 0.5): Pair<String, Double>? {
        val best = labels.maxWithOrNull(compareBy<String>({ score(name, it) }, { -it.length }, { it })) ?: return null
        val s = score(name, best)
        return if (s >= threshold) best to s else null
    }

    /** difflib.SequenceMatcher(None, a, b).ratio() (no junk; autojunk only applies to 200+ characters). */
    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val b2j = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() } += j }
        fun longest(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
            var besti = alo
            var bestj = blo
            var bestsize = 0
            var j2len = HashMap<Int, Int>()
            for (i in alo until ahi) {
                val newj2len = HashMap<Int, Int>()
                for (j in b2j[a[i]].orEmpty()) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newj2len[j] = k
                    if (k > bestsize) {
                        besti = i - k + 1
                        bestj = j - k + 1
                        bestsize = k
                    }
                }
                j2len = newj2len
            }
            return Triple(besti, bestj, bestsize)
        }
        var matches = 0
        val queue = ArrayDeque(listOf(intArrayOf(0, a.length, 0, b.length)))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast().let { listOf(it[0], it[1], it[2], it[3]) }
            val (i, j, k) = longest(alo, ahi, blo, bhi)
            if (k > 0) {
                matches += k
                if (alo < i && blo < j) queue += intArrayOf(alo, i, blo, j)
                if (i + k < ahi && j + k < bhi) queue += intArrayOf(i + k, ahi, j + k, bhi)
            }
        }
        return 2.0 * matches / (a.length + b.length)
    }
}
