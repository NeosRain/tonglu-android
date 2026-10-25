package cn.tonglu.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 个人备份的**边界**（B6，`docs/plan/56` §3.3「我的/备份」）。
 *
 * ## 这层测试守的是一条红线，不是一个功能
 * 备份文件会被用户存网盘、发给自己、丢在聊天记录里。只要里面混进一次
 * **校园 Cookie / AI Key / 登录令牌**，就等于把身份钥匙交出去，
 * 而且**事后无法收回**。所以「不含什么」这件事必须被机器锁住，
 * 不能只靠写代码时记得。
 *
 * 这里测的是纯函数 [PersonalBackup.encode] / [PersonalBackup.decode]，
 * 不起 Android 环境 —— 边界规则与平台无关。
 */
class PersonalBackupTest {

    private fun snapshotWithEverything() = PersonalBackup.Snapshot(
        courses = JSONArray().put(JSONObject().put("name", "高等数学")),
        scores = JSONArray().put(JSONObject().put("course", "英语")),
        termStart = "2026-09-01",
        reading = JSONObject().put("progress", JSONArray()),
        practice = JSONArray().put(JSONObject().put("id", "a1")),
        plans = JSONArray().put(JSONObject().put("id", "p1")),
    )

    // ---------------------------------------------------------------- 边界（红线）

    /**
     * **最关键的一条**：导出文件里必须显式声明「这几样没带」，且内容里不含它们。
     *
     * 如果哪天有人把 Cookie 塞进 sections，这条测试会失败 —— 那就是它存在的意义。
     */
    @Test
    fun `备份显式声明不含凭据并真的不含`() {
        val text = PersonalBackup.encode(snapshotWithEverything(), "2026-10-05T13:00:00Z", "0.6.54")
        val root = JSONObject(text)

        val omitted = root.getJSONArray("omitted")
        val declared = (0 until omitted.length()).map { omitted.getString(it) }
        assertTrue("必须声明不含校园 Cookie", declared.any { it.contains("Cookie") })
        assertTrue("必须声明不含密钥", declared.any { it.contains("密钥") })
        assertTrue("必须声明不含登录令牌", declared.any { it.contains("令牌") })

        // 再扫一遍**数据区**：`omitted` 里本来就会写出「Cookie」这个词（那是声明），
        // 所以不能扫全文 —— 要扫的是 sections，那里才是真正被带出去的数据。
        val data = root.getJSONObject("sections").toString().lowercase()
        listOf("cookie", "apikey", "api_key", "refreshtoken", "refresh_token", "bearer", "password", "token")
            .forEach { forbidden ->
                assertFalse("备份的 sections 里不该出现「$forbidden」", data.contains(forbidden))
            }
    }

    /** 声明是**固定写入**的，不依赖调用方传参 —— 调用方漏传也不能让它消失。 */
    @Test
    fun `omitted 声明固定在 encode 内不依赖调用方`() {
        val text = PersonalBackup.encode(PersonalBackup.Snapshot(), "2026-10-05T13:00:00Z", null)
        assertEquals(
            PersonalBackup.OMITTED.size,
            JSONObject(text).getJSONArray("omitted").length(),
        )
    }

    /** 白名单：没传的类别不会凭空出现在文件里。 */
    @Test
    fun `没传的类别不出现在备份里`() {
        val text = PersonalBackup.encode(PersonalBackup.Snapshot(), "2026-10-05T13:00:00Z", null)
        val sections = JSONObject(text).getJSONObject("sections")
        assertEquals(0, sections.length())
    }

    /** 空快照也是合法备份（用户可能就是导出个空档），不该抛。 */
    @Test
    fun `空快照可以正常导出`() {
        val text = PersonalBackup.encode(PersonalBackup.Snapshot(), "2026-10-05T13:00:00Z", null)
        assertNotNull(PersonalBackup.decode(text))
    }

    // ---------------------------------------------------------------- 往返

