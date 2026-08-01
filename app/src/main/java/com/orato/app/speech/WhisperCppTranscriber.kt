package com.orato.app.speech

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Offline whisper.cpp transcription. Serializes native context access.
 * Returns an ephemeral [TranscriptionResult] for metrics calculation only —
 * callers must not persist or expose the transcript in the normal report.
 */
class WhisperCppTranscriber(
    private val modelManager: WhisperModelManager,
) : SpeechTranscriber {

    private val mutex = Mutex()
    private val contextPtr = AtomicLong(0L)
    private val released = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    data class DebugTimings(
        val modelLoadMs: Long = 0L,
        val preprocessMs: Long = 0L,
        val inferenceMs: Long = 0L,
        val metricsMs: Long = 0L,
        val totalMs: Long = 0L,
        val threadCount: Int = 0,
        val modelName: String = "",
        val audioDurationMs: Long = 0L,
        val transcriptChars: Int = 0,
    )

    @Volatile
    var lastDebugTimings: DebugTimings? = null
        private set

    override suspend fun transcribe(
        audioFile: File,
        languageCode: String,
    ): TranscriptionResult = withContext(Dispatchers.Default) {
        check(!released.get()) { "transcriber closed" }
        cancelled.set(false)
        val totalStart = System.nanoTime()

        if (!WhisperNative.libraryLoaded) {
            throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
        }
        if (!modelManager.isReady()) {
            throw TranscriptionFailedException(SpeechConfig.USER_SAFE_MODEL_ERROR)
        }

        mutex.withLock {
            if (cancelled.get()) {
                throw TranscriptionCancelledException()
            }

            val prepared = try {
                WavPcm16Reader.prepareForWhisper(audioFile)
            } catch (_: WavPcm16Reader.WavReadError) {
                throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
            } catch (_: Throwable) {
                throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
            }

            if (cancelled.get()) {
                throw TranscriptionCancelledException()
            }

            ensureContextLocked()
            val ptr = contextPtr.get()
            if (ptr == 0L) {
                throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
            }

            val threads = SpeechConfig.preferredThreadCount()
            val inferStart = System.nanoTime()
            val rc = try {
                WhisperNative.transcribe(
                    contextPtr = ptr,
                    samples = prepared.samples,
                    languageCode = languageCode,
                    numThreads = threads,
                    translate = false,
                    initialPrompt = SpeechConfig.WHISPER_INITIAL_PROMPT,
                    carryInitialPrompt = SpeechConfig.WHISPER_CARRY_INITIAL_PROMPT,
                    suppressNst = SpeechConfig.WHISPER_SUPPRESS_NST,
                )
            } catch (t: Throwable) {
                logDebug("native transcribe error: ${t.javaClass.simpleName}")
                throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
            }
            val inferMs = (System.nanoTime() - inferStart) / 1_000_000L

            if (rc == -100 || cancelled.get()) {
                throw TranscriptionCancelledException()
            }
            if (rc != 0) {
                throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
            }

            // Collect segment text only — no UI segments / word timestamps.
            val segmentCount = WhisperNative.getSegmentCount(ptr)
            val textBuilder = StringBuilder()
            for (i in 0 until segmentCount) {
                val text = WhisperNative.getSegmentText(ptr, i).trim()
                if (text.isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append(' ')
                    textBuilder.append(text)
                }
            }

            val raw = textBuilder.toString()
            val cleaned = SpeechMetricsCalculator.stripInitialPromptEcho(raw).trim()
            val totalMs = (System.nanoTime() - totalStart) / 1_000_000L
            lastDebugTimings = DebugTimings(
                modelLoadMs = lastModelLoadMs,
                preprocessMs = prepared.preprocessingDurationMs,
                inferenceMs = inferMs,
                totalMs = totalMs,
                threadCount = threads,
                modelName = WhisperModelSpec.GGML_BASE.fileName,
                audioDurationMs = prepared.durationMs,
                transcriptChars = cleaned.length,
            )

            TranscriptionResult(
                transcript = cleaned,
                detectedLanguage = languageCode,
                processingDurationMs = totalMs,
            )
        }
    }

    override fun requestCancellation() {
        cancelled.set(true)
        try {
            if (WhisperNative.libraryLoaded) {
                WhisperNative.requestCancellation()
            }
        } catch (_: Throwable) {
        }
    }

    override suspend fun close() {
        requestCancellation()
        mutex.withLock {
            releaseContextLocked()
        }
    }

    suspend fun shutdown() {
        if (!released.compareAndSet(false, true)) {
            close()
            return
        }
        requestCancellation()
        mutex.withLock {
            releaseContextLocked()
        }
    }

    private var lastModelLoadMs: Long = 0L

    private fun ensureContextLocked() {
        if (contextPtr.get() != 0L) return
        val path = modelManager.modelPath().absolutePath
        val start = System.nanoTime()
        val ptr = try {
            WhisperNative.initializeContext(path)
        } catch (t: Throwable) {
            logDebug("init failed: ${t.javaClass.simpleName}")
            0L
        }
        lastModelLoadMs = (System.nanoTime() - start) / 1_000_000L
        if (ptr == 0L) {
            throw TranscriptionFailedException(SpeechConfig.USER_SAFE_MODEL_ERROR)
        }
        contextPtr.set(ptr)
    }

    private fun releaseContextLocked() {
        val ptr = contextPtr.getAndSet(0L)
        if (ptr == 0L) return
        try {
            WhisperNative.releaseContext(ptr)
        } catch (_: Throwable) {
        }
    }

    private fun logDebug(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message)
        }
    }

    companion object {
        private const val TAG = "WhisperCppTranscriber"
    }
}

class TranscriptionFailedException(val userSafeMessage: String) : Exception(userSafeMessage)

class TranscriptionCancelledException : Exception("cancelled")
