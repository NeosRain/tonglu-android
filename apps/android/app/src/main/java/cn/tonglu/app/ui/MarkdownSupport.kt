package cn.tonglu.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import kotlin.math.roundToInt

/**
 * 笔记 / 帖子正文的 Markdown 渲染。
 *
 * 为什么不用 Compose 原生的 Markdown 渲染器（阅读器正文用的那个）——
 * 因为**数学公式**。`multiplatform-markdown-renderer` 不支持 LaTeX，
 * 而它的 `annotator` 只能往 AnnotatedString 里塞文本，没法插入一段公式图片。
 * 所以要支持「行内 `$x^2$`」就必须换渲染管线。
 *
 * 这里走的是完全离线的 WebView：`assets/web/preview.html` +
 * marked（Markdown）+ KaTeX（公式，`output: 'mathml'`）+ DOMPurify（防 XSS）。
 * KaTeX 用 mathml 输出意味着**不需要 katex.css 和那几十个字体文件**，
 * 由 WebView 原生渲染 MathML，代价是少了 KaTeX 自己的精细字距。
 *
 * 内容可能来自别人（帖子详情），所以三层防护缺一不可：
 * 覆盖 marked 的 html renderer 丢弃原始 HTML → DOMPurify 再消毒 → 公式单独注入。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MarkdownBody(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 15,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    mutedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = Color.Transparent,
) {
    val density = LocalDensity.current
    val heightState = remember { mutableIntStateOf(0) }
    val webHolder = remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    val script = remember(markdown, fontSizeSp, textColor, mutedColor, accentColor, backgroundColor) {
        previewScript(
            markdown = markdown,
            sizeSp = (fontSizeSp * density.fontScale).roundToInt(),
            fg = textColor,
            muted = mutedColor,
            accent = accentColor,
            code = accentColor.copy(alpha = 0.09f),
            line = textColor.copy(alpha = 0.14f),
            bg = backgroundColor,
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (heightState.intValue > 0) {
                    // JS reports CSS pixels (Android dp), not physical display pixels.
                    Modifier.height(heightState.intValue.dp + 2.dp)
                } else {
                    Modifier.heightIn(min = 44.dp)
                }
            )
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                PassiveWebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    // 公式里的字号跟随外层正文设置，别让 WebView 自己缩放
                    settings.textZoom = 100
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onHeight(h: Int) {
                                post { heightState.intValue = h.coerceIn(0, 100_000) }
                            }
                        },
                        "TL",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                        }
                    }
                    loadUrl("file:///android_asset/web/preview.html")
                }
            },
            update = { webHolder.value = it },
        )
    }

    LaunchedEffect(webHolder.value, pageReady, script) {
        val web = webHolder.value ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        web.evaluateJavascript(script, null)
    }
}

/**
 * 只读 WebView：`onTouchEvent` 恒 false。
 *
 * WebView 默认会把落在自己身上的每一个触摸都吞掉，外层 Compose 的列表就再也滑不动
 * （AndroidView 的 interop 认为事件已被消费）。预览本身不需要交互，
 * 所以这里直接声明不吃触摸 —— `setOnTouchListener { false }` 是不够的，
 * 那样 View 自己的 `onTouchEvent` 仍会返回 true。
 */
