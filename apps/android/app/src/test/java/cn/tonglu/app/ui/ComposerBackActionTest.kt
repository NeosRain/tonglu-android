package cn.tonglu.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange

/**
 * 返回先驱动IME收起，IME下降沿自动折叠；初始尚未显示的IME不能误关面板。
 * 草稿在面板可见状态外保存，不以是否有草稿决定键盘/关层动作。
 */
class ComposerBackActionTest {
    @Test fun fullHeightAvoidsKeyboardOnce() { assertEquals(800, commentBottomInsetPx(2400, 2400, 800)) }
    @Test fun alreadyResizedDoesNotAddSecondKeyboardGap() { assertEquals(0, commentBottomInsetPx(2400, 1600, 800)) }
    @Test fun partiallyResizedOnlyAddsRemainingInset() { assertEquals(400, commentBottomInsetPx(2400, 2000, 800)) }
    @Test fun insetCannotExceedAvailableHeight() { assertEquals(300, commentBottomInsetPx(800, 300, 2000)) }
    @Test fun noInsetAddsNothing() { assertEquals(0, commentBottomInsetPx(2400, 2400, 0)) }
    @Test fun emojiSwitchDoesNotCloseEditorWhenKeyboardHides() {
        assertFalse(shouldCollapseComposer(true, false, emojiPanelOpen = true))
    }
    @Test fun topicNameIsSelectedForReplacement() {
        val value = insertCommentTopic(TextFieldValue("ab", TextRange(1)))
        assertEquals("a#话题#b", value.text)
        assertEquals(TextRange(2, 4), value.selection)
    }
    @Test fun overLimitTopicKeepsSelection() {
        val value = TextFieldValue("a".repeat(2000), TextRange(2000))
        assertEquals(value, insertCommentTopic(value))
    }
    @Test fun emojiReplacesSelectedText() {
        val next = insertCommentToken(TextFieldValue("你好世界", TextRange(2, 4)), "😀")
        assertEquals("你好😀", next.text)
        assertEquals(4, next.selection.end)
    }
    @Test fun insertsAtCursorNotAtEnd() {
        assertEquals("a#话题#b", insertCommentToken(TextFieldValue("ab", TextRange(1)), "#话题#").text)
    }
    @Test fun overLimitDoesNotSplitEmoji() {
        val value = TextFieldValue("a".repeat(1999), TextRange(1999))
        assertEquals(value, insertCommentToken(value, "😀"))
    }

    @Test fun initialKeyboardNotShown_doesNotCollapse() {
        assertFalse(shouldCollapseComposer(keyboardWasShown = false, imeVisible = false))
    }

    @Test fun visibleKeyboard_keepsEditor() {
        assertFalse(shouldCollapseComposer(keyboardWasShown = true, imeVisible = true))
    }

    @Test fun keyboardDown_returnsToBottomBar() {
        assertTrue(shouldCollapseComposer(keyboardWasShown = true, imeVisible = false))
    }

    @Test fun firstKeyboardFrame_doesNotCollapse() {
        assertFalse(shouldCollapseComposer(keyboardWasShown = false, imeVisible = true))
    }

    @Test
    fun imeUp_hidesKeyboard() {
        assertEquals(ComposerBackAction.HIDE_KEYBOARD, composerBackAction(imeVisible = true))
    }

    @Test
    fun imeDown_closesPanel() {
        assertEquals(ComposerBackAction.CLOSE_PANEL, composerBackAction(imeVisible = false))
    }

    /** 返回与IME下降沿组合必须关层，不能退回旧的“面板留下”规则。 */
    @Test
    fun draftIsIgnored() {
        assertEquals(ComposerBackAction.HIDE_KEYBOARD, composerBackAction(imeVisible = true))
        assertTrue(shouldCollapseComposer(keyboardWasShown = true, imeVisible = false))
        assertEquals(ComposerBackAction.CLOSE_PANEL, composerBackAction(imeVisible = false))
    }
}
