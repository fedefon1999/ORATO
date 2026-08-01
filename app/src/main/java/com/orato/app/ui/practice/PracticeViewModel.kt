package com.orato.app.ui.practice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orato.app.audio.AudioRecorder
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.LiveAudioDebug
import com.orato.app.audio.PendingSessionReport
import com.orato.app.audio.SessionPracticeReport
import com.orato.app.metrics.BodyMetricsEngine
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame
import com.orato.app.speech.MlKitSpeechTranscriber
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechMetricsCalculator
import com.orato.app.speech.SpeechSessionResult
import com.orato.app.speech.SpeechTranscriber
import com.orato.app.speech.TranscriptResult
import com.orato.app.speech.TranscriptUpdate
import com.orato.app.speech.TranscriptionAvailability
import com.orato.app.speech.TranscriptionPcmBridge
import com.orato.app.speech.TranscriptionState
import com.orato.app.speech.UnavailableReason
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
    val poseStatus: PoseDetectionStatus = PoseDetectionStatus.Initializing,
    val poseFrame: UpperBodyPoseFrame? = null,
    val liveMetrics: LiveBodyMetrics = LiveBodyMetrics(),
    val sessionReport: SessionBodyReport? = null,
    val audioDebug: LiveAudioDebug = LiveAudioDebug(),
    val audioMetrics: AudioSessionMetrics = AudioSessionMetrics.idle(),
    /** When false, body session remains usable but voice metrics are unavailable. */
    val microphoneAvailable: Boolean = true,
    val transcriptionState: TranscriptionState = TranscriptionState.Checking,
    val speechResult: SpeechSessionResult = SpeechSessionResult.NotAttempted,
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

    val audioState: AudioRecordingState
        get() = audioDebug.state
}

