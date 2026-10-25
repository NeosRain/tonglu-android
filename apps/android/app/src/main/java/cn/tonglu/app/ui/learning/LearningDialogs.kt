package cn.tonglu.app.ui.learning

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import cn.tonglu.app.data.*
import cn.tonglu.app.data.english.EnglishAiConfig
import cn.tonglu.app.data.english.EnglishAiTurn
import cn.tonglu.app.data.english.EnglishRepository
import cn.tonglu.app.data.english.TodayQueueRules
import cn.tonglu.app.data.english.reading.ReadingAiContext
import cn.tonglu.app.data.english.reading.ReadingAnalysisDatabase
import cn.tonglu.app.data.english.reading.ReadingAnalysisService
import cn.tonglu.app.data.english.reading.ReadingConversationLoad
import cn.tonglu.app.data.english.books.PersonalBookWord
import cn.tonglu.app.data.english.books.PersonalWordBookStore
import cn.tonglu.app.data.english.reading.RoomReadingAnalysisCache
import cn.tonglu.app.data.english.reading.RoomReadingConversationStore
import cn.tonglu.app.data.speech.Accent
import cn.tonglu.app.data.speech.SpeechCatalog
import cn.tonglu.app.data.speech.SpeechPreference
import cn.tonglu.app.data.speech.SpeechPurpose
import cn.tonglu.app.data.speech.SpeechRequest
import cn.tonglu.app.data.speech.SpeechResult
import cn.tonglu.app.data.speech.SpeechService
import cn.tonglu.app.ui.*
import cn.tonglu.app.ui.english.contextword.SpokenAccent
import cn.tonglu.app.ui.english.contextword.VoiceState
import cn.tonglu.app.ui.english.contextword.isStaleVoiceResult
import cn.tonglu.app.ui.english.reading.BackOutcome
import cn.tonglu.app.ui.english.reading.DeepDiveBase
import cn.tonglu.app.ui.english.reading.ReaderEntry
import cn.tonglu.app.ui.english.reading.ReaderSelection
import cn.tonglu.app.ui.english.reading.ReadingChatMessage
import cn.tonglu.app.ui.english.reading.ReadingDeepDiveContent
import cn.tonglu.app.ui.english.reading.ReadingDeepDiveState
import cn.tonglu.app.ui.english.reading.ReadingConversationState
import cn.tonglu.app.ui.listening.ListeningScreen
import com.google.gson.Gson
import kotlinx.coroutines.launch

/** The nine learning layers. Child layers retain their parent in the same composition slot. */
internal sealed interface LearningDialog {
    data object None : LearningDialog
    data class Reader(val articleId: String, val documentPath: String? = null,
        val anchor: String? = null, val index: Int = -1) : LearningDialog
    data class Ai(val articleId: String? = null, val reader: Reader? = null) : LearningDialog
    data object Updates : LearningDialog
    data object Graph : LearningDialog
    /**
     * `query` 由精听跟读那边"去英语页查这个词"带过来，为空就是正常打开。
     * `parent` 是**关掉查词之后回哪一层**。以前用 `fromListening` 布尔值代劳，
     * 布尔值只认两个去处，于是英语 Hub 里的查词也被硬塞成「来自精听」—— 返回时
     * Hub 整个被卸载，落到一个空精听层上（FINDINGS #6）。现在：
     * - 英语 Hub 里的查词由 Hub 自己叠子层处理（见 EnglishHubScreen），**不走这里**；
     * - 这里只负责顶层两个来源，父层留在下面继续存活（照 Reader/Ai 的做法）——
     *   不然查完词一返回，材料、播放位置、已打的时间点、录音全没了。
     */
    data class English(val query: String = "", val parent: LearningDialog = None) : LearningDialog
    data object Listening : LearningDialog
    /**
     * 英语**唯一一级入口**（47 §2 / 48 §1）：学习页只有这一张卡进英语，卡片里再分
     * 「单词 / 听说」两个能力入口，复用原有两屏而不是各占一个学习页卡片。
     * `route` 记住用户上次停在哪个能力，返回学习页再进来不丢位置。
     *
     * `initialMaterialId` 只在「从广场资料卡加入学习后直接打开」这一条路径上有值
     * （47 §3 P4）：带着它进来就直达精听工作区的那份材料。
     */
    data class EnglishHub(val route: String = "home", val initialMaterialId: String? = null) : LearningDialog
    data object Reading : LearningDialog
    data class NoteEditor(val record: ReadingNoteRecord) : LearningDialog
    data class DeleteReading(val request: PendingDelete) : LearningDialog
}

private fun LearningDialog.save(): Bundle = Bundle().also { bundle ->
    when (this) {
        LearningDialog.None -> bundle.putString("kind", "none")
        is LearningDialog.Reader -> {
            bundle.putString("kind", "reader"); bundle.putString("article", articleId)
            bundle.putString("document", documentPath); bundle.putString("anchor", anchor)
            bundle.putInt("index", index)
        }
        is LearningDialog.Ai -> {
            bundle.putString("kind", "ai"); bundle.putString("article", articleId)
            bundle.putBundle("reader", reader?.save())
        }
        LearningDialog.Updates -> bundle.putString("kind", "updates")
        LearningDialog.Graph -> bundle.putString("kind", "graph")
        is LearningDialog.English -> {
            bundle.putString("kind", "english"); bundle.putString("query", query)
            bundle.putBundle("parent", parent.save())
        }
        LearningDialog.Listening -> bundle.putString("kind", "listening")
        is LearningDialog.EnglishHub -> {
            bundle.putString("kind", "englishhub"); bundle.putString("route", route)
            bundle.putString("initialMaterial", initialMaterialId)
        }
        LearningDialog.Reading -> bundle.putString("kind", "reading")
        // These two were remember-only in the shelf. Restore their parent, not an unfinished edit.
        is LearningDialog.NoteEditor, is LearningDialog.DeleteReading -> bundle.putString("kind", "reading")
    }
}

