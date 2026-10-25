package cn.tonglu.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 教务成绩解析器。
 *
 * 与课表解析器（[ScheduleParsers]）同样的思路：不写死某一家的字段名，而是
 * **「候选字段名 + 值特征」双重识别** —— 教务改版、换系统、字段中英混用都能扛一扛。
 *
 * 之所以敢这么写：本校（金智 jwapp）成绩接口的响应字段还没实测过
 * （首页 scores.do 只查当前学期，开学时为空数组），所以第一版必须做成
 * 「认不出来让你看见」，而不是「认错了悄悄存下来」。
 * 预览页会把识别到的字段映射列出来，用户核对后再保存。
 */
object ScoreParsers {

    data class ParseResult(
        val records: List<ScoreRecord>,
        val format: String,
        val message: String,
        /** 识别到的字段映射（"课程名 ← courseName"），给用户核对用 */
        val mapping: List<String> = emptyList(),
        /** 接口自报的总条数（-1 = 没说）。分页时它比本页条数大，要提示用户翻页 */
        val total: Int = -1,
    )

    /** 粗筛：避免对每个静态资源都跑一遍解析器。宁可宽一点，反正后面还要看能不能解析出东西。 */
    fun looksLikeScores(body: String): Boolean {
        if (body.length > 4_000_000) return false
        // 课表接口排除掉：两边都带 courseName，靠这几个特征区分
        if (body.contains("arrangedList") || body.contains("weeksAndTeachers")) return false
        if (body.contains("dayOfWeek") && body.contains("beginSection")) return false
        return SCORE_HINTS.any { body.contains(it, ignoreCase = true) }
    }

    fun parseAuto(payload: String): ParseResult {
        val text = payload.trim()
        if (text.isEmpty()) return ParseResult(emptyList(), "unknown", "内容为空")

        if (text.startsWith("{") || text.startsWith("[")) {
            val json = runCatching { parseJson(text) }.getOrNull()
            if (json != null && json.records.isNotEmpty()) return json
        }
        val html = runCatching { parseHtml(text) }.getOrNull()
        if (html != null && html.records.isNotEmpty()) return html

        return ParseResult(
            emptyList(),
            "unknown",
            "没识别出成绩数据。请在教务系统里打开「成绩查询」页面（能看到成绩列表），再点抓取。",
        )
    }

    // ------------------------------------------------------------------ JSON

    fun parseJson(json: String): ParseResult {
        val root: Any = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)
        val arrays = mutableListOf<Pair<JSONArray, JSONObject?>>()
        collectArrays(root, null, arrays)
        if (arrays.isEmpty()) return ParseResult(emptyList(), "JSON", "JSON 里没有数组")

        val picked = arrays
            .map { it to arrayScore(it.first) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= MIN_ARRAY_SCORE }
            ?: return ParseResult(emptyList(), "JSON", "JSON 里没有找到像成绩表的数组")

