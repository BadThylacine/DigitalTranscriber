package com.example.offlinetranscriber

import kotlin.math.abs

/** Sample-index range for one audio segment: start (inclusive) to end (exclusive). */
data class AudioChunk(val startSample: Int, val endSample: Int) {
    fun startSeconds(sampleRate: Int) = startSample.toDouble() / sampleRate
    fun endSeconds(sampleRate: Int) = endSample.toDouble() / sampleRate
}

/**
 * Splits audio into chunks short enough for Whisper's ~30s window, cutting at
 * the quietest point near each boundary rather than mid-word. Used for BOTH
 * plain transcription and subtitle export, so a transcribe pass and a
 * translate pass of the same audio share identical boundaries -- this is what
 * keeps bilingual subtitle lines aligned in time without any extra matching.
 *
 * Default is short enough (10s) to make reasonable subtitle cues, and as a
 * side effect keeps any single hallucination/repetition loop confined to a
 * smaller stretch of audio than the old 28s chunks did.
 */
object AudioChunker {
    fun chunks(x: FloatArray, sampleRate: Int, maxSeconds: Int = 10, searchSeconds: Int = 2): List<AudioChunk> {
        val maxLen = maxSeconds * sampleRate
        val searchLen = searchSeconds * sampleRate
        val frame = (0.02f * sampleRate).toInt().coerceAtLeast(1)
        val result = ArrayList<AudioChunk>()
        var start = 0
        while (start < x.size) {
            if (x.size - start <= maxLen) { result.add(AudioChunk(start, x.size)); break }
            val hardEnd = start + maxLen
            var bestPos = hardEnd
            var bestEnergy = Float.MAX_VALUE
            var p = (hardEnd - searchLen).coerceAtLeast(start + 1)
            while (p + frame <= hardEnd) {
                var e = 0f
                for (k in p until p + frame) e += abs(x[k])
                if (e < bestEnergy) { bestEnergy = e; bestPos = p + frame / 2 }
                p += frame
            }
            result.add(AudioChunk(start, bestPos))
            start = bestPos
        }
        return result
    }
}
