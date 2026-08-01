package com.orato.app.speech

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a PCM16 WAV produced by ORATO into Whisper-ready float samples.
 * Pure Kotlin — validates RIFF structure, never mutates the source file.
 */
object WavPcm16Reader {

    data class WavInfo(
        val sampleRateHz: Int,
        val channelCount: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataByteCount: Long,
        val sampleCount: Long,
    )

    data class PreparedAudio(
        val samples: FloatArray,
        val sampleRateHz: Int,
        val durationMs: Long,
        val sourceSampleRateHz: Int,
        val sourceChannelCount: Int,
        val preprocessingDurationMs: Long,
    )

    sealed class WavReadError(message: String) : IOException(message) {
        class InvalidRiff : WavReadError("Invalid RIFF/WAVE header")
        class MissingDataChunk : WavReadError("Missing WAV data chunk")
        class UnsupportedEncoding(detail: String) : WavReadError("Unsupported WAV encoding: $detail")
        class Truncated : WavReadError("Truncated WAV file")
        class TooLong : WavReadError("Audio exceeds maximum allowed duration")
    }

    fun readInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12L) throw WavReadError.Truncated()
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
                throw WavReadError.InvalidRiff()
            }

            var sampleRate = -1
            var channels = -1
            var bits = -1
            var audioFormat = -1
            var dataOffset = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                val chunkIdBytes = ByteArray(4)
                raf.readFully(chunkIdBytes)
                val chunkId = String(chunkIdBytes)
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val chunkSize = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val chunkDataStart = raf.filePointer

                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16L) throw WavReadError.UnsupportedEncoding("fmt too small")
                        val fmt = ByteArray(chunkSize.toInt().coerceAtMost(64))
                        if (chunkDataStart + fmt.size > raf.length()) throw WavReadError.Truncated()
                        raf.readFully(fmt)
                        val buf = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = buf.short.toInt() and 0xFFFF
                        channels = buf.short.toInt() and 0xFFFF
                        sampleRate = buf.int
                        buf.int // byteRate
                        buf.short // blockAlign
                        bits = buf.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataOffset = chunkDataStart
                        dataSize = chunkSize
                        // Stop after finding data; remaining bytes are payload.
                        break
                    }
                    else -> {
                        // Skip unknown chunk (pad to even).
                    }
                }
                val next = chunkDataStart + chunkSize + (chunkSize and 1L)
                if (next > raf.length()) throw WavReadError.Truncated()
                raf.seek(next)
            }

            if (dataOffset < 0L || dataSize < 0L) throw WavReadError.MissingDataChunk()
            if (audioFormat != 1) throw WavReadError.UnsupportedEncoding("format=$audioFormat (need PCM=1)")
            if (bits != 16) throw WavReadError.UnsupportedEncoding("bits=$bits (need 16)")
            if (channels < 1) throw WavReadError.UnsupportedEncoding("channels=$channels")
            if (sampleRate <= 0) throw WavReadError.UnsupportedEncoding("sampleRate=$sampleRate")
            if (dataOffset + dataSize > raf.length()) throw WavReadError.Truncated()

            val bytesPerFrame = channels * 2
            if (dataSize % bytesPerFrame != 0L) throw WavReadError.Truncated()
            val sampleCount = dataSize / 2L // total PCM16 samples across channels

            return WavInfo(
                sampleRateHz = sampleRate,
                channelCount = channels,
                bitsPerSample = bits,
                dataOffset = dataOffset,
                dataByteCount = dataSize,
                sampleCount = sampleCount,
            )
        }
    }

    /**
     * Reads [file], downmixes to mono if needed, resamples to 16 kHz, converts to float [-1, 1].
     */
    fun prepareForWhisper(file: File): PreparedAudio {
        val started = System.nanoTime()
        val info = readInfo(file)
        val durationSec = info.dataByteCount.toDouble() /
            (info.sampleRateHz.toDouble() * info.channelCount * 2.0)
        if (durationSec > SpeechConfig.MAX_AUDIO_DURATION_SECONDS + 0.5) {
            throw WavReadError.TooLong()
        }

        val pcmBytes = ByteArray(info.dataByteCount.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val read = raf.read(pcmBytes)
            if (read != pcmBytes.size) throw WavReadError.Truncated()
        }

        val shortCount = pcmBytes.size / 2
        val interleaved = ShortArray(shortCount)
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            interleaved[i] = buf.short
        }

        val mono = MonoDownmixer.toMono(interleaved, info.channelCount)
        val resampler = Pcm16Resampler(info.sampleRateHz)
        // Process in chunks to keep peak allocation bounded for large files.
        val chunk = 4_000
        val resampledChunks = ArrayList<ShortArray>()
        var offset = 0
        while (offset < mono.size) {
            val len = minOf(chunk, mono.size - offset)
            resampledChunks.add(resampler.process(mono, offset, len))
            offset += len
        }
        val flushed = resampler.flush()
        if (flushed.isNotEmpty()) resampledChunks.add(flushed)

        val totalOut = resampledChunks.sumOf { it.size }
        val maxSamples = SpeechConfig.TARGET_SAMPLE_RATE_HZ * SpeechConfig.MAX_AUDIO_DURATION_SECONDS
        if (totalOut > maxSamples) throw WavReadError.TooLong()

        val combined = ShortArray(totalOut)
        var writeAt = 0
        for (part in resampledChunks) {
            System.arraycopy(part, 0, combined, writeAt, part.size)
            writeAt += part.size
        }

        val floats = Pcm16ToFloatConverter.convert(combined)
        val durationMs = (floats.size * 1000L) / SpeechConfig.TARGET_SAMPLE_RATE_HZ
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        return PreparedAudio(
            samples = floats,
            sampleRateHz = SpeechConfig.TARGET_SAMPLE_RATE_HZ,
            durationMs = durationMs,
            sourceSampleRateHz = info.sampleRateHz,
            sourceChannelCount = info.channelCount,
            preprocessingDurationMs = elapsedMs,
        )
    }
}
