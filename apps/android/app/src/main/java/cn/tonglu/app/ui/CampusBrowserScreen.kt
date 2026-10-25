package cn.tonglu.app.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 校园官方入口的「应用内浏览器」。
 *
 * 为什么需要它（2026-09-29 实测确定）：
 *  · 直接 ACTION_VIEW 甩给系统浏览器 → 系统浏览器是手机 UA，学校各系统一律按移动端分流：
 *    数字服务平台/学工/图书馆这些会把人导去「企业微信移动端」，甚至要求下载 App，
 *    在手机上根本走不通。教务同理，之前课表导入页已经踩过这个坑。
 *  · 所以这里不跳浏览器，改为 App 内打开，并且默认使用**电脑版 UA**，
 *    让学校系统按 PC 版渲染 —— 这才是这些系统真正能用的形态。
 *  · 只做「承载网页」：不代登录、不注入业务脚本、不读取页面内容。
 *    Cookie 由 WebView 自己持久化，统一身份认证登录一次即可复用（和浏览器里是同一个道理）。
 */

// 桌面 UA 统一在 JwWebSupport.kt（DESKTOP_UA）里，导入页与浏览器共用一份

private val BROWSER_TINT get() = AppColors.Accent
private val BROWSER_MUTED get() = AppColors.TextMuted
private val BROWSER_LINE get() = themeColor(Color(0xFFEDEFF3), AppColors.Divider)

/**
 * 「适应宽度」：先放开被学校页面写死的 maximum-scale/user-scalable（否则手机上一根手指都缩不动），
 * 再在页面明显比屏幕宽时按比例整体缩放（电脑版页面自带 min-width，不缩就会横向滚动）。
 */
private const val FIT_WIDTH_SCRIPT = """
(function () {
  try {
    var root = document.documentElement;
    var head = document.head || document.getElementsByTagName('head')[0];
    var m = document.querySelector('meta[name=viewport]');
    if (m) {
      m.setAttribute('content', 'width=device-width, initial-scale=1.0, minimum-scale=0.2, maximum-scale=5.0, user-scalable=yes');
    }
    root.style.zoom = '';
    root.style.width = '';
    root.style.minWidth = '';
    var vw = window.innerWidth || 0;
    var w = root.scrollWidth || 0;
    var bw = document.body ? document.body.scrollWidth : 0;
    if (bw > w) w = bw;
    if (vw > 0 && w > vw * 1.05) {
      root.style.zoom = (vw / w);
      root.style.minWidth = '0';
    }
  } catch (e) {}
})();
"""

private const val RESET_ZOOM_SCRIPT = """
(function () {
  try {
    var root = document.documentElement;
    root.style.zoom = '';
    root.style.width = '';
    root.style.minWidth = '';
  } catch (e) {}
})();
"""

private fun browserHostOf(url: String?): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.")

/** 统一身份认证 / 登录页：这些页面在电脑版下是二维码，需要提示用户怎么扫（手机上扫不了自己屏幕） */
private fun looksLikeLoginPage(url: String?): Boolean {
    val value = url.orEmpty().lowercase()
    return value.contains("sso.") || value.contains("login")
}

/** 打开手机上的企业微信；没有安装返回 false */
private fun launchWeCom(context: Context): Boolean {
    val manager = context.packageManager
    for (pkg in listOf("com.tencent.wework", "com.tencent.weworklite", "com.tencent.wework.android")) {
        val intent = runCatching { manager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
    }
    return false
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("链接", text))
    }
}

