package org.experimentalmachines.mobileagent.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.experimentalmachines.mobileagent.Settings
import java.io.File

/** Pinned release: the download cannot silently change when the Hub's main branch changes. */
object RecommendedModel {
    const val REPO = "experimentalmachines/LFM3-VL-2B-MobileAgent-LiteRT"
    const val REVISION = "671cbb92502fc6621d8cad9f4392e5aa004b5638"
    const val FILE = "LFM3-VL-2B-MobileAgent_int8_g1280x576_ctx4096.litertlm"
    const val BYTES = 2760755451L
    const val SHA256 = "8eba580944ec4627986698392fa235acca02dfc469bf1e00dc6d22288156870d"
    const val SIDECAR = "recommended.json"
    val url = "https://huggingface.co/$REPO/resolve/$REVISION/$FILE?download=true"
    val spec = ModelSpec(FILE, BYTES, SHA256, 1280, 576, 4096, "Mobile Agent · int8")

    /** The sidecar is written only after a verified rename, so its presence means a verified install. */
    internal fun installed(dir: File) = File(dir, SIDECAR).isFile && File(dir, FILE).length() == BYTES

    /** Never expose an incomplete or mismatched download to ModelStore. */
    internal fun install(part: File, dir: File, expected: ModelSpec = spec) {
        val target = File(dir, expected.file)
        val source = if (part.isFile) part else target // recover a process killed after the verified rename
        require(source.length() == expected.bytes) { "Download is incomplete. Please try again." }
        require(ModelStore.sha256(source) == expected.sha256) { "The checksum did not match. Please download again." }
        check(source == target || source.renameTo(target)) { "Could not save the model. Check available storage." }
        val pending = File(dir, "$SIDECAR.tmp")
        pending.writeText(Json.encodeToString(ModelSpec.serializer(), expected))
        check(pending.renameTo(File(dir, SIDECAR))) { "Could not save model details. Please try again." }
    }
}

/** Android owns the transfer, so it survives navigation and process death; our sidecar is committed only after verification. */
class ModelInstaller(context: Context, private val models: ModelManager, private val settings: Settings) {
    data class State(val phase: String = "idle", val bytes: Long = 0, val message: String = "") {
        val busy get() = phase in listOf("downloading", "waiting", "verifying", "cancelling")
    }
    private val context = context.applicationContext
    private val dm = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("model_download", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var worker: Job? = null
    private val part get() = File(models.modelsDir, RecommendedModel.FILE + ".download")
    private var id: Long
        get() = prefs.getLong("id", -1)
        set(value) { prefs.edit().putLong("id", value).commit() }

    init { if (id != -1L) watch(id) }

    fun start() {
        if (_state.value.busy) return
        _state.value = State("downloading")
        worker = scope.launch {
            try {
                val previous = id
                if (previous != -1L) withContext(Dispatchers.IO) { dm.remove(previous) }
                id = -1
                withContext(Dispatchers.IO) {
                    part.delete()
                    check(models.modelsDir.usableSpace > RecommendedModel.BYTES + 256L * 1024 * 1024) {
                        "Free at least 3.1 GB of storage, then try again."
                    }
                }
                val request = DownloadManager.Request(Uri.parse(RecommendedModel.url))
                    .setTitle("Mobile Agent model")
                    .setDescription("Downloading the on-device model from Hugging Face")
                    .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI) // "Wi-Fi only": unmetered cellular too is excluded
                    .setAllowedOverMetered(false)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(context, null, "models/${part.name}")
                val next = dm.enqueue(request) // persist without a cancellation point after enqueue
                id = next
                // This coroutine now owns monitoring; do not launch another request on repeated taps.
                monitor(next)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = State("failed", message = e.message ?: "Download could not start.") }
        }
    }

    fun cancel() {
        if (_state.value.phase == "verifying") return
        worker?.cancel()
        val previous = id
        _state.value = State("cancelling")
        worker = scope.launch {
            withContext(Dispatchers.IO) {
                if (previous != -1L) dm.remove(previous)
                part.delete()
            }
            id = -1 // only now: a process killed before this point re-finds the transfer and can still cancel it
            _state.value = State(message = "Download cancelled. You can start again anytime.")
        }
    }

    private fun watch(download: Long) {
        _state.value = State("downloading")
        worker = scope.launch {
            try { monitor(download) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.value = State("failed", message = e.message ?: "Download failed. Please try again.") }
        }
    }

    private fun activate() {
        settings.update { it.copy(modelSidecar = RecommendedModel.SIDECAR) }
        models.refresh()
        _state.value = State("ready", RecommendedModel.BYTES)
    }

    private suspend fun monitor(download: Long) {
        while (currentCoroutineContext().isActive) {
            val status = withContext(Dispatchers.IO) {
                dm.query(DownloadManager.Query().setFilterById(download)).use { c ->
                    if (!c.moveToFirst()) return@use null
                    Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                }
            }
            if (status == null) { // record gone: killed after the install but before the transfer was forgotten?
                id = -1
                check(RecommendedModel.installed(models.modelsDir)) { "Download was removed. Please try again." }
                activate()
                return
            }
            when (status.first) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    _state.value = State("verifying", RecommendedModel.BYTES)
                    withContext(Dispatchers.IO) { RecommendedModel.install(part, models.modelsDir) }
                    activate()
                    // Forget the transfer last: a restart before this line re-verifies and re-activates.
                    // Removing the record after the rename cannot remove the installed model.
                    withContext(Dispatchers.IO) { dm.remove(download) }
                    id = -1
                    return
                }
                DownloadManager.STATUS_FAILED -> error(when (status.third) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage. Free 3.1 GB and try again."
                    else -> "Download failed (${status.third}). Check your connection and try again."
                })
                DownloadManager.STATUS_PAUSED -> _state.value = State("waiting", status.second, "Waiting for Wi-Fi or a connection. Android will retry automatically.")
                else -> _state.value = State("downloading", status.second)
            }
            delay(750)
        }
    }
}
