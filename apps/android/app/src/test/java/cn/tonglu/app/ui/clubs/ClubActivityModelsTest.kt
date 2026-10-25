package cn.tonglu.app.ui.clubs

import cn.tonglu.app.data.ClubActivityDto
import cn.tonglu.app.data.ClubRsvpMapDto
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动页与横幅的规则（第二件，2026-10-12）。
 *
 * ## 这些断言在防什么
 * 活动这块最容易犯的错不是崩溃，是**把服务端没说的事说出来**：
 * - 给活动编一个「已取消」（`ClubActivity` 根本没有取消字段，被取消的活动在列表里直接不出现）；
 * - 时间串坏了就说「尚未开始」（那是一个断言，真实情况只是「解析不出来」）；
 * - 认不出的报名状态当成「已报名」；
 * - 系统开了「减少动画」还继续转横幅。
 *
 * 每一条都写成可执行判据：故意改坏就变红。
 */
class ClubActivityModelsTest {

    // ---------------------------------------------------------------- 阶段判定

    /** 2026-10-12 14:00 UTC。 */
    private val start = "2026-10-12T14:00:00.000Z"
    private val startMs = activityEpochMillis(start)!!

    @Test
    fun `开始之前是尚未开始`() {
        assertEquals(ActivityPhase.UPCOMING, activityPhase(start, null, startMs - 1))
    }

    @Test
    fun `正好到开始时刻就是进行中（不是尚未开始）`() {
        assertEquals(ActivityPhase.ONGOING, activityPhase(start, null, startMs))
    }

    @Test
    fun `有结束时间且已过就是已结束`() {
        val end = "2026-10-12T16:00:00.000Z"
        val endMs = activityEpochMillis(end)!!
        // 区间是左闭右开：end 前 1ms 还是进行中，正好到 end 就是已结束。
        assertEquals(ActivityPhase.ONGOING, activityPhase(start, end, endMs - 1))
        assertEquals("正好到结束时刻就该显示已结束", ActivityPhase.ENDED, activityPhase(start, end, endMs))
        assertEquals(ActivityPhase.ENDED, activityPhase(start, end, endMs + 1))
    }

    @Test
    fun `没有结束时间时开始之后一直算进行中，不替主办方猜结束`() {
        // 编一个「大概开一天就结束了」= 编数据：服务端只给了 startAt。
        assertEquals(ActivityPhase.ONGOING, activityPhase(start, null, startMs + 30L * 24 * 3600 * 1000))
    }

    @Test
    fun `开始时间解析不出来时如实说时间待确认，不塞进尚未开始`() {
        // 「尚未开始」是一个**断言**（说明它一定在未来）；解析失败时我们什么都不知道。
        assertEquals(ActivityPhase.UNKNOWN, activityPhase("not-a-time", null, startMs))
        assertEquals(ActivityPhase.UNKNOWN, activityPhase("", "2026-10-12T16:00:00.000Z", startMs))
    }

    @Test
    fun `结束时间坏掉不影响开始时间可用——但仍按进行中处理而不是已结束`() {
        assertEquals(ActivityPhase.ONGOING, activityPhase(start, "garbage", startMs + 3600_000))
    }

    @Test
    fun `活动阶段里不许出现已取消——服务端没有这个事实`() {
        // 服务端 `listActivities` 的 where 已经把「未上架 / 机审未过」的活动过滤掉，
        // 也就是说「被取消 / 被下线」的活动**根本不会出现在响应里**。
        // 谁要是加一个 CANCELLED，界面就会开始显示一个它无从得知的状态。
        val names = ActivityPhase.values().map { it.name }.toSet()
        assertEquals(setOf("UPCOMING", "ONGOING", "ENDED", "UNKNOWN"), names)
    }

    // ---------------------------------------------------------------- 时间文案

    @Test
    fun `同一天的时间区间省略第二个日期`() {
        // 🔴 时间要**按本机时区**造，不能用固定 UTC 串：14:00Z 在 UTC 机器上还是同一天，
        // 在东八区却是 22:00 —— 拿固定串写「同一天」的断言会在某些机器上假失败。
        val label = activityTimeLabel(isoLocal(2026, 10, 12, 14, 0), isoLocal(2026, 10, 12, 16, 0))
        assertEquals("同一天应只出现一次日期：$label", 2, label.count { it == '-' })
        assertTrue("应包含起止：$label", label.contains("—"))
    }

    @Test
    fun `跨天的时间区间两端都带日期`() {
        val label = activityTimeLabel(isoLocal(2026, 10, 12, 22, 0), isoLocal(2026, 10, 13, 6, 0))
        assertEquals("跨天应出现两个日期：$label", 4, label.count { it == '-' })
    }

