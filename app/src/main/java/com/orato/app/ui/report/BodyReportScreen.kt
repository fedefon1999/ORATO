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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orato.app.domain.model.Scenario
import com.orato.app.metrics.GestureActivityClass
import com.orato.app.metrics.GestureActivityMetric
import com.orato.app.metrics.PercentMetric
import com.orato.app.metrics.ScoredMetric
import com.orato.app.metrics.SessionBodyReport

@Composable
fun BodyReportScreen(
    scenario: Scenario,
    report: SessionBodyReport,
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
