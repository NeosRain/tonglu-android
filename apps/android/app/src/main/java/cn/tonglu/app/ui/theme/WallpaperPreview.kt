package cn.tonglu.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.ThemeMode
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/** Both sizes use the real wallpaper host and the same Miuix card defaults as the app. */
@Composable
internal fun WallpaperPreviewCard(onExpand: () -> Unit, title: String = "实时预览") {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = AppColors.TextSecondary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onExpand) { Text("全屏预览") }
        }
        val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
        ThemeWallpaperHost(
            Modifier.fillMaxWidth().height((224 * fontScale).dp).clip(RoundedCornerShape(18.dp)),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("示例 · 效果随设置同步", color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelSmall)
                PreviewContentCard(compact = true)
            }
        }
    }
}

/** Real settings, not a second set of preview-only values. */
@Composable
internal fun WallpaperAdjustmentControls(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth()) {
        WallpaperSlider("模糊程度", ThemeWallpaper.blur) { ThemeWallpaper.setBlur(context, it) }
        WallpaperSlider("暗化程度", ThemeWallpaper.dim) { ThemeWallpaper.setDim(context, it) }
        WallpaperSlider("卡片透光", ThemeWallpaper.glass) { ThemeWallpaper.setGlass(context, it) }
        if (ThemeWallpaper.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WallpaperSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}%", color = AppColors.TextPrimary,
            style = MaterialTheme.typography.labelMedium)
    }
    Slider(value = value, onValueChange = onChange, valueRange = 0f..1f,
        colors = SliderDefaults.colors(thumbColor = AppColors.Accent, activeTrackColor = AppColors.Accent,
            inactiveTrackColor = AppColors.ChipIdle))
}

@Composable
internal fun WallpaperPreviewDialog(onClose: () -> Unit) {
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    val context = LocalContext.current
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ThemeWallpaperHost(Modifier.fillMaxSize().overlayMotion(motion, fade = false)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Surface(color = AppColors.SurfaceOpaque) {
                    Column {
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { motion.requestClose() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回外观设置")
                        }
                        Column(Modifier.weight(1f)) {
                            Text("壁纸效果预览", color = AppColors.TextPrimary,
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("示例页面 · 设置会立即应用", color = AppColors.TextSecondary,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(selected = AppTheme.modeState == mode,
                                onClick = { AppTheme.setMode(context, mode) },
                                label = { Text(when (mode) {
                                    ThemeMode.System -> "跟随系统"
                                    ThemeMode.Light -> "浅色"
                                    ThemeMode.Dark -> "深色"
                                }) }, modifier = Modifier.weight(1f))
                        }
                    }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("同路", color = AppColors.TextPrimary, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Text("课表、成绩与学习，清楚地放在一起", color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    item { PreviewContentCard(compact = false) }

                }
                Surface(color = AppColors.SurfaceOpaque, shadowElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 288.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)) {
                        if (ThemeWallpaper.active) {
                            WallpaperAdjustmentControls()
                        } else {
                            Text("尚未设置壁纸，可返回外观设置选择图片。", color = AppColors.TextSecondary)
                        }
                        ThemeWallpaper.error?.let { Text(it, color = AppColors.Danger,
                            style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewContentCard(compact: Boolean) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(if (compact) 12.dp else 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 10.dp)) {
            Text("今天的安排 · 示例", color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
            Text("查看课程安排，留意今天的学习进度。", color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 2 else Int.MAX_VALUE)
            Text("辅助说明 · 壁纸、主题色和卡片透光会同步更新", color = AppColors.TextMuted,
                style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(AppColors.Accent)
                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("示例按钮", color = AppColors.OnAccent, style = MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(AppColors.ChipIdle)
                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("示例标签", color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
            LinearProgressIndicator(progress = { 0.65f }, modifier = Modifier.fillMaxWidth(),
                color = AppColors.Accent, trackColor = AppColors.ChipIdle)
        }
    }
}
