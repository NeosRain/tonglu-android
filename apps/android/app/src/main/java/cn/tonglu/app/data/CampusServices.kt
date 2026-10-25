package cn.tonglu.app.data

/**
 * 首页入口的分区。
 *
 * 之前 `CampusService` 只有 `tag`（"常用"/"生活"/"学习"…），那是给展示用的散文标签，
 * 拿它当分类用有两个后果：一是「文件互传」这种**应用内的工具**和「生活缴费」这种**校外服务**
 * 都被塞进了「校园网站」里，二是 UI 只能靠 `id` 硬编码猜分组（改一个 id 就静默归错类）。
 * 用户明确要求首页不能全部算校园服务 —— 所以分类改成数据自带的枚举，UI 只负责按 group 渲染。
 */
enum class ServiceGroup(val title: String, val hint: String) {
    /** 应用内的通用能力，和学校网站无关（局域网互传、以后的距离传递/网盘） */
    TOOL("常用工具", "应用自带的工具，不经学校服务器"),
    /** 校外生活服务（缴费；以后跑腿、丹东点评） */
    LIFE("生活服务", "校外服务，按官方页面提示操作"),
    /** 学校网址与业务系统 */
    CAMPUS("校园入口", "学校网址与业务系统，部分需要登录"),
}

/** 校园服务入口：学校官网导航或用户提供的业务地址；登录要求以来源页面为准。 */
data class CampusService(
    val id: String, val name: String, val desc: String, val url: String?, val tag: String,
    val desktopMode: Boolean = false, val defaultVisible: Boolean = true,
    val externalAppPackage: String? = null,
    /** 分区。默认归到校园入口，工具/生活服务必须显式指定，避免"漏标就混进学校网站"。 */
    val group: ServiceGroup = ServiceGroup.CAMPUS,
)

object CampusServices {
    const val PORTAL_URL = "https://bsdt.ldxy.cn/bsdt/home"
    const val JW_URL = "https://jw.liaodongu.edu.cn/jwapp/sys/homeapp/home/index.html?contextPath=/jwapp#/"
    const val SC_URL = "https://sc.liaodongu.edu.cn/"
    // 高校公开的跨校课平台名单：辽东学院超星单位码 16044。
    const val CHAOXING_URL = "https://lnldxy.fanya.chaoxing.com/"
    const val ELECTRICITY_PAYMENT_URL = "https://enjoy.abchina.com/jf-openweb/wechat/shareEpayItem?code=JF-EPAY2023031041857"
    val all = listOf(
        CampusService("localsend", "文件互传", "LocalSend 内置 · 局域网直传", null, "工具", group = ServiceGroup.TOOL),
        // 网盘走「用户自建 OpenList」这条路：我们不经手文件，所以描述里必须把这点说出来，
        // 否则用户会以为这是我们提供的公共网盘（蓝奏云那条路已被 WAF 堵死，见 docs/plan/30）。
        CampusService("netdisk", "网盘", "连你自己的 OpenList · 文件不经我们服务器", null, "工具", group = ServiceGroup.TOOL),
        CampusService("jw", "教务系统", "导入课表和成绩、选课", JW_URL, "常用", true),
        CampusService("chaoxing", "学习通", "辽东学院超星教学平台 · 自行登录", CHAOXING_URL, "学习"),
        CampusService(
            "payment", "生活缴费", "农行智能控电缴费 · 国内网络", ELECTRICITY_PAYMENT_URL, "生活",
            externalAppPackage = AgriculturalBankPayment.PACKAGE, group = ServiceGroup.LIFE,
        ),
        CampusService("lib", "图书馆", "图书馆官网与资源导航", "https://ldxytsg.mh.chaoxing.com", "学习"),
        CampusService("teaching", "教育教学", "教务通知与教学信息", "https://liaodongjwc.mh.chaoxing.com", "学习"),
        CampusService("sc", "双创平台", "项目申报、竞赛与学分认定", SC_URL, "竞赛", true),
        CampusService("career", "就业网", "招聘信息与就业服务", "https://ldxy.bysjy.com.cn/", "就业"),
        CampusService("logistics", "后勤服务", "后勤通知与服务信息", "https://www.liaodongu.edu.cn/houqin", "服务"),
        CampusService("school", "学校官网", "学校通知与院系导航", "https://www.liaodongu.edu.cn/", "官网"),
        CampusService("admissions", "招生网", "招生信息与专业介绍", "https://www.liaodongu.edu.cn/zhaosheng", "招生", defaultVisible = false),
        CampusService("xg", "学工事务", "在办事大厅查找相关服务", null, "服务", true, false),
        CampusService("lab", "实验室服务", "在办事大厅查找相关服务", null, "服务", true, false),
        CampusService("psy", "心理咨询", "在办事大厅查找相关服务", null, "服务", true, false),
    )
    val portal = CampusService("portal", "数字服务平台", "学校办事大厅", PORTAL_URL, "总入口", true)
    fun byId(id: String) = (all + portal).firstOrNull { it.id == id }

    /**
     * 某个分区下**可配置显隐**的入口（不含 portal）。
     *
     * portal 是办事大厅总入口，属于 CAMPUS 但不参与显隐开关 ——
     * 用户把校园入口全关了也应该留一个能进办事大厅的入口，所以它由 UI 单独追加。
     */
    fun inGroup(group: ServiceGroup) = all.filter { it.group == group }

    /** 首页用：分区里最终要渲染的条目（CAMPUS 末尾补上办事大厅）。 */
    fun entriesInGroup(group: ServiceGroup, visible: (CampusService) -> Boolean): List<CampusService> =
        inGroup(group).filter(visible).let { if (group == ServiceGroup.CAMPUS) it + portal else it }

    fun shareText() = buildString {
        appendLine("辽东学院常用网站")
        (listOf(portal) + all.filter { it.url != null }).forEach { appendLine("${it.name}：${it.url}") }
        appendLine("部分服务需要登录，按官方页面提示操作。")
    }
}