    @Test
    fun `开始时间坏了就不显示时间位，不编一个时间`() {
        assertEquals("", activityTimeLabel("garbage", null))
        assertEquals("", formatActivityTime(""))
    }

    @Test
    fun `只有开始时间时只显示开始`() {
        assertEquals(formatActivityTime(start), activityTimeLabel(start, null))
    }

    // ---------------------------------------------------------------- 报名状态

    @Test
    fun `认得出的报名状态各自成立`() {
        assertEquals(RsvpState.GOING, rsvpStateOf("GOING"))
        assertEquals(RsvpState.INTERESTED, rsvpStateOf("INTERESTED"))
    }

    @Test
    fun `认不出的报名状态一律按未报名——不猜成已报名`() {
        // 「猜成已报名」比「猜成未报名」坏得多：用户会以为自己报上了、当天不去。
        assertEquals(RsvpState.NONE, rsvpStateOf(null))
        assertEquals(RsvpState.NONE, rsvpStateOf(""))
        assertEquals(RsvpState.NONE, rsvpStateOf("going"))   // 大小写不对也不认
        assertEquals(RsvpState.NONE, rsvpStateOf("MAYBE"))
    }

    @Test
    fun `取消报名在服务端返回 CANCEL，客户端要落回未报名`() {
        // 服务端 rsvp(status='CANCEL') 返回 { status: 'CANCEL' }。
        assertEquals(RsvpState.NONE, rsvpStateOf("CANCEL"))
    }

    // ---------------------------------------------------------------- 横幅轮播

    @Test
    fun `只有一项时横幅不推进`() {
        assertEquals(0, nextBannerIndex(0, 1, animationsDisabled = false))
        assertEquals(0, nextBannerIndex(0, 0, animationsDisabled = false))
    }

    @Test
    fun `系统减少动画时永不自动推进`() {
        assertEquals(0, nextBannerIndex(0, 3, animationsDisabled = true))
        assertEquals(2, nextBannerIndex(2, 3, animationsDisabled = true))
    }

    @Test
    fun `正常轮播逐项前进并回绕`() {
        assertEquals(1, nextBannerIndex(0, 3, animationsDisabled = false))
        assertEquals(2, nextBannerIndex(1, 3, animationsDisabled = false))
        assertEquals(0, nextBannerIndex(2, 3, animationsDisabled = false))
    }

    @Test
    fun `横幅只在真的有活动时出现——空列表不画空横幅`() {
        // null（没拉到）走的是「不画」这条路：空横幅会让用户以为「社团一个活动都没有」。
        // 空态与失败态是两件事，UI 层对「没拉到」另有失败说明与重试入口。
        assertFalse(shouldShowActivityBanner(emptyList()))
        assertTrue(shouldShowActivityBanner(listOf(activity())))
    }

    // ---------------------------------------------------------------- 文案与字段

    @Test
    fun `地点为空时这一行整条不显示，不写地点待定`() {
        // 「地点：待定」是替主办方下结论（也许地点就在社团活动室、只是没填）。
        assertNull(activityPlaceLabel(null))
        assertNull(activityPlaceLabel(""))
        assertNull(activityPlaceLabel("   "))
        assertEquals("体育馆", activityPlaceLabel("  体育馆 "))
    }

    @Test
    fun `参与人数是报名与感兴趣的合并计数，不许写成报名人数`() {
        // 服务端 `_count.rsvps` 数的是 ClubActivityRsvp 的**全部行**，而 GOING 与 INTERESTED
        // 共用一张表（rsvp() 两种状态走同一个 upsert）→ 这个数不是「报名人数」。
        assertEquals("0 人报名或感兴趣", activityGoingLabel(0))
        assertEquals("12 人报名或感兴趣", activityGoingLabel(12))
        assertFalse("不许把合并计数说成报名人数", activityGoingLabel(12).contains("人报名 ·")
            || activityGoingLabel(12).endsWith("人报名"))
    }

    @Test
    fun `报名名单说明必须点出缺的是接口，并说明人数是合并计数`() {
        assertTrue(RSVP_ROSTER_PENDING.contains("接口"))
        assertTrue(RSVP_ROSTER_PENDING.contains("人数"))
        assertTrue("要说明合并计数，否则用户会以为这就是报名人数", RSVP_ROSTER_PENDING.contains("合并"))
    }

    @Test
    fun `空活动文案区分负责人与普通成员`() {
        // 负责人看得到「你可以发起」，普通成员看到的是说明而非一个他点不动的假按钮。
        assertTrue(ACTIVITY_EMPTY_FOR_OWNER.contains("负责人"))
        assertFalse(ACTIVITY_EMPTY_FOR_MEMBER.contains("负责人"))
        assertFalse("普通成员文案不许出现可操作入口的错觉", ACTIVITY_EMPTY_FOR_MEMBER.contains("你可以"))
    }

