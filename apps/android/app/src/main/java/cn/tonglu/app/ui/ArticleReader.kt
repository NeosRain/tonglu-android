/*
 * Markdown AST lazy rendering follows the upstream Android renderer example:
 * https://github.com/mikepenz/multiplatform-markdown-renderer (v0.45.0).
 * Copyright 2022 Mike Penz. Apache-2.0; portions Copyright 2021 Erik Hellman, MIT.
 * Full upstream license is packaged in assets/licenses/markdown-renderer.txt.
 * App-specific chrome, reading preferences and source actions are original.
 */
package cn.tonglu.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.LearningBlockTranslation
import cn.tonglu.app.data.ReadingBookmarkRecord
import cn.tonglu.app.data.ReadingNoteRecord
import cn.tonglu.app.data.ReadingStore
import cn.tonglu.app.data.ReaderBook
import cn.tonglu.app.data.ReaderDocument
import cn.tonglu.app.data.ReaderDocuments
import cn.tonglu.app.data.resolveReaderUrl
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.net.URI
import org.intellij.markdown.MarkdownElementTypes
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import cn.tonglu.app.ui.glass.GlassActionPanel
import cn.tonglu.app.ui.readingselection.readingTextHit
import cn.tonglu.app.ui.readingselection.supportsPreciseReading
import cn.tonglu.app.ui.readingselection.ReaderInteractiveParagraph
import cn.tonglu.app.ui.readingselection.isEnglishReadingArticle

internal fun copyArticleSource(context: Context, article: LearningArticle) {
    if (article.sourceUrl.isBlank()) return
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("原文链接", article.sourceUrl))
    Toast.makeText(context, if (article.isGitHub) "已复制 GitHub 原文链接" else "已复制原文链接", Toast.LENGTH_SHORT).show()
}

private fun openReadingLink(context: Context, value: String, base: String = "") {
    val resolved = resolveReaderUrl(base, value)
    if (resolved == null) { Toast.makeText(context, "此链接暂不支持打开", Toast.LENGTH_SHORT).show(); return }
    val uri = Uri.parse(resolved)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
        Toast.makeText(context, "此链接暂不支持打开", Toast.LENGTH_SHORT).show(); return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(context, "没有找到可以打开链接的浏览器", Toast.LENGTH_SHORT).show() }
}

/**
 * 纸张预设：白天色**成对**给出夜间色。
 *
 * 🔴 2026-10-15 用户报「夜间阅读页明显变灰、看着难受」：旧做法是把浅色纸往 App 画布色
 * `lerp(…, Canvas, 0.84)` —— 白纸算出来是 **#3A3C40**，比画布 #15171C 亮两档的一块灰，
 * 正文色同时被拉灰，整页低对比。现在每张纸自带夜间底色（带该纸色调的深色）与高对比正文，
 * 白天一个像素都不变；夜间不再“漂灰”，只是换同一张纸的夜间版本。
 */
private data class PaperPreset(
    val id: String, val bg: Color, val fg: Color, val muted: Color, val chip: String,
    val nightBg: Color, val nightFg: Color, val nightMuted: Color,
) {
    /** 夜间版本：只换这三个颜色，id / 标签不变（用户看到的还是同一张“白纸/暖纸/浅绿”）。 */
    fun forDark() = copy(bg = nightBg, fg = nightFg, muted = nightMuted)
}

/** 划线笔记的主色。和书架上笔记卡片的紫色是同一个。 */
private val NoteAccent get() = themeColor(Color(0xFF7C3AED), Color(0xFFBCA3FA))
/**
 * 精读模式的主色（青）。
 *
 * 为什么另起一色而不是复用 [NoteAccent]：两个模式的手势是同一个（点段落），
 * 用户必须**一眼**看出「现在点段落会拆句子」还是「会写想法」。颜色一样就分不出来了。
 */
private val DeepDiveAccent get() = themeColor(Color(0xFF0E7490), Color(0xFF67E8F9))
private val paperPresets = listOf(
    // 白纸 → 夜间：近黑中性纸 + 高对比正文（不是把白色 lerp 成灰）
    PaperPreset("default", Color(0xFFFFFFFF), Color(0xFF25282D), Color(0xFF747A83), "白纸",
        Color(0xFF191C20), Color(0xFFDCE0E5), Color(0xFF9FA6B0)),
    // 暖纸 → 夜间：带暖调的深棕纸，保住“暖”的身份
    PaperPreset("warm", Color(0xFFFAF8F3), Color(0xFF34312C), Color(0xFF827B71), "暖纸",
        Color(0xFF1E1A15), Color(0xFFE6DED3), Color(0xFFA99E8F)),
    // 浅绿 → 夜间：带绿调的深色，不塌成中性灰
    PaperPreset("bamboo", Color(0xFFF4F7F3), Color(0xFF2D352D), Color(0xFF768075), "浅绿",
        Color(0xFF161D18), Color(0xFFD8E2D9), Color(0xFF94A395)),
    // 「深灰」本来就是夜间中间档（比「夜间」浅一档），夜间与白天同值
    PaperPreset("graphite", Color(0xFF2B2F36), Color(0xFFD7DBE1), Color(0xFF98A0AB), "深灰",
        Color(0xFF2B2F36), Color(0xFFD7DBE1), Color(0xFF98A0AB)),
    PaperPreset("ink", Color(0xFF191C20), Color(0xFFDCE0E5), Color(0xFF9FA6B0), "夜间",
        Color(0xFF191C20), Color(0xFFDCE0E5), Color(0xFF9FA6B0)),
)

private data class ReaderHeading(val index: Int, val title: String, val level: Int, val anchor: String) {
    /** 这个标题所在的**节点下标**（用于截取「这一节」的正文范围）。 */
    val nodeIndex: Int get() = index
}

/** 正在编辑的笔记。`nodeIndex = -1` 表示整篇笔记，否则是被划线的第几个 Markdown 块。 */
private data class NoteDraft(val nodeIndex: Int, val noteId: String?) {
    val isWholeArticle: Boolean get() = nodeIndex < 0
}
private val headingLevels = mapOf(
    MarkdownElementTypes.ATX_1 to 1, MarkdownElementTypes.ATX_2 to 2,
    MarkdownElementTypes.ATX_3 to 3, MarkdownElementTypes.ATX_4 to 4,
    MarkdownElementTypes.ATX_5 to 5, MarkdownElementTypes.ATX_6 to 6,
    MarkdownElementTypes.SETEXT_1 to 1, MarkdownElementTypes.SETEXT_2 to 2,
)

private fun headingAnchor(title: String): String = title.lowercase(java.util.Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}_\\-\\s]"), "").replace(Regex("\\s"), "-")

/**
 * 阅读器冒泡给上层的原文选区。普通正文支持精确句子和关注词，富文本保留段落降级。
 *
 * 为什么是原始数据、不是精读自己的类型：阅读器只知道自己有哪些正文偏移，
 * 不该依赖精读界面的包 —— 上层据此构造选句、决定弹哪个层。
 *
 * `original` 是**本次渲染用的正文快照**（与 `start`/`end` 同一坐标系）；
 * 上层必须按它做 UTF-16 定位，不能换另一份文本去猜位置。
 */
data class ReaderParagraphSelection(
    val articleId: String,
    val revision: String,
    val original: String,
    val start: Int,
    val end: Int,
    val articleTitle: String,
    /**
     * 用户在精读模式里选了哪一种动作。
     *
     * 为什么由阅读器转达、而不是上层自己猜：**同一个点击手势只对应一种结果**，
     * 而"现在点段落会发生什么"只有阅读器知道（提示条就在它手里）。
     * 上层按这个字段决定弹结构面板还是词卡 —— 阅读器仍然不知道两者是什么。
     */
    val action: ReaderTapAction = ReaderTapAction.Structure,
    /** 直接点中的词在原句中的UTF-16位置；旧段落行为保持为空。 */
    val wordOffset: Int? = null,
)

