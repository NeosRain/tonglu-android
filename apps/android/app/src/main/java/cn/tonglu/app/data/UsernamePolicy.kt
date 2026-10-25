package cn.tonglu.app.data

/**
 * 用户名规范的 **Android 镜像** —— 唯一真源在 `backend/src/auth/username-policy.ts`。
 *
 * ## 为什么客户端要抄一份
 * 用户每敲一个字符就发一次网络请求是不可接受的（延迟 + 额度），所以「格式对不对」
 * 必须能离线判。而「抄一份」最大的风险是**抄歪**：客户端放行、服务端拒绝，
 * 用户就会看到「输入框是绿的、点了提交却报错」这种说不清楚的错。
 * 因此两边的一致性不靠人眼保证，靠 `scripts/audit_auth_policy.py` 逐项比对
 * （长度、字符集、保留前缀、保留字、结论码、文案），任一项对不上就让检查变红。
 *
 * 🔴 改这个文件时**必须同步改后端**，否则 CI（或走查前的手动检查）会拦住。
 * 中文字面量必须与后端逐字一致，包括标点 —— 比对脚本是按字符串比，不是按语义比。
 */
object UsernamePolicy {

    const val MIN = 3
    const val MAX = 24

    /** 归一化之后允许的字符集（小写） */
    val ALLOWED = Regex("^[a-z0-9_]+$")

    /** 用户实际敲进来的允许写法（归一化之前，允许大写） */
    val ALLOWED_INPUT = Regex("^[A-Za-z0-9_]+$")

    /** 历史自动派生 ID 专用前缀，新用户不许占用（否则会抢注老用户的派生 ID） */
    val RESERVED_PREFIXES = listOf("u_")

    /** 与后端 USERNAME_RESERVED 逐项一致 */
    val RESERVED: Set<String> = setOf(
        // ① 官方与系统身份
        "tonglu", "tongluapp", "official", "admin", "administrator", "root", "system",
        "support", "help", "service", "staff", "moderator", "mod", "operator", "owner",
        "security", "privacy", "legal", "billing", "payment",
        // ② 路由与技术词
        "api", "www", "app", "web", "mail", "ftp", "cdn", "static", "assets",
        "login", "logout", "register", "signup", "signin", "auth", "oauth", "token",
        "me", "user", "users", "account", "accounts", "profile", "settings", "config",
        "null", "undefined", "true", "false", "nan", "none", "anonymous", "guest",
        // ③ 通用角色词
        "student", "teacher", "school", "campus", "club", "clubs", "class", "grade",
    )

    // —— 结论码：与后端 USERNAME_CODES 的取值一致 ——
    const val CODE_OK = "USERNAME_OK"
    const val CODE_TOO_SHORT = "USERNAME_TOO_SHORT"
    const val CODE_TOO_LONG = "USERNAME_TOO_LONG"
    const val CODE_BAD_CHARS = "USERNAME_BAD_CHARS"
    const val CODE_RESERVED = "USERNAME_RESERVED"
    const val CODE_TAKEN = "USERNAME_TAKEN"

    // —— 文案：与后端 USERNAME_MESSAGES 逐字一致 ——
    const val MSG_OK = "用户名可用"
    const val MSG_TOO_SHORT = "用户名至少 3 个字符"
    const val MSG_TOO_LONG = "用户名最多 24 个字符"
    const val MSG_BAD_CHARS = "用户名只能包含字母、数字和下划线"
    const val MSG_RESERVED = "该用户名为系统保留，请换一个"
    const val MSG_TAKEN = "该用户名已被使用，请换一个"

    // —— 登录入口文案：与后端 LOGIN_MESSAGES 逐字一致 ——
    const val LOGIN_MSG_EMPTY = "请输入用户名或邮箱"
    const val LOGIN_MSG_HAS_SPACE = "登录名不能包含空格"
    const val LOGIN_MSG_TOO_LONG = "登录名过长"
    const val LOGIN_MSG_BAD_EMAIL = "邮箱格式不正确"
    /** 与 MSG_BAD_CHARS 同值，但**必须单独存在**：契约比对脚本按「登录码 → 文案」1:1 比对，
     *  复用同一个常量会让这一项从比对清单里消失，等于少了一道闸。 */
    const val LOGIN_MSG_BAD_CHARS = "用户名只能包含字母、数字和下划线"
    const val LOGIN_MSG_BAD_CREDENTIALS = "用户名或密码错误"

    /** 与后端 normalizeUsername 一致：去空白 + 转小写。请求前必须先过。 */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /**
     * 纯规则校验（**不发网络**）。返回结论码。
     * 「是否被占用」拿不到 —— 那要网络，做成另一个函数。
     */
    fun checkFormat(raw: String): String {
        val name = normalize(raw)
        if (name.length < MIN) return CODE_TOO_SHORT
        if (name.length > MAX) return CODE_TOO_LONG
        if (!ALLOWED.matches(name)) return CODE_BAD_CHARS
        if (RESERVED_PREFIXES.any { name.startsWith(it) }) return CODE_RESERVED
        if (RESERVED.contains(name)) return CODE_RESERVED
        return CODE_OK
    }

    fun messageFor(code: String): String = when (code) {
        CODE_OK -> MSG_OK
        CODE_TOO_SHORT -> MSG_TOO_SHORT
        CODE_TOO_LONG -> MSG_TOO_LONG
        CODE_BAD_CHARS -> MSG_BAD_CHARS
        CODE_RESERVED -> MSG_RESERVED
        CODE_TAKEN -> MSG_TAKEN
        else -> MSG_BAD_CHARS
    }

    /**
     * 登录标识的格式校验。返回 null 表示「可以往下走」。
     *
     * 🔴 这里**只判字符串长得对不对**，绝不判断账号是否存在 ——
     * 一旦本地/服务端能区分「账号不存在」和「密码错误」，防枚举就白做了。
     * 与后端 checkLoginIdentifier 保持同一套判据。
     */
    fun checkLoginIdentifier(raw: String): String? {
        val id = raw.trim()
        if (id.isEmpty()) return LOGIN_MSG_EMPTY
        if (id.any { it.isWhitespace() }) return LOGIN_MSG_HAS_SPACE
        if (id.length > 254) return LOGIN_MSG_TOO_LONG
        if (id.contains('@')) {
            // 与后端 @IsEmail 同口径：本地部分 + @ + 带点的域名
            val ok = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(id)
            return if (ok) null else LOGIN_MSG_BAD_EMAIL
        }
        return if (ALLOWED_INPUT.matches(id)) null else LOGIN_MSG_BAD_CHARS
    }
}
