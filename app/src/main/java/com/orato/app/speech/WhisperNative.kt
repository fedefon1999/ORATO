package com.orato.app.speech

/**
 * JNI bindings for the vendored whisper.cpp v1.9.1 native library.
 * Package-private — UI/domain layers must use [WhisperCppTranscriber] instead.
 */
internal object WhisperNative {
    @Volatile
    var libraryLoaded: Boolean = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            // Prefer FP16-capable arm64 build when present; fall back to default.
            try {
                System.loadLibrary("orato_whisper_v8fp16")
            } catch (_: UnsatisfiedLinkError) {
                System.loadLibrary("orato_whisper")
            }
            libraryLoaded = true
        } catch (t: Throwable) {
            libraryLoaded = false
            loadError = t.javaClass.simpleName
        }
    }

    @JvmStatic external fun initializeContext(modelPath: String): Long

    @JvmStatic external fun transcribe(
        contextPtr: Long,
        samples: FloatArray,
        languageCode: String,
        numThreads: Int,
        translate: Boolean,
    ): Int

    @JvmStatic external fun requestCancellation()

    @JvmStatic external fun releaseContext(contextPtr: Long)

    @JvmStatic external fun getSegmentCount(contextPtr: Long): Int

    @JvmStatic external fun getSegmentText(contextPtr: Long, index: Int): String

    /** Centiseconds from whisper.cpp (t0 * 10 = ms). */
    @JvmStatic external fun getSegmentT0(contextPtr: Long, index: Int): Long

    @JvmStatic external fun getSegmentT1(contextPtr: Long, index: Int): Long

    @JvmStatic external fun getSystemInfo(): String
}
