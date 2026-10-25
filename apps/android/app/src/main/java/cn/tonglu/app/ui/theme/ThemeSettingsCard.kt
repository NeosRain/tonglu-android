package cn.tonglu.app.ui.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import kotlin.math.roundToInt

/**
 * 个人页「外观」卡里的主题设置。
 *
 * 设计口径（用户 2026-10-18：「像酷安/B站那样可选主题色」「颜色可调」「模糊壁纸可调」）：
 * - 预设色是**一排圆形色块 + 选中勾**，点一下全局立即生效并持久化；
 * - 自定义色用 HSV 三根滑杆，实际应用色会自动夹到白字 ≥4.5:1 的亮度区间，
 *   预览里同时显示「所选」和「实际应用」，用户能看出安全校正做了什么；
 * - 壁纸有缩略图、模糊/压暗滑杆、更换/移除，全部只存本机；
 * - 顶部预览面板把当前主题色 + 当前壁纸（含模糊/压暗）合成一张小卡，改哪个都实时变。
 */
@Composable
internal fun ThemeSettingsCard() {
    val context = LocalContext.current
    val dark = AppTheme.darkState
    var customOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            notice = null
            ThemeWallpaper.save(context, uri)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("主题设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "主题色、模糊壁纸与暗化程度，改完立即生效 · 只保存在本机",
            color = AppColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        WallpaperPreviewCard(onExpand = { previewOpen = true })

        // ---------------------------------------------------------------- 主题色
        Text(
            "主题色",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 14.dp),
        )
        val selectedArgb = ThemeAccent.rawArgb
        val effectiveSelected = selectedArgb ?: themeAccentPresets.first().argb
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            themeAccentPresets.forEach { preset ->
                val applied = ThemeAccent.safeAccent(Color(preset.argb), dark)
                val selected = effectiveSelected == preset.argb
                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(applied)
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) AppColors.TextPrimary else AppColors.Divider,
                                shape = CircleShape,
                            )
                            .clickable {
                                ThemeAccent.select(context, Color(preset.argb))
                                notice = "已应用「${preset.label}」"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Text("✓", color = AppColors.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        preset.label,
                        color = if (selected) AppColors.TextPrimary else AppColors.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { customOpen = !customOpen }) {
                Text(if (customOpen) "收起自定义颜色" else "自定义颜色", color = AppColors.Accent)
            }
            TextButton(onClick = {
                ThemeAccent.clear(context)
                notice = "已恢复默认品牌蓝"
            }) { Text("恢复默认蓝", color = AppColors.TextSecondary) }
        }

        if (customOpen) CustomAccentEditor(dark = dark, notice = { notice = it })

        // ---------------------------------------------------------------- 壁纸
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = AppColors.Divider)
        Spacer(Modifier.height(12.dp))
        Text("背景壁纸", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "从相册选图，全 App 显示为模糊背景；大图会先缩小，图片只存本机、不上传",
            color = AppColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 96.dp, height = 64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.ChipIdle),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = ThemeWallpaper.sourceImage
                if (ThemeWallpaper.active && thumb != null) {
                    Image(thumb, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Text("未设置", color = AppColors.TextMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    TextButton(onClick = { picker.launch("image/*") }) {
                        Text(if (ThemeWallpaper.active) "更换壁纸" else "选择壁纸", color = AppColors.Accent)
                    }
                    if (ThemeWallpaper.active) {
                        TextButton(onClick = {
                            notice = null
                            ThemeWallpaper.remove(context)
                        }) {
                            Text("移除", color = AppColors.Danger)
                        }
                    }
                }
                Text(
                    if (ThemeWallpaper.active) {
                        "模糊 ${(ThemeWallpaper.blur * 100).roundToInt()}% · 压暗 ${(ThemeWallpaper.dim * 100).roundToInt()}% · 透光 ${(ThemeWallpaper.glass * 100).roundToInt()}%"
                    } else "支持 JPG / PNG / WebP",
                    color = AppColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (ThemeWallpaper.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (ThemeWallpaper.active) {
            WallpaperAdjustmentControls(Modifier.padding(top = 12.dp))
            Text(
                "往左更实、往右更透；可在全屏预览里边看边调，返回后保留设置。",
                color = AppColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        (ThemeWallpaper.error ?: notice)?.let { message ->
            Text(
                message,
                color = if (ThemeWallpaper.error != null) AppColors.Danger else AppColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (previewOpen) WallpaperPreviewDialog(onClose = { previewOpen = false })

}

@Composable
private fun CustomAccentEditor(dark: Boolean, notice: (String) -> Unit) {
    val context = LocalContext.current
    val base = ThemeAccent.rawArgb?.let { Color(it) } ?: AppColors.Accent
    val initial = ThemeAccent.hsvOf(base)
    var hue by remember { mutableFloatStateOf(initial.hue) }
    var saturation by remember { mutableFloatStateOf(initial.saturation) }
    var value by remember { mutableFloatStateOf(initial.value.coerceIn(0.04f, 1f)) }

    // 点预设 / 恢复默认后滑杆要跟上；拖动滑杆自己写的值与这里一致，不会来回跳。
    LaunchedEffect(ThemeAccent.rawArgb, dark) {
        val color = ThemeAccent.rawArgb?.let { Color(it) } ?: AppColors.Accent
        val hsv = ThemeAccent.hsvOf(color)
        hue = hsv.hue
        saturation = hsv.saturation
        value = hsv.value.coerceIn(0.04f, 1f)
    }

    fun apply(newHue: Float = hue, newSaturation: Float = saturation, newValue: Float = value) {
        val safeHue = newHue.coerceIn(0f, 360f)
        val safeSaturation = newSaturation.coerceIn(0f, 1f)
        val color = Color.hsv(safeHue, safeSaturation, newValue.coerceIn(0.04f, 1f))
        ThemeAccent.select(context, color, immediate = false)
    }

    val applied = ThemeAccent.safeAccent(Color.hsv(hue, saturation, value), dark)
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(color = Color.hsv(hue, saturation, value), label = "所选")
            Spacer(Modifier.width(10.dp))
            Swatch(color = applied, label = "实际应用")
            Spacer(Modifier.width(10.dp))
            Text(
                "#${applied.toHex()}",
                color = AppColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "亮度会自动校正：白字按钮 ≥4.5:1，深色主题下强调色当文字也 ≥3.0:1",
            color = AppColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        HueTrack()
        Text("色相 ${hue.roundToInt()}°", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = hue,
            onValueChange = { hue = it; apply(newHue = it) },
            valueRange = 0f..360f,
            colors = accentSliderColors(),
        )
        Text("饱和度 ${(saturation * 100).roundToInt()}%", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = saturation,
            onValueChange = { saturation = it; apply(newSaturation = it) },
            valueRange = 0f..1f,
            colors = accentSliderColors(),
        )
        Text("明度 ${(value * 100).roundToInt()}%", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = { value = it; apply(newValue = it) },
            valueRange = 0.04f..1f,
            colors = accentSliderColors(),
        )
    }
}

@Composable
private fun Swatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(color).border(1.dp, AppColors.Divider, CircleShape))
        Text(label, color = AppColors.TextMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun HueTrack() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
                    ),
                ),
            ),
    )
}

@Composable
private fun accentSliderColors() = SliderDefaults.colors(
    thumbColor = AppColors.Accent,
    activeTrackColor = AppColors.Accent,
    inactiveTrackColor = AppColors.ChipIdle,
)

private fun Color.toHex(): String =
    "%02X%02X%02X".format((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
