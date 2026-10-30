package cn.tonglu.app.data.update

import org.json.JSONObject
import java.net.URI

internal const val UPDATE_MANIFEST = "https://dl.useall.top/app-updates/stable.json"

internal data class UpdatePatch(val baseCode: Long, val baseSha256: String, val url: String,
    val sha256: String, val size: Long)

internal data class UpdateRelease(val versionCode: Long, val versionName: String, val url: String,
    val sha256: String, val size: Long, val minSdk: Int, val notes: String, val patch: UpdatePatch? = null)

internal fun UpdateRelease.toJson(): String = JSONObject().put("schemaVersion", 1).put("versionCode", versionCode)
    .put("versionName", versionName).put("url", url).put("sha256", sha256).put("size", size)
    .put("minSdk", minSdk).put("notes", notes).also { json ->
        patch?.let { json.put("delta", JSONObject().put("baseCode", it.baseCode).put("baseSha256", it.baseSha256)
            .put("url", it.url).put("sha256", it.sha256).put("size", it.size)) }
    }.toString()

private fun trustedUpdateUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host == URI(UPDATE_MANIFEST).host && uri.userInfo == null && uri.port == -1 &&
        uri.fragment == null && uri.rawQuery == null
}.getOrDefault(false)

/** 清单是数据，不执行远程代码；下载源限定为清单所在的 HTTPS 域名。 */
internal fun parseUpdateRelease(json: String, expectedChannel: UpdateChannel? = null): UpdateRelease {
    val o = JSONObject(json)
    if (expectedChannel != null) require(o.optString("channel", "stable") == expectedChannel.key) { "更新清单通道不匹配" }
    require(o.getInt("schemaVersion") == 1) { "更新清单格式不支持" }
    val url = o.getString("url")
    require(trustedUpdateUrl(url)) { "更新下载地址不可信" }
    val patch = o.optJSONObject("delta")?.let { entry ->
        UpdatePatch(entry.getLong("baseCode"), entry.getString("baseSha256").lowercase(),
            entry.getString("url"), entry.getString("sha256").lowercase(), entry.getLong("size"))
    }
    return UpdateRelease(o.getLong("versionCode"), o.getString("versionName"), url,
        o.getString("sha256").lowercase(), o.getLong("size"), o.getInt("minSdk"), o.getString("notes"), patch).also {
        require(it.versionCode > 0 && it.versionName.isNotBlank() && it.versionName.length <= 40) { "更新版本无效" }
        require(it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size in 1..500_000_000) { "更新文件校验信息无效" }
        require(it.minSdk >= 24 && it.notes.length <= 4000) { "更新说明无效" }
        patch?.let { delta ->
            require(delta.baseCode in 1 until it.versionCode && delta.size in 1 until it.size &&
                delta.baseSha256.matches(Regex("[0-9a-f]{64}")) && delta.sha256.matches(Regex("[0-9a-f]{64}")) &&
                trustedUpdateUrl(delta.url)) { "增量包校验信息无效" }
        }
    }
}
