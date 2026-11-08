package cn.tonglu.app.ui

import android.content.Context
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
    private val previewContext by lazy { object : ContextWrapper(this) {
        override fun getApplicationContext(): Context = this
        // Share only the isolated classroom fixture with ClassNotesPreviewActivity to verify entry parity.
        override fun getFilesDir() = File(super.getFilesDir(), "qa-classnotes").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa_learning_" + name, mode)
        override fun getDatabasePath(name: String) = File(super.getDatabasePath(name).parentFile, "qa_learning_$name")
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previousMode = AppTheme.modeState
        AppTheme.setEnabled(previewContext, intent.getBooleanExtra("dark", false))
        val original = CourseDto("qa-course", "示例 · 课堂笔记走查", "QA", weekday=1,
            startPeriod=1, endPeriod=2, startWeek=1, endWeek=20)
        val fixtureCourses = if (intent.getBooleanExtra("emptyCourses", false)) emptyList() else
            listOf(original, original.copy(id="qa-course-copy", weekday=2)) + (1..7).map {
                original.copy(id="qa-learning-$it", name="示例 · 课程$it", teacher="Fixture$it")
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
                                    if (intent.getBooleanExtra("quizMotion", false)) {
                                        var closed by remember { mutableStateOf(false) }
                                        val store = remember { cn.tonglu.app.data.listening.PracticeStore(
                                            File(previewContext.filesDir, "qa-quiz-attempts"), "qa-quiz-motion") }
                                        val bank = remember { cn.tonglu.app.data.question.QuestionBank(
                                            id = "qa-motion-bank", title = "本地示例 · 切题走查",
                                            sourceNote = "本地走查示例题，提交仅写隔离测试记录，不是真题。", license = "项目自编示例",
                                            questions = (1..3).map { n -> cn.tonglu.app.data.question.BankQuestion(
                                                id = "q$n", prompt = "示例题 $n：选择 A", options = listOf("Q${n}_A", "Q${n}_B"),
                                                answer = 0, explanation = "本地示例解析 $n") }) }
                                        if (closed) Text("题库走查已返回")
                                        else cn.tonglu.app.ui.learning.BankQuizPane(bank, "qa-quiz-motion", store,
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
        super.onDestroy()
    }
}
