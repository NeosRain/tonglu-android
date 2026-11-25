package cn.tonglu.app.ui.diary

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.ThemeMode
import cn.tonglu.app.ui.ThemeSystemBars
import cn.tonglu.app.ui.appMaterialColors
import cn.tonglu.app.ui.glass.LocalGlassNavigationEnabled
import cn.tonglu.app.ui.home.DiaryHomeCard
import java.io.File

/** Debug Activity 仅使用独立的示例日记文件和外观配置。 */
class GoalDiaryPreviewActivity : ComponentActivity() {
    private var previousMode = ThemeMode.System
    private val previewContext by lazy {
        object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa_diary_$name", mode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previousMode = AppTheme.modeState
        AppTheme.setEnabled(previewContext, intent.getStringExtra("theme") == "dark")
        val now = System.currentTimeMillis()
        val today = DiaryDates.dayAt(now)
        val week = DiaryDates.week(today)
        val start = when (intent.getStringExtra("page")) {
            "home" -> null
            "habits" -> DiaryPage.HABITS.name
            "quick" -> DiaryPage.QUICK.name
            "summary" -> DiaryPage.SUMMARY.name
            "history" -> DiaryPage.HISTORY.name
            "goals" -> DiaryPage.GOALS.name
            "survey" -> DiaryPage.SURVEY.route(week)
            "ranking" -> DiaryPage.RANKING.name
            "settings" -> DiaryPage.SETTINGS.name
            else -> DiaryPage.PLANS.name
        }
        val store = DiaryStore.forAndroidFile(File(noBackupFilesDir, "goal-diary-preview/$today.json"))
        val fixture = previewDiary(today, week)
        setContent {
            ThemeSystemBars()
            MaterialTheme(colorScheme = appMaterialColors()) {
                CompositionLocalProvider(
                    LocalContentColor provides AppColors.TextPrimary,
                    LocalGlassNavigationEnabled provides (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
                ) {
                    val state = rememberDiaryState(store, fixture)
                    var routes by rememberSaveable { mutableStateOf(ArrayList(listOfNotNull(start))) }
                    val open: (String) -> Unit = { routes = ArrayList(routes + it) }
                    Box(Modifier.fillMaxSize().background(AppColors.CanvasOpaque)) {
                        if (start == null) Column(
                            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            DiaryNotice("示例数据 · 仅供调试预览")
                            state.data?.let { DiaryHomeCard(it, state.now, open) }
                            DiaryLink(DiaryPage.PLANS.title, { open(DiaryPage.PLANS.name) })
                            DiaryLink(DiaryPage.HABITS.title, { open(DiaryPage.HABITS.name) })
                        }
                        DiaryNavigation(state, routes, open) { route ->
                            if (routes.lastOrNull() == route) {
                                OverlayDismissGuard.arm()
                                routes = ArrayList(routes.dropLast(1))
                                if (routes.isEmpty() && start != null) finish()
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

private fun previewDiary(today: Long, week: Long): DiaryData {
    val goal = DiaryGoal("preview-goal", "一个月内整理好作品集", today + 16)
    val plans = listOf(
        DiaryPlan("preview-plan-1", "理出作品集的三个主题", today, goal.id, done = true),
        DiaryPlan("preview-plan-2", "选出两份有代表性的作业", today, goal.id),
        DiaryPlan("preview-plan-3", "问同学愿不愿意帮忙看初稿", today),
        DiaryPlan("preview-plan-4", "给旧项目补一段过程说明", today - 1, goal.id),
        DiaryPlan("preview-plan-5", "收集三份版式参考", today - 2, goal.id, done = true),
    )
    val ideas = listOf(
        Idea("preview-idea-1", "先做一页只放过程图的作品说明。", DiaryDates.atTime(today, 8, 35), today, listOf("作品集", "表达")),
        Idea("preview-idea-2", "试试在图书馆留一个固定的深度阅读时段。", DiaryDates.atTime(today - 1, 16, 20), today - 1, listOf("习惯")),
        Idea("preview-idea-3", "把长任务拆成可以在晚饭前做完的小动作。", DiaryDates.atTime(today - 2, 18, 50), today - 2),
    )
    val habits = listOf(
        "写作业时反复查看消息", "睡前刷短视频", "连着几个晚上拖到很晚才睡", "想到问题就切去做别的事",
        "没列计划就打开社交应用", "排队时不停刷新动态", "给作品反复换模板", "用零食代替正餐",
        "带手机进自习区", "熬夜看直播", "把阅读拖到睡觉前", "午休超过一个小时",
        "做完作业忘了整理桌面", "口渴了才想起喝水", "周末起床后一直赖床", "在桌边吃宵夜",
    ).mapIndexed { index, title ->
        Prohibition("preview-habit-$index", title, week - 7, goal.id.takeIf { index in listOf(0, 3, 6) })
    }
    val previous = (week - 6 until week).map { pastWeek ->
        val answers = habits.mapIndexedNotNull { index, item ->
            val answer = when {
                index == 15 -> WeeklyAnswer.NONE
                index == 0 && pastWeek == week - 1 -> WeeklyAnswer.HIGH
                index == 1 -> WeeklyAnswer.MEDIUM
                index <= 10 -> WeeklyAnswer.entries[((pastWeek - week + 6).toInt() + index) % 4]
                else -> null
            }
            answer?.let { item.id to it }
        }.toMap()
        DiarySurvey(pastWeek, answers.keys.toList(), habits.map { it.id }, answers = answers, submitted = true)
    }
    val old = today - 4
    val summary = DiarySummary(
        "preview-summary-1", old - 2, old, DiaryDates.atTime(old, 20, 0),
        "把零散的材料收拢到一个地方之后，开始做初稿轻松了一些。", emptyList(), emptyList(), emptyList(),
    )
    return DiaryData(ideas, plans, listOf(goal), listOf(summary), habits, previous, example = true)
}
