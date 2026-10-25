package cn.tonglu.app.data

import org.json.JSONObject
import java.net.URI
import java.time.LocalDate

/** 用户主动选择的导出文件或官方页面；不执行文件内脚本。 */
data class ExternalSchedule(
    val courses: List<ParsedCourse>,
    val format: String,
    val periods: List<PeriodSlot> = emptyList(),
    val termStart: String? = null,
)

object ExternalScheduleParser {
    const val MAX_BYTES = 2 * 1024 * 1024

    fun read(input: java.io.InputStream): ExternalSchedule {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "文件最多支持 2 MB" }
            output.write(buffer, 0, count)
        }
        return parse(output.toString("UTF-8"))
    }

    fun officialUrl(raw: String): String {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { error("请填写学校官方教务的完整 HTTPS 地址") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "请填写学校官方教务的 HTTPS 地址，不要在地址里填写账号密码"
        }
        val sensitive = setOf("password", "passwd", "token", "access_token", "refresh_token", "ticket", "session", "sessionid", "secret", "code")
        require((uri.rawQuery.orEmpty() + "&" + uri.rawFragment.orEmpty().substringAfterLast('?')).split('&').none {
            java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() in sensitive
        }) { "请填写学校教务首页地址，不要使用含登录令牌的临时链接" }
        return uri.toASCIIString()
    }

    fun parse(payload: String): ExternalSchedule {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文件最多支持 2 MB" }
        val text = payload.removePrefix("\uFEFF").trim()
        require(text.isNotEmpty()) { "文件内容为空" }
        val root = if (text.startsWith("{")) JSONObject(text) else null
        if (root?.has("courses") == true) return shiguang(root)
        val result = ScheduleParsers.parseAuto(text)
        require(result.courses.isNotEmpty()) { "未识别到课程。可导入拾光单课表 JSON、正方/金智 JSON 或教务 HTML 课表。" }
        require(result.courses.size <= 2000) { "一次最多导入 2000 条课程" }
        result.courses.forEachIndexed { index, course -> validate(course, index) }
        return ExternalSchedule(result.courses.map { it.copy(source = "school_import") }, result.format)
    }

    private fun shiguang(root: JSONObject): ExternalSchedule {
        require(root.optJSONObject("comboSchedule")?.optJSONArray("publicSchedules")?.length().let { it == null || it == 0 }) {
            "这份课表使用分季节组合作息，暂不能完整转换。请导出固定作息课表。"
        }
        val rows = root.getJSONArray("courses")
        require(rows.length() in 1..2000) { "课表需包含 1–2000 条课程" }
        val courses = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(!row.optBoolean("isCustomTime", false)) { "第 ${index + 1} 条使用自定义时刻，请先转换为节次课表" }
            val weekArray = row.getJSONArray("weeks")
            val weeks = (0 until weekArray.length()).map { weekArray.get(it).toString().toIntOrNull()
                ?: error("第 ${index + 1} 条的周次必须是整数") }.distinct().sorted()
            require(weeks.isNotEmpty() && weeks.all { it in 1..60 }) { "第 ${index + 1} 条周次无效，请确认 1–60 周" }
            ParsedCourse(
                name = row.getString("name").trim(), teacher = optionalText(row, "teacher"),
                location = optionalText(row, "position"), weekday = integer(row, "day"),
                startPeriod = integer(row, "startSection"), endPeriod = integer(row, "endSection"),
                startWeek = weeks.first(), endWeek = weeks.last(), weeks = weeks,
                source = if (root.optBoolean("tongluExample", false)) "demo" else "school_import",
            ).also { validate(it, index) }
        }
        val slots = root.optJSONArray("timeSlots")?.let { array ->
            (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                PeriodSlot(integer(row, "number"), row.getString("startTime"), row.getString("endTime"))
            }.sortedBy { it.period }
        }.orEmpty()
        if (slots.isNotEmpty()) {
            require(slots.size <= 30 && slots.map { it.period } == (1..slots.size).toList()) { "作息节次需从 1 开始连续排列，最多 30 节" }
            slots.forEach { require(PeriodTable.isValidHhmm(it.start) && PeriodTable.isValidHhmm(it.end) &&
                PeriodTable.parseMinutes(it.end) > PeriodTable.parseMinutes(it.start)) { "第 ${it.period} 节作息时间无效" } }
            require(slots.zipWithNext().all { (a, b) -> PeriodTable.parseMinutes(a.end) <= PeriodTable.parseMinutes(b.start) }) { "作息时间相互重叠，请先核对" }
            require(courses.all { it.endPeriod <= slots.size }) { "课程超出导出的作息范围，请核对文件" }
        }
        val date = root.optJSONObject("config")?.optString("semesterStartDate")?.takeIf { it.isNotBlank() && it != "null" }
        if (date != null) require(runCatching { LocalDate.parse(date) }.isSuccess) { "开学日期无效，需为 YYYY-MM-DD" }
        return ExternalSchedule(courses, "拾光课表 JSON", slots, date)
    }

    private fun validate(course: ParsedCourse, index: Int) {
        require(course.name.isNotBlank() && course.name.length <= 200 && course.weekday in 1..7 &&
            course.startPeriod in 1..30 && course.endPeriod in course.startPeriod..30 &&
            course.startWeek in 1..60 && course.endWeek in course.startWeek..60) { "第 ${index + 1} 条课程的名称、星期、节次或周次无效" }
    }

    private fun integer(row: JSONObject, key: String): Int = row.get(key).toString().toIntOrNull()
        ?: error("$key 必须是整数")
    private fun optionalText(row: JSONObject, key: String): String? =
        if (row.isNull(key)) null else row.optString(key).trim().takeIf { it.isNotBlank() }
}
