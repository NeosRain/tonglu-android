package cn.tonglu.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.ParsedCourse
import cn.tonglu.app.data.ScheduleParsers
import cn.tonglu.app.data.ScheduleText
import com.google.gson.JsonParser
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 教务课表导入页（v2：直达企业微信扫码 + 全自动导入）。
 *
 * 关键设计（2026-09-29 实测确定）：
 *  · 教务（jw.liaodongu.edu.cn）按 UA 分流：手机 UA 只回「请从企业微信进入」；桌面 UA 才给登录页。
 *    而登录页唯一的方式就是**企业微信扫码**（页面里是 Tencent 的 wwLogin 二维码组件）。
 *    所以「直接做企业微信登录」= 跳过教务首页，直接打开统一身份认证的企业微信扫码页：
 *      https://sso.ldxy.cn/sso/cas3/APP028/login?service=<课表查询页>
 *    登录成功后 CAS 直接把我们送回课表查询页，一步到位。
 *  · 教务页面自带 `body{min-width:1366px}` 和 `maximum-scale=1.0;user-scalable=0` 的 viewport，
 *    这就是「电脑模式又大又缩不动」的根因。这里在页面加载后重写 viewport（width=1366 + 适配缩放
 *    + 允许缩放），并给登录页注入「只留二维码」的样式，让它在手机上是一张干净的小页面。
 *  · 二维码区域定时「截图」出来显示在原生面板里（PixelCopy），并提供「保存二维码到相册」，
 *    本机没有第二台设备时也能用企业微信「扫一扫 → 相册」完成登录。
 *  · 登录成功后全自动：跳课表查询 → 钩子抓接口 → 解析成功即自动保存并关闭导入页，用户零点击。
 */
// 桌面 UA / 域名 / 设计宽度 / 课表页地址 / 注入脚本 / 二维码截图 等公共件都在 JwWebSupport.kt，
// 与成绩导入（ScoreImportScreen）共用同一份，别再各写一遍。

/** 统一身份认证的企业微信扫码登录页（APP028 = 教务应用编号） */
private const val SSO_LOGIN_URL =
    "https://$SSO_HOST/sso/cas3/APP028/login?service=https%3A%2F%2Fjw.liaodongu.edu.cn%2Fjwapp%2Fsys%2Fkbapp%2F%2Adefault%2Findex.do"

private const val TAG = "TongluImport"

/** 粗筛：这些关键词出现才尝试解析（避免对每个静态资源都跑一遍解析器） */
private fun looksLikeTimetable(body: String): Boolean {
    if (body.length > 3_000_000) return false
    val keys = listOf(
        "kbList", "kb_list", "kcmc", "KCMC", "kbxx", "kbcx", "课表", "课程", "星期", "周次", "节次", "上课", "xqj", "XQJ",
        // 金智 kbapp 格式（辽东学院实锤）：字段名里没有中文，靠这些英文键识别
        "arrangedList", "getMyScheduleDetail", "beginSection", "courseName",
    )
    return keys.any { body.contains(it) }
}

