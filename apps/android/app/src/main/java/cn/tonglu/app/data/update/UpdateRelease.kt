package cn.tonglu.app.data.update

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

internal const val UPDATE_MANIFEST = "https://dl.useall.top/app-updates/stable.json"
internal val TRUSTED_UPDATE_HOSTS = setOf("dl.useall.top", "www.newrain.top")

internal data class UpdatePatch(val baseCode: Long, val baseSha256: String, val url: String,
    val sha256: String, val size: Long, val mirrors: List<String> = emptyList())

internal data class UpdateRelease(val versionCode: Long, val versionName: String, val url: String,
    val sha256: String, val size: Long, val minSdk: Int, val notes: String, val patch: UpdatePatch? = null,
    val patches: List<UpdatePatch> = emptyList(), val mirrors: List<String> = emptyList()) {
    val availablePatches: List<UpdatePatch> get() = (listOfNotNull(patch) + patches).distinct()
}

// 实测（2026-11-16，国内联通直连）：Cloudflare 主源约 5MB/s，阿里云镜像约 0.4MB/s。
// 主源能通时快得多，所以主源在前；主源失败后由下载器自动切到镜像，已下载部分继续续传。
internal val UpdateRelease.downloadUrls: List<String> get() = (listOf(url) + mirrors).distinct()
internal val UpdatePatch.downloadUrls: List<String> get() = (listOf(url) + mirrors).distinct()

private fun JSONObject.mirrors(): List<String> {
    if (!has("mirrors")) return emptyList()
    val array = getJSONArray("mirrors")
    require(array.length() <= 4) { "更新镜像数量超出范围" }
    return List(array.length()) { array.getString(it) }.also { mirrors ->
        require(mirrors.all(::trustedUpdateUrl)) { "更新镜像地址不可信" }
    }
}

/** Match the installed bytes before downloading; the version code alone is insufficient. */
internal fun UpdateRelease.patchForInstalled(code: Long, sha256: String): UpdatePatch? =
    availablePatches.filter { it.baseCode == code && it.baseSha256 == sha256 }.minByOrNull { it.size }

private fun UpdatePatch.toJson() = JSONObject().put("baseCode", baseCode).put("baseSha256", baseSha256)
    .put("url", url).put("sha256", sha256).put("size", size)
    .also { if (mirrors.isNotEmpty()) it.put("mirrors", JSONArray(mirrors)) }

private fun JSONObject.toPatch() = UpdatePatch(getLong("baseCode"), getString("baseSha256").lowercase(),
    getString("url"), getString("sha256").lowercase(), getLong("size"), mirrors())

internal fun UpdateRelease.toJson(): String = JSONObject().put("schemaVersion", 1).put("versionCode", versionCode)
    .put("versionName", versionName).put("url", url).put("sha256", sha256).put("size", size)
    .put("minSdk", minSdk).put("notes", notes).also { json ->
        if (mirrors.isNotEmpty()) json.put("mirrors", JSONArray(mirrors))
        patch?.let { json.put("delta", it.toJson()) }
        if (patches.isNotEmpty()) json.put("deltas", org.json.JSONArray().also { array -> patches.forEach { array.put(it.toJson()) } })
    }.toString()

private fun trustedUpdateUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host in TRUSTED_UPDATE_HOSTS && uri.userInfo == null && uri.port == -1 &&
        uri.fragment == null && uri.rawQuery == null
}.getOrDefault(false)

/** 清单是数据，不执行远程代码；镜像与主下载源采用同一可信域名白名单。 */
internal fun parseUpdateRelease(json: String, expectedChannel: UpdateChannel? = null): UpdateRelease {
    val o = JSONObject(json)
    if (expectedChannel != null) require(o.optString("channel", "stable") == expectedChannel.key) { "更新清单通道不匹配" }
    require(o.getInt("schemaVersion") == 1) { "更新清单格式不支持" }
    val url = o.getString("url")
    require(trustedUpdateUrl(url)) { "更新下载地址不可信" }
    val patch = o.optJSONObject("delta")?.toPatch()
    val patches = o.optJSONArray("deltas")?.let { array ->
        require(array.length() <= 8) { "增量候选数量超出范围" }
        List(array.length()) { array.getJSONObject(it).toPatch() }
    }.orEmpty()
    return UpdateRelease(o.getLong("versionCode"), o.getString("versionName"), url,
        o.getString("sha256").lowercase(), o.getLong("size"), o.getInt("minSdk"), o.getString("notes"), patch, patches, o.mirrors()).also {
        require(it.versionCode > 0 && it.versionName.isNotBlank() && it.versionName.length <= 40) { "更新版本无效" }
        require(it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size in 1..500_000_000) { "更新文件校验信息无效" }
        require(it.minSdk >= 24 && it.notes.length <= 4000) { "更新说明无效" }
        it.availablePatches.forEach { delta ->
            require(delta.baseCode in 1 until it.versionCode && delta.size in 1 until it.size &&
                delta.baseSha256.matches(Regex("[0-9a-f]{64}")) && delta.sha256.matches(Regex("[0-9a-f]{64}")) &&
                trustedUpdateUrl(delta.url)) { "增量包校验信息无效" }
        }
    }
}
