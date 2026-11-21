package cn.tonglu.app.data.english.intensive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RemoteCetErrorTest {
    @Test fun sourceFailuresShowChineseRecoveryOptions() {
        val failures = listOf(
            SocketTimeoutException("Read timed out"),
            SocketException("Connection reset"),
            UnknownHostException("raw.githubusercontent.com"),
            ProtocolException("原卷来源返回 HTTP 404"),
        )
        for (failure in failures) {
            assertEquals(
                failure.javaClass.simpleName,
                "原卷来源在境外，当前网络连不上。可以稍后重试，或先用『我的文章与书籍』导入本地文件阅读。",
                remoteCetErrorMessage(RemoteCetSourceException(failure)),
            )
        }
    }
}
