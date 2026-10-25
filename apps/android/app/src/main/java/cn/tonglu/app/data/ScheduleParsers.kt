package cn.tonglu.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 教务课表解析器。参考 WakeUp 课程表的做法：
 * App 内 WebView 登录教务 → 拿到课表接口的 JSON 或课表页 HTML → 分类型解析成统一结构。
 *
 * 目前覆盖：
 *  1. 正方/智慧教务（jwapp、jwglxt 系）课表查询接口返回的 JSON（kbList[]）
 *  2. 通用 HTML 课表表格（含 rowspan/colspan 合并单元格、周次段、单双周）
 *  3. 强智教务导出的 HTML 课表（走通用表格解析，特征：单元格内带 {第N-M周} 字样）
 */
object ScheduleParsers {

    data class ParseResult(
        val courses: List<ParsedCourse>,
        val format: String,
        val message: String,
    )

    /** 自动识别 payload 是 JSON 还是 HTML */
    fun parseAuto(payload: String): ParseResult {
        val text = payload.trim()
        if (text.isEmpty()) return ParseResult(emptyList(), "unknown", "内容为空")

        if (text.startsWith("{") || text.startsWith("[")) {
            // 金智（Wisedu）kbapp 课表接口优先：本校教务实测就是这个格式
            val wisedu = runCatching { parseWiseduJson(text) }.getOrDefault(emptyList())
            if (wisedu.isNotEmpty()) {
                return ParseResult(wisedu, "金智教务 JSON", "已从课表接口解析 ${wisedu.size} 门课")
            }
            val courses = runCatching { parseZhengfangJson(text) }.getOrDefault(emptyList())
            if (courses.isNotEmpty()) {
                return ParseResult(courses, "正方教务 JSON", "已从课表接口 JSON 解析 ${courses.size} 门课")
            }
        }

        val courses = runCatching { parseHtmlTable(text) }.getOrDefault(emptyList())
        if (courses.isNotEmpty()) {
            return ParseResult(courses, "HTML 课表表格", "已从课表页 HTML 解析 ${courses.size} 门课")
        }

        return ParseResult(
            emptyList(),
            "unknown",
            "没识别出课表数据。请在教务系统里打开「课表查询」页面（能看到课表格子），再点抓取。",
        )
    }

    // ---------------------------------------------------------------- 金智 JSON

    /**
     * 金智教育（Wisedu）kbapp 课表接口（辽东学院实锤格式）：
     * POST /jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do
     * ```json
     * {"code":"0","datas":{"getMyScheduleDetail":{"arrangedList":[{
     *   "courseName":"遥感概论","dayOfWeek":2,"beginSection":3,"endSection":4,
     *   "weeksAndTeachers":"9-16周[讲授]/刘晔[主讲]","placeName":"3#楼_408(多媒体)",
     *   "campusName":"金山校区","courseSerialNo":"01","teachingTarget":"理学B2401，理学B2402"}]}}}
     * ```
     */
    fun parseWiseduJson(json: String): List<ParsedCourse> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arranged = findArray(root, "arrangedList") ?: return emptyList()

