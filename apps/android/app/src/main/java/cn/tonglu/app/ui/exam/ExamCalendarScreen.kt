package cn.tonglu.app.ui.exam

import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.CampusBrowserScreen
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.themeColor
import cn.tonglu.app.ui.noticeForeground
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cn.tonglu.app.data.exam.ExamCalendarFeed
import cn.tonglu.app.data.exam.ExamCategory
import cn.tonglu.app.data.exam.ExamCountdown
import cn.tonglu.app.data.exam.ExamDateSource
import cn.tonglu.app.data.exam.ExamEvent
import cn.tonglu.app.data.exam.ExamSource
import cn.tonglu.app.data.exam.ExamStore
import cn.tonglu.app.data.exam.ExamSubjects
import cn.tonglu.app.data.exam.NationalExamSeed
import java.util.Calendar
import java.util.UUID

/**
 * 考试日历页（`docs/plan/54` M6）。
 *
 * **入口特意不放在首页**：M2 的第三条硬约束要求「关掉倒计时开关后首页不留任何痕迹」，
 * 所以这个页面从**学习页**进（它是学习类功能，首页保持干净）。
 *
 * 两个分区：
 *  · **国家考证**：年度表优先用服务端那份（`ExamCalendarFeed`，后台改完不用发版），
 *    拉不到就退回 APK 内置的 [NationalExamSeed]；
 *  · **我的考试**：用户自建 / 加入备考的，来自 [ExamStore]（按身份分桶）。
 *
 * **日期一律带来源**：抓不到官方数据时用户看到的是「待核实」，而不是假装正常的日期
 * （这是 M5 的硬要求，也是这个页面底部那行小字存在的理由）。
 */