private class PassiveWebView(context: Context) : WebView(context) {
    init {
        // 必须锁成**软件图层**，否则帖子详情一点开就崩（2026-10-09 实测 tombstone）：
        //
        //   帖子详情的玻璃回复条（GlassActionPanel）会用 blur()/lens() —— 那是
        //   RenderEffect；而被它采样的 LayerBackdrop 图层，记录的正是**本 WebView
        //   所在的那条 LazyColumn**。于是 hwui 必须把「含 WebView 硬件层」的离屏图层
        //   再重绘一遍，撞上 Chromium 的硬件绘制 functor：
        //
        //     #00 SkSurface::getCanvas()+0        ← surface 是 null
        //     #01 GLFunctorDrawable::onDraw(SkCanvas*)+1268
        //     ... RenderNodeDrawable::forceDraw → SkiaGpuPipeline::renderLayerImpl
        //
        //   即 RenderThread 空指针 SIGSEGV（fault addr 0x28），进程直接消失。
        //
        // 这块 WebView 只画一段**只读**的 Markdown / 公式预览：没有交互、没有视频、
        // 不吃触摸，硬件加速对它一点收益都没有。锁软件图层就能完全不注册 GL functor，
        // 从根上避开这条崩溃路径（对所有用到 MarkdownBody 的页面一起生效）。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

private val previewGson = Gson()

private fun previewScript(
    markdown: String,
    sizeSp: Int,
    fg: Color,
    muted: Color,
    accent: Color,
    code: Color,
    line: Color,
    bg: Color,
): String {
    val payload = previewGson.toJson(markdown)
    val opts = buildString {
        append("{\"fg\":\"").append(fg.css()).append("\",")
        append("\"muted\":\"").append(muted.css()).append("\",")
        append("\"accent\":\"").append(accent.css()).append("\",")
        append("\"code\":\"").append(code.css()).append("\",")
        append("\"line\":\"").append(line.css()).append("\",")
        append("\"bg\":\"").append(bg.css()).append("\",")
        append("\"size\":").append(sizeSp).append("}")
    }
    return "window.renderMarkdown($payload, $opts);"
}

/** Compose 的 `Color` → CSS `rgba(...)`。注意 CSS 不认 `#AARRGGBB`，别用 toArgb。 */
private fun Color.css(): String {
    val a = (alpha * 1000f).roundToInt() / 1000f
    return "rgba(${(red * 255).roundToInt()},${(green * 255).roundToInt()},${(blue * 255).roundToInt()},$a)"
}

// ---------------------------------------------------------------------------
// Emoji 与公式素材
//
// emoji 一律用「空格分隔的字符串」存，再 split 成列表 ——
// 直接写字面量列表也行，但 `String.toList()` 是按 UTF-16 code unit 拆的，
// 会把代理对（绝大多数 emoji）拦腰砍断，拼回去就是一串乱码方块。
// ---------------------------------------------------------------------------

internal val EmojiGroups: List<Pair<String, List<String>>> = listOf(
    "笑脸" to listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
        "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😋",
        "😛", "😜", "🤪", "🤗", "🤭", "🤫", "🤔", "🤐", "😐", "😶",
        "😏", "😒", "🙄", "😬", "😌", "😔", "😪", "🤤", "😴", "😷",
        "🤒", "🤕", "🤢", "🤮", "🥴", "😵", "🤯", "🤠", "🥳", "😎",
        "🤓", "🧐", "😕", "😟", "😮", "😲", "😳", "🥺", "😨", "😰",
        "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫",
        "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀", "👻", "👽",
    ),
    "手势" to listOf(
        "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉",
        "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤝", "🙏",
        "💪", "✍️", "💅", "🤳", "👏", "🙌", "👐", "🤲", "👊", "✊",
    ),
    "人物" to listOf(
        "👶", "🧒", "👦", "👧", "🧑", "👨", "👩", "🧓", "👴", "👵",
        "🙈", "🙉", "🙊", "💁", "🙅", "🙆", "🙋", "🤦", "🤷", "🧑‍🎓",
        "🧑‍🏫", "🧑‍💻", "🧑‍🔬", "🧑‍🎨", "🧑‍🚀", "🧑‍⚕️", "🧑‍🍳", "🧑‍🌾", "👮", "🕵️",
    ),
    "动物" to listOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
        "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🐤", "🦆",
        "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝", "🦋", "🐌",
        "🐢", "🐍", "🦎", "🐙", "🦑", "🦀", "🐠", "🐟", "🐬", "🐳",
        "🦈", "🐊", "🦓", "🦍", "🐘", "🐫", "🦒", "🐑", "🦌", "🐕",
        "🐈", "🐓", "🦚", "🦜", "🕊️", "🐇", "🦔", "🐿️", "🐀", "🦦",
    ),
    "食物" to listOf(
        "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐",
        "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑", "🥦",
        "🥬", "🥒", "🌶️", "🌽", "🥕", "🧄", "🧅", "🥔", "🍠", "🥐",
        "🍞", "🥖", "🧀", "🥚", "🍳", "🥞", "🥓", "🥩", "🍗", "🍔",
        "🍟", "🍕", "🥪", "🥙", "🌮", "🌯", "🥗", "🍝", "🍜", "🍲",
        "🍛", "🍣", "🍱", "🥟", "🍤", "🍙", "🍚", "🍢", "🍧", "🍨",
        "🍦", "🥧", "🧁", "🍰", "🎂", "🍮", "🍭", "🍬", "🍫", "🍿",
        "🍩", "🍪", "☕", "🍵", "🥤", "🧋", "🍺", "🍻", "🥂", "🍷",
    ),
    "活动" to listOf(
        "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🎱", "🏓",
        "🏸", "🏒", "🏑", "🥅", "⛳", "🏹", "🎣", "🥊", "🥋", "🛹",
        "⛸️", "🎿", "🏂", "🏋️", "🤼", "🤸", "🤺", "🏇", "🧘", "🏄",
        "🏊", "🚣", "🧗", "🚵", "🚴", "🏆", "🥇", "🥈", "🥉", "🏅",
        "🎫", "🎪", "🤹", "🎭", "🎨", "🎬", "🎤", "🎧", "🎼", "🎹",
        "🥁", "🎷", "🎺", "🎸", "🎻", "🎲", "🎯", "🎳", "🎮", "🧩",
    ),
    "旅行" to listOf(
        "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐",
        "🚚", "🚜", "🛴", "🚲", "🛵", "🏍️", "🚨", "🚄", "🚅", "🚂",
        "🚇", "🚉", "✈️", "🛫", "🛬", "🚀", "🛸", "🚁", "🛶", "⛵",
        "🚤", "🛳️", "⛴️", "🚢", "⚓", "⛽", "🚧", "🚦", "🗺️", "🗿",
        "🗽", "🗼", "🏰", "🏯", "🏟️", "🎡", "🎢", "⛲", "🏖️", "🏝️",
        "🌋", "⛰️", "🗻", "🏕️", "⛺", "🏠", "🏡", "🏢", "🏥", "🏦",
        "🏨", "🏪", "🏫", "⛪", "🕌", "🕋", "⛩️", "🌍", "🌙", "⭐",
    ),
    "物品" to listOf(
        "⌚", "📱", "💻", "⌨️", "🖥️", "🖨️", "🖱️", "🕹️", "💾", "💿",
        "📷", "📸", "📹", "🎥", "📞", "☎️", "📺", "📻", "🎙️", "⏰",
        "⌛", "⏳", "📡", "🔋", "🔌", "💡", "🔦", "🕯️", "💰", "💳",
        "💎", "⚖️", "🔧", "🔨", "🛠️", "🔩", "⚙️", "🧱", "⛓️", "🧲",
        "🔫", "💣", "🔪", "🛡️", "🔮", "🔭", "🔬", "🩺", "💊", "💉",
        "🧬", "🦠", "🧪", "🌡️", "🧹", "🧺", "🧻", "🚰", "🚿", "🛁",
        "📚", "📖", "📝", "✏️", "🖊️", "🖋️", "📌", "📎", "📐", "📏",
        "🔖", "🗂️", "📁", "📂", "📅", "📆", "🗒️", "🗓️", "📊", "📈",
    ),
    "符号" to listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔",
        "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "✨",
        "⭐", "🌟", "💫", "⚡", "🔥", "💥", "💦", "💨", "🌈", "☀️",
        "⛅", "☁️", "🌧️", "⛈️", "❄️", "☃️", "🌊", "✅", "❌", "⭕",
        "❗", "❓", "❕", "❔", "⚠️", "🔔", "🔕", "🔒", "🔓", "🔑",
        "🎉", "🎊", "🎁", "🏁", "🚩", "🔱", "♻️", "💯", "💢", "💤",
        "🆗", "🆕", "🆒", "🆓", "🈶", "🈚", "🈲", "🉑", "㊙️", "㊗️",
        "⬆️", "⬇️", "⬅️", "➡️", "↗️", "↘️", "↙️", "↖️", "🔃", "🔄",
    ),
)