        val courses = ArrayList<ParsedCourse>(arranged.length())
        for (index in 0 until arranged.length()) {
            val row = arranged.optJSONObject(index) ?: continue
            val name = row.optString("courseName", "").trim()
            val weekday = row.optInt("dayOfWeek", 0)
            val startPeriod = row.optInt("beginSection", 0)
            val endPeriod = row.optInt("endSection", 0)
            if (name.isBlank() || weekday !in 1..7 || startPeriod < 1) continue

            val weeksAndTeachers = row.optString("weeksAndTeachers", "")
            val weeks = parseWeeks(weeksAndTeachers)
            // 教师："/刘晔[主讲]"段，可能有多个 "/张三[主讲]/李四[辅导]"
            val teacher = Regex("/([^/\\[\\]]+)\\[[^\\]]*\\]").findAll(weeksAndTeachers)
                .mapNotNull { it.groupValues[1].trim().takeIf(String::isNotBlank) }
                .distinct()
                .joinToString("、")
                .ifBlank { null }
            val location = row.optString("placeName", "").trim().ifBlank { null }
            val campus = row.optString("campusName", "").trim().ifBlank { null }
            val place = listOfNotNull(location, campus?.let { "($it)" }).joinToString("").ifBlank { null }

            courses += ParsedCourse(
                name = name,
                teacher = teacher,
                location = place,
                weekday = weekday,
                startPeriod = startPeriod,
                endPeriod = if (endPeriod >= startPeriod) endPeriod else startPeriod,
                startWeek = weeks.firstOrNull() ?: 1,
                endWeek = weeks.lastOrNull() ?: 30,
                weeks = weeks,
                color = colorFor(name),
                source = "jwapp",
            )
        }
        return mergeAdjacent(courses)
    }

    // ---------------------------------------------------------------- 正方 JSON

    /** 正方/智慧教务课表接口：{"kbList":[{"kcmc":"高等数学","xqj":1,"jcs":"1-2","zcd":"1-16周",...}]} */
    fun parseZhengfangJson(json: String): List<ParsedCourse> {
        val root = if (json.trimStart().startsWith("[")) JSONObject().put("kbList", JSONArray(json))
        else JSONObject(json)
        val list = findArray(root, "kbList")
            ?: findArray(root, "kb_list")
            ?: findArray(root, "list")
            ?: return emptyList()

        val courses = ArrayList<ParsedCourse>(list.length())
        for (index in 0 until list.length()) {
            val row = list.optJSONObject(index) ?: continue
            val name = row.firstString("kcmc", "kcmcDisplay", "courseName", "kcbm_mc", "name") ?: continue
            val weekday = row.optInt("xqj", 0).takeIf { it in 1..7 }
                ?: row.firstString("xqjmc", "xingqi", "weekday")?.let { parseWeekday(it) }
                ?: continue
            val (startPeriod, endPeriod) = parsePeriodRange(
                row.firstString("jcs", "jc", "jcsDisplay", "periods", "jcmc") ?: continue,
            )
            val weeks = parseWeeks(row.firstString("zcd", "zcdDisplay", "weeks", "zc") ?: "")
            courses += ParsedCourse(
                name = name.trim(),
                teacher = row.firstString("xm", "jsxm", "teacherName", "jsmc", "teacher")?.trim()?.ifBlank { null },
                location = row.firstString("cdmc", "jxdd", "classroom", "jxlmc", "location")?.trim()?.ifBlank { null },
                weekday = weekday,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                startWeek = weeks.firstOrNull() ?: 1,
                endWeek = weeks.lastOrNull() ?: 30,
                weeks = weeks,
                color = colorFor(name),
                source = "jwapp",
            )
        }
        return mergeAdjacent(courses)
    }

    private fun findArray(node: JSONObject, key: String): JSONArray? {
        node.optJSONArray(key)?.let { return it }
        for (name in node.keys()) {
            val value = node.opt(name)
            if (value is JSONObject) findArray(value, key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key, "")
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    // ---------------------------------------------------------------- HTML 表格

    /** 通用课表表格解析：支持 rowspan/colspan 合并单元格 */
    fun parseHtmlTable(html: String): List<ParsedCourse> {
        val document = Jsoup.parse(html)
        val tables = document.select("table")
        var best: List<ParsedCourse> = emptyList()
        for (table in tables) {
            val parsed = runCatching { parseTable(table) }.getOrDefault(emptyList())
            if (parsed.size > best.size) best = parsed
        }
        return best
    }

    private fun parseTable(table: Element): List<ParsedCourse> {
        val trs = table.select("tr")
        if (trs.size < 3) return emptyList()

        // 1) 展开合并单元格成矩阵（同一 Element 会出现在它 rowspan 覆盖的每一行）
        val grid = ArrayList<ArrayList<Element?>>()
        val cursor = HashMap<Int, Int>() // 列 -> 还需要向下占用的行数
        for (tr in trs) {
            val row = ArrayList<Element?>()
            var column = 0
            for (cell in tr.select("th,td")) {
                while (true) {
                    val remain = cursor[column] ?: 0
                    if (remain > 0) {
                        cursor[column] = remain - 1
                        row.add(null)
                        column++
                    } else break
                }
                val rowspan = cell.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                val colspan = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                repeat(colspan) {
                    row.add(cell)
                    if (rowspan > 1) cursor[column] = rowspan - 1
                    column++
                }
            }
            while (true) {
                val remain = cursor[column] ?: 0
                if (remain <= 0) break
                cursor[column] = remain - 1
                row.add(null)
                column++
            }
            grid.add(row)
        }

        // 2) 找到含星期信息的表头行，建立 列 -> 星期 映射
        var headerRow = -1
        var columnToWeekday = emptyMap<Int, Int>()
        for ((rowIndex, row) in grid.withIndex()) {
            val mapping = HashMap<Int, Int>()
            row.forEachIndexed { column, cell ->
                val weekday = cell?.let { parseWeekday(it.wholeText().trim()) } ?: 0
                if (weekday in 1..7) mapping[column] = weekday
            }
            if (mapping.size >= 3) {
                headerRow = rowIndex
                columnToWeekday = mapping
                break
            }
        }
        if (headerRow < 0) return emptyList()

        // 3) 行 -> 节次
        val rowPeriod = IntArray(grid.size) { 0 }
        var lastPeriod = 0
        for (rowIndex in grid.indices) {
            val label = grid[rowIndex].firstOrNull { it != null }?.wholeText()?.trim().orEmpty()
            val period = parsePeriodLabel(label)
            if (period > 0) lastPeriod = period
            rowPeriod[rowIndex] = if (period > 0) period else lastPeriod
        }

        // 4) 逐格提取课程
        val courses = ArrayList<ParsedCourse>()
        val seen = HashSet<Element>()
        for (rowIndex in (headerRow + 1) until grid.size) {
            grid[rowIndex].forEachIndexed { column, cell ->
                val weekday = columnToWeekday[column] ?: return@forEachIndexed
                if (cell == null || !seen.add(cell)) return@forEachIndexed
                val text = cell.wholeText().trim()
                if (text.isBlank()) return@forEachIndexed
                if (parseWeekday(text) in 1..7 && text.length <= 4) return@forEachIndexed

                // 该单元格覆盖的行范围 -> 起止节次
                var first = rowIndex
                var last = rowIndex
                for (other in (headerRow + 1) until grid.size) {
                    if (grid[other].getOrNull(column) === cell) {
                        if (other < first) first = other
                        if (other > last) last = other
                    }
                }
                val startPeriod = rowPeriod[first].takeIf { it > 0 } ?: return@forEachIndexed
                val endPeriod = rowPeriod[last].takeIf { it > 0 } ?: startPeriod

                val parsed = parseCellText(text, weekday, startPeriod, endPeriod)
                if (parsed != null) courses += parsed
            }
        }
        return mergeAdjacent(courses)
    }

    /** 单元格文本 → 课程。形如："高等数学{第1-16周}张老师A101" / 多行："高等数学\n1-16周\n张老师\nA101" */
    private fun parseCellText(text: String, weekday: Int, startPeriod: Int, endPeriod: Int): ParsedCourse? {
        val lines = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        var weeks: List<Int> = emptyList()
        val rest = ArrayList<String>()
        for (line in lines) {
            if (line.contains('周') && line.any { it.isDigit() } && line.length <= 24 && parseWeekday(line) == 0) {
                val parsed = parseWeeks(line)
                if (parsed.isNotEmpty()) {
                    weeks = parsed
                    continue
                }
            }
            rest += line
        }
        if (rest.isEmpty()) return null

        val name = rest.first().replace(Regex("[（(].*?[)）]"), "").trim()
        if (name.isBlank()) return null
        val details = rest.drop(1)

        var teacher: String? = null
        var location: String? = null
        val roomPattern = Regex("(楼|室|区|馆|场|教\\d|A\\d|B\\d|C\\d|D\\d|\\d+$)")
        details.forEach { line ->
            if (location == null && roomPattern.containsMatchIn(line) && line.length <= 20) location = line
            else if (teacher == null) teacher = line
        }
        // 只有一行且既像教室又像老师：拆成两段
        if (teacher == null && details.size == 1) {
            val parts = details.first().split(Regex("[\\s·,，/]+"), limit = 2)
            teacher = parts.getOrNull(0)?.ifBlank { null }
            if (location == null && parts.size > 1) location = parts[1]
        }

        return ParsedCourse(
            name = name,
            teacher = teacher,
            location = location,
            weekday = weekday,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            startWeek = weeks.firstOrNull() ?: 1,
            endWeek = weeks.lastOrNull() ?: 30,
            weeks = weeks,
            color = colorFor(name),
            source = "jwapp",
        )
    }

    // ---------------------------------------------------------------- 基础解析

    fun parseWeekday(text: String): Int {
        val value = text.trim()
        if (value.isEmpty()) return 0
        Regex("星期([一二三四五六日天])").find(value)?.let { return chineseDigit(it.groupValues[1]) }
        Regex("周([一二三四五六日天])").find(value)?.let { return chineseDigit(it.groupValues[1]) }
        Regex("^([1-7])$").find(value)?.let { return it.groupValues[1].toInt() }
        return 0
    }

    private fun chineseDigit(char: String): Int = when (char) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "日", "天" -> 7
        else -> 0
    }

    /** "1-2" / "第1-2节" / "3" / "三" → 节次范围 */
    fun parsePeriodRange(text: String): Pair<Int, Int> {
        val cleaned = text.trim().replace("第", "").replace("节", "")
        Regex("(\\d+)\\s*[-~至]\\s*(\\d+)").find(cleaned)?.let {
            val start = it.groupValues[1].toInt()
            val end = it.groupValues[2].toInt()
            if (start > 0 && end >= start) return start to end
        }
        Regex("\\d+").find(cleaned)?.let {
            val period = it.value.toInt()
            if (period > 0) return period to period
        }
        if (cleaned.isNotEmpty()) {
            val period = chineseNumber(cleaned)
            if (period > 0) return period to period
        }
        return 0 to 0
    }

    private fun parsePeriodLabel(label: String): Int {
        if (label.isEmpty() || label.length > 12) return 0
        if (label.contains('周')) return 0
        val (start, _) = parsePeriodRange(label)
        return start
    }

    private fun chineseNumber(text: String): Int = when (text) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "七" -> 7
        "八" -> 8; "九" -> 9; "十" -> 10; "十一" -> 11; "十二" -> 12
        else -> 0
    }

    /** "1-16周" / "1-16周(单)" / "第3,5,7周" / "1-8,10-16周" → 具体周次列表 */
    fun parseWeeks(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        val odd = text.contains("单")
        val even = text.contains("双")
        val weeks = sortedSetOf<Int>()
        Regex("(\\d+)\\s*[-~至]\\s*(\\d+)").findAll(text).forEach { match ->
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            if (start in 1..30 && end in start..30) {
                for (week in start..end) {
                    if (odd && week % 2 == 0) continue
                    if (even && week % 2 == 1) continue
                    weeks += week
                }
            }
        }
        if (weeks.isEmpty()) {
            Regex("\\d+").findAll(text).forEach { match ->
                val week = match.value.toInt()
                if (week in 1..30) {
                    if (odd && week % 2 == 0) return@forEach
                    if (even && week % 2 == 1) return@forEach
                    weeks += week
                }
            }
        }
        return weeks.toList()
    }

    /**
     * 同一门课连续节次/相邻周次在教务系统里常拆成多条（如"高数 1-2节"和"高数 3-4节"是同一门连堂课），
     * 这里把星期相同、周次相同、课程名+教室相同、节次相邻的合并成一条，课表格子才不会被切碎。
     */
    private fun mergeAdjacent(courses: List<ParsedCourse>): List<ParsedCourse> {
        val result = ArrayList<ParsedCourse>()
        courses.sortedWith(compareBy({ it.weekday }, { it.startPeriod })).forEach { course ->
            val previous = result.lastOrNull()
            if (previous != null &&
                previous.weekday == course.weekday &&
                previous.endPeriod + 1 == course.startPeriod &&
                previous.name == course.name &&
                previous.weeks == course.weeks
            ) {
                result[result.size - 1] = previous.copy(endPeriod = course.endPeriod)
            } else {
                result += course
            }
        }
        return result
    }

    private val palette = listOf("blue", "violet", "teal", "amber", "rose", "indigo", "green")

    /** 同一门课稳定同色 */
    fun colorFor(name: String): String = palette[(name.hashCode().let { if (it < 0) -it else it }) % palette.size]

    /** 内置示例课表（离线试用/自检用，和辽东学院作息一致） */
    fun demoPayload(): String = """
        {"kbList":[
          {"kcmc":"高等数学A","xqj":1,"jcs":"1-2","zcd":"1-16周","cdmc":"文萃楼402","xm":"李国强"},
          {"kcmc":"大学英语(2)","xqj":1,"jcs":"3-4","zcd":"1-16周","cdmc":"文萃楼205","xm":"王芳"},
          {"kcmc":"体育(羽毛球)","xqj":1,"jcs":"7-8","zcd":"2-16周(双)","cdmc":"体育馆","xm":"赵鹏"},
          {"kcmc":"数据结构","xqj":2,"jcs":"1-2","zcd":"1-16周","cdmc":"信息楼301","xm":"陈伟"},
          {"kcmc":"大学物理","xqj":2,"jcs":"5-6","zcd":"1-16周","cdmc":"实验楼B204","xm":"孙敏"},
          {"kcmc":"毛泽东思想和中国特色社会主义理论体系概论","xqj":3,"jcs":"3-4","zcd":"1-16周","cdmc":"文萃楼108","xm":"周立"},
          {"kcmc":"数据结构实验","xqj":3,"jcs":"7-8","zcd":"3-15周(单)","cdmc":"信息楼机房2","xm":"陈伟"},
          {"kcmc":"线性代数","xqj":4,"jcs":"1-2","zcd":"1-16周","cdmc":"文萃楼301","xm":"李国强"},
          {"kcmc":"计算机网络","xqj":4,"jcs":"5-6","zcd":"1-16周","cdmc":"信息楼402","xm":"吴迪"},
          {"kcmc":"大学英语(2)","xqj":5,"jcs":"3-4","zcd":"1-16周","cdmc":"文萃楼205","xm":"王芳"},
          {"kcmc":"形势与政策","xqj":5,"jcs":"9-10","zcd":"5-12周","cdmc":"文萃楼203","xm":"周立"}
        ]}
    """.trimIndent()
}
