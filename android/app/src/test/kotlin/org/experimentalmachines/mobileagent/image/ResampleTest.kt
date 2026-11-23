package org.experimentalmachines.mobileagent.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.imageio.ImageIO

/** The resampler against Pillow's BICUBIC output for the same image (tools/gen_golden.py). */
class ResampleTest {
    private fun png(name: String) = ImageIO.read(javaClass.getResourceAsStream("/golden/$name")!!)

    @Test
    fun matchesPillowBicubicPortrait() = check("resize_in.png", "resize_out.png")

    @Test
    fun matchesPillowBicubicLandscape() = check("resize_in_land.png", "resize_out_land.png")

    private fun check(input: String, expected: String) {
        val src = png(input)
        val want = png(expected)
        val pixels = src.getRGB(0, 0, src.width, src.height, null, 0, src.width)
        for (threads in listOf(1, 4)) {
            val got = Resample.bicubic(pixels, src.width, src.height, want.width, want.height, threads)
            var maxDiff = 0
            var differing = 0
            for (y in 0 until want.height) for (x in 0 until want.width) {
                val a = got[y * want.width + x]
                val b = want.getRGB(x, y)
                val d = maxOf(
                    kotlin.math.abs(((a shr 16) and 255) - ((b shr 16) and 255)),
                    kotlin.math.abs(((a shr 8) and 255) - ((b shr 8) and 255)),
                    kotlin.math.abs((a and 255) - (b and 255)),
                )
                if (d > 0) differing++
                maxDiff = maxOf(maxDiff, d)
            }
            assertThat(differing).isEqualTo(0)
            assertThat(maxDiff).isEqualTo(0)
        }
    }

    @Test
    fun identityAndOneAxis() {
        val src = IntArray(6 * 4) { (it * 37) and 0xFFFFFF }
        assertThat(Resample.bicubic(src, 6, 4, 6, 4).map { it and 0xFFFFFF }).isEqualTo(src.toList())
        assertThat(Resample.bicubic(src, 6, 4, 3, 4).size).isEqualTo(12)
        assertThat(Resample.bicubic(src, 6, 4, 6, 2).size).isEqualTo(12)
    }
}
