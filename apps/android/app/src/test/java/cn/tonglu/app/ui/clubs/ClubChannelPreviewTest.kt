package cn.tonglu.app.ui.clubs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 社团频道的**本地预览**（plan/62 第一轮第 4 条）。
 *
 * ## 这几条为什么非测不可
 * 预览数据最容易出的问题不是崩溃，而是**看起来像真的**：
 * - 加一个「12 人已加入」→ 用户以为社团是真的，还真的有 12 个人；
 * - 忘打「示例」标签 → 入口横幅还在，但列表项已经像真实频道了；
 * - 所有频道都有内容 → plan/62 验收里的「空频道」这一态**永远走查不到**。
 *
 * 前两条用**反射**钉（字段名不许出现 member / unread；预览标记恒为 true），
 * 第三条断言「至少有一个频道返回空列表」。故意改坏任何一条都会变红。
 */
class ClubChannelPreviewTest {

    /**
     * 🔴 预览数据里**不许**出现成员数 / 未读数 / 最后活跃时间这类「看起来是真实运营数据」的字段。
     *
     * 为什么用反射而不是靠人看：这两个词以后很可能被顺手加回来
     * （「加个未读角标才像 Nekogram」），而那一刻界面上完全看不出问题。
     *
     * 2026-10-09 补齐：气泡流上线后 `PreviewMessage` 也进了这条检查 ——
     * 消息里最容易夹带的正是「几点几分发的」这种一加就像真数据的时间字段。
     */
    @Test
    fun `预览数据不许带成员数或未读数`() {
        val classes = listOf(PreviewChannel::class.java, PreviewMessage::class.java)
        val names = classes.flatMap { it.declaredFields.map { f -> f.name.lowercase() } } +
            classes.flatMap { it.declaredMethods.map { m -> m.name.lowercase() } } +
            ClubChannelPreview::class.java.declaredMethods.map { it.name.lowercase() }
        val forbidden = listOf("member", "unread", "online", "lastactive", "last_active", "timestamp", "sentat", "unreadcount")
        val hit = names.filter { name -> forbidden.any { name.contains(it) } }
        assertTrue("预览数据里出现了像真实数据的字段：$hit", hit.isEmpty())
    }

    /**
     * 气泡右下角那个「时间位」写的是常量占位，**不是时间**。
     * 位置照 Telegram 留是为了版式完整；一旦有人把它换成真实时间格式，这条会红。
     */
    @Test
    fun `时间位是占位不是时间`() {
        assertEquals("示例", ClubChannelPreview.TIME_SLOT)
        assertFalse("时间位不许写成时间格式", Regex("\\d{1,2}:\\d{2}").containsMatchIn(ClubChannelPreview.TIME_SLOT))
    }

    /** 每条频道都必须带预览标记 —— 界面靠它画「示例」标签，漏了就会像真数据。 */
    @Test
    fun `每条频道都带预览标记`() {
        val channels = ClubChannelPreview.channelsFor("anything")
        assertTrue("至少要有一条频道，否则版式无从看起", channels.isNotEmpty())
        assertTrue("有频道没带预览标记", channels.all { it.preview })
        assertTrue("频道 id 不能重复", channels.map { it.id }.toSet().size == channels.size)
        assertTrue("频道名不能为空", channels.all { it.name.isNotBlank() })
        assertTrue("频道摘要不能为空", channels.all { it.summary.isNotBlank() })
    }

    /** 同一个社团 id 每次都得给出同一套版式：编造「这家社团和别家不一样」只会误导走查。 */
    @Test
    fun `不同社团给出同一套版式，不编造差异`() {
        assertEquals(ClubChannelPreview.channelsFor("a"), ClubChannelPreview.channelsFor("b"))
    }

    /** 四种频道类型（公告 / 讨论 / 活动 / 外链）都要出现，否则版式不完整。 */
    @Test
    fun `四类频道都覆盖到`() {
        val kinds = ClubChannelPreview.channelsFor("x").map { it.kind }.toSet()
        assertEquals(ChannelKind.entries.toSet(), kinds)
    }

    /**
     * 至少要有一个**空频道**：plan/62 验收里点名要有「空频道」这一态。
     * 如果所有频道都有内容，那条验收就只能靠嘴说 —— 这是「代码里有、实际到不了」的典型。
     */
    @Test
    fun `至少有一个频道是空的，空态才走查得到`() {
        val channels = ClubChannelPreview.channelsFor("x")
        val empty = channels.filter { ClubChannelPreview.messagesFor(it.id).isEmpty() }
        assertTrue("没有空频道，空态永远看不到", empty.isNotEmpty())
        val withContent = channels.filter { ClubChannelPreview.messagesFor(it.id).isNotEmpty() }
        assertTrue("也不能全是空的，那样看不到有内容的版式", withContent.isNotEmpty())
    }

