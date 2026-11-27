package org.experimentalmachines.mobileagent.model

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelInstallTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(): Triple<File, File, ModelSpec> {
        val dir = temp.newFolder()
        val part = File(dir, "model.download").apply { writeText("the complete model") }
        return Triple(dir, part, ModelSpec("model.litertlm", part.length(), ModelStore.sha256(part), 1280, 576))
    }
    @Test fun validDownloadIsInstalledWithSidecar() {
        val (dir, part, spec) = fixture()
        RecommendedModel.install(part, dir, spec)
        assertFalse(part.exists())
        assertEquals(spec, ModelStore.parse(File(dir, RecommendedModel.SIDECAR).readText()))
        assertEquals(spec.sha256, ModelStore.sha256(File(dir, spec.file)))
    }
    @Test fun partialDownloadCannotReplaceInstalledModel() {
        val (dir, part, spec) = fixture()
        val existing = File(dir, spec.file).apply { writeText("previous model") }
        part.writeText("partial")
        assertThrows(IllegalArgumentException::class.java) { RecommendedModel.install(part, dir, spec) }
        assertEquals("previous model", existing.readText())
        assertFalse(File(dir, RecommendedModel.SIDECAR).exists())
    }
    @Test fun corruptDownloadCannotBeActivatedEvenWithCorrectSize() {
        val (dir, part, spec) = fixture()
        part.writeBytes(ByteArray(part.length().toInt()) { 42 })
        assertThrows(IllegalArgumentException::class.java) { RecommendedModel.install(part, dir, spec) }
        assertFalse(File(dir, spec.file).exists())
        assertFalse(File(dir, RecommendedModel.SIDECAR).exists())
    }
    @Test fun restartAfterRenameCanFinishTheInstall() {
        val (dir, part, spec) = fixture()
        assertTrue(part.renameTo(File(dir, spec.file)))
        RecommendedModel.install(part, dir, spec)
        assertEquals(spec, ModelStore.parse(File(dir, RecommendedModel.SIDECAR).readText()))
    }
    @Test fun installedOnlyAfterAVerifiedInstall() {
        val dir = temp.newFolder()
        assertFalse(RecommendedModel.installed(dir))
        java.io.RandomAccessFile(File(dir, RecommendedModel.FILE), "rw").use { it.setLength(RecommendedModel.BYTES) } // sparse
        assertFalse(RecommendedModel.installed(dir)) // file without a sidecar: an unverified copy
        File(dir, RecommendedModel.SIDECAR).writeText("{}")
        assertTrue(RecommendedModel.installed(dir))
        File(dir, RecommendedModel.FILE).writeText("truncated")
        assertFalse(RecommendedModel.installed(dir))
    }
}
