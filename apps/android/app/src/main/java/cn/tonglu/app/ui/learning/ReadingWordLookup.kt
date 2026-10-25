package cn.tonglu.app.ui.learning

import java.util.Locale

/**
 * 「原文词形 → 词典词元」的**候选还原**（纯函数，JVM 可直测）。
 *
 * 为什么需要它：点在正文里的词是文章里的**屈折形式**（studied / running / buses），
 * 而词书里存的是词元。契约要求「词形还原属于词典层，不能改变原文定位」，
 * 所以这里只产出**查表用的候选**，不回写任何偏移、也不改原句。
 *
 * ## 它是「猜词队列」，不是词法分析器
 *
 * - 第一个候选**永远是原形本身**：词书里有这个词就直接命中，什么还原都不做。
 * - 后面的候选必须**逐个在词书里精确匹配**（`lower(english) = 候选`）才算数。
 *   全都没命中 → `lemma = null`（**没还原出词元**），界面照实显示未收录，
 *   而不是拿一个「看起来像词元」的字符串去凑。
 *
 * 由此也解释了两件**故意不做**的事：
 *
 * 1. **不做不规则变化表**（went→go / feet→foot / better→good 这类）。
 *    要做得对，得配一份可能出错的词表；配错的代价是在词卡上写一个**词书里根本没有的词元** ——
 *    那比显示「未收录」坏得多。所以这些词只拿原形去查，查不到就是未收录。
 * 2. **不做 -er / -est**（比较级、最高级）。看着很划算，实测是错得最多的两条规则：
 *    corner→corn、flower→flow、number→numb、summer→sum、offer→off。
 *    这些**都是词书里真实存在的词**，所以「精确匹配」这道闸拦不住它们 ——
 *    用户会在词卡上看到「corner → corn」这种假词源。
 *    -er/-est 省下的是少数几个规则比较级（bigger/easier），
 *    代价是往词卡里塞假词源，不值得。
 *    （`WordLookupTest.reductionNeverInventsALemma` 用 corner/flower 把这条钉住了。）
 *
 * @return 去重、有序的候选；原形为空时返回空表（调用方据此**不查词**，而不是查一个空串）。
 */
fun lemmaCandidates(surface: String): List<String> {
    val base = surface.trim().lowercase(Locale.ROOT)
    if (base.isEmpty()) return emptyList()
    val out = LinkedHashSet<String>()
    out += base
    // 长度下限 2：单词不会更短（"a"/"I" 这类本身就只有一位，原形已经进队列了）。
    fun add(vararg candidates: String) {
        candidates.filter { it.length >= 2 }.forEach { out += it }
    }
    /** 双写辅音还原：stopp → stop、runn → run。只对「末两位相同且是辅音」生效。 */
    fun dedoubled(stem: String): String =
        if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2] &&
            stem.last() !in "aeiou") stem.dropLast(1) else stem

    // 所有格 / 缩写：student's → student
    if (base.endsWith("'s") || base.endsWith("\u2019s")) add(base.dropLast(2))
    // -ies / -ied → -y：studies → study、carried → carry
    if (base.endsWith("ies") && base.length > 4) add(base.dropLast(3) + "y")
    if (base.endsWith("ied") && base.length > 4) add(base.dropLast(3) + "y")
    // -ed：wanted → want、used → use、stopped → stop
    if (base.endsWith("ed") && base.length > 3) {
        val stem = base.dropLast(2)
        add(stem, stem + "e", dedoubled(stem), base.dropLast(1))
    }
    // -ing：reading → read、using → use、running → run
    if (base.endsWith("ing") && base.length > 4) {
        val stem = base.dropLast(3)
        add(stem, stem + "e", dedoubled(stem))
    }
    // -es / -s：buses → bus/buse、goes → go、books → book
    // 「-ss 结尾不动」（class / glass 不是复数）。
    // 已经去掉所有格的词不再走 -s（否则 "student's" 还会产出 "student'" 这种带撇号的垃圾候选）。
    val possessive = base.endsWith("'s") || base.endsWith("\u2019s")
    if (!possessive && base.endsWith("es") && base.length > 3) add(base.dropLast(2), base.dropLast(1))
    else if (!possessive && base.endsWith("s") && base.length > 2 && !base.endsWith("ss")) add(base.dropLast(1))
    return out.toList()
}

/**
 * 一次查词的**真实结果**。`lemma` 只在**还原后才命中**时有值；原形直接命中时是 null。
 *
 * 音标照抄词条自身的两个字段、不做任何推断：`ukPronunciation` 是英式，`pronunciation`
 * 是词典那一条（词书没给英美区分时两个口音都会退化成「通用音标」）。
 * 两个都没有就是 null —— 界面如实写「音标未登记」，**不用模型编**。
 */
data class WordLookupHit(val wordBank: String, val wordId: String, val english: String,
    val chinese: String, val lemma: String?,
    val phoneticUk: String?, val phoneticGeneral: String?)

/** 查词失败与「未收录」是两件事：前者要报错，后者要如实说未收录。 */
sealed interface WordLookup {
    data class Found(val hit: WordLookupHit) : WordLookup
    data object NotRecorded : WordLookup
}