class PracticeViewModel(
    application: Application,
    private val speechTranscriber: SpeechTranscriber = MlKitSpeechTranscriber(),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private val _sessionCompleted = MutableSharedFlow<SessionPracticeReport>(extraBufferCapacity = 1)
    val sessionCompleted: SharedFlow<SessionPracticeReport> = _sessionCompleted.asSharedFlow()

    private var timerJob: Job? = null
    private var recognitionJob: Job? = null
    private var availabilityJob: Job? = null
    private val poseUpdatesEnabled = AtomicBoolean(true)
    private val metricsEngine = BodyMetricsEngine()
    private val audioRecorder = AudioRecorder(application.applicationContext)

    private val finishing = AtomicBoolean(false)
    private var pcmBridge: TranscriptionPcmBridge? = null
    private val finalsBuilder = StringBuilder()
    private var latestPartial: String = ""
    private var transcriptionEnabledForSession: Boolean = false

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
        checkTranscriptionAvailability()
    }

    fun checkTranscriptionAvailability() {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            _uiState.update { it.copy(transcriptionState = TranscriptionState.Checking) }
            val availability = try {
                speechTranscriber.checkAvailability()
            } catch (_: Throwable) {
                TranscriptionAvailability.Unavailable(UnavailableReason.DeviceUnsupported)
            }
            _uiState.update {
                it.copy(transcriptionState = availability.toState())
            }
        }
    }

    /** Explicit user action — never called implicitly on screen entry. */
    fun prepareTranscription() {
        val current = _uiState.value.transcriptionState
        if (current is TranscriptionState.Downloading ||
            current is TranscriptionState.Ready ||
            current is TranscriptionState.Available
        ) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(transcriptionState = TranscriptionState.Downloading(null)) }
            val result = try {
                val mlKit = speechTranscriber as? MlKitSpeechTranscriber
                if (mlKit != null) {
                    mlKit.prepareModelWithProgress { pct ->
                        _uiState.update {
                            it.copy(transcriptionState = TranscriptionState.Downloading(pct))
                        }
                    }
                } else {
                    speechTranscriber.prepareModel()
                }
            } catch (_: Throwable) {
                TranscriptionAvailability.Unavailable(UnavailableReason.FeatureUnavailable)
            }
            _uiState.update { state ->
                when (result) {
                    TranscriptionAvailability.Ready ->
                        state.copy(transcriptionState = TranscriptionState.Ready)
                    TranscriptionAvailability.DownloadRequired ->
                        state.copy(
                            transcriptionState = TranscriptionState.Error(
                                SpeechConfig.USER_SAFE_DOWNLOAD_FAILED,
                            ),
                        )
                    is TranscriptionAvailability.Unavailable ->
                        state.copy(
                            transcriptionState = TranscriptionState.Unavailable(result.reason),
                        )
                }
            }
        }
    }

    fun onMicrophoneAvailabilityChanged(available: Boolean) {
        val wasAvailable = _uiState.value.microphoneAvailable
        _uiState.update { it.copy(microphoneAvailable = available) }
        if (!available && wasAvailable && _uiState.value.isRunning) {
            // Permission revoked mid-session — stop audio once; body session continues.
            teardownTranscription(completed = false)
            audioRecorder.markUnavailable("Permesso microfono revocato")
            viewModelScope.launch {
                audioRecorder.stop(completed = false)
            }
        }
    }

    /** Called when the practice screen / app moves to background. */
    fun onLeaveForeground() {
        val running = _uiState.value.isRunning
        val capturing = _uiState.value.audioState == AudioRecordingState.Recording ||
            _uiState.value.audioState == AudioRecordingState.Initializing
        if (running || capturing) {
            teardownTranscription(completed = false)
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
        metricsEngine.reset()
        finalsBuilder.clear()
        latestPartial = ""
        transcriptionEnabledForSession = false

        _uiState.update {
            it.copy(
                remainingSeconds = SESSION_DURATION_SECONDS,
                isRunning = true,
                isFinished = false,
                liveMetrics = LiveBodyMetrics(),
                sessionReport = null,
                speechResult = SpeechSessionResult.NotAttempted,
            )
        }

        viewModelScope.launch {
            audioRecorder.reset()
            if (_uiState.value.microphoneAvailable) {
                val ready = isTranscriptionReady(_uiState.value.transcriptionState)
                audioRecorder.start(microphonePermissionGranted = true)
                if (ready) {
                    // Wait briefly for sample rate to become known once recording starts.
                    var waits = 0
                    while (audioRecorder.currentSampleRateHz() <= 0 && waits < 50) {
                        delay(20)
                        waits++
                    }
                    val rate = audioRecorder.currentSampleRateHz()
                    if (rate > 0) {
                        startTranscriptionPipeline(rate)
                    }
                }
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

    private fun startTranscriptionPipeline(sampleRateHz: Int) {
        try {
            val (readPfd, bridge) = TranscriptionPcmBridge.open(sampleRateHz)
            pcmBridge = bridge
            audioRecorder.setPcmSink { samples, offset, length ->
                bridge.offerPcm16(samples, offset, length)
            }
            transcriptionEnabledForSession = true
            _uiState.update { it.copy(transcriptionState = TranscriptionState.Transcribing) }

            recognitionJob?.cancel()
            recognitionJob = viewModelScope.launch {
                try {
                    speechTranscriber.startRecognition(readPfd).collect { update ->
                        when (update) {
                            is TranscriptUpdate.Partial -> {
                                latestPartial = update.text
                            }
                            is TranscriptUpdate.Final -> {
                                if (update.text.isNotBlank()) {
                                    if (finalsBuilder.isNotEmpty()) finalsBuilder.append(' ')
                                    finalsBuilder.append(update.text.trim())
                                }
                                latestPartial = ""
                            }
                            TranscriptUpdate.Completed -> {
                                publishSpeechResult(success = true)
                            }
                            is TranscriptUpdate.Failed -> {
                                publishSpeechResult(
                                    success = false,
                                    errorMessage = update.userSafeMessage,
                                )
                            }
                        }
                    }
                    // Flow completed without explicit Completed — still finalize.
                    if (_uiState.value.speechResult is SpeechSessionResult.NotAttempted &&
                        transcriptionEnabledForSession
                    ) {
                        publishSpeechResult(success = finalsBuilder.isNotEmpty() || latestPartial.isNotEmpty())
                    }
                } catch (_: Throwable) {
                    publishSpeechResult(
                        success = false,
                        errorMessage = SpeechConfig.USER_SAFE_RECOGNITION_ERROR,
                    )
                }
            }
        } catch (_: Throwable) {
            // Transcription setup failed — capture continues normally.
            transcriptionEnabledForSession = false
            audioRecorder.setPcmSink(null)
            _uiState.update {
                it.copy(
                    transcriptionState = TranscriptionState.Error(
                        SpeechConfig.USER_SAFE_RECOGNITION_ERROR,
                    ),
                    speechResult = SpeechSessionResult.Unavailable(
                        SpeechConfig.USER_SAFE_RECOGNITION_ERROR,
                    ),
                )
            }
            PendingSessionReport.updateSpeech(
                SpeechSessionResult.Unavailable(SpeechConfig.USER_SAFE_RECOGNITION_ERROR),
            )
        }
    }

    private fun publishSpeechResult(success: Boolean, errorMessage: String? = null) {
        val transcript = buildString {
            append(finalsBuilder)
            if (latestPartial.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append(latestPartial.trim())
            }
        }.trim()

        val audio = audioRecorder.metrics.value
        val speech: SpeechSessionResult = when {
            !success && transcript.isEmpty() ->
                SpeechSessionResult.Unavailable(
                    errorMessage ?: SpeechConfig.USER_SAFE_RECOGNITION_ERROR,
                )
            transcript.isEmpty() ->
                SpeechSessionResult.Unavailable(SpeechConfig.METRICS_UNAVAILABLE_REPORT)
            else -> {
                val metrics = SpeechMetricsCalculator.compute(
                    transcript = transcript,
                    vadSpeechDurationMs = audio.speechDurationMs ?: 0L,
                    audio = audio,
                )
                if (metrics == null) {
                    SpeechSessionResult.Unavailable(SpeechConfig.METRICS_UNAVAILABLE_REPORT)
                } else {
                    SpeechSessionResult.Ready(metrics)
                }
            }
        }

        val completedState = when (speech) {
            is SpeechSessionResult.Ready ->
                TranscriptionState.Completed(
                    TranscriptResult(transcript = speech.metrics.transcript),
                )
            is SpeechSessionResult.Unavailable ->
                TranscriptionState.Error(speech.userSafeMessage)
            SpeechSessionResult.NotAttempted ->
                TranscriptionState.Ready
        }

        _uiState.update {
            it.copy(
                transcriptionState = completedState,
                speechResult = speech,
            )
        }
        PendingSessionReport.updateSpeech(speech)

        // If a report was already emitted, refresh its speech section in place.
        val pending = PendingSessionReport.peek()
        if (pending != null && pending.speech != speech) {
            PendingSessionReport.set(pending.copy(speech = speech))
        }
    }

    /**
     * Cancels the timer and clears metric accumulators so the next session
     * cannot inherit prior samples. Live preview continues without aggregation.
     * Stops audio exactly once and deletes the incomplete WAV.
     */
    fun resetSession() {
        timerJob?.cancel()
        timerJob = null
        finishing.set(false)
        teardownTranscription(completed = false)
        metricsEngine.reset()
        metricsEngine.stopAccumulation()
        viewModelScope.launch {
            audioRecorder.reset()
        }
        restoreIdleUiPreservingPose()
    }

    /** Like [resetSession] but assumes audio stop was already requested. */
    private fun resetSessionKeepingAudioStop() {
        timerJob?.cancel()
        timerJob = null
        finishing.set(false)
        teardownTranscription(completed = false)
        metricsEngine.reset()
        metricsEngine.stopAccumulation()
        viewModelScope.launch {
            audioRecorder.reset()
        }
        restoreIdleUiPreservingPose()
    }

    private fun restoreIdleUiPreservingPose() {
        val poseStatus = _uiState.value.poseStatus
        val poseFrame = _uiState.value.poseFrame
        val micOk = _uiState.value.microphoneAvailable
        val transcriptionState = when (val t = _uiState.value.transcriptionState) {
            is TranscriptionState.Transcribing,
            is TranscriptionState.Completed,
            is TranscriptionState.Error,
            -> {
                // Restore to Ready / DownloadRequired / Unavailable after a cancelled run.
                when {
                    t is TranscriptionState.Error &&
                        t.userSafeMessage == SpeechConfig.USER_SAFE_DOWNLOAD_FAILED -> t
                    t is TranscriptionState.Unavailable -> t
                    isTranscriptionReady(t) || t is TranscriptionState.Completed ->
                        TranscriptionState.Ready
                    else -> t
                }
            }
            else -> t
        }
        _uiState.value = PracticeUiState(
            poseStatus = when (poseStatus) {
                is PoseDetectionStatus.Error -> poseStatus
                PoseDetectionStatus.Initializing -> PoseDetectionStatus.Initializing
                else -> poseStatus
            },
            poseFrame = poseFrame,
            liveMetrics = metricsEngine.liveMetrics(),
            sessionReport = null,
            microphoneAvailable = micOk,
            audioDebug = LiveAudioDebug(),
            audioMetrics = AudioSessionMetrics.idle(),
            transcriptionState = transcriptionState,
            speechResult = SpeechSessionResult.NotAttempted,
        )
    }

    private fun finishSession() {
        if (!finishing.compareAndSet(false, true)) return
        metricsEngine.stopAccumulation()
        val bodyReport = metricsEngine.buildReport()

        viewModelScope.launch {
            // Close PCM write side first so the recognizer can finalize, then stop capture.
            closePcmBridgeWriteSide()
            audioRecorder.stop(completed = true)
            // Give recognition a short window to deliver finals after write-side close.
            delay(400)
            speechTranscriber.stop()

            val audioMetrics = audioRecorder.metrics.value
            if (transcriptionEnabledForSession &&
                _uiState.value.speechResult is SpeechSessionResult.NotAttempted
            ) {
                publishSpeechResult(success = finalsBuilder.isNotEmpty() || latestPartial.isNotEmpty())
            }

            val speech = _uiState.value.speechResult
            val report = SessionPracticeReport(
                body = bodyReport,
                audio = audioMetrics,
                speech = speech,
            )
            _uiState.update {
                it.copy(
                    remainingSeconds = 0,
                    isRunning = false,
                    isFinished = true,
                    sessionReport = bodyReport,
                    audioMetrics = audioMetrics,
                    speechResult = speech,
                )
            }
            PendingSessionReport.set(report)
            _sessionCompleted.tryEmit(report)
        }
    }

    private fun closePcmBridgeWriteSide() {
        audioRecorder.setPcmSink(null)
        try {
            pcmBridge?.closeWriteSide()
        } catch (_: Throwable) {
        }
        pcmBridge = null
    }

    private fun teardownTranscription(completed: Boolean) {
        recognitionJob?.cancel()
        recognitionJob = null
        closePcmBridgeWriteSide()
        viewModelScope.launch {
            try {
                speechTranscriber.stop()
            } catch (_: Throwable) {
            }
        }
        transcriptionEnabledForSession = false
        if (!completed) {
            finalsBuilder.clear()
            latestPartial = ""
        }
    }

    fun onPoseStatus(status: PoseDetectionStatus) {
        if (!poseUpdatesEnabled.get()) return
        _uiState.update { state ->
            if (state.poseStatus is PoseDetectionStatus.Error && status !is PoseDetectionStatus.Error) {
                state
            } else {
                state.copy(poseStatus = status)
            }
        }
    }

    fun onPoseFrame(frame: UpperBodyPoseFrame) {
        if (!poseUpdatesEnabled.get()) return
        if (_uiState.value.poseStatus is PoseDetectionStatus.Error) return

        val live = metricsEngine.processFrame(frame)
        _uiState.update {
            it.copy(
                poseFrame = frame,
                // Latched torso hysteresis from the metrics engine — never a single frame.
                poseStatus = if (live.validDetection) {
                    PoseDetectionStatus.Detected
                } else {
                    PoseDetectionStatus.Insufficient
                },
                liveMetrics = live,
            )
        }
    }

    fun consumeSessionReport(): SessionBodyReport? = _uiState.value.sessionReport

    override fun onCleared() {
        poseUpdatesEnabled.set(false)
        timerJob?.cancel()
        availabilityJob?.cancel()
        teardownTranscription(completed = false)
        metricsEngine.release()
        audioRecorder.resetAsync()
        try {
            speechTranscriber.close()
        } catch (_: Throwable) {
        }
        super.onCleared()
    }

    private fun isTranscriptionReady(state: TranscriptionState): Boolean =
        state is TranscriptionState.Ready || state is TranscriptionState.Available

    private fun TranscriptionAvailability.toState(): TranscriptionState =
        when (this) {
            TranscriptionAvailability.Ready -> TranscriptionState.Ready
            TranscriptionAvailability.DownloadRequired -> TranscriptionState.DownloadRequired
            is TranscriptionAvailability.Unavailable -> TranscriptionState.Unavailable(reason)
        }
}
