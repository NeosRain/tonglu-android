package cn.tonglu.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 学习通必须进入校级平台，不能误指向其他学校或代登录入口。 */
class CampusLearningEntryTest {
    @Test fun 学习通属于校园入口且默认显示() {
        val entry = requireNotNull(CampusServices.byId("chaoxing"))
        assertEquals(ServiceGroup.CAMPUS, entry.group)
        assertEquals("https://lnldxy.fanya.chaoxing.com/", entry.url)
        assertTrue(entry.defaultVisible)
        assertFalse(entry.desktopMode)
        assertTrue(CampusServices.shareText().contains("学习通：${entry.url}"))
    }
}
