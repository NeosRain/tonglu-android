package cn.tonglu.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 【开发夹具】下载取消登记簿的纯 JVM 用例（TtsDownloadRegistry）。
 *
 * 覆盖的是取消规则本身：先记标记再掐连接、cancel 早于 register 也不漏、
 * 新一轮下载清标记、注销后不再误掐。真实 HTTP 取消及时性（阻塞 read 立刻抛）
 * 属于 OkHttp 行为 + 设备实测，这里不冒充已验。
 */
class TtsDownloadRegistryTest {

    @Test fun requestCancelRecordsFlagAndFiresAction() {
        val registry = TtsDownloadRegistry()
        var aborted = 0
        registry.register("m1") { aborted++ }
        registry.requestCancel("m1")
        assertEquals(1, aborted)
        assertTrue(registry.isCancelRequested("m1"))
        // 重复取消可重复调用动作；OkHttp.cancel本身幂等，标记仍保持。
        registry.requestCancel("m1")
        assertEquals(2, aborted)
        assertTrue(registry.isCancelRequested("m1"))
    }

    @Test fun cancelBeforeRegisterStillTrackedByFlag() {
        val registry = TtsDownloadRegistry()
        // 用户手快：连接还没建好就点取消——没有动作可触发，但标记必须留下，
        // 读取循环按它自查退出。
        registry.requestCancel("m2")
        assertTrue(registry.isCancelRequested("m2"))
        var aborted = 0
        registry.register("m2") { aborted++ }
        // 登记时必须立即兑现已发出的取消，不能等待响应或下一个读取块。
        assertEquals(1, aborted)
        registry.requestCancel("m2")
        assertEquals(2, aborted)
    }

    @Test fun clearCancelOnNewAttemptAndIsolationBetweenModels() {
        val registry = TtsDownloadRegistry()
        var abortedA = 0
        var abortedB = 0
        registry.register("a") { abortedA++ }
        registry.register("b") { abortedB++ }
        registry.requestCancel("a")
        assertTrue(registry.isCancelRequested("a"))
        assertFalse(registry.isCancelRequested("b")) // 单任务保护之外按 id 隔离，不误伤
        // 新一轮下载开始：清标记
        registry.clearCancel("a")
        assertFalse(registry.isCancelRequested("a"))
        assertEquals(1, abortedA) // clear 不再触发动作
        assertEquals(0, abortedB)
    }

    @Test fun unregisterStopsFutureCancelFromFiring() {
        val registry = TtsDownloadRegistry()
        var aborted = 0
        registry.register("m") { aborted++ }
        registry.unregister("m")
        registry.requestCancel("m")
        assertEquals(0, aborted) // 连接已结束，取消动作不能再挂在旧连接上
        assertTrue(registry.isCancelRequested("m")) // 标记语义仍在，等待 clear
    }
}
