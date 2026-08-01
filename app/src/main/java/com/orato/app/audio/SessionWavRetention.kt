package com.orato.app.audio

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Retains completed session WAV paths until Whisper and report aggregation finish.
 * Prevents premature deletion from reset/stop/new-session cleanup.
 */
object SessionWavRetention {
    private val retained = ConcurrentHashMap.newKeySet<String>()

    fun retain(file: File) {
        retained.add(file.absolutePath)
    }

    fun release(file: File?) {
        if (file == null) return
        retained.remove(file.absolutePath)
    }

    fun isRetained(file: File): Boolean = retained.contains(file.absolutePath)

    fun isRetainedPath(absolutePath: String): Boolean = retained.contains(absolutePath)

    /** Test-only. */
    fun clearAll() {
        retained.clear()
    }
}