    // ---------------------------------------------------------------- 分类：活动是一条真实通道

    @Test
    fun `活动分类可用，且明确它走的是活动线而不是频道`() {
        assertTrue(ChannelCategory.ACTIVITY.hasRealSupport())
        assertTrue(ChannelCategory.ACTIVITY.isActivityLane())
        // 真频道只有两种 kind：活动**没有** kind，别把它混进频道筛选里。
        assertEquals(setOf("ANNOUNCEMENT", "DISCUSSION"), SERVER_CHANNEL_KINDS)
        assertNull(channelCategoryOfKind("ACTIVITY"))
    }

    @Test
    fun `未支持的三个分类仍然置灰`() {
        assertFalse(ChannelCategory.COHORT.hasRealSupport())
        assertFalse(ChannelCategory.BOT.hasRealSupport())
        assertFalse(ChannelCategory.RESOURCE.hasRealSupport())
        assertFalse(ChannelCategory.COHORT.isActivityLane())
    }

    @Test
    fun `未支持说明不再把活动算进去`() {
        assertTrue(CATEGORY_UNSUPPORTED_HINT.contains("年级"))
        assertTrue("要明确「活动不是频道」，否则用户会去频道里找活动",
            CATEGORY_UNSUPPORTED_HINT.contains("「活动」不是频道"))
        // 反向陷阱：谁把活动重新塞回「需要服务端提供」的清单里，这一条就红。
        assertFalse(CATEGORY_UNSUPPORTED_HINT.contains("资源 / 活动"))
    }

    // ---------------------------------------------------------------- 与服务端的字段名对齐

    @Test
    fun `活动响应按服务端真实字段反序列化（字段改名会在这里变红）`() {
        // 取自 clubs.service.ts listActivities 的返回结构；多给一个 _count 是为了证明
        // 客户端**不依赖**它（服务端已经把 _count.rsvps 折算成 going）。
        val json = """
            {"id":"act1","clubId":"c1","clubName":"摄影社","clubCategory":"兴趣",
             "postId":"p1","title":"秋日外拍","startAt":"2026-10-12T14:00:00.000Z",
             "endAt":"2026-10-12T16:00:00.000Z","location":"南校区","createdAt":"2026-10-01T00:00:00.000Z",
             "going":7,"_count":{"rsvps":7}}
        """.trimIndent()
        val dto = Gson().fromJson(json, ClubActivityDto::class.java)
        assertEquals("act1", dto.id)
        assertEquals("c1", dto.clubId)
        assertEquals("摄影社", dto.clubName)
        assertEquals("p1", dto.postId)
        assertEquals("秋日外拍", dto.title)
        assertEquals("南校区", dto.location)
        assertEquals(7, dto.going)
    }

    @Test
    fun `我的报名状态响应是 map 包裹的（不是裸对象）`() {
        val dto = Gson().fromJson("""{"map":{"a1":"GOING","a2":"INTERESTED"}}""", ClubRsvpMapDto::class.java)
        assertEquals(mapOf("a1" to "GOING", "a2" to "INTERESTED"), dto.map)
        assertEquals(RsvpState.GOING, rsvpStateOf(dto.map["a1"]))
    }

    @Test
    fun `缺少可选字段时活动仍能渲染成基本形状`() {
        // endAt / location / clubName 都可能缺：不能让缺一个可空字段就整页崩。
        val json = """{"id":"a","clubId":"c","postId":"p","title":"t","startAt":"2026-10-12T14:00:00.000Z"}"""
        val dto = Gson().fromJson(json, ClubActivityDto::class.java)
        assertNull(dto.endAt)
        assertNull(dto.location)
        assertNull(dto.clubName)
        assertEquals(0, dto.going)
        assertEquals(ActivityPhase.ONGOING, activityPhase(dto.startAt, dto.endAt, startMs + 1000))
    }

    // ---------------------------------------------------------------- 工具

    private fun activity() = ClubActivityDto(id = "a1", clubId = "c1", postId = "p1", title = "秋日外拍", startAt = start)

    /** 按**本机时区**造一个 ISO 串（时间文案的断言不能依赖机器时区）。 */
    private fun isoLocal(year: Int, month: Int, day: Int, hour: Int, minute: Int): String =
        java.time.ZonedDateTime.of(year, month, day, hour, minute, 0, 0, java.time.ZoneId.systemDefault())
            .toInstant().toString()
}