// isJwUrl / isSsoUrl / findActivity 已移到 JwWebSupport.kt（jw* 前缀的是同一批工具）

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun LiaodongScheduleImportScreen(
    onClose: () -> Unit,
    onSave: (courses: List<ParsedCourse>, replace: Boolean, done: (Boolean, String) -> Unit) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val activity = jwFindActivity(LocalContext.current)
    val activityState = rememberUpdatedState(activity)
    val onSaveState = rememberUpdatedState(onSave)
    val onCloseState = rememberUpdatedState(onClose)

    // login：展示二维码等扫码 | fetching：已登录，正在抓课表 | done：抓到并已保存
    var stage by remember { mutableStateOf("login") }
    var status by remember { mutableStateOf("正在打开企业微信扫码登录…") }
    var expandedWeb by remember { mutableStateOf(false) }
    val saveQrToGallery = rememberQrGallerySaver { status = it }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var parsed by remember { mutableStateOf<List<ParsedCourse>>(emptyList()) }
    var parsedFormat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val logDir = remember { File(appContext.getExternalFilesDir(null), "jw").apply { mkdirs() } }
    fun logToFile(tag: String, url: String, body: String) {
        runCatching {
            val file = File(logDir, "capture.log")
            if (file.length() > 12_000_000) file.delete()
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file.appendText("\n===== $tag $time len=${body.length} url=$url\n")
            file.appendText(body.take(2_000_000))
            file.appendText("\n")
        }
    }

    // 供 bridge 调用的「保存」和「跳课表页」入口（每次重组刷新，避免闭包过期）
    val saveAction = remember { object { var run: () -> Unit = {} } }
    val goScheduleAction = remember { object { var run: () -> Unit = {} } }

    val bridge = remember {
        object {
            var best: List<ParsedCourse> = emptyList()
            var bestFormat = ""
            var respCount = 0
            var autoArmed = false
            var autoSaved = false
            var loginAtMs = 0L
            var lastCaptureAt = 0L
            var lastQrKey = ""
            var wentToSchedule = false
            var reloadTried = false
            var lastAcceptedAt = 0L

            /** 找到更优的一份课表就更新预览；满足条件时自动保存 */
            fun accept(result: ScheduleParsers.ParseResult) {
                android.util.Log.d(TAG, "accept size=" + result.courses.size + " best=" + best.size + " fmt=" + result.format)
                if (result.courses.isEmpty() || result.courses.size < best.size || result.courses == best) return
                best = result.courses
                lastAcceptedAt = System.currentTimeMillis()
                bestFormat = result.format
                val size = result.courses.size
                val message = result.message
                mainHandler.post {
                    parsed = best
                    parsedFormat = bestFormat
                    status = "✓ $message"
                }
                maybeAutoSave()
                mainHandler.postDelayed({ maybeAutoSave() }, 950)
            }

            fun maybeAutoSave() {
                android.util.Log.d(TAG, "maybeAutoSave armed=" + autoArmed + " saved=" + autoSaved + " n=" + best.size)
                if (!autoArmed || autoSaved || best.isEmpty()) return
                val elapsed = System.currentTimeMillis() - loginAtMs
                if (System.currentTimeMillis() - lastAcceptedAt < 900) return
                val metadata = cn.tonglu.app.data.ScheduleStore(appContext)
                // 给校历和作息响应留出时间，避免课表一返回就销毁 WebView、漏掉后续接口。
                if (elapsed < 8_000 && (metadata.termStart.isNullOrBlank() || metadata.periodTableSource != "教务自动导入")) return
                // 优先等完整的整学期课表（≥3 门）；10 秒还没等到就用现有的
                if (best.size < 3 && elapsed < 10_000) return
                autoSaved = true
                android.util.Log.d(TAG, "AUTO SAVE n=" + best.size)
                mainHandler.post { saveAction.run() }
            }

            @JavascriptInterface
            fun capture(requestUrl: String, body: String) {
                if (body.length < 30) return
                respCount++
                logToFile("接口", requestUrl, body)
                // 学校实际响应是 datas.getTermWeeks / datas.getMySectionList 嵌套数组。
                if (cn.tonglu.app.data.ScheduleMetadata.capture(appContext, requestUrl, body)) maybeAutoSave()
                if (!looksLikeTimetable(body)) return
                val result = runCatching { ScheduleParsers.parseAuto(body) }.getOrNull() ?: return
                accept(result)
            }

            /**
             * 页面探针（主框架里每 1.2 秒上报一次）：
             *  · href/host → 判断是否已登录（离开 sso.ldxy.cn、落到 jw.liaodongu.edu.cn 即成功）
             *  · qr → 二维码区域在可视口里的位置，用它从窗口里截出二维码
             */
            @JavascriptInterface
            fun probe(raw: String) {
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
                val href = json.optString("href", "")
                val host = json.optString("host", "")
                val viewportWidth = json.optDouble("vw", 0.0)
                val viewportHeight = json.optDouble("vh", 0.0)
                val qr = json.optJSONObject("qr")

                if (host.contains(SSO_HOST)) {
                    android.util.Log.d(TAG, "probe SSO vw=$viewportWidth vh=$viewportHeight qr=" + qr)
                    if (host.isNotBlank()) mainHandler.post { if (stage == "login") status = "请用手机「企业微信」扫下方二维码登录" }
                    if (viewportWidth > 0 && viewportHeight > 0) {
                        val key = "${qr?.optInt("x")}|${qr?.optInt("y")}|${qr?.optInt("w")}"
                        val now = System.currentTimeMillis()
                        if (key != lastQrKey || now - lastCaptureAt > 4000) {
                            lastQrKey = key
                            lastCaptureAt = now
                            mainHandler.post {
                                val view = webView ?: return@post
                                val act = activityState.value ?: return@post
                                jwCaptureLoginQr(act, view, json) { bitmap -> qrBitmap = bitmap }
                            }
                        }
                    }
                    return
                }

                // 离开 SSO = 登录成功
                if (host.contains(JW_HOST)) {
                    android.util.Log.d(TAG, "probe JW host=" + host + " href=" + href)
                    if (!autoArmed) {
                        autoArmed = true
                        loginAtMs = System.currentTimeMillis()
                        mainHandler.post {
                            stage = "fetching"
                            status = "扫码登录成功，正在加载课表，通常需要约 10 秒，请稍候…"
                        }
                        mainHandler.postDelayed({ maybeAutoSave() }, 1100)
                        mainHandler.postDelayed({ maybeAutoSave() }, 3000)
                        mainHandler.postDelayed({ maybeAutoSave() }, 6000)
                        mainHandler.postDelayed({ maybeAutoSave() }, 11_000)
                    }
                    if (!wentToSchedule && !href.contains("kbapp")) {
                        wentToSchedule = true
                        goScheduleAction.run()
                    }
                    // 兜底：进了课表页却一直没抓到（钩子注入晚于 SPA 请求）→ 重载一次
                    if (!reloadTried) {
                        reloadTried = true
                        mainHandler.postDelayed({
                            if (best.isEmpty() && !autoSaved) {
                                mainHandler.post { status = "重新加载课表页再抓一次…" }
                                webView?.reload()
                            }
                        }, 4500)
                    }
                }
            }

            @JavascriptInterface
            fun grab(payload: String) {
                logToFile("抓取", "all-frames", payload)
                var frameSummary = ""
                var bestLocal: ScheduleParsers.ParseResult? = null
                runCatching {
                    val root = JsonParser.parseString(payload).asJsonObject
                    val frames = root.getAsJsonArray("frames")
                    for (element in frames) {
                        val frame = element.asJsonObject
                        val frameUrl = frame.get("url")?.asString ?: ""
                        val html = frame.get("html")?.asString ?: ""
                        if (html.isBlank()) {
                            frameSummary += "\n· ${frameUrl.takeLast(50)}（无法读取）"
                            continue
                        }
                        val result = runCatching { ScheduleParsers.parseAuto(html) }.getOrNull()
                        frameSummary += "\n· ${frameUrl.takeLast(50)} ${html.length / 1024}KB → ${result?.courses?.size ?: 0}门"
                        if (result != null && result.courses.isNotEmpty() &&
                            (bestLocal == null || result.courses.size > bestLocal!!.courses.size)
                        ) {
                            bestLocal = result
                        }
                    }
                    val apiLog = root.getAsJsonArray("apiLog")
                    if (apiLog != null) {
                        for (element in apiLog) {
                            val entry = element.asJsonObject
                            val head = entry.get("h")?.asString ?: ""
                            if (head.isBlank() || !looksLikeTimetable(head)) continue
                            val result = runCatching { ScheduleParsers.parseAuto(head) }.getOrNull() ?: continue
                            if (result.courses.isNotEmpty() &&
                                (bestLocal == null || result.courses.size > bestLocal!!.courses.size)
                            ) {
                                bestLocal = result
                            }
                        }
                    }
                }
                val found = bestLocal
                mainHandler.post { busy = false }
                if (found != null && found.courses.isNotEmpty()) {
                    accept(found)
                } else {
                    mainHandler.post {
                        status = "抓到 ${frameSummary.count { it == '·' }} 个框架但没识别出课表：$frameSummary\n" +
                            "原始内容已存日志（files/jw/capture.log）"
                    }
                }
            }

            @JavascriptInterface
            fun note(message: String) {
                mainHandler.post { status = message }
            }
        }
    }

    // 保存 & 跳课表页的具体实现，绑定给 bridge
    saveAction.run = {
        android.util.Log.d(TAG, "saveAction n=" + parsed.size)
        if (!busy && parsed.isNotEmpty()) {
            busy = true
            onSaveState.value(parsed, true) { ok, message ->
                busy = false
                status = message
                if (ok) {
                    stage = "done"
                    mainHandler.postDelayed({ onCloseState.value() }, 600)
                } else {
                    bridge.autoSaved = false
                }
            }
        }
    }
    goScheduleAction.run = {
        android.util.Log.d(TAG, "goSchedule")
        webView?.loadUrl(SCHEDULE_PAGE)
    }

    // 从企业微信切回来时，重新布钩子 / 探针（后台期间定时器可能被挂起）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                webView?.evaluateJavascript(BOOTSTRAP_SCRIPT, null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val compactWeb = stage == "login" && !expandedWeb

    // 网络体检：VPN / 境外 IP 下教务根本打不开，以前只能干等白屏，现在明确告诉用户导不进来。
    val netCheck = rememberCampusNetworkCheck()

    Column(Modifier.fillMaxSize().background(AppColors.Canvas).safeDrawingPadding()) {
        CampusNetworkNotice(netCheck)
        // 同成绩导入：登录后的加载期报**真实**等待秒数，超过 45 秒换琥珀色 + 可操作提示。
        // 写死的「扫码后等待约 10 秒」在慢网络下会一直挂着不动，用户看不出是在跑还是卡死了。
        val loading = stage == "fetching"
        val waited = rememberElapsedSeconds(loading)
        val stalled = loading && waited >= 45
        Surface(color = if (stalled) noticeBackground(Color(0xFFFFF3E0)) else noticeBackground(Color(0xFFE8F0FE)), modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    stage == "login" -> "请使用企业微信扫码登录 · 不要使用应用账号登录教务"
                    stalled -> "还在获取课表（已等待 $waited 秒）。网络慢时更久；一直没动静就点「只看二维码」重新登录，或先用「手动添加」。"
                    loading -> "$status · 已等待 $waited 秒"
                    else -> status
                },
                color = if (stalled) noticeForeground(Color(0xFFB26A00)) else noticeForeground(Color(0xFF254E78)),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // ---------------- 顶栏 ----------------
        Row(
            Modifier.fillMaxWidth().background(AppColors.SurfaceOpaque).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text("企业微信扫码导入课表", fontWeight = FontWeight.SemiBold)
                Text(
                    status,
                    color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                )
            }
            Surface(
                color = when (stage) {
                    "login" -> noticeBackground(Color(0xFFFFF3E0))
                    "fetching" -> noticeBackground(Color(0xFFE8F0FE))
                    else -> noticeBackground(Color(0xFFE7F7EC))
                },
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    when (stage) {
                        "login" -> "待扫码"
                        "fetching" -> "抓取中"
                        else -> "已完成"
                    },
                    color = when (stage) {
                        "login" -> noticeForeground(Color(0xFFB26A00))
                        "fetching" -> noticeForeground(Color(0xFF1A56DB))
                        else -> noticeForeground(Color(0xFF12833B))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // ---------------- 网页区（登录页已收敛成只剩二维码的小页面） ----------------
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .then(if (compactWeb) Modifier.height(300.dp) else Modifier.weight(1f)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // 桌面 UA：教务只有给 PC 才渲染登录页（登录页本身即企业微信扫码）
                        settings.userAgentString = DESKTOP_UA
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        setInitialScale(0)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(bridge, "TongluBridge")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                qrBitmap = null
                                if (isJwUrl(url) && stage == "login") {
                                    stage = "fetching"
                                    status = "检测到已登录，正在自动获取课表…"
                                }
                            }

                            // 体检通过但页面照样打不开（中途断网、被拦）时，靠这条兜底把提示亮出来。
                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) netCheck.loadFailed = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (isSsoUrl(url)) {
                                    // SSO 登录页恒定收敛成「只留二维码」：原始页是全屏视频背景，
                                    // 登录卡片被视频盖住（完整模式下扫不了），只有收敛后可用
                                    view?.evaluateJavascript(QR_ONLY_SCRIPT, null)
                                    view?.evaluateJavascript(BOOTSTRAP_SCRIPT, null)
                                    if (stage == "login") status = "请用手机「企业微信」扫下方二维码登录"
                                } else {
                                    view?.evaluateJavascript(jwFitViewportScript(view), null)
                                    view?.evaluateJavascript(BOOTSTRAP_SCRIPT, null)
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                if (isJwUrl(url) && stage == "login") {
                                    stage = "fetching"
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                        }
                        loadUrl(SSO_LOGIN_URL)
                    }
                },
                update = { view -> webView = view },
            )
            if (stage == "fetching") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(themeColor(Color(0xE6FFFFFF), AppColors.Surface.copy(alpha = 0xE6 / 255f)))
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("正在自动获取课表，已等待 $waited 秒…", style = MaterialTheme.typography.bodySmall, color = themeColor(Color(0xFF374151), AppColors.TextSecondary))
                    }
                }
            }
        }

        // ---------------- 缩放工具条（修「电脑模式又大又缩不动」） ----------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolChip("缩小") { webView?.zoomOut() }
            ToolChip("放大") { webView?.zoomIn() }
            ToolChip("适应宽度") {
                val current = webView?.url.orEmpty()
                if (isSsoUrl(current)) {
                    webView?.evaluateJavascript(QR_ONLY_SCRIPT, null)
                } else {
                    webView?.evaluateJavascript(jwFitViewportScript(webView), null)
                }
            }
            ToolChip("重新加载") {
                stage = "login"
                expandedWeb = false
                status = "重新打开企业微信扫码登录…"
                webView?.setInitialScale(0)
                webView?.loadUrl(SSO_LOGIN_URL)
            }
            // 登录页固定收敛为二维码视图（原始页视频背景会盖住登录卡片），
            // 「完整网页」只对登录后的教务页面有意义
            if (stage != "login") {
                ToolChip(if (expandedWeb) "只看二维码" else "完整网页") {
                    expandedWeb = !expandedWeb
                    // 原生初始缩放兜底：动态改已有 viewport meta 的 initial-scale 不生效
                    webView?.setInitialScale(if (expandedWeb) jwDesktopScalePercent(webView) else 0)
                    webView?.reload()
                }
            }
        }
        Text(
            "电脑模式按教务页面原始宽度整体缩放；缩放按钮可随时放大/缩小。",
            color = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )

        // ---------------- 扫码面板（自动截取二维码 + 告知怎么做） ----------------
        if (stage == "login") {
            Surface(
                color = AppColors.OnAccent,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shot = qrBitmap
                        Surface(
                            color = themeColor(Color(0xFFF3F4F6), AppColors.ChipIdle),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, themeColor(Color(0xFFE5E7EB), AppColors.Divider)),
                        ) {
                            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                                if (shot != null) {
                                    Image(
                                        bitmap = shot.asImageBitmap(),
                                        contentDescription = "登录二维码",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(78.dp),
                                    )
                                } else {
                                    Text("加载中…", style = MaterialTheme.typography.labelSmall, color = themeColor(Color(0xFF9CA3AF), AppColors.TextMuted))
                                }
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("用企业微信扫码登录", fontWeight = FontWeight.SemiBold)
                            Text(
                                "① 手机打开「企业微信」→ 右上角 ➕ → 扫一扫\n" +
                                    "② 扫上面二维码 / 或点「保存二维码」后用相册选它\n" +
                                    "③ 确认登录后会自动抓取课表并导入，无需其他操作",
                                color = themeColor(Color(0xFF4B5563), AppColors.TextSecondary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                            onClick = {
                                val shot = qrBitmap
                                saveQrToGallery(shot)
                            },
                        ) { Text("保存二维码") }
                        OutlinedButton(onClick = {
                            status = if (jwOpenWeCom(appContext)) {
                                "已打开企业微信：右上角 ➕ → 扫一扫"
                            } else {
                                "没检测到企业微信，请用另一台手机的企业微信扫码"
                            }
                        }) { Text("打开企业微信") }
                    }
                }
            }
        } else {
            // ---------------- 结果面板 ----------------
            Surface(color = AppColors.SurfaceOpaque, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (parsed.isEmpty()) "正在解析课表…" else "解析到 ${parsed.size} 门课",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (parsedFormat.isBlank()) "抓到课表数据后会自动导入" else "来源：$parsedFormat",
                                color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Button(
                                enabled = parsed.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                                onClick = { saveAction.run() },
                            ) { Text("保存到课表") }
                        }
                    }
                    if (parsed.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            Modifier.fillMaxWidth().height(132.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(parsed) { course ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(color = AppColors.Accent.copy(alpha = .12f), shape = RoundedCornerShape(8.dp)) {
                                        Text(
                                            ScheduleText.shortWeekday(course.weekday),
                                            color = AppColors.Accent,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        Text(course.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${course.startPeriod}-${course.endPeriod}节 · ${course.weekLabel()} · " +
                                                listOfNotNull(course.location, course.teacher).joinToString(" · "),
                                            color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolChip("抓取当前页") {
                            busy = true
                            webView?.evaluateJavascript(GRAB_SCRIPT, null)
                        }
                        ToolChip("课表查询") { goScheduleAction.run() }
                        ToolChip("示例课表") {
                            val result = ScheduleParsers.parseAuto(ScheduleParsers.demoPayload())
                            parsed = result.courses
                            parsedFormat = "内置示例"
                            status = "已载入内置示例课表（${result.courses.size} 门）"
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    Surface(
        color = themeColor(Color(0xFFEFF2F7), AppColors.ChipIdle),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = themeColor(Color(0xFF374151), AppColors.TextSecondary),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}


