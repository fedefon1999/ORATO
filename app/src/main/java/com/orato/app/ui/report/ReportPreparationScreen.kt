package com.orato.app.ui.report

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orato.app.report.ReportPreparationCoordinator
import com.orato.app.report.ReportPreparationPresentation
import com.orato.app.report.ReportPreparationState

@Composable
fun ReportPreparationScreen(
    sessionId: String,
    onReady: (sessionId: String) -> Unit,
    onCancelled: () -> Unit,
    onFailedHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coordinator = ReportPreparationCoordinator.get(
        androidx.compose.ui.platform.LocalContext.current,
    )
    val state by coordinator.state.collectAsStateWithLifecycle()
    var showCancelDialog by rememberSaveable { mutableStateOf(false) }
    var navigatedReady by rememberSaveable { mutableStateOf(false) }

    BackHandler {
        showCancelDialog = true
    }

    LaunchedEffect(state) {
        when (val s = state) {
            is ReportPreparationState.Ready -> {
                if (!navigatedReady && (s.report.sessionId == sessionId || sessionId.isBlank())) {
                    navigatedReady = true
                    onReady(s.report.sessionId)
                }
            }
            ReportPreparationState.Cancelled -> onCancelled()
            else -> Unit
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Preparazione del report",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Stiamo completando l’analisi della sessione.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "L’elaborazione avviene sul dispositivo e può richiedere alcuni secondi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(36.dp))

        when (val s = state) {
            is ReportPreparationState.Processing -> {
                val stageLabel = ReportPreparationPresentation.stageLabel(s.stage)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(56.dp)
                        .semantics {
                            contentDescription = stageLabel
                            liveRegion = LiveRegionMode.Polite
                        },
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stageLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                if (s.realProgress != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { s.realProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.72f),
                    )
                }
                if (s.elapsedMs >= 2_500L) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tempo trascorso: ${ReportPreparationPresentation.formatElapsed(s.elapsedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is ReportPreparationState.Failed -> {
                Text(
                    text = s.userSafeMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onFailedHome) {
                    Text("Torna alla home")
                }
            }
            is ReportPreparationState.Ready,
            ReportPreparationState.Cancelled,
            ReportPreparationState.Idle,
            -> {
                CircularProgressIndicator(modifier = Modifier.size(56.dp))
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Interrompere l’elaborazione del report?") },
            text = {
                Text("I risultati di questa sessione potrebbero non essere disponibili.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelDialog = false
                        coordinator.requestCancel()
                    },
                ) {
                    Text("Interrompi")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Continua elaborazione")
                }
            },
        )
    }
}
