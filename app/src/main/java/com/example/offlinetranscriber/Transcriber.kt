package com.example.offlinetranscriber

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlin.math.abs

/**
 * Wraps sherpa-onnx's Whisper recognizer. Whisper handles at most ~30 s per
 * pass, so longer audio is split into <=28 s chunks, cutting at the quietest
 * point near each boundary to avoid slicing through words.
 */
class Transcriber(private val context: Context) {

    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null

    /** language: Whisper code such as "en", "de"; empty string = auto-detect. */
    @Synchronized
    private fun recognizerFor(language: String, task: String): OfflineRecognizer {
        val key = "$language|$task"
        if (recognizer != null && loadedKey == key) return recognizer!!
        recognizer?.release()

        val whisper = OfflineWhisperModelConfig(
            encoder = "whisper/encoder.onnx",
            decoder = "whisper/decoder.onnx",
            language = language,
            task = task,
        )
        val model = OfflineModelConfig(
            whisper = whisper,
            tokens = "whisper/tokens.txt",
            numThreads = 4,
            debug = false,
            provider = "cpu",
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = model,
        )
        // Passing the AssetManager makes sherpa-onnx read the model straight from the APK.
        return OfflineRecognizer(assetManager = context.assets, config = config).also {
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
        val rec = recognizerFor(language, task)
        val chunks = split(pcm.samples, pcm.sampleRate)
        val parts = ArrayList<String>()
        chunks.forEachIndexed { i, (start, end) ->
            if (isCancelled()) throw InterruptedException("Cancelled")
            onProgress(i, chunks.size)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(pcm.samples.copyOfRange(start, end), pcm.sampleRate)
                rec.decode(stream)
                val text = rec.getResult(stream).text.trim()
                if (text.isNotEmpty()) parts.add(text)
            } finally {
                stream.release()
            }
        }
        onProgress(chunks.size, chunks.size)
        return parts.joinToString(" ")
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }

    /** Returns (startSample, endSampleExclusive) pairs. */
    private fun split(x: FloatArray, sr: Int): List<Pair<Int, Int>> {
        val maxLen = 28 * sr
        val searchLen = 4 * sr
        val frame = (0.02f * sr).toInt().coerceAtLeast(1)
        val result = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < x.size) {
            if (x.size - start <= maxLen) { result.add(start to x.size); break }
            val hardEnd = start + maxLen
            var bestPos = hardEnd
            var bestEnergy = Float.MAX_VALUE
            var p = hardEnd - searchLen
            while (p + frame <= hardEnd) {
                var e = 0f
                for (k in p until p + frame) e += abs(x[k])
                if (e < bestEnergy) { bestEnergy = e; bestPos = p + frame / 2 }
                p += frame
            }
            result.add(start to bestPos)
            start = bestPos
        }
        return result
    }
}
