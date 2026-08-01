package com.orato.app.speech

/**
 * Deterministic, boundary-aware Italian filler detection.
 * Operates on tokenizer tokens — never matches substrings inside normal words.
 *
 * Whisper may omit or normalize vocal fillers, so counts are estimates —
 * not complete acoustic measurements. Does not classify “tipo” / “allora”.
 */
object FillerDetector {

    val VOCAL_FILLERS: Set<String> = setOf(
        "eh", "ehm", "em", "uhm", "um", "mhm", "mmm",
    )

    val DISCOURSE_FILLERS: Set<String> = setOf(
        "cioè", "praticamente", "diciamo", "insomma",
    )

    val ALL_FILLERS: Set<String> = VOCAL_FILLERS + DISCOURSE_FILLERS

    data class Result(
        val fillerCount: Int,
        val breakdown: Map<String, Int>,
    )

    fun detect(transcript: String): Result =
        detect(TranscriptTokenizer.tokenize(transcript))

    fun detect(tokens: List<String>): Result {
        if (tokens.isEmpty()) {
            return Result(fillerCount = 0, breakdown = emptyMap())
        }
        val counts = linkedMapOf<String, Int>()
        var total = 0
        for (token in tokens) {
            val normalized = TranscriptTokenizer.normalizeToken(token)
            if (normalized in ALL_FILLERS) {
                counts[normalized] = (counts[normalized] ?: 0) + 1
                total++
            }
        }
        return Result(fillerCount = total, breakdown = counts.toMap())
    }
}
