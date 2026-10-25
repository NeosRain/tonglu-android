package cn.tonglu.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.tonglu.app.data.ScoreBoard
import cn.tonglu.app.data.ScoreParsers
import cn.tonglu.app.data.ScoreRecord
import org.json.JSONObject

private const val SCORE_TAG = "TongluScore"

/** 成绩查询应用作为 CAS 的 service：登录后直接落在成绩页 */
private val SCORE_SSO_URL = ssoLoginUrl(SCORE_PAGE)

/**
 * 从教务导入成绩。
 *
 * 和课表导入是同一套链路、同一套合规边界（登录由用户本人扫码完成、只把页面自己发出的
 * 响应抄一份给原生、Cookie 留在 WebView 里），差别是：
 *  · 落点是「成绩查询」应用 `sys/cjcx` 下的 `*default/index.do`（入口取自教务首页 myApps.do 的真实返回）；
 *  · 解析走 [ScoreParsers] 的自适应识别，认出来的字段映射会显示出来让你核对；
 *  · **不自动保存** —— 成绩是结论性数据，宁可多一次点击，也不要存错。
 *
 * 抓不到时可以直接「载入示例成绩」，先看分析结果长什么样。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ScoreImportScreen(
    onClose: () -> Unit,
    onSave: (records: List<ScoreRecord>, demo: Boolean, done: (Boolean, String) -> Unit) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val activity = jwFindActivity(LocalContext.current)
    val onSaveState = rememberUpdatedState(onSave)
    val onCloseState = rememberUpdatedState(onClose)

    var stage by remember { mutableStateOf("login") }
    var status by remember { mutableStateOf("正在打开企业微信扫码登录…") }
    var parsed by remember { mutableStateOf<List<ScoreRecord>>(emptyList()) }
    var parseInfo by remember { mutableStateOf("") }
    var mapping by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 接口自报的总条数（-1 = 没说）。分页时用来提示「还有多少没抓到」 */
    var totalSize by remember { mutableStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    // 当前预览是不是「载入示例成绩」来的：示例数据必须全程打标，保存时也要如实记录来源
    var demoLoaded by remember { mutableStateOf(false) }
    val saveQrToGallery = rememberQrGallerySaver { status = it }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val logDir = remember { File(appContext.getExternalFilesDir(null), "jw").apply { mkdirs() } }
    fun logToFile(tag: String, url: String, body: String) {
        runCatching {
            val file = File(logDir, "score_capture.log")
            if (file.length() > 8_000_000) file.delete()
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("\n===== $tag $time len=${body.length} url=$url\n")
            file.appendText(body.take(300_000))
            file.appendText("\n")
        }
    }

    val bridge = remember {
        object {
            /** 累积识别到的记录：按「课程名 | 学年学期 | 考试类型」去重合并 */
            val merged = linkedMapOf<String, ScoreRecord>()
            var jumped = false
            var lastQrKey = ""
            var lastQrAt = 0L
            var lastMapping: List<String> = emptyList()
            var lastTotal = -1
            var lastNote = ""
            var lastSource = ""

            fun keyOf(r: ScoreRecord): String =
                listOf(r.courseName, r.termCode.orEmpty(), r.examType.orEmpty()).joinToString("|")

            /** 合并一份解析结果；返回有没有带来新东西 */
            @Synchronized
            fun accept(result: ScoreParsers.ParseResult, source: String): Boolean {
                if (result.records.isEmpty()) return false
                var changed = false
                result.records.forEach { record ->
                    val key = keyOf(record)
                    val old = merged[key]
                    // 同一条记录再遇到时，保留「有分数的」那版（同一门课多次重修会有多条）
                    if (old == null || (old.scoreText.isBlank() && record.scoreText.isNotBlank())) {
                        merged[key] = record
                        changed = true
                    }
                }
                if (result.mapping.isNotEmpty()) lastMapping = result.mapping
                if (result.total > 0) lastTotal = result.total
                if (changed) lastSource = source
                return changed
            }

            @Synchronized
            fun snapshot(): List<ScoreRecord> =
                merged.values.sortedWith(compareByDescending<ScoreRecord> { it.termCode.orEmpty() }.thenBy { it.courseName })

            @Synchronized
            fun count(): Int = merged.size

            fun publish(message: String) {
                val records = snapshot()
                val map = lastMapping
                val total = lastTotal
                mainHandler.post {
                    parsed = records
                    parseInfo = message
                    mapping = map
                    totalSize = total
                    demoLoaded = false
                    stage = "fetching"
                    status = "已识别 ${records.size} 条成绩记录，核对后点「保存到成绩」"
                }
            }

            /** 页面自己的 XHR/fetch 响应会送到这里 */
            @JavascriptInterface
            fun capture(url: String, body: String) {
                if (body.length < 30) return
                logToFile("接口", url, body)
                if (!ScoreParsers.looksLikeScores(body)) return
                android.util.Log.d(SCORE_TAG, "capture url=$url len=${body.length}")
                val result = runCatching { ScoreParsers.parseAuto(body) }.getOrNull() ?: return
                if (accept(result, "接口")) publish(result.message)
            }

            /**
             * 「抓取当前页」：把主文档 + 全部同源子框架的 DOM、以及页面自己的接口响应日志
             * 一起送过来 —— 金智 jwapp 的网格往往在子框架里，只看主文档会一片空白。
             */
            @JavascriptInterface
            fun grab(payload: String) {
                logToFile("抓取", "grab", payload)
                val before = count()
                var frameCount = 0
                var apiCount = 0
                var firstMessage = ""
                runCatching {
                    val root = JSONObject(payload)
                    root.optJSONArray("frames")?.let { frames ->
                        for (i in 0 until frames.length()) {
                            val html = frames.optJSONObject(i)?.optString("html").orEmpty()
                            if (html.length < 60) continue
                            frameCount++
                            val result = runCatching { ScoreParsers.parseAuto(html) }.getOrNull() ?: continue
                            if (firstMessage.isBlank() && result.records.isEmpty()) firstMessage = result.message
                            accept(result, "页面")
                        }
                    }
                    root.optJSONArray("apiLog")?.let { logs ->
                        for (i in 0 until logs.length()) {
                            val body = logs.optJSONObject(i)?.optString("h").orEmpty()
                            if (body.length < 30) continue
                            apiCount++
                            val result = runCatching { ScoreParsers.parseAuto(body) }.getOrNull() ?: continue
                            if (firstMessage.isBlank() && result.records.isEmpty()) firstMessage = result.message
                            accept(result, "接口")
                        }
                    }
                }.onFailure { android.util.Log.d(SCORE_TAG, "grab failed: ${it.message}") }

                val after = count()
                val added = after - before
                mainHandler.post {
                    busy = false
                    if (added > 0) {
                        publish("本次新增 $added 条（共 $after 条）")
                    } else if (after > 0) {
                        status = "这一页没有新的成绩（已识别 $after 条）。要补齐别的学期，先在教务页切换学期再抓一次"
                    } else {
                        status = "这一页没抓到成绩（看了 $frameCount 个框架、$apiCount 条接口响应）。" +
                            (firstMessage.ifBlank { "请确认页面上能看到成绩列表" }) +
                            "　原始内容已存 files/jw/score_capture.log"
                    }
                }
            }

            /** 页面探针：判断登录是否成功、顺手截二维码 */
            @JavascriptInterface
            fun probe(raw: String) {
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
                val host = json.optString("host", "")
                val qr = json.optJSONObject("qr")
                val viewportWidth = json.optDouble("vw", 0.0)
                val viewportHeight = json.optDouble("vh", 0.0)

                if (host.contains(SSO_HOST)) {
                    mainHandler.post { if (stage == "login") status = "请用手机「企业微信」扫下方二维码登录" }
                    if (viewportWidth > 0 && viewportHeight > 0) {
                        val key = "${qr?.optInt("x")}|${qr?.optInt("y")}|${qr?.optInt("w")}"
                        val now = System.currentTimeMillis()
                        if (key != lastQrKey || now - lastQrAt > 4000) {
                            lastQrKey = key
                            lastQrAt = now
                            mainHandler.post {
                                val view = webView ?: return@post
                                val act = activity ?: return@post
                                jwCaptureLoginQr(act, view, json) { bitmap -> qrBitmap = bitmap }
                            }
                        }
                    }
                    return
                }

                // 离开 SSO 落到教务 = 登录成功：直接进成绩查询页
                if (host.contains(JW_HOST) && !jumped) {
                    jumped = true
                    mainHandler.post {
                        stage = "fetching"
                        // 真实秒数由顶部横幅报（rememberElapsedSeconds），这里不要再写死「约 10 秒」
                        status = "扫码登录成功，正在加载成绩，请勿重复扫码"
                        webView?.loadUrl(SCORE_PAGE)
                    }
                }
            }

            @JavascriptInterface
            fun note(message: String) {
                lastNote = message
                mainHandler.post { status = message }
            }

            /**
             * 诊断用：把页面上的真实 DOM 结构原样落盘（files/jw/score_capture.log）。
             * 教务这套网格的翻页控件是图标字体，靠猜类名找不着 —— 只能先看清楚再动手。
             */
            @JavascriptInterface
            fun dump(text: String) {
                logToFile("诊断", "dom", text)
            }
        }
    }

    // 从企业微信切回来时补一次钩子（后台期间定时器可能被挂起）
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

    // 等待实际新增记录，慢网络不会因为固定 2.6 秒截止而漏页。
    var autoPaging by remember { mutableStateOf(false) }
    var pageTries by remember { mutableStateOf(0) }
    var nextPage by remember { mutableStateOf(2) }
    val flipNextPage: () -> Unit = { autoPaging = true }
    LaunchedEffect(autoPaging) {
        if (!autoPaging) return@LaunchedEffect
        while (autoPaging && pageTries < 200) {
            if (totalSize > 0 && parsed.size >= totalSize) { status = "全部 $totalSize 条已补齐，请核对后保存"; break }
            val view = webView ?: break
            val before = parsed.size
            status = "正在补齐第 $nextPage 页 · 已识别 ${parsed.size}/${if (totalSize > 0) totalSize else "未知"} 条，请勿关闭页面"
            var received = false
            for (attempt in 0..1) {
                view.evaluateJavascript(jwFetchScorePageScript(nextPage), null)
                repeat(30) {
                    if (!received) {
                        kotlinx.coroutines.delay(500)
                        received = parsed.size > before
                    }
                }
                if (received) break
            }
            if (!received) { status = "第 $nextPage 页尚未收到新数据，可重试补齐或手动翻页；已识别 ${parsed.size} 条"; break }
            nextPage++; pageTries++
        }
        autoPaging = false
    }
    DisposableEffect(Unit) { onDispose { mainHandler.removeCallbacksAndMessages(null); webView?.stopLoading(); webView?.destroy() } }

    // 识别到「不止一页」就自己补齐：网格每页只给 10 条，接口自报总数往往远大于已识别数。
    // 只自动开一次（autoStarted 兜底），最多翻 12 次，够了或没进展就停手。
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(totalSize, parsed.size) {
        if (!autoStarted && totalSize > 0 && parsed.size > 0 && parsed.size < totalSize) {
            autoStarted = true
            autoPaging = true
            pageTries = 0
            nextPage = 2
            status = "这一页 ${parsed.size} 条，教务说一共 $totalSize 条 —— 正在自动补齐…"
            flipNextPage()
        }
    }

    val save: () -> Unit = {
        if (!busy && parsed.isNotEmpty()) {
            busy = true
            onSaveState.value(parsed, demoLoaded) { ok, message ->
                busy = false
                status = message
                if (ok) {
                    stage = "done"
                    mainHandler.postDelayed({ onCloseState.value() }, 700)
                }
            }
        }
    }

    // 网络体检：VPN / 境外 IP 下教务根本打不开，以前只能干等白屏，现在明确告诉用户导不进来。
    // 与课表导入共用同一份实现，文案与判定口径不至于走偏。
    val netCheck = rememberCampusNetworkCheck()

    Column(Modifier.fillMaxSize().background(AppColors.Canvas).then(Modifier.safeDrawingPadding())) {
        CampusNetworkNotice(netCheck)
        // 扫码登录成功后的加载期：报**真实**等待秒数，超过 45 秒换成琥珀色并给出可操作提示。
        // 以前这里是一句写死的「通常需要约 10 秒」—— 慢网络下等到 1 分钟还不变，用户只能干等。
        val loading = stage == "fetching"
        val waited = rememberElapsedSeconds(loading)
        val stalled = loading && waited >= 45
        Surface(color = if (stalled) noticeBackground(Color(0xFFFFF3E0)) else noticeBackground(Color(0xFFE8F0FE)), modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    stage == "login" -> "请使用企业微信扫码登录 · 不要使用应用账号登录教务"
                    stalled -> "还在加载成绩（已等待 $waited 秒）。教务在慢网络下会更久；始终没有数据就点下方「抓取当前页」重试，或先在教务页切换学期。"
                    loading -> "$status · 已等待 $waited 秒"
                    else -> status
                },
                color = if (stalled) noticeForeground(Color(0xFFB26A00)) else noticeForeground(Color(0xFF254E78)),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            Modifier.fillMaxWidth().background(AppColors.SurfaceOpaque).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text("从教务导入成绩", fontWeight = FontWeight.SemiBold)
                Text(status, color = themeColor(Color(0xFF6B7280), AppColors.TextMuted), style = MaterialTheme.typography.bodySmall, maxLines = 3)
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
                        "fetching" -> "已识别"
                        else -> "已完成"
                    },
                    color = when (stage) {
                        "login" -> noticeForeground(Color(0xFFB26A00))
                        "fetching" -> noticeForeground(noticeForeground(Color(0xFF1A56DB)))
                        else -> noticeForeground(Color(0xFF12833B))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.userAgentString = DESKTOP_UA
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { qrBitmap = null; if (!isSsoUrl(url.orEmpty()) && parsed.isEmpty()) status = "正在完成登录并加载成绩，通常需要约 10 秒，请稍候…" }
                            // 体检通过但页面照样打不开（中途断网、被拦）时，靠这条兜底把提示亮出来。
                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) netCheck.loadFailed = true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript(jwFitViewportScript(view), null)
                                view.evaluateJavascript(BOOTSTRAP_SCRIPT, null)
                                if (isSsoUrl(url)) view.evaluateJavascript(QR_ONLY_SCRIPT, null)
                                view.evaluateJavascript(
                                    "document.documentElement.style.webkitTextSizeAdjust='100%';",
                                    null,
                                )
                            }
                        }
                        addJavascriptInterface(bridge, "TongluBridge")
                        setInitialScale(jwDesktopScalePercent(this))
                        loadUrl(SCORE_SSO_URL)
                        webView = this
                    }
                },
            )

            if (stage == "login") {
                val shot = qrBitmap
                if (shot != null) {
                    Surface(
                        color = AppColors.OnAccent,
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 6.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                bitmap = shot.asImageBitmap(),
                                contentDescription = "登录二维码",
                                modifier = Modifier.size(92.dp),
                            )
                            Column(Modifier.padding(start = 12.dp).width(180.dp)) {
                                Text("手机扫不到屏幕？", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "把二维码存到相册，再用企业微信「扫一扫 → 右上角相册」选它。",
                                    color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ToolChip("存到相册") {
                                        saveQrToGallery(shot)
                                    }
                                    ToolChip("打开企业微信") {
                                        if (!jwOpenWeCom(appContext)) status = "没有找到企业微信，请用另一台设备扫码"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------- 底部：预览 + 操作 ----------------
        Surface(color = AppColors.SurfaceOpaque, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (parsed.isEmpty()) "等待成绩数据…" else "已识别 ${parsed.size} 条记录",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            parseInfo.ifBlank { "登录后打开「成绩查询」页面，数据会自动抓取" },
                            color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Button(
                            enabled = parsed.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
                            onClick = save,
                        ) { Text(if (demoLoaded) "保存示例数据" else "保存到成绩") }
                    }
                }

                if (mapping.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "识别到的字段：" + mapping.joinToString("，"),
                        color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                    )
                }

                if (demoLoaded) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = noticeBackground(Color(0xFFFFF4E5)),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "这是内置示例数据，不是你的真实成绩 —— 只是让你先看看效果。保存后成绩页会标注「示例」，之后导入真实成绩会自动覆盖。",
                            color = noticeForeground(Color(0xFFB25E09)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                if (totalSize > parsed.size) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = noticeBackground(Color(0xFFE8F0FE)),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "已抓到 ${parsed.size} / 共 $totalSize 条 —— 成绩页默认每页只加载 10 条。"
                                + "在页面下方翻页（或把每页条数调到最大），每显示一页就会自动累计进来，再点保存。",
                            color = noticeForeground(noticeForeground(Color(0xFF1A56DB))),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                if (parsed.isNotEmpty()) {
                    val terms = remember(parsed) { parsed.mapNotNull { it.termCode }.distinct().sorted() }
                    if (terms.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "覆盖 ${terms.size} 个学期：" + terms.joinToString("、") +
                                if (!demoLoaded && terms.size <= 1) {
                                    " —— 不止这些？教务页可能默认只显示一个学期，在页面里切换学期后再点「抓取当前页」即可补齐"
                                } else {
                                    ""
                                },
                            color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 3,
                        )
                    }
                }

                if (parsed.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val preview = remember(parsed) { ScoreBoard.byCourse(parsed) }
                    LazyColumn(
                        Modifier.fillMaxWidth().height(140.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(preview, key = { it.name }) { course ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = (if (course.passed) noticeForeground(Color(0xFF12833B)) else themeColor(Color(0xFFD9480F), AppColors.Danger)).copy(alpha = .12f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        if (course.passed) "已过" else "未过",
                                        color = if (course.passed) noticeForeground(Color(0xFF12833B)) else themeColor(Color(0xFFD9480F), AppColors.Danger),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(course.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        listOfNotNull(
                                            course.effective?.scoreText?.takeIf { it.isNotBlank() }
                                                ?.let { ScoreBoard.scoreWithUnit(it) },
                                            ScoreBoard.termLabel(course.effective?.termCode),
                                            if (course.retakeCount > 0) "重修 ${course.retakeCount} 次" else null,
                                        ).joinToString(" · "),
                                        color = themeColor(Color(0xFF6B7280), AppColors.TextMuted),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolChip("自动翻页补齐") {
                        // 网格每页 10 条、总数 61：点一次翻一页，每页的响应自动累计进来。
                        // 最多翻 12 次；够了或没进展就停手，绝不空转。
                        autoPaging = true
                        pageTries = 0
                        status = "正在翻页补齐（每翻一页自动累计）…"
                        flipNextPage()
                    }
                    ToolChip("抓取当前页") {
                        busy = true
                        status = "正在翻页面和接口记录找成绩…"
                        // 若 10 秒没有回音（脚本被页面拦下 / 桥没挂上），自己把手刹松开，
                        // 不能让按钮永远停在转圈上 —— 之前就是这里卡死的
                        mainHandler.postDelayed({
                            if (busy) {
                                busy = false
                                status = "抓取没有回应。试试点「成绩查询」进成绩页，等列表出来再抓一次。（原始内容见 files/jw/score_capture.log）"
                            }
                        }, 10_000)
                        webView?.evaluateJavascript(GRAB_SCRIPT, null)
                    }
                    ToolChip("成绩查询") { webView?.loadUrl(SCORE_PAGE) }
                    ToolChip("教务首页") { webView?.loadUrl("$JW_BASE$JW_APP/sys/homeapp/home/index.html") }
                    ToolChip("载入示例成绩") {
                        val result = ScoreParsers.parseAuto(ScoreParsers.demoPayload())
                        parsed = result.records
                        parseInfo = result.message + "（示例数据）"
                        mapping = result.mapping
                        demoLoaded = true
                        stage = "fetching"
                        status = "已载入示例成绩 ${result.records.size} 条 —— 这是演示数据，不是你的真实成绩"
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


