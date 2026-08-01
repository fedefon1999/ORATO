package com.orato.app.speech

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Downloads / verifies / removes the offline Whisper model in app-private storage.
 * HTTPS only; renames from .part only after SHA-1 validation.
 */
class WhisperModelManager(
    context: Context,
    private val spec: WhisperModelSpec = WhisperModelSpec.GGML_BASE,
) {
    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, SpeechConfig.MODEL_DIR_RELATIVE)
    private val modelFile = File(modelsDir, spec.fileName)
    private val partFile = File(modelsDir, "${spec.fileName}.part")

    private val _state = MutableStateFlow<WhisperModelState>(WhisperModelState.Checking)
    val state: StateFlow<WhisperModelState> = _state.asStateFlow()

    private val cancelDownload = AtomicBoolean(false)

    fun modelPath(): File = modelFile

    fun isReady(): Boolean = _state.value is WhisperModelState.Ready && modelFile.isFile

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _state.value = WhisperModelState.Checking
        modelsDir.mkdirs()
        when {
            !modelFile.isFile -> {
                cleanupPart()
                _state.value = WhisperModelState.NotDownloaded
            }
            !verifyChecksum(modelFile) -> {
                modelFile.delete()
                cleanupPart()
                _state.value = WhisperModelState.Invalid
            }
            else -> _state.value = WhisperModelState.Ready
        }
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        cancelDownload.set(false)
        modelsDir.mkdirs()
        cleanupPart()

        val expected = spec.expectedSizeBytes
        if (expected != null && !hasEnoughStorage(expected)) {
            _state.value = WhisperModelState.Error(SpeechConfig.USER_SAFE_MODEL_ERROR)
            return@withContext
        }

        _state.value = WhisperModelState.Downloading(0)
        try {
            val url = URL(spec.downloadUrl)
            require(url.protocol.equals("https", ignoreCase = true)) { "HTTPS required" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                _state.value = WhisperModelState.Error(SpeechConfig.USER_SAFE_MODEL_ERROR)
                return@withContext
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
                ?: expected
                ?: -1L

            connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPct = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        if (cancelDownload.get()) {
                            cleanupPart()
                            _state.value = WhisperModelState.NotDownloaded
                            return@withContext
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = WhisperModelState.Downloading(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
            connection.disconnect()

            _state.value = WhisperModelState.Verifying
            if (!verifyChecksum(partFile)) {
                cleanupPart()
                _state.value = WhisperModelState.Invalid
                return@withContext
            }
            if (modelFile.exists()) modelFile.delete()
            if (!partFile.renameTo(modelFile)) {
                partFile.copyTo(modelFile, overwrite = true)
                partFile.delete()
            }
            _state.value = WhisperModelState.Ready
        } catch (_: Throwable) {
            cleanupPart()
            _state.value = WhisperModelState.Error(SpeechConfig.USER_SAFE_MODEL_ERROR)
        }
    }

    fun cancelDownload() {
        cancelDownload.set(true)
    }

    suspend fun removeModel() = withContext(Dispatchers.IO) {
        cancelDownload.set(true)
        modelFile.delete()
        cleanupPart()
        _state.value = WhisperModelState.NotDownloaded
    }

    fun verifyChecksum(file: File): Boolean {
        if (!file.isFile) return false
        expectedSizeOk(file) || return false
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
        return actual.equals(spec.expectedSha1, ignoreCase = true)
    }

    /** Exposed for unit tests. */
    fun sha1Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private fun expectedSizeOk(file: File): Boolean {
        val expected = spec.expectedSizeBytes ?: return true
        // Allow small CDN variance (±1 KiB) but reject clearly wrong sizes.
        return kotlin.math.abs(file.length() - expected) <= 1024L
    }

    private fun hasEnoughStorage(neededBytes: Long): Boolean {
        return try {
            val stat = StatFs(modelsDir.absolutePath.ifEmpty { appContext.filesDir.absolutePath })
            val available = stat.availableBytes
            available > neededBytes + (50L * 1024L * 1024L) // +50 MiB headroom
        } catch (_: Throwable) {
            true
        }
    }

    private fun cleanupPart() {
        if (partFile.exists()) partFile.delete()
    }
}
