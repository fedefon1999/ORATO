package com.orato.app.ui.practice

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.LiveAudioDebug
import com.orato.app.audio.SessionPracticeReport
import com.orato.app.domain.model.Scenario
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun PracticeScreen(
    scenario: Scenario,
    onExit: () -> Unit,
    onSessionComplete: (SessionPracticeReport) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PracticeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(viewModel) {
        viewModel.sessionCompleted.collect { report ->
            onSessionComplete(report)
        }
    }

    LaunchedEffect(micPermission.status.isGranted) {
        viewModel.onMicrophoneAvailabilityChanged(micPermission.status.isGranted)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.onLeaveForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Leaving the practice screen — stop capture exactly once.
            viewModel.onLeaveForeground()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(scenario.displayName) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Esci",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        when {
            cameraPermission.status.isGranted -> {
                PracticeSessionContent(
                    uiState = uiState,
                    microphoneGranted = micPermission.status.isGranted,
                    onRequestMicrophone = { micPermission.launchPermissionRequest() },
                    onStart = viewModel::startSession,
                    onReset = viewModel::resetSession,
                    onPoseFrame = viewModel::onPoseFrame,
                    onPoseStatus = viewModel::onPoseStatus,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            else -> {
                CameraPermissionRationale(
                    status = cameraPermission.status,
                    onRequest = { cameraPermission.launchPermissionRequest() },
                    onRequestBoth = {
                        cameraPermission.launchPermissionRequest()
                        micPermission.launchPermissionRequest()
                    },
                    micGranted = micPermission.status.isGranted,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraPermissionRationale(
    status: PermissionStatus,
    onRequest: () -> Unit,
    onRequestBoth: () -> Unit,
    micGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Permesso fotocamera necessario",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Orato usa la fotocamera frontale per la sessione di pratica. " +
                "Il microfono è richiesto per le metriche vocali. " +
                "Nessun video viene salvato in questa milestone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (status.shouldShowRationale) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Senza fotocamera non è possibile avviare l’esercizio.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = if (micGranted) onRequest else onRequestBoth) {
            Text(
                if (micGranted) "Consenti fotocamera"
                else "Consenti fotocamera e microfono",
            )
        }
    }
}

@Composable
private fun PracticeSessionContent(
    uiState: PracticeUiState,
    microphoneGranted: Boolean,
    onRequestMicrophone: () -> Unit,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onPoseFrame: (UpperBodyPoseFrame) -> Unit,
    onPoseStatus: (PoseDetectionStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            FrontCameraPreview(
                onPoseFrame = onPoseFrame,
                onPoseStatus = onPoseStatus,
                modifier = Modifier.fillMaxSize(),
            )

            PoseSkeletonOverlay(
                poseFrame = uiState.poseFrame,
                mirrorHorizontally = true,
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = uiState.formattedTime,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.poseStatusLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (uiState.poseStatus) {
                        is PoseDetectionStatus.Error -> MaterialTheme.colorScheme.error
                        PoseDetectionStatus.Detected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onPrimary
                    },
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiveMetricsDebugPanel(metrics = uiState.liveMetrics)
                LiveAudioDebugPanel(debug = uiState.audioDebug)
            }
        }

        Text(
            text = "L’audio viene analizzato durante l’esercizio e salvato temporaneamente " +
                "sul dispositivo. Non viene ancora caricato online.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!microphoneGranted) {
            Text(
                text = "Microfono non concesso: le metriche vocali non saranno disponibili. " +
                    "Puoi comunque esercitarti con la fotocamera.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = onRequestMicrophone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Consenti microfono")
            }
        }

        Text(
            text = when {
                uiState.isFinished -> "Sessione completata. Apertura report…"
                uiState.isRunning -> "Parla con naturalezza. Mantieni lo sguardo verso la fotocamera."
                else -> "Quando sei pronto, avvia i 90 secondi di pratica."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        when {
            uiState.isFinished -> {
                // Navigation to the report is handled by LaunchedEffect.
            }

            uiState.isRunning -> {
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Annulla")
                }
            }

            else -> {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Avvia 90 secondi")
                }
            }
        }
    }
}

/**
 * Compact development overlay. Kept small so it does not obstruct the camera.
 */
@Composable
private fun LiveMetricsDebugPanel(
    metrics: LiveBodyMetrics,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "debug metrics",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        )
        DebugLine("torsoValid", if (metrics.torsoValid) "yes" else "no")
        DebugLine("L-sh", landmarkDebug(metrics.leftShoulder))
        DebugLine("R-sh", landmarkDebug(metrics.rightShoulder))
        DebugLine("L-hip", landmarkDebug(metrics.leftHip))
        DebugLine("R-hip", landmarkDebug(metrics.rightHip))
        DebugLine(
            "L-wrist",
            metrics.leftWristVisibility?.let { "%.2f".format(it) } ?: "—",
        )
        DebugLine(
            "R-wrist",
            metrics.rightWristVisibility?.let { "%.2f".format(it) } ?: "—",
        )
        DebugLine("L-fingers", metrics.leftValidFingerCount.toString())
        DebugLine("R-fingers", metrics.rightValidFingerCount.toString())
        DebugLine("L-hand", handDebug(metrics.leftHand))
        DebugLine("R-hand", handDebug(metrics.rightHand))
        DebugLine(
            "tilt",
            metrics.shoulderTilt?.let { "%.3f".format(it) } ?: "—",
        )
        DebugLine(
            "trunk",
            metrics.trunkAngleDegrees?.let { "%.1f°".format(it) } ?: "—",
        )
    }
}

