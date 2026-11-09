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
    @Test fun 跨版本候选往返并按安装包实际指纹选择() {
        val previous = UpdatePatch(75, "b".repeat(64), "https://dl.useall.top/from-75.tmld", "c".repeat(64), 8000000)
        val skipped = UpdatePatch(73, "d".repeat(64), "https://dl.useall.top/from-73.tmld", "e".repeat(64), 9000000)
        val manifest = release.copy(patch = previous, patches = listOf(previous, skipped))
        val parsed = parseUpdateRelease(manifest.toJson())
        assertEquals(manifest, parsed)
        assertEquals(previous, parsed.patchForInstalled(75, "b".repeat(64)))
        assertEquals(skipped, parsed.patchForInstalled(73, "d".repeat(64)))
        assertNull(parsed.patchForInstalled(73, "f".repeat(64)))
        assertNull(parsed.patchForInstalled(72, "d".repeat(64)))
    }
    @Test(expected = IllegalArgumentException::class) fun 次要候选也必须来自可信域名() {
        parseUpdateRelease(release.copy(patches = listOf(UpdatePatch(73, "b".repeat(64),
            "https://example.org/patch", "c".repeat(64), 8000000))).toJson())
    }
    @Test(expected = IllegalArgumentException::class) fun 拒绝其他下载域名() { parseUpdateRelease(release.copy(url = "https://example.org/a.apk").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝明文下载() { parseUpdateRelease(release.copy(url = "http://dl.useall.top/a.apk").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝地址凭据() { parseUpdateRelease(release.copy(url = "https://" + "user:pass@" + "dl.useall.top/a.apk").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝错误哈希() { parseUpdateRelease(release.copy(sha256 = "xx").toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝过大文件() { parseUpdateRelease(release.copy(size = 500000001).toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝负版本() { parseUpdateRelease(release.copy(versionCode = -1).toJson()) }
    @Test(expected = IllegalArgumentException::class) fun 拒绝未知格式() { parseUpdateRelease(release.toJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
}
