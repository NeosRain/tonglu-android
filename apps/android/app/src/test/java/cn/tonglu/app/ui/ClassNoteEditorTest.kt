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
    @Test fun titleEditsPreserveBodyWhitespaceAndRichContent() {
        val original = NoteDocument("  alpha\n\nend  ", title = "Old title")
        val editor = NoteEditorSession(original)
        editor.title = "New title"
        assertEquals(original.copy(title = "New title"), editor.document())
        val restored = NoteEditorSession(editor.document())
        assertEquals("New title", restored.title)
        restored.state.selection = TextRange(2, 7)
        restored.state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        assertEquals("New title", restored.document().title)
        assertEquals(original.text, restored.document().text)
        assertEquals("Only title", NoteEditorSession(NoteDocument("", title = "Only title")).document().title)
    }
}
