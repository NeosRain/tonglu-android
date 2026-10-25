package cn.tonglu.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.Levels
import cn.tonglu.app.data.MyLevelDto
import cn.tonglu.app.data.TrustConditionDto
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/**
 * 「我的等级」页（docs/plan/66 §十：全量展示）。
 *
 * 🔴 **TL 只在这里显示，且只给本人看**（红线 8）。展示 TL 的理由和作者行不显示它
 * 是同一条：TL 是风控面，公开等于给刷子画靶子（「刷到 TL2 就能发外链」的路径被明码标价）。
 *
 * 🔴 **这页不给任何权限**（红线 1）。不要因为「Lv5 了是不是该给点什么」而在这里加开关。
 *
 * 降级必须给原因且可查：所以「信任等级变动」把服务端的 human-readable reason 直接展示，
 * 不做二次翻译（服务端已保证不写代号）。
 */
@Composable
internal fun MyLevelScreen(
    load: suspend () -> Result<MyLevelDto>,
    onToggleGrowth: suspend (optOut: Boolean) -> Result<Unit>,
    onClose: () -> Unit,
    topInset: Dp = 0.dp,
) {
    var level by remember { mutableStateOf<MyLevelDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var toggling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        load().onSuccess { level = it; error = null }
            .onFailure { error = "等级数据加载失败：${it.message ?: "网络异常"}。稍后下拉可重试。" }
    }

    // ⚠️ 这里**不要**再加 safeDrawingPadding()：顶部内边距已经由 LevelTopBar 的
    // statusBarsPadding() 吃了，两处都加会把状态栏高度算两遍（顶栏下面多留一条空白）。
    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        LevelTopBar(onClose)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 40.dp + topInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 🔴 错误不能只挂在「还没数据」这个分支里：数据加载成功**之后**再失败
            // （比如切换「参与成长等级」被后端 400 拒），用户看到的就只是开关弹回去、
            // 什么都没发生 —— 这正是 BUGS A28 里最难查的那一半。
            if (error != null) {
                item {
                    Text(
                        error!!,
                        color = AppColors.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val dto = level
            if (dto == null) {
                item {
                    Text(
                        "正在读取等级…",
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }

            item { LevelHeadline(dto) }
            item { ExpCard(dto) }

            // —— 信任等级（仅本人）——
            item { TrustCard(dto) }

            // —— 参与成长等级开关 ——
            item {
                MiuixCard(insideMargin = PaddingValues(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("参与成长等级", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (dto.growthOptedOut)
                                    "已关闭。学习计数不再累计，已获得的等级和经验保留。"
                                else
                                    "背词、练题、阅读、真题会折算成经验。只上传数字，不上传读了什么。",
                                color = AppColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Switch(
                            checked = !dto.growthOptedOut,
                            enabled = !toggling,
                            onCheckedChange = { want ->
                                toggling = true
                                scope.launch {
                                    // 🔴 `want` 是「开关要不要勾上」= 参不参与；
                                    // 而 onToggleGrowth 这个参数名是 optOut —— 方向相反，
                                    // 少了这个取反就会把「关闭参与」发成「取消退出」，服务端一次空操作。
                                    onToggleGrowth(!want).onSuccess {
                                        level = dto.copy(growthOptedOut = !want)
                                    }.onFailure {
                                        error = "切换失败：${it.message ?: "网络异常"}"
                                    }
                                    toggling = false
                                }
                            },
                        )
                    }
                }
            }

            item { RulesCard() }
        }
    }
}

@Composable
private fun LevelTopBar(onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onClose() }) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Text("我的等级", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LevelHeadline(dto: MyLevelDto) {
    MiuixCard(insideMargin = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelBadge(dto.level, dto.levelName)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(dto.levelName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (dto.nextLevel != null) {
                            Text(
                                "距 Lv${dto.nextLevel} ${dto.nextLevelName.orEmpty()}还差 ${dto.expToNext} 点",
                                color = AppColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("已达最高等级", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (dto.streakDays > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("${dto.streakDays}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AppColors.Accent)
                    Text("连续活跃天", color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // 进度条：已超额时 progress 会是 1f，且文案由 expToNext=0 如实显示「已达最高等级」。
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(AppColors.ChipIdle),
        ) {
            Box(
                Modifier.fillMaxWidth(dto.progress.coerceIn(0f, 1f)).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(AppColors.Accent),
            )
        }
    }
}

/**
 * EXP 双线。
 *
 * 🔴 刻意分开显示（plan/66 §2.1）：合并成一条会出现「背单词背到 Lv7 但从不发帖」的人
 * 自动拿到社区高权限 —— 那不是信任，那是刷题。学习线不给社区权限，所以要分开。
 */
@Composable
private fun ExpCard(dto: MyLevelDto) {
    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        Text("经验", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ExpRow("学习经验", dto.expLearn, "背词、练题、阅读、真题、资料包")
        ExpRow("社区经验", dto.expSocial, "发帖、回复、评论被赞、举报被采纳")
        Spacer(Modifier.height(6.dp))
        Text(
            "合计 ${dto.expTotal}。等级只涨不跌 —— 删帖也不会掉段位。",
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ExpRow(label: String, value: Int, hint: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppColors.Accent)
    }
}

/** 信任等级 + 逐条件达成态。「还差哪一条」必须能自查（Linux.do 的 connect 是反面例子）。 */
@Composable
private fun TrustCard(dto: MyLevelDto) {
    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("信任等级", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    // muted 时要说清原因，不能静默显示（红线 4：降级必须给原因）
                    if (dto.muted) "已被禁言，信任等级锁定在 TL0"
                    else "仅你可见，别人看不到这一项",
                    color = if (dto.muted) AppColors.Danger else AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TrustBadge(dto.trustLevel ?: 0, dto.trustName.orEmpty())
        }
        if (dto.muted) {
            Spacer(Modifier.height(10.dp))
            Text(
                "禁言结束后会从 TL1重新开始爬。恢复途径与解禁时间会在消息里通知你。",
                color = AppColors.Danger,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val conditions = dto.trustConditions.orEmpty()
        if (conditions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (dto.trustUpgradeReady) "已满足下一级的全部条件" else "升到下一级还差：",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (dto.trustUpgradeReady) AppColors.Accent else AppColors.TextPrimary,
            )
            conditions.forEach { TrustConditionRow(it) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "信任等级管的是权限（能不能发链接、能不能建社团），跟成长等级无关。学习再多也不会直接拿到社区权限。",
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TrustConditionRow(condition: TrustConditionDto) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (condition.met) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (condition.met) "已达成" else "未达成",
            tint = if (condition.met) AppColors.Accent else AppColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(condition.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "${condition.current}/${condition.required}",
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RulesCard() {
    MiuixCard(insideMargin = PaddingValues(16.dp)) {
        Text("规则说明", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        listOf(
            "成长等级 Lv1–Lv8 只涨不跌，按累计经验计算，删除内容不会掉级。",
            "经验分学习和社区两条线，各自封顶。给出赞不涨经验（点赞是认同不是产出）。",
            "信任等级 TL0–TL3 可升可降，管的是社区权限；降级会发站内通知并记录原因。",
            "等级和成就都**不换任何权限**，权限只由信任等级决定。",
            "本App 内不设虚拟货币与打赏。",
        ).forEach {
            Text(
                "· $it",
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Lv 徽标。配色只随等级变，是装饰 —— 权限判定不在这一层（红线 1）。 */
@Composable
internal fun LevelBadge(level: Int, name: String, compact: Boolean = false) {
    val bg = levelBadgeColor(level)
    Box(
        Modifier.clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
            .background(bg)
            .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = if (compact) 2.dp else 4.dp),
    ) {
        Text(
            if (compact) "Lv$level" else "Lv$level · $name",
            color = Color.White,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun levelBadgeColor(level: Int): Color = when (Levels.accentIndex(level)) {
    4 -> Color(0xFFB4690E)
    3 -> Color(0xFF2563EB)
    2 -> Color(0xFF3F9E6B)
    else -> Color(0xFF6E7683)
}

@Composable
private fun TrustBadge(level: Int, name: String) {
    // TL 用与 Lv 不同的色系：Lv 是激励面（冷色→暖色），TL 是风控面（统一灰蓝），
    // 避免用户把「颜色更亮」误读成「权限更高」。
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF4A5568))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("TL$level · $name", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}