package com.orato.app.speech

/**
 * Immediate accidental word repetitions (“il il”, “questo questo”).
 * Ignores filler tokens already counted by [FillerDetector].
 * Does not count pairs across sentence boundaries.
 */
object ImmediateRepetitionDetector {

    data class Result(
        val count: Int,
        val breakdown: Map<String, Int>,
    )

    fun detect(
        transcript: String,
        fillerTokenIndices: Set<Int> = emptySet(),
    ): Result {
        val tokens = TranscriptTokenizer.tokenize(transcript)
        val spans = TranscriptTokenizer.tokenizeWithSpans(transcript)
        return detect(tokens, spans, transcript, fillerTokenIndices)
    }

    fun detect(
        tokens: List<String>,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
        fillerTokenIndices: Set<Int>,
    ): Result {
        if (tokens.size < 2) return Result(0, emptyMap())
        val counts = linkedMapOf<String, Int>()
        var total = 0
        var i = 0
        while (i < tokens.size - 1) {
            val a = TranscriptTokenizer.normalizeToken(tokens[i])
            val b = TranscriptTokenizer.normalizeToken(tokens[i + 1])
            val eitherIsFiller = i in fillerTokenIndices || (i + 1) in fillerTokenIndices
            if (!eitherIsFiller &&
                a == b &&
                a.isNotEmpty() &&
                !crossesSentenceBoundary(i, spans, original)
            ) {
                counts[a] = (counts[a] ?: 0) + 1
                total++
                i += 2 // consume the pair
            } else {
                i++
            }
        }
        return Result(total, counts.toMap())
    }

    private fun crossesSentenceBoundary(
        firstIndex: Int,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Boolean {
        if (firstIndex + 1 >= spans.size) return false
        val from = spans[firstIndex].end
        val to = spans[firstIndex + 1].start
        if (from >= to) return false
        for (i in from until to) {
            val c = original[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') return true
        }
        return false
    }
}
