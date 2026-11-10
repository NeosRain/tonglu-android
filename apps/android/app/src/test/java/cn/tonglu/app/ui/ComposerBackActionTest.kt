package cn.tonglu.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange

/**
 * 仅明确返回后才等待IME下降沿折叠；输入法独立隐藏不代表退出编辑。
 * 草稿在面板可见状态外保存，不以是否有草稿决定键盘/关层动作。
 */
class ComposerBackActionTest {
    @Test fun fullHeightAvoidsKeyboardOnce() { assertEquals(800, commentBottomInsetPx(2400, 2400, 800)) }
    @Test fun alreadyResizedDoesNotAddSecondKeyboardGap() { assertEquals(0, commentBottomInsetPx(2400, 1600, 800)) }
    @Test fun partiallyResizedOnlyAddsRemainingInset() { assertEquals(400, commentBottomInsetPx(2400, 2000, 800)) }
    @Test fun insetCannotExceedAvailableHeight() { assertEquals(300, commentBottomInsetPx(800, 300, 2000)) }
    @Test fun noInsetAddsNothing() { assertEquals(0, commentBottomInsetPx(2400, 2400, 0)) }
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
    @Test fun overLimitDoesNotSplitEmoji() {
        val value = TextFieldValue("a".repeat(1999), TextRange(1999))
        assertEquals(value, insertCommentToken(value, "😀"))
    }





    @Test
    fun imeUp_hidesKeyboard() {
        assertEquals(ComposerBackAction.HIDE_KEYBOARD, composerBackAction(imeVisible = true))
    }

    @Test
    fun imeDown_closesPanel() {
        assertEquals(ComposerBackAction.CLOSE_PANEL, composerBackAction(imeVisible = false))
    }

    @Test fun keyboardChangesWithoutBackKeepEditor() {
        for (wasShown in listOf(false, true)) for (visible in listOf(false, true)) {
            assertFalse(shouldCollapseComposer(wasShown, visible, closeRequested = false))
        }
    }

    @Test fun explicitBackWaitsForKeyboardAndKeepsEmojiSwitchOpen() {
        assertFalse(shouldCollapseComposer(false, false, closeRequested = true))
        assertFalse(shouldCollapseComposer(true, true, closeRequested = true))
        assertTrue(shouldCollapseComposer(true, false, closeRequested = true))
        assertFalse(shouldCollapseComposer(true, false, closeRequested = true, emojiPanelOpen = true))
    }
}
