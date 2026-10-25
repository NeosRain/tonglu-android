package cn.tonglu.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import cn.tonglu.app.ui.theme.ThemeAccent
import cn.tonglu.app.ui.theme.ThemeCanvas

/** 全应用唯一的主题口径：手动选择或跟随系统，玻璃与页面始终同步。 */
object AppTheme {
    private const val PREF_FILE = "tonglu_settings"
    private const val PREF_KEY = "dark_theme"
    private const val MODE_KEY = "theme_mode"
    private var callbacksInstalled = false

    var darkState by mutableStateOf(false)
        private set
    var modeState by mutableStateOf(ThemeMode.System)
        private set

    private fun systemDark(context: android.content.Context): Boolean =
        context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun savedMode(context: android.content.Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
        return restoreThemeMode(prefs.getString(MODE_KEY, null),
            if (prefs.contains(PREF_KEY)) prefs.getBoolean(PREF_KEY, false) else null)
    }

    fun enabled(context: android.content.Context): Boolean = resolveDark(savedMode(context), systemDark(context))

    /** 启动前同步，配置变化时也重算；无需改动 Activity 的主题主体。 */
    fun syncFrom(context: android.content.Context): Boolean {
        modeState = savedMode(context)
        darkState = resolveDark(modeState, systemDark(context))
        if (!callbacksInstalled) {
            val app = context.applicationContext
            app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
                override fun onConfigurationChanged(config: android.content.res.Configuration) {
                    val night = config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    darkState = resolveDark(modeState, night)
                }
                override fun onLowMemory() = Unit
            })
            callbacksInstalled = true
        }
        return darkState
    }

    fun setMode(context: android.content.Context, mode: ThemeMode) {
        context.getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
            .edit().putString(MODE_KEY, mode.name).apply()
        modeState = mode
        darkState = resolveDark(mode, systemDark(context))
    }

    /** 兼容旧开关调用，只影响手动模式。 */
    fun setEnabled(context: android.content.Context, value: Boolean) =
        setMode(context, if (value) ThemeMode.Dark else ThemeMode.Light)
}

/** Keep the exact legacy light value while resolving the dark semantic colour at read time. */
internal fun themeColor(light: Color, dark: Color, isDark: Boolean = AppTheme.darkState): Color =
    if (isDark) dark else light

/** Notice backgrounds and their ink must adapt together; neither changes in light mode. */
internal fun noticeBackground(light: Color, isDark: Boolean = AppTheme.darkState): Color =
    if (isDark) lerp(light, Color(0xFF15171C), 0.84f) else light

internal fun noticeForeground(light: Color, isDark: Boolean = AppTheme.darkState): Color =
    if (isDark) lerp(light, AppColors.OnAccent, 0.62f) else light

/**
 * 深色主题的语义色值。抽成 internal 对象，是为了让 [ThemeColorTest] 能断言**真实使用值**的
 * WCAG 对比度 —— 色值抄进测试是钉不住的（改了源码测试照样绿），必须引用同一份。
 *
 * 2026-10-10 由 `work/sweep67/theme_contrast.py` 查出深色 `OnAccent/Accent = 2.76:1`（需 ≥4.5，
 * BUGS A31），修法 = 加深 [Accent]；深色强调色因此受**双向约束**：白字 ≥4.5 要求亮度 ≤0.183，
 * 压深底 ≥3.0 要求亮度 ≥0.135 —— 可行区间很窄，改值必须先跑 ThemeColorTest。
 */
internal object DarkPalette {
    /** 整页底色。 */
    val Canvas: Color = Color(0xFF15171C)

    /** 卡片/面板底。 */
    val Surface: Color = Color(0xFF1D2027)

    /** 未选中态胶囊/标签的底。 */
    val ChipIdle: Color = Color(0xFF272B33)

    /**
     * 深色强调色：`#6E9BF0`（白字仅 2.76:1）→ `#4173CC`。
     * 现值对比度：白字 4.62 / Canvas 3.88 / Surface 3.53 / ChipIdle 3.07（ThemeColorTest 钉住）。
     * 代价：Accent **当文字/图标**用时对比度从 6.5 降到 3.9 —— 仍过项目审计阈值 3.0；
     * 若以后嫌深，正确方向是拆「容器 Accent / 内容 AccentInk」双槽，不是把这一个值再调浅。
     */
    val Accent: Color = Color(0xFF4173CC)