/**
 * 公式面板的素材。
 *
 * 分三组：常用结构（分数、根号、积分…）、希腊字母、关系与运算符号。
 * 插入后整段 LaTeX 会被选中，直接敲键盘就能替换，不用先删模板。
 */
internal val FormulaGroups: List<Pair<String, List<Pair<String, String>>>> = listOf(
    "结构" to listOf(
        "分数" to "\\frac{a}{b}",
        "根号" to "\\sqrt{x}",
        "n 次根" to "\\sqrt[n]{x}",
        "上标" to "x^{n}",
        "下标" to "x_{i}",
        "求和" to "\\sum_{i=1}^{n}",
        "连乘" to "\\prod_{i=1}^{n}",
        "积分" to "\\int_{a}^{b} f(x)\\,dx",
        "二重积分" to "\\iint_{D} f(x,y)\\,d\\sigma",
        "极限" to "\\lim_{x \\to 0}",
        "导数" to "\\frac{dy}{dx}",
        "偏导" to "\\frac{\\partial f}{\\partial x}",
        "向量" to "\\vec{a}",
        "矩阵" to "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
        "行列式" to "\\begin{vmatrix} a & b \\\\ c & d \\end{vmatrix}",
        "分段函数" to "\\begin{cases} a, & x > 0 \\\\ b, & x \\le 0 \\end{cases}",
        "组合数" to "\\binom{n}{k}",
        "上划线" to "\\overline{AB}",
        "角度" to "90^{\\circ}",
        "括号" to "\\left( \\frac{a}{b} \\right)",
    ),
    "希腊字母" to listOf(
        "α" to "\\alpha", "β" to "\\beta", "γ" to "\\gamma", "δ" to "\\delta",
        "ε" to "\\varepsilon", "ζ" to "\\zeta", "η" to "\\eta", "θ" to "\\theta",
        "ι" to "\\iota", "κ" to "\\kappa", "λ" to "\\lambda", "μ" to "\\mu",
        "ν" to "\\nu", "ξ" to "\\xi", "π" to "\\pi", "ρ" to "\\rho",
        "σ" to "\\sigma", "τ" to "\\tau", "υ" to "\\upsilon", "φ" to "\\varphi",
        "χ" to "\\chi", "ψ" to "\\psi", "ω" to "\\omega",
        "Γ" to "\\Gamma", "Δ" to "\\Delta", "Θ" to "\\Theta", "Λ" to "\\Lambda",
        "Ξ" to "\\Xi", "Π" to "\\Pi", "Σ" to "\\Sigma", "Φ" to "\\Phi",
        "Ψ" to "\\Psi", "Ω" to "\\Omega",
    ),
    "符号" to listOf(
        "±" to "\\pm", "×" to "\\times", "÷" to "\\div", "·" to "\\cdot",
        "≤" to "\\le", "≥" to "\\ge", "≠" to "\\ne", "≈" to "\\approx",
        "≡" to "\\equiv", "∝" to "\\propto", "∞" to "\\infty", "∂" to "\\partial",
        "∇" to "\\nabla", "∈" to "\\in", "∉" to "\\notin", "⊂" to "\\subset",
        "⊆" to "\\subseteq", "∪" to "\\cup", "∩" to "\\cap", "∅" to "\\varnothing",
        "∀" to "\\forall", "∃" to "\\exists", "¬" to "\\neg", "∧" to "\\land",
        "∨" to "\\lor", "→" to "\\to", "⇒" to "\\Rightarrow", "⇔" to "\\Leftrightarrow",
        "↦" to "\\mapsto", "…" to "\\dots", "∠" to "\\angle", "△" to "\\triangle",
        "°" to "^{\\circ}", "√" to "\\sqrt{}", "†" to "\\dagger", "∫" to "\\int",
    ),
)

