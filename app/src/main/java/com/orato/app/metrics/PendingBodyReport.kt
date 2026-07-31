package com.orato.app.metrics

/**
 * Holds the latest completed [SessionBodyReport] for navigation to the local
 * report screen without Parcelable route arguments.
 *
 * Cleared when the report is consumed or when leaving the report flow.
 */
object PendingBodyReport {
    @Volatile
    private var report: SessionBodyReport? = null

    fun set(value: SessionBodyReport) {
        report = value
    }

    fun peek(): SessionBodyReport? = report

    fun clear() {
        report = null
    }
}