private fun restoreDialog(bundle: Bundle): LearningDialog = when (bundle.getString("kind")) {
    "reader" -> LearningDialog.Reader(requireNotNull(bundle.getString("article")),
        bundle.getString("document"), bundle.getString("anchor"), bundle.getInt("index", -1))
    "ai" -> LearningDialog.Ai(bundle.getString("article"),
        bundle.getBundle("reader")?.let { restoreDialog(it) as? LearningDialog.Reader })
    "updates" -> LearningDialog.Updates
    "graph" -> LearningDialog.Graph
    "english" -> LearningDialog.English(bundle.getString("query").orEmpty(),
        bundle.getBundle("parent")?.let { restoreDialog(it) } ?: LearningDialog.None)
    "listening" -> LearningDialog.Listening
    "englishhub" -> LearningDialog.EnglishHub(
        bundle.getString("route") ?: "home", bundle.getString("initialMaterial"))
    "reading" -> LearningDialog.Reading
    else -> LearningDialog.None
}

internal val LearningDialogSaver = Saver<LearningDialog, Bundle>(save = { it.save() }, restore = ::restoreDialog)

/** All callbacks that dismiss or replace a layer delegate to LearningScreen's guarded exit. */
@Composable
internal fun LearningDialogs(
    dialog: LearningDialog, catalog: LearningCatalog, store: ReadingStore,
    favorites: Set<String>, lastArticle: LearningArticle?, search: String, owner: String,
    signedIn: Boolean, onAsk: suspend (String, List<LearningExcerpt>, String) -> LearningAnswer,
    onTranslate: suspend (String?, List<String>) -> LearningBlockTranslation,
    onPublishNote: ((String, String) -> Unit)?,
    onShow: (LearningDialog) -> Unit, onClose: (LearningDialog) -> Unit,
    onOpen: (LearningDialog.Reader) -> Unit,
    onFavorite: (String, Boolean) -> Unit, onLibraryChanged: () -> Unit,
    /** 做题作答记录有变动（T5）：题库页 / 精听区都在这里冒泡，上层做防抖后推云端。 */
    onPracticeChanged: () -> Unit = {},
    /** 本次阅读停留秒数（plan/66 学习线 read.seconds）。只传秒数，不传读了哪篇。 */
    onReadingSeconds: (Int) -> Unit = {},
    /** 首次学会一个单词（plan/66 学习线 words.learned）。 */
    onWordLearned: () -> Unit = {},
) {
    when (dialog) {
        LearningDialog.None -> Unit
        is LearningDialog.Reader, is LearningDialog.Ai -> {
            val reader = (dialog as? LearningDialog.Reader) ?: (dialog as LearningDialog.Ai).reader
            // B5 A2：阅读器里「问这一节」。范围由 ArticleReader 截好再冒泡上来，
            // 这里只负责弹面板 —— 阅读器不直接持有 AI 密钥存储，避免两处各读一份配置。
            val context = androidx.compose.ui.platform.LocalContext.current
            val aiStore = androidx.compose.runtime.remember { cn.tonglu.app.data.english.EnglishAiStore(context) }
            val aiConfig = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(aiStore.load()) }
            val scoped = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<Pair<cn.tonglu.app.data.english.AiScope, String>?>(null) }
            val aiSettingsOpen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            // ---------- 精读：把阅读器的段落选区接到冻结的分析链路上（EXECUTION-READER.md §三）----------
            // 归属一律用**真实账号**：分析缓存与对话都按 owner 隔离（游客 / 两个账号之间互不可见）。
            val deepDive = androidx.compose.runtime.remember(owner) {
                ReadingDeepDiveState(owner) { aiConfig.value.ready() } }
            val analysis = androidx.compose.runtime.remember(owner) {
                ReadingAnalysisService(owner, RoomReadingAnalysisCache(ReadingAnalysisDatabase.of(context).dao())) }
            val deepDiveScope = androidx.compose.runtime.rememberCoroutineScope()
            // 分析动作出口：宿主开协程跑真实链路（读缓存 → 调模型 → 校验 → 写盘），
            // 结果按 requestId 回填；换句/换号之后才回来的迟到结果由状态机自己丢弃。
            SideEffect {
                deepDive.onAnalyzeRequested = { requestId, selection ->
                    deepDiveScope.launch {
                        try {
                            deepDive.analysisSucceeded(requestId, analysis.analyze(selection.sentence, aiConfig.value))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            // 不吞成「失败」两个字：密钥 / 余额 / 校验的具体原因要看得见
                            // （与 AiScopeDialog 同一条规矩）。
                            deepDive.analysisFailed(requestId, error.message ?: "分析失败，请稍后重试")
                        }
                    }
                }
            }
            // 换号：状态机随 `remember(owner)` 整个重建；老实例再显式清一次，
            // 让仍在飞的那次结果落到「已清空」的状态上（契约要求换号必清）。
            DisposableEffect(owner) { onDispose { deepDive.clearForAccountSwitch() } }

            // ---------- 我的精读文章（2026-10-13）----------
            // 自建文章不在 catalog 里 —— Reader 按 id 从 catalog 找文章，找不到就不会打开。
            // 只读当前章节；PDF/EPUB 上千页时不能把整个书架正文全部拉进内存。
            val myReadingRepository = remember(owner) { EnglishRepository(context) }
            var myReadingArticle by remember(owner, reader?.articleId) {
                androidx.compose.runtime.mutableStateOf<
                    cn.tonglu.app.data.english.MyReadingArticle?>(null) }
            LaunchedEffect(owner, reader?.articleId) {
                val id = reader?.articleId?.takeIf { it.startsWith("my-") } ?: return@LaunchedEffect
                try {
                    myReadingArticle = myReadingRepository.myArticle(id, owner)
                        ?: error("文章已不存在，请重新导入")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (cause: Exception) {
                    Toast.makeText(context, cause.message ?: "文章无法打开", Toast.LENGTH_LONG).show()
                    onClose(LearningDialog.None)
                }
            }

            // ---------- 对话持久化（C02 门面）：关闭重开继续对话 ----------
            // threadId 由 `ReadingAiContext` **派生**（句子快照 + 关注词），不是调用方给的 ——
            // 换句 / 换关注词就落到另一条线程，上一句的讨论不会串到这一句下面。
            val conversationStore = remember(owner) {
                RoomReadingConversationStore(ReadingAnalysisDatabase.of(context).dao()) }
            val conversationBridge = remember(owner) { ReadingConversationBridge(owner, conversationStore) }
            var restoredConversation by remember(owner) { mutableStateOf<ReadingConversationState?>(null) }
            var historyError by remember(owner) { mutableStateOf<String?>(null) }
            var saveError by remember(owner) { mutableStateOf<String?>(null) }
            var readRetry by remember(owner) { mutableStateOf(0) }
            var writeRetry by remember(owner) { mutableStateOf(0) }
            // 恢复完成之前不允许落库，也不发请求；新问题可先留在内存中等待。
            LaunchedEffect(deepDive.conversation, owner, readRetry) {
                restoredConversation = null
                historyError = null
                saveError = null
                val conversation = deepDive.conversation ?: return@LaunchedEffect
                val thread = ReadingAiContext(conversation.selection.sentence, conversation.selection.wordTokenId)
                try {
                when (val restored = conversationBridge.restore(thread)) {
                    is ReadingConversationLoad.Loaded -> {
                        // 库里存的应当都是「已完成」的消息；万一有一条 role 不合法的坏行，
                        // **单条丢掉**而不是让整段历史读不出来。
                        var dropped = 0
                        val history = restored.conversation.messages.mapNotNull { turn ->
                            try {
                                require(turn.content.isNotBlank() && turn.content.length <= 12000) { "历史消息内容无效" }
                                ReadingChatMessage(turn.role, turn.content)
                            }
                            catch (invalid: IllegalArgumentException) { dropped++; null }
                        }
                        if (deepDive.conversation !== conversation) return@LaunchedEffect
                        val hasNewQuestion = conversation.messages.isNotEmpty()
                        conversation.messages = history + conversation.messages
                        if (dropped > 0) Toast.makeText(context, "有${dropped}条无效历史未显示，其余对话已恢复", Toast.LENGTH_SHORT).show()
                        // 草稿也恢复：§4 要求「失败草稿保留」——用户没发出去的那句话不该因为退出就没了。
                        if (!hasNewQuestion && conversation.draft.isBlank()) conversation.draft = restored.conversation.draft
                        restoredConversation = conversation
                    }
                    is ReadingConversationLoad.Corrupt -> {
                        // 坏记录要**说出来**，不能静默当空 —— 静默会让人以为记录丢了。
                        historyError = "历史记录损坏，原记录已保留；请重试或联系反馈"
                    }
                    ReadingConversationLoad.Empty -> if (deepDive.conversation === conversation) restoredConversation = conversation
                }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    historyError = "读取历史失败，请重试"
                    android.util.Log.w("ReadingConversation", "读取对话历史失败", error)
                }
            }
            // 消息 / 草稿一变就落库。只存**已完成**的消息 ——
            // pending / failed 是瞬时状态：重启后「发送中」没有意义，失败那条也不该被当成历史
            // （用户会重试或丢掉它）。LaunchedEffect 在 key 变化时会取消上一次，
            // 所以快速输入天然只落最后一次，不用另加 debounce。
            LaunchedEffect(deepDive.conversation, restoredConversation, deepDive.conversation?.messages, deepDive.conversation?.draft, writeRetry) {
                val conversation = deepDive.conversation ?: return@LaunchedEffect
                if (conversation !== restoredConversation) return@LaunchedEffect
                val settled = conversation.messages.filter { !it.pending && !it.failed }
                val draft = readingConversationDraft(conversation)
                try {
                    // 写入短事务不随输入/关层取消，防止最后一份草稿刚提交就被撤销。
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { conversationBridge.save(
                        ReadingAiContext(conversation.selection.sentence, conversation.selection.wordTokenId),
                        settled.map { EnglishAiTurn(it.role, it.content) },
                        draft,
                    ) }
                    if (deepDive.conversation === conversation) saveError = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (deepDive.conversation === conversation) saveError = "保存对话失败，请重试"
                    android.util.Log.w("ReadingConversation", "保存对话失败", error)
                }
            }
            // 发送/重试才进入Sending；关闭、取消或换号会取消协程，迟到结果另做实例校验。
            LaunchedEffect(deepDive.conversation, deepDive.conversation?.phase, restoredConversation, owner) {
                val conversation = deepDive.conversation ?: return@LaunchedEffect
                if (conversation !== restoredConversation) return@LaunchedEffect
                val request = ReadingChatRequest.pending(conversation) ?: return@LaunchedEffect
                try {
                    val answer = conversationBridge.answer(request, aiConfig.value)
                    if (request.isCurrent(deepDive)) deepDive.chatSucceeded(answer)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (request.isCurrent(deepDive)) deepDive.chatFailed(error.message ?: "请求失败，请重试")
                }
            }
            // 浮层窗口的「存在」与状态机**解耦**：状态机说没了之后，还要把退场动画播完才真正摘窗口
            // （不这样就是「关层时窗口瞬间消失」，见 C11）。
            var deepDiveMounted by remember(owner) { mutableStateOf(false) }
            LaunchedEffect(deepDive.overlayActive) { if (deepDive.overlayActive) deepDiveMounted = true }
            // Keep the existing reader composed beneath AI. Its scroll, chapter and drafts survive BACK.
            reader?.let { target ->
                // 目录优先，查不到兜到自建精读文章（「我的精读文章」打开的 Reader 走这条）。
                (catalog.articles.firstOrNull { it.id == target.articleId }
                    ?: myReadingArticle?.takeIf { it.id == target.articleId }
                        ?.let { myReadingRepository.toLearningArticle(it) })?.let { article ->
                ArticleReader(article, store, favorite = article.id in favorites,
                    initialDocumentPath = target.documentPath, initialAnchor = target.anchor, initialIndex = target.index,
                    onAiSearch = { onShow(LearningDialog.Ai(article.id, target)) },
                    onAskAiScope = { scope, label ->
                        if (aiConfig.value.ready()) scoped.value = scope to label else aiSettingsOpen.value = true
                    },
                    onReadingSelection = { selection ->
                        // 原文句子/段落偏移 → 精读选区；关注词偏移跟着同一份原句走。
                        // 就地说明原因，不弹一个空浮层。
                        try {
                            val base = ReaderSelection.of(selection.articleId, selection.revision,
                                selection.original, selection.start, selection.end, selection.wordOffset)
                            // 精确点词直接带关注词；富文本降级没有关注词，仍可在原有面板选择。
                            when (selection.action) {
                                ReaderTapAction.Word -> deepDive.openWordCard(base)
                                ReaderTapAction.Structure -> deepDive.openStructure(base)
                            }
                        } catch (invalid: IllegalArgumentException) {
                            Toast.makeText(context, "这一段暂时无法解析：${invalid.message}",
                                Toast.LENGTH_SHORT).show()
                        }
                    },
                    // 浮层画在阅读器的**内容槽**里：同一个窗口、同一份纸张配色（「夜墨」不刺眼）。
                    overlay = {
                        if (deepDiveMounted) DeepDiveLayer(deepDive, owner, article.title,
                            aiConfig = { aiConfig.value },
                            conversationNotice = if (deepDive.conversation == null) null else historyError ?: saveError ?: if (restoredConversation !== deepDive.conversation) "正在恢复对话历史…" else null,
                            onRetryConversation = { if (historyError != null) readRetry++ else if (saveError != null) writeRetry++ },
                            onOpenAiSettings = { aiSettingsOpen.value = true },
                            onClosed = { deepDiveMounted = false })
                    },
                    onTranslate = onTranslate,
                    onReadingSeconds = onReadingSeconds,
                    onFavorite = { onFavorite(article.id, it) }, onLibraryChanged = onLibraryChanged,
                    onClose = { onClose(LearningDialog.None) })
            } }
            scoped.value?.let { pair ->
                val (scope, label) = pair
                // 归属必须是真实账号，不是字面量 "reader" —— 会话历史按 owner 分桶，
                // 写死一个常量会让两个账号共用同一份「问这一节」历史（原来的 bug，一并收掉）。
                AiScopeDialog(owner = owner, scopeKey = "reader:" + scope.label,
                    scope = scope, title = "问 AI · " + label, config = aiConfig.value,
                    quickQuestions = listOf("这一节在讲什么？", "帮我解释重点句", "这段里的生词有哪些？", "用简单的话再说一遍"),
                    onOpenSettings = { aiSettingsOpen.value = true }, onDismiss = { scoped.value = null })
            }
            if (aiSettingsOpen.value) EnglishAiSettingsDialog(store = aiStore, initial = aiConfig.value,
                onSaved = { aiConfig.value = it }, onDismiss = { aiSettingsOpen.value = false })
            if (dialog is LearningDialog.Ai) LearningSearchDialog(catalog,
                if (dialog.articleId == null) search else "", articleId = dialog.articleId, onAsk = onAsk,
                onOpen = { hit -> onOpen(LearningDialog.Reader(hit.article.id, hit.documentPath, hit.anchor)) },
                onClose = { onClose(dialog.reader ?: LearningDialog.None) })
        }
        LearningDialog.Updates -> TodayUpdatesScreen(catalog = catalog, recent = lastArticle,
            favorites = favorites, lastArticle = lastArticle,
            onOpen = { onOpen(LearningDialog.Reader(it.id)) }, onClose = { onClose(LearningDialog.None) })
        LearningDialog.Graph -> LearningGraphScreen(catalog = catalog,
            onOpenArticle = { onOpen(LearningDialog.Reader(it.id)) }, onClose = { onClose(LearningDialog.None) })
        // 精听层与「从精听点词进来的英语页」共用同一个分支：
        // 切过去查词时精听层**不失活**，返回时原样还在（材料 / 播放位置 / 打点 / 录音都留着）。
        is LearningDialog.Listening, is LearningDialog.English -> {
            val showListening = dialog is LearningDialog.Listening ||
                (dialog as? LearningDialog.English)?.parent is LearningDialog.Listening
            if (showListening) ListeningScreen(owner,
                onClose = { onClose(LearningDialog.None) },
                onLookupWord = { word -> onShow(LearningDialog.English(word, LearningDialog.Listening)) })
            // key：换一个词换一份全新界面，否则 rememberSaveable 会把上一个查过的词残留下来
            if (dialog is LearningDialog.English) androidx.compose.runtime.key(dialog.query) {
                EnglishStudyScreen(owner, initialQuery = dialog.query,
                    onWordLearned = onWordLearned,
                    onClose = { onClose(dialog.parent) })
            }
        }
        // 英语唯一一级入口：容器内切「单词 / 听说」，两屏各自复用，不重写。
        is LearningDialog.EnglishHub -> {
            val hubContext = androidx.compose.ui.platform.LocalContext.current
            // 🔴 进度必须**异步**读。`ReadingLibrary.articleProgress` 是同步的
            // `filesDir/reading/<ownerHash>.json` 读文件 + Gson 解析；直接写在参数求值里
            // 就是**每次 recomposition 都读一遍文件**，进英语页必卡
            // （走查时 logcat 实测 `Skipped 41 frames`）。用 produceState 挪到 IO 线程，
            // 并且按 (文章, owner) 缓存 —— 换文章/换号才重读。
            val englishReading by androidx.compose.runtime.produceState<Pair<LearningArticle, ReadingProgressRecord>?>(
                initialValue = null, lastArticle?.id, owner, catalog.revision,
            ) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val candidates = (catalog.articles + listOfNotNull(lastArticle))
                        .filter { cn.tonglu.app.ui.readingselection.isEnglishReadingArticle(it) }
                        .associateBy { it.id }
                    store.library.recentProgress(500).firstNotNullOfOrNull { record ->
                        candidates[record.article]?.let { it to record }
                    }
                }
            }
            val lastArticleRecord = englishReading?.second
            EnglishHubScreen(owner,
                initialRoute = dialog.route,
                initialMaterialId = dialog.initialMaterialId,
                onClose = { onClose(LearningDialog.None) },
                onPracticeChanged = onPracticeChanged,
                onWordLearned = onWordLearned,
                lastArticle = englishReading?.first,
                // 取不到记录 = 「还没读过」→ 按钮显示「开始精读」。
                // 🔴 不把「没有记录」当成 0 进度：那会让一篇从没打开过的文章显示「继续阅读」，
                //    用户点进去发现是空白的第一次阅读，会以为进度丢了。
                lastArticleRatio = lastArticleRecord?.ratio,
                // 打开精读：复用 Reader 这条既有通路（和资料区点开文章同一条），不新造阅读入口。
                materialReader = { article, closeReader ->
                    // 打开材料时恢复上次阅读位置：进度按 articleId+revision 存在 ReadingLibrary 里，
                    // 考试材料 / 我的文章 / EF10 个人词书生成文章都走同一条。
                    // 以前一律传 -1 —— 「继续阅读」按钮显示着百分比，点进去却回到文章顶部。
                    val savedIndex = remember(article.id, owner) {
                        runCatching { ReadingLibrary.of(hubContext, owner).articleProgress(article.id)?.index ?: -1 }
                            .getOrDefault(-1)
                    }
                    var child by remember(article.id, owner) { mutableStateOf<LearningDialog>(LearningDialog.Reader(
                        article.id, article.documentUrl, null,
                        if (article.id == englishReading?.first?.id) lastArticleRecord?.index ?: -1 else savedIndex)) }
                    LearningDialogs(child, catalog.copy(articles = catalog.articles.filterNot { it.id == article.id } + article),
                        store, favorites, lastArticle, search, owner, signedIn, onAsk, onTranslate, onPublishNote,
                        onShow = { child = it }, onClose = { next ->
                            OverlayDismissGuard.arm()
                            if (next == LearningDialog.None) { onLibraryChanged(); closeReader() } else child = next
                        }, onOpen = { child = it }, onFavorite = onFavorite, onLibraryChanged = onLibraryChanged,
                        onPracticeChanged = onPracticeChanged, onReadingSeconds = onReadingSeconds, onWordLearned = onWordLearned)
                })
        }
        LearningDialog.Reading, is LearningDialog.NoteEditor, is LearningDialog.DeleteReading -> ReadingShelfScreen(
            catalog = catalog, library = store.library,
            editing = (dialog as? LearningDialog.NoteEditor)?.record,
            pending = (dialog as? LearningDialog.DeleteReading)?.request,
            onEdit = { onShow(LearningDialog.NoteEditor(it)) },
            onPendingDelete = { onShow(LearningDialog.DeleteReading(it)) },
            onCloseChild = { onClose(LearningDialog.Reading) },
            onOpen = { target -> onOpen(LearningDialog.Reader(target.articleId.substringBefore(':'),
                target.documentPath, target.anchor, target.index)) },
            onChanged = onLibraryChanged, onClose = { onClose(LearningDialog.None) }, onPublishNote = onPublishNote,
        )
    }
}

