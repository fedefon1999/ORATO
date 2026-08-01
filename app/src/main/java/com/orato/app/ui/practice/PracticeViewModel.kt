package com.orato.app.ui.practice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orato.app.audio.AudioRecorder
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.LiveAudioDebug
import com.orato.app.domain.model.Scenario
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.domain.model.VisualAnalysisMapping
import com.orato.app.face.FaceCalibrationProfile
import com.orato.app.face.FaceDetectionStatus
import com.orato.app.face.FaceFrame
import com.orato.app.face.FaceMetricsEngine
import com.orato.app.face.LiveFaceMetrics
import com.orato.app.face.PendingFaceCalibration
import com.orato.app.face.SessionFaceReport
import com.orato.app.metrics.BodyMetricsEngine
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame
import com.orato.app.report.ReportPreparationCoordinator
import com.orato.app.speech.WhisperModelManager
import com.orato.app.speech.WhisperModelState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SESSION_DURATION_SECONDS = 90

data class PracticeUiState(
    val remainingSeconds: Int = SESSION_DURATION_SECONDS,
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val visualAnalysisMode: VisualAnalysisMode = VisualAnalysisMode.BODY_ONLY,
    val poseStatus: PoseDetectionStatus = PoseDetectionStatus.Initializing,
    val faceStatus: FaceDetectionStatus = FaceDetectionStatus.Initializing,
    val poseFrame: UpperBodyPoseFrame? = null,
    val liveMetrics: LiveBodyMetrics = LiveBodyMetrics(),
    val liveFaceMetrics: LiveFaceMetrics = LiveFaceMetrics(),
    val sessionReport: SessionBodyReport? = null,
    val faceReport: SessionFaceReport? = null,
    val audioDebug: LiveAudioDebug = LiveAudioDebug(),
    val audioMetrics: AudioSessionMetrics = AudioSessionMetrics.idle(),
    /** When false, body session remains usable but voice metrics are unavailable. */
    val microphoneAvailable: Boolean = true,
    val whisperModelState: WhisperModelState = WhisperModelState.Checking,
    val bodyFailed: Boolean = false,
    val faceFailed: Boolean = false,
) {
    val progress: Float
        get() = 1f - (remainingSeconds.toFloat() / SESSION_DURATION_SECONDS.toFloat())

    val formattedTime: String
        get() {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val poseStatusLabel: String
        get() = when (val status = poseStatus) {
            PoseDetectionStatus.Initializing -> "Inizializzazione rilevamento..."
            PoseDetectionStatus.Detected -> "Posizione rilevata"
            PoseDetectionStatus.Insufficient -> "Posizionati davanti alla fotocamera"
            is PoseDetectionStatus.Error -> status.message
        }

    val faceStatusLabel: String
        get() = when (val status = faceStatus) {
            FaceDetectionStatus.Initializing -> "Inizializzazione viso..."
            FaceDetectionStatus.Detected -> "Viso rilevato"
            FaceDetectionStatus.Insufficient -> "Posiziona il viso nell’inquadratura"
            is FaceDetectionStatus.Error -> status.message
        }

    /** Compact validity indicator shown during practice (no live gaze %). */
    val compactValidityLabel: String
        get() = when (visualAnalysisMode) {
            VisualAnalysisMode.BODY_ONLY -> poseStatusLabel
            VisualAnalysisMode.FACE_ONLY -> faceStatusLabel
            VisualAnalysisMode.BODY_AND_FACE -> when {
                poseStatus is PoseDetectionStatus.Error &&
                    faceStatus is FaceDetectionStatus.Error -> "Analisi visuale non disponibile"
                poseStatus is PoseDetectionStatus.Error -> faceStatusLabel
                faceStatus is FaceDetectionStatus.Error -> poseStatusLabel
                poseStatus == PoseDetectionStatus.Detected ||
                    faceStatus == FaceDetectionStatus.Detected -> "Tracciamento attivo"
                else -> "Inquadra viso e busto"
            }
        }

    val audioState: AudioRecordingState
        get() = audioDebug.state
}

/** Navigation signal after timer finalization — never carries a partial report. */
data class SessionEndedNavigation(
    val sessionId: String,
    val scenarioRouteArg: String,
)

class PracticeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private val _sessionEnded = MutableSharedFlow<SessionEndedNavigation>(extraBufferCapacity = 1)
    val sessionEnded: SharedFlow<SessionEndedNavigation> = _sessionEnded.asSharedFlow()

    private var timerJob: Job? = null
    private val poseUpdatesEnabled = AtomicBoolean(true)
    private val faceUpdatesEnabled = AtomicBoolean(true)
    private val metricsEngine = BodyMetricsEngine()
    private val faceMetricsEngine = FaceMetricsEngine()
    private val audioRecorder = AudioRecorder(application.applicationContext)
    private val modelManager = WhisperModelManager(application.applicationContext)
    private val reportPrep = ReportPreparationCoordinator.get(application.applicationContext)

    private val finishing = AtomicBoolean(false)
    private var activeScenario: Scenario? = null
    private var activeScenarioRouteArg: String = ""
    private var activeScenarioDisplayName: String = ""

    init {
        viewModelScope.launch {
            audioRecorder.liveDebug.collect { debug ->
                _uiState.update { it.copy(audioDebug = debug) }
            }
        }
        viewModelScope.launch {
            audioRecorder.metrics.collect { metrics ->
                _uiState.update { it.copy(audioMetrics = metrics) }
            }
        }
        viewModelScope.launch {
            modelManager.state.collect { modelState ->
                _uiState.update { it.copy(whisperModelState = modelState) }
            }
        }
        viewModelScope.launch {
            modelManager.refresh()
        }
    }

    fun bindScenario(scenario: Scenario) {
        activeScenario = scenario
        activeScenarioRouteArg = scenario.routeArg
        activeScenarioDisplayName = scenario.displayName
        val mode = VisualAnalysisMapping.modeFor(scenario)
        val calibration = PendingFaceCalibration.peek()
        if (calibration != null) {
            faceMetricsEngine.setCalibration(calibration)
        }
        _uiState.update {
            it.copy(visualAnalysisMode = mode)
        }
    }

    /** @deprecated Prefer [bindScenario]. */
    fun bindScenario(routeArg: String, displayName: String) {
        activeScenarioRouteArg = routeArg
        activeScenarioDisplayName = displayName
        val scenario = Scenario.fromRouteArg(routeArg)
        bindScenario(scenario)
    }

    fun applyCalibration(profile: FaceCalibrationProfile) {
        faceMetricsEngine.setCalibration(profile)
        PendingFaceCalibration.set(profile)
    }

    /** Explicit user action — never called implicitly on screen entry. */
    fun downloadWhisperModel() {
        viewModelScope.launch {
            modelManager.download()
        }
    }

    fun cancelWhisperModelDownload() {
        modelManager.cancelDownload()
    }

    fun removeWhisperModel() {
        viewModelScope.launch {
            modelManager.removeModel()
        }
    }

    fun onMicrophoneAvailabilityChanged(available: Boolean) {
        val wasAvailable = _uiState.value.microphoneAvailable
        _uiState.update { it.copy(microphoneAvailable = available) }
        if (!available && wasAvailable && _uiState.value.isRunning) {
            audioRecorder.markUnavailable("Permesso microfono revocato")
            viewModelScope.launch {
                audioRecorder.stop(completed = false)
            }
        }
    }

    fun onLeaveForeground() {
        val running = _uiState.value.isRunning
        val capturing = _uiState.value.audioState == AudioRecordingState.Recording ||
            _uiState.value.audioState == AudioRecordingState.Initializing
        if (running || capturing) {
            viewModelScope.launch {
                audioRecorder.stop(completed = false)
            }
            if (running) {
                resetSessionKeepingAudioStop()
            }
        }
    }

    fun startSession() {
        if (_uiState.value.isRunning || _uiState.value.isFinished) return

        finishing.set(false)
        val mode = _uiState.value.visualAnalysisMode
        if (VisualAnalysisMapping.usesPose(mode)) {
            metricsEngine.reset()
        }
        if (VisualAnalysisMapping.usesFace(mode)) {
            faceMetricsEngine.reset()
            PendingFaceCalibration.peek()?.let { faceMetricsEngine.setCalibration(it) }
        }

        _uiState.update {
            it.copy(
                remainingSeconds = SESSION_DURATION_SECONDS,
                isRunning = true,
                isFinished = false,
                liveMetrics = LiveBodyMetrics(),
                liveFaceMetrics = LiveFaceMetrics(),
                sessionReport = null,
                faceReport = null,
                bodyFailed = false,
                faceFailed = false,
            )
        }

        // Exactly one AudioRecord start per session.
        viewModelScope.launch {
            audioRecorder.reset()
            if (_uiState.value.microphoneAvailable) {
                audioRecorder.start(microphonePermissionGranted = true)
            } else {
                audioRecorder.markUnavailable(
                    "Microfono non disponibile — metriche vocali assenti",
                )
            }
        }

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val current = _uiState.value.remainingSeconds
                if (current <= 1) {
                    finishSession()
                    break
                } else {
                    _uiState.update { it.copy(remainingSeconds = current - 1) }
                }
            }
        }
    }

    fun resetSession() {
        timerJob?.cancel()
        timerJob = null
        finishing.set(false)
        metricsEngine.reset()
        metricsEngine.stopAccumulation()
        faceMetricsEngine.reset()
        faceMetricsEngine.stopAccumulation()
        viewModelScope.launch {
            audioRecorder.reset()
        }
        restoreIdleUiPreservingPose()
    }

    private fun resetSessionKeepingAudioStop() {
        timerJob?.cancel()
        timerJob = null
        finishing.set(false)
        metricsEngine.reset()
        metricsEngine.stopAccumulation()
        faceMetricsEngine.reset()
        faceMetricsEngine.stopAccumulation()
        viewModelScope.launch {
            audioRecorder.reset()
        }
        restoreIdleUiPreservingPose()
    }

    private fun restoreIdleUiPreservingPose() {
        val poseStatus = _uiState.value.poseStatus
        val faceStatus = _uiState.value.faceStatus
        val poseFrame = _uiState.value.poseFrame
        val micOk = _uiState.value.microphoneAvailable
        val modelState = _uiState.value.whisperModelState
        val mode = _uiState.value.visualAnalysisMode
        _uiState.value = PracticeUiState(
            visualAnalysisMode = mode,
            poseStatus = when (poseStatus) {
                is PoseDetectionStatus.Error -> poseStatus
                PoseDetectionStatus.Initializing -> PoseDetectionStatus.Initializing
                else -> poseStatus
            },
            faceStatus = when (faceStatus) {
                is FaceDetectionStatus.Error -> faceStatus
                FaceDetectionStatus.Initializing -> FaceDetectionStatus.Initializing
                else -> faceStatus
            },
            poseFrame = poseFrame,
            liveMetrics = metricsEngine.liveMetrics(),
            liveFaceMetrics = faceMetricsEngine.liveMetrics(),
            sessionReport = null,
            faceReport = null,
            microphoneAvailable = micOk,
            audioDebug = LiveAudioDebug(),
            audioMetrics = AudioSessionMetrics.idle(),
            whisperModelState = modelState,
        )
    }

    /**
     * Stops capture exactly once, finalizes required visual/audio components,
     * starts report preparation, then emits navigation — never a partial final report.
     */
    private fun finishSession() {
        if (!finishing.compareAndSet(false, true)) return
        val mode = _uiState.value.visualAnalysisMode
        val usesPose = VisualAnalysisMapping.usesPose(mode)
        val usesFace = VisualAnalysisMapping.usesFace(mode)

        if (usesPose) metricsEngine.stopAccumulation()
        if (usesFace) {
            faceMetricsEngine.stopAccumulation()
            // Close the last observed interval at a monotonic end bound.
            faceMetricsEngine.finalizeAt(android.os.SystemClock.uptimeMillis())
        }

        val bodyFailed = _uiState.value.bodyFailed ||
            _uiState.value.poseStatus is PoseDetectionStatus.Error
        val faceFailed = _uiState.value.faceFailed ||
            _uiState.value.faceStatus is FaceDetectionStatus.Error

        val bodyReport = if (usesPose && !bodyFailed) {
            metricsEngine.buildReport()
        } else if (usesPose) {
            null
        } else {
            null
        }
        val faceReport = if (usesFace && !faceFailed) {
            faceMetricsEngine.buildReport()
        } else {
            null
        }

        viewModelScope.launch {
            audioRecorder.stop(completed = true)
            val audioMetrics = audioRecorder.metrics.value
            val wav = audioRecorder.completedWavFile()
            val sessionId = UUID.randomUUID().toString()
            if (wav != null) {
                com.orato.app.audio.SessionWavRetention.retain(wav)
            }

            _uiState.update {
                it.copy(
                    remainingSeconds = 0,
                    isRunning = false,
                    isFinished = true,
                    sessionReport = bodyReport,
                    faceReport = faceReport,
                    audioMetrics = audioMetrics,
                    bodyFailed = bodyFailed,
                    faceFailed = faceFailed,
                )
            }

            val modelReady = modelManager.ensureReadyFromDisk()
            reportPrep.start(
                scenarioName = activeScenarioDisplayName.ifBlank { "Sessione" },
                totalSessionDurationMs = SESSION_DURATION_SECONDS * 1_000L,
                body = bodyReport,
                audio = audioMetrics,
                wavFile = wav,
                modelReady = modelReady,
                sessionId = sessionId,
                scenario = activeScenario,
                visualAnalysisMode = mode,
                face = faceReport,
                bodyFailed = usesPose && bodyFailed,
                faceFailed = usesFace && faceFailed,
            )

            PendingFaceCalibration.clear()

            _sessionEnded.tryEmit(
                SessionEndedNavigation(
                    sessionId = sessionId,
                    scenarioRouteArg = activeScenarioRouteArg,
                ),
            )
        }
    }

    fun onPoseStatus(status: PoseDetectionStatus) {
        if (!poseUpdatesEnabled.get()) return
        _uiState.update { state ->
            val next = if (state.poseStatus is PoseDetectionStatus.Error &&
                status !is PoseDetectionStatus.Error
            ) {
                state
            } else {
                state.copy(
                    poseStatus = status,
                    bodyFailed = state.bodyFailed || status is PoseDetectionStatus.Error,
                )
            }
            next
        }
    }

    fun onFaceStatus(status: FaceDetectionStatus) {
        if (!faceUpdatesEnabled.get()) return
        _uiState.update { state ->
            val next = if (state.faceStatus is FaceDetectionStatus.Error &&
                status !is FaceDetectionStatus.Error
            ) {
                state
            } else {
                state.copy(
                    faceStatus = status,
                    faceFailed = state.faceFailed || status is FaceDetectionStatus.Error,
                )
            }
            next
        }
    }

    fun onPoseFrame(frame: UpperBodyPoseFrame) {
        if (!poseUpdatesEnabled.get()) return
        if (_uiState.value.poseStatus is PoseDetectionStatus.Error) return
        if (!VisualAnalysisMapping.usesPose(_uiState.value.visualAnalysisMode)) return

        val live = metricsEngine.processFrame(frame)
        _uiState.update {
            it.copy(
                poseFrame = frame,
                poseStatus = if (live.validDetection) {
                    PoseDetectionStatus.Detected
                } else {
                    PoseDetectionStatus.Insufficient
                },
                liveMetrics = live,
            )
        }
    }

    fun onFaceFrame(frame: FaceFrame) {
        if (!faceUpdatesEnabled.get()) return
        if (_uiState.value.faceStatus is FaceDetectionStatus.Error) return
        if (!VisualAnalysisMapping.usesFace(_uiState.value.visualAnalysisMode)) return

        val live = faceMetricsEngine.processFrame(frame)
        _uiState.update {
            it.copy(
                faceStatus = if (live.validTracking || live.facePresent) {
                    if (live.validTracking) FaceDetectionStatus.Detected
                    else FaceDetectionStatus.Insufficient
                } else {
                    FaceDetectionStatus.Insufficient
                },
                liveFaceMetrics = live,
            )
        }
    }

    fun consumeSessionReport(): SessionBodyReport? = _uiState.value.sessionReport

    override fun onCleared() {
        poseUpdatesEnabled.set(false)
        faceUpdatesEnabled.set(false)
        timerJob?.cancel()
        metricsEngine.release()
        faceMetricsEngine.release()
        PendingFaceCalibration.clear()
        audioRecorder.resetAsync()
        // Report preparation continues on the application-scoped coordinator.
        super.onCleared()
    }
}
