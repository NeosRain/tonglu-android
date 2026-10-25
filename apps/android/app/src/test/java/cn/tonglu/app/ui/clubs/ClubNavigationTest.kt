package cn.tonglu.app.ui.clubs

import cn.tonglu.app.data.ClubDto
import org.junit.Assert.*
import org.junit.Test

class ClubNavigationTest {
    private val clubs = (1..20).map { ClubDto("club-$it", "社团$it", "介绍$it", "兴趣") }
    private fun target(index: Int) = clubs[index].let { ClubDetailTarget(it.id, it.name, it.category, it.description, true) }

    @Test fun `打开任意社团仍保留全部头像及原顺序`() {
        assertEquals(clubs.map { it.id }, clubRailTargets(clubs, target(13)).map { it.id })
    }

    @Test fun `切换社团不改变左栏成员`() {
        assertEquals(clubRailTargets(clubs, target(1)), clubRailTargets(clubs, target(18)))
    }

    @Test fun `示例入口包含所有示例而非只显示所选示例`() {
        previewClubTargets.forEach { selected ->
            assertEquals(previewClubTargets, clubRailTargets(emptyList(), selected))
        }
    }

    @Test fun `示例与真实社团同时存在时全部保留且不重复`() {
        val rail = clubRailTargets(clubs + clubs.first(), previewClubTargets.first())
        assertEquals(clubs.size + previewClubTargets.size, rail.size)
        assertEquals(clubs.map { it.id } + previewClubTargets.map { it.id }, rail.map { it.id })
    }

    @Test fun `已打开但接口暂时缺失的社团不会消失`() {
        val selected = target(5)
        val rail = clubRailTargets(emptyList(), selected)
        assertEquals(listOf(selected), rail)
        assertTrue(rail.all { it.real })
    }

    @Test fun `在示例工作区选中真实社团也不会移除示例头像`() {
        assertEquals(clubRailTargets(clubs, previewClubTargets.first(), true),
            clubRailTargets(clubs, target(4), true))
    }

    @Test fun `示例头像不全部显示示字且不会伪装真实社团`() {
        assertEquals(listOf("游", "院"), previewClubTargets.map { it.avatarLabel() })
        assertTrue(previewClubTargets.all { !it.real && it.id.startsWith(PREVIEW_CLUB_ID_PREFIX) && it.name.contains("示例") })
    }
}
