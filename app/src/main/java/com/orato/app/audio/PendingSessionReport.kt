package com.orato.app.audio

import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.SpeechSessionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the latest completed practice report (body + audio + speech) for navigation
 * without Parcelable route arguments.
 *
 * Exposed as a [StateFlow] so the report screen can update when transcription
 * finishes shortly after body/audio metrics appear — without recreating the session.
 *
 * Cleared when leaving the report flow.
 */
object PendingSessionReport {
    private val _report = MutableStateFlow<SessionPracticeReport?>(null)
    val report: StateFlow<SessionPracticeReport?> = _report.asStateFlow()

    fun set(value: SessionPracticeReport) {
        _report.value = value
    }

    fun updateSpeech(speech: SpeechSessionResult) {
        _report.update { current ->
            current?.copy(speech = speech) ?: current
        }
    }

    fun peek(): SessionPracticeReport? = _report.value

    fun clear() {
        _report.value = null
    }

    /** Convenience for callers that only need the body section. */
    fun peekBody(): SessionBodyReport? = _report.value?.body

    fun peekAudio(): AudioSessionMetrics? = _report.value?.audio
}
