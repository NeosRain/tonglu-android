package cn.tonglu.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 自建网盘（OpenList / Alist v3 兼容 HTTP API）客户端。
 *
 * ## 为什么是「自建实例」这条路
 * 蓝奏云的密码登录已被阿里云 WAF 堵死（见 `docs/plan/30-NETDISK-LANZOU.md` §一），
 * 而 OpenList 是**唯一有官方 API** 的目标：不逆向、不模拟登录、不绕反爬。
 * 代价是每个想用的人得有一台自己的 OpenList（或 Alist）—— 所以入口做成
 * 「填服务器地址 + 账号」，谁有谁用，我们不经手任何文件。
 *
 * ## 隐私形态（和「文件互传」一致）
 * 地址、账号、口令、token **只存这台手机上**（EncryptedSharedPreferences），
 * 不上云、不导出、不进日志；文件从手机直连用户自己的服务器，不走我们的后端。
 *
 * ## 已实测的接口（2026-10-03，Pan 实例 v4.2.6）
 * | 动作 | 请求 |
 * |---|---|
 * | 登录 | `POST /api/auth/login` `{username,password}` → `data.token` |
 * | 列表 | `POST /api/fs/list` `{path,page,per_page,refresh}` → `data.content[]` |
 * | 建目录 | `POST /api/fs/mkdir` `{path}` |
 * | 上传 | `PUT /api/fs/put`，头 `File-Path: <URL 编码的完整路径>`，body 原始字节 |
 * | 直链 | `POST /api/fs/get` `{path}` → `data.raw_url` + `data.sign` |
 * | 删除 | `POST /api/fs/remove` `{dir,names[]}` |
 * 鉴权头是 `Authorization: <token>`（**不加 Bearer 前缀**）。
 * 普通账号（权限 1016，仅 WebDAV 读写）即可跑通全部链路，不需要管理员。
 */
