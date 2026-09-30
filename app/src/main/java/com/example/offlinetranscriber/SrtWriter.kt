package com.example.offlinetranscriber

import java.util.Locale

/**
 * Builds .srt content from a shared list of chunk boundaries plus one or two
 * text lists aligned to those same boundaries (index i of each list = same
 * time range). For bilingual export, pass the line that should appear on
 * top as [primary] and the one below as [secondary].
 */
object SrtWriter {
    fun build(
        chunks: List<AudioChunk>,
        sampleRate: Int,
        primary: List<String>,
        secondary: List<String>? = null,
    ): String {
        val sb = StringBuilder()
        var index = 1
        for (i in chunks.indices) {
            val top = primary.getOrNull(i)?.trim().orEmpty()
            val bottom = secondary?.getOrNull(i)?.trim().orEmpty() ?: ""
            if (top.isEmpty() && bottom.isEmpty()) continue // silent chunk: no cue
            sb.append(index++).append('\n')
            sb.append(timecode(chunks[i].startSeconds(sampleRate)))
                .append(" --> ")
                .append(timecode(chunks[i].endSeconds(sampleRate)))
                .append('\n')
            if (top.isNotEmpty()) sb.append(top).append('\n')
            if (bottom.isNotEmpty()) sb.append(bottom).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun timecode(totalSeconds: Double): String {
        val ms = (totalSeconds * 1000).toLong().coerceAtLeast(0)
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val msRem = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, msRem)
    }
}
