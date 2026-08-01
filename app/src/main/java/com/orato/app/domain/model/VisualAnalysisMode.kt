package com.orato.app.domain.model

/**
 * Centralized visual analysis mode for a practice session.
 *
 * Mapping is keyed by [Scenario] enum identity — never by localized display strings.
 * Pose and Face never run in the same session.
 */
enum class VisualAnalysisMode {
    /** Pose Landmarker only (presentation). */
    BODY_ONLY,

    /** Face Landmarker only (exam, video-call simulation). */
    FACE_ONLY,
}

/**
 * Single source of truth: scenario → visual analysis mode.
 * Do not duplicate these checks in Composables, ViewModels, analyzers, or report builders.
 */
object VisualAnalysisMapping {
    fun modeFor(scenario: Scenario): VisualAnalysisMode =
        when (scenario) {
            Scenario.PRESENTATION -> VisualAnalysisMode.BODY_ONLY
            Scenario.EXAM -> VisualAnalysisMode.FACE_ONLY
            Scenario.CONVERSATION -> VisualAnalysisMode.FACE_ONLY // Videochiamata online
        }

    fun requiresFaceCalibration(scenario: Scenario): Boolean =
        modeFor(scenario) == VisualAnalysisMode.FACE_ONLY

    fun usesPose(mode: VisualAnalysisMode): Boolean =
        mode == VisualAnalysisMode.BODY_ONLY

    fun usesFace(mode: VisualAnalysisMode): Boolean =
        mode == VisualAnalysisMode.FACE_ONLY
}