class OpenListClient(
    /** 服务器地址，例如 `https://pan.newrain.top`（末尾斜杠会自动去掉） */
    baseUrl: String,
    private val token: String? = null,
) {
    val base: String = normalizeBase(baseUrl)

    private val http = sharedHttp

    // ---------------------------------------------------------------- 模型

    data class Entry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val modified: String?,
    )

    /**
     * 登录结果。
     *
     * `root` 是这个账号的根目录（`/api/me` 的 `base_path`）：OpenList 的 fs 接口路径是
     * **从存储根算起**的绝对路径，普通账号往往只在某个子目录里有意义 ——
     * 实测 `siyuan` 账号 `base_path=/siyuan`，`list("/")` 会列出 main/new/siyuan/test
     * 这些思源内部目录。所以浏览起点必须是 `base_path`，且不许往上走。
     */
    data class LoginResult(val token: String, val root: String)

    /** 登录失败、权限不足、过期等都抛这个，`message` 直接可以给用户看。 */
    class OpenListException(message: String, val unauthorized: Boolean = false) : IOException(message)

    // ---------------------------------------------------------------- 接口

    /**
     * 登录换 token，并顺带读回这个账号的根目录。
     *
     * 根目录取自 `/api/me` 的 `base_path`；这一步失败**不影响登录成功**，退回 `/`
     * （老版本 Alist 或后台限制了 me 接口时仍可用）。
     */
    fun login(username: String, password: String): LoginResult {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }
        val data = call("POST", "/api/auth/login", body, authed = false)
        val token = data?.str("token") ?: throw OpenListException("服务器没有返回登录凭据")
        val root = runCatching { call("GET", "/api/me", null, explicitToken = token)?.str("base_path") }
            .getOrNull()?.takeIf { it.startsWith("/") } ?: "/"
        return LoginResult(token, root)
    }

    fun list(path: String): List<Entry> {
        val body = JsonObject().apply {
            addProperty("path", normalizePath(path))
            addProperty("page", 1)
            addProperty("per_page", 0)
            addProperty("refresh", true)
        }
        val data = call("POST", "/api/fs/list", body) ?: return emptyList()
        // 空目录时服务器返回的是 `"content": null`（不是 `[]`，也不是省略键）。
        // **别用 `getAsJsonArray("content")`** —— Gson 那个重载是 `(JsonArray) members.get(name)`
        // 直接强转，遇到 JsonNull 会抛 ClassCastException（走查实测：
        // "com.google.gson.JsonNull cannot be cast to com.google.gson.JsonArray"，
        // 表现为「删掉最后一个条目后列表不刷新、界面飘一行 Java 异常」）。
        val content = data.get("content")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return content.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            Entry(
                name = obj.str("name") ?: return@mapNotNull null,
                isDir = obj.bool("is_dir"),
                size = obj.long("size"),
                modified = obj.str("modified"),
            )
        }
    }

    fun mkdir(path: String) {
        val body = JsonObject().apply { addProperty("path", normalizePath(path)) }
        call("POST", "/api/fs/mkdir", body)
    }

    /** 删除若干条目（`dir` 是所在目录，`names` 是条目名）。 */
    fun remove(dir: String, names: List<String>) {
        val body = JsonObject().apply {
            addProperty("dir", normalizePath(dir))
            add("names", Gson().toJsonTree(names))
        }
        call("POST", "/api/fs/remove", body)
    }

    /** 取直链（`raw_url` 拼上 `sign` 才是完整可访问地址）。`path` 是完整文件路径。 */
    fun directLink(path: String): String {
        val body = JsonObject().apply { addProperty("path", normalizePath(path)) }
        val data = call("POST", "/api/fs/get", body) ?: throw OpenListException("服务器没有返回文件信息")
        val raw = data.str("raw_url") ?: throw OpenListException("这个文件没有直链")
        val url = if (raw.startsWith("http")) raw else base + raw
        val sign = data.str("sign")
        if (sign.isNullOrBlank()) return url
        return url + (if (url.contains("?")) "&" else "?") + "sign=" + sign
    }

    /**
     * 上传。
     *
     * `onProgress(已写字节, 总字节)` 会在写流过程中被回调（总字节未知时为 -1）——
     * 大文件上传要让人看见在动，否则用户会以为卡死。
     */
    fun upload(
        dir: String,
        fileName: String,
        resolver: ContentResolver,
        uri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val path = join(dir, fileName)
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val body = UriRequestBody(resolver, uri, onProgress)
        val request = Request.Builder()
            .url(base + "/api/fs/put")
            .header("File-Path", encoded)
            .header("Authorization", requireToken())
            .put(body)
            .build()
        execute(request) { data -> data }  // put 成功时 data 为 null
    }

    // ---------------------------------------------------------------- 内部

    private fun requireToken(explicit: String? = null): String =
        (explicit ?: token)?.takeIf { it.isNotBlank() }
            ?: throw OpenListException("请先登录", unauthorized = true)

    /**
     * 统一发 JSON 请求并拆 `{code,message,data}` 信封；`data` 可能是 null。
     *
     * `authed = false` 只用于登录；`explicitToken` 用于「登录时顺手读 /api/me」——
     * 那会儿实例上的 token 还是空的。
     */
    private fun call(
        method: String,
        path: String,
        body: JsonObject?,
        authed: Boolean = true,
        explicitToken: String? = null,
    ): JsonObject? {
        // GET 不许带 body（OkHttp 会直接抛）；其余方法一律发 JSON。
        val payload = if (method == "GET" || method == "HEAD") {
            null
        } else {
            (body ?: JsonObject()).toString().toRequestBody(JSON)
        }
        val builder = Request.Builder().url(base + path).method(method, payload)
        if (authed) builder.header("Authorization", requireToken(explicitToken))
        return execute(builder.build()) { it }
    }

    private fun <T> execute(request: Request, pick: (JsonObject?) -> T): T {
        val response: Response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw OpenListException("连不上服务器，检查地址与网络：${e.message ?: "连接失败"}")
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw OpenListException("登录已过期，请重新登录", unauthorized = true)
            if (resp.code == 403) throw OpenListException("这个账号没有该目录的权限")
            if (resp.code == 404) throw OpenListException("地址不对：服务器上没有这个接口（确认是 OpenList/Alist 实例）")
            if (resp.code !in 200..299) {
                throw OpenListException("服务器返回 ${resp.code}：${text.take(120).ifBlank { "无内容" }}")
            }
            val root = runCatching { JsonParser.parseString(text) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw OpenListException("服务器返回的不是 JSON，可能地址填成了网页首页")
            val code = root.int("code") ?: 200
            if (code != 200) {
                val message = root.str("message") ?: "操作失败（$code）"
                throw OpenListException(message, unauthorized = code == 401)
            }
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            return pick(data)
        }
    }

    /** 从 `content://` 直接流式上传，不用先把文件整个读进内存。 */
    private class UriRequestBody(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = OCTET

        override fun contentLength(): Long =
            runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
                .getOrDefault(-1L)

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            val input = resolver.openInputStream(uri) ?: throw OpenListException("读不到所选文件")
            input.use { stream ->
                stream.source().use { source ->
                    var written = 0L
                    val buffer = okio.Buffer()
                    while (true) {
                        val read = source.read(buffer, 64 * 1024)
                        if (read == -1L) break
                        sink.write(buffer, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        /** 上传要给足写超时（几十 MB 的文件在同一 WiFi 下也可能要一会儿）。 */
        private val sharedHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        /** 用户可能只填 `pan.newrain.top`；补上 https://，去掉末尾斜杠。 */
        fun normalizeBase(raw: String): String {
            var v = raw.trim().trimEnd('/')
            if (v.isEmpty()) throw OpenListException("请填写服务器地址")
            if (!v.startsWith("http://") && !v.startsWith("https://")) {
                v = if (v.startsWith("localhost") || v.startsWith("127.0.0.1") || v.startsWith("192.168.")) {
                    "http://$v"
                } else {
                    "https://$v"
                }
            }
            return v
        }

        /** 服务器要的路径以 `/` 开头、不以 `/` 结尾（根目录就是 `/`）。 */
        fun normalizePath(path: String): String {
            val v = path.trim()
            if (v.isEmpty() || v == "/") return "/"
            return "/" + v.trim('/')
        }

        /**
         * 把目录与文件名拼成完整路径。
         *
         * 别写成 `normalizePath(dir) + "/" + name` —— 根目录会得到 `//name`。
         */
        fun join(dir: String, name: String): String {
            val d = normalizePath(dir)
            return if (d == "/") "/$name" else "$d/$name"
        }

        /** 进入子目录 / 回到上级。根目录时返回 null（不许再往上）。 */
        fun parentOf(path: String): String? {
            val v = normalizePath(path)
            if (v == "/") return null
            val cut = v.lastIndexOf('/')
            return if (cut <= 0) "/" else v.substring(0, cut)
        }

        fun humanSize(bytes: Long): String = when {
            bytes < 0 -> ""
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }
}

/**
 * 网盘凭据存储。
 *
 * **只在本机**：用与登录会话同一套 EncryptedSharedPreferences（独立文件名），
 * 明文既不进 APK 也不进任何文档/日志。退出登录 = 清空这个文件。
 */
class OpenListStore(context: Context) {
    private val preferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "tonglu_openlist",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    data class Account(val base: String, val username: String, val token: String, val root: String)

    fun read(): Account? {
        val base = preferences.getString("base", null)?.takeIf { it.isNotBlank() } ?: return null
        val token = preferences.getString("token", null)?.takeIf { it.isNotBlank() } ?: return null
        val root = preferences.getString("root", null)?.takeIf { it.startsWith("/") } ?: "/"
        return Account(base, preferences.getString("username", "").orEmpty(), token, root)
    }

    /** 记住地址与账号名，方便下次直接登录（口令不留，token 是登录后才存）。 */
    fun rememberAddress(base: String, username: String) {
        preferences.edit().putString("base", base).putString("username", username).apply()
    }

    fun saveSession(base: String, username: String, token: String, root: String) {
        preferences.edit()
            .putString("base", base)
            .putString("username", username)
            .putString("token", token)
            .putString("root", root)
            .apply()
    }

    /** 只清 token（地址与账号名留着，免得重新登录还要重敲一遍）。 */
    fun clear() {
        preferences.edit().remove("token").remove("root").apply()
    }
}

/**
 * Gson 的 `JsonNull.asString` 会抛 `UnsupportedOperationException`，而 OpenList 对空字段
 * 常常**返回 `null` 而不是省略键**（例如空目录里条目的 `modified`、空目录的 `content`）。
 * 所以读字段一律走这几个「不是原生类型就当没有」的助手 —— 别直接 `.asString` / `.asInt`，
 * 那会在一个空字段上把整次调用打崩。**也别用 `getAsJsonArray(key)`**：那个重载是直接强转
 * （`members.get(key) as JsonArray`），JsonNull 会抛 ClassCastException。
 */
private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

private fun JsonObject.long(key: String): Long =
    runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L }.getOrDefault(0L)

private fun JsonObject.int(key: String): Int? =
    runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()