/**
 * Compact development-only audio panel (no waveform).
 * Does not expose the local file path.
 */
@Composable
private fun LiveAudioDebugPanel(
    debug: LiveAudioDebug,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "debug audio",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        )
        DebugLine("state", audioStateLabel(debug.state))
        DebugLine("rate", debug.sampleRateHz?.toString() ?: "—")
        DebugLine("source", debug.audioSourceLabel ?: "—")
        DebugLine(
            "dBFS",
            debug.currentDbfs?.let { "%.1f".format(it) } ?: "—",
        )
        DebugLine(
            "noise",
            debug.noiseFloorDbfs?.let { "%.1f".format(it) } ?: "—",
        )
        DebugLine("speech", if (debug.isSpeech) "yes" else "no")
        DebugLine("captured", "%d ms".format(debug.capturedDurationMs))
        DebugLine("dropped", debug.droppedReadCount.toString())
        if (debug.errorMessage != null) {
            DebugLine("error", debug.errorMessage)
        }
    }
}

private fun audioStateLabel(state: AudioRecordingState): String =
    when (state) {
        AudioRecordingState.Idle -> "Idle"
        AudioRecordingState.Initializing -> "Initializing"
        AudioRecordingState.Recording -> "Recording"
        AudioRecordingState.Stopping -> "Stopping"
        AudioRecordingState.Completed -> "Completed"
        AudioRecordingState.Error -> "Error"
    }

private fun landmarkDebug(info: com.orato.app.metrics.LandmarkDebugInfo): String {
    val vis = info.visibility?.let { "%.2f".format(it) } ?: "—"
    val frame = if (info.inFrame) "in" else "out"
    return "$vis/$frame"
}

private fun handDebug(info: com.orato.app.metrics.HandDebugInfo): String {
    val avg = info.averageVisibility?.let { "%.2f".format(it) } ?: "—"
    val box = info.boundingBoxSize?.let { "%.3f".format(it) } ?: "—"
    val spread = info.fingerSpread?.let { "%.3f".format(it) } ?: "—"
    val inside = if (info.insideTorsoRegion) "inT" else "outT"
    val occ = if (info.occludedByTorso) "occ" else "clear"
    val vis = if (info.handVisible) "vis" else "hide"
    return "$vis avg=$avg box=$box spr=$spread $inside $occ"
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
    )
}
