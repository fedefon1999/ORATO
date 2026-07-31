package com.orato.app.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams mono PCM 16-bit samples into a valid WAV file.
 *
 * Writes a placeholder RIFF/fmt/data header up front and finalizes the data
 * length when [finalizeHeader] / [close] is called. Never touches public storage.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRateHz: Int,
    private val channelCount: Int = AudioMetricsConfig.CHANNEL_COUNT,
    private val bitsPerSample: Int = AudioMetricsConfig.BYTES_PER_SAMPLE * 8,
) : Closeable {

    private val raf: RandomAccessFile
    private var dataBytes: Long = 0L
    private var finalized: Boolean = false
    private var closed: Boolean = false

    init {
        require(sampleRateHz > 0)
        require(channelCount > 0)
        require(bitsPerSample == 16)
        file.parentFile?.mkdirs()
        raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        writeHeaderPlaceholder()
    }

    /** Bytes of PCM payload written so far (excludes the 44-byte header). */
    fun pcmByteCount(): Long = dataBytes

    /**
     * Appends little-endian PCM 16-bit samples.
     * @throws IOException if the writer is already finalized/closed
     */
    @Synchronized
    fun writeSamples(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        checkWritable()
        if (length <= 0) return
        val bytes = ByteArray(length * 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val end = offset + length
        for (i in offset until end) {
            buf.putShort(samples[i])
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    /**
     * Appends raw little-endian PCM bytes (must be even length).
     */
    @Synchronized
    fun writePcmBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        checkWritable()
        if (length <= 0) return
        require(length % 2 == 0) { "PCM byte length must be even for 16-bit samples" }
        raf.write(bytes, offset, length)
        dataBytes += length
    }

    /**
     * Patches RIFF and data chunk sizes so the file is a valid WAV.
     * Idempotent: subsequent calls are no-ops.
     */
    @Synchronized
    fun finalizeHeader() {
        if (finalized || closed) return
        patchSizes()
        finalized = true
    }

    /**
     * Finalizes the header (if needed) and closes the file.
     * Idempotent.
     */
    @Synchronized
    override fun close() {
        if (closed) return
        try {
            if (!finalized) {
                patchSizes()
                finalized = true
            }
        } finally {
            raf.close()
            closed = true
        }
    }

    private fun checkWritable() {
        if (closed || finalized) {
            throw IOException("WavFileWriter is closed or finalized")
        }
    }

    private fun writeHeaderPlaceholder() {
        val header = buildHeader(
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            dataSize = 0,
        )
        raf.write(header)
    }

    private fun patchSizes() {
        val header = buildHeader(
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            dataSize = dataBytes.toInt().coerceAtLeast(0),
        )
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header)
        raf.seek(pos)
    }

    companion object {
        const val HEADER_SIZE: Int = 44

        /**
         * Builds a 44-byte PCM WAV header.
         * Exposed for unit tests.
         */
        fun buildHeader(
            sampleRateHz: Int,
            channelCount: Int,
            bitsPerSample: Int,
            dataSize: Int,
        ): ByteArray {
            val byteRate = sampleRateHz * channelCount * bitsPerSample / 8
            val blockAlign = (channelCount * bitsPerSample / 8).toShort()
            val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(36 + dataSize) // chunk size
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16) // PCM fmt chunk size
            buffer.putShort(1) // audio format = PCM
            buffer.putShort(channelCount.toShort())
            buffer.putInt(sampleRateHz)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign)
            buffer.putShort(bitsPerSample.toShort())
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(dataSize)
            return buffer.array()
        }

        /**
         * Reads the data-chunk size field from a WAV header (bytes 40–43).
         * Useful in tests after finalization.
         */
        fun readDataSize(header: ByteArray): Int {
            require(header.size >= HEADER_SIZE)
            return ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }

        fun readRiffChunkSize(header: ByteArray): Int {
            require(header.size >= 8)
            return ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }
}
