package cn.tonglu.app.data.question

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置题库的内容契约（`docs/plan/57` §2.1 / §五）。
 *
 * ## 为什么必须有
 * 题库是**内容**，内容错误不会被编译器发现：答案下标越界、解析为空、
 * 题目键重复、来源标注缺失 —— 这些在界面上的表现只是「这题怪怪的」，
 * 却直接关系到「不给用户看不靠谱的题」这条底线。
 *
 * 这里直接读 `assets/questions/` 的源文件（不是构建产物），
 * 所以内容一改就能立刻发现。
 */
class QuestionBankContentTest {

    private val gson = Gson()

    /** 从测试工作目录回溯到 assets。CI 与本地都从 app 模块根跑。 */
    private val assetsDir: File by lazy {
        val candidates = listOf(
            File("src/main/assets/questions"),
            File("app/src/main/assets/questions"),
            File("../app/src/main/assets/questions"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 assets/questions，测试工作目录=" + File(".").absolutePath)
    }

    private fun indexBanks(): List<String> {
        val text = File(assetsDir, "index.json").readText()
        val idx = gson.fromJson(text, BankIndexFile::class.java)
        return idx.banks ?: emptyList()
    }

    private class BankIndexFile(val banks: List<String>? = null)

    private fun bank(fileName: String): QuestionBank =
        gson.fromJson(File(assetsDir, fileName).readText(), QuestionBank::class.java)

    @Test
    fun `清单非空且引用的文件都存在`() {
        val banks = indexBanks()
        assertTrue("题库清单不能为空", banks.isNotEmpty())
        banks.forEach { name ->
            val f = File(assetsDir, name)
            assertTrue("清单引用了不存在的文件：$name", f.isFile)
        }
    }

    @Test
    fun `每个题库都通过可用性三道闸`() {
        indexBanks().forEach { name ->
            val b = bank(name)
            assertTrue("${b.id} 的题库 id 不能含冒号", b.keySafe)
            assertTrue("${b.id} 必须写明来源（打标红线）", b.sourceNote.isNotBlank())
            assertTrue("${b.id} 至少要有一道可判分的题", b.usable)
        }
    }

    @Test
    fun `每题都合法：选项至少两个 答案在位 有解析 题号不含冒号`() {
        indexBanks().forEach { name ->
            val b = bank(name)
            b.questionList.forEach { q ->
                assertFalse("${b.id}/${q.id} 题号不能含冒号", q.id.contains(':'))
                assertTrue("${b.id}/${q.id} 题号不能为空", q.id.isNotBlank())
                assertTrue("${b.id}/${q.id} 选项至少两个", q.options.size >= 2)
                assertTrue("${b.id}/${q.id} 答案下标越界", q.answer in q.options.indices)
                assertTrue("${b.id}/${q.id} 必须有解析", q.explanation.isNotBlank())
                assertTrue("${b.id}/${q.id} 题面不能为空", q.prompt.isNotBlank())
                assertTrue(
                    "${b.id}/${q.id} 难度取值非法：${q.difficulty}",
                    Difficulty.isValid(q.difficulty),
                )
                assertEquals(
                    "${b.id}/${q.id} 选项不允许重复",
                    q.options.size, q.options.toSet().size,
                )
            }
        }
    }

    /** 题目键全局唯一 —— 重复就意味着两份题共享作答历史。 */
    @Test
    fun `题目键全局唯一`() {
        val keys = indexBanks().flatMap { name ->
            val b = bank(name)
            b.questionList.map { b.keyOf(it) }
        }
        assertEquals("存在重复的题目键", keys.size, keys.distinct().size)
    }

    /** 题库 id 之间也不能重名（否则不同题库的题会同键）。 */
    @Test
    fun `题库id不重复`() {
        val ids = indexBanks().map { bank(it).id }
        assertEquals("题库 id 有重复", ids.size, ids.distinct().size)
    }

    /**
     * 打标红线：题库不许把自己说成真题。
     *
     * **判据不是「含『真题』字样」** —— 正确写法恰恰是
     * 「不是任何考试的真题」，里面就有「真题」二字。
     * 所以要判的是「没有一句否认」：来源说明里必须出现否定的表述。
     * （第一版写成子串否定，把正当的否定说明判成了冒充 —— 测试自己有过错。）
     */
    @Test
    fun `题库来源说明必须明确否认是真题`() {
        indexBanks().forEach { name ->
            val b = bank(name)
            val text = b.sourceNote + b.title
            val denies = listOf("不是", "非", "自建", "自编", "自行编写").any { text.contains(it) }
            assertTrue(
                "${b.id} 的来源说明看不出它「不是真题」—— 打标红线：$text",
                denies,
            )
        }
    }

    /**
     * 难度分层要够诊断用（docs/plan/56 §4.4 要按层抽题）。
     *
     * **分层是跨题库的，不是每个题库内都要分** —— 三个题库各占一层
     * （语法=basic、词汇=core、进阶=advanced）是**故意**的设计：
     * 同一层的题放一起，用户做起来节奏一致，也让「测一测」能按层成组抽题。
     * （第一版要求「每个题库至少两层」，与设计不符 —— 测试自己有过错。）
     */
    @Test
    fun `题库整体覆盖全部三个难度层`() {
        val layers = indexBanks()
            .flatMap { bank(it).questionList.map { q -> q.difficulty } }
            .toSet()
        assertEquals(
            "题库整体应覆盖三个难度层（跨题库分层），当前只有 $layers",
            Difficulty.order.toSet(), layers,
        )
    }

    /** 每个题目自己必须落在合法难度层里 —— 这是抽题的前提。 */
    @Test
    fun `每题难度层都在合法取值内`() {
        indexBanks().forEach { name ->
            val b = bank(name)
            b.questionList.forEach { q ->
                assertTrue(
                    "${b.id}/${q.id} 难度层非法：${q.difficulty}",
                    Difficulty.isValid(q.difficulty),
                )
            }
        }
    }

    /** 内容规模：够支撑一次练习，不至于点进去就做完。 */
    @Test
    fun `题库总量够做一次练习`() {
        val total = indexBanks().sumOf { bank(it).size }
        assertTrue("题库总量应不少于 20 题，当前 $total", total >= 20)
    }
    @Test
    fun `四六级六个方向题库完整并明确自编`() {
        val names = indexBanks().filter { it.startsWith("cet4-") || it.startsWith("cet6-") }
        assertEquals(6, names.size)
        assertEquals(52, names.sumOf { bank(it).questionList.size })
        names.forEach { name ->
            val b = bank(name)
            assertTrue(b.title.contains("自编"))
            assertTrue(b.sourceNote.contains("非官方"))
            assertTrue(b.questionList.map { it.answer }.toSet().size >= 3)
            if (name.contains("listening")) {
                assertTrue(b.questionList.all { !it.audioText.isNullOrBlank() })
                assertTrue(b.questionList.all { !it.prompt.contains(it.audioText.orEmpty()) })
            }
        }
    }

    @Test
    fun `旧题库没有朗读字段仍可解析判分`() {
        val old = bank("en-basic-grammar.json").questionList.first()
        assertTrue(old.gradable)
        assertTrue(old.audioText.isNullOrBlank())
    }

}
