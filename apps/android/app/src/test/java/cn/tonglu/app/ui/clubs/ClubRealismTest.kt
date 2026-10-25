package cn.tonglu.app.ui.clubs

import cn.tonglu.app.data.ClubDto
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「现实社团」这一轮的新规则（2026-10-12）。
 *
 * ## 这些断言在防什么
 * 社团页最危险的不是崩溃，而是**把不知道的事说成知道的事**：
 * - 没拉到成员关系 → 画一个「加入」按钮给已加入的人（他点下去服务端说「已是成员」）；
 * - 接口 500 → 显示「暂时没有社团」（用户以为平台上一个社团都没有）；
 * - 后端没有的分类 → 按频道标题猜（叫「2024级」就当年级频道）。
 *
 * 每一条都写成**可执行**的判据，故意改坏就变红。
 */
class ClubRealismTest {

    // ---------------------------------------------------------------- 我的社团身份

    private val snapshot = ClubMembershipSnapshot(
        myIds = setOf("joined"), ownerIds = setOf("mine"), pendingIds = setOf("pending"), loaded = true,
    )

    @Test
    fun `我创建的优先于已加入`() {
        // 创建者本身也是 OWNER 成员：两个集合都命中时必须报「我创建的」，
        // 否则负责人会在自己社群里看到「已加入」而不是管理身份。
        assertEquals(ClubMembership.OWNER, snapshot.of("mine"))
    }

    @Test
    fun `已加入与申请中各自成立`() {
        assertEquals(ClubMembership.JOINED, snapshot.of("joined"))
        assertEquals(ClubMembership.PENDING, snapshot.of("pending"))
    }

    @Test
    fun `都不命中才是未加入`() {
        assertEquals(ClubMembership.NONE, snapshot.of("stranger"))
    }

    @Test
    fun `没拉到成员关系时一律状态未知，不许猜成未加入`() {
        val unknown = ClubMembershipSnapshot(loaded = false)
        assertEquals(ClubMembership.UNKNOWN, unknown.of("anything"))
        // 即使 id 恰好在集合里（比如旧快照残留），loaded=false 时也不能当真
        val staleButNotLoaded = ClubMembershipSnapshot(myIds = setOf("x"), ownerIds = setOf("y"), loaded = false)
        assertEquals(ClubMembership.UNKNOWN, staleButNotLoaded.of("x"))
        assertEquals(ClubMembership.UNKNOWN, staleButNotLoaded.of("y"))
    }

    @Test
    fun `空集合加已加载才是真的一个都没加入`() {
        assertEquals(ClubMembership.NONE, ClubMembershipSnapshot(loaded = true).of("any"))
    }

    // ---------------------------------------------------------------- 归属与成员数

    @Test
    fun `未绑定学校只能说归属待确认，不许反推成校外`() {
        assertEquals("归属待确认", affiliationLabel(null))
        assertEquals("归属待确认", affiliationLabel(""))
        assertEquals("归属待确认", affiliationLabel("   "))
        assertFalse("不许把未绑定说成校外", affiliationLabel(null).contains("校外"))
    }

    @Test
    fun `绑定学校的社团显示校内`() {
        assertEquals("校内社团", affiliationLabel("school-1"))
    }

    @Test
    fun `成员数按真实数字显示，零成员也是真实结论`() {
        assertEquals("0 名成员", memberCountLabel(0))
        assertEquals("12 名成员", memberCountLabel(12))
    }

    // ---------------------------------------------------------------- 频道一级分类

    @Test
    fun `只按 kind 映射分类，绝不按频道名猜`() {
        assertEquals(ChannelCategory.ANNOUNCEMENT, channelCategoryOfKind("ANNOUNCEMENT"))
        assertEquals(ChannelCategory.DISCUSSION, channelCategoryOfKind("DISCUSSION"))
        // 「年级」这种只有名字能看出来的，不许猜：未知 kind 归 null（只在「全部」里显示）
        assertNull(channelCategoryOfKind("2024级"))
        assertNull(channelCategoryOfKind("COHORT"))
        assertNull(channelCategoryOfKind(""))
    }

