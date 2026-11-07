package cn.tonglu.app.ui.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishHubNavigationTest {
    @Test fun nestedToolsReturnThroughTheirActualParents() {
        var path = englishHubStart("home")
        listOf("tools", "more", "plan").forEach { path = englishHubOpen(path, it) }
        assertEquals(listOf("home", "tools", "more", "plan"), path)
        path = englishHubBack(path, "plan")
        assertEquals("more", path.last())
        path = englishHubBack(path, "more")
        assertEquals("tools", path.last())
        assertEquals(listOf("home"), englishHubBack(path, "tools"))
    }

    @Test fun deepLinksHaveAnEnglishHomeToReturnTo() {
        val path = englishHubStart("listening")
        assertEquals(listOf("home", "listening"), path)
        assertEquals(listOf("home"), englishHubBack(path, "listening"))
    }

    @Test fun lateParentCallbackCannotCloseANewChild() {
        val path = listOf("home", "tools", "more", "daily")
        assertEquals(path, englishHubBack(path, "more"))
    }

    @Test fun repeatedDestinationsDoNotDuplicateWindows() {
        val path = listOf("home", "tools", "more", "daily")
        assertEquals(path, englishHubOpen(path, "daily"))
        assertEquals(listOf("home", "tools"), englishHubOpen(path, "tools"))
    }

    @Test fun unknownDeepLinkCannotLeaveAnInvisibleBackLayer() {
        assertEquals(listOf("home"), englishHubStart("unknown"))
        assertEquals(listOf("home"), englishHubOpen(listOf("home"), "unknown"))
    }
}
