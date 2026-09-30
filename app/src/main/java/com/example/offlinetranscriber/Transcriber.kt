package com.example.offlinetranscriber

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/** Thrown when transcribe/translate is called with no model downloaded yet. */
class NoModelException : Exception("No model is installed. Download one first.")

/**
 * Wraps sherpa-onnx's Whisper recognizer, loading the currently active model
 * from disk (see ModelManager) by absolute file path -- sherpa-onnx's
 * no-AssetManager constructor reads straight from the filesystem, which is
 * what lets models be downloaded in-app instead of baked into the APK.
 */
class Transcriber(private val modelManager: ModelManager) {

    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null // "<modelId>|<language>|<task>"

    /** language: Whisper code such as "en", "de"; empty string = auto-detect. */
    @Synchronized
    private fun recognizerFor(language: String, task: String): OfflineRecognizer {
        val active = modelManager.activeModel() ?: throw NoModelException()
        val key = "${active.id}|$language|$task"
        if (recognizer != null && loadedKey == key) return recognizer!!
        recognizer?.release()

        val dir = active.dir
        val whisper = OfflineWhisperModelConfig(
            encoder = File(dir, "encoder.onnx").absolutePath,
            decoder = File(dir, "decoder.onnx").absolutePath,
            language = language,
            task = task,
        )
        val model = OfflineModelConfig(
            whisper = whisper,
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = 4,
            debug = false,
            provider = "cpu",
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = model,
        )
        // No assetManager argument -> sherpa-onnx loads the files from the paths above.
        return OfflineRecognizer(config = config).also {
            recognizer = it
            loadedKey = key
        }
    }

    fun transcribe(
        pcm: Pcm,
        language: String,
        task: String = "transcribe", // "transcribe" or "translate" (to English)
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): String {
        val chunks = AudioChunker.chunks(pcm.samples, pcm.sampleRate)
        return transcribeChunks(pcm, language, task, chunks, onProgress, isCancelled)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    /**
     * Same as [transcribe] but returns one (possibly empty) string per chunk in
     * [chunks], instead of joining them. Used for subtitle export, where each
     * chunk becomes one timed cue -- and where calling this with the *same*
     * [chunks] list for both "transcribe" and "translate" is what keeps
     * bilingual subtitle lines aligned in time.
     */
    fun transcribeChunks(
        pcm: Pcm,
        language: String,
        task: String,
        chunks: List<AudioChunk>,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): List<String> {
        val rec = recognizerFor(language, task)
        val texts = ArrayList<String>(chunks.size)
        chunks.forEachIndexed { i, c ->
            if (isCancelled()) throw InterruptedException("Cancelled")
            onProgress(i, chunks.size)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(pcm.samples.copyOfRange(c.startSample, c.endSample), pcm.sampleRate)
                rec.decode(stream)
                texts.add(rec.getResult(stream).text.trim())
            } finally {
                stream.release()
            }
        }
        onProgress(chunks.size, chunks.size)
        return texts
    }

    /** Call after switching/removing the active model so a stale recognizer isn't reused. */
    @Synchronized
    fun invalidate() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }

    @Synchronized
    fun release() = invalidate()
}
