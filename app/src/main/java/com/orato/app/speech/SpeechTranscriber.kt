package com.orato.app.speech

/**
 * Abstraction over on-device speech recognition so the alpha ML Kit dependency
 * can be replaced without touching ViewModels or Compose UI.
 *
 * Implementations own recognizer lifecycle and must not hold Activity references.
 */
interface SpeechTranscriber {
    /**
     * Probes feature availability without downloading models.
     * Safe to call on every practice-screen entry.
     */
    suspend fun checkAvailability(): TranscriptionAvailability

    /**
     * Downloads the on-device model after an explicit user action.
     * No-ops (success) when already ready. Never called implicitly on screen entry.
     */
    suspend fun prepareModel(): TranscriptionAvailability

    /**
     * Starts streaming recognition from the read end of a PCM pipe.
     * Caller writes raw headerless PCM16 mono 16 kHz little-endian to the write end
     * and closes it exactly once when capture stops.
     *
     * Collecting the flow drives recognition; cancellation / [stop] / [close] tear down.
     */
    fun startRecognition(readPfd: android.os.ParcelFileDescriptor): kotlinx.coroutines.flow.Flow<TranscriptUpdate>

    /** Requests recognition stop; idempotent. */
    suspend fun stop()

    /** Releases recognizer resources; idempotent. */
    fun close()
}
