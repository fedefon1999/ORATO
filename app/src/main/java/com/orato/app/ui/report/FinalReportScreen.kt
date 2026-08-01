package com.orato.app.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orato.app.audio.VoiceReportPresentation
import com.orato.app.domain.model.Scenario
import com.orato.app.report.AiCoachingState
import com.orato.app.report.CompletedSessionReport
import com.orato.app.report.FinalReportPresentation
import com.orato.app.report.RhythmAndFluencyReportData

@Composable
fun FinalReportScreen(
    scenario: Scenario,
    report: CompletedSessionReport,
    onHome: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReportHeader(scenario = scenario, report = report)
        SessionOverviewCard(report = report)
        BodySectionCard(report = report)
        VoiceSectionCard(report = report)
        RhythmSectionCard(report = report)
        AiCoachingSection(report.aiCoachingState)

        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRepeat, modifier = Modifier.fillMaxWidth()) {
            Text("Ripeti scenario")
        }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
            Text("Torna alla home")
        }
    }
}

@Composable
private fun ReportHeader(scenario: Scenario, report: CompletedSessionReport) {
    Text(
        text = "Report della sessione",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = scenario.displayName,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = FinalReportPresentation.formatDateTime(report.completedAtEpochMs),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "Durata sessione: ${FinalReportPresentation.formatDuration(report.totalSessionDurationMs)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "Analisi completata",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SessionOverviewCard(report: CompletedSessionReport) {
    ReportSectionCard(title = "Panoramica", summary = "Sintesi fattuale della sessione.") {
        MetricItem(
            label = "Durata",
            formattedValue = FinalReportPresentation.formatDuration(report.totalSessionDurationMs),
        )
        MetricItem(
            label = "Tempo effettivo di parlato",
            formattedValue = FinalReportPresentation.formatDuration(report.effectiveSpeechDurationMs),
        )
        val rhythm = report.rhythmAndFluency
        if (rhythm.linguisticAvailable) {
            MetricItem(
                label = "Parole",
                formattedValue = rhythm.wordCount?.toString() ?: "—",
            )
            MetricItem(
                label = "Ritmo",
                formattedValue = FinalReportPresentation.formatWpm(rhythm.wordsPerMinute),
            )
        }
    }
}

@Composable
private fun BodySectionCard(report: CompletedSessionReport) {
    val body = report.body.session
    ReportSectionCard(title = "Corpo") {
        MetricItem("Presenza in camera", FinalReportPresentation.formatPercentMetric(body.cameraPresence))
        MetricItem("Equilibrio spalle", FinalReportPresentation.formatScored(body.shoulderBalance))
        MetricItem("Inclinazione del busto", FinalReportPresentation.formatInclination(body.trunkInclination))
        MetricItem("Stabilità del busto", FinalReportPresentation.formatScored(body.trunkStability))
        MetricItem("Visibilità una mano", FinalReportPresentation.formatPercentMetric(body.oneHandVisibility))
        MetricItem("Visibilità due mani", FinalReportPresentation.formatPercentMetric(body.twoHandVisibility))
        MetricItem(
            "Attività gestuale",
            FinalReportPresentation.formatGesture(body.gestureActivity.classification),
        )
    }
}

@Composable
private fun VoiceSectionCard(report: CompletedSessionReport) {
    val voice = report.voice
    ReportSectionCard(
        title = "Voce",
        summary = "Volume e qualità dell’acquisizione. Le pause sono in Ritmo e fluidità.",
    ) {
        if (voice.insufficientData) {
            MetricItem("Metriche vocali", "Dati audio insufficienti")
            if (voice.errorMessage != null) {
                Text(
                    text = voice.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@ReportSectionCard
        }
        MetricItem(
            label = "Percentuale di parlato",
            formattedValue = FinalReportPresentation.formatPercent(voice.speechRatioPercent),
            explanation = "Calcolata sui blocchi di parlato, unendo le pause inferiori a 0,5 secondi.",
        )
        MetricItem(
            label = "Tempo effettivo di parlato",
            formattedValue = FinalReportPresentation.formatDuration(voice.effectiveSpeechDurationMs),
            explanation = "Include le brevi pause naturali e si interrompe quando inizia una pausa significativa.",
        )
        MetricItem(
            label = "Volume medio",
            formattedValue = FinalReportPresentation.formatVolumeDbfs(voice.meanSpeechDbfs),
            explanation = VoiceReportPresentation.VOLUME_HINT,
        )
        MetricItem(
            label = "Variazione del volume",
            formattedValue = FinalReportPresentation.formatVolumeVariation(voice.volumeVariationStdDevDb),
        )
        MetricItem(
            label = "Clipping",
            formattedValue = FinalReportPresentation.formatClipping(voice.clippingPercent),
        )
        MetricItem(
            label = "Qualità dell’acquisizione",
            formattedValue = voice.inputQuality.name,
        )
    }
}

@Composable
private fun RhythmSectionCard(report: CompletedSessionReport) {
    val rhythm = report.rhythmAndFluency
    ReportSectionCard(title = "Ritmo e fluidità") {
        if (rhythm.linguisticAvailable) {
            LinguisticMetrics(rhythm)
        } else {
            Text(
                text = rhythm.linguisticUnavailableMessage
                    ?: "Le metriche di ritmo e linguaggio non sono disponibili per questa sessione.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val recovery = when (rhythm.linguisticUnavailableReason) {
                com.orato.app.speech.LinguisticUnavailableReason.MODEL_NOT_DOWNLOADED,
                com.orato.app.speech.LinguisticUnavailableReason.MODEL_INVALID,
                com.orato.app.speech.LinguisticUnavailableReason.MODEL_CHECKSUM_FAILED,
                -> com.orato.app.speech.SpeechConfig.PREPARE_MODEL_ACTION
                null -> null
                else -> com.orato.app.speech.SpeechConfig.RETRY_SESSION_ACTION
            }
            if (recovery != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = recovery,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        PauseMetrics(rhythm)
    }
}

@Composable
private fun LinguisticMetrics(rhythm: RhythmAndFluencyReportData) {
    MetricItem(
        label = "Parole",
        formattedValue = rhythm.wordCount?.toString() ?: "—",
    )
    MetricItem(
        label = "Ritmo",
        formattedValue = FinalReportPresentation.formatWpm(rhythm.wordsPerMinute),
        explanation = "Calcolato sulla durata effettiva del parlato.",
    )
    MetricItem(
        label = "Ripetizioni ravvicinate",
        formattedValue = rhythm.immediateRepetitionCount?.toString() ?: "—",
    )
    val markers = rhythm.discourseMarkers
    MetricItem(
        label = "Intercalari discorsivi stimati",
        formattedValue = markers?.totalCount?.toString() ?: "—",
        explanation = "Rilevati dal testo riconosciuto; il valore è una stima.",
    )
    MetricItem(
        label = "Intercalari discorsivi al minuto",
        formattedValue = FinalReportPresentation.formatMarkersPerMinute(markers?.markersPerMinute),
    )
    val breakdown = markers?.breakdown.orEmpty()
    if (breakdown.isNotEmpty()) {
        ExpandableDetails(
            collapsedLabel = "Mostra dettaglio intercalari",
            expandedLabel = "Nascondi dettaglio intercalari",
        ) {
            breakdown.forEach { (word, count) ->
                MetricItem(label = word, formattedValue = count.toString())
            }
        }
    }
}

@Composable
private fun PauseMetrics(rhythm: RhythmAndFluencyReportData) {
    MetricItem(
        label = "Pause significative",
        formattedValue = rhythm.significantPauseCount?.toString() ?: "—",
    )
    MetricItem(
        label = "Pause significative al minuto",
        formattedValue = FinalReportPresentation.formatMarkersPerMinute(rhythm.significantPausesPerMinute),
    )
    MetricItem(
        label = "Pause lunghe",
        formattedValue = rhythm.longPauseCount?.toString() ?: "—",
    )
    MetricItem(
        label = "Pausa più lunga",
        formattedValue = FinalReportPresentation.formatDuration(rhythm.longestPauseDurationMs),
    )
    MetricItem(
        label = "Durata mediana delle pause",
        formattedValue = FinalReportPresentation.formatDuration(rhythm.medianPauseDurationMs),
    )
    MetricItem(
        label = "Tratto continuo più lungo",
        formattedValue = FinalReportPresentation.formatDuration(rhythm.longestContinuousSpeechMs),
    )
}

@Composable
private fun AiCoachingSection(state: AiCoachingState) {
    when (state) {
        AiCoachingState.NotAvailable,
        AiCoachingState.Loading,
        is AiCoachingState.Error,
        -> Unit
        is AiCoachingState.Ready -> {
            ReportSectionCard(title = "Consigli") {
                Text(
                    text = state.advice.conciseSummary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.advice.strengths.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Punti di forza", style = MaterialTheme.typography.titleSmall)
                    state.advice.strengths.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.advice.improvementAreas.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Aree di miglioramento", style = MaterialTheme.typography.titleSmall)
                    state.advice.improvementAreas.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.advice.nextExercise?.let { exercise ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Prossimo esercizio", style = MaterialTheme.typography.titleSmall)
                    Text(exercise, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