/**
 * 精读浮层的宿主窗口。
 *
 * 为什么是**独立的 Dialog 窗口**、而不是「画在阅读器里面的一层」：
 * `ReadingDeepDiveContent` 内部用 `PredictiveBackHandler`（挂 Activity 的 dispatcher），
 * 而焦点窗口是 Dialog 时那条收不到返回（BUGS N6 已定论）。给它自己的窗口，
 * 系统返回就落到它自己的 `onDismissRequest` 上 —— 既不会误关下面的阅读器，也只有一条提交路径。
 *
 * 视觉上它仍然画在阅读器**之上**（Dialog 窗口在阅读器窗口之上），
 * 而且因为组合在阅读器的内容槽里，它继承的是阅读器的纸张配色，不是 App 主题。
 *
 * 退场动画自己写（和阅读器同一套）：Dialog 只有 `onDismissRequest` 的话，
 * 关层时窗口**瞬间消失**，一点过渡都没有（`scripts/audit_overlay_structure.py` 的 C11 钉的就是这条）。
 */
@Composable
private fun DeepDiveLayer(
    state: ReadingDeepDiveState,
    owner: String,
    articleTitle: String,
    /** 当前 AI 配置。用**取值函数**而不是值：用户在浮层里配好之后必须立刻生效（S75 的教训）。 */
    aiConfig: () -> EnglishAiConfig,
    conversationNotice: String? = null,
    onRetryConversation: () -> Unit = {},
    onOpenAiSettings: () -> Unit,
    /** 退场动画播完、窗口可以从组合里摘掉了。 */
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val motion = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    // ---------- 词卡（B 的组件）的真数据来源 ----------
    val context = androidx.compose.ui.platform.LocalContext.current
    val wordBank = remember(owner) { ReadingWordBank(context, owner) }
    // 今日队列（C03）用的仓储。跟 owner 一起重建 —— 与 wordBank 同一条纪律：
    // 换号之后还拿着旧对象的引用，读写就可能落到别人的桶里（FINDINGS #5）。
    val englishRepository = remember(owner) { EnglishRepository(context) }
    // 个人词书（EF04）：精读点词「收藏」= 先选目标书再写入，带原句与文章位置。
    // 与 wordBank 同一条纪律：按 owner 重建 —— 换号之后还拿着旧引用，读写就可能落到别人的桶里。
    val personalBooks = remember(owner) { PersonalWordBookStore(context, owner) }
    // 收藏草稿在**点击时刻**冻结：弹层开着的时候用户换了词，也不会把 A 词写进 B 词的草稿。
    var collectDraft by remember(owner) { mutableStateOf<CollectWordDraft?>(null) }
    val speech = remember { SpeechService(context) }
    val speechPreference = remember(owner) { SpeechPreference(context) }
    DisposableEffect(speech) { speech.start(); onDispose { speech.shutdown() } }
    // queueReady = true：C03 之后队列表与幂等方法都已实现，按钮不再是「今日学习尚未接入」的
    // 死按钮。写库由下面的 onQueueRequested 接管（状态机自己不碰数据库，保持可纯 JVM 单测）。
    val wordCard = remember(owner) {
        ReadingWordCardState(owner, configReady = { aiConfig().ready() }, queueReady = true)
    }
    val wordContext = remember(owner) { WordContextService(owner) }
    var notice by remember(owner) { mutableStateOf<String?>(null) }
    DisposableEffect(owner) { onDispose { wordCard.clearForAccountSwitch() } }
    // 声线可用性从**真实清单**取（问过引擎的系统朗读 + 装没装的离线模型），不按"应该有"猜。
    LaunchedEffect(speech, owner) {
        wordCard.updateUkVoice(voiceStateOf(SpeechCatalog.voices(Accent.British, speech.systemReady, context)))
        wordCard.updateUsVoice(voiceStateOf(SpeechCatalog.voices(Accent.American, speech.systemReady, context)))
    }
    // 选了词就查：词书命中 / 未收录 / 查崩了，三种结果分别进状态机（未收录不许冒充失败）。
    LaunchedEffect(state.base, owner) {
        val chosen = (state.base as? DeepDiveBase.WordCard)?.selection ?: return@LaunchedEffect
        if (chosen.wordTokenId == null) return@LaunchedEffect
        wordCard.start(chosen)
        try {
            val empty = wordBank.importedBooks().isEmpty()
            when (val result = wordBank.lookup(chosen.wordText.orEmpty())) {
                is WordLookup.Found -> {
                    wordCard.loaded(result.hit, wordBank.bookName(result.hit.wordBank), empty)
                    // 收藏状态 = 是否已在**任一本个人词书**（EF04 修复A：收藏不再是切换语义，
                    // 取消收藏在词书管理里做，避免误点把词取消掉）。星标只管「加入」。
                    // 读失败不推翻查词结果：收藏状态只是星标的初值，保持「未收藏」（再点一次
                    // 也只是重新走选书流程）比把整张卡打成「查词失败」诚实得多 —— 与队列读回同一条纪律。
                    try {
                        // 词库没给词元时按词形本身归一（B 的 key 归一同一条规则：NFC + 小写 + 空格）。
                        val key = PersonalBookWord.normalize(result.hit.lemma ?: result.hit.english)
                        val owned = personalBooks.snapshot().books.any { it.words.any { w -> w.key == key } }
                        wordCard.applyFavorite(owned)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("EnglishWordCard", "读回个人词书收藏状态失败", error)
                    }
                    // 队列状态**以库为准**读回一次。
                    //
                    // 不读回的话，退出重进之后一个昨天/刚才已经加进今日队列的词
                    // 仍然显示「加入今日学习」，用户再点一次 → `INSERT OR IGNORE` 一条都没插，
                    // 按钮却跳到「已加入」，看上去像是这次点的生效了。
                    // §4 验收明确要「今日队列测退出重进」，所以这里必须读。
                    //
                    // 读失败**不推翻查词结果**：队列状态只是按钮的初值，退回 IDLE（可再点）
                    // 比把整张卡打成「查词失败」诚实得多 —— 所以单独 try，不并进上面那个。
                    try {
                        wordCard.applyQueued(englishRepository.queuedToday(
                            owner = owner,
                            day = TodayQueueRules.day(System.currentTimeMillis()),
                            wordId = result.hit.wordId,
                            surface = chosen.wordText.orEmpty(),
                        ))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("EnglishWordCard", "读回今日队列状态失败", error)
                    }
                }
                WordLookup.NotRecorded -> wordCard.loaded(null, null, empty)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            wordCard.loadFailed(error.message ?: "查词失败")
        }
    }
    // 本句含义：与结构分析同一条冻结上下文契约，但**不落缓存**（见 WordContextService 的说明）。
    SideEffect {
        wordCard.onAnalysisRequested = { requestId, selection ->
            scope.launch {
                try {
                    val tokenId = selection.wordTokenId
                    if (tokenId == null) return@launch
                    wordCard.analysisSucceeded(requestId,
                        wordContext.explain(selection.sentence, tokenId, aiConfig()))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    wordCard.analysisFailed(requestId, error.message ?: "分析失败，请稍后重试")
                }
            }
        }
    }
    /**
     * 加入今日（C03）：**只往候选队列写一条，绝不写学习事件**。
     *
     * 「挑一个词出来看」不是「作答」——`answer` 会推进 `dueAt` 复习间隔、也会让
     * `stats.today` 涨一个，用户只是点了一下词，统计却动了，那是假的进度。
     *
     * 失败**不假装成功**：回填 false，按钮停在可重试的状态。
     * `day` 走 [TodayQueueRules.day] 取本地日期，跨天即新队列（不需要定时清理任务）。
     */
    SideEffect {
        wordCard.onQueueRequested = { requestId, selection ->
            scope.launch {
                try {
                    val added = englishRepository.queueToday(
                        owner = owner,
                        day = TodayQueueRules.day(System.currentTimeMillis()),
                        wordId = wordCard.wordId,
                        surface = selection.wordText.orEmpty(),
                        lemma = wordCard.lemma,
                    )
                    // 🔴 返回值**不是**成功标志，别拿它当 `ok` 回填。
                    // `true` = 这一条是新增；`false` = 主键冲突被 INSERT OR IGNORE 掉
                    // （这个词今天早就在队列里了）；真失败会**抛异常**走下面的 catch。
                    // 两种返回对界面都是「已在今日队列」，所以一律回填成功 ——
                    // 若按 `added` 回填，重复加入会被显示成「加入失败，重试」，
                    // 而它其实什么都没做错。
                    wordCard.completeQueue(requestId, true)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    wordCard.completeQueue(requestId, false)
                }
            }
        }
    }
    /** 朗读一个词。用**词元**（studied → study）——音标本来就是词条的，读词形反而对不上。 */
    val speak: (SpokenAccent) -> Unit = { spoken ->
        val accent = if (spoken == SpokenAccent.UK) Accent.British else Accent.American
        val speaking = wordCard.surface
        val result = speech.speak(SpeechRequest(
            text = wordCard.lemma ?: wordCard.surface,
            locale = localeOf(accent), accent = accent,
            voiceId = speechPreference.voiceId(owner, accent),
            purpose = SpeechPurpose.Word,
            utteranceId = "reader-word-$speaking-$accent"))
        // 🔴 迟到的结果不许写到另一个词上：播放期间用户换了词，这一条结果整条丢掉
        // （用 B 的规则函数判 —— 那条规则就是为这个写的，不是摆设）。
        if (!isStaleVoiceResult(speaking, wordCard.surface)) {
            val failed = when (result) {
                SpeechResult.Spoken -> false
                is SpeechResult.Failed -> {
                    notice = "朗读失败（${result.problem}）。这不是「没有音源」，是这台设备的朗读引擎出了问题。"
                    true
                }
                is SpeechResult.Unavailable -> { notice = result.reason; true }
                // Degraded = 声线语言跟内容对不上（拿别的语言的声音念英文就是这么报的）。
                // **绝不能报成功**：用户听到的就是错的音，报成功等于骗他。
                is SpeechResult.Degraded -> { notice = result.reason; true }
            }
            val voice = if (failed) VoiceState.FAILED else VoiceState.READY
            if (spoken == SpokenAccent.UK) wordCard.updateUkVoice(voice) else wordCard.updateUsVoice(voice)
        }
    }
    LaunchedEffect(Unit) { motion.animateTo(1f, tween(OverlayMotion.ENTER_MS, easing = FastOutSlowInEasing)) }
    /** 整层真的没了：先播退场动画，播完再 arm 穿透守卫并请宿主摘窗口。 */
    fun exit() {
        if (closing) return
        closing = true
        scope.launch {
            motion.animateTo(0f, tween(OverlayMotion.EXIT_MS, easing = FastOutSlowInEasing))
            OverlayDismissGuard.arm()
            onClosed()
        }
    }
    // 一步后退：收键盘 → 退对话 → 退来源层；退到底才走退场动画。
    // 与精读浮层内部那条提交路径同一条语义，只是这里拿的是 Dialog 窗口自己的返回。
    val back: () -> Unit = {
        when (state.onBack()) {
            BackOutcome.COLLAPSE_KEYBOARD -> keyboard?.hide()
            BackOutcome.POP_LAYER -> if (!state.overlayActive) exit()
            BackOutcome.IGNORED -> {}
        }
    }
    Dialog(onDismissRequest = { back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnClickOutside = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            // 和阅读器一样关掉系统动画与系统 dim：系统 dim 盖不住阅读器自己的窗口，
            // 而且颜色不可控 —— 遮罩由下面那层自己画、自己淡出。
            window.setWindowAnimations(0)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } }
        // 键盘可见性由宿主随窗口 insets 更新；返回顺序第一步（收键盘）消费它。
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        LaunchedEffect(imeVisible) { state.keyboardVisible = imeVisible }
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                alpha = motion.value
                translationY = (1f - motion.value) * 32.dp.toPx()
            }) {
            // 遮罩自己画，并且**吃掉**落在遮罩上的触摸 ——
            // 不消费的话手指会穿到下面正文上（划到一段就会弹出笔记或精读框）。
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).pointerInput(Unit) {})
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ReadingDeepDiveContent(state = state, articleTitle = articleTitle,
                    onCollapseKeyboard = { keyboard?.hide() },
                    onOverlayDismissed = { exit() },
                    onOpenAiSettings = onOpenAiSettings,
                    // 词卡插槽：A 留的接口，这里填上 B 的真实组件（原来是个写着「尚未接线」的占位）。
                    wordCard = { selection ->
                        ReadingWordCardPanel(
                            selection = selection, state = wordCard, notice = notice,
                            // 点句子里的词 → 换一层选区（不是新开一层）：层数不变，返回语义也不变。
                            onPickWord = { tokenId -> state.openWordCard(selection.copy(wordTokenId = tokenId)) },
                            onToggleFavorite = {
                                // beginFavorite 返回 false = 上一次还在保存中。这里它顺便把星标
                                // 置成不可点，选书弹层开着的时候不会再触发第二次。
                                if (wordCard.beginFavorite()) {
                                    val found = wordCard.hit
                                    val meaning = wordCard.definition?.trim().orEmpty()
                                    val sentenceMeta = wordCard.selection?.sentence
                                    when {
                                        found == null -> wordCard.favoriteFailed("这个词没有对应的词条")
                                        // B 的 store 拒绝空释义：没有释义的词收进书里没法复习，如实说清。
                                        meaning.isEmpty() -> wordCard.favoriteFailed("这个词还没有释义，查到释义后再收藏")
                                        sentenceMeta == null -> wordCard.favoriteFailed("缺少来源句子，无法保存")
                                        else -> {
                                            // 文章位置 = 句子在原文里的起点 + 词在句内的偏移（UTF-16）。
                                            // 词书里回看原句时能定位到就是这个词，不是大概某处。
                                            val pos = if (wordCard.hitStart >= 0 && wordCard.hitEnd > wordCard.hitStart)
                                                "${sentenceMeta.start + wordCard.hitStart}-${sentenceMeta.start + wordCard.hitEnd}" else ""
                                            collectDraft = CollectWordDraft(
                                                surface = wordCard.surface,
                                                lemma = wordCard.lemma ?: wordCard.surface,
                                                meaning = meaning,
                                                sentence = wordCard.sentence,
                                                articleId = sentenceMeta.articleId,
                                                sourcePosition = pos,
                                            )
                                        }
                                    }
                                }
                            },
                            onSpeak = speak,
                            onRequestAnalysis = {
                                // 没配 AI 就去配置页，**不**在这里发一个注定失败的请求；
                                // 配好回来按钮还是「让 AI 解释这句」，点一下就能发（S75 的教训）。
                                if (aiConfig().ready()) wordCard.requestAnalysis()
                                else {
                                    notice = "本句含义要用你自己的 AI 接口。词书释义和发音不受影响。"
                                    onOpenAiSettings()
                                }
                            },
                            onAskAi = {
                                // 问 AI 带着**当前这个词**（关注词编号进上下文），返回时回到词卡层。
                                state.openConversation(ReaderEntry.WORD_CARD, selection, articleTitle)
                            },
                            // 加入今日（C03）：状态机置 SAVING，真正的写库交给上面的 onQueueRequested。
                            // 这里不做「能不能加」的判断 —— 那是状态机（queueReady）的事，
                            // 界面层再判一次就会出现两处口径。
                            onQueue = { wordCard.requestQueue() },
                            onOpenAiSettings = onOpenAiSettings,
                            onClose = { back() })
                    })
            }
            conversationNotice?.let { message ->
                Surface(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 48.dp),
                    color = AppColors.SurfaceOpaque) {
                    TextButton(onClick = onRetryConversation, enabled = !message.startsWith("正在")) {
                        Text(message, color = AppColors.TextPrimary)
                    }
                }
            }
        }
    }
    // 选书弹层（EF04 修复A）：挂在词卡窗口之上，取消时把 beginFavorite 占用的保存锁放回去。
    collectDraft?.let { draft ->
        CollectToBookDialog(
            owner = owner, word = draft,
            onDismiss = {
                collectDraft = null
                wordCard.applyFavorite(wordCard.favorite)   // 只解锁，不改收藏状态
            },
            onCollected = { bookName, alreadyThere ->
                collectDraft = null
                wordCard.applyFavorite(true)
                notice = if (alreadyThere) "「${draft.surface}」已经在《$bookName》里"
                else "已收藏到《$bookName》，来源原句一并保存"
            },
        )
    }
}
