package com.orato.app.ui.calibration

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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.orato.app.domain.model.Scenario
import com.orato.app.domain.model.VisualAnalysisMapping
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.FaceCalibrationManager
import com.orato.app.face.FaceCalibrationProgress
import com.orato.app.face.FaceCalibrationProfile
import com.orato.app.face.FaceCalibrationUiState
import com.orato.app.face.PendingFaceCalibration
import com.orato.app.ui.practice.FrontCameraPreview

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun FaceCalibrationScreen(
    scenario: Scenario,
    onCalibrationComplete: (FaceCalibrationProfile) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = VisualAnalysisMapping.modeFor(scenario)
    val requireUpperTorso = mode == VisualAnalysisMode.BODY_AND_FACE
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val calibrationManager = remember(requireUpperTorso) {
        FaceCalibrationManager(requireUpperTorso = requireUpperTorso)
    }
    var progress by remember {
        mutableStateOf(
            FaceCalibrationProgress(
                uiState = FaceCalibrationUiState.POSITION_FACE,
                acceptedSamples = 0,
                requiredSamples = com.orato.app.face.FaceMetricsConfig.CALIBRATION_MIN_ACCEPTED_SAMPLES,
                validContinuousDurationMs = 0L,
                requiredDurationMs = com.orato.app.face.FaceMetricsConfig.MIN_VALID_CALIBRATION_DURATION_MS,
                faceValid = false,
                irisValid = false,
                shouldersValid = if (requireUpperTorso) false else null,
            ),
        )
    }

    DisposableEffect(Unit) {
        calibrationManager.reset()
        onDispose {
            // Do not persist calibration if cancelled mid-way without completion.
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Calibrazione della videocamera") },
                navigationIcon = {
                    IconButton(onClick = {
                        PendingFaceCalibration.clear()
                        onCancel()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Annulla",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        if (!cameraPermission.status.isGranted) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Permesso fotocamera necessario per la calibrazione.",
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                    Text("Consenti fotocamera")
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Guarda direttamente la videocamera.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Mantieni la testa ferma per alcuni secondi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (requireUpperTorso) {
                Text(
                    text = "Assicurati che siano visibili il viso, le spalle e la parte superiore del busto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                FrontCameraPreview(
                    visualAnalysisMode = mode,
                    onPoseFrame = { pose ->
                        calibrationManager.onPoseFrame(pose)
                    },
                    onFaceFrame = { frame ->
                        val next = calibrationManager.process(frame)
                        progress = next
                        next.profile?.let { profile ->
                            PendingFaceCalibration.set(profile)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                Text(
                    text = calibrationStatusLabel(progress.uiState),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                        .padding(12.dp),
                )
            }

            // Truthful progress from accepted stable samples — not elapsed time.
            LinearProgressIndicator(
                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${progress.acceptedSamples} / ${progress.requiredSamples} campioni · " +
                    "${progress.validContinuousDurationMs} / ${progress.requiredDurationMs} ms validi",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            if (progress.uiState == FaceCalibrationUiState.COMPLETED && progress.profile != null) {
                Button(
                    onClick = {
                        val profile = progress.profile!!
                        PendingFaceCalibration.set(profile)
                        onCalibrationComplete(profile)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continua")
                }
            }
        }
    }
}

private fun calibrationStatusLabel(state: FaceCalibrationUiState): String =
    when (state) {
        FaceCalibrationUiState.POSITION_FACE -> "Posiziona il viso nell’inquadratura"
        FaceCalibrationUiState.MOVE_CLOSER -> "Avvicinati leggermente"
        FaceCalibrationUiState.MOVE_FARTHER -> "Allontanati leggermente"
        FaceCalibrationUiState.LOOK_AT_CAMERA -> "Guarda la videocamera"
        FaceCalibrationUiState.HOLD_STILL -> "Mantieni la posizione"
        FaceCalibrationUiState.SHOW_SHOULDERS -> "Rendi visibili anche le spalle"
        FaceCalibrationUiState.COMPLETED -> "Calibrazione completata"
    }
