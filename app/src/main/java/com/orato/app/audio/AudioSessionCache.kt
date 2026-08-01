package com.orato.app.audio

import java.io.File

/**
 * Manages temporary session WAV files under app cache storage.
 *
 * Path layout: `{cacheDir}/orato_sessions/{sessionId}.wav`
 *
 * Cleanup policy (see also [AudioMetricsConfig.CACHE_SUBDIR]):
 * 1. Never store in public storage; no storage permission required.
 * 2. Cancelled / failed recordings are deleted immediately via [deleteSessionFile].
 * 3. Successfully completed files remain for the next speech-to-text milestone.
 * 4. On each new session start, [prepareSessionDir] deletes other WAVs and any
 *    file older than [AudioMetricsConfig.CACHE_MAX_AGE_MS].
 */
object AudioSessionCache {

    fun sessionDir(cacheDir: File): File =
        File(cacheDir, AudioMetricsConfig.CACHE_SUBDIR)

    fun sessionFile(cacheDir: File, sessionId: String): File =
        File(sessionDir(cacheDir), "$sessionId.wav")

    /**
     * Ensures the cache directory exists and applies the cleanup policy.
     * @param retainSessionId optional session id whose file must not be deleted
     *        (normally null at start — previous sessions are cleared)
     */
    fun prepareSessionDir(cacheDir: File, retainSessionId: String? = null) {
        val dir = sessionDir(cacheDir)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        val now = System.currentTimeMillis()
        val retainName = retainSessionId?.let { "$it.wav" }
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".wav", ignoreCase = true)) return@forEach
            if (SessionWavRetention.isRetained(file)) return@forEach
            val tooOld = now - file.lastModified() > AudioMetricsConfig.CACHE_MAX_AGE_MS
            val isRetained = retainName != null && file.name == retainName
            if (!isRetained || tooOld) {
                // At session start retainSessionId is null → delete all prior WAVs.
                // Safety: always delete too-old files (unless actively leased).
                if (retainSessionId == null || tooOld || !isRetained) {
                    file.delete()
                }
            }
        }
    }

    fun deleteSessionFile(cacheDir: File, sessionId: String) {
        val file = sessionFile(cacheDir, sessionId)
        if (file.exists() && !SessionWavRetention.isRetained(file)) {
            file.delete()
        }
    }

    /** Deletes a file if it exists and is not leased; swallows failures. */
    fun deleteQuietly(file: File?) {
        try {
            if (file != null && !SessionWavRetention.isRetained(file)) {
                file.delete()
            }
        } catch (_: Exception) {
            // best-effort
        }
    }
}