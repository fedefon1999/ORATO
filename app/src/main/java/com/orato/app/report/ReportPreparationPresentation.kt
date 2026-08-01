package com.orato.app.report

/**
 * Italian presentation labels for report preparation stages.
 */
object ReportPreparationPresentation {

    fun stageLabel(stage: ReportPreparationStage): String =
        when (stage) {
            ReportPreparationStage.FINALIZING_RECORDING -> "Salvataggio della sessione…"
            ReportPreparationStage.FINALIZING_BODY_ANALYSIS -> "Completamento dell’analisi del corpo…"
            ReportPreparationStage.FINALIZING_FACE_ANALYSIS -> "Completamento dell’analisi del viso…"
            ReportPreparationStage.FINALIZING_VOICE_ANALYSIS -> "Completamento dell’analisi della voce…"
            ReportPreparationStage.PREPARING_AUDIO -> "Preparazione dell’audio…"
            ReportPreparationStage.ANALYZING_RHYTHM -> "Analisi del ritmo e della fluidità…"
            ReportPreparationStage.CALCULATING_METRICS -> "Calcolo delle metriche…"
            ReportPreparationStage.BUILDING_REPORT -> "Creazione del report…"
        }

    fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0) {
            "%d:%02d".format(minutes, seconds)
        } else {
            "%d s".format(seconds)
        }
    }
}
