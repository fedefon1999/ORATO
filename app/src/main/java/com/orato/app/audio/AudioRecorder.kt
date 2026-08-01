package com.orato.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Session-scoped microphone capture using [AudioRecord] (not MediaRecorder).
 *
 * - Mono PCM 16-bit; prefers 16 kHz with robust fallbacks.
 * - Audio source preference: UNPROCESSED → VOICE_RECOGNITION → MIC.
 * - All reads run off the main thread.
 * - At most one [AudioRecord] instance; shutdown is idempotent.
 * - Writes `cacheDir/orato_sessions/{sessionId}.wav` (path never shown in UI).
 * - Completed WAV is kept for optional post-session offline transcription.
 */
class AudioRecorder(
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stopMutex = Mutex()
    private val stopOnce = AtomicBoolean(false)

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavFileWriter? = null
    private var analyzer: AudioFrameAnalyzer? = null
    private var accumulator: AudioSessionAccumulator? = null

    private var sessionId: String? = null
    private var outputFile: File? = null
    private var sampleRateHz: Int = 0
    private var audioSourceLabel: String? = null
    private var keepFileOnStop: Boolean = false

    private val _state = MutableStateFlow(AudioRecordingState.Idle)
    val state: StateFlow<AudioRecordingState> = _state.asStateFlow()

    private val _liveDebug = MutableStateFlow(LiveAudioDebug())
    val liveDebug: StateFlow<LiveAudioDebug> = _liveDebug.asStateFlow()

    private val _metrics = MutableStateFlow(AudioSessionMetrics.idle())
    val metrics: StateFlow<AudioSessionMetrics> = _metrics.asStateFlow()

    /**
     * Path of the completed session WAV when [AudioRecordingState.Completed], else null.
     * Used for post-session offline transcription — never shown in the UI.
     */
    fun completedWavFile(): File? {
        val file = outputFile
        return if (_state.value == AudioRecordingState.Completed && file != null && file.isFile) {
            file
        } else {
            null
        }
    }

    /**
     * Starts capture for a new practice session.
     * No-ops if already initializing/recording/stopping.
     *
     * @param microphonePermissionGranted caller must verify RECORD_AUDIO
     */
    fun start(microphonePermissionGranted: Boolean) {
        val current = _state.value
        if (current == AudioRecordingState.Initializing ||
            current == AudioRecordingState.Recording ||
            current == AudioRecordingState.Stopping
        ) {
            return
        }

        stopOnce.set(false)
        keepFileOnStop = false
        _metrics.value = AudioSessionMetrics.idle()
        _state.value = AudioRecordingState.Initializing
        _liveDebug.value = LiveAudioDebug(state = AudioRecordingState.Initializing)

        if (!microphonePermissionGranted || !hasRecordAudioPermission()) {
            failAndIdle("Permesso microfono non concesso")
            return
        }

        recordJob = scope.launch {
            try {
                beginRecordingLocked()
            } catch (t: Throwable) {
                handleUnrecoverable(t.message ?: "AudioRecord initialization failed")
            }
        }
    }

    /**
     * Marks the session as unable to capture audio (permission denied / revoked)
     * without creating an [AudioRecord]. Body practice can continue.
     */
    fun markUnavailable(message: String) {
        stopOnce.set(true)
        keepFileOnStop = false
        releaseRecorderOnly()
        _state.value = AudioRecordingState.Error
        _metrics.value = AudioSessionMetrics.recordingError(message)
        _liveDebug.value = LiveAudioDebug(
            state = AudioRecordingState.Error,
            errorMessage = message,
        )
    }

    /**
     * Stops capture exactly once.
     * @param completed true when the 90 s timer finished successfully — keep WAV;
     *                  false for cancel / leave / background / permission / error — delete WAV.
     */
    suspend fun stop(completed: Boolean) {
        stopMutex.withLock {
            val prior = _state.value
            // Already terminal — keep existing metrics (including RECORDING_ERROR).
            if (prior == AudioRecordingState.Idle ||
                prior == AudioRecordingState.Completed ||
                prior == AudioRecordingState.Error
            ) {
                if (!stopOnce.get()) stopOnce.set(true)
                // Never delete a successfully completed WAV here — Whisper may still need it.
                if (prior != AudioRecordingState.Completed) {
                    cleanupFailedFile()
                }
                releaseRecorderOnly()
                return
            }
            if (!stopOnce.compareAndSet(false, true)) {
                return
            }
            keepFileOnStop = completed

            _state.value = AudioRecordingState.Stopping
            publishLive(errorMessage = _liveDebug.value.errorMessage)

            recordJob?.cancelAndJoin()
            recordJob = null

            finalizeAndPublish(completed = completed, errorMessage = null)
        }
    }

    /** Idempotent stop from non-suspend callers (main thread safe). */
    fun stopAsync(completed: Boolean) {
        scope.launch {
            stop(completed)
        }
    }

    /**
     * Full reset between exercises / when leaving the practice screen.
     * Stops recording; does not delete a WAV that is leased for post-session analysis.
     */
    suspend fun reset() {
        stop(completed = false)
        stopMutex.withLock {
            stopOnce.set(false)
            keepFileOnStop = false
            val file = outputFile
            if (file != null && SessionWavRetention.isRetained(file)) {
                // Keep path for Whisper; clear recorder state only.
                sessionId = null
                sampleRateHz = 0
                audioSourceLabel = null
                analyzer = null
                accumulator = null
                _metrics.value = AudioSessionMetrics.idle()
                _state.value = AudioRecordingState.Idle
                _liveDebug.value = LiveAudioDebug()
                return
            }
            sessionId = null
            outputFile = null
            sampleRateHz = 0
            audioSourceLabel = null
            analyzer = null
            accumulator = null
            _metrics.value = AudioSessionMetrics.idle()
            _state.value = AudioRecordingState.Idle
            _liveDebug.value = LiveAudioDebug()
        }
    }

    fun resetAsync() {
        scope.launch { reset() }
    }

    private suspend fun beginRecordingLocked() = withContext(ioDispatcher) {
        AudioSessionCache.prepareSessionDir(appContext.cacheDir)

        val id = UUID.randomUUID().toString()
        sessionId = id
        val file = AudioSessionCache.sessionFile(appContext.cacheDir, id)
        outputFile = file

        val config = openAudioRecord()
            ?: run {
                handleUnrecoverable("Microfono non disponibile o occupato")
                return@withContext
            }

        audioRecord = config.record
        sampleRateHz = config.sampleRateHz
        audioSourceLabel = config.sourceLabel

        val frameSamples = PcmMath.frameSampleCount(sampleRateHz)
        val frameAnalyzer = AudioFrameAnalyzer(sampleRateHz = sampleRateHz, frameSampleCount = frameSamples)
        val sessionAcc = AudioSessionAccumulator(
            sampleRateHz = sampleRateHz,
            frameSampleCount = frameSamples,
        )
        analyzer = frameAnalyzer
        accumulator = sessionAcc

        val writer = try {
            WavFileWriter(file = file, sampleRateHz = sampleRateHz)
        } catch (t: Throwable) {
            releaseRecorderOnly()
            handleUnrecoverable(t.message ?: "Impossibile creare il file audio")
            return@withContext
        }
        wavWriter = writer

        try {
            config.record.startRecording()
        } catch (t: Throwable) {
            writer.close()
            wavWriter = null
            releaseRecorderOnly()
            AudioSessionCache.deleteQuietly(file)
            handleUnrecoverable(t.message ?: "Avvio registrazione fallito")
            return@withContext
        }

        if (config.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            writer.close()
            wavWriter = null
            releaseRecorderOnly()
            AudioSessionCache.deleteQuietly(file)
            handleUnrecoverable("Microfono non disponibile o occupato")
            return@withContext
        }

        _state.value = AudioRecordingState.Recording
        publishLive()

        val readBuffer = ShortArray(frameSamples)
        // Leftover samples when read size is not an exact frame multiple.
        val pending = ShortArray(frameSamples)
        var pendingCount = 0

        try {
            while (isActive && !stopOnce.get()) {
                val read = try {
                    config.record.read(readBuffer, 0, readBuffer.size)
                } catch (t: Throwable) {
                    sessionAcc.recordDroppedRead()
                    handleUnrecoverable(t.message ?: "Errore di lettura audio")
                    return@withContext
                }

                when {
                    read > 0 -> {
                        // Write raw samples to WAV.
                        try {
                            writer.writeSamples(readBuffer, 0, read)
                        } catch (t: Throwable) {
                            handleUnrecoverable(t.message ?: "Errore scrittura audio")
                            return@withContext
                        }

                        // Feed analyzer in fixed-size frames.
                        var offset = 0
                        // Drain pending + new data into frames.
                        while (pendingCount > 0 && offset < read) {
                            val need = frameSamples - pendingCount
                            val take = minOf(need, read - offset)
                            System.arraycopy(readBuffer, offset, pending, pendingCount, take)
                            pendingCount += take
                            offset += take
                            if (pendingCount == frameSamples) {
                                processFrame(frameAnalyzer, sessionAcc, pending, 0, frameSamples)
                                pendingCount = 0
                            }
                        }
                        while (offset + frameSamples <= read) {
                            processFrame(frameAnalyzer, sessionAcc, readBuffer, offset, frameSamples)
                            offset += frameSamples
                        }
                        if (offset < read) {
                            val left = read - offset
                            System.arraycopy(readBuffer, offset, pending, 0, left)
                            pendingCount = left
                        }
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE ||
                        read == AudioRecord.ERROR_DEAD_OBJECT ||
                        read == AudioRecord.ERROR -> {
                        sessionAcc.recordDroppedRead()
                        handleUnrecoverable("Errore registratore ($read)")
                        return@withContext
                    }
                    read == 0 -> {
                        sessionAcc.recordDroppedRead()
                        publishLive()
                    }
                    else -> {
                        // Negative unknown error codes.
                        sessionAcc.recordDroppedRead()
                        handleUnrecoverable("Errore registratore ($read)")
                        return@withContext
                    }
                }
            }

            // Flush trailing partial frame if any (still counts toward duration metrics).
            if (pendingCount > 0) {
                processFrame(frameAnalyzer, sessionAcc, pending, 0, pendingCount)
                pendingCount = 0
            }
        } finally {
            // stop() performs finalization when invoked externally.
            // If the loop exits due to cancellation after stopOnce, finalize is done by stop().
            // If the loop exits due to unrecoverable error, handleUnrecoverable already ran.
        }
    }

    private fun processFrame(
        frameAnalyzer: AudioFrameAnalyzer,
        sessionAcc: AudioSessionAccumulator,
        samples: ShortArray,
        offset: Int,
        length: Int,
    ) {
        val result = frameAnalyzer.analyze(samples, offset, length)
        sessionAcc.acceptFrame(result)
        publishLive()
    }

    private fun publishLive(errorMessage: String? = _liveDebug.value.errorMessage) {
        val acc = accumulator
        val debug = if (acc != null) {
            acc.liveSnapshot(
                state = _state.value,
                audioSourceLabel = audioSourceLabel,
                errorMessage = errorMessage,
            )
        } else {
            LiveAudioDebug(
                state = _state.value,
                sampleRateHz = sampleRateHz.takeIf { it > 0 },
                audioSourceLabel = audioSourceLabel,
                errorMessage = errorMessage,
            )
        }
        _liveDebug.value = debug
    }

    private suspend fun finalizeAndPublish(completed: Boolean, errorMessage: String?) {
        try {
            audioRecord?.run {
                try {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                } catch (_: Throwable) {
                    // ignore
                }
                try {
                    release()
                } catch (_: Throwable) {
                    // ignore
                }
            }
        } finally {
            audioRecord = null
        }

        val writer = wavWriter
        wavWriter = null
        val file = outputFile
        val acc = accumulator
        val source = audioSourceLabel
        val rate = sampleRateHz

            if (completed && errorMessage == null && writer != null && acc != null) {
            try {
                writer.finalizeHeader()
                writer.close()
            } catch (t: Throwable) {
                AudioSessionCache.deleteQuietly(file)
                _state.value = AudioRecordingState.Error
                val msg = t.message ?: "Finalizzazione WAV fallita"
                acc.finalizeOpenSegment()
                _metrics.value = AudioSessionMetrics.recordingError(msg).copy(
                    capturedDurationMs = acc.capturedDurationMs(),
                    speechDurationMs = null,
                    droppedReadCount = acc.droppedReadCount(),
                    sampleRateHz = rate.takeIf { it > 0 },
                    audioSourceLabel = source,
                )
                _liveDebug.value = LiveAudioDebug(
                    state = AudioRecordingState.Error,
                    sampleRateHz = rate.takeIf { it > 0 },
                    audioSourceLabel = source,
                    errorMessage = msg,
                    capturedDurationMs = acc.capturedDurationMs(),
                    droppedReadCount = acc.droppedReadCount(),
                )
                return
            }
            acc.finalizeOpenSegment()
            _state.value = AudioRecordingState.Completed
            val metrics = acc.buildMetrics(
                state = AudioRecordingState.Completed,
                audioSourceLabel = source,
                errorMessage = null,
            )
            _metrics.value = metrics
            _liveDebug.value = acc.liveSnapshot(
                state = AudioRecordingState.Completed,
                audioSourceLabel = source,
                errorMessage = null,
            )
        } else {
            try {
                writer?.close()
            } catch (_: Throwable) {
                // ignore
            }
            AudioSessionCache.deleteQuietly(file)
            outputFile = null
            if (errorMessage != null) {
                _state.value = AudioRecordingState.Error
                _metrics.value = if (acc != null) {
                    acc.buildMetrics(
                        state = AudioRecordingState.Error,
                        audioSourceLabel = source,
                        errorMessage = errorMessage,
                    )
                } else {
                    AudioSessionMetrics.recordingError(errorMessage)
                }
                publishLive(errorMessage)
            } else {
                _state.value = AudioRecordingState.Idle
                _metrics.value = AudioSessionMetrics.idle()
                _liveDebug.value = LiveAudioDebug()
            }
        }
    }

    private fun handleUnrecoverable(message: String) {
        if (!stopOnce.compareAndSet(false, true)) {
            // Already stopping; just publish error hint.
            _liveDebug.value = _liveDebug.value.copy(errorMessage = message)
            return
        }
        _state.value = AudioRecordingState.Stopping
        scope.launch {
            stopMutex.withLock {
                recordJob = null
                finalizeAndPublish(completed = false, errorMessage = message)
            }
        }
    }

    private fun failAndIdle(message: String) {
        stopOnce.set(true)
        _state.value = AudioRecordingState.Error
        _metrics.value = AudioSessionMetrics.recordingError(message)
        _liveDebug.value = LiveAudioDebug(
            state = AudioRecordingState.Error,
            errorMessage = message,
        )
    }

    private fun cleanupFailedFile() {
        AudioSessionCache.deleteQuietly(outputFile)
        outputFile = null
    }

    private fun releaseRecorderOnly() {
        try {
            audioRecord?.run {
                try {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                } catch (_: Throwable) {
                }
                try {
                    release()
                } catch (_: Throwable) {
                }
            }
        } finally {
            audioRecord = null
        }
        try {
            wavWriter?.close()
        } catch (_: Throwable) {
        }
        wavWriter = null
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private data class OpenedRecord(
        val record: AudioRecord,
        val sampleRateHz: Int,
        val sourceLabel: String,
    )

    private fun openAudioRecord(): OpenedRecord? {
        val sources = preferredSources()
        val rates = buildList {
            add(AudioMetricsConfig.PREFERRED_SAMPLE_RATE_HZ)
            AudioMetricsConfig.FALLBACK_SAMPLE_RATES_HZ.forEach { add(it) }
        }.distinct()

        for (source in sources) {
            for (rate in rates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                    continue
                }
                val bufferSize = (minBuf * AudioMetricsConfig.AUDIO_RECORD_BUFFER_MULTIPLIER)
                    .coerceAtLeast(minBuf)
                val record = try {
                    AudioRecord(
                        source.source,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                    )
                } catch (_: SecurityException) {
                    return null
                } catch (_: Throwable) {
                    continue
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    try {
                        record.release()
                    } catch (_: Throwable) {
                    }
                    continue
                }
                return OpenedRecord(record, rate, source.label)
            }
        }
        return null
    }

    private data class SourceOption(val source: Int, val label: String)

    private fun preferredSources(): List<SourceOption> {
        val list = mutableListOf<SourceOption>()
        if (supportsUnprocessed()) {
            list.add(SourceOption(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"))
        }
        list.add(SourceOption(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"))
        list.add(SourceOption(MediaRecorder.AudioSource.MIC, "MIC"))
        return list
    }

    private fun supportsUnprocessed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "yes"
        } catch (_: Throwable) {
            false
        }
    }
}
