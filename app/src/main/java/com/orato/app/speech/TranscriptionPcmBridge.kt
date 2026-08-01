package com.orato.app.speech

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.os.ParcelFileDescriptor

/**
 * Copies existing AudioRecord PCM into an ML Kit PFD pipe without blocking capture.
 *
 * - Resamples to 16 kHz mono PCM16 little-endian when needed
 * - Never writes a WAV header
 * - Bounded queue: if the recognizer is slow, frames are dropped (not AudioRecord)
 * - [closeWriteSide] closes the write end exactly once
 */
class TranscriptionPcmBridge(
    private val writePfd: ParcelFileDescriptor,
    inputSampleRateHz: Int,
    maxQueuedFrames: Int = SpeechConfig.PCM_BRIDGE_MAX_QUEUED_FRAMES,
) {
    private val resampler = Pcm16Resampler(inputRateHz = inputSampleRateHz)
    private val queue = ArrayBlockingQueue<ByteArray>(maxQueuedFrames.coerceAtLeast(1))
    private val closed = AtomicBoolean(false)
    private val writerThread: Thread

    @Volatile
    var droppedFrames: Int = 0
        private set

    init {
        writerThread = Thread(
            {
                FileOutputStream(writePfd.fileDescriptor).use { out ->
                    while (!closed.get() || queue.isNotEmpty()) {
                        val chunk = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                        try {
                            out.write(chunk)
                        } catch (_: Throwable) {
                            // Recognizer closed read side — stop writing.
                            closed.set(true)
                            queue.clear()
                            break
                        }
                    }
                    try {
                        out.flush()
                    } catch (_: Throwable) {
                    }
                }
            },
            "orato-stt-pcm-bridge",
        ).also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Offers a copy of PCM samples. Returns immediately.
     * Drops the frame if the bounded queue is full (backpressure).
     */
    fun offerPcm16(samples: ShortArray, offset: Int, length: Int) {
        if (closed.get() || length <= 0) return
        val resampled = try {
            resampler.process(samples, offset, length)
        } catch (_: Throwable) {
            return
        }
        if (resampled.isEmpty()) return
        enqueue(toLittleEndianBytes(resampled))
    }

    /**
     * Flushes the resampler and closes the write side exactly once.
     * Safe to call from any thread; idempotent.
     */
    fun closeWriteSide() {
        if (!closed.compareAndSet(false, true)) return
        try {
            val flushed = resampler.flush()
            if (flushed.isNotEmpty()) {
                enqueue(toLittleEndianBytes(flushed))
            }
        } catch (_: Throwable) {
        }
        try {
            writerThread.join(2_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            writePfd.close()
        } catch (_: Throwable) {
        }
    }

    private fun enqueue(bytes: ByteArray) {
        if (!queue.offer(bytes)) {
            // Drop oldest then retry once — keep latency bounded.
            queue.poll()
            droppedFrames++
            if (!queue.offer(bytes)) {
                droppedFrames++
            }
        }
    }

    companion object {
        fun toLittleEndianBytes(samples: ShortArray): ByteArray {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                buf.putShort(s)
            }
            return buf.array()
        }

        /**
         * Creates a pipe; returns (readPfd for ML Kit, bridge owning writePfd).
         */
        fun open(
            inputSampleRateHz: Int,
            maxQueuedFrames: Int = SpeechConfig.PCM_BRIDGE_MAX_QUEUED_FRAMES,
        ): Pair<ParcelFileDescriptor, TranscriptionPcmBridge> {
            val pipe = ParcelFileDescriptor.createPipe()
            val readPfd = pipe[0]
            val writePfd = pipe[1]
            val bridge = TranscriptionPcmBridge(writePfd, inputSampleRateHz, maxQueuedFrames)
            return readPfd to bridge
        }
    }
}

/**
 * Non-blocking sink invoked from the AudioRecord read loop.
 */
fun interface PcmFrameSink {
    fun onPcmFrame(samples: ShortArray, offset: Int, length: Int)
}
