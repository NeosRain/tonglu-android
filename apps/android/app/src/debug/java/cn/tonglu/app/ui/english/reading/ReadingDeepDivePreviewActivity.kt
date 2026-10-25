package cn.tonglu.app.ui.english.reading

import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.english.reading.ReadingAnalysisCodec
import cn.tonglu.app.data.english.reading.ReadingSentence
import cn.tonglu.app.data.english.reading.readingTokens
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 仅 debug 变体的精读浮层走查入口。
 *
 * 为什么需要它：`ui/english/reading/` 下的组件是阅读器执行包交的独立实现，**当前没有调用点**
 * （正文选区动作、真实分析与对话链路由主会话接进 ArticleReader，见 EXECUTION-READER.md §三）。
 * 没有入口就无法在真机上渲染、返回与截图，所以本 Activity 只做「把夹具挂上去」这一件事。
 *
 * 🔴 注意：KDoc 里不要写「斜杠 + 双星号」的路径通配符 —— Kotlin 的块注释可以嵌套，
 * 那个写法会开启一层嵌套注释，而编译器报的却是「文件末尾 Unclosed comment」，很难反查。
 *
 * 🔴 边界：本文件在 `src/debug`，不进 release 包；不读登录态、不读数据库、**不发起任何网络请求**。
 * 结构分析结果是本机 JSON 夹具，但**照常走 ReadingAnalysisCodec.decode 校验**（只渲染校验通过的结果）；
 * 对话永远停在用户点击之后，未配置时如实显示 NeedsConfig。
 * 演示正文是自己写的模拟文章，界面与日志都标明「模拟」，不冒充教材或线上内容。
 *
 * 走查用法：
 *   adb -P 5038 -s 8b4766a5 shell am start -n cn.tonglu.app/.ui.english.reading.ReadingDeepDivePreviewActivity \
 *     --es scene structure|failure|noconfig|conversation|wordcard [--ez dark true|false]
 */
class ReadingDeepDivePreviewActivity : ComponentActivity() {
    private var previousMode = ThemeMode.System

    /** 走查不改用户真实主题：读设置也重定向到独立文件。 */
    private val previewContext by lazy {
        object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("reader_preview_" + name, mode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previousMode = AppTheme.modeState
        AppTheme.setEnabled(previewContext, intent.getBooleanExtra("dark", false))
        val scene = intent.getStringExtra(EXTRA_SCENE) ?: SCENE_STRUCTURE
        setContent {
            val scheme = if (AppTheme.darkState) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
                    PreviewScreen(scene)
                }
            }
        }
    }

    override fun onDestroy() {
        AppTheme.setMode(previewContext, previousMode)
        super.onDestroy()
    }
}

internal const val EXTRA_SCENE = "scene"
internal const val SCENE_STRUCTURE = "structure"
internal const val SCENE_FAILURE = "failure"
internal const val SCENE_NOCONFIG = "noconfig"
internal const val SCENE_CONVERSATION = "conversation"
internal const val SCENE_WORDCARD = "wordcard"

/** 本机模拟正文；来源与许可是「本文件作者自写」，不得当作教材或线上文章引用。 */
private const val PREVIEW_ATTRIBUTION = "阅读演示 · 本机模拟文章（非真实教材，不联网，结果不入库）"
private const val PREVIEW_TITLE = "模拟文章 · 一句复合句"
private const val PREVIEW_BODY = """Reading a sentence becomes easier when you can see how its parts fit together.

We help them because the map on the wall shows which road leads to the campus.

The map on the wall is old, but the road it shows is the one we take every morning."""

/** 被选中的目标句；第三段同样含 map / road，用来验「不跳到同名第一词」。 */
private const val PREVIEW_SENTENCE =
    "We help them because the map on the wall shows which road leads to the campus."

/** 关注词：句中出现两次（the / map），取 map 验偏移定位。 */
private const val PREVIEW_FOCUS_WORD = "map"

/** 夹具分析结果（走 Codec 校验；成分有意做成嵌套，验「嵌套不覆盖文字」）。 */
private const val PREVIEW_ANALYSIS_JSON = """
{
  "translation": "我们帮助他们，因为墙上的地图标出了哪条路通向校园。",
  "summary": "主句是主谓宾结构，后面跟 because 引导的原因状语从句，从句内部又套了一个宾语从句。",
  "components": [
    {"role": "主语", "tokenIds": [0], "explanation": "We 是 help 这个动作的发出者"},
    {"role": "谓语", "tokenIds": [1], "explanation": "help 是主句的谓语动词"},
    {"role": "宾语", "tokenIds": [2], "explanation": "them 是 help 的承受者"},
    {"role": "从句", "tokenIds": [3,4,5,6,7,8,9,10,11,12,13,14,15], "explanation": "because 引导的原因状语从句，回答「为什么」"},
    {"role": "主语", "tokenIds": [4,5,6,7,8], "explanation": "从句的主语：the map on the wall"},
    {"role": "谓语", "tokenIds": [9], "explanation": "从句的谓语：shows"},
    {"role": "宾语", "tokenIds": [10,11,12,13,14,15], "explanation": "shows 的宾语，本身又是一个从句"}
  ],
  "uncertainty": "「the map on the wall」整段作主语；其中介词短语是否再单列为定语存在不同划分。"
}
"""

