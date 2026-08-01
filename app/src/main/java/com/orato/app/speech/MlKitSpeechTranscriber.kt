package com.orato.app.speech

import android.os.Build
import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ML Kit GenAI Speech Recognition adapter (alpha).
 * Locale: Italian ([Locale.ITALY] / it-IT), [SpeechRecognizerOptions.Mode.MODE_BASIC], on-device.
 *
 * Never exposes raw exception messages to callers — only [SpeechConfig] user-safe strings.
 */
class MlKitSpeechTranscriber : SpeechTranscriber {

    private val mutex = Mutex()
    private var recognizer: SpeechRecognizer? = null
    private val closed = AtomicBoolean(false)
    private val recognitionActive = AtomicBoolean(false)

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val options = speechRecognizerOptions {
            locale = Locale.ITALY
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        return SpeechRecognition.getClient(options).also { recognizer = it }
    }

    override suspend fun checkAvailability(): TranscriptionAvailability {
        if (closed.get()) {
            return TranscriptionAvailability.Unavailable(UnavailableReason.FeatureUnavailable)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return TranscriptionAvailability.Unavailable(UnavailableReason.UnsupportedApiLevel)
        }
        return mutex.withLock {
            try {
                val client = ensureRecognizer()
                when (client.checkStatus()) {
                    FeatureStatus.AVAILABLE -> TranscriptionAvailability.Ready
                    FeatureStatus.DOWNLOADABLE,
                    FeatureStatus.DOWNLOADING,
                    -> TranscriptionAvailability.DownloadRequired
                    FeatureStatus.UNAVAILABLE ->
                        TranscriptionAvailability.Unavailable(UnavailableReason.FeatureUnavailable)
                    else ->
                        TranscriptionAvailability.Unavailable(UnavailableReason.FeatureUnavailable)
                }
            } catch (_: Throwable) {
                TranscriptionAvailability.Unavailable(UnavailableReason.DeviceUnsupported)
            }
        }
    }

    override suspend fun prepareModel(): TranscriptionAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return TranscriptionAvailability.Unavailable(UnavailableReason.UnsupportedApiLevel)
        }
        return prepareModelWithProgress { }
    }

    /**
     * Downloads while emitting progress percentages when the adapter can derive them.
     */
    suspend fun prepareModelWithProgress(onProgress: (Int?) -> Unit): TranscriptionAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return TranscriptionAvailability.Unavailable(UnavailableReason.UnsupportedApiLevel)
        }
        return mutex.withLock {
            try {
                val client = ensureRecognizer()
                when (client.checkStatus()) {
                    FeatureStatus.AVAILABLE -> TranscriptionAvailability.Ready
                    FeatureStatus.UNAVAILABLE ->
                        TranscriptionAvailability.Unavailable(UnavailableReason.FeatureUnavailable)
                    else -> {
                        var result: TranscriptionAvailability =
                            TranscriptionAvailability.DownloadRequired
                        client.download().collect { status ->
                            when (status) {
                                is DownloadStatus.DownloadCompleted -> {
                                    onProgress(100)
                                    result = TranscriptionAvailability.Ready
                                }
                                is DownloadStatus.DownloadFailed -> {
                                    result = TranscriptionAvailability.Unavailable(
                                        UnavailableReason.FeatureUnavailable,
                                    )
                                }
                                is DownloadStatus.DownloadProgress -> {
                                    onProgress(progressPercentOf(status))
                                }
                                else -> onProgress(null)
                            }
                        }
                        when (client.checkStatus()) {
                            FeatureStatus.AVAILABLE -> TranscriptionAvailability.Ready
                            else -> result
                        }
                    }
                }
            } catch (_: Throwable) {
                TranscriptionAvailability.Unavailable(UnavailableReason.FeatureUnavailable)
            }
        }
    }

    override fun startRecognition(readPfd: ParcelFileDescriptor): Flow<TranscriptUpdate> =
        callbackFlow {
            if (closed.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                trySend(TranscriptUpdate.Failed(SpeechConfig.USER_SAFE_UNAVAILABLE))
                close()
                return@callbackFlow
            }
            recognitionActive.set(true)
            val client = try {
                ensureRecognizer()
            } catch (_: Throwable) {
                trySend(TranscriptUpdate.Failed(SpeechConfig.USER_SAFE_RECOGNITION_ERROR))
                close()
                return@callbackFlow
            }

            val request = try {
                speechRecognizerRequest {
                    audioSource = AudioSource.fromPfd(readPfd)
                }
            } catch (_: Throwable) {
                trySend(TranscriptUpdate.Failed(SpeechConfig.USER_SAFE_RECOGNITION_ERROR))
                close()
                return@callbackFlow
            }

            val collectJob = launch {
                try {
                    client.startRecognition(request)
                        .catch {
                            trySend(TranscriptUpdate.Failed(SpeechConfig.USER_SAFE_RECOGNITION_ERROR))
                        }
                        .collect { response ->
                            when (response) {
                                is SpeechRecognizerResponse.PartialTextResponse -> {
                                    trySend(TranscriptUpdate.Partial(textOf(response)))
                                }
                                is SpeechRecognizerResponse.FinalTextResponse -> {
                                    trySend(TranscriptUpdate.Final(textOf(response)))
                                }
                                is SpeechRecognizerResponse.CompletedResponse -> {
                                    trySend(TranscriptUpdate.Completed)
                                }
                                is SpeechRecognizerResponse.ErrorResponse -> {
                                    trySend(
                                        TranscriptUpdate.Failed(
                                            SpeechConfig.USER_SAFE_RECOGNITION_ERROR,
                                        ),
                                    )
                                }
                            }
                        }
                } catch (_: Throwable) {
                    trySend(TranscriptUpdate.Failed(SpeechConfig.USER_SAFE_RECOGNITION_ERROR))
                } finally {
                    recognitionActive.set(false)
                    channel.close()
                }
            }

            awaitClose {
                collectJob.cancel()
                recognitionActive.set(false)
                runBlocking {
                    try {
                        client.stopRecognition()
                    } catch (_: Throwable) {
                    }
                }
                try {
                    readPfd.close()
                } catch (_: Throwable) {
                }
            }
        }

    override suspend fun stop() {
        if (!recognitionActive.getAndSet(false)) return
        try {
            recognizer?.stopRecognition()
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        recognitionActive.set(false)
        runBlocking {
            try {
                recognizer?.stopRecognition()
            } catch (_: Throwable) {
            }
        }
        try {
            recognizer?.close()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    private fun progressPercentOf(status: DownloadStatus.DownloadProgress): Int? {
        // Alpha DownloadProgress field names vary — treat progress as optional.
        return try {
            val methods = status.javaClass.methods.filter { it.parameterCount == 0 }
            fun longProp(vararg names: String): Long? {
                for (name in names) {
                    val m = methods.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: methods.firstOrNull {
                            it.name.equals("get${name.replaceFirstChar { c -> c.uppercase() }}", ignoreCase = true)
                        }
                    val value = m?.invoke(status) as? Number
                    if (value != null) return value.toLong()
                }
                return null
            }
            val total = longProp("totalBytes", "totalSizeInBytes", "bytesToDownload") ?: return null
            val done = longProp("receivedBytes", "bytesDownloaded", "downloadedBytes") ?: return null
            if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun textOf(response: SpeechRecognizerResponse): String {
        return try {
            when (response) {
                is SpeechRecognizerResponse.PartialTextResponse -> response.text
                is SpeechRecognizerResponse.FinalTextResponse -> response.text
                else -> ""
            }
        } catch (_: Throwable) {
            ""
        }
    }
}