    val Danger: Color = Color(0xFFE06C6C)
    val TextPrimary: Color = Color(0xFFEDEFF3)
    val TextSecondary: Color = Color(0xFFA8AEBA)
    val TextMuted: Color = Color(0xFF8A919D)
    val NavInactive: Color = Color(0xFF9AA1AC)
    val Divider: Color = Color(0xFF2A2F38)
}

/**
 * 语义色 —— **浅色/深色成对**，全 App 的背景与文字都从这里取。
 *
 * ## 为什么要这么一层（2026-10-08 上一轮翻车的教训）
 * 之前只把 `MainActivity` 的 colorScheme 切成 `darkColorScheme` 就上机了，结果：
 * **卡片黑了、页面底还是浅灰、标题在白底上几乎看不见**（证据
 * `work/regression/shots/2026-10-08/dark_3_home.png`）。
 * 真因是全 App 的配色是**写死的浅色**，其中最要命的是 `Scaffold(containerColor = Canvas)`。
 *
 * ## 一条铁律
 * `Color.White` 在本项目里**既当底色又当前景**（未选中的分类胶囊是白底，而压在 Accent 上的字也是白，
 * 课程块的节次数字压在深饱和课程色上也是白，扫码面板干脆拿它当整块白底）——
 * **同一个字面量多种语义** —— 所以按用途分成 [Surface]/[ChipIdle]（底色）与 [OnAccent]（前景），
 * **不许**再全局替换 `Color.White`。也因此 OnAccent 恒白是架构决定，深色下压不住 Accent 时
 * 要动的是 Accent（见 [DarkPalette.Accent]），不是 OnAccent。
 *
 * 这些是普通 `get()`（读的是 Compose 状态）：在 composable 里读会自动重组，
 * 不需要 `@Composable`，因此原来那些 `private val` 常量可以直接转发到它，改动面最小。
 */
object AppColors {

    private val dark: Boolean get() = AppTheme.darkState

    /**
     * 默认色锚点。
     *
     * `scripts/audit_theme_contrast.py` 用正则从 `AppColors.kt` 解析**真实槽值**，
     * 所以这里必须保留原来的 `if (dark) DarkPalette.X else Color(0x...)` 写法。
     * 运行时暴露的 [Canvas]/[Accent] 在「用户没选自定义色 / 没设壁纸」时原样转发这里，
     * 默认路径与改版前逐字节一致（审计与 ThemeColorTest 的默认值不变证明）。
     */
    internal object Defaults {
        /** 整页底色默认值。 */
        val Canvas: Color get() = if (dark) DarkPalette.Canvas else Color(0xFFF5F6F8)

        /**
         * 卡片/面板默认底（不透明）。
         *
         * 保留 `if (dark) DarkPalette.X else Color.White` 这个**字面形状**是硬要求：
         * `scripts/audit_theme_contrast.py` 用正则从这里解析真实槽值，写成函数调用会
         * 让审计「槽解析不全」直接报红（那不是在拦 bug，是在拦格式）。
         */
        val Surface: Color get() = if (dark) DarkPalette.Surface else Color.White

        /** 默认品牌蓝：浅色 #2563EB / 深色 #4173CC。 */
        val Accent: Color get() = if (dark) DarkPalette.Accent else Color(0xFF2563EB)
    }

    /** 不随壁纸透明化的页面底色：壁纸宿主垫底、预览面板回退用。 */
    val CanvasOpaque: Color get() = Defaults.Canvas

    /**
     * 整页底色（= `Scaffold(containerColor)`）。上一轮就是漏了这个。
     * 有壁纸时按 [ThemeCanvas] 留出半透明，根层壁纸才能透出来；没壁纸时原值不变。
     */
    val Canvas: Color get() = ThemeCanvas.resolve(Defaults.Canvas)

