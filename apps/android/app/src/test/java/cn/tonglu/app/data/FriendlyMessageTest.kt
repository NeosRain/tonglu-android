package cn.tonglu.app.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class FriendlyMessageTest {
    @Test fun `network library english text is not shown to users`() {
        assertEquals("网络连接中断，请稍后重试。本机课表和成绩仍可使用。", IOException("connection closed").toFriendlyMessage())
        assertEquals("网络连接中断，请稍后重试。本机课表和成绩仍可使用。", java.io.EOFException("unexpected end of stream").toFriendlyMessage())
    }

    @Test fun `app io errors keep their chinese explanation`() {
        assertEquals("连不上服务器，检查地址与网络", IOException("连不上服务器，检查地址与网络").toFriendlyMessage())
    }
}
