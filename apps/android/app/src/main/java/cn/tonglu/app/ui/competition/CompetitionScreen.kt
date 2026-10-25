package cn.tonglu.app.ui.competition

import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.themeColor
import cn.tonglu.app.ui.noticeForeground
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.TongluApi
import cn.tonglu.app.data.competition.Competition
import cn.tonglu.app.data.competition.competitionOpenForRegistration
import cn.tonglu.app.data.competition.CompetitionCatalog
import cn.tonglu.app.data.competition.CompetitionCountdown
import cn.tonglu.app.data.competition.CompetitionDeadline
import cn.tonglu.app.data.competition.CompetitionKind
import cn.tonglu.app.data.competition.CompetitionSubscriptionStore
import cn.tonglu.app.data.competition.Subscription
import cn.tonglu.app.data.exam.ExamCountdown
import kotlinx.coroutines.launch

private val Ink get() = themeColor(Color(0xFF1F2937), AppColors.TextPrimary)
private val Muted get() = themeColor(Color(0xFF6B7280), AppColors.TextMuted)
private val Faint get() = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted)
private val Accent get() = AppColors.Accent
private val Urgent get() = noticeForeground(Color(0xFFB26A00))
private val CardBg get() = AppColors.Surface
private val PageBg get() = AppColors.Canvas

/**
 * 竞赛与「我的日程」页（`docs/plan/56` §2.3）。
 *
 * 两个分区，上下叠：
 *  · **我的日程**：我订阅的竞赛截止节点，按剩余天数升序 —— 这是「会提醒我的东西」；
 *  · **竞赛目录**：学校已发布的赛事（服务端那份），可逐个期限订阅。
 *
 * ## 三条不能破的规矩（与后端同源，见 `CompetitionCountdown` 注释）
 * 1. **日期待核实的不倒计时** —— 显示「日期待核实」而不是天数。
 *    服务端本来就不会下发未核实的期限，这里是第二道闸：万一字段在但值是空的，
 *    界面也不能编一个日期出来。
 * 2. **只到日的不补时刻** —— 用服务端给的 `display`（「当日截止，时间以原文为准」）。
 * 3. 订阅被拒**必须把服务端原话说出来**（最典型：未核实的期限不能订阅）。
 */
