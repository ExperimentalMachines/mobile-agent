package org.experimentalmachines.mobileagent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * The sidecar `model.json` next to a `.litertlm` file: which file, how to check it, and the fixed image grid its
 * vision encoder takes (the app resizes every screenshot to exactly this size).
 *
 * ```json
 * {"file": "LFM3-VL-2B-MobileAgent_int4_g1280x576_ctx4096.litertlm", "bytes": 2072561915, "sha256": "6008...",
 *  "image_height": 1280, "image_width": 576, "context_tokens": 4096, "display_name": "MobileAgent v4"}
 * ```
 */
@Serializable
data class ModelSpec(
    val file: String,
    val bytes: Long? = null,
    val sha256: String? = null,
    @SerialName("image_height") val imageHeight: Int,
    @SerialName("image_width") val imageWidth: Int,
    @SerialName("context_tokens") val contextTokens: Int = 4096,
    @SerialName("display_name") val displayName: String? = null,
    /** "v4" (system prompt v4 + installed-app line) or "v1" (models v1-v3). */
    @SerialName("prompt_version") val promptVersion: String = "v4",
    @SerialName("enable_thinking") val enableThinking: Boolean = true,
) {
    val name: String get() = displayName ?: file
}

/** A sidecar found in the models folder, and what the file next to it looks like right now. */
data class ModelEntry(val sidecar: File, val spec: ModelSpec?, val error: String?) {
    val modelFile: File? get() = spec?.let { File(sidecar.parentFile, it.file) }
    val present: Boolean get() = modelFile?.isFile == true
    val actualBytes: Long? get() = modelFile?.takeIf { it.isFile }?.length()
    val sizeOk: Boolean get() = present && (spec?.bytes == null || spec.bytes == actualBytes)

    /** One line for the model card. */
    fun problem(): String? = when {
        error != null -> "${sidecar.name}: $error"
        !present -> "Missing ${spec!!.file}"
        !sizeOk -> "${spec!!.file} is $actualBytes bytes, expected ${spec.bytes} (incomplete copy?)"
        else -> null
    }
}

object ModelStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun dir(externalFiles: File): File = File(externalFiles, "models")

    fun parse(text: String): ModelSpec = json.decodeFromString(ModelSpec.serializer(), text)

    /** Every `*.json` sidecar in the models folder, `model.json` first. */
    fun list(externalFiles: File): List<ModelEntry> =
        dir(externalFiles).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedWith(compareBy({ it.name != "model.json" }, { it.name }))
            .map { f ->
                runCatching { parse(f.readText()) }.fold(
                    { ModelEntry(f, it, null) },
                    { ModelEntry(f, null, "not a model sidecar (${it.message?.lineSequence()?.firstOrNull()})") },
                )
            }

    /** The `.litertlm` files that no sidecar names (shown so a missing sidecar is obvious). */
    fun orphans(externalFiles: File, entries: List<ModelEntry>): List<File> {
        val named = entries.mapNotNull { it.spec?.file }.toSet()
        return dir(externalFiles).listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.name !in named }.orEmpty().toList()
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
