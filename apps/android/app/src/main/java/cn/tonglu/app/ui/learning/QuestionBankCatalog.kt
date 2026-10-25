package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.question.QuestionBank

/** 只识别现有发布规则中的考次，不通过标题里一个「四级」猜测它是真题。 */
internal data class ExamBankGroup(
    val id: String,
    val level: String,
    val year: Int,
    val month: Int,
    val paper: Int,
    val banks: List<QuestionBank>,
) {
    val title: String get() = "$year 年 $month 月 · ${if (level == "cet4") "四级" else "六级"} · 第 $paper 套"
}

private val examBankId = Regex("^(cet4|cet6)-(20\\d{2})-(06|12)-([1-3])-(listening|cloze|matching|reading|writing|translation)$")

internal fun examGroupId(bank: QuestionBank): String? =
    examBankId.matchEntire(bank.id)?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}-${it.groupValues[4]}" }

internal fun examBankGroups(banks: List<QuestionBank>): List<ExamBankGroup> = banks
    .mapNotNull { bank -> examBankId.matchEntire(bank.id)?.let { it to bank } }
    .groupBy { (_, bank) -> requireNotNull(examGroupId(bank)) }
    .map { (id, entries) ->
        val values = entries.first().first.groupValues
        val order = listOf("listening", "cloze", "matching", "reading", "writing", "translation")
        ExamBankGroup(id, values[1], values[2].toInt(), values[3].toInt(), values[4].toInt(),
            entries.sortedBy { order.indexOf(it.first.groupValues[5]) }.map { it.second })
    }
    .sortedWith(compareByDescending<ExamBankGroup> { it.year }.thenByDescending { it.month }
        .thenBy { it.level }.thenBy { it.paper })

/** 从真实作答键找下一道可判分且未作答的题；全部完成后重新练习，不删除历史。 */
internal fun nextUnansweredIndex(bank: QuestionBank, answeredKeys: Set<String>): Int =
    bank.questionList.indexOfFirst { it.gradable && bank.keyOf(it) !in answeredKeys }.coerceAtLeast(0)

/** 本轮错题列表固定，订正后先留在原题看解析，下次进入才重新筛选。 */
internal fun reviewQuestions(bank: QuestionBank, reviewKeys: Set<String>) =
    bank.questionList.filter { bank.keyOf(it) in reviewKeys }

internal sealed interface BankCatalogRow {
    val key: String
    data class Group(val group: ExamBankGroup, val expanded: Boolean) : BankCatalogRow {
        override val key = "group:${group.id}"
    }
    data class Bank(val bank: QuestionBank, val grouped: Boolean) : BankCatalogRow {
        override val key = "bank:${bank.id}"
    }
}

internal fun bankCatalogRows(banks: List<QuestionBank>, filter: String, expanded: Map<String, Boolean>): List<BankCatalogRow> {
    val groups = examBankGroups(banks).filter { filter == "all" || filter == it.level }
    return buildList {
        if (filter != "other") groups.forEach { group ->
            val open = expanded[group.id] ?: false
            add(BankCatalogRow.Group(group, open))
            if (open) group.banks.forEach { add(BankCatalogRow.Bank(it, true)) }
        }
        if (filter == "all" || filter == "other") banks.filter { examGroupId(it) == null }
            .forEach { add(BankCatalogRow.Bank(it, false)) }
    }
}
