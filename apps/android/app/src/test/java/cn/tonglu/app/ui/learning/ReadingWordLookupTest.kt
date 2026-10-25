package cn.tonglu.app.ui.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词形还原候选的单测。
 *
 * 这里钉的不是「还原得多准」，而是**还原不许造假**：
 * 候选只是拿去词书里精确匹配的一串字，匹配不上就没有词元。
 * 所以最要紧的两条断言是 `reductionNeverInventsALemma`（对照组）和 `originalFormAlwaysComesFirst`。
 */
class ReadingWordLookupTest {

    // ---------- 🔴 对照组：故意不做的还原，必须真的没做 ----------

    /**
     * corner→corn、flower→flow、number→numb、summer→sum、offer→off
     * 这五个「-er」都是**词书里真实存在的词**，所以「精确匹配」这道闸拦不住它们 ——
     * 一旦做了 -er 规则，用户会在词卡上看到「corner → corn」这种**假词源**。
     *
     * 同理 -est（fastest→fast 之外还有 rest / test / best…）。
     * 本测试故意用这五个词当对照组：如果哪天有人「顺手加个 -er」，
     * 这条会红，而不是等到词卡上出现假词源才被发现。
     */
    @Test fun reductionNeverInventsALemma() {
        for (surface in listOf("corner", "flower", "number", "summer", "offer", "under")) {
            assertEquals(
                "$surface 只该有原形一个候选（否则会给出假词源）",
                listOf(surface), lemmaCandidates(surface),
            )
        }
    }

    /** 比较级 / 最高级同理：`bigger` 不许变成 `big`、`fastest` 不许变成 `fast`。 */
    @Test fun comparativeAndSuperlativeAreNotGuessed() {
        assertEquals(listOf("bigger"), lemmaCandidates("bigger"))
        assertEquals(listOf("easier"), lemmaCandidates("easier"))
        assertEquals(listOf("fastest"), lemmaCandidates("fastest"))
    }

    /** 不规则变化表**故意不做**：表配错的代价是在词卡上写一个词书里没有的词元。 */
    @Test fun irregularFormsAreLeftToExactMatching() {
        for (surface in listOf("went", "feet", "better", "children", "men", "took")) {
            assertEquals("$surface 不该被猜成别的词元", listOf(surface), lemmaCandidates(surface))
        }
    }

    // ---------- 正常还原 ----------

    /** 第一个候选永远是原形本身：词书里有这个词就直接命中，一个还原动作都不用做。 */
    @Test fun originalFormAlwaysComesFirst() {
        for (surface in listOf("studied", "running", "buses", "student's", "books", "study")) {
            assertEquals("原形必须排在第一位：$surface", surface, lemmaCandidates(surface).first())
        }
    }

    /** 还原候选必须**全小写**：词书里存的是小写词元，候选大小写不一致就永远匹配不上。 */
    @Test fun candidatesAreLowerCased() {
        assertEquals("studies", lemmaCandidates("Studies").first())
        assertTrue(lemmaCandidates("StUdIeD").contains("study"))
    }

    @Test fun pluralSAndEs() {
        assertTrue(lemmaCandidates("books").contains("book"))
        assertTrue(lemmaCandidates("buses").contains("bus"))
        assertTrue(lemmaCandidates("goes").contains("go"))
    }

    /** `-ss` 结尾的词不是复数，不许掉尾巴（class → clas 是错的）。 */
    @Test fun doubleSSurvivesPluralReduction() {
        assertEquals(listOf("class"), lemmaCandidates("class"))
        assertEquals(listOf("glass"), lemmaCandidates("glass"))
        assertEquals(listOf("press"), lemmaCandidates("press"))
    }

    @Test fun iesAndIedBecomeY() {
        assertTrue(lemmaCandidates("studies").contains("study"))
        assertTrue(lemmaCandidates("carried").contains("carry"))
        assertTrue(lemmaCandidates("cities").contains("city"))
    }

    @Test fun edFormsTryStemAndEStem() {
        assertTrue("wanted → want", lemmaCandidates("wanted").contains("want"))
        assertTrue("used → use（补回 e）", lemmaCandidates("used").contains("use"))
        assertTrue("stopped → stop（去双写）", lemmaCandidates("stopped").contains("stop"))
    }

    @Test fun ingFormsTryStemAndEStem() {
        assertTrue("reading → read", lemmaCandidates("reading").contains("read"))
        assertTrue("using → use", lemmaCandidates("using").contains("use"))
        assertTrue("running → run", lemmaCandidates("running").contains("run"))
        assertTrue("stopping → stop", lemmaCandidates("stopping").contains("stop"))
    }

    @Test fun possessiveDropsTheApostropheS() {
        assertTrue(lemmaCandidates("student's").contains("student"))
        assertTrue(lemmaCandidates("teacher\u2019s").contains("teacher"))
        // 去掉所有格之后**不该**再走 -s，否则会产出 "student'" 这种带撇号的垃圾候选。
        val candidates = lemmaCandidates("student's")
        assertTrue(
            "不产出带撇号的候选：$candidates",
            candidates.none { it.endsWith("'") || it.endsWith("\u2019") },
        )
    }

    // ---------- 边界 ----------

    /** 空 / 空白：返回空表。调用方据此**不查词**，而不是拿空串去查。 */
    @Test fun blankSurfaceHasNoCandidates() {
        assertEquals(emptyList<String>(), lemmaCandidates(""))
        assertEquals(emptyList<String>(), lemmaCandidates("   "))
        assertEquals(emptyList<String>(), lemmaCandidates("\t\n"))
    }

    /** 前后空白要先吃掉，否则候选里会带空格、在词书里永远匹配不上。 */
    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals(listOf("study"), lemmaCandidates("  study  "))
    }

    /** 单字母词：原形只有一位，仍然要产出（`a` / `I` 是真实词条）。 */
    @Test fun singleLetterWordStillProducesItself() {
        assertEquals(listOf("a"), lemmaCandidates("A"))
    }

    /** 候选去重：不会出现同一个字符串两次（否则调用方会重复查一遍词书）。 */
    @Test fun candidatesAreDeduplicated() {
        val list = lemmaCandidates("studied")
        assertEquals("候选不该有重复：$list", list.distinct(), list)
    }
}
