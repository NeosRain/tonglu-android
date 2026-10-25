package cn.tonglu.app.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 教务课表页面返回的校历和作息；不读取账号、成绩或课程内容。 */
object ScheduleMetadata {
    fun capture(context: Context, url: String, body: String): Boolean = runCatching {
        val store = ScheduleStore(context)
        val root = JSONObject(body)
        if (root.has("code") && root.optString("code") != "0") return@runCatching false
        val datas = root.opt("datas")
        when {
            url.contains("getTermWeeks") -> {
                val rows = (datas as? JSONObject)?.optJSONArray("getTermWeeks")
                    ?: datas as? JSONArray ?: return@runCatching false
                val first = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optInt("serialNumber") == 1 } ?: return@runCatching false
                val start = first.optString("startDate").take(10)
                if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(start)) return@runCatching false
                store.termStart = start
                true
            }
            url.contains("getMySectionList") -> {
                val rows = (datas as? JSONObject)?.optJSONArray("getMySectionList")
                    ?: datas as? JSONArray ?: return@runCatching false
                val slots = (0 until rows.length()).mapNotNull { index ->
                    val item = rows.optJSONObject(index) ?: return@mapNotNull null
                    if (!item.optBoolean("enable", true)) return@mapNotNull null
                    val period = item.optInt("code", item.optInt("sort"))
                    val start = PeriodTable.normalizeHhmm(item.optString("startTime").take(5))
                        ?: return@mapNotNull null
                    val end = PeriodTable.normalizeHhmm(item.optString("endTime").take(5))
                        ?: return@mapNotNull null
                    if (period !in 1..30 || PeriodTable.parseMinutes(end) <= PeriodTable.parseMinutes(start))
                        return@mapNotNull null
                    PeriodSlot(period, start, end)
                }.distinctBy { it.period }.sortedBy { it.period }
                // 不用残缺响应覆盖已有作息。
                if (slots.size < 2 || slots.first().period != 1 ||
                    slots.map { it.period } != (1..slots.last().period).toList()) return@runCatching false
                store.savePeriodTable(slots)
                store.periodTableSource = "教务自动导入"
                true
            }
            else -> false
        }
    }.getOrDefault(false)

    /** 老版本已经抓到响应但未解析成功时，升级后一次恢复，无需重新登录。 */
    fun recover(context: Context) {
        val prefs = context.getSharedPreferences("tonglu_schedule", Context.MODE_PRIVATE)
        if (prefs.getBoolean("metadata_recovered_v1", false)) return
        val file = File(context.getExternalFilesDir(null), "jw/capture.log")
        if (!file.exists()) return
        var pendingUrl: String? = null
        file.useLines { lines ->
            lines.forEach { line ->
                if (line.startsWith("===== ")) {
                    pendingUrl = line.substringAfter("url=", "").takeIf {
                        it.contains("getTermWeeks") || it.contains("getMySectionList")
                    }
                } else if (line.isNotBlank() && pendingUrl != null) {
                    capture(context, pendingUrl!!, line)
                    pendingUrl = null
                }
            }
        }
        prefs.edit().putBoolean("metadata_recovered_v1", true).apply()
    }
}