/** 精读模式的点击动作；长按普通正文始终选择所在句子的结构。 */
enum class ReaderTapAction { Structure, Word }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArticleReader(article: LearningArticle, store: ReadingStore, favorite: Boolean,
    onFavorite: (Boolean) -> Unit, onClose: () -> Unit,
    initialDocumentPath: String? = null, initialAnchor: String? = null, initialIndex: Int = -1,
    onAiSearch: () -> Unit = {}, onLibraryChanged: () -> Unit = {},
    /**
     * 本次阅读停留秒数（离开阅读器时回调一次）。用于学习线经验 `read.seconds`。
     *
     * 为什么在这里算：只有阅读器自己知道「停留了多久」—— 上层只看到「进度变了」，
     * 而进度变化与停留时长无关（翻一页 3 秒和读一页 3 分钟都只写一次进度）。
     * 服务端有日上限（300 秒 = 2 点，日上限 6 点），所以这里**多报也不会多给**。
     *
     * 🔴 只报秒数，不报**读了哪篇** —— 读什么书是用户隐私（红线 10）。
     */
    onReadingSeconds: (Int) -> Unit = {},
    /** B5 A2：范围问答。回调给「选段内容 + 给人看的标题」，由上层弹面板。 */
    onAskAiScope: ((cn.tonglu.app.data.english.AiScope, String) -> Unit)? = null,
    onTranslate: (suspend (String?, List<String>) -> LearningBlockTranslation)? = null,
    /**
     * 精读：把正文里被点的**段落范围**冒泡给上层。
     *
     * 分层理由与 [onAskAiScope] 一致 —— 阅读器不持有 AI 配置、不建浮层；
     * 上层决定弹什么、用什么配置，阅读器只负责「用户点了哪一段」。
     */
    onReadingSelection: ((ReaderParagraphSelection) -> Unit)? = null,
    /**
     * 宿主叠在正文**之上**的一层内容槽。
     *
     * 为什么需要它、而不是让宿主自己在外面画一层：阅读器是**自己的 Dialog 窗口**，
     * 而且它自己的配色（`readerScheme`）是跟着纸张走的 —— 用户选了「夜墨」时整页是黑的，
     * 而 App 可能还是浅色。浮层画在外面就会变成「黑纸上飘一张白面板」。
     * 放进这个槽里，浮层与正文同一个窗口、同一份配色，位置与层次也对得上。
     *
     * 阅读器不关心槽里是什么（精读浮层由宿主装配），只保证它在正文之上、在阅读器主题之内。
     */
    overlay: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 阅读计时：进页面记开始时刻，离开时算差值。
    // 用**单调时钟**（elapsedRealtime）而不是墙钟 —— 用户中途改系统时间不会算出负数或巨额秒数。
    val startedAt = remember(article.id, initialDocumentPath) { android.os.SystemClock.elapsedRealtime() }
    val reported = remember(article.id, initialDocumentPath) { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 不写成 onClose 里的一行：关闭路径有多条（返回键、章节回退、外层面板关掉），
    // 只有 DisposableEffect 能保证**无论走哪条**都报一次且只报一次。
    DisposableEffect(article.id, initialDocumentPath) {
        onDispose {
            if (reported.compareAndSet(false, true)) {
                val seconds = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                // 不足 5 秒的当「误触/看了一眼就走」，不计入 —— 否则点开关掉就能刷时长。
                if (seconds >= 5) onReadingSeconds(seconds)
            }
        }
    }
    val book by produceState<ReaderBook?>(null, article.id, article.sourceRevision) {
        value = ReaderDocuments.book(context, article)
    }
    var history by remember(article.id, initialDocumentPath, initialAnchor, initialIndex) { mutableStateOf(emptyList<LearningArticle>()) }
    var targetAnchor by remember(article.id, initialDocumentPath, initialAnchor, initialIndex) { mutableStateOf(initialAnchor) }
    // 段落级跳转（从「我的阅读」里点一条划线笔记回来）：-1 表示按锚点或上次位置恢复
    var targetIndex by remember(article.id, initialDocumentPath, initialAnchor, initialIndex) { mutableIntStateOf(initialIndex) }
    var loading by remember { mutableStateOf(false) }
    var chapterJob by remember { mutableStateOf<Job?>(null) }
    val current = history.lastOrNull() ?: article
    /**
     * 章节的文档路径；根文档（封面那篇）是空串。
     * 阅读进度按「根文章 id + 文档路径」记账 —— 一本 1000 章的书要能一章一条进度，
     * 书架上也才分得清读到哪一章了。
     */
    val documentPath = if (current.id == article.id) "" else current.id.removePrefix("${article.id}:")
    fun openDocument(document: ReaderDocument) {
        val collection = book ?: return
        if (loading || current.documentUrl == collection.url(document)) return
        loading = true
        chapterJob = scope.launch {
            try {
                val next = ReaderDocuments.read(context, article, collection, document)
                history = (history + next).takeLast(20)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, "章节暂时无法读取，请打开原文", Toast.LENGTH_SHORT).show()
            } finally { loading = false }
        }
    }
    LaunchedEffect(book, initialDocumentPath, initialAnchor) {
        val document = book?.documents?.firstOrNull { it.path == initialDocumentPath }
        if (document != null) openDocument(document)
    }
    // 仅保留当前章和真实上一章两层；上一章的滚动与排版保留，不让返回预览露出资料根目录。
    val visiblePages = (listOf(article) + history).takeLast(2)
    visiblePages.forEach { page -> key(page.id) {
        val topPage = page.id == current.id
        ArticleReaderPage(page, store, favorite, onFavorite, onClose = {
            chapterJob?.cancel(); loading = false
            if (history.isNotEmpty()) history = history.dropLast(1) else onClose()
        }, chapters = book?.chapters.orEmpty(), onChapter = { targetAnchor = null; targetIndex = -1; openDocument(it) },
            initialAnchor = targetAnchor, initialIndex = targetIndex, onAiSearch = onAiSearch,
            onAskAiScope = onAskAiScope, onReadingSelection = onReadingSelection,
            overlay = { if (topPage) overlay() }, isTopPage = topPage,
            isChapter = page.id != article.id, rootId = article.id,
            documentPath = if (page.id == article.id) "" else page.id.removePrefix("${article.id}:"),
            // 章节列表里要标出「正在读」的那一章。封面那篇（根文档）本身也在 chapters 里，
            // 不标的话点它没有任何反应（openDocument 会提前 return），用户会以为坏了。
            isCurrentChapter = { doc -> book?.let { it.url(doc) == current.documentUrl } ?: false },
            onLibraryChanged = onLibraryChanged, onTranslate = onTranslate,
            chapterLoading = loading, onDocumentLink = { url ->
                val document = book?.find(url)
                if (document == null) false else { targetAnchor = Uri.parse(url).fragment; targetIndex = -1; openDocument(document); true }
            })
    } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ArticleReaderPage(article: LearningArticle, store: ReadingStore, favorite: Boolean,
    onFavorite: (Boolean) -> Unit, onClose: () -> Unit, chapters: List<ReaderDocument>,
    onChapter: (ReaderDocument) -> Unit, chapterLoading: Boolean, onDocumentLink: (String) -> Boolean,
    initialAnchor: String?, initialIndex: Int, onAiSearch: () -> Unit,
    rootId: String, documentPath: String, onLibraryChanged: () -> Unit,
    onAskAiScope: ((cn.tonglu.app.data.english.AiScope, String) -> Unit)? = null,
    onTranslate: (suspend (String?, List<String>) -> LearningBlockTranslation)? = null,
    onReadingSelection: ((ReaderParagraphSelection) -> Unit)? = null,
    /** 宿主浮层内容槽；见 [ArticleReader] 上同名参数的说明。 */
    overlay: @Composable () -> Unit = {},
    isChapter: Boolean = false,
    isTopPage: Boolean = true,
    isCurrentChapter: (ReaderDocument) -> Boolean = { false }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = store.library
    val readerFocus = remember { FocusRequester() }
    val motion = remember { Animatable(if (isTopPage) 0f else 1f) }
    val latestClose by rememberUpdatedState(onClose)
    var closing by remember { mutableStateOf(false) }
    val englishReading = remember(article.id, article.body) { isEnglishReadingArticle(article) }
    val directReading = englishReading && onReadingSelection != null
    var fontSize by rememberSaveable { mutableIntStateOf(if (englishReading) store.englishFontSize else store.fontSize) }
    var lineHeight by rememberSaveable { mutableFloatStateOf(if (englishReading) store.englishLineHeight else store.lineHeight) }
    // 纸张三态：**用户显式选过就尊重他**；没选过则**跟着主题走** ——
    // 夜间进文章直接给「夜墨」，不然一片白纸（用户 2026-10-08 报的就是这条）。
    // 老值 `plain/midnight/kraft` 是早期 id，继续兼容。
    var paperStyle by rememberSaveable { mutableStateOf(
        when (val chosen = store.paperStyleChosen) {
            "plain" -> "default"
            "midnight" -> "ink"
            "kraft" -> "warm"
            null -> if (AppTheme.darkState) "ink" else "default"
            else -> chosen
        }
    ) }
    val paperIdx = paperPresets.indexOfFirst { it.id == paperStyle }.takeIf { it >= 0 } ?: 0
    val selectedPaper = paperPresets[paperIdx]
    // 夜间用同一张纸的**夜间版本**（底色/正文/次要色成对给），不再 lerp 漂灰。
    val paper = if (AppTheme.darkState) selectedPaper.forDark() else selectedPaper
    var sheet by remember { mutableIntStateOf(0) }
    var showAttrib by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showProjectDetails by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var matchCursor by remember { mutableIntStateOf(0) }
    /** 书签 / 笔记变化后 +1：`ReadingLibrary` 不是 Compose 状态，得手动敲一下才重算。 */
    var libraryTick by remember { mutableIntStateOf(0) }
    /** 非空时弹笔记编辑器。 */
    var noteDraft by remember { mutableStateOf<NoteDraft?>(null) }
    /** 划线模式：打开之后点段落就能写想法。默认关，免得随手一碰就弹窗。 */
    var marking by rememberSaveable { mutableStateOf(false) }
    /**
     * 精读模式：打开之后点段落，把「这一段」冒泡给上层弹精读浮层。
     * 与划线模式**互斥** —— 同一个点击手势不能既写笔记又开浮层，否则用户猜不到会弹哪个。
     */
    var deepDiving by rememberSaveable(article.id) { mutableStateOf(directReading) }
    var showReadingHint by rememberSaveable(article.id) { mutableStateOf(true) }
    /**
     * 精读模式里选的是「查词」而不是「句子结构」。
     *
     * 为什么不新加一个工具栏按钮：工具栏已经有 8 个按钮，`Arrangement.SpaceEvenly` 在窄屏上
     * 本来就挤；再加一个会把每个都压到最小触摸尺寸以下。而这个选择**本来就是精读模式内部的**
     * —— 两种动作都是"点段落"，只在提示条上做二选一。
     */
    var wordLooking by rememberSaveable { mutableStateOf(false) }
    // 精读真正可用 = 「模式开着」且「上层确实给了回调」。
    // 上层没接线时（回调为 null）这里恒 false：工具栏不显示开关、点段落也不会吞掉手势。
    // 不这样写的话，`deepDiving` 被 rememberSaveable 从进程死亡里恢复成 true 之后，
    // 段落会挂上一个**点了没反应**的 clickable —— 既不开浮层也写不了笔记。
    val deepDiveActive = deepDiving && onReadingSelection != null
    fun libraryChanged() { libraryTick++; onLibraryChanged() }
    val background = paper.bg
    // LINK is navigation, not permission to mirror a repository's README.
    val contentKey = remember(article) {
        article.body.takeIf { it.isNotBlank() } ?: "## 阅读原文\n\n${article.summary}\n\n本条目提供原文导航，正文由原作者在 GitHub 维护。"
    }
    val markdownState = rememberMarkdownState(contentKey)
    val readerImages = remember(article.documentUrl) { ReaderImages(article.documentUrl) }
    val parseState by markdownState.state.collectAsState()
    val parsed = parseState as? State.Success
    val nodes = remember(parsed, contentKey) { parsed?.node?.children.orEmpty().filterNot {
        val text = contentKey.substring(it.startOffset, it.endOffset).trim()
        text.isBlank() || (text.startsWith("<!--") && text.endsWith("-->"))
    } }
    // ---------- 页内 AI 翻译（docs/plan/37 B1）----------
    // 原文/译文/双语在同一阅读器里切换：译文按 Markdown 块走与原文**同一套渲染管线**，
    // 不再另开弹窗（那正是「跳到另页显示源码」的根因）。LazyColumn 的 item key
    // （node.startOffset）三种模式下都不变，切换时位置天然保留。
    var transMode by rememberSaveable { mutableIntStateOf(0) } // 0 原文 / 1 译文 / 2 双语
    var transVisible by rememberSaveable { mutableStateOf(false) }
    var transBlocks by remember(article.id, contentKey) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var transTotal by remember(article.id, contentKey) { mutableIntStateOf(0) }
    var transFailed by remember(article.id, contentKey) { mutableIntStateOf(0) }
    var transCached by remember(article.id, contentKey) { mutableIntStateOf(0) }
    var transRunning by remember(article.id, contentKey) { mutableStateOf(false) }
    /** 真实失败原因（我们自己的错话原文）。为 null 时给通用的网络/格式提示。 */
    var transReason by remember(article.id, contentKey) { mutableStateOf<String?>(null) }
    var transNeedsModel by remember(article.id, contentKey) { mutableStateOf(false) }
    var transError by remember(article.id, contentKey) { mutableStateOf<String?>(null) }
    /**
     * 可翻译块 = 除代码/HTML/注释外的 Markdown 块。批的切法（≤8 块、≤2400 字符）
     * 只取决于内容本身，对所有客户端一致 → 拼出来的批原文稳定，公共缓存才能命中。
     */
    val translatable = remember(contentKey, nodes) {
        val skipTypes = setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.HTML_BLOCK)
        nodes.mapIndexedNotNull { index, node ->
            if (node.type in skipTypes) null
            else contentKey.substring(node.startOffset, node.endOffset).trim()
                .takeIf { it.isNotEmpty() && it.length <= 4800 }?.let { index to it }
        }
    }
    fun startTranslation() {
        val callback = onTranslate ?: return
        if (transRunning || translatable.isEmpty()) return
        transRunning = true; transError = null; transReason = null; transNeedsModel = false
        transTotal = translatable.size; transFailed = 0; transCached = 0
        // 已翻过的块（重试场景）不再花钱；批按 pending 顺序切。
        val pending = translatable.filterNot { (index, _) -> transBlocks.containsKey(index) }
        val batches = ArrayList<List<Pair<Int, String>>>()
        var current = ArrayList<Pair<Int, String>>()
        var chars = 0
        for (item in pending) {
            val add = item.second.length + if (current.isEmpty()) 0 else 2
            if (current.isNotEmpty() && (chars + add > 2400 || current.size >= 8)) {
                batches.add(current); current = ArrayList(); chars = 0
            }
            current.add(item); chars += add
        }
        if (current.isNotEmpty()) batches.add(current)
        transVisible = true
        scope.launch {
            // 按批降级：一批失败不拖垮其余批（已成功的照常显示，失败的可重试补齐）。
            batches.forEach { batch ->
                try {
                    val result = callback(article.id.takeIf { batch.size == 1 }, batch.map { it.second })
                    val list = result.blocks
                    // **先看状态，再看长度**：`NEEDS_MODEL` 回来的是**空表**，
                    // 排在长度检查后面就会被当成「长度对不上」，`transNeedsModel` 永远起不来 →
                    // 界面只剩一句「（网络或格式问题）」，把「服务器没配模型 / 你没填自己的密钥」
                    // 糊过去，正好违反本文件上面那条「不许统一写网络问题」的约定（2026-10-04 实测）。
                    if (result.state == "NEEDS_MODEL") { transNeedsModel = true; transFailed += batch.size }
                    else if (list.size != batch.size) { transFailed += batch.size } // 长度对不上就整批记为失败，别让界面越界
                    else {
                        transBlocks = transBlocks + batch.mapIndexed { i, item -> item.first to list[i] }
                        if (result.state == "CACHED") transCached += batch.size
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("TongluLearn", "块翻译失败：${error::class.java.simpleName}: ${error.message}")
                    transFailed += batch.size
                    // 我们自己抛的话（未登录、段落过长）直接照抄给用户看。
                    // 不许统一写「网络问题」糊过去 —— 那样未登录的人会一直以为是网不好。
                    transReason = error.message?.takeIf { it.isNotBlank() && it.length <= 40 }
                }
            }
            transRunning = false
            // `transNeedsModel` 时下面那条指引（「填入你自己的接口」）已经把原因说清楚了；
            // 再补一行「（网络或格式问题）」只会把人往网络故障上带 —— 这种情形不写通用错误行。
            if (transFailed > 0 && transBlocks.size < transTotal && !transNeedsModel) {
                transError = when (val reason = transReason) {
                    null -> "有 $transFailed 段没有翻成功（网络或格式问题）。点「重试」只补这些段，已翻好的不会重复计费。"
                    else -> "$reason（$transFailed 段）。点「重试」只补这些段，已翻好的不会重复计费。"
                }
            }
        }
    }
    fun requestTranslate() {
        // 全中文的资料没有翻译意义：直接说明，不发任何请求（也就不会动用户的 API）。
        val cjk = contentKey.count { it.code in 0x4E00..0x9FFF }
        if (cjk * 2 > contentKey.length) {
            Toast.makeText(context, "这篇资料已经是中文，无需翻译", Toast.LENGTH_SHORT).show(); return
        }
        if (transBlocks.isEmpty() && !transRunning) { transMode = if (englishReading) 2 else 1; startTranslation() }
        else { transVisible = true; if (transMode == 0) transMode = if (englishReading) 2 else 1 }
    }
    // 章数够多才走「书籍封面」形态：显示章节目录、正文收到「查看项目说明全文」里。
    // 只有 1~2 章的（比如只有一个 README 的 awesome 清单）直接显示正文，否则用户会以为这篇是空的。
    // 已经在读某章时也不再套封面：点进章节就是要读它，还要再点一次「查看项目说明全文」很别扭。
    val bookOverview = !isChapter && chapters.size >= 3
    val firstTitle = nodes.indexOfFirst { headingLevels[it.type] == 1 }
    val headings = remember(parsed, contentKey) {
        val anchors = mutableMapOf<String, Int>()
        nodes.mapIndexedNotNull { index, node ->
        if (node.type in headingLevels)
        {
            val title = contentKey.substring(node.startOffset, node.endOffset).trim()
                .substringBefore('\n').trimStart('#').trim().replace(Regex("\\s+#+\\s*$"), "")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1").replace(Regex("[*`~]"), "")
            val base = headingAnchor(title)
            val count = anchors[base] ?: 0; anchors[base] = count + 1
            ReaderHeading(index, title, headingLevels.getValue(node.type),
                if (count == 0) base else "$base-$count")
        }
        else null
    } }
    val matches by produceState(emptyList<Int>(), parsed, searchQuery, showSearch) {
        value = emptyList()
        val query = searchQuery.trim()
        if (showSearch && query.isNotEmpty()) {
            delay(180)
            value = withContext(Dispatchers.Default) { nodes.mapIndexedNotNull { index, node ->
                index.takeIf { contentKey.substring(node.startOffset, node.endOffset).contains(query, true) }
            } }
        }
    }
    val readingRevision = remember(article.sourceRevision, contentKey) {
        java.security.MessageDigest.getInstance("SHA-256").digest(
            "reader-layout-2:${article.sourceRevision}\n$contentKey".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    }
    val position = remember(article.id, readingRevision) { store.position(article.id, readingRevision) }
    val listState = rememberLazyListState()
    var restored by remember(article.id, readingRevision) { mutableStateOf(false) }
    LaunchedEffect(parsed, listState) {
        if (parsed != null && !restored) {
            // Wait for the lazy content; a loading screen must not overwrite the saved position.
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            val target = headings.firstOrNull { it.anchor == initialAnchor }
            when {
                // 1) 按标题锚点跳（章节链接 / 阅读器内 #锚点）
                target != null -> { showProjectDetails = true; listState.scrollToItem(target.index + 1) }
                // 2) 按段落序号跳（从「我的阅读」点一条划线笔记回来）
                initialIndex >= 0 -> { showProjectDetails = true; listState.scrollToItem((initialIndex + 1).coerceAtMost(nodes.size)) }
                // 3) 恢复上次读到哪儿
                else -> listState.scrollToItem(position.first.coerceIn(0, nodes.size + 1), position.second.coerceAtLeast(0))
            }
            restored = true
        }
    }
    /**
     * 保存进度。两个口径一起写：
     *   - 本机精确位置（index + offset），只在 revision 相同时用 —— 换个字号就可能偏；
     *   - 归一化比例 ratio，跟着账号同步，换设备、换排版都还能大致落回原处。
     * `finished` 看「最后一项是否已经完全露出来」，比 ratio ≥ 0.98 可靠 —— 短文章永远滚不出 98%。
     */
    val saveReading: () -> Unit = {
        if (restored) {
            val index = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            // 「上次读到哪篇」还是要记的，学习页的「继续阅读」靠它。
            store.savePosition(article.id, readingRevision, index, offset)
            val info = listState.layoutInfo
            // 书籍封面（封面 + 章节目录）本身不是正文，不记进度。
            // 记的话书架会出现一条「Python 学习 100 天 · 0%」—— 用户只是翻了下章节目录而已，
            // 真正的进度应该落在具体章节上（小说 App 也是记「第几章」而不是「目录页」）。
            if (info.totalItemsCount > 0 && !bookOverview) {
                val viewport = info.viewportSize.height.coerceAtLeast(1)
                val ratio = ((index + offset.toFloat() / viewport) / info.totalItemsCount).coerceIn(0f, 1f)
                val last = info.visibleItemsInfo.lastOrNull()
                val atEnd = last != null && last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= viewport + 4
                library.saveProgress(rootId, documentPath, ratio, index, offset, readingRevision, atEnd)
            }
        }
    }
    val latestSave by rememberUpdatedState(saveReading)
    // 滚动停下来 0.8 秒写一次。snapshotFlow 是 conflated 的（只留最新值），
    // 所以这里的 delay 等价于防抖：一直滚就一直重来，停住了才落盘。
    LaunchedEffect(listState, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { delay(800); latestSave() }
    }
    LaunchedEffect(matches, restored) {
        matchCursor = 0
        matches.firstOrNull()?.let { if (restored) { showProjectDetails = true; listState.animateScrollToItem(it + 1) } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(article.id, readingRevision, lifecycle, listState) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latestSave() }
        lifecycle.addObserver(observer)
        onDispose { latestSave(); lifecycle.removeObserver(observer) }
    }
    val close: () -> Unit = {
        if (noteDraft != null) {
            noteDraft = null
        } else if (isTopPage && !closing) {
            closing = true
            // 全屏层收起后顶栏立刻可见，先开返回穿透冷却（组件自兜底：无论宿主有没有包守卫都生效）。
            OverlayDismissGuard.arm()
            scope.launch {
                motion.animateTo(0f, tween(OverlayMotion.EXIT_MS, easing = FastOutSlowInEasing)); latestClose()
            }
        }
    }
    // 跟手返回：**叠加**在这一层自己的进出场动画之上，不替换（理由见 OverlayMotion）。
    // 拖够了松手 → 走的是本页原本那套 close（会把退出动画播完再真正 dismiss）。
    val edgeBack = rememberEdgeBackState()
    val predictiveBack = OverlayMotion.enabledState
    LaunchedEffect(close) { edgeBack.onCommit = close }

    fun findMatch(direction: Int) {
        if (matches.isEmpty()) return
        matchCursor = (matchCursor + direction + matches.size) % matches.size
        scope.launch { listState.animateScrollToItem(matches[matchCursor] + 1) }
    }
    val currentHeading by remember(headings, listState) { derivedStateOf {
        headings.lastOrNull { it.index + 1 <= listState.firstVisibleItemIndex }
    } }
    // 本页已有的划线与书签。段落笔记按 startOffset 对上，正文里就能标出「这段写过东西」。
    val pageNotes = remember(libraryTick, rootId, documentPath) { library.notes(rootId, documentPath) }
    val noteAt = remember(pageNotes) { pageNotes.filterNot { it.isWholeArticle }.associateBy { it.startOffset } }
    val pageBookmarks = remember(libraryTick, rootId, documentPath) { library.bookmarks(rootId, documentPath) }
    val wholeNotes = remember(libraryTick, rootId) { library.notes(rootId).filter { it.isWholeArticle } }

    /** 在当前滚动位置加一枚书签：锚在所属标题 + 当前段落，顺手留一句摘录。 */
    fun addBookmark() {
        val index = listState.firstVisibleItemIndex
        // 第 0 项是文章标题，所以段落序号要 -1
        val nodeIndex = (index - 1).coerceIn(0, (nodes.size - 1).coerceAtLeast(0))
        val node = nodes.getOrNull(nodeIndex)
        val excerpt = if (node != null) library.excerpt(contentKey, node.startOffset) else article.title
        library.addBookmark(rootId, documentPath, currentHeading?.anchor, nodeIndex, 0, excerpt)
        libraryChanged()
        Toast.makeText(context, "已加书签", Toast.LENGTH_SHORT).show()
    }
    val readerDark = AppTheme.darkState || paper.id == "ink"
    val readerScheme = (if (readerDark) darkColorScheme() else lightColorScheme()).copy(
        background = paper.bg, surface = paper.bg, onSurface = paper.fg, onBackground = paper.fg,
        onSurfaceVariant = paper.muted, primary = if (readerDark) Color(0xFFAAC4DB) else Color(0xFF3C6482),
        surfaceVariant = paper.muted.copy(alpha = 0.09f), surfaceContainer = paper.bg,
        outline = paper.muted.copy(alpha = 0.55f), outlineVariant = paper.muted.copy(alpha = 0.2f),
    )
    LaunchedEffect(isTopPage) {
        if (isTopPage) motion.animateTo(1f, tween(OverlayMotion.ENTER_MS, easing = FastOutSlowInEasing))
        else { closing = false; motion.snapTo(1f) }
    }
    val backProgress = rememberEdgeBackProgressState(edgeBack)

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize()) {
        LaunchedEffect(showSearch, isTopPage) { if (isTopPage && !showSearch) readerFocus.requestFocus() }
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setWindowAnimations(0); window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !readerDark
                isAppearanceLightNavigationBars = !readerDark
            }
        } }
        MaterialTheme(colorScheme = readerScheme) {
        CompositionLocalProvider(
            // 与 `MainActivity` 根部同一条规矩：**定了 colorScheme 就必须同时给出 LocalContentColor**
            // （Material3 的 MaterialTheme 不管它，默认是 Color.Black）。
            // 阅读器必须自己给一份 —— 它的纸张可以选「墨黑」，那时 App 可能还是浅色。
            LocalContentColor provides paper.fg,
            LocalUriHandler provides object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("#")) {
                    val anchor = Uri.decode(uri.removePrefix("#"))
                    val target = headings.firstOrNull { it.anchor == anchor }
                    if (target != null) scope.launch { listState.animateScrollToItem(target.index + 1) }
                    else Toast.makeText(context, "未找到对应章节", Toast.LENGTH_SHORT).show()
                } else {
                    val resolved = resolveReaderUrl(article.documentUrl, uri)
                    if (resolved == null || !onDocumentLink(resolved)) openReadingLink(context, uri, article.documentUrl)
                }
            }
        }) {
            Surface(color = background, modifier = Modifier.fillMaxSize()
                .edgeSwipeBack(edgeBack, enabled = predictiveBack && isTopPage && noteDraft == null)
                .graphicsLayer {
                    alpha = motion.value; translationY = (1f - motion.value) * 48.dp.toPx()
                    applyEdgeBack(backProgress.value, edgeBack.fromLeft, fade = false)
                }) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN || showSearch) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                                        scope.launch { listState.animateScrollBy(listState.layoutInfo.viewportSize.height * 0.85f) }; true
                                    }
                                    KeyEvent.KEYCODE_PAGE_UP -> {
                                        scope.launch { listState.animateScrollBy(-listState.layoutInfo.viewportSize.height * 0.85f) }; true
                                    }
                                    else -> false
                                }
                    }.focusRequester(readerFocus).focusable()) {
                    ReaderHeader(currentHeading?.title ?: article.title, close, paper.fg,
                        onSearch = { showSearch = !showSearch }, onAttrib = { showAttrib = true }, onAiSearch = onAiSearch,
                        onOriginal = { openReadingLink(context, article.documentUrl) },
                        onTranslate = onTranslate?.let { { requestTranslate() } },
                        // B5 A2：范围明确的一问一答。**只给用户正在看的那一节**，不给全文 ——
                        // §6.2 要求全文/整库上传前必须明确范围，本批不做全文，所以这里截当前小节。
                        onAskSection = onAskAiScope?.let { ask -> {
                            val title = currentHeading?.title ?: article.title
                            val body = currentSectionText(contentKey, nodes, currentHeading?.nodeIndex ?: -1)
                            ask(cn.tonglu.app.data.english.EnglishAiScopes.selection(article.title, body),
                                title)
                        } })
                    if (chapterLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (showSearch) ReaderSearchBar(searchQuery, { searchQuery = it },
                        counter = if (matches.isEmpty()) "无匹配" else "${matchCursor + 1}/${matches.size} 段",
                        hasMatches = matches.isNotEmpty(), onPrevious = { findMatch(-1) },
                        onNext = { findMatch(1) }, onClose = { showSearch = false; searchQuery = "" })
                    if (marking && !showSearch) MarkingBar(paper, notes = pageNotes.size,
                        onDone = { marking = false })
                    if (deepDiveActive && !showSearch && showReadingHint) DeepDiveBar(paper,
                        onDone = { showReadingHint = false })
                    if (transVisible && onTranslate != null) TranslateBar(paper, mode = transMode,
                        onMode = { transMode = it }, translated = transBlocks.size, total = transTotal,
                        cached = transCached, running = transRunning, needsModel = transNeedsModel,
                        error = if (transRunning) null else transError,
                        onRetry = { startTranslation() }, onClose = { transVisible = false; transMode = 0 })
                    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = fontSize.sp, lineHeight = (fontSize * lineHeight).sp,
                        color = paper.fg, letterSpacing = 0.sp, fontFamily = FontFamily.SansSerif,
                    )
                    // 原文与译文块共用同一套排版（docs/plan/37 B1）。
                    val mdTypography = markdownTypography(text = bodyStyle, paragraph = bodyStyle, ordered = bodyStyle,
                        bullet = bodyStyle, list = bodyStyle, quote = bodyStyle,
                        table = bodyStyle, inlineCode = bodyStyle.copy(fontFamily = FontFamily.Monospace),
                        code = bodyStyle.copy(fontFamily = FontFamily.Monospace),
                        textLink = TextLinkStyles(style = bodyStyle.copy(color = MaterialTheme.colorScheme.primary).toSpanStyle()),
                        h1 = bodyStyle.copy(fontSize = (fontSize + 7).sp, lineHeight = ((fontSize + 7) * 1.4f).sp, fontWeight = FontWeight.Bold, color = paper.fg),
                        h2 = bodyStyle.copy(fontSize = (fontSize + 4).sp, lineHeight = ((fontSize + 4) * 1.45f).sp, fontWeight = FontWeight.SemiBold, color = paper.fg),
                        h3 = bodyStyle.copy(fontSize = (fontSize + 1).sp, lineHeight = ((fontSize + 1) * 1.5f).sp, fontWeight = FontWeight.SemiBold, color = paper.fg),
                        h4 = bodyStyle.copy(fontWeight = FontWeight.SemiBold, color = paper.fg),
                        h5 = bodyStyle.copy(fontWeight = FontWeight.SemiBold, color = paper.fg),
                        h6 = bodyStyle.copy(fontWeight = FontWeight.SemiBold, color = paper.fg))
                    val readerBackdrop = rememberLayerBackdrop()
                    Box(Modifier.weight(1f)) {
                    SelectionContainer(Modifier.fillMaxSize().layerBackdrop(readerBackdrop)) {
                        Markdown(markdownState = markdownState,
                            imageTransformer = readerImages,
                            typography = mdTypography,
                            components = markdownComponents(
                                paragraph = { model ->
                                    val raw = model.content.substring(model.node.startOffset, model.node.endOffset)
                                    if (raw.contains("![") || raw.contains("<img", ignoreCase = true)) {
                                        MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
                                    } else ReaderInteractiveParagraph(model.content, model.node,
                                        model.typography.paragraph, deepDiveActive, rootId, article.sourceRevision,
                                        article.title, DeepDiveAccent.copy(alpha = 0.12f)) { onReadingSelection?.invoke(it) }
                                },
                                codeFence = { model -> MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style -> ReaderCode(code, language, style) } },
                                codeBlock = { model -> MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style -> ReaderCode(code, language, style) } },
                            ),
                            modifier = Modifier.fillMaxSize(),
                            loading = { Box(it.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } },
                            error = { Column(it.padding(24.dp).verticalScroll(rememberScrollState())) {
                                Text("这篇文章暂时无法排版。", color = paper.fg)
                                if (article.sourceUrl.isNotBlank()) TextButton(onClick = { openReadingLink(context, article.sourceUrl) }) { Text("打开原文") }
                                else Text(contentKey, style = bodyStyle, modifier = Modifier.padding(top = 16.dp))
                            } },
                            success = { state, components, modifier ->
                                BoxWithConstraints(modifier) {
                                val gutter = if (maxWidth > 700.dp) (maxWidth - 640.dp) / 2 else 20.dp
                                LazyColumn(Modifier.fillMaxSize(), state = listState,
                                    contentPadding = PaddingValues(start = gutter, end = gutter, top = 16.dp, bottom = 100.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    item(key = "article-heading") {
                                        ReaderArticleHeading(article, paper)
                                        if (bookOverview) {
                                            Text(article.summary, style = bodyStyle, modifier = Modifier.padding(bottom = 24.dp))
                                            chapters.forEach { chapter ->
                                                val reading = isCurrentChapter(chapter)
                                                Row(Modifier.fillMaxWidth()
                                                    .clickable(enabled = !reading) { onChapter(chapter) }
                                                    .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Text(chapter.title, fontSize = 16.sp,
                                                        color = if (reading) paper.muted else paper.fg,
                                                        modifier = Modifier.weight(1f))
                                                    if (reading) {
                                                        Text("正在读", fontSize = 12.sp, color = NoteAccent)
                                                    } else {
                                                        Icon(Icons.Outlined.ChevronRight, null, tint = paper.muted, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                                HorizontalDivider(color = paper.muted.copy(alpha = 0.12f))
                                            }
                                            TextButton(onClick = { showProjectDetails = !showProjectDetails }) { Text(if (showProjectDetails) "收起项目说明" else "查看项目说明全文") }
                                        }
                                    }
                                    itemsIndexed(nodes, key = { _, node -> node.startOffset }, contentType = { _, node -> node.type }) { index, node ->
                                        val matched = index in matches
                                        if ((!bookOverview || showProjectDetails) && index != firstTitle) {
                                        // 划线过的段落：左边一条紫线 + 淡紫底，扫一眼就知道这段写过东西
                                        val note = noteAt[node.startOffset]
                                        val blockText = contentKey.substring(node.startOffset, node.endOffset)
                                        val tint = when {
                                            matched -> MaterialTheme.colorScheme.primary.copy(alpha =
                                                if (index == matches.getOrNull(matchCursor)) 0.17f else 0.07f)
                                            note != null -> NoteAccent.copy(alpha = 0.09f)
                                            else -> Color.Transparent
                                        }
                                        Box(Modifier.fillMaxWidth()
                                            .then(when {
                                                // 两个模式互斥，同一段不会既写笔记又开浮层。
                                                marking -> Modifier.clickable {
                                                    noteDraft = NoteDraft(index, note?.key)
                                                }
                                                else -> Modifier
                                            })
                                            .background(tint, RoundedCornerShape(6.dp))
                                            .drawBehind {
                                                if (note != null) drawRect(
                                                    color = NoteAccent.copy(alpha = 0.55f),
                                                    size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height),
                                                )
                                            }) {
                                            // 页内翻译渲染：没译文的块（代码块/未翻完/失败）一律保留原文。
                                            val translation = transBlocks[index]
                                            when {
                                                translation == null || transMode == 0 ->
                                                    MarkdownElement(node, components, state.content)
                                                transMode == 1 ->
                                                    TranslatedBlock(translation, mdTypography, components)
                                                else -> Column {
                                                    MarkdownElement(node, components, state.content)
                                                    TranslatedBlock(translation, mdTypography, components)
                                                }
                                            }
                                        }
                                        }
                                    }
                                    item(key = "source") { ArticleSource(article, paper, onShowAttrib = { showAttrib = true }, onLicenses = { sheet = 3 }) }
                                }
                                }
                            })
                    }
                    val readerTools: @Composable () -> Unit = {
                    ReaderTools(article, paper, favorite,
                        marks = pageBookmarks.size + pageNotes.size + wholeNotes.size,
                        marking = marking,
                        deepDiving = deepDiveActive,
                        deepDiveAvailable = onReadingSelection != null,
                        englishReading = englishReading,
                        onTranslate = onTranslate?.let { { requestTranslate() } },
                        onFavorite = { onFavorite(!favorite) },
                        onToc = { sheet = 1 },
                        onSettings = { sheet = 2 },
                        onMarks = { sheet = 4 },
                        onBookmark = { addBookmark() },
                        // 划线 / 精读互斥：打开一个就把另一个关掉，免得用户猜不到点段落会弹哪个。
                        onMarking = { marking = !marking
                            if (marking) { showSearch = false; deepDiving = false; wordLooking = false } },
                        onDeepDive = { deepDiving = !deepDiving
                            // 退出精读要把「查词/句子结构」的选择也复位：下次进来默认还是句子结构，
                            // 免得用户以为上次的查词模式是默认行为。
                            if (deepDiving) { showSearch = false; marking = false; wordLooking = true; showReadingHint = true } else wordLooking = false },
                        onCopy = { copyArticleSource(context, article) },
                    )
                    }
                    val toolsModifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp)
                    if (englishReading) Surface(modifier = toolsModifier, shape = RoundedCornerShape(24.dp),
                        color = background, tonalElevation = 3.dp, shadowElevation = 2.dp) { readerTools() }
                    else GlassActionPanel(readerBackdrop, toolsModifier, surfaceColor = background) { readerTools() }
                    }
                }
            }
        }
        if (showAttrib) AttributionDialog(article, onDismiss = { showAttrib = false })
        // 就地笔记覆盖在正文下方，可收起继续滚动原文；退出保留本机草稿。
        noteDraft?.let { draft ->
            val node = if (draft.isWholeArticle) null else nodes.getOrNull(draft.nodeIndex)
            val quote = node?.let { contentKey.substring(it.startOffset, it.endOffset).trim().take(200) }.orEmpty()
            val existing = draft.noteId?.let { key -> pageNotes.firstOrNull { it.key == key } }
                ?: if (draft.isWholeArticle) wholeNotes.firstOrNull() else null
            ReaderNotePanel(
                draftIdentity = "$rootId\u0000$documentPath\u0000${draft.nodeIndex}\u0000${draft.noteId.orEmpty()}\u0000$readingRevision",
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding(),
                title = buildString {
                    append(article.title)
                    documentPath.substringAfterLast('/').removeSuffix(".md")
                        .takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                },
                quote = quote,
                initialBody = existing?.text.orEmpty(),
                onDismiss = { noteDraft = null },
                onDelete = existing?.let { record -> { library.removeNote(record.key); noteDraft = null; libraryChanged() } },
                onSave = { body ->
                    val start = node?.startOffset ?: -1
                    val end = node?.endOffset ?: -1
                    library.upsertNote(existing?.key, rootId, documentPath, start, end, quote,
                        contentKey.substring((start - 24).coerceAtLeast(0), start.coerceAtLeast(0)), body)
                    noteDraft = null
                    libraryChanged()
                },
            )
        }
        if (sheet != 0) ModalBottomSheet(onDismissRequest = { sheet = 0 }, containerColor = background,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = englishReading)) {
            if (sheet == 1) ReaderToc(headings, currentHeading?.index, paper.muted, paper.fg,
                chapters = chapters, onChapter = { document -> sheet = 0; onChapter(document) },
                // 在读某一章时默认给「本页目录」，而不是又一张全书章节表
                defaultBookTab = chapters.isNotEmpty() && !isChapter,
                isCurrent = isCurrentChapter,
                onSelect = { index -> sheet = 0; showProjectDetails = true; scope.launch { listState.animateScrollToItem(if (index == firstTitle) 0 else index + 1) } })
            else if (sheet == 3) ReaderLicenses()
            else if (sheet == 4) ReaderMarks(
                paper = paper,
                bookmarks = pageBookmarks,
                notes = pageNotes.filterNot { it.isWholeArticle },
                wholeNotes = wholeNotes,
                onJump = { index -> sheet = 0; showProjectDetails = true; scope.launch { listState.animateScrollToItem(index + 1) } },
                onEditNote = { record -> sheet = 0; noteDraft = NoteDraft(
                    if (record.isWholeArticle) -1 else nodes.indexOfFirst { it.startOffset == record.startOffset },
                    record.key) },
                onAddWholeNote = { sheet = 0; noteDraft = NoteDraft(-1, wholeNotes.firstOrNull()?.key) },
                onDeleteBookmark = { id -> library.removeBookmark(id); libraryChanged() },
                onDeleteNote = { id -> library.removeNote(id); libraryChanged() },
            )
            else ReaderSettings(fontSize, lineHeight, paperStyle,
                onFont = { fontSize = it.coerceIn(13, 28); if (englishReading) store.englishFontSize = fontSize else store.fontSize = fontSize },
                onLine = { lineHeight = it; if (englishReading) store.englishLineHeight = it else store.lineHeight = it },
                onPaper = { id -> paperStyle = id; store.paperStyle = id }, englishReading = englishReading, onClose = { sheet = 0 })
        }
        // 宿主浮层画在最后 = 画在最上面，且与正文同一个窗口、同一份纸张配色。
        overlay()
        }
        }
    }
}

@Composable
private fun ReaderHeader(title: String, onClose: () -> Unit, fg: Color, onSearch: () -> Unit, onAttrib: () -> Unit,
    onAiSearch: () -> Unit, onOriginal: () -> Unit, onTranslate: (() -> Unit)? = null,
    onAskSection: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习页", tint = fg) }
        Text(title, fontSize = 13.sp, color = fg.copy(alpha = 0.65f), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "文章内搜索", tint = fg.copy(alpha = 0.75f)) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "阅读选项", tint = fg.copy(alpha = 0.75f)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // 先给「问这一节」：范围最明确，也是 §6.3 A2 要求的那条路。
                onAskSection?.let { callback -> DropdownMenuItem(text = { Text("问 AI（这一节）") }, onClick = { menu = false; callback() }) }
                DropdownMenuItem(text = { Text("AI 搜索这篇文章") }, onClick = { menu = false; onAiSearch() })
                onTranslate?.let { callback -> DropdownMenuItem(text = { Text("AI 翻译这篇文章") }, onClick = { menu = false; callback() }) }
                DropdownMenuItem(text = { Text("打开原始页面") }, onClick = { menu = false; onOriginal() })
                DropdownMenuItem(text = { Text("作者与开源许可") }, onClick = { menu = false; onAttrib() })
            }
        }
    }
}

@Composable
private fun ReaderSearchBar(query: String, onQuery: (String) -> Unit, counter: String,
    hasMatches: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onQuery,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                decorationBox = { input -> Box { if (query.isEmpty()) Text("查找文章内容", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp); input() } },
            )
            Text(if (query.isBlank()) "" else counter, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onPrevious, enabled = hasMatches) { Icon(Icons.Outlined.KeyboardArrowUp, "上一匹配段落") }
            IconButton(onClick = onNext, enabled = hasMatches) { Icon(Icons.Outlined.KeyboardArrowDown, "下一匹配段落") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭文章搜索") }
        }
    }
}

@Composable
private fun ReaderArticleHeading(article: LearningArticle, paper: PaperPreset) {
    val context = LocalContext.current
    Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
        Text(article.title, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, color = paper.fg,
            fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp)
        Text("${article.author} · ${article.licenseLabel}", fontSize = 12.sp, lineHeight = 20.sp,
            color = paper.muted, modifier = Modifier.padding(top = 10.dp))
        if (article.kind == "LINK") OutlinedButton(onClick = { openReadingLink(context, article.sourceUrl) }, modifier = Modifier.padding(top = 14.dp)) {
            Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(17.dp))
            Text(if (article.isGitHub) "阅读 GitHub 原文" else "阅读原文", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ReaderCode(code: String, language: String?, style: TextStyle) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(vertical = 10.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language ?: "代码", fontSize = 11.sp, color = muted, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("代码", code))
                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Outlined.ContentCopy, "复制代码", modifier = Modifier.size(16.dp), tint = muted) }
            }
            Text(code, style = style.copy(fontSize = style.fontSize, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
        }
    }
}

@Composable
private fun ArticleSource(article: LearningArticle, paper: PaperPreset, onShowAttrib: () -> Unit, onLicenses: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp)) {
        HorizontalDivider(color = paper.muted.copy(alpha = 0.16f))
        Text("${article.author} · ${article.licenseLabel}", fontSize = 12.sp, color = paper.muted, modifier = Modifier.padding(top = 16.dp))
        FlowRow(Modifier.padding(top = 6.dp)) {
            if (article.sourceUrl.isNotBlank()) TextButton(onClick = { openReadingLink(context, article.documentUrl) }) { Text("打开原文") }
            TextButton(onClick = onShowAttrib) { Text("署名与许可") }
            TextButton(onClick = onLicenses) { Text("开源声明") }
        }
    }
}

@Composable
private fun ReaderLicenses() {
    val context = LocalContext.current
    val license by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/markdown-renderer.txt").bufferedReader().use { it.readText() } }
                .getOrDefault("许可文件暂时无法读取")
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("阅读器开源声明", style = MaterialTheme.typography.titleLarge)
        Text("multiplatform-markdown-renderer 0.45.0\nCopyright 2022 Mike Penz · Apache-2.0\n包含 Copyright 2021 Erik Hellman · MIT 部分。\n已修改：工具栏、目录、按段落查找、纸张主题和阅读进度。",
            fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
        TextButton(onClick = { openReadingLink(context, "https://github.com/mikepenz/multiplatform-markdown-renderer") }) { Text("查看开源项目") }
        SelectionContainer { Text(license ?: "正在读取许可…", fontSize = 11.sp, lineHeight = 18.sp) }
    }
}

@Composable
private fun AttributionDialog(article: LearningArticle, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("完整归属") },
        text = {
            SelectionContainer { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("作者：${article.author}", fontSize = 13.sp)
                Text("许可：${article.licenseId}", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Text("来源版本：${article.sourceRevision}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text("改动：${article.changeNote}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("署名：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(article.attribution, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                if (article.sourceUrl.isNotBlank()) Text("原文地址：${article.sourceUrl}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                if (article.licenseUrl.isNotBlank()) Text("许可地址：${article.licenseUrl}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            } }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ReaderTools(article: LearningArticle, paper: PaperPreset, favorite: Boolean, marks: Int,
    marking: Boolean, deepDiving: Boolean, deepDiveAvailable: Boolean, onFavorite: () -> Unit,
    onToc: () -> Unit, onSettings: () -> Unit,
    onMarks: () -> Unit, onBookmark: () -> Unit, onMarking: () -> Unit,
    onDeepDive: () -> Unit, onCopy: () -> Unit, englishReading: Boolean = false,
    onTranslate: (() -> Unit)? = null) {
    var more by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        if (englishReading) {
            onTranslate?.let { ReaderTool(Icons.Outlined.Translate, "翻译", paper.fg, it) }
            ReaderTool(Icons.Outlined.TextFields, "排版", paper.fg, onSettings)
        } else ReaderTool(Icons.Outlined.FormatListBulleted, "目录", paper.fg, onToc)
        ReaderTool(Icons.Outlined.StickyNote2, "笔记", if (marks > 0) NoteAccent else paper.fg, onMarks)
        if (deepDiveAvailable && !englishReading) ReaderTool(Icons.Outlined.AutoStories,
            if (deepDiving) "退出精读" else "精读", if (deepDiving) DeepDiveAccent else paper.fg, onDeepDive)
        Box {
            ReaderTool(Icons.Outlined.MoreHoriz, "更多", paper.fg) { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (englishReading) {
                    DropdownMenuItem(text = { Text("文章目录") }, onClick = { more = false; onToc() })
                    if (deepDiveAvailable) DropdownMenuItem(text = { Text(if (deepDiving) "关闭点词与选句" else "开启点词与选句") },
                        onClick = { more = false; onDeepDive() })
                } else DropdownMenuItem(text = { Text("字号与纸张") }, onClick = { more = false; onSettings() })
                DropdownMenuItem(text = { Text("添加当前位置书签") }, onClick = { more = false; onBookmark() })
                DropdownMenuItem(text = { Text(if (marking) "退出划线" else "划线写想法") }, onClick = { more = false; onMarking() })
                DropdownMenuItem(text = { Text(if (favorite) "取消收藏文章" else "收藏文章") }, onClick = { more = false; onFavorite() })
                if (article.sourceUrl.isNotBlank()) DropdownMenuItem(text = { Text(if (article.isGitHub) "复制 GitHub 原文链接" else "复制原文链接") },
                    onClick = { more = false; onCopy() })
            }
        }
    }
}

@Composable
private fun ReaderTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    tint: Color, onClick: () -> Unit) {
    Column(Modifier.heightIn(min = 52.dp).widthIn(min = 64.dp).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, color = tint, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 划线模式下的提示条。用户得知道现在点段落会发生什么。 */
@Composable
private fun MarkingBar(paper: PaperPreset, notes: Int, onDone: () -> Unit) {
    Surface(color = NoteAccent.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Edit, null, tint = NoteAccent, modifier = Modifier.size(16.dp))
            Text(
                if (notes > 0) "划线模式 · 点一段写想法，本页已有 $notes 条" else "划线模式 · 点一段写下你的想法",
                fontSize = 12.sp, color = paper.fg, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = onDone) { Text("完成", fontSize = 12.sp, color = NoteAccent) }
        }
    }
}

/** 精读模式下的提示条。和 [MarkingBar] 同一个范式：用户得知道现在点段落会发生什么。 */
@Composable
private fun DeepDiveBar(paper: PaperPreset, onDone: () -> Unit) {
    Surface(color = DeepDiveAccent.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("点单词查词 · 长按句子分析", fontSize = 12.sp, color = paper.fg,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("知道了", fontSize = 12.sp, color = DeepDiveAccent) }
        }
    }
}

/** 书签与笔记面板：整篇笔记 / 本页划线 / 本页书签。 */
@Composable
private fun ReaderMarks(
    paper: PaperPreset,
    bookmarks: List<ReadingBookmarkRecord>,
    notes: List<ReadingNoteRecord>,
    wholeNotes: List<ReadingNoteRecord>,
    onJump: (Int) -> Unit,
    onEditNote: (ReadingNoteRecord) -> Unit,
    onAddWholeNote: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
        Text("书签与笔记", style = MaterialTheme.typography.titleLarge, color = paper.fg,
            modifier = Modifier.padding(vertical = 12.dp))
        TextButton(onClick = onAddWholeNote) {
            Text(if (wholeNotes.isEmpty()) "写一条整篇笔记" else "编辑整篇笔记", color = NoteAccent)
        }
        wholeNotes.forEach { note ->
            MarkRow(paper = paper, accent = NoteAccent, label = "整篇笔记", body = note.text,
                quote = null, onEdit = { onEditNote(note) }, onJump = null, onDelete = { onDeleteNote(note.key) })
        }
        if (bookmarks.isEmpty() && notes.isEmpty() && wholeNotes.isEmpty()) {
            Text("还没加过书签。读书时点书签按钮存位置，点「划线」写想法。", color = paper.muted,
                fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        if (notes.isNotEmpty()) {
            Text("本页划线（${notes.size}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = paper.muted, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            notes.forEach { note ->
                MarkRow(paper = paper, accent = NoteAccent, label = null, body = note.text, quote = note.quote,
                    onEdit = { onEditNote(note) }, onJump = { onJump(note.startOffset) },
                    onDelete = { onDeleteNote(note.key) })
            }
        }
        if (bookmarks.isNotEmpty()) {
            Text("本页书签（${bookmarks.size}）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = paper.muted, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            bookmarks.forEach { bookmark ->
                MarkRow(paper = paper, accent = Color(0xFFD97706), label = "书签", body = bookmark.text,
                    quote = null, onEdit = { onJump(bookmark.index) }, onJump = { onJump(bookmark.index) },
                    onDelete = { onDeleteBookmark(bookmark.key) })
            }
        }
    }
}

@Composable
private fun MarkRow(
    paper: PaperPreset,
    accent: Color,
    label: String?,
    body: String,
    quote: String?,
    onEdit: () -> Unit,
    onJump: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = paper.muted.copy(alpha = 0.07f),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onEdit)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
            Box(Modifier.width(3.dp).heightIn(min = 34.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                if (label != null) Text(label, fontSize = 10.sp, color = accent, fontWeight = FontWeight.SemiBold)
                quote?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = paper.muted, modifier = Modifier.padding(top = 2.dp))
                }
                Text(body.ifBlank { "（空）" }, fontSize = 13.sp, lineHeight = 20.sp, color = paper.fg,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (label != null || !quote.isNullOrBlank()) 4.dp else 0.dp))
                if (onJump != null) TextButton(onClick = onJump,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text("跳到原文", fontSize = 11.sp, color = accent)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, "删除", tint = paper.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ReaderToc(headings: List<ReaderHeading>, currentIndex: Int?, muted: Color, fg: Color,
    chapters: List<ReaderDocument>, onChapter: (ReaderDocument) -> Unit, onSelect: (Int) -> Unit,
    defaultBookTab: Boolean = true, isCurrent: (ReaderDocument) -> Boolean = { false }) {
    var bookTab by remember { mutableStateOf(chapters.isNotEmpty() && defaultBookTab) }
    if (chapters.isNotEmpty()) Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = bookTab, onClick = { bookTab = true }, label = { Text("全书章节") })
        FilterChip(selected = !bookTab, onClick = { bookTab = false }, label = { Text("本页目录") })
    }
    if (bookTab && chapters.isNotEmpty()) {
        Text("全书章节 · ${chapters.size} 章", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(chapters, key = { it.path }) { chapter ->
                val reading = isCurrent(chapter)
                Row(Modifier.fillMaxWidth().clickable(enabled = !reading) { onChapter(chapter) }
                    .heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(chapter.title, color = if (reading) NoteAccent else fg, fontSize = 15.sp,
                        lineHeight = 23.sp, fontWeight = if (reading) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (reading) Text("正在读", fontSize = 12.sp, color = NoteAccent)
                }
            }
        }
        return
    }
    Text("文章目录（${headings.size}）", style = MaterialTheme.typography.titleLarge, color = fg, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    if (headings.isEmpty()) Text("这篇资料没有章节标题", color = muted, modifier = Modifier.padding(24.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        itemsIndexed(headings) { _, item ->
            Text(item.title, color = if (item.index == currentIndex) MaterialTheme.colorScheme.primary else fg,
                fontWeight = if (item.index == currentIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(item.index) }.heightIn(min = 48.dp)
                    .padding(start = 24.dp + 12.dp * (item.level - 1).coerceIn(0, 5), end = 24.dp, top = 12.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun ReaderSettings(fontSize: Int, lineHeight: Float, paperStyle: String,
    onFont: (Int) -> Unit, onLine: (Float) -> Unit, onPaper: (String) -> Unit, englishReading: Boolean = false, onClose: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("阅读设置", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            if (englishReading) TextButton(onClick = onClose) { Text("完成") }
        }
        TextButton(onClick = { onFont(if (englishReading) 19 else 16); onLine(if (englishReading) 1.8f else 1.6f); onPaper("default") }) { Text("恢复推荐排版") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", modifier = Modifier.weight(1f)); Text("$fontSize sp")
        }
        Slider(value = fontSize.toFloat(), onValueChange = { onFont(it.toInt()) }, valueRange = 13f..28f, steps = 14)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("行距", modifier = Modifier.weight(1f)); Text(String.format(java.util.Locale.ROOT, "%.2f 倍", lineHeight))
        }
        Slider(value = lineHeight, onValueChange = onLine, valueRange = 1.5f..2.2f)
        Spacer(Modifier.height(8.dp))
        Text("纸张背景", modifier = Modifier.padding(bottom = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(paperPresets) { preset ->
                val selected = preset.id == paperStyle
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = preset.bg,
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(width = 78.dp, height = 56.dp).clickable { onPaper(preset.id) },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(preset.chip, color = preset.fg, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("键盘 Page Up / Page Down 按屏幕翻页", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 页内翻译条（docs/plan/37 B1）：原文 / 译文 / 双语三态切换 + 进度与引导。
 * 内嵌在阅读器顶栏下方，不是独立层 —— 切模式不离开当前页面。
 */
@Composable
private fun TranslateBar(paper: PaperPreset, mode: Int, onMode: (Int) -> Unit,
    translated: Int, total: Int, cached: Int, running: Boolean,
    needsModel: Boolean, error: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    Surface(color = paper.muted.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                listOf("原文" to 0, "译文" to 1, "双语" to 2).forEach { (label, m) ->
                    FilterChip(selected = mode == m, onClick = { onMode(m) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.padding(end = 6.dp).heightIn(min = 30.dp))
                }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, "返回原文", tint = paper.muted, modifier = Modifier.size(20.dp))
                }
            }
            Text(
                    when {
                        running -> "翻译中 ${translated}/${total}"
                        needsModel && translated < total -> "已缓存 ${translated}/${total} 段"
                        else -> "已译 ${translated}/${total} 段" + if (cached > 0) " · 其中缓存 $cached" else ""
                    },
                    fontSize = 11.sp, color = paper.muted,
                    modifier = Modifier.padding(end = 12.dp, bottom = 4.dp))
            if (needsModel && translated < total && !running) {
                Text("还有段落没人翻过。到「学习 → 英语学习 → AI 设置」填入你自己的接口（DeepSeek / 智谱 / Kimi 等）后点重试，费用计入你的账户；已缓存的段落不受影响。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = paper.muted,
                    modifier = Modifier.padding(end = 12.dp, bottom = 2.dp))
            }
            if (error != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("重试", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 译文块：淡色底 + 左侧细线，与原文同一阅读流、同一套 Markdown 排版。
 * 纯译文模式下它顶替原文；双语模式下跟在原文后面。
 */
@Composable
private fun TranslatedBlock(text: String, typography: MarkdownTypography, components: MarkdownComponents) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)
        .background(tint, RoundedCornerShape(6.dp))
        .drawBehind {
            drawRect(color = accent,
                size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height))
        }
        .padding(start = 10.dp, top = 4.dp, bottom = 2.dp, end = 6.dp)) {
        Markdown(text, typography = typography, components = components, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * 截取「当前这一节」的正文，供 B5 A2 的范围问答使用。
 *
 * 为什么只给一节、不给全文：§6.2 明确要求「全文/整库上传前明确范围」。
 * 一次把整篇长文丢给模型既贵又慢，而且用户问的通常是眼前这一段。
 *
 * 范围 = 从当前标题节点开始，到**下一个同级或更高级标题**之前。
 * 找不到标题（用户停在开头）就从第一个节点开始；整篇截到上限为止。
 * 截断时如实加一行说明 —— 不假装给全了（模型与用户都该知道只看到一部分）。
 */
private fun currentSectionText(
    source: String,
    nodes: List<org.intellij.markdown.ast.ASTNode>,
    headingNodeIndex: Int,
    maxChars: Int = 4000,
): String {
    if (nodes.isEmpty()) return source.take(maxChars)
    val startIdx = if (headingNodeIndex in nodes.indices) headingNodeIndex else 0
    val startOffset = nodes[startIdx].startOffset
    // 找下一个标题节点（标题层级由 type 决定；这里只按「节点类型是否为标题」判断即可）。
    val endOffset = nodes.drop(startIdx + 1)
        .firstOrNull { it.type.toString().contains("ATX_HEADING") || it.type.toString().contains("SETEXT") }
        ?.startOffset
        ?: source.length
    val slice = source.substring(startOffset.coerceIn(0, source.length), endOffset.coerceIn(0, source.length)).trim()
    return if (slice.length <= maxChars) slice else slice.take(maxChars) + "\n（这一节较长，以上只截取了前面一部分）"
}
