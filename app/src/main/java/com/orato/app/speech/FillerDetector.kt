package com.orato.app.speech

import java.util.Locale

/**
 * Deterministic Italian filler detection with explicit categories.
 *
 * Whisper may omit or normalize vocal fillers — counts are estimates
 * (“Riempitivi stimati”), not complete acoustic measurements.
 */
object FillerDetector {

    val VOCAL_FILLERS: Set<String> = setOf(
        "eh", "ehm", "em", "emm", "uhm", "um", "mhm", "mmm", "mm",
    )

    val CLEAR_DISCOURSE_FILLERS: Set<String> = setOf(
        "cioè", "praticamente", "diciamo", "insomma",
    )

    val CONTEXTUAL_MARKERS: Set<String> = setOf(
        "tipo", "allora", "ecco", "dunque",
    )

    /** Conservative repeated-letter vocal normalizations (finite map, not open regex). */
    private val VOCAL_VARIANT_NORMALIZE: Map<String, String> = mapOf(
        "ehmm" to "ehm",
        "ehmmm" to "ehm",
        "ehmmmm" to "ehm",
        "uhmm" to "uhm",
        "uhmmm" to "uhm",
        "mmmm" to "mmm",
        "mmmmm" to "mmm",
        "emm" to "emm",
        "emmm" to "emm",
    )

    private val ARTICLES_BEFORE_TIPO: Set<String> = setOf(
        "un", "uno", "una", "il", "lo", "la", "i", "gli", "le",
        "dei", "degli", "delle", "quel", "quello", "quella", "quei", "quegli",
        "questo", "questa", "questi", "queste", "ogni", "altro", "altra",
    )

    data class Result(
        val fillerCount: Int,
        val breakdown: Map<String, Int>,
        /** Indices of tokens classified as fillers (for repetition de-duplication). */
        val fillerTokenIndices: Set<Int>,
    )

    fun detect(transcript: String): Result {
        val tokens = TranscriptTokenizer.tokenize(transcript)
        val spans = TranscriptTokenizer.tokenizeWithSpans(transcript)
        return detect(tokens, spans, transcript)
    }

    fun detect(tokens: List<String>): Result =
        detect(tokens, emptyList(), tokens.joinToString(" "))

    fun detect(
        tokens: List<String>,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Result {
        if (tokens.isEmpty()) {
            return Result(0, emptyMap(), emptySet())
        }
        val counts = linkedMapOf<String, Int>()
        val fillerIndices = mutableSetOf<Int>()
        var total = 0

        for (i in tokens.indices) {
            val raw = TranscriptTokenizer.normalizeToken(tokens[i])
            val normalized = normalizeVocalVariant(raw)
            val counted: String? = when {
                normalized in VOCAL_FILLERS -> normalized
                normalized in CLEAR_DISCOURSE_FILLERS -> normalized
                normalized in CONTEXTUAL_MARKERS -> {
                    if (isContextualFiller(normalized, tokens, i, spans, original)) {
                        normalized
                    } else {
                        null
                    }
                }
                else -> null
            }
            if (counted != null) {
                counts[counted] = (counts[counted] ?: 0) + 1
                fillerIndices.add(i)
                total++
            }
        }
        return Result(total, counts.toMap(), fillerIndices.toSet())
    }

    fun normalizeVocalVariant(token: String): String {
        val lower = token.lowercase(Locale.ROOT)
        return VOCAL_VARIANT_NORMALIZE[lower] ?: lower
    }

    private fun isContextualFiller(
        marker: String,
        tokens: List<String>,
        index: Int,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Boolean {
        val prev = tokens.getOrNull(index - 1)?.let { TranscriptTokenizer.normalizeToken(it) }
        val next = tokens.getOrNull(index + 1)?.let { TranscriptTokenizer.normalizeToken(it) }
        val boundaryBefore = isBoundaryBefore(index, spans, original)
        val punctAfter = hasPunctuationAfter(index, spans, original)

        return when (marker) {
            "tipo" -> {
                // "un tipo di …" / "tipo di …" → noun construction
                if (next == "di") return false
                if (prev != null && prev in ARTICLES_BEFORE_TIPO) return false
                // Count discourse: start/after punctuation, or comma-framed ", tipo,"
                boundaryBefore || punctAfter
            }
            "allora" -> {
                // "da allora" → temporal
                if (prev == "da") return false
                boundaryBefore || punctAfter
            }
            "ecco" -> {
                // "Ecco, …" discourse; "Ecco il documento" without comma → skip
                if (punctAfter) return true
                if (next != null && next in ARTICLES_BEFORE_TIPO) return false
                false
            }
            "dunque" -> {
                // "Dunque, …" discourse. Bare "Dunque il risultato…" → skip.
                punctAfter || (index > 0 && boundaryBefore)
            }
            else -> false
        }
    }

    private fun isBoundaryBefore(
        index: Int,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Boolean {
        if (index <= 0) return true
        if (index >= spans.size) return index == 0
        val start = spans[index].start
        var i = start - 1
        while (i >= 0 && original[i].isWhitespace()) i--
        if (i < 0) return true
        val c = original[i]
        return c == ',' || c == ';' || c == ':' || c == '.' || c == '!' || c == '?' ||
            c == '…' || c == '-' || c == '—'
    }

    private fun hasPunctuationAfter(
        index: Int,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Boolean {
        if (index >= spans.size) return false
        val end = spans[index].end
        var i = end
        while (i < original.length && original[i].isWhitespace()) i++
        if (i >= original.length) return false
        val c = original[i]
        return c == ',' || c == ';' || c == ':' || c == '.' || c == '!' || c == '?' ||
            c == '…' || c == '-' || c == '—'
    }
}
