package cn.tonglu.app.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.CampusZone
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.themeColor

/**
 * 校园地图：内置学校官网 2026-09-20 发布的三张官方平面图（临江 / 金山北苑 / 金山小南苑，
 * 见 [CampusZone]），捏合缩放（上限按图的像素算，见下面注释）+ 拖动 + 双击复位。
 * 没有自绘底图、没有第三方地图服务 —— 学校官方发布的平面图就是唯一权威来源，原样展示。
 */
@Composable
internal fun CampusMapScreen(
    onClose: () -> Unit,
    /** 打开外部网页：一律交回宿主的 App 内浏览器（CampusBrowserScreen）。 */
    onOpenUrl: (url: String, title: String) -> Unit = { _, _ -> },
) {
    var zoneIndex by remember { mutableIntStateOf(0) }
    val zone = CampusZone.entries[zoneIndex]
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().background(AppColors.Canvas).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().background(AppColors.SurfaceOpaque).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = themeColor(Color(0xFF1F2937), AppColors.TextPrimary))
            }
            Text(
                "校园地图",
                color = themeColor(Color(0xFF1F2937), AppColors.TextPrimary),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            SourcePageButton(zone, onOpenUrl)
        }

        // 校区切换：三个 tab（临江 / 金山北苑 / 金山小南苑）。
        // 「来源：学校官网」这行小字去掉了 —— 三个 tab 之后横着已经放不下，
        // 而且底部说明行本来就是干这个的，同一句话说两遍没有意义。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CampusZone.entries.forEachIndexed { index, z ->
                val selected = index == zoneIndex
                Surface(
                    color = if (selected) AppColors.Accent else themeColor(Color(0xFFEBEDF1), AppColors.ChipIdle),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            zoneIndex = index
                        },
                ) {
                    Text(
                        z.label,
                        color = if (selected) AppColors.OnAccent else themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // 官方示意图：捏合缩放 + 拖动 + 双击复位
        val bitmap = remember(zone) {
            try { context.assets.open(zone.assetPath).use { BitmapFactory.decodeStream(it) } }
            catch (e: java.io.IOException) { null }
        }
        if (bitmap == null) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("开源版不附带学校地图图片，请点击右上角原页查看学校官网。", color = AppColors.TextPrimary)
            }
            return@Column
        }
        var scale by remember(zone) { mutableFloatStateOf(1f) }
        var offset by remember(zone) { mutableStateOf(Offset.Zero) }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(themeColor(Color(0xFFEBEDF1), AppColors.ChipIdle))
                .pointerInput(zone) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // 🔴 缩放上限是**按图算的**，不是拍脑袋的 8 倍：内置图是 2400px 宽，
                        // 放大超过「1:1 像素」（≈ 2400 / 屏宽 ≈ 1.7 倍）只会糊，
                        // 上限给到 3 倍留一点余量。换更大/更小的图时这里要跟着改
                        // （更好的做法是读 bitmap.width，但那要把它跨出 remember —— 先不做）。
                        scale = (scale * zoom).coerceIn(1f, 3f)
                        // 位移钳在「图的真实显示范围」内：先算 ContentScale.Fit 之后的显示尺寸，
                        // 再钳 (显示尺寸 - 盒子尺寸)/2。之前的写法钳的是「盒子宽度×scale/2」，
                        // 竖直方向完全没钳住，图会被拖出视野。
                        val boxW = size.width.toFloat()
                        val boxH = size.height.toFloat()
                        val fit = minOf(boxW / bitmap.width, boxH / bitmap.height)
                        val maxX = ((bitmap.width * fit * scale - boxW) / 2f).coerceAtLeast(0f)
                        val maxY = ((bitmap.height * fit * scale - boxH) / 2f).coerceAtLeast(0f)
                        offset = if (scale > 1f) {
                            Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        } else {
                            Offset.Zero
                        }
                    }
                }
                .pointerInput(zone) {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "辽东学院${zone.label}校园示意图（学校官网发布）",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
            )
            if (scale > 1.02f) {
                Surface(
                    color = themeColor(Color(0xCCFFFFFF), Color(0xCC2A2F38)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            scale = 1f
                            offset = Offset.Zero
                        },
                ) {
                    Text(
                        "复位",
                        color = themeColor(Color(0xFF1F2937), Color(0xFFE8EAED)),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
        // 出处说明：来源页 / 发布日期逐项来自 [CampusZone]（与 assets/map/PROVENANCE.json 一致）。
        // 🔴 出处与许可是两件事，这里只写「从哪来的」；「能怎么用」在 PROVENANCE.json 的 rights 里。
        Text(
            "图片来源：辽东学院官网《辽东学院各校区平面图》（${zone.publishedAt} 发布），版权归学校所有；" +
                "楼栋名称以图内图例与学校最新通知为准。",
            color = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 顶栏右侧的「原页」入口：打开官网发布页（App 内浏览器）。 */
@Composable
private fun SourcePageButton(zone: CampusZone, onOpen: (String, String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onOpen(zone.sourceUrl, "学校官网 · ${zone.label}示意图") }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.OpenInBrowser,
            contentDescription = null,
            tint = AppColors.Accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("原页", color = AppColors.Accent, fontSize = 13.sp)
    }
}