        return mapRows(picked.first.first, picked.first.second)
    }

    /**
     * 递归收集「元素是对象的数组」，同时记下它挂在哪个对象下 ——
     * 教务接口的分页信息（`totalSize` / `pageSize`）就在 rows 的父对象里，
     * 不知道总数的话，用户会以为 10 条就是全部。
     */
    private fun collectArrays(
        node: Any?,
        parent: JSONObject?,
        out: MutableList<Pair<JSONArray, JSONObject?>>,
        depth: Int = 0,
    ) {
        if (node == null || depth > 6) return
        when (node) {
            is JSONArray -> {
                if (node.length() > 0 && node.optJSONObject(0) != null) out += node to parent
                for (i in 0 until node.length()) collectArrays(node.opt(i), parent, out, depth + 1)
            }
            is JSONObject -> node.keys().forEach { key -> collectArrays(node.opt(key), node, out, depth + 1) }
        }
    }

    /** 给一个数组打「像不像成绩表」的分 */
    private fun arrayScore(array: JSONArray): Int {
        if (array.length() == 0) return -1
        val row = array.optJSONObject(0) ?: return -1
        val keys = row.keys().asSequence().toList().map { it.lowercase().replace("_", "") }
        var score = 0
        if (keys.any { k -> NAME_KEYS.any { k == it || k.contains(it) } }) score += 3
        if (keys.any { k -> SCORE_KEYS.any { k == it || k.contains(it) } }) score += 3
        if (keys.any { k -> PASS_KEYS.any { k == it || k.contains(it) } }) score += 2
        if (keys.any { k -> TERM_KEYS.any { k == it || k.contains(it) } }) score += 2
        if (keys.any { k -> CREDIT_KEYS.any { k == it || k.contains(it) } }) score += 1
        if (row.keys().asSequence().any { k -> ScoreBoard.parseScoreValue(row.optString(k, "")) != null }) score += 1
        return score
    }

    private fun mapRows(array: JSONArray, parent: JSONObject? = null): ParseResult {
        val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        if (rows.isEmpty()) return ParseResult(emptyList(), "JSON", "数组里没有对象行")
        val keys = rows.flatMap { row -> row.keys().asSequence().toList() }.distinct()

        val nameKey = pickKey(rows, keys, NAME_KEYS)
            ?: return ParseResult(emptyList(), "JSON", "没认出哪一列是课程名")
        val scoreKey = pickKey(rows, keys, SCORE_KEYS, ::looksLikeScoreValue)
        val passKey = pickKey(rows, keys, PASS_KEYS, ::looksLikePassValue)
        val creditKey = pickKey(rows, keys, CREDIT_KEYS, ::looksLikeSmallNumber)
        val pointKey = pickKey(rows, keys, POINT_KEYS, ::looksLikeSmallNumber)
        val termKey = pickKey(rows, keys, TERM_KEYS, ::looksLikeTermValue)
        val examKey = pickKey(rows, keys, EXAM_KEYS)
        val typeKey = pickKey(rows, keys, TYPE_KEYS)

        val records = rows.mapNotNull { row ->
            val name = row.optString(nameKey, "").trim()
            if (name.isEmpty()) return@mapNotNull null
            val scoreText = scoreKey?.let { row.optString(it, "").trim() }.orEmpty()
            val value = ScoreBoard.parseScoreValue(scoreText)
            val passText = passKey?.let { row.optString(it, "").trim() }.orEmpty()
            ScoreRecord(
                courseName = name,
                scoreText = scoreText,
                scoreValue = value,
                credit = creditKey?.let { toNumber(row.optString(it, "")) },
                gradePoint = pointKey?.let { toNumber(row.optString(it, "")) },
                termCode = normalizeTerm(termKey?.let { row.optString(it, "") }.orEmpty()),
                examType = examKey?.let { row.optString(it, "").trim() }?.takeIf { it.isNotEmpty() },
                courseType = typeKey?.let { row.optString(it, "").trim() }?.takeIf { it.isNotEmpty() },
                passed = if (passText.isNotEmpty()) judgePassField(passText, value)
                else ScoreBoard.judgePass(scoreText, value),
            )
        }
        if (records.isEmpty()) return ParseResult(emptyList(), "JSON", "行里没有可用的课程名")
        // 关键校验：只认出课程名、认不出成绩列时，多半是「课程列表 / 菜单」而不是成绩。
        // 宁可返回空让用户看见，也不要拿一份看错的数据糊弄过去。
        if (scoreKey == null && passKey == null) {
            return ParseResult(
                emptyList(), "JSON",
                "认出了课程名（$nameKey），但没有成绩列 —— 这一份可能不是成绩表",
            )
        }
        val evidence = records.count { it.scoreText.isNotBlank() } + records.count { passKey != null }
        if (evidence == 0) {
            return ParseResult(emptyList(), "JSON", "成绩列这一栏是空的，先确认教务页面里能看到分数")
        }

        val mapping = buildList {
            add("课程名 ← $nameKey")
            scoreKey?.let { add("成绩 ← $it") }
            passKey?.let { add("及格 ← $it") }
            creditKey?.let { add("学分 ← $it") }
            termKey?.let { add("学年学期 ← $it") }
            examKey?.let { add("考试类型 ← $it") }
        }
        val total = parent?.optInt("totalSize", -1) ?: -1
        val message = if (total > records.size) {
            "已识别本页 ${records.size} 条（接口说一共 $total 条）"
        } else {
            "已从接口 JSON 识别 ${records.size} 条成绩记录"
        }
        return ParseResult(records, "成绩 JSON", message, mapping, total)
    }

    /**
     * 选列：先按候选字段名精确匹配，再退一步做包含匹配，最后才用「值长什么样」来猜。
     * 名字匹配优先级最高 —— 值特征只是兜底，容易把学分当成分数。
     */
    private fun pickKey(
        rows: List<JSONObject>,
        keys: List<String>,
        candidates: List<String>,
        valueTest: ((String) -> Boolean)? = null,
    ): String? {
        val normalized = keys.associateWith { it.lowercase().replace("_", "") }
        val exact = normalized.entries.filter { (_, k) -> candidates.any { it == k } }
        // 教务同时给“代码列”和“展示列”（CXCKDM=01 / CXCKDM_DISPLAY=重修）：
        // 展示列才是人能看懂的，优先它 —— 否则“考试类型”会显示成一串代码
        exact.firstOrNull { (_, k) -> k.endsWith("display") }?.let { return it.key }
        exact.firstOrNull()?.let { return it.key }
        normalized.entries.firstOrNull { (_, k) -> k.length >= 3 && candidates.any { k.contains(it) } }?.let { return it.key }
        if (valueTest != null) {
            keys.firstOrNull { key ->
                val values = rows.mapNotNull { row ->
                    row.optString(key, "").trim().takeIf { it.isNotEmpty() }
                }
                values.isNotEmpty() && values.count(valueTest) >= values.size * 0.7
            }?.let { return it }
        }
        return null
    }

    private fun looksLikeScoreValue(value: String): Boolean =
        ScoreBoard.parseScoreValue(value)?.let { it in 0.0..150.0 } == true

    private fun looksLikeSmallNumber(value: String): Boolean =
        toNumber(value)?.let { it in 0.0..30.0 } == true

    private fun looksLikePassValue(value: String): Boolean {
        val v = value.trim().lowercase()
        return v in listOf("是", "否", "true", "false", "通过", "未通过", "及格", "不及格", "已通过", "未及格")
    }

    private fun looksLikeTermValue(value: String): Boolean =
        ScoreBoard.TERM_FULL.containsMatchIn(value) || ScoreBoard.TERM_PAIR.containsMatchIn(value)

    /**
     * 及格列的取值可能是「是 / 否」「true / false」「通过 / 未通过」，也可能直接是分数。
     * 直接丢给 [ScoreBoard.judgePass] 会把「是」判成未通过（它不在及格词表里），所以先认一遍布尔写法。
     */
    private fun judgePassField(text: String, scoreValue: Double?): Boolean = when (text.trim().lowercase()) {
        "是", "true", "1", "y", "yes", "通过", "已通过", "及格" -> true
        "否", "false", "0", "n", "no", "未通过", "不及格" -> false
        else -> ScoreBoard.judgePass(text, scoreValue)
    }

    private fun toNumber(raw: String): Double? =
        raw.trim().replace("分", "").toDoubleOrNull()

    /** "2023-2024-1" / "2023-2024学年第一学期" / "2023-2024" 都归一到编码 */
    private fun normalizeTerm(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        ScoreBoard.TERM_FULL.find(text)?.let { match ->
            return "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
        }
        val pair = ScoreBoard.TERM_PAIR.find(text) ?: return null
        val season = when {
            text.contains("春") || text.contains("第二") || text.contains("2学") -> 2
            text.contains("秋") || text.contains("第一") || text.contains("1学") -> 1
            else -> return null
        }
        return "${pair.groupValues[1]}-${pair.groupValues[2]}-$season"
    }

    // ------------------------------------------------------------------ HTML

    /** 成绩页也可能是表格：挑行数最多的表，用表头文字认列 */
    fun parseHtml(html: String): ParseResult {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").maxByOrNull { it.select("tr").size }
            ?: return ParseResult(emptyList(), "HTML", "页面里没有表格")
        val trs = table.select("tr")
        if (trs.size < 2) return ParseResult(emptyList(), "HTML", "表格里没有数据行")

        val headers = trs.first()!!.select("th,td").map { it.text().trim() }
        val nameCol = headers.indexOfFirst { head -> NAME_KEYS.any { head.contains(it) } }
        if (nameCol < 0) return ParseResult(emptyList(), "HTML", "表头里没找到「课程名称」列")
        val scoreCol = headers.indexOfFirst { head -> SCORE_KEYS.any { head.contains(it) } }
        val creditCol = headers.indexOfFirst { head -> CREDIT_KEYS.any { head.contains(it) } }
        val passCol = headers.indexOfFirst { head -> PASS_KEYS.any { head.contains(it) } }
        val termCol = headers.indexOfFirst { head -> TERM_KEYS.any { head.contains(it) } }
        val examCol = headers.indexOfFirst { head -> EXAM_KEYS.any { head.contains(it) } }

        val records = trs.drop(1).mapNotNull { tr ->
            val cells = tr.select("td,th").map { it.text().trim() }
            val name = cells.getOrNull(nameCol)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (name.contains("课程") && name.contains("名称")) return@mapNotNull null
            val scoreText = scoreCol.takeIf { it >= 0 }?.let { cells.getOrNull(it) }.orEmpty()
            val value = ScoreBoard.parseScoreValue(scoreText)
            val passText = passCol.takeIf { it >= 0 }?.let { cells.getOrNull(it) }.orEmpty()
            ScoreRecord(
                courseName = name,
                scoreText = scoreText,
                scoreValue = value,
                credit = creditCol.takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.let { toNumber(it) },
                termCode = normalizeTerm(termCol.takeIf { it >= 0 }?.let { cells.getOrNull(it) }.orEmpty()),
                examType = examCol.takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() },
                passed = if (passText.isNotEmpty()) judgePassField(passText, value)
                else ScoreBoard.judgePass(scoreText, value),
            )
        }
        if (records.isEmpty()) return ParseResult(emptyList(), "HTML", "表格里没有成绩行")
        if (scoreCol < 0 && passCol < 0) {
            return ParseResult(emptyList(), "HTML", "表头里没有「成绩」列 —— 这张表可能不是成绩表")
        }
        return ParseResult(records, "HTML 成绩表格", "已从成绩页表格识别 ${records.size} 条成绩记录")
    }

    // ------------------------------------------------------------------ 示例

    /**
     * 内置示例：覆盖用户描述的那种情况 —— 一门课挂了三次、第四次才过，
     * 旧记录全都还在。用来在没有真实数据时也能验收「到底过没过」的结论。
     */
    fun demoPayload(): String = """
    {"code":"0","msg":null,"datas":[
      {"courseName":"高等数学（上）","zpcj":"41","xf":"5","xnxqdm":"2023-2024-1","ksxz":"正常考试","kcxz":"必修"},
      {"courseName":"高等数学（上）","zpcj":"52","xf":"5","xnxqdm":"2023-2024-2","ksxz":"重修","kcxz":"必修"},
      {"courseName":"高等数学（上）","zpcj":"45","xf":"5","xnxqdm":"2024-2025-1","ksxz":"重修","kcxz":"必修"},
      {"courseName":"高等数学（上）","zpcj":"78","xf":"5","xnxqdm":"2024-2025-2","ksxz":"重修","kcxz":"必修"},
      {"courseName":"大学英语（一）","zpcj":"88","xf":"4","xnxqdm":"2023-2024-1","ksxz":"正常考试","kcxz":"必修"},
      {"courseName":"程序设计基础","zpcj":"34","xf":"3","xnxqdm":"2023-2024-1","ksxz":"正常考试","kcxz":"必修"},
      {"courseName":"程序设计基础","zpcj":"51","xf":"3","xnxqdm":"2023-2024-2","ksxz":"重修","kcxz":"必修"},
      {"courseName":"大学物理","zpcj":"良好","xf":"3","xnxqdm":"2023-2024-2","ksxz":"正常考试","kcxz":"必修"},
      {"courseName":"思想道德与法治","zpcj":"缺考","xf":"3","xnxqdm":"2024-2025-1","ksxz":"正常考试","kcxz":"必修"},
      {"courseName":"体育（一）","zpcj":"92","xf":"1","xnxqdm":"2023-2024-1","ksxz":"正常考试","kcxz":"必修"}
    ]}
    """.trimIndent()

    // ------------------------------------------------------------------ 词表

    private const val MIN_ARRAY_SCORE = 5

    private val SCORE_HINTS = listOf(
        "成绩", "分数", "及格", "重修", "补考", "学分", "绩点",
        "zpcj", "kscj", "zscj", "score", "grade", "gradePoint",
        // 辽东学院（金智 cjcx）实锤字段名 —— 没有这几个，整份响应会被当静态资源丢掉
        "xskcm", "xszcjmc", "zcj", "sfjg", "xnxqdm", "cxckdm",
    )

    /** 课程名 */
    private val NAME_KEYS = listOf(
        // 我校真实列：XSKCM（学生课程名，含（）、空格等完整写法）
        "xskcm", "kcm", "coursename", "kcmc", "kcname", "course", "coursetitle", "name", "kcbm",
        "课程名称", "课程名", "科目",
    )

    /** 成绩 */
    private val SCORE_KEYS = listOf(
        // XSZCJMC = 教务页面上那一列显示成绩（“47”/“及格”/“优”），比数值列 ZCJ 更贴近原文
        "xszcjmc", "zcj", "zpcj", "score", "cj", "kscj", "zscj", "grade", "totalscore", "scorevalue", "scoretext", "df",
        "成绩", "分数", "总评", "总评成绩", "最终成绩",
    )

    /** 是否及格 */
    private val PASS_KEYS = listOf(
        "ispass", "pass", "sfjg", "jg", "passflag", "jgflag",
        "是否及格", "及格", "是否通过",
    )

    /** 学分 */
    private val CREDIT_KEYS = listOf(
        "credit", "xf", "creditvalue", "creditvalue2", "学分",
    )

    /** 绩点 */
    private val POINT_KEYS = listOf(
        "gradepoint", "jd", "gpa", "point", "绩点",
    )

    /** 学年学期 */
    private val TERM_KEYS = listOf(
        "termcode", "xnxqdm", "xnxqmc", "term", "xnxq", "termname", "xnmxqm", "xnxqid", "yearterm",
        "学年学期", "学期", "学年",
    )

    /** 考试类型（正常考试 / 重修 / 补考） */
    private val EXAM_KEYS = listOf(
        // 我校用 CXCKDM_DISPLAY 记“初修 / 重修 / 补考”—— 这正是判断挂科重修次数的依据
        "cxckdmdisplay", "cxckdm", "kslbdmdisplay", "kslbdm",
        "examtype", "ksxz", "examname", "ksmc", "ksxzmc", "examcategory",
        "考试类型", "考试性质", "考试方式",
    )

    /** 课程性质（必修 / 选修） */
    private val TYPE_KEYS = listOf(
        "coursetype", "kcxz", "kclx", "coursenature", "kctype",
        "课程性质", "课程类型", "修读性质",
    )
}
