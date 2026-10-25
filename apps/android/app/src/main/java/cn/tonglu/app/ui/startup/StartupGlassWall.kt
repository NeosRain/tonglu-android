package cn.tonglu.app.ui.startup

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 薄玻璃方格：默认使用程序化底光，波峰按真实透视抬升。
 * 后半段相机平滑归正，最终为正面平铺，保留细薄边缘。
 * 数值为待设备观感验收的候选，不以几何计算替代实际预览。
 */
/** 把 [pts] 里从 at 开始的 4 个点连成一个闭合四边形（顺序：左上、右上、右下、左下）。 */
private fun quadPath(target: Path, pts: FloatArray, at: Int) {
    target.rewind()
    target.moveTo(pts[at], pts[at + 1])
    for (k in 1 until 4) target.lineTo(pts[at + k * 2], pts[at + k * 2 + 1])
    target.close()
}

internal fun DrawScope.drawStartupGlassWall(
    progress: Float,
    columns: Int,
    rows: Int,
    canvas: Color,
    ink: Color,
    accent: Color,
) {
    val w = size.width
    val h = size.height
    val count = (columns + 2) * (rows + 2)
    if (w <= 0f || h <= 0f || columns <= 0 || rows <= 0) return

    // 底色：竖向品牌渐变 + 中心径向光晕。玻璃是半透的，背后得有东西可透才不成一块死板。
    drawRect(Brush.verticalGradient(listOf(canvas, accent.copy(alpha = .18f), canvas)))
    drawRect(
        Brush.radialGradient(
            listOf(accent.copy(alpha = .16f), Color.Transparent),
            center = Offset(w * .5f, h * .34f),
            radius = w * .85f,
        ),
    )

    val cell = w / columns
    // 波浪后半段同时消除透视和全局倾斜，避免终态仍像斜铺的砖墙。
    val settleT = ((progress - .45f) / .23f).coerceIn(0f, 1f)
    val perspective = 1f - settleT * settleT * (3f - 2f * settleT)
    val cam = glassCamera(w, h, yaw = .055f * perspective, pitch = -.035f * perspective,
        roll = -.004f * perspective, focalScale = 3.8f)
    val inset = .65.dp.toPx()
    val body = 3.dp.toPx()
    val lift = 18.dp.toPx()
    val tilt = .028f          // 波峰处只保留轻微滚转，避免翻板感
    val shadowSpread = 4.dp.toPx()
    val bodyDir = FloatArray(2)
    cam.bodyDirection(bodyDir)

    val front = FloatArray(count * 8)
    val back = FloatArray(count * 8)
    val shadow = FloatArray(count * 8)
    val order = IntArray(count)
    val strength = FloatArray(count)
    val depth = FloatArray(count)
    val cx4 = FloatArray(4)
    val cy4 = FloatArray(4)
    val path = Path()      // 前脸
    val slab = Path()      // 砖体 / 投影的四边形
    val cut = Path()       // 砖体减掉前脸之后剩下的一圈

    // 🔴 多铺一圈（每边一格）：有透视之后上排会**整体向内缩**，不铺这一圈，屏幕
    // 左上/右上会露出没有砖的三角（实测顶点被拉到 (34,60) 附近）——墙就不像墙了。
    for (row in -1 until rows + 1) {
        for (col in -1 until columns + 1) {
            val i = (row + 1) * (columns + 2) + (col + 1)
            val wave = startupWave(progress, (col + .5f) / columns, (row + .5f) / rows)
            val s = abs(wave)
            strength[i] = s
            val z = -wave * lift                    // 波峰把砖推向镜头（z 越小越近）
            val ang = wave * tilt
            val ca = cos(ang)
            val sa = sin(ang)
            val ccx = -w * .5f + (col + .5f) * cell
            val ccy = -h * .5f + (row + .5f) * cell
            cx4[0] = -w * .5f + col * cell + inset
            cy4[0] = -h * .5f + row * cell + inset
            cx4[1] = -w * .5f + (col + 1) * cell - inset
            cy4[1] = cy4[0]
            cx4[2] = cx4[1]
            cy4[2] = -h * .5f + (row + 1) * cell - inset
            cx4[3] = cx4[0]
            cy4[3] = cy4[2]
            for (k in 0 until 4) {
                // 砖绕自身中心滚转（视图空间绕 z 轴），波峰处最明显。
                val dx = cx4[k] - ccx
                val dy = cy4[k] - ccy
                val rx = ccx + dx * ca - dy * sa
                val ry = ccy + dx * sa + dy * ca
                val at = i * 8 + k * 2
                cam.project(rx, ry, z, front, at)
                cam.project(rx, ry, z + body, back, at)
                // 软影：随波峰沿"砖厚方向"拉长，砖离开墙面时最深。
                val spread = body + s * shadowSpread
                shadow[at] = front[at] + bodyDir[0] * spread + 2f
                shadow[at + 1] = front[at + 1] + bodyDir[1] * spread + 3f
            }
            depth[i] = cam.depth(ccx, ccy, z)
            order[i] = i
        }
    }

    // 画家算法：远的先画，近的压在上面（正投影下顺序无所谓，有了 z 就必须排）。
    for (a in 1 until count) {
        val key = order[a]
        val kd = depth[key]
        var b = a - 1
        while (b >= 0 && depth[order[b]] < kd) {
            order[b + 1] = order[b]
            b--
        }
        order[b + 1] = key
    }

    for (n in 0 until count) {
        val i = order[n]
        val s = strength[i]
        val at = i * 8
        val lit = Color.White.copy(alpha = (.075f + s * .16f).coerceAtMost(.25f))
        val shade = ink.copy(alpha = .018f + s * .025f)

        quadPath(path, front, at)
        // 🔴 砖体与投影**必须先减掉前脸**再画。
        // 前脸是半透的（alpha .08~.66，玻璃要有东西可透），如果先画一整块深色砖体、
        // 再把半透前脸盖上去，那层深色会从脸下面透出来 —— 实测把浅色主题整格亮度
        // 从预测的 ~220 压到 187，整屏发灰发闷。减掉之后露出的才是"侧面"那一条。
        quadPath(slab, shadow, at)
        cut.rewind()
        cut.op(slab, path, PathOperation.Difference)
        drawPath(cut, ink.copy(alpha = .015f + s * .05f))

        quadPath(slab, back, at)
        cut.rewind()
        cut.op(slab, path, PathOperation.Difference)
        drawPath(
            cut,
            Brush.linearGradient(
                listOf(ink.copy(alpha = .10f + s * .06f), ink.copy(alpha = .025f + s * .035f)),
                Offset(back[at], back[at + 1]), Offset(back[at + 4], back[at + 5]),
            ),
        )

        val near = Offset(front[at], front[at + 1])
        val far = Offset(front[at + 4], front[at + 5])
        drawPath(path, Brush.linearGradient(listOf(lit, shade), near, far))
        if (s > .02f) drawPath(path, accent.copy(alpha = s * .12f))
        drawPath(
            path,
            brush = Brush.linearGradient(
                listOf(Color.White.copy(alpha = .28f + s * .24f), Color.White.copy(alpha = .025f)),
                near, far,
            ),
            style = Stroke((.55f + s * .45f).dp.toPx()),
        )
    }
}