    /** 预览消息也必须自带「示例」字样 —— 它脱离了横幅就只剩这几行字。 */
    @Test
    fun `预览消息自带示例字样`() {
        ClubChannelPreview.channelsFor("x")
            .flatMap { ClubChannelPreview.messagesFor(it.id) }
            .forEach { assertTrue("预览消息没写「示例」：$it", it.text.contains("示例")) }
    }

    /**
     * 发送人名字也要打标：气泡流上线后，「示例 · 成员 A」这种名字是**最容易被当成真人**的东西。
     * 谁把「示例 · 」前缀去掉，这条就红。
     */
    @Test
    fun `发送人都带示例前缀`() {
        val messages = ClubChannelPreview.channelsFor("x").flatMap { ClubChannelPreview.messagesFor(it.id) }
        assertTrue("得有消息才看得到气泡版式", messages.isNotEmpty())
        messages.forEach { assertTrue("发送人没打标：${it.sender}", it.sender.contains("示例")) }
    }

    /** 至少有一条**带引用块**的消息：Telegram 气泡里的回复引用是这个版式的关键元素。 */
    @Test
    fun `至少有带引用块的消息`() {
        val messages = ClubChannelPreview.channelsFor("x").flatMap { ClubChannelPreview.messagesFor(it.id) }
        assertTrue("没有引用块，回复版式看不到", messages.any { it.replyTo != null })
        assertTrue("也不能全是引用块", messages.any { it.replyTo == null })
    }

    /** 过滤 chips：四种类型各能筛出自己那一类，`全部` 不过滤。 */
    @Test
    fun `频道过滤按类型生效`() {
        val channels = ClubChannelPreview.channelsFor("x")
        assertEquals(channels, filterChannels(channels, ChannelFilter.ALL))
        ChannelFilter.entries.filter { it != ChannelFilter.ALL }.forEach { f ->
            val got = filterChannels(channels, f)
            assertTrue("「${f.label}」筛不出任何频道，这个 chip 就是死的", got.isNotEmpty())
            assertTrue("「${f.label}」筛出了别的类型", got.all { it.kind.name == f.name })
        }
    }

    /** 过滤后不能丢频道：四个分类加起来必须等于全集。 */
    @Test
    fun `四类过滤加起来等于全部`() {
        val channels = ClubChannelPreview.channelsFor("x")
        val sum = ChannelFilter.entries
            .filter { it != ChannelFilter.ALL }
            .flatMap { filterChannels(channels, it) }
        assertEquals(channels.toSet(), sum.toSet())
    }

    /** 打标文案必须非空且各说各的（入口 / 列表标签 / 顶栏副标题 / 操作处）。 */
    @Test
    fun `三处打标文案都在`() {
        listOf(
            ClubChannelPreview.TAG,
            ClubChannelPreview.BANNER_TITLE,
            ClubChannelPreview.BANNER_BODY,
            ClubChannelPreview.MY_CLUBS_MISSING,
            ClubChannelPreview.AFFILIATION_MISSING,
            ClubChannelPreview.MESSAGES_MISSING,
            ClubChannelPreview.CHANNEL_SUBTITLE,
            ClubChannelPreview.TIME_SLOT,
        ).forEach { assertTrue("打标文案不能为空", it.isNotBlank()) }
        assertFalse("预览横幅必须点明这是本地预览", ClubChannelPreview.BANNER_TITLE.contains("实时"))
        // 副标题是截图里唯一保证会被拍进去的打标 —— 它必须自证是示例
        assertTrue("顶栏副标题必须写明这是示例", ClubChannelPreview.CHANNEL_SUBTITLE.contains("示例"))
        assertFalse("未接入消息时不许出现「实时连接已就绪」", ClubChannelPreview.MESSAGES_MISSING.contains("实时连接已就绪"))
    }

    /** 归属与核实状态：没核实就只能写「待确认」，且不许出现「学校官方认证」。 */
    @Test
    fun `归属与核实都如实写待确认`() {
        assertEquals("归属待确认", ClubChannelPreview.affiliationLabel())
        assertEquals("待确认", ClubChannelPreview.verificationLabel())
        listOf(ClubChannelPreview.affiliationLabel(), ClubChannelPreview.verificationLabel())
            .forEach { assertFalse("未获学校授权不许写官方认证：$it", it.contains("官方认证")) }
    }

    /** 预览社团的 id 必须带前缀，免得被当成真实 clubId 去请求接口。 */
    @Test
    fun `预览社团 id 带前缀`() {
        assertTrue(PREVIEW_CLUB_ID_PREFIX.isNotBlank())
        assertFalse("预览 id 不能长得像真 id", PREVIEW_CLUB_ID_PREFIX.startsWith("cl"))
    }
}
