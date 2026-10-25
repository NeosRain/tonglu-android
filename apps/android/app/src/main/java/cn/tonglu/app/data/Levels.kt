package cn.tonglu.app.data

/**
 * 成长等级与信任等级的**客户端镜像**（依据 `docs/plan/66-USER-LEVELS-AND-ACHIEVEMENTS.md` v0.2.1）。
 *
 * 🔴 为什么客户端要存一份：等级徽标要在帖子作者行、他人主页、自己的等级页多处渲染，
 * 每次都等服务端拿会把「渲染一个 Lv 标签」变成网络请求。阈值表与后端
 * `backend/src/levels/levels.rules.ts` 一一对应 —— **改一处必须同步另一处**，
 * 两边的 `LevelsRulesContractTest` 会校验这份一致性说明。
 *
 * 🔴 **权限只由 TL 给，Lv 不换任何权限**（plan/66 §十一·红线 1）。
 * 客户端不参与任何权限判定：它只能提示「你不能发」，真拦截在后端。
 */
object Levels {
    /** Lv1–Lv8 累计 EXP 阈值。索引 0 对应 Lv1。 */
    val THRESHOLDS = intArrayOf(0, 50, 150, 400, 900, 1800, 3500, 6000)

    /** 校园语境命名；Lv8 用产品工作代号「同路人」收口。 */
    val NAMES = arrayOf("新生", "同窗", "行者", "学长", "领航", "栋梁", "传灯", "同路人")

    /** 信任等级名。**只在本人视角展示**，永不公开（红线 8：TL 是风控面不是激励面）。 */
    val TRUST_NAMES = arrayOf("观察中", "同窗", "骨干", "主理")

    val MAX_LEVEL = THRESHOLDS.size

    /** 等级徽标的语义色：低等级用中性色，高等级用强调色（避免"数字大=颜色闹"的反向激励）。 */
    fun accentIndex(level: Int): Int = when {
        level >= 7 -> 4
        level >= 5 -> 3
        level >= 3 -> 2
        else -> 1
    }

    fun name(level: Int): String = NAMES[(level - 1).coerceIn(0, NAMES.size - 1)]

    fun trustName(level: Int): String = TRUST_NAMES[level.coerceIn(0, TRUST_NAMES.size - 1)]

    /** 客户端按阈值算等级（与服务端 `levelFromExp` 同口径）。**只用于乐观显示**，以服务端返回为准。 */
    fun levelFromExp(expTotal: Int): Int {
        if (expTotal <= 0) return 1
        var level = 1
        for (i in THRESHOLDS.indices) if (expTotal >= THRESHOLDS[i]) level = i + 1
        return level
    }

    /** 距下一级的 EXP。已达最高级或超量时返回 0，**永不为负**（plan/66 §2.2 显示规则）。 */
    fun expToNext(expTotal: Int): Int {
        val level = levelFromExp(expTotal)
        if (level >= MAX_LEVEL) return 0
        return maxOf(0, THRESHOLDS[level] - expTotal)
    }

    /** 当前级段进度 0f..1f。 */
    fun progress(expTotal: Int): Float {
        val level = levelFromExp(expTotal)
        if (level >= MAX_LEVEL) return 1f
        val base = THRESHOLDS[level - 1]
        val next = THRESHOLDS[level]
        if (next <= base) return 0f
        return ((expTotal - base).toFloat() / (next - base)).coerceIn(0f, 1f)
    }

    /**
     * 这次答题算不算「首次学会这个词」（成长等级学习线 `words.learned`，plan/66 §2.3）。
     *
     * 🔴 为什么不能只看 `correct`：复习答对会的也是 correct，但那个词早就学会了。
     * 拿 correct 计数 = 把「背了 200 遍的同一个词」算成 200 个词，日上限也拦不住语义错误。
     * 判据是「这次对了 且 此前 level 还是 0 且 一次都没答过」——`level == 0` 表示还没记住，
     * `attempts == 0` 表示这是头一回见（曾经答错过的词不算「新学会」，它在复习）。
     *
     * 抽成纯函数是为了能被单测固定住（红线：「修法写进代码」≠「被固定住」）。
     */
    fun isFirstLearned(correct: Boolean, prevLevel: Int, prevAttempts: Int): Boolean =
        correct && prevLevel == 0 && prevAttempts == 0
}