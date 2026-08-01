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
import com.orato.app.domain.model.Scenario
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechReportPresentation
import com.orato.app.speech.WhisperModelState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun PracticeScreen(
    scenario: Scenario,
    onExit: () -> Unit,
    onSessionEnded: (SessionEndedNavigation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PracticeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    LaunchedEffect(scenario) {
        viewModel.bindScenario(scenario)
    }

    LaunchedEffect(viewModel) {
        viewModel.sessionEnded.collect { nav ->
            onSessionEnded(nav)
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
                val analysisMode = com.orato.app.domain.model.VisualAnalysisMapping.modeFor(scenario)
                PracticeSessionContent(
                    uiState = uiState,
                    visualAnalysisMode = analysisMode,
                    microphoneGranted = micPermission.status.isGranted,
                    onRequestMicrophone = { micPermission.launchPermissionRequest() },
                    onStart = viewModel::startSession,
                    onReset = viewModel::resetSession,
                    onDownloadModel = viewModel::downloadWhisperModel,
                    onCancelDownload = viewModel::cancelWhisperModelDownload,
                    onRemoveModel = viewModel::removeWhisperModel,
                    onPoseFrame = viewModel::onPoseFrame,
                    onPoseStatus = viewModel::onPoseStatus,
                    onFaceFrame = viewModel::onFaceFrame,
                    onFaceStatus = viewModel::onFaceStatus,
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
    visualAnalysisMode: com.orato.app.domain.model.VisualAnalysisMode,
    microphoneGranted: Boolean,
    onRequestMicrophone: () -> Unit,
    onStart: () -> Unit,
    onReset: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemoveModel: () -> Unit,
    onPoseFrame: (UpperBodyPoseFrame) -> Unit,
    onPoseStatus: (PoseDetectionStatus) -> Unit,
    onFaceFrame: (com.orato.app.face.FaceFrame) -> Unit,
    onFaceStatus: (com.orato.app.face.FaceDetectionStatus) -> Unit,
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
                visualAnalysisMode = visualAnalysisMode,
                onPoseFrame = onPoseFrame,
                onPoseStatus = onPoseStatus,
                onFaceFrame = onFaceFrame,
                onFaceStatus = onFaceStatus,
                modifier = Modifier.fillMaxSize(),
            )

            if (visualAnalysisMode != com.orato.app.domain.model.VisualAnalysisMode.FACE_ONLY) {
                PoseSkeletonOverlay(
                    poseFrame = uiState.poseFrame,
                    mirrorHorizontally = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }

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
                    text = uiState.compactValidityLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                )
            }

            PracticeDebugPanel(
                visualAnalysisMode = visualAnalysisMode,
                poseStatus = uiState.poseStatus,
                poseStatusLabel = uiState.poseStatusLabel,
                bodyMetrics = uiState.liveMetrics,
                faceMetrics = uiState.liveFaceMetrics,
                faceStatusLabel = uiState.faceStatusLabel,
                audioDebug = uiState.audioDebug,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }

        WhisperModelStatusRow(
            state = uiState.whisperModelState,
            sessionRunning = uiState.isRunning || uiState.isFinished,
            onDownload = onDownloadModel,
            onCancelDownload = onCancelDownload,
            onRemove = onRemoveModel,
        )

        Text(
            text = "L’audio viene analizzato durante l’esercizio e salvato temporaneamente " +
                "sul dispositivo. La trascrizione offline avviene dopo la sessione, se il modello è pronto.",
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
                uiState.isFinished -> "Sessione completata. Preparazione del report…"
                uiState.isRunning -> practiceRunningInstruction(visualAnalysisMode)
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

@Composable
private fun WhisperModelStatusRow(
    state: WhisperModelState,
    sessionRunning: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val label = SpeechReportPresentation.modelStatusLabel(state)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!sessionRunning &&
            (state is WhisperModelState.NotDownloaded ||
                state is WhisperModelState.Invalid ||
                state is WhisperModelState.Error)
        ) {
            Text(
                text = SpeechConfig.MODEL_NOT_READY_SESSION_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        if (!sessionRunning) {
            when {
                SpeechReportPresentation.showDownloadAction(state) -> {
                    TextButton(onClick = onDownload) {
                        Text("Scarica modello")
                    }
                }
                state is WhisperModelState.Downloading -> {
                    TextButton(onClick = onCancelDownload) {
                        Text("Annulla download")
                    }
                }
                SpeechReportPresentation.showRemoveAction(state) -> {
                    TextButton(onClick = onRemove) {
                        Text("Rimuovi modello")
                    }
                }
            }
        }
    }
}

internal fun practiceRunningInstruction(
    mode: com.orato.app.domain.model.VisualAnalysisMode,
): String = when (mode) {
    com.orato.app.domain.model.VisualAnalysisMode.BODY_ONLY ->
        "Assicurati che il corpo sia ben visibile nell’inquadratura."
    com.orato.app.domain.model.VisualAnalysisMode.FACE_ONLY ->
        "Mantieni il viso visibile e guarda naturalmente verso la videocamera."
    com.orato.app.domain.model.VisualAnalysisMode.BODY_AND_FACE ->
        "Assicurati che viso, spalle e parte superiore del busto siano visibili."
}
