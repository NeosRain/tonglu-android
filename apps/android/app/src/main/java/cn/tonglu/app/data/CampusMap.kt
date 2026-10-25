package cn.tonglu.app.data

/**
 * 校园地图（2026-10-10 用户点名要上线；2026-10-11 槽位 A 完成换源）。
 *
 * 数据来源：**辽东学院官网《辽东学院各校区平面图》（2026-09-20 发布）**，
 * 三张官方插画式平面图（临江 / 金山北苑 / 金山小南苑，原尺寸 4961×3508）。
 * 图片已按等比降采样（宽 2400）+ WebP 重编码内置进 APK 的 `assets/map/`，
 * **离线可用**，不依赖学校服务器可用性。
 *
 * 为什么不画交互底图 / 不接地图服务：我们没有校内建筑的准确坐标，自绘「看起来像真的」
 * 交互地图就是编数据（红线：示例/不实数据必须打标，而打了标的假数据对学生没用）；
 * 高德/百度没有校内楼栋级数据且用户点名不要。学校官方发布的平面图就是唯一权威来源。
 *
 * 🔴 出处与许可**分开登记**（红线：不拼成一坨字符串、绝不从 source 里抠许可）：
 * 每张图的来源页 / 发布日期 / 权利说明 / sha256 / 字节数逐项写在
 * `assets/map/PROVENANCE.json`（生成脚本 `work/tmp/map26/build_assets.py`，
 * 里面同时登记了降采样这一步本身 —— 「仅重编码、未裁剪、未改动图内内容」要如实说）。
 */
enum class CampusZone(
    /** 界面上显示的短名（tab 用，不能太长）。 */
    val label: String,
    /** 图内自带的完整标题（图中上方就印着「辽东学院××平面图」）。 */
    val title: String,
    /** assets 里的图片路径。 */
    val assetPath: String,
    /** 官方发布页（供「来源」说明与「原页」入口）。 */
    val sourceUrl: String,
    /** 学校发布日期（来源页标题行，逐张核对过）。 */
    val publishedAt: String,
) {
    LINJIANG(
        "临江校区",
        "辽东学院临江校区平面图",
        "map/linjiang.webp",
        "https://www.liaodongu.edu.cn/info/1475/96603.htm",
        "2026-09-20",
    ),
    JINSHAN_BEIYUAN(
        "金山 · 北苑",
        "辽东学院金山校区北苑平面图",
        "map/jinshan-beiyuan.webp",
        "https://www.liaodongu.edu.cn/info/1475/96603.htm",
        "2026-09-20",
    ),
    JINSHAN_XIAONANYUAN(
        "金山 · 小南苑",
        "辽东学院金山校区小南苑平面图",
        "map/jinshan-xiaonanyuan.webp",
        "https://www.liaodongu.edu.cn/info/1475/96603.htm",
        "2026-09-20",
    ),
}
