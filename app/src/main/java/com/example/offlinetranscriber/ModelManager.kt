package com.example.offlinetranscriber

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.edit

/** One downloadable Whisper model, as published in sherpa-onnx's GitHub releases. */
data class ModelInfo(
    val id: String,          // e.g. "base" -- also the archive/file name prefix upstream
    val label: String,       // shown in the picker
    val approxDownloadMb: Int,
    val multilingual: Boolean,
) {
    val archiveUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-whisper-$id.tar.bz2"
}

/**
 * Downloads, stores, switches between, and removes Whisper models in the app's
 * private files directory (not assets - those are fixed at build time). Only
 * ever keeps ONE model on disk: picking a new one downloads it to a temp folder
 * first and only deletes the old one after the new one verifies complete, so a
 * failed/cancelled download never leaves the app without a working model.
 */
class ModelManager(private val context: Context) {

    companion object {
        val CATALOG = listOf(
            ModelInfo("tiny", "Tiny (~115 MB, fastest, weakest)", 115, multilingual = true),
            ModelInfo("base", "Base (~200 MB, good default)", 200, multilingual = true),
            ModelInfo("small", "Small (~640 MB, best accuracy)", 640, multilingual = true),
            ModelInfo("tiny.en", "Tiny, English only (~115 MB)", 115, multilingual = false),
            ModelInfo("base.en", "Base, English only (~200 MB)", 200, multilingual = false),
            ModelInfo("small.en", "Small, English only (~640 MB)", 640, multilingual = false),
        )

        private const val PREFS = "model_manager"
        private const val KEY_ACTIVE_ID = "active_model_id"
    }

    private val modelsRoot = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class InstalledModel(val id: String, val dir: File)

    /** Null if no model has finished downloading yet. */
    fun activeModel(): InstalledModel? {
        val id = prefs.getString(KEY_ACTIVE_ID, null) ?: return null
        val dir = File(modelsRoot, id)
        val ok = File(dir, "encoder.onnx").length() > 0 &&
            File(dir, "decoder.onnx").length() > 0 &&
            File(dir, "tokens.txt").length() > 0
        if (!ok) return null // e.g. app was killed mid-install; treat as "no model"
        return InstalledModel(id, dir)
    }

    fun infoFor(id: String): ModelInfo? = CATALOG.find { it.id == id }

    /**
     * Downloads [model], extracting only the three files we need straight out of
     * the .tar.bz2 stream (skips fp32 weights and test audio in the archive, so
     * we never write more to disk than necessary). On success, replaces whatever
     * model was active before. Throws on failure/cancellation and leaves the
     * previously active model untouched.
     */
    fun download(model: ModelInfo, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit) {
        val tmpDir = File(modelsRoot, "${model.id}.downloading")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        val connection = (URL(model.archiveUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Server returned HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong // -1 if unknown; UI falls back to a spinner

            // Throttled: the un-throttled version calls onProgress() (which posts to the
            // UI thread) on every single stream read -- tens of thousands of times for a
            // large file -- which floods the main thread's message queue and can trigger
            // an ANR that looks like the app freezing, with nothing saved when it's killed.
            var readTotal = 0L
            var lastReportedAt = 0L
            val reportEveryBytes = 256 * 1024L
            fun maybeReport() {
                if (readTotal - lastReportedAt >= reportEveryBytes) {
                    lastReportedAt = readTotal
                    onProgress(readTotal, total)
                }
            }
            val counting = object : java.io.FilterInputStream(connection.inputStream) {
                override fun read(): Int = super.read().also { if (it >= 0) { readTotal++; maybeReport() } }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { n -> if (n > 0) { readTotal += n; maybeReport() } }
            }

            var gotEncoder = false; var gotDecoder = false; var gotTokens = false
            BZip2CompressorInputStream(counting).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val dest = when {
                            name.endsWith("-encoder.int8.onnx") -> File(tmpDir, "encoder.onnx")
                            name.endsWith("-decoder.int8.onnx") -> File(tmpDir, "decoder.onnx")
                            name.endsWith("-tokens.txt") -> File(tmpDir, "tokens.txt")
                            else -> null // skip fp32 weights, test_wavs/, etc.
                        }
                        if (dest != null && !entry.isDirectory) {
                            dest.outputStream().use { out -> tar.copyTo(out) }
                            when (dest.name) {
                                "encoder.onnx" -> gotEncoder = true
                                "decoder.onnx" -> gotDecoder = true
                                "tokens.txt" -> gotTokens = true
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            if (!(gotEncoder && gotDecoder && gotTokens)) {
                throw IOException("Archive was missing expected model files")
            }
            onProgress(readTotal, total) // final update so the UI shows 100%

            // Only touch the previously-active model after the new one is verified complete.
            val previous = activeModel()
            val finalDir = File(modelsRoot, model.id)
            finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) throw IOException("Could not finalize downloaded model")
            prefs.edit { putString(KEY_ACTIVE_ID, model.id) }
            if (previous != null && previous.id != model.id) previous.dir.deleteRecursively()
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Frees space; leaves the app in a "no model installed" state. */
    fun removeActiveModel() {
        activeModel()?.dir?.deleteRecursively()
        prefs.edit { remove(KEY_ACTIVE_ID) }
    }
}
