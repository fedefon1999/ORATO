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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.orato.app.domain.model.Scenario
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun PracticeScreen(
    scenario: Scenario,
    onExit: () -> Unit,
    onSessionComplete: (SessionBodyReport) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PracticeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ),
    )

    LaunchedEffect(viewModel) {
        viewModel.sessionCompleted.collect { report ->
            onSessionComplete(report)
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
            permissionsState.allPermissionsGranted -> {
                PracticeSessionContent(
                    uiState = uiState,
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
                PermissionRationale(
                    permissionsState = permissionsState,
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
private fun PermissionRationale(
    permissionsState: MultiplePermissionsState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Permessi necessari",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Orato usa la fotocamera frontale e il microfono per la sessione di pratica. " +
                "Nessun video viene salvato in questa milestone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
            Text("Consenti fotocamera e microfono")
        }
    }
}

@Composable
private fun PracticeSessionContent(
    uiState: PracticeUiState,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onPoseFrame: (UpperBodyPoseFrame) -> Unit,
    onPoseStatus: (PoseDetectionStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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

            LiveMetricsDebugPanel(
                metrics = uiState.liveMetrics,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }

        Text(
            text = when {
                uiState.isFinished -> "Sessione completata. Apertura report corporeo…"
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
        DebugLine(
            "L-sh",
            landmarkDebug(metrics.leftShoulder),
        )
        DebugLine(
            "R-sh",
            landmarkDebug(metrics.rightShoulder),
        )
        DebugLine(
            "L-hip",
            landmarkDebug(metrics.leftHip),
        )
        DebugLine(
            "R-hip",
            landmarkDebug(metrics.rightHip),
        )
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
        DebugLine("L-hand", if (metrics.leftHandVisible) "yes" else "no")
        DebugLine("R-hand", if (metrics.rightHandVisible) "yes" else "no")
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

private fun landmarkDebug(info: com.orato.app.metrics.LandmarkDebugInfo): String {
    val vis = info.visibility?.let { "%.2f".format(it) } ?: "—"
    val frame = if (info.inFrame) "in" else "out"
    return "$vis/$frame"
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
    )
}
