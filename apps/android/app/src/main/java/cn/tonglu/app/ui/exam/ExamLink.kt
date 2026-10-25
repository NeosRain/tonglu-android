package cn.tonglu.app.ui.exam

import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.themeColor
import cn.tonglu.app.ui.noticeForeground
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.exam.ExamCategory
import cn.tonglu.app.data.exam.ExamCountdown
import cn.tonglu.app.data.exam.ExamEvent
import cn.tonglu.app.data.exam.ExamSubjects

/**
 * 科目 → 学习内容的跳转映射（`docs/plan/54` M7）。
 *
 * ## 兜底规则比映射本身更重要
 * 映射不到目标时**按钮如实变成「暂无备考资料」并禁用**，同时给一个「去学习页找资料」的
 * 次级入口。**绝不做「点了没反应」或「跳到一个空页面」** —— 这是本项目反复强调的
 * 「缺失必须打标」原则的延伸：宁可说没有，也不能假装去了一个地方。
 *
 * 「计算机备考页」按 plan Q7 的建议**不新建独立页面**，而是复用学习页 + 资料分类筛选。
 * 目前资料库里还没有 NCRE 资料包，所以 `ncre*` 如实置灰 —— 等资料挂上去，把这里
 * 的 available 打开即可，UI 不用改。
 */
object ExamLink {

    /**
     * 「考试日历页的四六级入口」用的深链 key（2026-10-15 迁移）。
     *
     * 为什么不直接改 [ExamSubjects.CET4]/[ExamSubjects.CET6] 那条映射：它是考试详情
     * 「去备考」在用的老路径（→ 英语中心 `words`，即四六级词库），把用户已经在用的那扇门
     * 换掉不算「搬入口」。这两个 key 只给考试日历页的「备考材料」用：→ 精读入口
     * `intensive`（应试考试材料 / 推荐文章那一屏，一字未改）。
     *
     * 入口归属与返回语义见 `work/exam-entry-move-20261015/PLAN.md`。
     */
    const val CET4_PREP = "cet4-prep"
    const val CET6_PREP = "cet6-prep"

    /**
     * 官方考试网（报名 / 大纲 / 查分），**没有**历年真题下载。
     *
     * 与 `content/learning/catalog.json` 里 `cet4-official-entry` / `cet6-official-entry`
     * 两篇的 `sourceUrl` 同源：那两篇只做原站导航的资料条目**仍留在目录数据里**
     * （收藏 / 搜索 / 上次阅读点进去照旧），只是不再出现在资料库的浏览列表里 ——
     * 四六级入口现在挂在考试日历页。
     * `work/exam-entry-move-20261015/tools/audit_exam_entry_move.py` 核对这两处一致。
     */
    const val CET_OFFICIAL_URL = "https://cet.neea.edu.cn/"

    enum class Kind {
        /** 英语类：走英语中心（四六级词库本来就在里面） */
        ENGLISH,

        /** 走资料检索（无专属页） */
        LIBRARY,

        /** 没有对应内容 */
        NONE,
    }

    data class Target(
        val kind: Kind,
        /** 可用时按钮上的文案 */
        val label: String,
        /** false = 按钮变「暂无备考资料」并禁用 */
        val available: Boolean,
        /** `EnglishHub` 的 route（"home" / "words" / "listening"） */
        val route: String? = null,
    )

    fun resolve(subjectKey: String?): Target = when (subjectKey) {
        ExamSubjects.CET4 -> Target(Kind.ENGLISH, "英语四级备考资料", available = true, route = "words")
        ExamSubjects.CET6 -> Target(Kind.ENGLISH, "英语六级备考资料", available = true, route = "words")
        ExamSubjects.KAOYAN -> Target(Kind.LIBRARY, "考研资料", available = false)
        ExamSubjects.NCER1, ExamSubjects.NCER2, ExamSubjects.NCER3, ExamSubjects.NCER4 ->
            Target(Kind.LIBRARY, "计算机备考资料", available = false)
        ExamSubjects.JIAOSHI -> Target(Kind.LIBRARY, "教资备考资料", available = false)
        ExamSubjects.PUTONGHUA -> Target(Kind.LIBRARY, "普通话备考资料", available = false)
        // 考试日历页「备考材料」：直达精读入口那一屏（应试考试材料 / 推荐文章）。
        CET4_PREP -> Target(Kind.ENGLISH, "英语四级备考材料", available = true, route = "intensive")
        CET6_PREP -> Target(Kind.ENGLISH, "英语六级备考材料", available = true, route = "intensive")
        else -> Target(Kind.NONE, "备考资料", available = false)
    }
}

