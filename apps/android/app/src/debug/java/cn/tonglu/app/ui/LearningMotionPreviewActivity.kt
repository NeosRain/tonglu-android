package cn.tonglu.app.ui

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Home
import com.kyant.backdrop.backdrops.layerBackdrop
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.tonglu.app.ui.learning.LearningScreen
import cn.tonglu.app.data.CourseDto
import java.io.File
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** Offline debug fixture: its own files, preferences, database and owner; no login or AI requests. */
class LearningMotionPreviewActivity : ComponentActivity() {
    private var previousMode = ThemeMode.System
    // Match older real packs: Gson leaves omitted non-null Kotlin default fields null.
    private fun legacyQuestion(id: String, prompt: String, options: List<String>, explanation: String,
        difficulty: String = cn.tonglu.app.data.question.Difficulty.BASIC) = com.google.gson.Gson().fromJson(
        com.google.gson.Gson().toJson(mapOf("id" to id, "type" to "single-choice", "prompt" to prompt,
            "options" to options, "answer" to 0, "explanation" to explanation, "difficulty" to difficulty)),
        cn.tonglu.app.data.question.BankQuestion::class.java)

    private val previewContext by lazy { object : ContextWrapper(this) {
        override fun getApplicationContext(): Context = this
        // Share only the isolated classroom fixture with ClassNotesPreviewActivity to verify entry parity.
        override fun getFilesDir() = File(super.getFilesDir(), "qa-classnotes").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa_learning_" + name, mode)
        override fun getDatabasePath(name: String) = File(super.getDatabasePath(name).parentFile, "qa_learning_$name")
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("planMotion", false) || intent.getBooleanExtra("flexibleLayout", false)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        enableEdgeToEdge()
        previousMode = AppTheme.modeState
        AppTheme.setEnabled(previewContext, intent.getBooleanExtra("dark", false))
        val original = CourseDto("qa-course", "示例 · 课堂笔记走查", "QA", weekday=1,
            startPeriod=1, endPeriod=2, startWeek=1, endWeek=20)
        val fixtureCourses = if (intent.getBooleanExtra("emptyCourses", false)) emptyList() else
            listOf(original, original.copy(id="qa-course-copy", weekday=2)) + (1..7).map {
                original.copy(id="qa-learning-$it", name="示例 · 课程$it", teacher="Fixture$it")
            }
        if (intent.getBooleanExtra("glassWallpaper", false)) {
            // Self-created local fixture; never writes the real wallpaper directory/preferences.
            val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(
                0f, 0f, 256f, 256f, intArrayOf(0xFF204E86.toInt(), 0xFFCAAA67.toInt(), 0xFF327B66.toInt()),
                null, android.graphics.Shader.TileMode.CLAMP) }
            canvas.drawRect(0f, 0f, 256f, 256f, paint)
            val fixture = File(previewContext.filesDir, "qa-glass-wallpaper.png")
            fixture.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            cn.tonglu.app.ui.theme.ThemeWallpaper.save(previewContext, android.net.Uri.fromFile(fixture))
        }
        setContent {
            val controller = remember(AppTheme.darkState) {
                ThemeController(if (AppTheme.darkState) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            }
            var timetableRequests by remember { mutableIntStateOf(0) }
            MiuixTheme(colors = controller.currentColors()) {
                MaterialTheme(colorScheme = appMaterialColors()) {
                    CompositionLocalProvider(LocalContext provides previewContext, LocalContentColor provides AppColors.TextPrimary) {
                        ThemeSystemBars()
                        Surface(color = AppColors.CanvasOpaque) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                                Text("本地示例 · 学习走查", style = MaterialTheme.typography.labelSmall)
                                if (timetableRequests > 0) Text("课表入口请求：$timetableRequests", style = MaterialTheme.typography.labelSmall)
                                Box(Modifier.weight(1f)) {
                                    if (intent.getBooleanExtra("bottomChrome", false)) {
                                        BottomChromePreview(longGraphCategory = intent.getBooleanExtra("graphLongCategory", false))
                                    } else if (intent.getBooleanExtra("examForm", false)) {
                                        var showing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
                                        var result by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
                                        var saves by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
                                        Column {
                                            Text("本地示例 · 不保存到考试库 · 确认次数：$saves")
                                            Text(result)
                                            TextButton(onClick = { showing = true }) { Text("再次添加考试") }
                                        }
                                        if (showing) cn.tonglu.app.ui.exam.AddExamDialog(
                                            onDismiss = { result = "已取消"; showing = false },
                                            onConfirm = { title, date ->
                                                saves++; result = "$title · $date"; showing = false
                                            },
                                        )
                                    } else if (intent.getBooleanExtra("catalogMotion", false)) {
                                        var closed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                                        if (closed) Text("题库目录走查已返回")
                                        else cn.tonglu.app.ui.learning.QuestionBankScreen("qa-catalog-ui", onClose = { closed = true },
                                            wrongReview = intent.getBooleanExtra("wrongReview", false))
                                    } else if (intent.getBooleanExtra("historyMotion", false)) {
                                        var ready by remember { mutableStateOf(false) }
                                        var setupError by remember { mutableStateOf<String?>(null) }
                                        var closed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                                        LaunchedEffect(Unit) {
                                            try {
                                                val store = cn.tonglu.app.data.english.intensive.IntensiveStudyStore(previewContext, "qa-history-ui")
                                                if (intent.getBooleanExtra("seedHistory", false)) {
                                                    store.manual("qa-history-article", "本地示例 · 精读记录", "qa-sentence", "A local sample for reading history.", "这是一条本地示例译文。")
                                                    store.favorite("qa-history-article", "本地示例 · 精读记录", "qa-sentence", "A local sample for reading history.", true)
                                                }
                                                ready = true
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                            catch (cause: Exception) { setupError = cause.localizedMessage }
                                        }
                                        if (closed) Text("精读记录走查已返回")
                                        else if (ready) cn.tonglu.app.ui.english.intensive.ReadingHistoryScreen("qa-history-ui", onClose = { closed = true })
                                        else Text(setupError ?: "正在准备隔离精读记录")
                                    } else if (intent.getBooleanExtra("planMotion", false)) {
                                        var closed by remember { mutableStateOf(false) }
                                        if (closed) Text("计划走查已返回")
                                        else cn.tonglu.app.ui.learning.LearningPlanPane(owner = "qa-plan-motion",
                                            bankTitles = emptyList(), bookName = null, onClose = { closed = true })
                                    } else if (intent.getBooleanExtra("dailyMotion", false)) {
                                        var ready by remember { mutableStateOf(false) }
                                        var setupError by remember { mutableStateOf<String?>(null) }
                                        var closed by remember { mutableStateOf(false) }
                                        var lookedUp by remember { mutableStateOf("") }
                                        LaunchedEffect(Unit) {
                                            try {
                                                val repo = cn.tonglu.app.data.english.EnglishRepository(previewContext)
                                                val book = "qa-daily-motion-book"
                                                repo.dao.book(cn.tonglu.app.data.english.EnglishBook(book,
                                                    "本地示例 · 例句走查", "本地示例", 3, "1", source = "项目自编示例", license = "项目自编示例"))
                                                repo.dao.words(listOf(
                                                    cn.tonglu.app.data.english.EnglishWord("qa-daily-forest", "forest", "森林",
                                                        example = "The forest is calm.", exampleTranslation = "森林很安静。", wordBank = book, position = 1),
                                                    cn.tonglu.app.data.english.EnglishWord("qa-daily-note", "note", "笔记",
                                                        example = "We read a short note.", exampleTranslation = "我们读一篇短笔记。", wordBank = book, position = 2),
                                                    cn.tonglu.app.data.english.EnglishWord("qa-daily-light", "light", "光",
                                                        example = "Soft light fills the room.", exampleTranslation = "柔光洒满房间。", wordBank = book, position = 3)))
                                                repo.dao.settings(cn.tonglu.app.data.english.EnglishSettings("qa-daily-motion", book))
                                                ready = true
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                            catch (cause: Exception) { setupError = cause.message ?: "例句走查准备失败" }
                                        }
                                        if (closed) Text("例句走查已返回 · 查词请求：$lookedUp")
                                        else if (ready) cn.tonglu.app.ui.learning.DailySentencePane("qa-daily-motion",
                                            onClose = { closed = true }, onLookupWord = { lookedUp = it })
                                        else Text(setupError ?: "正在准备本地示例例句")
                                    } else if (intent.getBooleanExtra("quizMotion", false)) {
                                        var closed by remember { mutableStateOf(false) }
                                        val store = remember { cn.tonglu.app.data.listening.PracticeStore(
                                            File(previewContext.filesDir, "qa-quiz-attempts"), "qa-quiz-motion") }
                                        val bank = remember { cn.tonglu.app.data.question.QuestionBank(
                                            id = "qa-motion-bank", title = "本地示例 · 切题走查",
                                            sourceNote = "本地走查示例题，提交仅写隔离测试记录，不是真题。", license = "项目自编示例",
                                            questions = (1..3).map { n -> legacyQuestion(
                                                id = "q$n", prompt = "示例题 $n：选择 A", options = listOf("Q${n}_A", "Q${n}_B"),
                                                explanation = "本地示例解析 $n") }) }
                                        if (closed) Text("题库走查已返回")
                                        else cn.tonglu.app.ui.learning.BankQuizPane(bank, "qa-quiz-motion", store,
                                            onExit = { closed = true }, onAnswered = {})
                                    } else if (intent.getBooleanExtra("diagnosisMotion", false)) {
                                        var closed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                                        var ready by remember { mutableStateOf(false) }
                                        var setupError by remember { mutableStateOf<String?>(null) }
                                        val store = remember { cn.tonglu.app.data.listening.PracticeStore(
                                            File(previewContext.filesDir, "qa-diagnosis-attempts"), "qa-diagnosis-motion") }
                                        val bank = remember { cn.tonglu.app.data.question.QuestionBank(
                                            id = "qa-diagnosis-bank", title = "本地示例 · 自测走查",
                                            sourceNote = "本地示例，提交仅写隔离走查记录。", license = "项目自编示例",
                                            questions = (1..3).map { n -> legacyQuestion(
                                                id = "q$n", prompt = "本地示例 $n：选择 A", options = listOf("D${n}_A", "D${n}_B"),
                                                explanation = "本地示例解析 $n",
                                                difficulty = cn.tonglu.app.data.question.Difficulty.order[n - 1]) }) }
                                        LaunchedEffect(store) {
                                            try {
                                                kotlinx.coroutines.delay(600) // Mirrors the real entry reloading its local catalog after rotation.
                                                if (intent.getBooleanExtra("seedDiagnosis", false) && store.attempts().none { it.correct }) {
                                                    store.record(questionId = "q1", materialId = bank.id, materialTitle = bank.title,
                                                        chosen = 0, correct = true, questionKey = bank.keyOf(bank.questionList.first()),
                                                        mode = cn.tonglu.app.data.listening.PracticeMode.QUIZ)
                                                }
                                                ready = true
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                            catch (cause: Exception) { setupError = "走查准备失败：${cause.localizedMessage}" }
                                        }
                                        val availability = intent.getStringExtra("diagnosisAvailability")
                                        val displayError = if (availability == "error")
                                            "本地走查示例：题组读取失败，请返回后重试。\n".repeat(12) + "错误信息结束"
                                            else setupError
                                        if (closed) Text("自测走查已返回")
                                        else cn.tonglu.app.ui.learning.VocabDiagnosisPane(if (ready && availability == null) listOf(bank) else emptyList(), store,
                                            loading = !ready && displayError == null, loadError = displayError,
                                            onExit = { closed = true }, onAnswered = {})
                                    } else if (intent.getBooleanExtra("wordMotion", false)) {
                                        var ready by remember { mutableStateOf(false) }
                                        var setupError by remember { mutableStateOf<String?>(null) }
                                        var closed by remember { mutableStateOf(false) }
                                        LaunchedEffect(Unit) {
                                            try {
                                                val repo = cn.tonglu.app.data.english.EnglishRepository(previewContext)
                                                val book = cn.tonglu.app.data.english.EnglishBookStore.catalog(previewContext, null)
                                                    .first { it.id == "kylebing-cet4-compact" }
                                                repo.ensureBook(book)
                                                repo.dao.settings(cn.tonglu.app.data.english.EnglishSettings(
                                                    "qa-word-motion", book.id, dailyGoal = 3))
                                                ready = true
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                            catch (cause: Exception) { setupError = cause.message ?: "走查准备失败" }
                                        }
                                        if (closed) Text("词卡走查已返回")
                                        else if (ready) EnglishStudyScreen(owner = "qa-word-motion", startOnReady = true,
                                            directSessionExit = true, onClose = { closed = true })
                                        else Text(setupError ?: "正在准备本机示例词卡")
                                    } else {
                                    LearningScreen(search = "", owner = "qa-learning-motion",
                                        courses = fixtureCourses, onOpenTimetable = { timetableRequests++ },
                                        onAsk = { _, _, _ -> error("走查入口不请求AI") },
                                        onTranslate = { _, _ -> error("走查入口不请求AI") })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        AppTheme.setMode(previewContext, previousMode)
        cn.tonglu.app.ui.theme.ThemeWallpaper.load(this)
        super.onDestroy()
    }
}

/** Uses production chrome; all editor actions remain in memory and never publish. */
@Composable
private fun BottomChromePreview(longGraphCategory: Boolean = false) {
    var count by remember { mutableIntStateOf(3) }
    var selected by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf("") }
    var opaque by remember { mutableStateOf(false) }
    var body by remember { mutableStateOf("本地示例 · 编辑内容，不会发布。") }
    val context = LocalContext.current
    val store = remember { cn.tonglu.app.data.ReadingStore(context, "qa-bottom-chrome") }
    val article = remember { cn.tonglu.app.data.LearningArticle(
        id = "qa-bottom-chrome", sectionId = "qa-bottom", title = "本地示例 · 阅读底栏", summary = "仅隔离走查",
        author = "本地示例", kind = "ARTICLE", sourceUrl = "", licenseId = "ORIGINAL", licenseUrl = "",
        attribution = "项目自编示例", changeNote = "", sourceRevision = "1", updatedAt = "", featured = false, order = 0,
        body = (1..30).joinToString("\n\n") { "Soft light fills the forest. We read a short note and learn something new every day." }) }
    var graphArticle by remember { mutableStateOf<cn.tonglu.app.data.LearningArticle?>(null) }
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val tabs = if (count == 3) listOf("首页", "学习", "我的") else listOf("首页", "学习", "广场", "社团", "我的")
    val icon = androidx.compose.material.icons.Icons.Default.Home
    val icons = List(count) { icon }
    cn.tonglu.app.ui.theme.ThemeWallpaperHost(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Row {
                    TextButton(onClick = { count = if (count == 3) 5 else 3; selected = selected.coerceAtMost(count - 1) }) { Text("切换三／五栏") }
                    TextButton(onClick = { opaque = !opaque }) { Text("玻璃／兼容") }
                }
                Row {
                    TextButton(onClick = { route = "note" }) { Text("便携笔记") }
                    TextButton(onClick = { route = "post" }) { Text("发帖编辑") }
                }
                Row {
                    TextButton(onClick = { route = "reader" }) { Text("英语阅读") }
                    TextButton(onClick = { route = "graph" }) { Text("学习图谱") }
                    TextButton(onClick = { route = if (route == "rail") "" else "rail" }) { Text("平板侧栏") }
                }
                Text("本地示例 · 当前导航：${tabs[selected]}")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    repeat(20) { n ->
                        Surface(color = if (n % 2 == 0) AppColors.Accent.copy(alpha = .25f) else AppColors.SurfaceOpaque,
                            modifier = Modifier.fillMaxWidth().height(92.dp).padding(6.dp)) {
                            Text("本地示例 $n · 玻璃背景采样", modifier = Modifier.padding(20.dp))
                        }
                    }
                }
            }
            if (opaque) CoolapkNavigationBar(tabs, icons, icons, selected, { selected = it }, Modifier.align(Alignment.BottomCenter))
            else GlassBottomBar(tabs, icons, icons, selected, { selected = it }, backdrop, { 0f }, Modifier.align(Alignment.BottomCenter))
        }
        if (route == "rail") TabletNavigationRail(tabs, icons, icons, selected, { selected = it }, backdrop, true)
        if (route == "reader") ArticleReader(graphArticle ?: article, store, false, {}, { route = "" })
        if (route == "graph") LearningGraphScreen(cn.tonglu.app.data.LearningCatalog(1, "qa", listOf(
            cn.tonglu.app.data.LearningSection("qa-bottom", null, "本地示例", "本地示例", "blue", 0)),
            if (longGraphCategory) List(12) { i -> article.copy(
                id = "qa-graph-$i", title = "本地示例 · 资料${i + 1} · 分类完整列表与大字换行检查",
                sectionId = "qa-bottom", summary = "本地示例 · 仅用于界面走查，不会保存到资料库",
            ) } else listOf(article)),
            onOpenArticle = { graphArticle = it; route = "reader" }, onClose = { route = "" })
        if (route == "note") NoteEditorDialog("本地示例 · 便携笔记", "", body,
            onSave = { body = it; route = "" }, onDelete = null, onDismiss = { route = "" })
        if (route == "post") PostComposerDialog(onPublish = { _, _, _, done -> done(); route = "" },
            onDismiss = { route = "" }, initialBody = body, heading = "本地示例 · 不会发布")
    }
}
