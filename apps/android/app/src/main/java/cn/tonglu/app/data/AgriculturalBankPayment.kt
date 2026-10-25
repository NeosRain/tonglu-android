package cn.tonglu.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 只解析公开缴费入口的导航信息，不携带应用登录凭据、银行 Cookie 或付款信息。 */
object AgriculturalBankPayment {
    const val PACKAGE = "com.android.bankabc"
    const val DOWNLOAD_URL = "https://www.abchina.com/cn/download/"
    private const val ITEM_CODE = "JF-EPAY2023031041857"
    private const val INFO_URL = "https://wx.abchina.com/webank/main/openLabelUrl/getOpenLabelUrlInfoById"
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 每次读取农行当前返回的 App 链接，避免写死带加密参数的临时跳转地址。 */
    suspend fun resolve(): Uri = withContext(Dispatchers.IO) {
        val landing = client.newCall(Request.Builder().url(CampusServices.ELECTRICITY_PAYMENT_URL).build()).execute().use { response ->
            if (response.code in 300..399) {
                response.header("Location") ?: error("农行缴费入口暂不可用")
            } else {
                check(response.isSuccessful) { "农行缴费入口暂不可用" }
                val html = response.body?.string().orEmpty()
                Regex("window\\.location\\.replace\\(['\"]([^'\"]+)['\"]\\)")
                    .find(html)?.groupValues?.get(1) ?: error("农行缴费入口已更新，请稍后重试")
            }
        }.toHttpUrlOrNull() ?: error("农行缴费链接无效")
        check(landing.scheme == "https" && landing.host == "wx.abchina.com" && landing.encodedPath == "/webank/main-view/openTagForId") { "农行缴费入口已更新，请稍后重试" }
        val body = JSONObject().apply {
            listOf("id", "dynamicData", "displayParam", "type", "extendType", "extendParam").forEach { key ->
                put(key, landing.queryParameter(key).orEmpty())
            }
        }
        check(body.getString("id").isNotBlank()) { "农行缴费入口暂不可用" }
        val request = Request.Builder().url(INFO_URL)
            .header("Referer", landing.toString()).header("Origin", "https://wx.abchina.com")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val result = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "农行缴费入口暂不可用" }
            val info = JSONObject(response.body?.string().orEmpty())
            check(info.optString("status") == "0000") { "农行缴费入口暂不可用" }
            info.getJSONObject("result")
        }
        val display = result.optString("displayUrl").toHttpUrlOrNull()
        check(display?.scheme == "https" && display.host == "enjoy.abchina.com" && display.queryParameter("code") == ITEM_CODE) { "缴费项目信息不匹配，请稍后重试" }
        val appUrl = result.getString("appUrl")
        check(appUrl.startsWith("bankabc://")) { "农行 App 跳转入口已更新，请稍后重试" }
        val navigation = JSONObject(Uri.decode(appUrl.removePrefix("bankabc://")))
        check(navigation.optString("method") == "jumpToSharedProduct" && navigation.optString("type") == "1" && navigation.optString("param").isNotBlank()) { "农行 App 跳转入口已更新，请稍后重试" }
        Uri.parse(appUrl)
    }
}
