package cn.tonglu.app.ui.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceNoteTest {
    @Test fun 只去掉成对的加粗标记() {
        assertEquals(
            "题组含原版录音和逐题解析，保留 * 与 **",
            stripSourceNoteBold("题组含**原版录音**和**逐题解析**，保留 * 与 **"),
        )
        assertEquals("第三方整理", stripSourceNoteBold("第三方整理"))
    }
}
