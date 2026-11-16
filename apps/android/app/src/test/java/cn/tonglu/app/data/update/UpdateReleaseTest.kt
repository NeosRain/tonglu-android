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

    @Test fun 镜像往返保留完整包与所有增量候选() {
        val delta = UpdatePatch(75, "b".repeat(64), "https://dl.useall.top/from-75.tmld", "c".repeat(64),
            8000000, listOf("https://www.newrain.top/from-75.tmld"))
        val manifest = release.copy(mirrors = listOf("https://www.newrain.top/76.apk"),
            patch = delta, patches = listOf(delta.copy(baseCode = 73)))
        assertEquals(manifest, parseUpdateRelease(manifest.toJson()))
    }

    @Test fun 镜像在前主源在后且去重() {
        val mirror = "https://www.newrain.top/76.apk"
        assertEquals(listOf(release.url, mirror), release.copy(mirrors = listOf(mirror, mirror, release.url)).downloadUrls)
        val delta = UpdatePatch(75, "b".repeat(64), "https://dl.useall.top/patch", "c".repeat(64), 12,
            listOf("https://www.newrain.top/patch", "https://www.newrain.top/patch"))
        assertEquals(listOf(delta.url, "https://www.newrain.top/patch"), delta.downloadUrls)
    }

    @Test fun 无镜像旧清单保持原行为() {
        val parsed = parseUpdateRelease(release.toJson())
        assertEquals(release, parsed)
        assertTrue(parsed.mirrors.isEmpty())
        assertEquals(listOf(release.url), parsed.downloadUrls)
        assertFalse(org.json.JSONObject(release.toJson()).has("mirrors"))
    }

    @Test fun 任意不可信镜像拒绝整个清单() {
        val invalid = listOf("http://www.newrain.top/a.apk", "https://evil.com/a.apk",
            "https://user@www.newrain.top/a.apk", "https://www.newrain.top:8443/a.apk",
            "https://www.newrain.top/a.apk?token=x", "https://www.newrain.top/a.apk#x")
        invalid.forEach { url ->
            val delta = UpdatePatch(75, "b".repeat(64), "https://dl.useall.top/patch", "c".repeat(64), 12,
                listOf(url))
            listOf(release.copy(mirrors = listOf(url)), release.copy(patch = delta),
                release.copy(patches = listOf(delta))).forEach { manifest ->
                try { parseUpdateRelease(manifest.toJson()); fail("应当拒绝 $url") }
                catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class) fun 镜像数量不能超过四个() {
        parseUpdateRelease(release.copy(mirrors = List(5) { "https://www.newrain.top/$it.apk" }).toJson())
    }
}
