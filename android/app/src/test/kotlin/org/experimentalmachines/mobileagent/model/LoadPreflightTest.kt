package org.experimentalmachines.mobileagent.model

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class LoadPreflightTest {
    @get:Rule val temp = TemporaryFolder()
    private val gb = 1_000_000_000L
    private fun sparse(f: File, bytes: Long) = f.also { RandomAccessFile(it, "rw").use { r -> r.setLength(bytes) } }

    @Test fun gpuOnlyAlwaysKeepsTheCache() {
        val dir = temp.newFolder(); val model = sparse(File(temp.root, "m.litertlm"), 3 * gb)
        assertEquals(dir.absolutePath, LlmRuntime.weightCacheDir(dir, model, cpu = false, freeBytes = 0))
    }
    @Test fun cpuWithRoomKeepsTheCache() {
        val dir = temp.newFolder(); val model = sparse(File(temp.root, "m.litertlm"), 3 * gb)
        assertEquals(dir.absolutePath, LlmRuntime.weightCacheDir(dir, model, cpu = true, freeBytes = 4 * gb))
    }
    @Test fun cpuWithoutRoomRunsUncachedAndDropsHalfWrittenPacks() {
        // The emulator crash: 1.6 GB free, a 2.76 GB model, a vision pack cut off mid-write.
        val dir = temp.newFolder(); val model = sparse(File(temp.root, "m.litertlm"), 2_760_755_451L)
        val pack = sparse(File(dir, "m.litertlm_1795758791_2760755451.vision_encoder.xnnpack_cache"), 406_605_824L)
        val other = File(dir, "other.bin").apply { writeText("x") }
        val otherModel = sparse(File(dir, "m.litertlm-v2.litertlm_1795758791_2760755451.xnnpack_cache"), 1000L)
        assertEquals(LlmRuntime.NO_CACHE, LlmRuntime.weightCacheDir(dir, model, cpu = true, freeBytes = 1_600_000_000L))
        assertFalse(pack.exists())
        assertTrue(other.exists())
        assertTrue(otherModel.exists()) // another model's packs are neither counted nor deleted
    }
    @Test fun completePacksNeedNoNewRoom() {
        val dir = temp.newFolder(); val model = sparse(File(temp.root, "m.litertlm"), 3 * gb)
        sparse(File(dir, "m.litertlm_1_${3 * gb}.xnnpack_cache"), 4 * gb)
        assertEquals(dir.absolutePath, LlmRuntime.weightCacheDir(dir, model, cpu = true, freeBytes = 100_000_000L))
    }
    @Test fun packsOfAnotherFileWithTheSameNameDoNotCount() {
        val dir = temp.newFolder(); val model = sparse(File(temp.root, "m.litertlm"), 3 * gb)
        sparse(File(dir, "m.litertlm_1_${2 * gb}.xnnpack_cache"), 4 * gb) // an older m.litertlm of another size
        assertEquals(LlmRuntime.NO_CACHE, LlmRuntime.weightCacheDir(dir, model, cpu = true, freeBytes = 100_000_000L))
    }
    @Test fun memoryCheck() {
        assertNotNull(LlmRuntime.memoryProblem(2_532_116_000L, 2_760_755_451L)) // the 2.5 GB design emulator
        assertNull(LlmRuntime.memoryProblem(8 * gb, 2_760_755_451L))
        assertNull(LlmRuntime.memoryProblem(0, 2_760_755_451L)) // unknown: do not block
    }
}
