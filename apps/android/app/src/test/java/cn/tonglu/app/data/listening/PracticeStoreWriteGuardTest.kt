package cn.tonglu.app.data.listening

import cn.tonglu.app.data.PracticeEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import cn.tonglu.app.data.listening.PracticeMode
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * **写路径**遇到「读不出来」必须停下 —— FINDINGS #10 的正身验收。
 *
 * ## 为什么又开一个测试文件
 * [PracticeFileReadTest] 测的是**解析判定**（纯函数），这里测的是**判定之后写路径真的停手**，
 * 而且断言到**文件字节**：损坏文件在调用前后必须一模一样。
 * 只测判定等于默认「判定对 → 行为就对」，那一步恰恰是 #10 要挡的事。
 *
 * ## 怎么绕开 Context
 * `PracticeStore` 的主构造器是 `(root: File, owner)`（`internal`），单测直接指向临时目录。
 * 生产入口 `PracticeStore.of(context, owner)` 行为一字未改 —— 见那里的 KDoc。
 *
 * ## 为什么必须有对照组
 * 「损坏时 record 抛异常」这种断言有个陷阱：**如果 record 在任何情况下都抛，它也会过**。
 * 所以下面三组对照组是必需的，不是凑数：
 * - [空目录上记一条_正常写入]
 * - [正常文件上再记一条_历史保留]
 * - [空文件上记一条_正常写入]
 * 它们一红，说明守卫过头了（把正常写入也挡了），同样是坏掉。
 *
 * ## 没覆盖的一条
 * `attempts()`（**显示**路径）读失败时返回空列表并 `Log.e` —— 那条日志在 JVM 单测里
 * 会撞上 android.jar stub（项目设了 `isReturnDefaultValues = false`），测不了。
 * 显示路径本来就不写盘，风险在「界面空了」而不是「数据丢了」，接受这个缺口并记录在此。
 */
class PracticeStoreWriteGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val owner = "user-1"

    private fun newStore(): Pair<File, PracticeStore> {
        val dir = tmp.newFolder("practice")
        return dir to PracticeStore(dir, owner)
    }

    /**
     * 存储自己的文件名是 `SHA256(owner).json` —— 这里**刻意不去复算那个哈希**
     * （复算等于把测试焊死在命名规则上，改名就要跟着改）。目录里只有这一份 json，
     * 直接认后缀即可。
     */
    private fun jsonFile(dir: File): File = dir.listFiles()!!.single { it.name.endsWith(".json") }

    /** 先正常写一条把文件建出来，再把内容换成坏的 —— 拿到的是**真实路径**。 */
    private fun seedRaw(store: PracticeStore, dir: File, raw: String): File {
        store.record("q01", "bank", "题组", 1, true, "bank:q01", PracticeMode.QUIZ)
        val f = jsonFile(dir)
        f.writeText(raw)
        return f
    }

    private fun corruptFrom(dir: File, store: PracticeStore): File {
        store.record("q01", "bank", "题组", 1, true, "bank:q01", PracticeMode.QUIZ)
        val f = jsonFile(dir)
        val good = f.readText()
        // 落在中间：开头那条记录是完整的 —— 「读到几条算几条」的写法会在这里漏
        f.writeText(good.substring(0, good.length / 2))
        return f
    }

    private inline fun expectUnreadable(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what：读不出来时必须抛 [PracticeStoreUnreadableException]，而不是静默继续")
        } catch (e: PracticeStoreUnreadableException) {
            // 期望
        }
    }

    private fun newAttemptEvent(id: String = "cloud-1") = PracticeEventDto(
        eventId = "e-$id",
        kind = PracticeEventDto.KIND_ATTEMPT,
        payload = mapOf(
            "id" to id, "questionId" to "q01", "questionKey" to "bank:q01",
            "materialId" to "bank", "materialTitle" to "题组",
            "chosen" to 1, "correct" to true, "answeredAt" to 9_000L,
        ),
    )

    // ---------------------------------------------------------------- 记一条作答

    /** 损坏文件上记一条：必须抛，且**原文件一个字节都不许变**。 */
    @Test
    fun `损坏文件上记一条作答_抛异常且原文件字节不变`() {
        val (dir, store) = newStore()
        val f = corruptFrom(dir, store)
        val before = f.readText()

        expectUnreadable("record") {
            store.record("q02", "bank", "题组", 2, false, "bank:q02", PracticeMode.QUIZ)
        }

        assertEquals("损坏文件必须保留原样，等用户/修复来处理，不许被一条新作答替换", before, f.readText())
    }

    // ---------------------------------------------------------------- 增量合并 / 删除 / 导出 / 恢复

    /** 云端增量下来时本机文件是坏的：合并必须停手，不能拿「空的本机」当底子写。 */
    @Test
    fun `损坏文件上做增量合并_抛异常且不写盘`() {
        val (dir, store) = newStore()
        val f = corruptFrom(dir, store)
        val before = f.readText()

        expectUnreadable("applyChanges") { store.applyChanges(listOf(newAttemptEvent())) }

        assertEquals(before, f.readText())
    }

    /** 墓碑落地（按 id 删）同理：不知道本机有什么，就不能删。 */
    @Test
    fun `损坏文件上按 id 删除_抛异常且不写盘`() {
        val (dir, store) = newStore()
        val f = corruptFrom(dir, store)
        val before = f.readText()

        expectUnreadable("removeByIds") { store.removeByIds(setOf("a")) }

        assertEquals(before, f.readText())
    }

    /**
     * 导出（上传 / 备份）读不出来必须抛，不能返回空列表。
     *
     * 空列表会让上传路径判断「没有要传的」，于是**同步报成功、其实一条都没上去**
     * —— 比丢数据更糟，那是假装成功。
     */
    @Test
    fun `损坏文件导出_抛异常而不是谎报没有记录`() {
        val (dir, store) = newStore()
        corruptFrom(dir, store)

        expectUnreadable("exportAttempts") { store.exportAttempts() }
    }

    /**
     * 最阴的一条：`restoreIfEmpty` 是**整包覆盖**。
     *
     * 它读盘失败要是退化成空列表，就会被判成「本机是空桶」→ 拿云端数据把损坏文件整个盖掉，
     * 用户本机那几百条连「损坏待修」的机会都没有，直接消失。
     */
    @Test
    fun `损坏文件上恢复云端数据_抛异常而不覆盖`() {
        val (dir, store) = newStore()
        val f = corruptFrom(dir, store)
        val before = f.readText()

        expectUnreadable("restoreIfEmpty") {
            store.restoreIfEmpty(
                listOf(
                    QuestionAttempt(
                        id = "c1", questionId = "q01", questionKey = "bank:q01",
                        materialId = "bank", materialTitle = "题组", chosen = 1,
                        correct = true, answeredAt = 5_000L,
                    ),
                ),
            )
        }

        assertEquals("整包恢复绝不能盖掉读不出来的本机文件", before, f.readText())
    }

    // ---------------------------------------------------------------- 对照组：正常写入必须照常

    /** 全新设备（没有文件）：照常写，不许抛。 */
    @Test
    fun `空目录上记一条_正常写入`() {
        val (dir, store) = newStore()
        store.record("q01", "bank", "题组", 1, true, "bank:q01", PracticeMode.QUIZ)
        assertEquals(1, jsonFile(dir).let { PracticeStore(dir, owner).attempts().size })
    }

    /** 已有历史：追加，不覆盖。这条同时守住「守卫没有把正常路径一起挡掉」。 */
    @Test
    fun `正常文件上再记一条_历史保留且条数加一`() {
        val (dir, store) = newStore()
        store.record("q01", "bank", "题组", 1, true, "bank:q01", PracticeMode.QUIZ)
        val firstId = store.attempts().first().id
        store.record("q02", "bank", "题组", 2, false, "bank:q02", PracticeMode.QUIZ)

        val rows = store.attempts()
        assertEquals(2, rows.size)
        assertTrue("先记的那条不能被后一条挤掉", rows.any { it.id == firstId })
        assertEquals("bank:q02", rows.first().questionKey)
    }

    /**
     * 文件在但是**空的**（写入被打断留下的 0 字节，是「确实没有历史」这一档）：照常写。
     *
     * 把这一档也挡掉，用户就永远写不进去了 —— 守卫过头同样是 bug。
     */
    @Test
    fun `空文件上记一条_正常写入`() {
        val (dir, store) = newStore()
        seedRaw(store, dir, "")
        store.record("q01", "bank", "题组", 1, true, "bank:q01", PracticeMode.QUIZ)
        assertEquals(1, store.attempts().size)
    }

    /**
     * 顺带钉住一件事：`IOException` 语义没被破坏 —— 守卫抛的是 IOException 子类，
     * 调用方已有的「落盘失败要提示用户」分支能直接接管，不需要学新异常。
     */
    @Test
    fun `守卫抛的是 IOException 子类`() {
        val (dir, store) = newStore()
        corruptFrom(dir, store)
        try {
            store.record("q02", "bank", "题组", 2, false, "bank:q02", PracticeMode.QUIZ)
            fail("应当抛出")
        } catch (e: IOException) {
            assertTrue("必须是 PracticeStoreUnreadableException，提示语才对得上", e is PracticeStoreUnreadableException)
            assertTrue("提示必须说明「没有写入」，不能让用户以为存上了", e.message!!.contains("不做任何写入"))
        }
    }
}
