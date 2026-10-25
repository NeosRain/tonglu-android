package cn.tonglu.app.data

data class SchoolEntry(val name: String, val url: String, val guide: String, val source: String)

/** 官方入口目录，不等同于已通过各校账号的课表解析认证。核对日期 2026-10-23。 */
object SchoolDirectory {
    val liaoning = listOf(
        SchoolEntry("东北大学", "https://aao.neu.edu.cn/", "从教务处学生服务进入新版教务；需校园网，校外按学校要求使用官方 VPN。", "https://aao.neu.edu.cn/2026/0227/c9434a450413/page.htm"),
        SchoolEntry("大连理工大学", "https://jxyxbzzx.dlut.edu.cn/", "从页面顶部「本科生教学管理系统」进入。", "https://jxyxbzzx.dlut.edu.cn/"),
        SchoolEntry("大连海事大学", "https://jwc.dlmu.edu.cn/", "从「师生服务 → 教务系统」进入。", "https://jwc.dlmu.edu.cn/"),
        SchoolEntry("辽宁大学", "https://jwc.lnu.edu.cn/", "从学校教务处查找教务入口；不能读取时使用课表文件导入。", "https://jwc.lnu.edu.cn/"),
        SchoolEntry("辽宁师范大学", "https://jwc.lnnu.edu.cn/", "从「学生专区 → 新版教务系统」进入。", "https://jwc.lnnu.edu.cn/"),
        SchoolEntry("大连海洋大学", "https://portal.dlou.edu.cn/auth/cas/login", "登录学校统一认证门户，再进入本科生教务系统。", "https://hhxy.dlou.edu.cn/bksjwxt/list.htm"),
    )

    fun search(query: String): List<SchoolEntry> = liaoning.filter { it.name.contains(query.trim(), ignoreCase = true) }
}