    @Test
    fun `真实数据只支持服务端已实现的分类`() {
        assertTrue(ChannelCategory.ALL.hasRealSupport())
        assertTrue(ChannelCategory.ANNOUNCEMENT.hasRealSupport())
        assertTrue(ChannelCategory.DISCUSSION.hasRealSupport())
        // 这三类后端还没有对应 kind —— 界面必须置灰，不能给「点了看到空列表」的错觉
        listOf(ChannelCategory.COHORT, ChannelCategory.BOT, ChannelCategory.RESOURCE)
            .forEach { assertFalse("${it.label} 后端还没有 kind，不该显示为可用", it.hasRealSupport()) }
    }

    @Test
    fun `活动可用但不是频道——走活动线而不是 kind 筛选`() {
        // 2026-10-12 第二件：活动有了真实接口（`GET clubs/:id/activities`，活动即帖子），
        // 所以它**可点**；但它**没有 kind** —— 点开显示的是活动列表，不是按 kind 筛出来的频道。
        // 两句话必须同时成立：少第一句 = 把可用功能置灰；少第二句 = 把活动当频道猜类型。
        assertTrue(ChannelCategory.ACTIVITY.hasRealSupport())
        assertTrue(ChannelCategory.ACTIVITY.isActivityLane())
        assertNull("活动不是频道的一种，不许有 kind 映射", channelCategoryOfKind("ACTIVITY"))
        assertFalse("活动不该进频道 kind 清单", SERVER_CHANNEL_KINDS.contains("ACTIVITY"))
    }

    @Test
    fun `服务端 kind 清单与映射表一致`() {
        // 加 kind 时漏改任一处都会让界面永远置灰（或永远点不动），这里钉住一致性。
        SERVER_CHANNEL_KINDS.forEach { kind ->
            assertTrue("kind=$kind 没有分类映射", channelCategoryOfKind(kind) != null)
            assertTrue("kind=$kind 不在可用分类里", channelCategoryOfKind(kind)!!.hasRealSupport())
        }
        assertEquals(2, SERVER_CHANNEL_KINDS.size)
    }

    private fun channel(id: String, kind: String) = cn.tonglu.app.data.ClubChannelDto(
        id = id, name = id, kind = kind, createdAt = "2026-10-12T00:00:00.000Z",
    )

    @Test
    fun `按分类筛频道，全部不丢任何一类`() {
        val channels = listOf(
            channel("a", "ANNOUNCEMENT"), channel("b", "DISCUSSION"),
            channel("c", "DISCUSSION"), channel("d", "SOMETHING_NEW"),
        )
        assertEquals(channels, filterRealChannels(channels, ChannelCategory.ALL))
        assertEquals(listOf("a"), filterRealChannels(channels, ChannelCategory.ANNOUNCEMENT).map { it.id })
        assertEquals(listOf("b", "c"), filterRealChannels(channels, ChannelCategory.DISCUSSION).map { it.id })
        // 未知 kind 不塞进任何分类（塞错比不显示更坏），但仍留在「全部」里
        assertTrue(filterRealChannels(channels, ChannelCategory.BOT).isEmpty())
    }

    @Test
    fun `未支持分类的说明写清是服务端还没有，不是没人建`() {
        assertTrue(CATEGORY_UNSUPPORTED_HINT.contains("服务端"))
        assertFalse("不能让人以为是自己建一下就行", CATEGORY_UNSUPPORTED_HINT.contains("还没有人建"))
    }

    // ---------------------------------------------------------------- DTO 与真实字段

