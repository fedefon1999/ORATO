package com.orato.app.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.PauseBuckets
import com.orato.app.audio.SessionPracticeReport
import com.orato.app.audio.VoiceReportPresentation
import com.orato.app.domain.model.Scenario
import com.orato.app.metrics.GestureActivityClass
import com.orato.app.metrics.GestureActivityMetric
import com.orato.app.metrics.PercentMetric
import com.orato.app.metrics.ScoredMetric
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechIntelligenceMetrics
import com.orato.app.speech.SpeechReportPresentation
import com.orato.app.speech.SpeechSessionResult

@Composable
fun BodyReportScreen(
    scenario: Scenario,
    report: SessionPracticeReport,
    onHome: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Report corporeo",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = scenario.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Punteggi provvisori di ingegneria per il feedback di pratica. " +
                "Non sono valutazioni mediche o scientifiche.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        BodySection(report.body)

        Spacer(modifier = Modifier.height(8.dp))

        VoiceSection(report.audio)

        Spacer(modifier = Modifier.height(8.dp))

        SpeechSection(report.speech)

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onRepeat,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ripeti scenario")
        }
        OutlinedButton(
            onClick = onHome,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Torna alla home")
        }
    }
}

@Composable
private fun BodySection(report: SessionBodyReport) {
    ReportPercentRow(
        label = "Presenza in camera",
        metric = report.cameraPresence,
    )
    ReportScoreRow(
        label = "Equilibrio spalle",
        metric = report.shoulderBalance,
        rawSuffix = " (tilt)",
    )
    ReportInclinationRow(metric = report.trunkInclination)
    ReportScoreRow(
        label = "Stabilità del busto",
        metric = report.trunkStability,
        rawSuffix = " (sway)",
    )
    ReportPercentRow(
        label = "Visibilità una mano",
        metric = report.oneHandVisibility,
    )
    ReportPercentRow(
        label = "Visibilità due mani",
        metric = report.twoHandVisibility,
    )
    ReportGestureRow(metric = report.gestureActivity)
}