@Composable
private fun PreviewScreen(scene: String) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val configReady = scene != SCENE_NOCONFIG

    val selection = remember {
        val start = PREVIEW_BODY.indexOf(PREVIEW_SENTENCE)
        require(start >= 0) { "夹具正文与目标句不一致" }
        val end = start + PREVIEW_SENTENCE.length
        // 用契约 API 构造选区，不手搓偏移；wordOffset 相对句子。
        val sentence = ReadingSentence.select("preview-article", "rev-1", PREVIEW_BODY, start, end)
        require(sentence.text == PREVIEW_SENTENCE) { "夹具选区偏移不自洽" }
        val tokens = readingTokens(sentence)
        val offset = tokens.firstOrNull { it.text == PREVIEW_FOCUS_WORD }?.start
        require(offset != null) { "夹具关注词不在句中" }
        ReaderSelection.of("preview-article", "rev-1", PREVIEW_BODY, start, end, offset)
    }

    val state = remember {
        ReadingDeepDiveState("preview-owner", { configReady }).also { st ->
            st.onAnalyzeRequested = { requestId, selected ->
                scope.launch {
                    delay(700)
                    if (scene == SCENE_FAILURE) {
                        st.analysisFailed(requestId, "本地夹具：模拟失败，未发出任何网络请求")
                    } else {
                        runCatching {
                            ReadingAnalysisCodec.decode(PREVIEW_ANALYSIS_JSON, selected.tokens)
                        }.onSuccess { st.analysisSucceeded(requestId, it) }
                            .onFailure { st.analysisFailed(requestId, it.message ?: "夹具解析失败") }
                    }
                }
            }
        }
    }

    // 键盘可见性由宿主随窗口 insets 更新，返回顺序第一步消费它。
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) { state.keyboardVisible = imeVisible }

    // 场景直达：走查脚本按 scene 启动即可落在目标状态上。
    LaunchedEffect(scene) {
        when (scene) {
            SCENE_STRUCTURE, SCENE_FAILURE -> {
                state.openStructure(selection)
                state.requestAnalysis()
            }
            SCENE_NOCONFIG -> {
                state.openStructure(selection)
                state.requestAnalysis()
            }
            SCENE_CONVERSATION -> state.openConversation(ReaderEntry.BODY, selection, PREVIEW_TITLE)
            SCENE_WORDCARD -> state.openWordCard(selection)
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(20.dp)) {
            Text("精读浮层走查 · 仅 debug 变体", fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface)
            Text(PREVIEW_ATTRIBUTION, fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
            Text(PREVIEW_BODY, fontSize = 17.sp, lineHeight = 28.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp))
            Text("当前场景：$scene（owner=preview-owner，配置=${if (configReady) "有" else "无"}）",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp))
        }

        if (!state.overlayActive) {
            PreviewControls(
                modifier = Modifier.align(Alignment.BottomCenter),
                onStructure = { state.openStructure(selection) },
                onWordCard = { state.openWordCard(selection) },
                onConversation = { state.openConversation(ReaderEntry.BODY, selection, PREVIEW_TITLE) },
            )
        }

        if (state.overlayActive) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ReadingDeepDiveContent(
                    state = state,
                    articleTitle = PREVIEW_TITLE,
                    onCollapseKeyboard = { keyboard?.hide() },
                    onOverlayDismissed = { OverlayDismissGuard.arm() },
                    onOpenAiSettings = { /* 走查入口不接真实设置页导航 */ },
                )
            }
        }
    }
}

@Composable
private fun PreviewControls(
    modifier: Modifier = Modifier,
    onStructure: () -> Unit,
    onWordCard: () -> Unit,
    onConversation: () -> Unit,
) {
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom) {
        TextButton(onClick = onStructure) { Text("打开结构面板") }
        TextButton(onClick = onWordCard) { Text("打开词卡插槽") }
        TextButton(onClick = onConversation) { Text("从正文打开对话") }
    }
}