    /**
     * 卡片/面板底。
     * - 没壁纸：与改版前**逐字节一致**（浅色纯白 / 深色 `#1D2027`）；
     * - 有壁纸：降一点 alpha 变成磨砂玻璃（见 [ThemeCanvas.resolveCard]）。
     *
     * 🔴 **整屏容器不要用它**：全屏页/弹层/吸顶栏要 [SurfaceOpaque]，否则壁纸会透上来，
     * 正文压在照片上（S175 的 MyReading 透底就是这一类）。卡片才是这里的目标。
     */
    val Surface: Color get() = ThemeCanvas.resolveCard(Defaults.Surface, dark)

    /** 不透明卡片底：全屏页、弹层、顶栏/底栏、底部抽屉用，别让壁纸透上来。 */
    val SurfaceOpaque: Color get() = Defaults.Surface

    /** 未选中态胶囊/标签的底（浅色下是白，夜里必须变深，否则白底浅字）。 */
    val ChipIdle: Color get() = if (dark) DarkPalette.ChipIdle else Color.White

    /**
     * 强调色（浅色）。
     *
     * 2026-10-11 从 `#3978E8` 加深到 `#2563EB`：**白字压在强调色上只有 4.18:1**
     * （按钮 / 选中胶囊 / 徽章的 `OnAccent` 恒白），低于正文 4.5:1。深一档后
     * 白字 5.17、当文字压在页面底 4.78、压在深色卡片 3.47 —— 三条都过。
     * 判据见 `scripts/audit_theme_contrast.py`（浅色 OnAccent/Accent）；
     * `ThemeColorTest` 也钉了同一个数。**改这一个值要跟着改全仓所有品牌蓝复制点**
     * （`scripts/audit_brand_color.py` 会拦住漏改的）。
     *
     * 2026-10-18 起真正暴露的是**用户主题色**：没选自定义色时 [ThemeAccent.resolve]
     * 原样返回 [Defaults.Accent]；选了才按白字 ≥4.5 / 深色底 ≥3.0 自动校正亮度。
     */
    val Accent: Color get() = ThemeAccent.resolve(Defaults.Accent, dark)

    /**
     * 开屏专用的品牌装饰色：**白天薄荷、晚上深海**（2026-10-16 用户「适配夜间模式」）。
     *
     * ## 为什么不直接改 [Accent]
     * 用户要的「白天薄荷 / 晚上深海」如果落到 [Accent] 上，会同时踩两个雷：
     * ① [OnAccent] 是**恒白**（架构决定，见上面那段铁律），而白字压在薄荷上只有
     *    **1.47:1**（薄荷是亮色！实测 work/icon-recolor-20261016/），全站按钮、
     *    选中胶囊、徽章上的文字会一起变得读不出来；
     * ② [Accent] 被 `scripts/audit_brand_color.py` 的 16 处复制点钉住，改一处要改十六处。
     * 这一条只服务开屏的玻璃墙底色、光晕与波峰描边（大面积装饰，不承载文字），
     * 因此可以放心用亮色，也不必动品牌蓝。
     *
     * ## 取值依据（都按最不利端实测，不用平均值）
     * 日 #4FCBA6 = 图标薄荷渐变的暗端（与图标同色系）；
     * 夜 #3E8FC4 = 深海蓝，压在深色 Canvas `#15171C` 上 5.08:1 —— 深海底 `#061725`
     * 那种深度在深色底上等于看不见，所以夜里取的是「深海里的亮档」而不是最深那档。
     */
    val StartupAccent: Color get() = if (dark) Color(0xFF3E8FC4) else Color(0xFF4FCBA6)

    /** 压在 [Accent] 上的前景：**两套主题都要白**。 */
    val OnAccent: Color get() = Color.White

    val Danger: Color get() = if (dark) DarkPalette.Danger else Color(0xFFD14343)

    val TextPrimary: Color get() = if (dark) DarkPalette.TextPrimary else Color(0xFF17181C)

    val TextSecondary: Color get() = if (dark) DarkPalette.TextSecondary else Color(0xFF3E4247)

    val TextMuted: Color get() = if (dark) DarkPalette.TextMuted else Color(0xFF858B96)

    val NavInactive: Color get() = if (dark) DarkPalette.NavInactive else Color(0xFF535B66)

    val Divider: Color get() = if (dark) DarkPalette.Divider else Color(0xFFE6E8EC)
}
