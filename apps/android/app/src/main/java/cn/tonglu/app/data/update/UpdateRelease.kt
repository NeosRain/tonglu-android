package cn.tonglu.app.data.update

import org.json.JSONObject
import java.net.URI

internal const val UPDATE_MANIFEST = "https://dl.useall.top/app-updates/stable.json"

internal data class UpdateRelease(val versionCode: Long, val versionName: String, val url: String,
    val sha256: String, val size: Long, val minSdk: Int, val notes: String)

internal fun UpdateRelease.toJson(): String = JSONObject().put("schemaVersion", 1).put("versionCode", versionCode)
    .put("versionName", versionName).put("url", url).put("sha256", sha256).put("size", size)
    .put("minSdk", minSdk).put("notes", notes).toString()

/** 清单是数据，不执行远程代码；下载源限定为清单所在的 HTTPS 域名。 */
internal fun parseUpdateRelease(json: String, expectedChannel: UpdateChannel? = null): UpdateRelease {
    val o = JSONObject(json)
    if (expectedChannel != null) require(o.optString("channel", "stable") == expectedChannel.key) { "更新清单通道不匹配" }
    require(o.getInt("schemaVersion") == 1) { "更新清单格式不支持" }
    val url = o.getString("url")
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == URI(UPDATE_MANIFEST).host && uri.userInfo == null) { "更新下载地址不可信" }
    return UpdateRelease(o.getLong("versionCode"), o.getString("versionName"), url,
        o.getString("sha256").lowercase(), o.getLong("size"), o.getInt("minSdk"), o.getString("notes")).also {
        require(it.versionCode > 0 && it.versionName.isNotBlank() && it.versionName.length <= 40) { "更新版本无效" }
        require(it.sha256.matches(Regex("[0-9a-f]{64}")) && it.size in 1..500_000_000) { "更新文件校验信息无效" }
        require(it.minSdk >= 24 && it.notes.length <= 4000) { "更新说明无效" }
    }
}
