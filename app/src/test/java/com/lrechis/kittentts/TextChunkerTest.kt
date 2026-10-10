package com.lrechis.kittentts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("Hello there. How are you?"), TextChunker.chunk("  Hello there. How are you?  "))
    }

    @Test
    fun emptyTextGivesNoChunks() {
        assertEquals(emptyList<String>(), TextChunker.chunk("   \n  "))
    }

    @Test
    fun splitsAtSentenceBoundariesWithoutLosingText() {
        val sentences = (1..12).map { "Sentence number $it is here to fill the chunk with words." }
        val text = sentences.joinToString(" ")
        val chunks = TextChunker.chunk(text, maxChars = 120)

        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("chunk too long: ${chunk.length}", chunk.length <= 120)
            assertTrue("chunk must end at a sentence boundary: $chunk", chunk.endsWith("."))
        }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun lineBreaksAreBoundaries() {
        val chunks = TextChunker.chunk("first line\nsecond line\n\nthird line", maxChars = 12)
        assertEquals(listOf("first line", "second line", "third line"), chunks)
    }

    @Test
    fun overlongSentenceIsSplitAtWhitespace() {
        val words = (1..100).map { "word$it" }
        val text = words.joinToString(" ")
        val chunks = TextChunker.chunk(text, maxChars = 50)

        for (chunk in chunks) assertTrue(chunk.length <= 50)
        assertEquals(words, chunks.flatMap { it.split(' ') })
    }

    @Test
    fun singleGiantWordIsHardSplit() {
        val text = "a".repeat(700)
        val chunks = TextChunker.chunk(text, maxChars = 300)
        assertEquals(listOf(300, 300, 100), chunks.map { it.length })
    }
}
