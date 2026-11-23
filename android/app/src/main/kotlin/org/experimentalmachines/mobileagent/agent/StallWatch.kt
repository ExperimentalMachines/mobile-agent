package org.experimentalmachines.mobileagent.agent

import android.os.SystemClock
import android.util.Log

/**
 * Names the call a stuck agent step is in. Coroutine timeouts cannot end a blocking call (an accessibility tree walk,
 * a binder call) or a hop onto a blocked main thread, so a step marks its phase here and a daemon thread logs every
 * relevant thread's stack once when a phase outlives its limit.
 */
object StallWatch {
    private const val TAG = "MobileAgent"
    private const val POLL_MS = 5_000L
    private const val MAX_FRAMES = 40

    @Volatile private var phase: String? = null
    @Volatile private var since = 0L
    @Volatile private var limitMs = 0L
    @Volatile private var reported = false

    private val watcher = Thread(::loop, "mobileagent-stallwatch").apply { isDaemon = true }

    @Synchronized
    fun start() {
        if (!watcher.isAlive) watcher.start()
    }

    fun enter(name: String, limit: Long) {
        start()
        since = SystemClock.elapsedRealtime()
        limitMs = limit
        reported = false
        phase = name
    }

    fun exit() {
        phase = null
    }

    inline fun <T> phase(name: String, limit: Long, block: () -> T): T {
        enter(name, limit)
        try {
            return block()
        } finally {
            exit()
        }
    }

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val p = phase ?: continue
            val waited = SystemClock.elapsedRealtime() - since
            if (!reported && waited > limitMs) {
                reported = true
                dump(p, waited)
            }
        }
    }

    private fun dump(p: String, waited: Long) {
        Log.w(TAG, "STALL: step phase '$p' running for ${waited / 1000} s (limit ${limitMs / 1000} s); thread stacks follow")
        for ((thread, frames) in Thread.getAllStackTraces()) {
            val ours = thread.name == "main" || frames.any { it.className.startsWith("org.experimentalmachines") }
            if (!ours) continue
            Log.w(TAG, "STALL thread \"${thread.name}\" ${thread.state}")
            frames.take(MAX_FRAMES).forEach { Log.w(TAG, "STALL     at $it") }
        }
    }
}
