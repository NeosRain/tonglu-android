package cn.tonglu.app.ui

import org.junit.Assert.*
import org.junit.Test

class ThemeModeTest {
    @Test fun `自动主题响应系统 手动主题保持固定`() {
        assertTrue(resolveDark(ThemeMode.System, true))
        assertFalse(resolveDark(ThemeMode.System, false))
        assertFalse(resolveDark(ThemeMode.Light, true))
        assertTrue(resolveDark(ThemeMode.Dark, false))
    }
    @Test fun `保留历史手动选择 新安装默认自动`() {
        assertEquals(ThemeMode.System, restoreThemeMode(null, null))
        assertEquals(ThemeMode.Dark, restoreThemeMode(null, true))
        assertEquals(ThemeMode.Light, restoreThemeMode(null, false))
        assertEquals(ThemeMode.System, restoreThemeMode("System", true))
        assertEquals(ThemeMode.System, restoreThemeMode("失效值", null))
    }
}
