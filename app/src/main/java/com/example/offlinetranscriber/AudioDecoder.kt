package com.example.offlinetranscriber

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Mono float PCM (-1..1) at the audio track's native sample rate. */
class Pcm(val samples: FloatArray, val sampleRate: Int)

/**
 * Decodes the first audio track of a video/audio Uri using Android's own
 * MediaExtractor + MediaCodec (no FFmpeg). Downmixes to mono float.
 * Resampling to 16 kHz is left to sherpa-onnx.
 */
object AudioDecoder {

    private class GrowableFloats(initial: Int = 1 shl 20) {
        var data = FloatArray(initial)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): Pcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var track = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; inFormat = f; break
                }
            }
            require(track >= 0 && inFormat != null) { "No audio track found in this file." }
            extractor.selectTrack(track)

            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs =
                if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inFormat, null, null, 0)
                start()
            }

            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var pcmFloat = false

            val out = GrowableFloats()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (isCancelled()) throw InterruptedException("Cancelled")

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        if (pcmFloat) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (fr in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += fb.get()
                                out.add(sum / channels)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (fr in 0 until frames) {
                                var sum = 0
                                for (c in 0 until channels) sum += sb.get()
                                out.add(sum / channels / 32768f)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            onProgress(1f)
            return Pcm(out.toArray(), sampleRate)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }
}
