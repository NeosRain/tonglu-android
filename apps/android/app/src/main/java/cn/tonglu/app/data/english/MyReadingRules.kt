package cn.tonglu.app.data.english

/**
 * 「我的精读文章」的纯规则（JVM 可直测，不碰 Room）。
 *
 * 校验与标签归一从 `EnglishRepository.addMyArticle` 抽出来，是为了：
 * - 表单侧（按钮禁用）和仓储侧（保存前的 require）**用同一份判据**，
 *   两处各写一份迟早漂移 —— 表单放行了仓储拦下，用户看到的是一句莫名其妙的报错；
 * - 这类「必填 + 归一」的规则必须有单测钉着（同 [TodayQueueRules] 的纪律）。
 *
 * 🔴 来源与版权**必填**：这是用户点名的要求（「一定要备注文章来源和版权信息，别忘了」）。
 * 与词库「出处/许可分别登记」同一口径 —— 没有出处的内容连本机都不留。
 */
object MyReadingRules {

    /**
     * 校验四项必填。返回第一条错误的人类可读文案；`null` = 全部通过。
     * 顺序就是表单字段顺序：从上往下报第一条错的，用户改起来最顺手。
     */
    fun validate(title: String, body: String, sourceNote: String, licenseNote: String): String? = when {
        title.isBlank() -> "标题不能为空"
        body.isBlank() -> "正文不能为空"
        sourceNote.isBlank() -> "来源不能为空（如：2024年6月四级真题 卷二 阅读B）"
        licenseNote.isBlank() -> "版权信息不能为空（如：个人学习使用，来源：纸质试卷）"
        else -> null
    }

    /**
     * 标签归一：任意分隔（竖线 / 逗号 / 顿号 / 空格）都收，去空去重，竖线拼回。
     * `"2024-06，四级 卷二"` → `"2024-06|四级|卷二"`。
     *
     * 🔴 用竖线（`|`）做存储分隔符：标签本身可能含空格（`2024-06` 没问题，但
     * `卷 二` 这种用户手滑也不罕见），空格不能当分隔符存。
     */
    fun normalizeTags(raw: String): String = raw
        .split("|", "，", ",", "、", " ")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("|")

    /** 表单侧判「能否保存」：与 [validate] 同一份判据，别在 UI 再抄一份。 */
    fun canSave(title: String, body: String, sourceNote: String, licenseNote: String): Boolean =
        validate(title, body, sourceNote, licenseNote) == null
}