/**
 * 点首页倒计时条目后弹出来的详情。
 *
 * 为什么要点开一层而不是直接跳：国家考证里有相当一部分日期是「待核实」的
 * （[cn.tonglu.app.data.exam.ExamDateSource]），**这句话必须让用户看到**，
 * 直接跳走就没人知道那个「?」是什么意思了。
 */
@Composable
internal fun ExamDetailSheet(
    event: ExamEvent,
    onDismiss: () -> Unit,
    onOpenStudy: (ExamEvent) -> Unit,
    onOpenLibrary: () -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
) {
    // 2026-10-09：这一层原来 `onDismissRequest = onDismiss`，关层没有动画。
    // 它是**底部面板**形态（外框全屏 + 内容贴底），所以把 overlayMotion 挂在外框 Box 上：
    // 退场时整层淡出 + 轻微下沉，点空白处关闭也走同一条动画。
    val motion = rememberOverlayMotion(
        onClose = { onDismiss() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().overlayMotion(motion, fade = false).clickable { motion.requestClose() },
            contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = false) { },
                color = AppColors.Surface,
                contentColor = AppColors.TextPrimary,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            ) {
                ExamDetailContent(event, { motion.requestClose() }, onOpenStudy, onOpenLibrary, onTogglePin)
            }
        }
    }
}


/** 同一内容可在日历卡片内展开，也可用于首页的快捷详情面板。 */
@Composable
internal fun ExamDetailContent(
    event: ExamEvent,
    onDismiss: () -> Unit,
    onOpenStudy: (ExamEvent) -> Unit,
    onOpenLibrary: () -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
) {
    val result = ExamCountdown.of(event, fixedUtcPlus8 = event.category == ExamCategory.NATIONAL)
    val target = ExamLink.resolve(event.subjectKey)
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
    Text(event.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "考试日期 ${event.date}${event.timeText?.let { " · $it" } ?: ""}",
        style = MaterialTheme.typography.bodyMedium,
        color = themeColor(Color(0xFF4B5563), AppColors.TextSecondary),
    )
    result?.let {
        Text(
            it.label,
            fontWeight = FontWeight.Bold,
            color = if (it.urgent) themeColor(Color(0xFFD93A3A), AppColors.Danger) else AppColors.Accent,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    // 待核实：**必须在这一层说清楚**，否则首页那个「?」没人看得懂。
    if (result?.verifyHint != null) {
        Text(
            result.verifyHint,
            style = MaterialTheme.typography.bodySmall,
            color = noticeForeground(Color(0xFFB26A00)),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    event.sourceLabel?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp))
    }

    Spacer(Modifier.height(16.dp))
    if (target.available) {
        Button(modifier = Modifier.fillMaxWidth(), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent), onClick = { onOpenStudy(event) }) {
            Text(target.label, color = AppColors.OnAccent)
        }
    } else {
        // 如实置灰 —— 不做「点了没反应」，也不跳空页面。
        Button(modifier = Modifier.fillMaxWidth(), enabled = false, onClick = { }) {
            Text("暂无备考资料")
        }
        TextButton(modifier = Modifier.fillMaxWidth(), onClick = onOpenLibrary) {
            Text("去学习页找资料", color = themeColor(Color(0xFF6B7280), AppColors.TextMuted))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        TextButton(onClick = { onTogglePin(event.id, false) }) {
            Text("取消备考", color = noticeForeground(Color(0xFFB26A00)))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { onDismiss() }) { Text("收起", color = themeColor(Color(0xFF6B7280), AppColors.TextMuted)) }
    }
                }
}
