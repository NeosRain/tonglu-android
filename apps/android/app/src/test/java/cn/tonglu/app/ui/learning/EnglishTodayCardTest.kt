package cn.tonglu.app.ui.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTodayCardTest {
    @Test fun remainingCountsActualWords() { assertEquals(13, englishGoalRemaining(7, 20)) }
    @Test fun completedGoalNeverNegative() { assertEquals(0, englishGoalRemaining(25, 20)) }
    @Test fun invalidGoalIsSafe() { assertEquals(1, englishGoalRemaining(0, 0)) }
    @Test fun invalidHistoryNeverIncreasesProgress() { assertEquals(20, englishGoalRemaining(-3, 20)) }
}