/** 最近用过的 emoji。全局单例，活到进程结束 —— 够用了，不必落盘。 */
internal val RecentEmojis: MutableList<String> = mutableListOf()

/** 记住一个用过的 emoji，最多留 24 个，最近用的排前面。 */
internal fun rememberEmoji(emoji: String) {
    RecentEmojis.remove(emoji)
    RecentEmojis.add(0, emoji)
    while (RecentEmojis.size > 24) RecentEmojis.removeAt(RecentEmojis.size - 1)
}

/**
 * 列表页用的 Markdown 摘要。
 *
 * 广场卡片只显示几行纯文本，不能上 WebView（一个卡片一个 WebView，滑两屏就卡死）。
 * 之前是在调用点堆一串 `replace(Regex(...))`，漏了公式和列表 —— 于是从笔记发过来的帖子
 * 在广场里显示成 `$$ \frac{a}{b} $$` 和 `- 第一点` 这种源码，用户直接反馈「显示有问题」。
 *
 * 规则：
 * - 只做**降级**，不追求还原：图片 → `[图片]`、行内/块级公式 → `[公式]`、链接保留文字；
 * - 表格、代码围栏、引用、标题、列表符号、强调符号一律去掉（列表项**保留内容**，只丢符号）；
 * - 最后折叠连续空白，保证不会因为换行把 6 行额度浪费掉。
 *
 * 不在这里截断字数：`maxLines` 交给 Compose 按实际行宽算，比自己猜字数准。
 */
