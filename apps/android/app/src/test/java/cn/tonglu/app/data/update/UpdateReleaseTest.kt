package cn.tonglu.app.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val release = UpdateRelease(76, "0.6.62", "https://dl.useall.top/app-updates/76.apk", "a".repeat(64), 140000000, 24, "修复返回手势")
    @Test fun 清单往返保留字段() { assertEquals(release, parseUpdateRelease(release.toJson())) }
    @Test fun 增量清单往返保留基包校验() {
        val withPatch = release.copy(patch = UpdatePatch(75, "b".repeat(64),
            "https://dl.useall.top/app-updates/76/from-75.tmld", "c".repeat(64), 8000000))
        assertEquals(withPatch, parseUpdateRelease(withPatch.toJson()))
    }
    @Test(expected = IllegalArgumentException::class) fun 拒绝第三方增量地址() {
        parseUpdateRelease(release.copy(patch = UpdatePatch(75, "b".repeat(64),
            "https://example.org/patch", "c".repeat(64), 8000000)).toJson())
    }
    @Test(expected = IllegalArgumentException::class) fun 拒绝其他下载域名() { parseUpdateRelease(release.copy(url = "https://example.org/a.apk").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝明文下载() { parseUpdateRelease(release.copy(url = "http://dl.useall.top/a.apk").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝地址凭据() { parseUpdateRelease(release.copy(url = "https://" + "user:pass@" + "dl.useall.top/a.apk").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝错误哈希() { parseUpdateRelease(release.copy(sha256 = "xx").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝过大文件() { parseUpdateRelease(release.copy(size = 500000001).toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝负版本() { parseUpdateRelease(release.copy(versionCode = -1).toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝未知格式() { parseUpdateRelease(release.toJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
}
