package cn.tonglu.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 一言（hitokoto.cn）的一条句子 */
data class HitokotoQuote(
    val text: String,
    val from: String? = null,
    val fromWho: String? = null,
) {
    /** 署名行：「罗曼·罗兰 · 《约翰·克利斯朵夫》」 */
    fun credit(): String? = when {
        !fromWho.isNullOrBlank() && !from.isNullOrBlank() -> "$fromWho · $from"
        !from.isNullOrBlank() -> from
        !fromWho.isNullOrBlank() -> fromWho
        else -> null
    }
}

/**
 * 一言 API 客户端（https://hitokoto.cn，开放、免费、无需 key）。
 *
 * 分类参数 c：a 动画 / b 漫画 / c 游戏 / d 文学 / e 原创 / i 诗词 / k 哲学。
 * 这里挑的是「激励 + 二次元」那一挂，太丧的类别（l 抖机灵、j 网易云热评）不取。
 */
object Hitokoto {

    private const val ENDPOINT =
        "https://v1.hitokoto.cn/?encode=json&charset=utf-8&c=a&c=b&c=c&c=d&c=i&c=k"

    private val gson = Gson()
    private const val HOUR_MILLIS = 60L * 60 * 1000

    /** 拉一条；失败（没网、超时、接口抽风）返回 null，交给调用方保留旧文案 */
    suspend fun fetch(): HitokotoQuote? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "TongluApp (Android)")
            }
            try {
                if (connection.responseCode != 200) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val node = gson.fromJson(body, JsonObject::class.java) ?: return@runCatching null
                val text = node.str("hitokoto")
                if (text.isNullOrBlank()) null
                else HitokotoQuote(
                    text = text,
                    from = node.str("from"),
                    fromWho = node.str("from_who"),
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** 距离下一个整点还有多少毫秒（多留 800ms，别卡在整点边界上） */
    fun millisToNextHour(now: Long = System.currentTimeMillis()): Long =
        HOUR_MILLIS - (now % HOUR_MILLIS) + 800L

    /** 当前小时的标记，用来判断缓存是不是「本小时的」 */
    fun hourKey(now: Long = System.currentTimeMillis()): String = (now / HOUR_MILLIS).toString()

    private fun JsonObject.str(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        val value = runCatching { element.asString }.getOrNull()
        return value?.takeIf { it.isNotBlank() && it != "null" }
    }
}

/** 一言的本地缓存：没网、或者整点那一刻正好断网，也不至于空着 */
class HitokotoStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("tonglu_hitokoto", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): HitokotoQuote? = runCatching {
        val raw = preferences.getString("quote", null) ?: return null
        gson.fromJson(raw, HitokotoQuote::class.java)
    }.getOrNull()

    fun save(quote: HitokotoQuote) {
        preferences.edit()
            .putString("quote", gson.toJson(quote))
            .putString("hour", Hitokoto.hourKey())
            .apply()
    }

    /** 缓存的这条是不是本小时拉的（不是就该换） */
    fun cachedThisHour(): Boolean = preferences.getString("hour", null) == Hitokoto.hourKey()
}
