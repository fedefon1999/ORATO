package com.orato.app.domain.model

/**
 * Centralized visual analysis mode for a practice session.
 *
 * Mapping is keyed by [Scenario] enum identity — never by localized display strings.
 */
enum class VisualAnalysisMode {
    /** Pose Landmarker only (e.g. presentation). */
    BODY_ONLY,

    /** Face Landmarker only (e.g. exam, video call simulation). */
    FACE_ONLY,

    /** Both Pose and Face Landmarker (interview). */
    BODY_AND_FACE,
}

/**
 * Single source of truth: scenario → visual analysis mode.
 * Do not duplicate these checks in Composables, ViewModels, analyzers, or report builders.
 */
object VisualAnalysisMapping {
    fun modeFor(scenario: Scenario): VisualAnalysisMode =
        when (scenario) {
            Scenario.PRESENTATION -> VisualAnalysisMode.BODY_ONLY
            Scenario.INTERVIEW -> VisualAnalysisMode.BODY_AND_FACE
            Scenario.EXAM -> VisualAnalysisMode.FACE_ONLY
            Scenario.CONVERSATION -> VisualAnalysisMode.FACE_ONLY // Videochiamata online
        }

    fun requiresFaceCalibration(scenario: Scenario): Boolean =
        when (modeFor(scenario)) {
            VisualAnalysisMode.BODY_ONLY -> false
            VisualAnalysisMode.FACE_ONLY,
            VisualAnalysisMode.BODY_AND_FACE,
            -> true
        }

    fun usesPose(mode: VisualAnalysisMode): Boolean =
        mode == VisualAnalysisMode.BODY_ONLY || mode == VisualAnalysisMode.BODY_AND_FACE

    fun usesFace(mode: VisualAnalysisMode): Boolean =
        mode == VisualAnalysisMode.FACE_ONLY || mode == VisualAnalysisMode.BODY_AND_FACE
}
