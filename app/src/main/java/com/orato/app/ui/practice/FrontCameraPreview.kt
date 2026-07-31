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
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.PoseLandmarkerAnalyzer
import com.orato.app.pose.PoseLandmarkerClient
import com.orato.app.pose.UpperBodyPoseFrame
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "FrontCameraPreview"

/**
 * Front-camera preview via CameraX with Pose Landmarker [ImageAnalysis]
 * on a dedicated executor (never the main thread).
 *
 * Does not record, save, or upload frames.
 */
@Composable
fun FrontCameraPreview(
    onPoseFrame: (UpperBodyPoseFrame) -> Unit,
    onPoseStatus: (PoseDetectionStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val clientRef = remember { AtomicReference<PoseLandmarkerClient?>(null) }
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

    DisposableEffect(lifecycleOwner) {
        onPoseStatus(PoseDetectionStatus.Initializing)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()

                cameraExecutor.execute {
                    val client = PoseLandmarkerClient(
                        context = context,
                        onResult = onPoseFrame,
                        onError = { message ->
                            onPoseStatus(PoseDetectionStatus.Error(message))
                        },
                    )
                    client.initialize()
                    clientRef.set(client)

                    val poseReady = client.isReady
                    if (poseReady) {
                        onPoseStatus(PoseDetectionStatus.Insufficient)
                    }

                    mainExecutor.execute {
                        try {
                            val preview = Preview.Builder()
                                .build()
                                .also { it.surfaceProvider = previewView.surfaceProvider }

                            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                            val useCases = mutableListOf<androidx.camera.core.UseCase>(preview)

                            if (poseReady) {
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setTargetRotation(rotation)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            cameraExecutor,
                                            PoseLandmarkerAnalyzer(client),
                                        )
                                    }
                                useCases += imageAnalysis
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                *useCases.toTypedArray(),
                            )
                        } catch (error: Exception) {
                            Log.e(TAG, "Failed to bind camera use cases", error)
                            onPoseStatus(
                                PoseDetectionStatus.Error(
                                    "Impossibile avviare la fotocamera per il rilevamento.",
                                ),
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to obtain camera provider", error)
                onPoseStatus(
                    PoseDetectionStatus.Error(
                        "Impossibile accedere alla fotocamera.",
                    ),
                )
            }
        }
        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            clientRef.getAndSet(null)?.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}
