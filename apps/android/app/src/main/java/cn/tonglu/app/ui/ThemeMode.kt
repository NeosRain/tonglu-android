package cn.tonglu.app.ui

enum class ThemeMode(val label: String) {
    System("跟随系统"), Light("浅色"), Dark("深色")
}

internal fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** 已存在的手动选择保留；没有历史设置的安装默认跟随系统。 */
internal fun restoreThemeMode(stored: String?, legacy: Boolean?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == stored }
        ?: legacy?.let { if (it) ThemeMode.Dark else ThemeMode.Light }
        ?: ThemeMode.System
