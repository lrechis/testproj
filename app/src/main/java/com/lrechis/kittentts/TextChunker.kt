package com.lrechis.kittentts

/**
 * Splits text into pieces small enough for one synthesis call.
 *
 * Synthesising a whole essay in one call would make sherpa-onnx accumulate the complete
 * utterance in memory before returning it, so the text is cut at sentence boundaries into chunks
 * of at most [MAX_CHUNK_CHARS] characters; a single over-long sentence is split at whitespace.
 */
object TextChunker {
    const val MAX_CHUNK_CHARS = 300

    /** Whitespace that follows sentence punctuation, or any line break. */
    private val SENTENCE_BOUNDARY = Regex("(?<=[.!?…;:])\\s+|\\n+")

    fun chunk(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        val sentences = text.split(SENTENCE_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            for (piece in splitLongSentence(sentence, maxChars)) {
                if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) {
                    chunks.add(current.toString())
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }

    private fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val pieces = ArrayList<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            var cut = rest.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            pieces.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces.add(rest)
        return pieces
    }
}
