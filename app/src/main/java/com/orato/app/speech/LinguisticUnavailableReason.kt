package com.orato.app.speech

/**
 * Explicit internal reasons why linguistic metrics are unavailable.
 * Release UI shows only a concise safe message; debug may surface [debugLabel].
 */
enum class LinguisticUnavailableReason {
    MODEL_NOT_DOWNLOADED,
    MODEL_INVALID,
    MODEL_CHECKSUM_FAILED,
    WAV_NOT_FOUND,
    WAV_NOT_FINALIZED,
    WAV_INVALID,
    JNI_LIBRARY_UNAVAILABLE,
    CONTEXT_INITIALIZATION_FAILED,
    INFERENCE_FAILED,
    INFERENCE_TIMEOUT,
    EMPTY_TRANSCRIPT,
    CANCELLED,
    STALE_SESSION,
    INSUFFICIENT_AUDIO,
    UNKNOWN,
    ;

    val debugLabel: String get() = name
}

data class LinguisticDiagnostics(
    val reason: LinguisticUnavailableReason?,
    val sanitizedMessage: String?,
    val modelReady: Boolean = false,
    val wavSizeBytes: Long? = null,
    val wavDurationMs: Long? = null,
    val preprocessSampleCount: Int? = null,
    val preprocessMs: Long? = null,
    val modelLoadMs: Long? = null,
    val inferenceMs: Long? = null,
    val segmentCount: Int? = null,
    val transcriptCharCount: Int? = null,
    val sessionId: String? = null,
)
