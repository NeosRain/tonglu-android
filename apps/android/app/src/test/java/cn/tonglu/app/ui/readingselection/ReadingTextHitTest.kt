package cn.tonglu.app.ui.readingselection

import org.junit.Assert.*
import org.junit.Test
import cn.tonglu.app.ui.english.reading.ReaderSelection

class ReadingTextHitTest {
    private fun hit(text: String, at: Int, word: Boolean = true) = readingTextHit(text, 0, text.length, at, word)

    @Test fun repeatedWordUsesActualOffset() {
        val text = "We learn and learn every day."
        val result = hit(text, text.lastIndexOf("learn"))!!
        assertEquals(text.lastIndexOf("learn"), result.wordOffset)
    }
    @Test fun secondSentenceCarriesOnlyItsContext() {
        val text = "First sentence. Another sentence is here."
        val result = hit(text, text.indexOf("here"))!!
        assertEquals("Another sentence is here.", text.substring(result.start, result.end))
        assertEquals("Another sentence is ".length, result.wordOffset)
    }
    @Test fun abbreviationDoesNotSplitName() {
        val text = "Dr. Smith teaches English. We learn."
        val result = hit(text, text.indexOf("Smith"))!!
        assertEquals("Dr. Smith teaches English.", text.substring(result.start, result.end))
    }
    @Test fun decimalDoesNotSplitSentence() {
        val text = "It costs 3.14 dollars. Next one."
        val result = hit(text, text.indexOf("dollars"))!!
        assertEquals("It costs 3.14 dollars.", text.substring(result.start, result.end))
    }
    @Test fun contractionStaysOneWord() {
        val text = "We don't stop."
        assertEquals(3, hit(text, text.indexOf("don't") + 3)!!.wordOffset)
    }
    @Test fun whitespaceAndPunctuationAreNotWords() {
        assertNull(hit("We learn.", 2))
        assertNull(hit("We learn.", 8))
    }
    @Test fun longPressPunctuationCanSelectSentence() {
        assertEquals(9, hit("We learn.", 8, false)!!.end)
    }
    @Test fun originalGlobalOffsetsArePreserved() {
        val text = "# Title\n\nWe learn.\n\nOther."
        val result = readingTextHit(text, 9, 18, 3, true)!!
        assertEquals(9, result.start)
        assertEquals(18, result.end)
        assertEquals(3, result.wordOffset)
    }
    @Test fun surrogatePairBeforeWordDoesNotShiftUtf16Offset() {
        val text = "😀 We learn."
        assertEquals(text.indexOf("learn"), hit(text, text.indexOf("learn"))!!.wordOffset)
    }
    @Test fun formattedMarkdownIsNotPretendedPlainText() {
        assertFalse(supportsPreciseReading("A **bold** word."))
        assertNull(hit("A [link](https://example.com).", 3))
    }
    @Test fun multilineSentenceKeepsOriginalNewline() {
        val text = "We learn\ntogether every day."
        val result = hit(text, text.indexOf("together"))!!
        assertEquals(text, text.substring(result.start, result.end))
    }
    @Test fun noHitOutsideBlock() {
        assertNull(hit("A sentence.", -1))
        assertNull(hit("A sentence.", 99))
    }
    @Test fun hostSelectionOpensExactlyTheSecondRepeatedWord() {
        val text = "We learn and learn every day."
        val result = hit(text, text.lastIndexOf("learn"))!!
        val selection = ReaderSelection.of("article", "revision", text, result.start, result.end, result.wordOffset)
        assertEquals("learn", selection.wordText)
        assertEquals(text.lastIndexOf("learn"), selection.tokens.single { it.id == selection.wordTokenId }.start)
    }
    @Test fun structureSelectionDoesNotInventFocusedWord() {
        val text = "One sentence. Another sentence."
        val result = hit(text, text.indexOf("Another"), false)!!
        val selection = ReaderSelection.of("article", "revision", text, result.start, result.end, result.wordOffset)
        assertEquals("Another sentence.", selection.sentence.text)
        assertNull(selection.wordTokenId)
    }
}
