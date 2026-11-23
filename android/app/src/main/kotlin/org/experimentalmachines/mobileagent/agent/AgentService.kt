package org.experimentalmachines.mobileagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The agent's eyes and hands. Android binds this service once the user turns it on in
 * Accessibility settings; it can then take screenshots (API 30+), dispatch gestures, press Back / Home, set text in
 * the focused field, and read the window tree for the safety guard.
 */
class AgentService : AccessibilityService() {

    private val shotExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** When another app's UI last changed (scrolled, redrew, opened a window). */
    @Volatile private var lastUiEventAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        lastUiEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        shotExecutor.shutdown()
        super.onDestroy()
    }

    /**
     * Waits until the screen has stopped changing: [quietMs] without a UI event from another app, at most [maxMs].
     * (A fixed second is not always enough: a fling can still be scrolling when the next screenshot is taken.)
     */
    suspend fun awaitQuiet(quietMs: Long = QUIET_MS, maxMs: Long = SETTLE_MAX_MS) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - lastUiEventAt >= quietMs || now - start >= maxMs) return
            delay(POLL_MS)
        }
    }

    // ---- Observe --------------------------------------------------------------------------------------------------

    /** The display as a software ARGB bitmap (physical pixels). takeScreenshot refuses calls ~1/3 s apart: retried. */
    suspend fun screenshot(): Bitmap {
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            val result = runCatching { takeOne() }
            result.getOrNull()?.let { return it }
            if (attempt == SCREENSHOT_ATTEMPTS - 1) throw result.exceptionOrNull()!!
            delay(SCREENSHOT_RETRY_MS)
        }
        error("unreachable")
    }

    private suspend fun takeOne(): Bitmap = withTimeout(SCREENSHOT_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            ?: return cont.resumeWithException(IllegalStateException("screenshot buffer could not be wrapped"))
                        val soft = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped.recycle()
                        cont.resume(toSrgb(soft))
                    } finally {
                        hw.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    cont.resumeWithException(IllegalStateException("takeScreenshot failed with code $errorCode"))
                }
            })
        }
    }

    /** The model was trained on sRGB screenshots: a wide-gamut capture (e.g. Display P3) is converted. */
    private fun toSrgb(b: Bitmap): Bitmap {
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val cs = b.colorSpace
        if (!loggedColorSpace) {
            loggedColorSpace = true
            Log.i(TAG, "screenshot ${b.width}x${b.height}, colour space ${cs?.name}")
        }
        if (cs == null || cs == srgb) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888, false, srgb)
        Canvas(out).drawBitmap(b, 0f, 0f, null)
        b.recycle()
        return out
    }

    @Volatile private var loggedColorSpace = false

    /** The windows, the element under ([x], [y]) and the focused field, for the safety guard. */
    fun live(x: Int?, y: Int?): Guard.Live {
        val packages = windows.map { it.root?.packageName?.toString() }
        var labels = emptyList<String>()
        var targetPackage: String? = null
        var readable = true
        if (x != null && y != null) {
            val hit = liveNodeAt(x, y)
            readable = hit != null
            hit?.let { node ->
                targetPackage = node.packageName?.toString()
                val out = mutableListOf<String>()
                var n: AccessibilityNodeInfo? = node
                var depth = 0
                while (n != null && depth < 4) {
                    listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.viewIdResourceName?.substringAfter(":id/")?.replace('_', ' '))
                        .map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
                    n = n.parent
                    depth++
                }
                labels = out
                val cls = node.className?.toString().orEmpty()
                if (out.isEmpty() && OPAQUE.any { cls.endsWith(it) }) readable = false
            }
        }
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val hidden = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.root == null }
        return Guard.Live(
            packages, labels, targetPackage, focused?.isPassword == true, focused?.packageName?.toString(),
            protectedWindow = hidden?.let { it.title?.toString() ?: "untitled" },
            foregroundPackage = foreground(),
            screenTitle = screenTitle(),
            targetReadable = readable,
        )
    }

    /** The app in front: the active app window (not this app's), "protected: <title>" if its content is hidden. */
    fun foreground(): String? {
        val w = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.root?.packageName?.toString() != packageName }
            .let { list -> list.firstOrNull { it.isActive } ?: list.maxByOrNull { it.layer } } ?: return null
        return w.root?.packageName?.toString() ?: "protected: ${w.title}"
    }

    /** The active app window's title and its heading texts (Settings pages mark their title as a heading). */
    private fun screenTitle(): String? {
        val w = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive } ?: return null
        val parts = mutableListOf<String>()
        w.title?.toString()?.let { parts += it }
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || parts.size > 6) return
            if (n.isHeading) (n.text ?: n.contentDescription)?.toString()?.let { parts += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        w.root?.let { walk(it, 0) }
        return parts.distinct().joinToString(" | ").ifEmpty { null }
    }

    /** One line per window as this service sees it (diagnostics: `WINDOWS` adb hook). */
    fun describeWindows(): List<String> = windows.map { w ->
        val r = w.root
        "type=${w.type} layer=${w.layer} active=${w.isActive} title=${w.title} pkg=${r?.packageName} root=${r != null} children=${r?.childCount}"
    }

    private fun touchable(w: AccessibilityWindowInfo, x: Int, y: Int): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val region = android.graphics.Region()
            w.getRegionInScreen(region)
            if (!region.isEmpty) return region.contains(x, y)
        }
        return Rect().also(w::getBoundsInScreen).contains(x, y)
    }

    /** The deepest live node containing the point, top window first, skipping accessibility overlays. */
    private fun liveNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        fun deepest(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > MAX_DEPTH) return null
            val b = Rect().also(n::getBoundsInScreen)
            if (!b.contains(x, y)) return null
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { c -> deepest(c, depth + 1)?.let { return it } }
            return n
        }
        // The top window whose touchable region holds the point gets the touch (as input dispatch routes it). Not the
        // first root that contains it: the keyboard's root spans the whole screen while only its keys take touches,
        // so a tap on an app's Send button above the keyboard was read as a tap on the keyboard (seen on Messaging).
        return windows.sortedByDescending { it.layer }
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.root?.packageName?.toString() != packageName }
            .filter { w -> touchable(w, x, y) }
            .firstNotNullOfOrNull { w -> w.root?.let { deepest(it, 0) } }
    }

    // ---- Act (AndroidWorld's actuation, android_world/env/actuation.py) -------------------------------------------

    suspend fun tap(x: Int, y: Int, durationMs: Long) {
        gesture(GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, durationMs))
    }

    suspend fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long) {
        val path = Path().apply {
            moveTo(x0.toFloat(), y0.toFloat())
            lineTo(x1.toFloat(), y1.toFloat())
        }
        gesture(GestureDescription.StrokeDescription(path, 0, durationMs))
    }

    private suspend fun gesture(stroke: GestureDescription.StrokeDescription) {
        val description = GestureDescription.Builder().addStroke(stroke).build()
        val done = withTimeoutOrNull(stroke.duration + GESTURE_GRACE_MS) {
            suspendCancellableCoroutine { cont ->
                val dispatched = dispatchGesture(description, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = cont.resume(true)
                    override fun onCancelled(gestureDescription: GestureDescription?) = cont.resume(false)
                }, null)
                if (!dispatched) cont.resume(false)
            }
        }
        if (done == null) Log.w(TAG, "gesture result did not arrive") else if (!done) Log.w(TAG, "gesture was cancelled")
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Types into the focused field the way a keyboard would (AndroidWorld types with `adb shell input text`): at the
     * cursor, replacing a selection; at the end when the field reports no cursor. The cursor then follows the text.
     */
    fun typeText(text: String): String {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return "no text field is focused"
        val existing = field.text?.toString()?.takeUnless { field.isShowingHintText }.orEmpty()
        val (value, cursor) = Typing.insert(existing, field.textSelectionStart, field.textSelectionEnd, text)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "typing failed"
        field.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return "typed"
    }

    /** Enter in the focused field (the keyboard's action key). */
    fun pressEnter(): String {
        val field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "no focused field for enter"
        return if (field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) "pressed enter" else "enter failed"
    }

    fun openApp(name: String): String {
        val app = InstalledApps.resolve(InstalledApps.list(this), name) ?: return "no app called \"$name\""
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { startActivity(intent) }.fold({ "opened ${app.label}" }, { "could not open ${app.label}: ${it.message}" })
    }

    // ---- Tap indicator ---------------------------------------------------------------------------------------------

    /**
     * A ring where a tap landed, drawn as an accessibility overlay (not touchable, trusted for touch occlusion) for
     * [RING_MS] after the gesture; gone long before the next screenshot.
     */
    fun showTap(x: Int, y: Int) = main.post {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = (RING_DP * resources.displayMetrics.density).toInt()
        val view = RingView(this)
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - size / 2
            this.y = y - size / 2
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        runCatching { wm.addView(view, lp) }.onFailure { return@post }
        view.animate().alpha(0f).setStartDelay(RING_MS / 2).setDuration(RING_MS / 2).withEndAction {
            runCatching { wm.removeView(view) }
        }.start()
    }

    private class RingView(context: Context) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * context.resources.displayMetrics.density
            color = 0xFF8FA6FF.toInt()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x558FA6FF }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            canvas.drawCircle(r, r, r * 0.42f, fill)
            canvas.drawCircle(r, r, r - stroke.strokeWidth, stroke)
        }
    }

    companion object {
        private const val TAG = "MobileAgent"
        private const val SCREENSHOT_ATTEMPTS = 3
        private const val SCREENSHOT_RETRY_MS = 350L
        private const val SCREENSHOT_TIMEOUT_MS = 5_000L
        private const val GESTURE_GRACE_MS = 5_000L
        private const val MAX_DEPTH = 40
        private const val QUIET_MS = 500L
        private const val SETTLE_MAX_MS = 4_000L
        private const val POLL_MS = 100L
        private const val RING_DP = 56
        private const val RING_MS = 700L

        /** Views that draw their own content without accessibility nodes. */
        private val OPAQUE = listOf("WebView", "SurfaceView", "TextureView", "GLSurfaceView")

        @Volatile var instance: AgentService? = null
            private set
    }
}
