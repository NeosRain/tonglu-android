package cn.tonglu.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S154：候选包与正式包同号时，装过候选包的设备必须还能收到正式版。 */
class UpdatePolicyTest {
    private fun release(code: Long, name: String) =
        UpdateRelease(code, name, "https://dl.useall.top/app-updates/$code/tonglu-$name.apk",
            "a".repeat(64), 76_827_799, 24, "说明")

    @Test fun 线上号更大就是更新() {
        assertTrue(isNewerRelease(release(905, "0.9.5"), 904, "0.9.4"))
    }

    @Test fun 同号正式版不重复提示() {
        assertFalse(isNewerRelease(release(904, "0.9.4"), 904, "0.9.4"))
    }

    @Test fun 本机是候选包时同号正式版算更新() {
        assertTrue(isNewerRelease(release(904, "0.9.4"), 904, "0.9.4-rc.3"))
    }

    @Test fun 本机是候选包时同号候选包不算更新() {
        assertFalse(isNewerRelease(release(904, "0.9.4-rc.3"), 904, "0.9.4-rc.3"))
    }

    @Test fun 线上号更小不降级() {
        assertFalse(isNewerRelease(release(903, "0.9.3"), 904, "0.9.4-rc.3"))
        assertFalse(isNewerRelease(release(903, "0.9.3"), 904, "0.9.4"))
    }

    @Test fun 老设备跨多个版本仍按号大小() {
        assertTrue(isNewerRelease(release(904, "0.9.4"), 800, "0.8.0"))
    }
}