@Composable
internal fun CompetitionScreen(
    api: TongluApi,
    bearer: String?,
    onClose: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CompetitionSubscriptionStore.get(context) }

    val catalog by CompetitionCatalog.catalog.collectAsState()
    val catalogError by CompetitionCatalog.error.collectAsState()
    val subs by store.subscriptions.collectAsState()
    val message by store.message.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var subscribedOnly by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        try {
            CompetitionCatalog.sync(context, api)
            if (bearer != null) store.refresh(api, bearer)
        } finally { loading = false }
    }
    LaunchedEffect(bearer) {
        CompetitionCatalog.prime(context)
        refresh()
    }

    val items = catalog ?: emptyList()
    val mySubs = subs ?: emptyList()
    val todayMillis = remember { System.currentTimeMillis() }
    // 推测是纯函数（见文件末尾 CompetitionForecastRules），目录一变就重算一次。
    val forecasts = remember(items, todayMillis) { CompetitionForecastRules.forecast(items, todayMillis) }

    Column(Modifier.fillMaxSize().background(PageBg).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().background(AppColors.SurfaceOpaque).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary)) }
            Text("竞赛与日程", color = themeColor(androidx.compose.material3.LocalContentColor.current, AppColors.TextPrimary), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(enabled = !loading, onClick = { scope.launch { refresh() } }) { Text(if (loading) "同步中…" else "刷新") }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Surface(color = CardBg, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Wifi, null, tint = Accent, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("学校平台需校园网", color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("学校双创平台必须连接校园网才能打开，移动数据或校外 Wi-Fi 无法访问。请先连接校园网，再查看校内公告或报名。",
                            color = AppColors.TextSecondary, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            // ── 我的日程 ────────────────────────────────────────────────
            SectionTitle("我的日程")
            if (bearer == null) {
                HintCard("登录后才能订阅截止提醒。订阅跟着账号走，换手机不用重新订。")
            } else if (mySubs.isEmpty()) {
                HintCard("还没订阅任何截止提醒。往下翻到「竞赛目录」，点某条期限右边的「提醒我」。")
            } else {
                mySubs.sortedBy { sub ->
                    // 已失效的排最后；其余按剩余天数（没有日期的排在有日期的后面）
                    val result = sub.deadline?.let { CompetitionCountdown.of(it, todayMillis) }
                    when {
                        sub.invalidated -> Int.MAX_VALUE
                        result == null -> Int.MAX_VALUE - 1
                        result.phase == CompetitionCountdown.Phase.ENDED -> Int.MAX_VALUE - 2
                        else -> result.days
                    }
                }.forEach { sub ->
                    ScheduleCard(sub = sub, nowMillis = todayMillis, onUnsubscribe = {
                        scope.launch {
                            store.setEnabled(api, bearer, sub.eventId, sub.deadlineId, false)
                        }
                    }, onOpenUrl = onOpenUrl)
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 竞赛目录 ────────────────────────────────────────────────
            SectionTitle("竞赛目录")
            catalogError?.let { error ->
                HintCard("同步失败：$error" + if (items.isNotEmpty()) "。下面保留上次目录，可点击刷新重试。" else "。请点击刷新重试。")
            }
            if (items.isEmpty()) {
                HintCard(
                    if (loading) "正在同步…"
                    else if (catalogError != null) "暂时无法取得赛事目录。"
                    else "平台尚未发布赛事目录，不代表学校没有比赛。可先查看学校双创平台的原公告。"
                )
                TextButton(onClick = { onOpenUrl(cn.tonglu.app.data.CampusServices.SC_URL) }) {
                    Text("查看学校双创平台（需校园网）")
                }
            } else {
                val subscribedIds = remember(mySubs) { mySubs.map { it.deadlineId }.toSet() }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip("全部", !subscribedOnly) { subscribedOnly = false }
                    Chip("只看可报名的", subscribedOnly) { subscribedOnly = true }
                }
                val shown = items.filter { competition ->
                    !subscribedOnly || competitionOpenForRegistration(competition, todayMillis)
                }
                if (shown.isEmpty()) {
                    HintCard("没有还在报名期内的赛事了。")
                } else {
                    shown.forEach { competition ->
                        CompetitionCard(
                            competition = competition,
                            nowMillis = todayMillis,
                            subscribedIds = subscribedIds,
                            canSubscribe = bearer != null,
                            onSubscribe = { deadline, enabled ->
                                // canSubscribe=false 时按钮是「登录后可订阅」，点不到这里；
                                // 但 bearer 是可空类型，这里必须显式收窄，不然编译不过。
                                val token = bearer
                                if (token != null) {
                                    scope.launch {
                                        store.setEnabled(api, token, competition.id, deadline.id, enabled)
                                    }
                                }
                            },
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 可能要来 · 提前准备（用户 2026-10-15 第 3 条）────────────────
            // 用户原话：「应该是可以获取到未来能有什么比赛，或者推测未来会有什么比赛的，因为去年和今年
            // 打的比赛基本上都是相同的内容……新增一个栏目，用于推测会有什么比赛以便提前准备。」
            // 口径（Lead 定）：**不新采集数据**，只拿公开目录里**跨年度同名赛事**当原料推窗口；
            // 推测必须写明依据与样本数，数据不足就如实说「暂不能推测」，绝不编一个日期。
            SectionTitle("可能要来 · 提前准备")
            HintCard("按往年同一赛事的报名日期推算，只作提前准备的时间参考；当年官方公告出来后以「竞赛目录」里的真实期限为准。")
            if (forecasts.isEmpty()) {
                HintCard(
                    "数据不足，暂不能推测 —— 需要同一赛事至少两个年度的报名记录。" +
                        "现在公开目录里还没有这样的样本，等往年赛事也上架后这里会自动出现推测。"
                )
            } else {
                forecasts.forEach { forecast -> ForecastCard(forecast = forecast, onOpenUrl = onOpenUrl) }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("赛事原公告")
            HintCard("报名资格、校内选拔与截止时间以原公告为准。这些入口不表示当前正在报名。")
            TextButton(onClick = { onOpenUrl(cn.tonglu.app.data.CampusServices.SC_URL) }) {
                Text("辽东学院双创平台（需校园网）")
            }
            TextButton(onClick = { onOpenUrl("https://www.cms.org.cn/index.php/Home/comp/comp/cid/16.html") }) {
                Text("全国大学生数学竞赛 · 中国数学会")
            }
            TextButton(onClick = { onOpenUrl("https://neccs.saikr.com/") }) {
                Text("全国大学生英语竞赛 · 组委会公告")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "截止日期来自学校公告，只展示已核实过的期限；原文只写到日期的，按「当日截止」提醒，不替你补具体钟点。" +
                    "没核实过的日期不会出现在这里，也不会被提醒。",
                fontSize = 11.sp, color = Faint, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    // 操作结果一次性提示（订上了 / 被拒了）。服务端拒绝的原因要原样说出来。
    message?.let { text ->
        LaunchedEffect(text) {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            store.consumeMessage()
        }
    }
}

/** 我订阅的一条截止节点。 */
@Composable
private fun ScheduleCard(
    sub: Subscription,
    nowMillis: Long,
    onUnsubscribe: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val deadline = sub.deadline
    val result = deadline?.let { CompetitionCountdown.of(it, nowMillis) }

    Surface(color = CardBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sub.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onUnsubscribe) { Text("取消", color = Muted, fontSize = 13.sp) }
            }
            if (sub.invalidated) {
                Text("这场赛事的这条期限已失效（主办方改期或赛事已下架）", fontSize = 12.sp, color = Urgent)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        deadline?.kindLabel ?: "截止",
                        fontSize = 12.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (result != null && !result.unknown) {
                        Text(result.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (result.urgent) Urgent else Accent)
                        if (result.urgent) {
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Outlined.Alarm, "临近", tint = Urgent, modifier = Modifier.size(14.dp))
                        }
                    } else {
                        Text("日期待核实", fontSize = 12.sp, color = Urgent)
                    }
                }
                // 日期本身 + 精度说明。**不补 23:59** —— display 是服务端给的原话。
                Text(
                    deadline?.let(::competitionDeadlineLabel) ?: "日期待核实",
                    fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp),
                )
                if (sub.changed) {
                    Text("主办方更新过时间，上面的日期已是最新", fontSize = 11.sp, color = Urgent, modifier = Modifier.padding(top = 2.dp))
                }
            }
            sub.sourceUrl?.let { url ->
                TextButton(onClick = { onOpenUrl(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("官网 / 原文", fontSize = 12.sp)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(13.dp), tint = Accent)
                }
            }
        }
    }
}

/** 目录里的一场赛事，展开它的每条期限。 */
@Composable
private fun CompetitionCard(
    competition: Competition,
    nowMillis: Long,
    subscribedIds: Set<String>,
    canSubscribe: Boolean,
    onSubscribe: (CompetitionDeadline, Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Surface(color = CardBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(competition.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
            val meta = listOfNotNull(competition.organizer, competition.academicYear).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, fontSize = 11.sp, color = Faint, modifier = Modifier.padding(top = 2.dp))

            Spacer(Modifier.height(6.dp))

            if (competition.deadlines.isEmpty()) {
                Text("期限待核实 · 查看原公告确认报名安排", fontSize = 12.sp, color = Urgent)
            }
            competition.deadlines.forEach { deadline ->
                val result = CompetitionCountdown.of(deadline, nowMillis)
                val subscribed = deadline.id in subscribedIds
                val ended = result.phase == CompetitionCountdown.Phase.ENDED
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(deadline.kindLabel, fontSize = 12.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (result.unknown) "日期待核实" else if (ended) "已过" else result.label,
                                fontSize = 12.sp,
                                color = if (result.unknown || ended) Muted else if (result.urgent) Urgent else Accent,
                                fontWeight = if (!result.unknown && !ended) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        Text(
                            competitionDeadlineLabel(deadline),
                            fontSize = 11.sp, color = Faint,
                        )
                    }
                    when {
                        ended -> Text("已结束", fontSize = 12.sp, color = Faint)
                        // 未核实的期限：服务端会 400。这里直接不给按钮，省得用户点了才知道。
                        result.unknown -> Text("未核实", fontSize = 12.sp, color = Faint)
                        subscribed -> TextButton(onClick = { onSubscribe(deadline, false) }) {
                            Text("已订阅", color = Accent, fontSize = 13.sp)
                        }
                        !canSubscribe -> Text("登录后可订阅", fontSize = 11.sp, color = Faint)
                        else -> TextButton(onClick = { onSubscribe(deadline, true) }) { Text("提醒我") }
                    }
                }
            }

            competition.sourceUrl?.let { url ->
                TextButton(onClick = { onOpenUrl(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("官网 / 原文", fontSize = 12.sp)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(13.dp), tint = Accent)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Ink,
        modifier = Modifier.padding(bottom = 8.dp, start = 2.dp))
}

@Composable
private fun HintCard(text: String) {
    Surface(color = CardBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(text, fontSize = 12.sp, color = Faint, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (on) Accent else themeColor(Color(0xFFF0F2F5), AppColors.ChipIdle), modifier = Modifier.clickable(onClick = onClick)) {
        Text(text, fontSize = 12.sp, color = if (on) AppColors.OnAccent else Muted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}
/**
 * 「可能要来」的一条推测。
 *
 * 只由 [CompetitionForecastRules] 造出来，界面不再加工 —— **推测和依据必须一起显示**，
 * 否则用户会把它当成官方已发布的赛程。
 */
internal data class CompetitionForecast(
    val title: String,
    val organizer: String?,
    val sourceUrl: String?,
    /** 预测年份，如 2027 */
    val year: Int,
    /** 月份 1..12 */
    val month: Int,
    /** 日期落在哪一段：上旬 / 中旬 / 下旬（按旬写是有意的，见规则 KDoc） */
    val segment: String,
    /** 依据样本（`yyyy-MM-dd`，按时间倒序），至少两条 */
    val samples: List<String>,
) {
    /** 「预计 2027 年 3 月中旬」 */
    val windowLabel: String get() = "预计 $year 年 $month 月$segment"

    /** 置信口径：样本几个年度、月份是不是一致 —— 这句话必须跟推测一起出现。 */
    val basisLabel: String
        get() {
            val months = samples.map { it.substring(5, 7).toInt() }.distinct()
            val tail = when {
                samples.size >= 3 && months.size == 1 -> "，近 ${samples.size} 个年度都落在这个窗口，样本较稳"
                months.size > 1 -> "，往年月份不完全一致（${months.sorted().joinToString("、") { "$it 月" }}），仅供参考"
                else -> "，样本只有 ${samples.size} 个年度，仅供参考"
            }
            return "依据：" + samples.joinToString("、") + " 的报名日期" + tail
        }
}

/**
 * 「推测未来会有什么比赛」的规则（用户 2026-10-15 第 3 条）。
 *
 * ## 原料与边界（**不新采集任何数据**）
 * 只用公开目录里已有的 [Competition]：标题 + 学年 + 已核实的报名期限。因此：
 *  · **同一赛事至少两个年度**的报名日期才推 —— 一个样本推不出规律，只会编；
 *  · 已经在报名期内的赛事不算「可能要来」（它就在上面的「竞赛目录」里）；
 *  · 只推**报名**窗口（[CompetitionKind.REGISTRATION]），不推比赛/提交时间。
 *
 * ## 为什么按「旬」而不是某一天
 * 公告日期每年会飘几天（3/12 → 3/16），写死某一天是假精确。
 * 「月份 + 上/中/下旬」才是这份数据撑得住的精度，界面也照这个口径写。
 *
 * ## 纯函数
 * 不碰 Android、不自己读时钟（now 由调用方传）—— 跨年、闰年、当年已过这几种边界
 * 才能被确定性验证，而不是靠走查。
 */
internal object CompetitionForecastRules {

    /** 归一化标题：年份、届次、空白与标点都抹掉，让跨年度的同名赛事归到一组。 */
    internal fun normalizeTitle(raw: String): String =
        raw.replace(Regex("\\d{4}\\s*年?"), "")
            .replace(Regex("第\\s*[0-9一二三四五六七八九十]+\\s*届"), "")
            .replace(Regex("[\\s\\u3000·、,，.。()（）\\[\\]【】《》\\-]"), "")
            .lowercase()

    /** 这场赛事的报名日期（`yyyy-MM-dd`）；没有就不参与推测。 */
    private fun registrationDate(competition: Competition): String? =
        competition.deadlines
            .firstOrNull { it.kind == CompetitionKind.REGISTRATION }
            ?.let { CompetitionCountdown.dateOf(it) }

    /** 推测下一年度的报名窗口；按月份升序最多 [limit] 条。 */
    internal fun forecast(
        items: List<Competition>,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 5,
    ): List<CompetitionForecast> {
        val today = ExamCountdown.todayEpochDay(nowMillis, fixedUtcPlus8 = true)
        val thisYear = ExamCountdown.epochDayToDate(today).substring(0, 4).toIntOrNull() ?: 2026
        return items
            .filterNot { competitionOpenForRegistration(it, nowMillis) }
            .groupBy { normalizeTitle(it.title) }
            .mapNotNull { (_, group) ->
                val samples = group.mapNotNull { competition ->
                    registrationDate(competition)?.let { date -> Sample(competition, date) }
                }
                // 至少两个不同年度：只有一个年度那叫「回顾」，不叫「推测」。
                if (samples.map { it.date.substring(0, 4) }.distinct().size < 2) return@mapNotNull null

                // 月份取众数；同年同月里的日期取中位数当代表 —— 代表日只用来决定「今年还是明年」，
                // 界面显示的是「旬」，不会把中位数当成公告日期。
                val month = samples.groupingBy { it.date.substring(5, 7).toInt() }
                    .eachCount().maxByOrNull { it.value }!!.key
                val days = samples.map { it.date }
                    .filter { it.substring(5, 7).toInt() == month }
                    .map { it.substring(8, 10).toInt() }
                    .sorted()
                val representativeDay = days[days.size / 2].coerceIn(1, 28)
                val segment = when {
                    representativeDay <= 10 -> "上旬"
                    representativeDay <= 20 -> "中旬"
                    else -> "下旬"
                }
                val candidate = "%04d-%02d-%02d".format(thisYear, month, representativeDay)
                val passed = (ExamCountdown.dateToEpochDay(candidate) ?: Long.MAX_VALUE) < today
                val latest = samples.maxByOrNull { it.date }!!
                CompetitionForecast(
                    title = latest.competition.title,
                    organizer = latest.competition.organizer,
                    sourceUrl = latest.competition.sourceUrl,
                    year = if (passed) thisYear + 1 else thisYear,
                    month = month,
                    segment = segment,
                    samples = samples.map { it.date }.sortedDescending(),
                )
            }
            .sortedWith(compareBy({ it.month }, { it.segment }))
            .take(limit)
    }

    private data class Sample(val competition: Competition, val date: String)
}

/** 「可能要来」的一条：预测窗口 + 依据 + 官网（**有才显示**）。 */
@Composable
private fun ForecastCard(forecast: CompetitionForecast, onOpenUrl: (String) -> Unit) {
    Surface(color = CardBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(forecast.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
            forecast.organizer?.let {
                Text(it, fontSize = 11.sp, color = Faint, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                forecast.windowLabel + "开始报名",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Accent,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(forecast.basisLabel, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
            forecast.sourceUrl?.let { url ->
                TextButton(onClick = { onOpenUrl(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("官网 / 原文", fontSize = 12.sp)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(13.dp), tint = Accent)
                }
            }
        }
    }
}

/** 同一条期限在目录和我的日程使用同一口径；UTC时刻先转北京时间再展示。 */
internal fun competitionDeadlineLabel(deadline: CompetitionDeadline): String {
    if (deadline.precision != "DATETIME") {
        return "${deadline.date.orEmpty()} · 当日截止，时间以原文为准"
    }
    val raw = deadline.deadlineAt
    val instant = raw?.let {
        runCatching { java.time.Instant.parse(it) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull()
    }
    if (instant != null) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .withZone(java.time.ZoneOffset.ofHours(8)).format(instant) + "（北京时间）"
    }
    // 旧缓存可能没有偏移。只给出已知日期，不把不确定时刻当准确截止点。
    return "${deadline.date.orEmpty()} · 具体时间待核实"
}