internal fun markdownExcerpt(body: String): String {
    var text = body
    // 注释先去掉，否则下面按行处理的规则会被它干扰
    text = text.replace(Regex("<!--(?s:.*?)-->"), "")
    // 代码围栏：整块丢掉内容 —— 摘要里放一段代码没有任何意义
    text = text.replace(Regex("(?s)```.*?```"), " [代码] ")
    // 公式必须在去美元符号之前处理：`$$...$$` 要在 `$...$` 前面匹配，否则块级公式会被拆成两半
    text = text.replace(Regex("(?s)\\$\\$.*?\\$\\$"), " [公式] ")
    text = text.replace(Regex("\\$[^$\n]+\\$"), " [公式] ")
    // 图片：先于链接处理，否则 ![alt](url) 会被链接规则吃掉 alt
    text = text.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "[图片]")
    text = text.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    text = text.replace(Regex("(?m)^\\s{0,3}(```|~~~).*$"), "")
    // 表格：分隔行整行丢，其余行按「 | 」拆成可读文本
    text = text.replace(Regex("(?m)^\\s*\\|?\\s*:?-{2,}.*$"), "")
    text = text.replace(Regex("(?m)^\\s*\\|(.+)\\|\\s*$")) { match ->
        match.groupValues[1].split('|').joinToString(" · ") { it.trim() }
    }
    text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    text = text.replace(Regex("(?m)^\\s{0,3}>\\s?"), "")
    // 列表符号：`- `、`* `、`+ `、`1. `、`- [ ] `；只丢符号，内容留下
    text = text.replace(Regex("(?m)^\\s{0,4}([-*+]|\\d{1,3}[.)])\\s+(\\[[ xX]]\\s+)?"), "")
    text = text.replace(Regex("(?m)^\\s{0,4}([-*_])\\s*\\1\\s*\\1[-*_\\s]*$"), "")
    // 强调：**粗**、*斜*、~~删~~、`行内代码`
    text = text.replace(Regex("```"), "")
    text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    text = text.replace(Regex("__(.+?)__"), "$1")
    text = text.replace(Regex("~~(.+?)~~"), "$1")
    text = text.replace(Regex("`([^`]+)`"), "$1")
    text = text.replace(Regex("(?m)(?<!\\S)\\*([^*\\n]+)\\*(?!\\S)"), "$1")
    text = text.replace(Regex("(?m)(?<!\\S)_([^_\\n]+)_(?!\\S)"), "$1")
    return text.replace(Regex("\\s+"), " ").trim()
}