    /** 导出再导入：每一类都还原（值不变）。 */
    @Test
    fun `导出后导入各类数据一致`() {
        val text = PersonalBackup.encode(snapshotWithEverything(), "2026-10-05T13:00:00Z", "0.6.54")
        val parsed = PersonalBackup.decode(text)!!
        assertEquals("高等数学", parsed.courses!!.getJSONObject(0).getString("name"))
        assertEquals("英语", parsed.scores!!.getJSONObject(0).getString("course"))
        assertEquals("2026-09-01", parsed.termStart)
        assertNotNull(parsed.reading)
        assertEquals("a1", parsed.practice!!.getJSONObject(0).getString("id"))
        assertEquals("p1", parsed.plans!!.getJSONObject(0).getString("id"))
    }

    /** 只带课表的备份，其它类别解析出来是 null（不是空数组 —— 空数组是「有这一类但没内容」）。 */
    @Test
    fun `只带课表的备份其它类别为 null`() {
        val text = PersonalBackup.encode(
            PersonalBackup.Snapshot(courses = JSONArray().put(JSONObject().put("name", "语文"))),
            "2026-10-05T13:00:00Z", null,
        )
        val parsed = PersonalBackup.decode(text)!!
        assertNotNull(parsed.courses)
        assertNull(parsed.scores)
        assertNull(parsed.practice)
        assertNull(parsed.plans)
    }

    /** `presentSections()` 如实列出实际带了哪几类（界面用来提示「将导入：…」）。 */
    @Test
    fun `presentSections 如实反映实际类别`() {
        val text = PersonalBackup.encode(snapshotWithEverything(), "2026-10-05T13:00:00Z", null)
        val names = PersonalBackup.decode(text)!!.presentSections()
        assertEquals(listOf("课表", "成绩", "学期设置", "阅读数据", "作答记录", "学习计划"), names)
    }

    // ---------------------------------------------------------------- 坏文件

    /** 不是 JSON：返回 null，不抛（「尽力猜」比空更危险）。 */
    @Test
    fun `非 JSON 内容解析返回 null`() {
        assertNull(PersonalBackup.decode("这不是 JSON"))
        assertNull(PersonalBackup.decode(""))
    }

    /** 被截断的 JSON：返回 null。 */
    @Test
    fun `截断的 JSON 解析返回 null`() {
        assertNull(PersonalBackup.decode("""{"schemaVersion":1,"sections":{"""))
    }

    /** 没有 `sections` 字段：返回 null（不是「空备份」—— 那会让用户以为导入成功了）。 */
    @Test
    fun `缺 sections 字段解析返回 null`() {
        assertNull(PersonalBackup.decode("""{"schemaVersion":1}"""))
    }

    /**
     * **版本比我们新**：拒收。
     *
     * 更新的 App 可能给字段换了含义（比如 `correct` 从布尔改成对象），
     * 照旧解析会得到「看起来像成功、其实全是错值」的结果 —— 比明确失败糟得多。
     */
    @Test
    fun `更新的 schemaVersion 被拒收`() {
        val future = JSONObject()
            .put("schemaVersion", PersonalBackup.SCHEMA_VERSION + 1)
            .put("sections", JSONObject().put("courses", JSONObject().put("items", JSONArray())))
            .toString()
        assertNull(PersonalBackup.decode(future))
    }

    /** 版本与当前相同 / 更旧：都能读（旧版文件是我们要支持的迁移来源）。 */
    @Test
    fun `同版本与更旧版本的备份可读`() {
        val same = JSONObject()
            .put("schemaVersion", PersonalBackup.SCHEMA_VERSION)
            .put("sections", JSONObject().put("termStart", "2026-09-01")).toString()
        assertNotNull(PersonalBackup.decode(same))

        val older = JSONObject()
            .put("schemaVersion", 0)
            .put("sections", JSONObject().put("termStart", "2026-09-01")).toString()
        assertEquals("2026-09-01", PersonalBackup.decode(older)!!.termStart)
    }

    /** 缺 `schemaVersion`：按 0 处理（当成最老的版本读）。 */
    @Test
    fun `缺 schemaVersion 时按最老版本读`() {
        val text = """{"sections":{"termStart":"2026-09-01"}}"""
        assertEquals("2026-09-01", PersonalBackup.decode(text)!!.termStart)
    }
}
