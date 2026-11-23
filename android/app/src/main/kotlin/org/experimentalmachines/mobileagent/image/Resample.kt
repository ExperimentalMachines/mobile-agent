package org.experimentalmachines.mobileagent.image

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Pillow's `Image.resize(size, Image.BICUBIC)` for 8-bit RGB (libImaging/Resample.c: separable convolution, the
 * bicubic kernel with a = -0.5 widened by the downscale factor, 22-bit fixed-point weights, horizontal pass first).
 * Training and the benchmarks shrank screenshots this way (`agent_format.resize_screenshot`), so the phone feeds the
 * model the same pixels; a unit test checks it against Pillow's output.
 *
 * Pixels are packed ARGB ints (`Bitmap.getPixels`); alpha is ignored and written as 255.
 */
object Resample {
    private const val PRECISION_BITS = 32 - 8 - 2
    private const val SUPPORT = 2.0

    private fun bicubic(x0: Double): Double {
        val a = -0.5
        val x = if (x0 < 0.0) -x0 else x0
        if (x < 1.0) return ((a + 2.0) * x - (a + 3.0)) * x * x + 1
        if (x < 2.0) return (((x - 5) * x + 8) * x - 4) * a
        return 0.0
    }

    /** precompute_coeffs + normalize_coeffs_8bpc: per output index its first input index, tap count and int weights. */
    private class Coeffs(val ksize: Int, val bounds: IntArray, val kk: IntArray)

    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterscale = if (scale < 1.0) 1.0 else scale
        val support = SUPPORT * filterscale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val pre = DoubleArray(outSize * ksize)
        val ss = 1.0 / filterscale
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var ww = 0.0
            val base = xx * ksize
            for (x in 0 until xmax) {
                val w = bicubic((x + xmin - center + 0.5) * ss)
                pre[base + x] = w
                ww += w
            }
            for (x in 0 until xmax) if (ww != 0.0) pre[base + x] /= ww
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        val one = (1 shl PRECISION_BITS).toDouble()
        val kk = IntArray(pre.size) { i -> if (pre[i] < 0) (-0.5 + pre[i] * one).toInt() else (0.5 + pre[i] * one).toInt() }
        return Coeffs(ksize, bounds, kk)
    }

    private fun clip8(v: Int): Int {
        if (v >= (1 shl PRECISION_BITS shl 8)) return 255
        if (v <= 0) return 0
        return v shr PRECISION_BITS
    }

    /**
     * Resizes [src] ([srcW] x [srcH]) to [dstW] x [dstH]. [threads] > 1 splits rows across a small pool (the result is
     * identical: rows are independent). Pass order as in Pillow 13 (ImagingResampleInner): horizontal first, unless
     * the height shrinks by more than twice as many pixels as the width (a phone screenshot does), then vertical first.
     */
    fun bicubic(src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, threads: Int = 1): IntArray {
        require(src.size >= srcW * srcH)
        val needH = dstW != srcW
        val needV = dstH != srcH
        if (!needH && !needV) return IntArray(dstW * dstH) { src[it] or (0xFF shl 24) }
        val horizontalFirst = !((srcH - dstH) > 0 && (srcH - dstH) > (srcW - dstW) * 2)
        val pool = if (threads > 1) Executors.newFixedThreadPool(threads) else null
        try {
            val par = Par(pool, threads)
            val vert = if ((needH && horizontalFirst) || needV) coeffs(srcH, dstH) else null
            val horiz = if (needH) coeffs(srcW, dstW) else null
            return if (horizontalFirst) {
                var img = src
                var w = srcW
                if (needH) {
                    val first = vert!!.bounds[0]
                    val last = vert.bounds[dstH * 2 - 2] + vert.bounds[dstH * 2 - 1]
                    if (first != 0) for (i in 0 until dstH) vert.bounds[i * 2] -= first
                    img = horizontal(par, img, w, last - first, first, dstW, horiz!!)
                    w = dstW
                }
                if (needV) img = vertical(par, img, w, dstH, vert!!)
                img
            } else {
                var img = src
                if (needV) img = vertical(par, img, srcW, dstH, vert!!)
                if (needH) img = horizontal(par, img, srcW, dstH, 0, dstW, horiz!!)
                img
            }
        } finally {
            pool?.shutdown()
        }
    }

    private class Par(val pool: java.util.concurrent.ExecutorService?, val threads: Int) {
        fun run(n: Int, body: (Int, Int) -> Unit) {
            if (pool == null || n < 64) return body(0, n)
            val chunk = (n + threads - 1) / threads
            pool.invokeAll((0 until n step chunk).map { s -> Callable { body(s, minOf(n, s + chunk)) } }).forEach { it.get() }
        }
    }

    /** _ImagingResampleHorizontal_8bpc: [rows] output rows from input rows offset .. offset + rows. */
    private fun horizontal(par: Par, src: IntArray, srcW: Int, rows: Int, offset: Int, dstW: Int, c: Coeffs): IntArray {
        val out = IntArray(dstW * rows)
        val k = c.kk
        val ks = c.ksize
        par.run(rows) { from, to ->
            for (yy in from until to) {
                val inRow = (yy + offset) * srcW
                val outRow = yy * dstW
                for (xx in 0 until dstW) {
                    val xmin = c.bounds[xx * 2]
                    val xmax = c.bounds[xx * 2 + 1]
                    val kb = xx * ks
                    var r = 1 shl (PRECISION_BITS - 1)
                    var g = r
                    var b = r
                    for (x in 0 until xmax) {
                        val p = src[inRow + x + xmin]
                        val w = k[kb + x]
                        r += ((p shr 16) and 0xFF) * w
                        g += ((p shr 8) and 0xFF) * w
                        b += (p and 0xFF) * w
                    }
                    out[outRow + xx] = (0xFF shl 24) or (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
                }
            }
        }
        return out
    }

    /** _ImagingResampleVertical_8bpc: [dstH] rows of width [w]; bounds index the input rows. */
    private fun vertical(par: Par, src: IntArray, w: Int, dstH: Int, c: Coeffs): IntArray {
        val out = IntArray(w * dstH)
        val k = c.kk
        val ks = c.ksize
        par.run(dstH) { from, to ->
            for (yy in from until to) {
                val ymin = c.bounds[yy * 2]
                val ymax = c.bounds[yy * 2 + 1]
                val kb = yy * ks
                val outRow = yy * w
                for (xx in 0 until w) {
                    var r = 1 shl (PRECISION_BITS - 1)
                    var g = r
                    var b = r
                    for (y in 0 until ymax) {
                        val p = src[(y + ymin) * w + xx]
                        val wt = k[kb + y]
                        r += ((p shr 16) and 0xFF) * wt
                        g += ((p shr 8) and 0xFF) * wt
                        b += (p and 0xFF) * wt
                    }
                    out[outRow + xx] = (0xFF shl 24) or (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
                }
            }
        }
        return out
    }
}