@Composable
private fun VoiceSection(audio: AudioSessionMetrics) {
    Text(
        text = "Voce",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = "Pause e volume dall’audio locale.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (audio.insufficientData ||
        audio.inputQuality == AudioInputQuality.INSUFFICIENT_AUDIO ||
        audio.inputQuality == AudioInputQuality.RECORDING_ERROR
    ) {
        ReportLine(label = "Metriche vocali", value = "Dati audio insufficienti")
        if (audio.errorMessage != null) {
            Text(
                text = audio.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ReportLine(
            label = "Durata catturata",
            value = formatDurationMs(audio.capturedDurationMs),
        )
        ReportLine(
            label = "Qualità ingresso",
            value = inputQualityLabel(audio.inputQuality),
        )
        return
    }

    val buckets = audio.pauseBuckets ?: PauseBuckets.empty()

    Text(
        text = "Sintesi",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    ReportLine(
        label = "Acquisizione",
        value = VoiceReportPresentation.acquisitionLabel(
            VoiceReportPresentation.acquisitionSummary(audio),
        ),
    )
    ReportLine(
        label = "Volume",
        value = VoiceReportPresentation.volumeLabel(
            VoiceReportPresentation.volumeSummary(audio),
        ),
    )
    ReportLine(
        label = "Pause lunghe",
        value = VoiceReportPresentation.longPauseLabel(
            VoiceReportPresentation.longPauseSummary(audio),
        ),
    )

    Spacer(modifier = Modifier.height(4.dp))

    ReportLine(
        label = "Durata catturata",
        value = formatDurationMs(audio.capturedDurationMs),
    )
    ReportLine(
        label = "Rapporto di parlato",
        value = audio.speechRatioPercent?.let { "%.0f%%".format(it) } ?: "—",
    )
    if (audio.meanSpeechDbfs == null) {
        ReportLine(label = "Volume medio", value = "Dati insufficienti")
    } else {
        ReportLine(
            label = "Volume medio",
            value = VoiceReportPresentation.formatMeanVolumeDbfs(audio.meanSpeechDbfs),
        )
        Text(
            text = VoiceReportPresentation.VOLUME_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ReportLine(
        label = "Variazione di volume",
        value = audio.volumeVariationStdDevDb?.let { "%.2f dB σ".format(it) } ?: "—",
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = "Pause",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = VoiceReportPresentation.PAUSE_INTERPRETATION,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ReportLine(
        label = "Pause brevi",
        value = buckets.briefCount.toString(),
    )
    ReportLine(
        label = "Pause significative",
        value = buckets.significantCount.toString(),
    )
    ReportLine(
        label = "Pause lunghe",
        value = buckets.longCount.toString(),
    )
    ReportLine(
        label = "Durata mediana pausa",
        value = audio.medianPauseDurationMs?.let { formatDurationMs(it) } ?: "—",
    )
    ReportLine(
        label = "Pausa più lunga",
        value = audio.longestPauseDurationMs?.let { formatDurationMs(it) } ?: "—",
    )
    ReportLine(
        label = "Clipping",
        value = audio.clippingPercent?.let { "%.2f%%".format(it) } ?: "—",
    )
}

@Composable
private fun SpeechSection(speech: SpeechSessionResult) {
    Text(
        text = "Ritmo e trascrizione",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )

    when (speech) {
        SpeechSessionResult.NotAttempted,
        is SpeechSessionResult.Unavailable,
        -> {
            Text(
                text = SpeechConfig.METRICS_UNAVAILABLE_REPORT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SpeechSessionResult.Ready -> {
            SpeechMetricsBlock(speech.metrics)
        }
    }
}

@Composable
private fun SpeechMetricsBlock(metrics: SpeechIntelligenceMetrics) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    ReportLine(
        label = "Parole",
        value = SpeechReportPresentation.formatWordCount(metrics.wordCount),
    )
    ReportLine(
        label = "Ritmo",
        value = SpeechReportPresentation.formatWpm(metrics.wordsPerMinute),
    )
    ReportLine(
        label = "Riempitivi stimati",
        value = SpeechReportPresentation.formatFillerCount(metrics.fillerCount),
    )
    val breakdown = SpeechReportPresentation.formatFillerBreakdown(metrics.fillerBreakdown)
    if (breakdown.isNotEmpty()) {
        Text(
            text = breakdown,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Trascrizione: Nascondi" else "Trascrizione: Mostra")
    }
    if (expanded) {
        val transcriptScroll = rememberScrollState()
        Text(
            text = metrics.transcript.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(transcriptScroll),
        )
    }
}

private fun formatDurationMs(ms: Long): String {
    if (ms < 1_000L) return "%d ms".format(ms)
    val seconds = ms / 1_000.0
    return "%.1f s".format(seconds)
}

private fun inputQualityLabel(quality: AudioInputQuality): String =
    when (quality) {
        AudioInputQuality.GOOD -> "GOOD"
        AudioInputQuality.TOO_QUIET -> "TOO_QUIET"
        AudioInputQuality.CLIPPING -> "CLIPPING"
        AudioInputQuality.INSUFFICIENT_AUDIO -> "INSUFFICIENT_AUDIO"
        AudioInputQuality.RECORDING_ERROR -> "RECORDING_ERROR"
    }

@Composable
private fun ReportPercentRow(
    label: String,
    metric: PercentMetric,
) {
    ReportLine(
        label = label,
        value = if (metric.insufficientData || metric.percent == null) {
            "Dati insufficienti"
        } else {
            "%.0f%%".format(metric.percent)
        },
    )
}

@Composable
private fun ReportScoreRow(
    label: String,
    metric: ScoredMetric,
    rawSuffix: String = "",
) {
    val value = when {
        metric.insufficientData || metric.score == null -> "Dati insufficienti"
        else -> "${metric.score}/100"
    }
    ReportLine(label = label, value = value)
    if (!metric.insufficientData && metric.rawValue != null) {
        Text(
            text = "Misura grezza: %.3f%s".format(metric.rawValue, rawSuffix),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReportInclinationRow(metric: ScoredMetric) {
    val value = when {
        metric.insufficientData || metric.rawValue == null -> "Dati insufficienti"
        else -> "%.1f°".format(metric.rawValue)
    }
    ReportLine(label = "Inclinazione mediana del busto", value = value)
    if (!metric.insufficientData && metric.score != null) {
        Text(
            text = "Punteggio provvisorio: ${metric.score}/100",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReportGestureRow(metric: GestureActivityMetric) {
    val value = when {
        metric.insufficientData || metric.classification == null -> "Dati insufficienti"
        else -> gestureLabel(metric.classification)
    }
    ReportLine(label = "Attività gestuale", value = value)
    if (!metric.insufficientData) {
        val avg = metric.averageActivity?.let { "%.3f".format(it) } ?: "—"
        val active = metric.activeTimePercent?.let { "%.0f%%".format(it) } ?: "—"
        Text(
            text = "Media movimento: $avg · Tempo attivo: $active",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReportLine(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Start,
        )
    }
}

private fun gestureLabel(classification: GestureActivityClass): String =
    when (classification) {
        GestureActivityClass.LOW -> "LOW"
        GestureActivityClass.BALANCED -> "BALANCED"
        GestureActivityClass.HIGH -> "HIGH"
    }
