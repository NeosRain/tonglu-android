package cn.tonglu.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.CampusServices
import cn.tonglu.app.data.CampusService
import cn.tonglu.app.data.ServiceGroup
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/**
 * 首页的入口区：**按 [ServiceGroup] 分成三块独立卡片**（常用工具 / 生活服务 / 校园入口）。
 *
 * 之前是一张「校园服务 · 数字平台」大卡把所有东西装在一起 —— 于是「文件互传」和「生活缴费」
 * 也被当成学校网站了。用户明确说：文件互传是通用工具、缴费是生活服务，都不属于校园网站。
 * 现在分类来自数据（`CampusService.group`），这里只按分组渲染，不再猜。
 *
 * 显隐偏好沿用原来的 `campus_entries` 表（key 就是 service id），
 * 老用户关掉过的入口升级后**不会自己冒出来**；新工具（localsend / payment）
 * 走 `defaultVisible` 的默认值，第一次见到就是可见。
 */
@Composable
internal fun CampusServicesSections(
    onImport: () -> Unit,
    onOpenService: (CampusService) -> Unit,
    openingServiceId: String? = null,
) {
    val prefs = LocalContext.current.getSharedPreferences("campus_entries", Context.MODE_PRIVATE)
    var visibility by remember {
        mutableStateOf(CampusServices.all.associate { it.id to prefs.getBoolean(it.id, it.defaultVisible) })
    }
    var managing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        ServiceGroup.entries.forEach { group ->
            val entries = CampusServices.entriesInGroup(group) { visibility[it.id] == true }
            // 工具/生活服务被用户全关掉时整块消失；校园入口永远有「办事大厅」兜底，不会空。
            if (entries.isEmpty()) return@forEach

            Text(
                group.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 8.dp),
            )
            MiuixCard(insideMargin = PaddingValues(0.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    entries.forEach { service ->
                        ServiceEntryRow(
                            service = service,
                            opening = service.id == openingServiceId,
                            onClick = { if (service.id == "jw") onImport() else onOpenService(service) },
                        )
                    }
                    // 管理入口只放一次，挂在最后一块（校园入口，条目最多的那块）的卡内底部。
                    if (group == ServiceGroup.CAMPUS) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                group.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { managing = true }) { Text("管理入口") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (managing) AlertDialog(
        onDismissRequest = { managing = false },
        title = { Text("显示的入口") },
        text = {
            LazyColumn {
                ServiceGroup.entries.forEach { group ->
                    item(key = "head:${group.name}") {
                        Text(
                            group.title,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    items(CampusServices.inGroup(group), key = { it.id }) { service ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(service.name, Modifier.weight(1f))
                            Switch(
                                checked = visibility[service.id] == true,
                                onCheckedChange = { checked ->
                                    // 改一次就落盘：对话框可能被系统直接销毁，攒着会丢
                                    prefs.edit().putBoolean(service.id, checked).apply()
                                    visibility = visibility + (service.id to checked)
                                },
                            )
                        }
                    }
                }
                item(key = "note") {
                    Text(
                        "办事大厅是固定入口，不参与上面的开关。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { managing = false }) { Text("完成") } },
    )
}

/** 入口行：彩色圆角图标 + 名称/说明 + 右侧动作词。色彩与间距沿用原来的紧凑方案。 */
@Composable
private fun ServiceEntryRow(service: CampusService, opening: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = !opening,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val tint = campusServiceTint(service.id)
        Surface(
            color = tint.copy(alpha = 0.13f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(38.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(campusServiceIcon(service.id), contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(service.name, modifier = Modifier.fillMaxWidth())
            Text(
                service.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            when {
                opening -> "打开中…"
                service.id == "jw" -> "导入"
                service.id == "payment" -> "前往农行"
                else -> "打开"
            },
        )
    }
}

private fun campusServiceIcon(id: String): ImageVector = when (id) {
    "localsend" -> Icons.Outlined.SwapHoriz
    "netdisk" -> Icons.Outlined.Cloud
    "portal" -> Icons.Outlined.Apps
    "jw", "teaching", "chaoxing" -> Icons.Outlined.School
    "lib" -> Icons.Outlined.Book
    "sc" -> Icons.Outlined.Flag
    "payment" -> Icons.Outlined.Bolt
    "career" -> Icons.Outlined.WorkOutline
    "logistics" -> Icons.Outlined.Build
    "school" -> Icons.Outlined.Language
    "admissions" -> Icons.Outlined.HowToReg
    "xg" -> Icons.Outlined.Groups
    "lab" -> Icons.Outlined.Science
    "psy" -> Icons.Outlined.FavoriteBorder
    else -> Icons.Outlined.Article
}

private fun campusServiceTint(id: String): Color = when (id) {
    "localsend" -> Color(0xFF0C8599)
    "netdisk" -> Color(0xFF1971C2)
    "portal" -> Color(0xFF2563EB)
    "jw" -> Color(0xFF2563EB)
    "lib" -> Color(0xFF6741D9)
    "teaching", "chaoxing" -> Color(0xFF00897B)
    "sc" -> Color(0xFFD9480F)
    "payment" -> Color(0xFF00897B)
    "career" -> Color(0xFF1976A3)
    "logistics" -> Color(0xFFC58019)
    "school" -> Color(0xFF2563EB)
    "admissions" -> Color(0xFF8B5AC1)
    "xg" -> Color(0xFF099268)
    "lab" -> Color(0xFF0C8599)
    "psy" -> Color(0xFFA61E4D)
    else -> Color(0xFF2563EB)
}
