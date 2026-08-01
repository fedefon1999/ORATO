package com.orato.app.audio

import com.orato.app.metrics.SessionBodyReport

/**
 * Holds the latest completed practice report (body + audio) for navigation
 * without Parcelable route arguments.
 *
 * Cleared when the report is consumed or when leaving the report flow.
 */
object PendingSessionReport {
    @Volatile
    private var report: SessionPracticeReport? = null

    fun set(value: SessionPracticeReport) {
        report = value
    }

    fun peek(): SessionPracticeReport? = report

    fun clear() {
        report = null
    }

    /** Convenience for callers that only need the body section. */
    fun peekBody(): SessionBodyReport? = report?.body

    fun peekAudio(): AudioSessionMetrics? = report?.audio
}
