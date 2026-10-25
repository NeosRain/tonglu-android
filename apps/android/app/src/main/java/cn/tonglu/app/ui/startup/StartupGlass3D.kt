package cn.tonglu.app.ui.startup

import kotlin.math.cos
import kotlin.math.sin

/**
 * 开屏玻璃砖的针孔相机：把视图空间的 (x, y, z) 投到屏幕像素。
 *
 * 为什么值得单独有一层：用户要的是「3D 的方块玻璃」，而"3D 感"必须来自**真的透视**，
 * 不能靠画两层描边假装。视图空间以**屏幕中心为原点、y 向下、z 越大越远**，
 * 焦距越短透视越强；旋转矩阵由 yaw/pitch/roll 三个角乘出来（Rz·Rx·Ry，行主序）。
 *
 * 纯数学，不碰 Android/Compose 绘制，便于单独核对。
 */
internal class GlassCamera(
    private val cx: Float,
    private val cy: Float,
    private val focal: Float,
    private val m: FloatArray,
) {
    /**
     * 投影一个视图空间点到屏幕像素，写进 [out] 的 at / at+1。
     *
     * 分母下限 [focal] * .35：镜头**在砖体前面**时 f + vz 会趋近 0，尺度会炸成天文数字；
     * 本效果里 z 只有 ±几十 dp，取不到这个下限，但留一道闸总比留一个 NaN 好。
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray, at: Int) {
        val vx = m[0] * x + m[1] * y + m[2] * z
        val vy = m[3] * x + m[4] * y + m[5] * z
        val vz = m[6] * x + m[7] * y + m[8] * z
        val s = focal / (focal + vz).coerceAtLeast(focal * .35f)
        out[at] = cx + vx * s
        out[at + 1] = cy + vy * s
    }

    /** 视图空间深度（越大越远）。画家算法按它排序，近的砖压在远的上面。 */
    fun depth(x: Float, y: Float, z: Float): Float = m[6] * x + m[7] * y + m[8] * z

    /**
     * 砖体（+z 方向，即砖的厚度）在屏幕上的方向。相机一有俯仰/偏航，
     * 所有砖的厚度就**朝同一个屏幕方向**长（本效果里是右下），这也是
     * 「受光面在上左、厚度与暗面在下右」这套光照能自洽的原因。
     */
    fun bodyDirection(out: FloatArray) {
        out[0] = m[2]
        out[1] = m[5]
    }
}

/**
 * 建相机。[focalScale] 是焦距相对屏宽的倍数：太大退化成正投影（格子全是正方形，看不出立体），
 * 太小边角拉伸成鱼眼。本效果取 2.6 —— 上下排约 5% 的尺度差，够读出深度又不至于变形。
 */
internal fun glassCamera(
    width: Float,
    height: Float,
    yaw: Float,
    pitch: Float,
    roll: Float,
    focalScale: Float,
): GlassCamera {
    val cy = cos(yaw)
    val sy = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)
    val cr = cos(roll)
    val sr = sin(roll)
    return GlassCamera(
        cx = width * .5f,
        cy = height * .5f,
        focal = width * focalScale,
        m = floatArrayOf(
            // R = Rz(roll) · Rx(pitch) · Ry(yaw)
            cr * cy - sr * sp * sy, -sr * cp, cr * sy + sr * sp * cy,
            sr * cy + cr * sp * sy, cr * cp, sr * sy - cr * sp * cy,
            -cp * sy, sp, cp * cy,
        ),
    )
}
