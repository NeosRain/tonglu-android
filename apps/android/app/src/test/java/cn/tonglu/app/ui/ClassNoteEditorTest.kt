package cn.tonglu.app.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class ClassNoteEditorTest {
    @Test fun untouchedNoteKeepsOriginalWhitespace() {
        val raw = "  缩进\n\n正文\t尾部  \n\n"
        assertEquals(NoteDocument(raw), NoteEditorSession(NoteDocument(raw)).document())
    }
    @Test fun formattingSurvivesSaveReloadAndUndoRedo() {
        val original = NoteDocument("alpha\nbeta")
        val editor = NoteEditorSession(original)
        editor.state.selection = TextRange(0, 5)
        editor.state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        val formatted = editor.document()
        assertEquals(original.text, formatted.text)
        assertNotNull(formatted.html)
        val restored = NoteEditorSession(formatted)
        assertTrue(restored.state.annotatedString.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 && it.end >= 5 })
        assertTrue(editor.state.history.undo())
        assertEquals(original, editor.document())
        assertTrue(editor.state.history.redo())
        assertEquals(formatted, editor.document())
    }
}