    @Test
    fun `ClubDto 能读出服务端的 _count 成员数`() {
        // 🔴 这条防的是「字段名带下划线，只能靠 @SerializedName 映射」：
        // 注解写错时 Gson **不报错**，只是静静给 0 —— 界面显示「0 名成员」而实际有 30 人。
        val json = """{"id":"c1","name":"跑步社","description":"约跑","category":"体育",""" +
            """"ownerId":"u1","schoolId":null,"_count":{"members":30}}"""
        val dto = Gson().fromJson(json, ClubDto::class.java)
        assertEquals(30, dto.membersCount)
        assertEquals("u1", dto.ownerId)
        assertNull(dto.schoolId)
    }

    @Test
    fun `没有 _count 时成员数为零而不是崩`() {
        val dto = Gson().fromJson("""{"id":"c1","name":"n","description":"d","category":"x"}""", ClubDto::class.java)
        assertEquals(0, dto.membersCount)
        assertNull(dto.ownerId)
    }

    @Test
    fun `真实社团转界面模型时带上全部真实字段`() {
        val json = """{"id":"c1","name":"跑步社","description":"约跑","category":"体育",""" +
            """"ownerId":"u1","schoolId":"s1","_count":{"members":7}}"""
        val target = Gson().fromJson(json, ClubDto::class.java).toTarget()
        assertTrue(target.real)
        assertEquals("u1", target.ownerId)
        assertEquals("s1", target.schoolId)
        assertEquals(7, target.membersCount)
        assertEquals("校内社团", affiliationLabel(target.schoolId))
    }

    // ---------------------------------------------------------------- 头像轨道

    private val roster = listOf(
        ClubDto("a", "跑步社", "约跑", "体育"),
        ClubDto("b", "读书会", "读书", "文学"),
        ClubDto("c", "机器人社", "做机器人", "科技"),
    )

    @Test
    fun `轨道只放已加入的社团`() {
        val membership = ClubMembershipSnapshot(myIds = setOf("a", "c"), loaded = true)
        assertEquals(listOf("a", "c"), railCandidates(roster, membership).map { it.id })
    }

    @Test
    fun `真没加入任何社团时轨道为空`() {
        assertEquals(emptyList<String>(), railCandidates(roster, ClubMembershipSnapshot(loaded = true)).map { it.id })
    }

    @Test
    fun `拿不到我的关系时不过滤，退化成展示全部`() {
        // 方向很重要：不确定时放宽（都在）而不是收紧（轨道空掉看起来像「我的社团没了」）。
        val unknown = ClubMembershipSnapshot(loaded = false)
        assertEquals(listOf("a", "b", "c"), railCandidates(roster, unknown).map { it.id })
    }

    @Test
    fun `我在别的社团的关系不会漏掉当前这个已加入的`() {
        // 轨道切换不串：当前选中的社团若确实已加入，必须仍在候选里。
        val membership = ClubMembershipSnapshot(myIds = setOf("b"), ownerIds = setOf("b"), loaded = true)
        assertTrue(railCandidates(roster, membership).any { it.id == "b" })
    }

    /** 预览示例必须不带任何真实字段 —— 否则「本地示例」会长出真的成员数和归属。 */
    @Test
    fun `预览示例不带真实成员数与归属`() {
        previewClubTargets.forEach {
            assertFalse(it.real)
            assertEquals(0, it.membersCount)
            assertNull(it.schoolId)
            assertNull(it.ownerId)
        }
    }

    /** 成员名单还没接口：文案必须说「需要服务端接口」，不能暗示「社团里没人」。 */
    @Test
    fun `成员名单待接口的说明不含误导`() {
        assertTrue(MEMBERS_ROSTER_PENDING.contains("成员接口"))
        assertFalse(MEMBERS_ROSTER_PENDING.contains("暂无成员"))
    }

    /** 认证材料只给本人与审核者 —— 这条是隐私红线，文案必须明说。 */
    @Test
    fun `认证材料可见范围写明`() {
        assertTrue(VERIFICATION_PRIVACY.contains("本人"))
        assertTrue(VERIFICATION_PRIVACY.contains("审核"))
    }
}