@Composable
internal fun ExamCalendarScreen(
    store: ExamStore,
    onClose: () -> Unit,
    onOpenStudy: (ExamEvent) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val mine by store.events.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        store.reload()
        // 先把上次拉到的年度表装上（联网同步由 AppViewModel 在别处顺带做，不在这里等网络）
        ExamCalendarFeed.prime(context)
    }

    val todayEpochDay = remember { ExamCountdown.todayEpochDay() }
    val calendar = remember { Calendar.getInstance() }
    var year by remember { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(calendar.get(Calendar.MONTH) + 1) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf(setOf<String>()) }
    var adding by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ExamEvent?>(null) }
    // 四六级官方入口：App 内浏览器（和题库页「官方考试网」同一条路，见 CampusBrowserScreen）。
    // 独立窗口承载 —— 关掉浏览器回到考试日历，滚动位置与筛选都还在。
    var officialUrl by remember { mutableStateOf<String?>(null) }
    var officialTitle by remember { mutableStateOf("中国教育考试网 · 四六级") }

    // 年度表可能是服务端那份（ExamCalendarFeed 同步后 feed 会变），所以按 feed 重算
    val feed by ExamCalendarFeed.calendar.collectAsState()
    val calendarInfo = remember(feed) { ExamCalendarFeed.current(context) }
    val national = remember(feed, todayEpochDay) { ExamCalendarFeed.upcoming(context, todayEpochDay) }
    val groupList = remember(feed) { ExamCalendarFeed.groups(context) }
    // 「已加入」的判据 = 有一条**还在我的考试里、并且正在备考**的记录。
    //
    // 🔴 2026-10-15 用户报的 bug（Lead 转来）：原来这里只看 nationalExamId 的**存在性**
    //    （mapNotNull 出来就是"加过"），而「取消备考」只把 pinned 置 false、记录并不消失
    //    → 上面那一行永远回不到「加入备考」，点几次都一样。
    //    现在两边同一条口径：已加入 ⇔ 存在 && pinned && 未归档。
    //    这条口径同时救活了**两个入口**的取消备考：我的考试行里的、和考试详情面板里的
    //    （后者走 TongluApp 的 examStore.setPinned(id,false)，同一个字段）。
    val nationalJoined = remember(mine) {
        mine.filter { it.pinned && it.archivedAt == null }.mapNotNull { it.nationalExamId }.toSet()
    }

    val nationalEntries = national.filter { groups.isEmpty() || it.group in groups }
    val mineEntries = mine.filter { it.pinned && it.archivedAt == null }

    // 月历上要打点的日期：两个分区都算上
    val marked = remember(nationalEntries, mineEntries) {
        val set = mutableSetOf<String>()
        nationalEntries.forEach { set.add(it.date) }
        mineEntries.forEach { set.add(it.date) }
        set
    }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().background(AppColors.SurfaceOpaque).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary)) }
            Text("考试日历", color = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text("自定义") }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // ── 四六级入口（2026-10-15 迁移）────────────────────────────────────
            // 用户 2026-10-15：「要把四六级竞赛考试的入口直接放在考试页面，而不是放在资料库里面」，
            // 随后澄清「竞赛是竞赛，考试是考试，我说的是考试日历里面」→ 竞赛页保持独立，不搬。
            // 搬的是「学习 → 资料库 → 英语与考试 → 四级 / 六级」里那两篇**只做原站导航**的条目
            // （catalog 的 cet4-official-entry / cet6-official-entry：kind=LINK、正文为空）。
            // 资料库的浏览列表从此不再列它们（那里只留可读材料），入口挂到这一页。
            // 🔴 只搬入口、不搬正文：官方站不提供历年真题下载，真题版权归考试主办机构，
            //    这里不收录任何未授权全文（下方说明行如实告知）。
            // 那两篇**没有从目录数据里删** —— 收藏 / 搜索 / 上次阅读点进去仍到原来那屏。
            // 归属与返回语义：work/exam-entry-move-20261015/PLAN.md
            SectionTitle("四六级入口")
            CET_ENTRIES.forEach { entry ->
                // 下一场取同一份年度表（服务端那份拉不到时是内置表），没有就如实说没有。
                val next = national.firstOrNull { it.subjectKey == entry.subjectKey }
                val preview = next?.let { slot ->
                    ExamEvent(
                        id = "cet-preview-${entry.subjectKey}",
                        title = slot.title,
                        subjectKey = slot.subjectKey,
                        date = slot.date,
                        category = ExamCategory.NATIONAL,
                        source = ExamSource.NATIONAL,
                        nationalExamId = slot.id,
                        pinned = false,
                        dateSource = slot.dateSource,
                        sourceLabel = slot.sourceLabel,
                    )
                }
                val countdown = preview?.let { ExamCountdown.of(it, fixedUtcPlus8 = true) }
                CetEntryCard(
                    label = entry.label,
                    nextLine = when {
                        next == null -> "年度表里没有未过期的场次 · 日期以官方公告为准"
                        else -> "下一场 ${next.date}" +
                            (countdown?.label?.let { " · $it" } ?: "") +
                            (if (ExamDateSource.needsVerify(next.dateSource)) " · 待核实" else "")
                    },
                    onOpenOfficial = {
                        officialTitle = "中国教育考试网 · ${entry.label}"
                        officialUrl = ExamLink.CET_OFFICIAL_URL
                    },
                    // 「备考材料」深链到**既有**精读入口那一屏（应试考试材料 / 推荐文章），
                    // 一屏两扇门，不新造第二屏；路由由 ExamLink 的 prep key 决定。
                    onOpenMaterials = {
                        onOpenStudy(
                            ExamEvent(
                                id = "cet-entry-${entry.subjectKey}",
                                title = entry.label,
                                subjectKey = entry.prepKey,
                                date = next?.date.orEmpty(),
                                category = ExamCategory.NATIONAL,
                                source = ExamSource.NATIONAL,
                                nationalExamId = next?.id,
                                pinned = false,
                                dateSource = next?.dateSource ?: ExamDateSource.USER,
                                sourceLabel = next?.sourceLabel,
                            )
                        )
                    },
                )
            }
            Text(
                "官方站只提供报名、大纲与查分，不提供历年真题下载；真题版权归考试主办机构，" +
                    "这里只做原站导航、不收录未授权全文。备考材料在精读入口，与「学习 → 资料库」里的" +
                    "文章是同一批，进度和收藏共享。",
                fontSize = 11.sp, color = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted),
                modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 12.dp),
            )

            // 月份切换 + 月历网格
            Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            if (month == 1) { year -= 1; month = 12 } else month -= 1
                        }) { Text("‹", color = themeColor(Color(0xFF4B5563), AppColors.TextSecondary)) }
                        Text("${year}年${month}月", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        TextButton(onClick = {
                            if (month == 12) { year += 1; month = 1 } else month += 1
                        }) { Text("›", color = themeColor(Color(0xFF4B5563), AppColors.TextSecondary)) }
                    }
                    MonthGrid(year = year, month = month, marked = marked, selected = selectedDate,
                        onSelect = { selectedDate = if (selectedDate == it) null else it })
                }
            }

            Spacer(Modifier.height(12.dp))

            // 分类筛选（只作用于国家考证区）
            Surface(color = AppColors.Surface, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("分类筛选", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        groupList.forEach { group ->
                            val on = group in groups
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (on) AppColors.Accent else themeColor(Color(0xFFF0F2F5), AppColors.ChipIdle),
                                modifier = Modifier.clickable {
                                    groups = if (on) groups - group else groups + group
                                },
                            ) {
                                Text(
                                    group, fontSize = 12.sp,
                                    color = if (on) AppColors.OnAccent else themeColor(Color(0xFF4B5563), AppColors.TextSecondary),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 国家考证
            SectionTitle("国家考证")
            val shownNational = nationalEntries.filter { selectedDate == null || it.date == selectedDate }
            if (shownNational.isEmpty()) {
                EmptyLine(if (selectedDate == null) "这一年表里没有更多场次了" else "这一天没有国家考证")
            } else {
                shownNational.forEach { exam ->
                    val joined = exam.id in nationalJoined
                    Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(exam.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    exam.date + (exam.endDate?.let { " ~ $it" } ?: "") +
                                        (if (ExamDateSource.needsVerify(exam.dateSource)) " · 待核实" else ""),
                                    fontSize = 12.sp, color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                                )
                            }
                            if (joined) {
                                Text("已加入", fontSize = 12.sp, color = noticeForeground(Color(0xFF12833B)))
                            } else {
                                TextButton(onClick = {
                                    // **幂等**：同一条国家考证如果本机已经留过记录（取消备考之后
                                    // 记录是留着的），就把它重新置顶 —— 不能再 upsert 一条新的，
                                    // 否则「加入备考 → 取消备考 → 再加入」会在「我的考试」里攒出
                                    // 两条同名考试（同一条口径的另一半，见上面 nationalJoined）。
                                    val existing = mine.firstOrNull { it.nationalExamId == exam.id }
                                    if (existing != null) {
                                        store.setPinned(existing.id, true)
                                    } else {
                                        store.upsert(
                                            ExamEvent(
                                                id = UUID.randomUUID().toString(),
                                                title = exam.title,
                                                subjectKey = exam.subjectKey,
                                                date = exam.date,
                                                category = ExamCategory.NATIONAL,
                                                source = ExamSource.NATIONAL,
                                                nationalExamId = exam.id,
                                                pinned = true,
                                                dateSource = exam.dateSource,
                                                sourceLabel = exam.sourceLabel,
                                            )
                                        )
                                    }
                                }) { Text("加入备考") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 我的考试
            SectionTitle("我的备考")
            val shownMine = mineEntries.filter { selectedDate == null || it.date == selectedDate }
            if (shownMine.isEmpty()) {
                EmptyLine("还没有正在备考的考试，可在上方加入或自定义")
            } else {
                shownMine.forEach { event ->
                    val result = ExamCountdown.of(event, fixedUtcPlus8 = event.category == ExamCategory.NATIONAL)
                    Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).animateContentSize()) {
                        Column {
                        Row(Modifier.fillMaxWidth().clickable { detail = if (detail?.id == event.id) null else event }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(event.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    "${event.date} · ${result?.label ?: "日期待确认"}" +
                                        (if (result?.needsVerify == true) " ?" else ""),
                                    fontSize = 12.sp, color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                                )
                            }
                            TextButton(onClick = { detail = if (detail?.id == event.id) null else event }) {
                                Text(if (detail?.id == event.id) "收起详情" else "展开详情")
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(visible = detail?.id == event.id) {
                            ExamDetailContent(
                                event = event,
                                onDismiss = { detail = null },
                                onOpenStudy = onOpenStudy,
                                onOpenLibrary = onOpenLibrary,
                                onTogglePin = { id, pinned -> store.setPinned(id, pinned); detail = null },
                            )
                        }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                // 数据来源必须如实说清：内置表是「核实于打包那天」，服务端那份才是当下的。
                (if (calendarInfo.online) "考试日期来自服务端年度表" else "还没拉到服务端年度表，用的是 App 内置表") +
                    (calendarInfo.verifiedAt.takeIf { it.isNotBlank() }?.let { "（核实于 $it）" } ?: "") +
                    "，官方公告为准；带「待核实」的是按往年规律推算的。" +
                    "普通话水平测试没有全国统一日期，只能自己添加。",
                fontSize = 11.sp, color = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted), modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (adding) {
        AddExamDialog(
            onDismiss = { adding = false },
            onConfirm = { title, date ->
                store.upsert(
                    ExamEvent(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        subjectKey = ExamSubjects.CUSTOM,
                        date = date,
                        category = ExamCategory.OTHER,
                        source = ExamSource.MANUAL,
                        pinned = true,
                        dateSource = ExamDateSource.USER,
                    )
                )
                adding = false
            },
        )
    }


    // 官方入口的浏览器层：**画在考试日历之上的同一窗口内层** —— 和 TongluApp 里那个
    // 「竞赛公告」浏览器（`browserUrl`）走的是同一条路：退场动画、返回键（网页能后退先退网页）、
    // 不透明底色都由 CampusBrowserScreen 自己的 overlayMotion 提供，关掉就回到这一页。
    // ⚠️ 刻意**不再套一层 Dialog**：套了本文件就出现「无平台默认宽度」的全屏 Dialog 属性
    //    而自己没有任何 motion，会被 `scripts/audit_overlay_structure.py` 的 C11 判红
    //    （「每个全屏层都要有退场动画」）。实测：套 Dialog 的写法就是那条红灯。
    //    （那条判据是**按文件里有没有那个属性字符串**扫的 —— 连注释里写出它也算，别写回去。）
    officialUrl?.let { url ->
        CampusBrowserScreen(
            url = url,
            title = officialTitle,
            onClose = { OverlayDismissGuard.arm(); officialUrl = null },
        )
    }
}

/**
 * 四六级入口的一行。
 *
 * @param subjectKey 用来在年度表里找「下一场」（[ExamSubjects.CET4] / [ExamSubjects.CET6]）
 * @param prepKey 「备考材料」深链用的 key（[ExamLink.CET4_PREP] / [ExamLink.CET6_PREP]）——
 *   刻意**不复用** subjectKey：那条映射（→ 英语中心 `words`）是考试详情「去备考」的老路径。
 */
private data class CetEntry(val subjectKey: String, val prepKey: String, val label: String)

private val CET_ENTRIES = listOf(
    CetEntry(ExamSubjects.CET4, ExamLink.CET4_PREP, "英语四级（CET-4）"),
    CetEntry(ExamSubjects.CET6, ExamLink.CET6_PREP, "英语六级（CET-6）"),
)

/**
 * 四六级入口卡：一行考试 + 下一场 + 两个动作。
 *
 * 为什么不在这里再放一个「加入备考」：那个动作已经属于下面「国家考证」里带日期的那一行，
 * 同一件事不摆两份（用户口径：不要让用户在两个地方看到同一件事）。
 */
@Composable
private fun CetEntryCard(
    label: String,
    nextLine: String,
    onOpenOfficial: () -> Unit,
    onOpenMaterials: () -> Unit,
) {
    Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                nextLine,
                fontSize = 12.sp, color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onOpenOfficial, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("官方入口", fontSize = 13.sp)
                }
                OutlinedButton(onClick = onOpenMaterials, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("备考材料", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp, start = 2.dp))
}

@Composable
private fun EmptyLine(text: String) {
    Surface(color = AppColors.Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 13.sp, color = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted), modifier = Modifier.padding(14.dp))
    }
}

/**
 * 月历网格。有考试的日期打点，选中高亮。
 *
 * 星期从周日开始（与 `Calendar` 一致，也符合国内日历习惯）。
 */
@Composable
private fun MonthGrid(
    year: Int,
    month: Int,
    marked: Set<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val cells = remember(year, month) {
        val calendar = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
        val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // 1=周日
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val list = mutableListOf<String?>()
        repeat(firstDayOfWeek - 1) { list.add(null) }
        for (day in 1..daysInMonth) list.add("%04d-%02d-%02d".format(year, month, day))
        list
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 11.sp, color = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted))
                }
            }
        }
        // 按周切行：cells 一定是 7 的倍数（前面补了占位）
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                week.forEach { date ->
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clickable(enabled = date != null) {
                            if (date != null) onSelect(date)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (date != null) {
                            val day = date.substringAfterLast("-").toIntOrNull() ?: 0
                            val isSelected = date == selected
                            val hasExam = date in marked
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .background(
                                        color = if (isSelected) AppColors.Accent else Color.Transparent,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$day",
                                    fontSize = 13.sp,
                                    fontWeight = if (hasExam) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> AppColors.OnAccent
                                        hasExam -> AppColors.Accent
                                        else -> themeColor(Color(0xFF374151), AppColors.TextPrimary)
                                    },
                                )
                            }
                            // 打点：有考试的日期下方一个小圆点
                            if (hasExam && !isSelected) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 3.dp)
                                        .size(4.dp)
                                        .background(AppColors.Accent, CircleShape),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 自定义考试（手动创建）。
 *
 * 日期用文本输入 `yyyy-MM-dd` —— M4 的主要用法是「从国家考证库里选」（日期自动带出），
 * 手动只是补那些库里没有的（校内的、地方的）。所以这里不值得为它做一个完整的日期选择器，
 * 但**格式必须校验并如实提示**，不能让用户存进去一个算不出天数的日期。
 */
@Composable
private fun AddExamDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    val valid = title.isNotBlank() && ExamCountdown.dateToEpochDay(date) != null
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = AppColors.Surface, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("添加考试", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    title, { title = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
                    label = { Text("考试名称") }, singleLine = true,
                )
                OutlinedTextField(
                    date, { date = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("考试日期（如 2026-12-19）") }, singleLine = true,
                )
                if (date.isNotBlank() && ExamCountdown.dateToEpochDay(date) == null) {
                    Text("日期格式不对，请按 2026-12-19 这样填", fontSize = 12.sp, color = themeColor(Color(0xFFD93A3A), AppColors.Danger),
                        modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onDismiss() }) { Text("取消", color = themeColor(Color(0xFF6B7280), AppColors.TextMuted)) }
                    TextButton(enabled = valid, onClick = { onConfirm(title.trim(), date.trim()) }) {
                        Text("添加")
                    }
                }
            }
        }
    }
}
