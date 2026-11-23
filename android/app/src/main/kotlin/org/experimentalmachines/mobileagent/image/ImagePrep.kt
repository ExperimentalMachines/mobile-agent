package org.experimentalmachines.mobileagent.image

import android.graphics.Bitmap
import android.os.SystemClock
import java.io.ByteArrayOutputStream

/** A screenshot made into the model's input: resized to the file's fixed grid, encoded as PNG. */
object ImagePrep {
    data class Prepared(val png: ByteArray, val width: Int, val height: Int, val resizeMs: Long, val encodeMs: Long)

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** [bitmap] (any size, ARGB_8888 software bitmap) to [gridH] x [gridW] with Pillow-identical bicubic, then PNG. */
    fun toModelPng(bitmap: Bitmap, gridH: Int, gridW: Int): Prepared {
        val t0 = SystemClock.elapsedRealtime()
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val out = Resample.bicubic(src, w, h, gridW, gridH, threads)
        val small = Bitmap.createBitmap(out, gridW, gridH, Bitmap.Config.ARGB_8888)
        val t1 = SystemClock.elapsedRealtime()
        val bytes = ByteArrayOutputStream(gridW * gridH).use { s ->
            small.compress(Bitmap.CompressFormat.PNG, 100, s)
            s.toByteArray()
        }
        small.recycle()
        val t2 = SystemClock.elapsedRealtime()
        return Prepared(bytes, gridW, gridH, t1 - t0, t2 - t1)
    }
}
