package com.orato.app.ui.practice

import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.domain.model.VisualAnalysisMapping
import com.orato.app.face.FaceDetectionStatus
import com.orato.app.face.FaceFrame
import com.orato.app.face.FaceLandmarkerClient
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.PoseLandmarkerClient
import com.orato.app.pose.UpperBodyPoseFrame
import com.orato.app.vision.VisualAnalysisCoordinator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "FrontCameraPreview"

/**
 * Single front-camera CameraX binding with mode-aware visual analysis.
 *
 * Does not record, save, or upload frames.
 * Pose and Face share one ImageAnalysis use case (STRATEGY_KEEP_ONLY_LATEST).
 */
@Composable
fun FrontCameraPreview(
    visualAnalysisMode: VisualAnalysisMode,
    onPoseFrame: (UpperBodyPoseFrame) -> Unit = {},
    onPoseStatus: (PoseDetectionStatus) -> Unit = {},
    onFaceFrame: (FaceFrame) -> Unit = {},
    onFaceStatus: (FaceDetectionStatus) -> Unit = {},
    onCoordinatorReady: (VisualAnalysisCoordinator?, FaceLandmarkerClient?, PoseLandmarkerClient?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val sessionId = remember { AtomicLong(System.nanoTime()) }
    val poseRef = remember { AtomicReference<PoseLandmarkerClient?>(null) }
    val faceRef = remember { AtomicReference<FaceLandmarkerClient?>(null) }
    val coordinatorRef = remember { AtomicReference<VisualAnalysisCoordinator?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, visualAnalysisMode) {
        val usesPose = VisualAnalysisMapping.usesPose(visualAnalysisMode)
        val usesFace = VisualAnalysisMapping.usesFace(visualAnalysisMode)

        if (usesPose) onPoseStatus(PoseDetectionStatus.Initializing)
        if (usesFace) onFaceStatus(FaceDetectionStatus.Initializing)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val sid = sessionId.incrementAndGet()

        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()

                cameraExecutor.execute {
                    val (pose, face) = VisualAnalysisCoordinator.createClients(
                        context = context,
                        mode = visualAnalysisMode,
                        sessionId = sid,
                        onPoseFrame = onPoseFrame,
                        onPoseError = { message ->
                            onPoseStatus(PoseDetectionStatus.Error(message))
                        },
                        onFaceFrame = onFaceFrame,
                        onFaceError = { message ->
                            onFaceStatus(FaceDetectionStatus.Error(message))
                        },
                    )
                    poseRef.set(pose)
                    faceRef.set(face)

                    val poseReady = pose?.isReady == true
                    val faceReady = face?.isReady == true

                    if (usesPose) {
                        if (poseReady) {
                            onPoseStatus(PoseDetectionStatus.Insufficient)
                        } else if (pose == null) {
                            onPoseStatus(
                                PoseDetectionStatus.Error(
                                    "Impossibile caricare il modello di rilevamento posa.",
                                ),
                            )
                        }
                    }
                    if (usesFace) {
                        if (faceReady) {
                            onFaceStatus(FaceDetectionStatus.Insufficient)
                        } else if (face == null) {
                            onFaceStatus(
                                FaceDetectionStatus.Error(
                                    "Impossibile caricare il modello di rilevamento del viso.",
                                ),
                            )
                        }
                    }

                    val analysisNeeded = (usesPose && poseReady) || (usesFace && faceReady)

                    mainExecutor.execute {
                        try {
                            val preview = Preview.Builder()
                                .build()
                                .also { it.surfaceProvider = previewView.surfaceProvider }

                            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                            val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)

                            if (analysisNeeded) {
                                val coordinator = VisualAnalysisCoordinator(
                                    mode = visualAnalysisMode,
                                    poseClient = pose,
                                    faceClient = face,
                                )
                                coordinatorRef.set(coordinator)
                                onCoordinatorReady(coordinator, face, pose)

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setTargetRotation(rotation)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(cameraExecutor, coordinator)
                                    }
                                useCases += imageAnalysis
                            }

                            // Bind camera exactly once for this session.
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                *useCases.toTypedArray(),
                            )
                        } catch (error: Exception) {
                            Log.e(TAG, "Failed to bind camera use cases", error)
                            if (usesPose) {
                                onPoseStatus(
                                    PoseDetectionStatus.Error(
                                        "Impossibile avviare la fotocamera per il rilevamento.",
                                    ),
                                )
                            }
                            if (usesFace) {
                                onFaceStatus(
                                    FaceDetectionStatus.Error(
                                        "Impossibile avviare la fotocamera per il rilevamento.",
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to obtain camera provider", error)
                if (usesPose) {
                    onPoseStatus(
                        PoseDetectionStatus.Error("Impossibile accedere alla fotocamera."),
                    )
                }
                if (usesFace) {
                    onFaceStatus(
                        FaceDetectionStatus.Error("Impossibile accedere alla fotocamera."),
                    )
                }
            }
        }
        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            coordinatorRef.getAndSet(null)?.close()
            poseRef.getAndSet(null)?.close()
            faceRef.getAndSet(null)?.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