/** 交给系统浏览器（用户明确要求「用外部浏览器打开」时才走这里） */
private fun openWithSystemBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampusBrowserScreen(
    url: String,
    title: String,
    onClose: () -> Unit,
    initialDesktopMode: Boolean = false,
    onReadPage: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    var desktopMode by rememberSaveable { mutableStateOf(initialDesktopMode) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val popupHolder = remember { mutableListOf<WebView>() }

    // 提示条自言自语 3 秒后自动消失
    LaunchedEffect(hint) {
        if (hint != null) {
            kotlinx.coroutines.delay(3000)
            hint = null
        }
    }

    // 这一层**不接管**跟手返回（backEnabled = false）：它的返回有别的含义 ——
    // 网页能后退就先退一页，退不动了才关掉这一层。让跟手返回抢过去的话，
    // 用户往右拖一下看到的是「整层要关掉」，松手却只是网页退了一页，对不上。
    // 有网页历史时先退网页；没有历史时才由宿主接管预测返回并关闭窗口。
    val motion = rememberOverlayMotion(
        onClose = onClose,
        predictive = OverlayMotion.enabledState,
        selfDrag = false,
        backEnabled = !canGoBack,
    )

    BackHandler(enabled = canGoBack) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else motion.requestClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            popupHolder.forEach { runCatching { it.destroy() } }
            popupHolder.clear()
            webView?.let { view ->
                runCatching {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.stopLoading()
                    view.destroy()
                }
            }
            webView = null
        }
    }

    Column(Modifier.fillMaxSize().overlayMotion(motion, fade = false).background(AppColors.SurfaceOpaque).statusBarsPadding()) {
        // ---------------- 顶栏 ----------------
        Surface(color = AppColors.SurfaceOpaque, shadowElevation = 0.dp) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        val view = webView
                        if (view != null && view.canGoBack()) view.goBack() else motion.requestClose()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = AppColors.TextPrimary)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 2.dp)) {
                        Text(
                            pageTitle.ifBlank { title },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (desktopMode) "电脑版 · ${browserHostOf(currentUrl)}" else "手机版 · ${browserHostOf(currentUrl)}",
                            color = BROWSER_MUTED,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BrowserChip(if (desktopMode) "电脑版" else "手机版") {
                        val view = webView ?: return@BrowserChip
                        desktopMode = !desktopMode
                        view.settings.userAgentString = if (desktopMode) DESKTOP_UA else null
                        view.settings.loadWithOverviewMode = desktopMode
                        view.settings.useWideViewPort = true
                        hint = if (desktopMode) "已切到电脑版页面" else "已切到手机版页面"
                        view.reload()
                    }
                    if (onReadPage != null) androidx.compose.material3.TextButton(onClick = {
                        webView?.evaluateJavascript("document.documentElement.outerHTML") { encoded ->
                            try {
                                val html = org.json.JSONTokener(encoded).nextValue() as? String
                                    ?: error("页面尚未就绪，请稍后再试")
                                require(html.toByteArray(Charsets.UTF_8).size <= cn.tonglu.app.data.ExternalScheduleParser.MAX_BYTES) {
                                    "页面超过 2 MB，请改用课表导出文件"
                                }
                                onReadPage(html)
                            } catch (error: Exception) { hint = error.message ?: "读取页面失败，请重试" }
                        }
                    }) { Text("读取课表") }
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Outlined.Refresh, "刷新", tint = AppColors.TextPrimary)
                    }
                }
                // 细进度条（自绘，避免依赖不同 material3 版本的进度条签名）
                Box(Modifier.fillMaxWidth().height(2.dp).background(BROWSER_LINE)) {
                    if (progress in 1..99) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress / 100f)
                                .height(2.dp)
                                .background(BROWSER_TINT),
                        )
                    }
                }
            }
        }

        // ---------------- 网页区 ----------------
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // 普通官网使用手机版；教务和业务门户按入口配置使用电脑版。
                        settings.userAgentString = if (desktopMode) DESKTOP_UA else WebSettings.getDefaultUserAgent(ctx)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = desktopMode
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (!url.isNullOrBlank()) currentUrl = url
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (!url.isNullOrBlank()) currentUrl = url
                                canGoBack = view?.canGoBack() == true
                                if (desktopMode) view?.evaluateJavascript(FIT_WIDTH_SCRIPT, null)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                if (!url.isNullOrBlank()) currentUrl = url
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    hint = "页面没打开（${error?.description ?: "网络错误"}），可试试「外部浏览器」"
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url ?: return false
                                val scheme = target.scheme.orEmpty().lowercase()
                                if (scheme == "http" || scheme == "https") return false
                                // 非 http 协议（wxwork://、alipays:// 等）：
                                //  · 用户自己点的 → 交给系统（企业微信扫码回调这类必须走系统）
                                //  · 页面自己偷偷跳的 → 拦下，别把人莫名其妙甩出 App
                                if (request.hasGesture()) {
                                    openWithSystemBrowser(view!!.context, target.toString())
                                } else {
                                    hint = "页面想跳到外部 App（$scheme://），已拦下；确有需要可点「外部浏览器」"
                                }
                                return true
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, newTitle: String?) {
                                pageTitle = newTitle.orEmpty()
                            }

                            // target=_blank 的链接：留在当前视图里打开，避免弹窗被吞掉
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                val host = view ?: return false
                                val popup = WebView(host.context)
                                // 持住引用：WebViewTransport 交出去之后如果没人引用，可能被回收导致加载失败
                                popupHolder.add(popup)
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        v: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        request?.url?.let { host.loadUrl(it.toString()) }
                                        return true
                                    }
                                }
                                transport.webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        loadUrl(url)
                    }
                },
                update = { view -> webView = view },
            )

            if (hint != null) {
                Surface(
                    color = Color(0xE6111827),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                ) {
                    Text(
                        hint.orEmpty(),
                        color = AppColors.OnAccent,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // ---------------- 电脑版登录页提示 ----------------
        if (desktopMode && looksLikeLoginPage(currentUrl)) {
            Surface(color = noticeBackground(Color(0xFFFFF7E6))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "电脑版登录页需要扫码：截图后用企业微信「扫一扫 → 相册」选中二维码；或用另一台设备扫。",
                        color = noticeForeground(Color(0xFF8A5A00)),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "打开企业微信",
                        color = BROWSER_TINT,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable {
                                hint = if (launchWeCom(context)) "已打开企业微信：右上角 ➕ → 扫一扫" else "没检测到企业微信，请用另一台设备扫码"
                            }
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }

        // ---------------- 底部工具条 ----------------
        Surface(color = AppColors.SurfaceOpaque) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BrowserChip("适应宽度") {
                    webView?.evaluateJavascript(FIT_WIDTH_SCRIPT, null)
                    hint = "已按屏幕宽度整体缩放"
                }
                BrowserChip("原始比例") {
                    webView?.evaluateJavascript(RESET_ZOOM_SCRIPT, null)
                }
                BrowserChip("缩小") {
                    webView?.evaluateJavascript("document.documentElement.style.zoom = String(Math.max(0.15, (parseFloat(document.documentElement.style.zoom)||1) - 0.1))", null)
                }
                BrowserChip("放大") {
                    webView?.evaluateJavascript("document.documentElement.style.zoom = String(Math.min(3.0, (parseFloat(document.documentElement.style.zoom)||1) + 0.1))", null)
                }
                BrowserChip("复制链接") {
                    copyToClipboard(context, currentUrl)
                    hint = "链接已复制"
                }
                BrowserChip("外部浏览器") {
                    openWithSystemBrowser(context, currentUrl)
                }
            }
        }
    }
}

@Composable
private fun BrowserChip(label: String, onClick: () -> Unit) {
    Surface(
        // 🔴 原来是写死的浅灰 `#EFF2F7`：深色主题下胶囊还是浅色，而里面的字用的是深色方案的
        //    浅色前景 →「浅底浅字」，直接看不见。`ChipIdle` 本来就分浅/深两套。
        color = AppColors.ChipIdle,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = themeColor(Color(0xFF374151), AppColors.TextSecondary),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}


