package cn.tonglu.app.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.webkit.WebView

/**
 * 教务（金智 jwapp）抓取链路的公共件：UA / 地址 / 注入脚本 / 二维码截图 / 企业微信唤起。
 *
 * 课表导入（[ScheduleImportScreen]）与成绩导入（[ScoreImportScreen]）共用同一份，
 * 别再各自复制一遍 —— 之前 UA 就在 CampusBrowserScreen 与导入页各写了一份，
 * 靠「记得改两处」维持一致，那是迟早要出错的。
 *
 * 合规边界（与 docs/PLATFORM_INTEGRATION.md 一致）：
 *  · 登录一律由用户本人在 WebView 里完成（企业微信扫码 / 账号密码），App 不代登录；
 *  · 脚本只做两件事：把页面自己的 XHR/fetch 响应**抄一份**给原生、以及把当前 URL 报上来；
 *  · Cookie 留在 WebView 里，App 不读取、不导出、不同步。
 */

/** 桌面 Chrome UA：学校系统据此给 PC 版页面（手机 UA 会被赶回「请从企业微信进入」） */
internal const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** 教务 PC 版设计宽度（页面自带 min-width:1366px），电脑模式按它做整体缩放 */
internal const val DESKTOP_LAYOUT_WIDTH = 1366

internal const val JW_HOST = "jw.liaodongu.edu.cn"
internal const val SSO_HOST = "sso.ldxy.cn"
internal const val JW_BASE = "https://$JW_HOST"
internal const val JW_APP = "/jwapp"

/** 课表查询应用（金智 kbapp），直接作为 CAS 的 service，登录后落点就在课表页 */
internal const val SCHEDULE_PAGE = "$JW_BASE$JW_APP/sys/kbapp/*default/index.do#/wdkb"

/**
 * 成绩查询应用（金智 cjcx），入口取自教务首页的 myApps.do 实际返回：
 * `{"appName":"cjcx","appCn":"成绩查询","entry":null,"myAppsPages":[{"menuCode":"cjcx",...}]}`
 * 菜单树 menus.do 里它的 entry 是 `sys/cjcx` 下的 `*default/index.do`。
 */
internal const val SCORE_PAGE = "$JW_BASE$JW_APP/sys/cjcx/*default/index.do"

/** 统一身份认证的企业微信扫码登录页（APP028 = 教务应用编号），service 决定登录后的落点 */
internal fun ssoLoginUrl(entryUrl: String): String =
    "https://$SSO_HOST/sso/cas3/APP028/login?service=" +
        Uri.encode(entryUrl).replace("*", "%2A")

internal fun jwFindActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 用 host 判断（不能用整串 contains：SSO 登录地址的 service 参数里就带着教务域名） */
internal fun isJwUrl(url: String?): Boolean =
    !url.isNullOrBlank() && runCatching { Uri.parse(url).host }.getOrNull()?.let { it == JW_HOST || it.endsWith(".$JW_HOST") } == true

internal fun isSsoUrl(url: String?): Boolean =
    !url.isNullOrBlank() && runCatching { Uri.parse(url).host }.getOrNull()?.let { it == SSO_HOST || it.endsWith(".$SSO_HOST") } == true

/** 打开手机上的企业微信；没有安装时返回 false（不做 scheme 兜底，免得被浏览器接走把用户甩出应用） */
internal fun jwOpenWeCom(context: Context): Boolean {
    val manager = context.packageManager
    for (pkg in listOf("com.tencent.wework", "com.tencent.weworklite", "com.tencent.wework.android")) {
        val intent = runCatching { manager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
    }
    return false
}

/** 电脑模式的初始缩放百分比（供 setInitialScale 用；meta 里的 initial-scale 对自带 viewport 的页面改不动） */
internal fun jwDesktopScalePercent(view: WebView?): Int {
    val w = view?.width ?: return 30
    val density = view.resources.displayMetrics.density
    if (density <= 0f || w <= 0) return 30
    val cssWidth = w / density
    return ((cssWidth / DESKTOP_LAYOUT_WIDTH).coerceIn(0.15f, 1.0f) * 100).toInt().coerceAtLeast(15)
}

/** 电脑模式适配：教务页面自带 min-width:1366px 与 maximum-scale=1.0;user-scalable=0 —— 重写它。
 *  注意：必须「删掉旧 meta 再新建」，原地改 content 时 initial-scale 不会被 Chromium 重新应用。 */
internal fun jwFitViewportScript(view: WebView?): String {
    val scale = jwDesktopScalePercent(view) / 100f
    return """
(function () {
  try {
    var head = document.head || document.getElementsByTagName('head')[0];
    if (!head) return;
    var meta = document.querySelector('meta[name=viewport]');
    if (meta && meta.parentNode) meta.parentNode.removeChild(meta);
    meta = document.createElement('meta');
    meta.setAttribute('name', 'viewport');
    meta.setAttribute('content', 'width=$DESKTOP_LAYOUT_WIDTH, initial-scale=$scale, minimum-scale=0.15, maximum-scale=5.0, user-scalable=yes');
    head.appendChild(meta);
  } catch (e) {}
})();
"""
}

/** 用 PixelCopy 从窗口里截一块区域（跨域 iframe 里的二维码也能截到） */
/** 统一坐标转换；候选区域识别失败时在可见页面中寻找二维码。 */
internal fun jwCaptureLoginQr(activity: Activity, view: WebView, probe: org.json.JSONObject, onResult: (Bitmap?) -> Unit) {
    if (!isSsoUrl(view.url) || view.width <= 0 || view.height <= 0) return
    val xy = IntArray(2); view.getLocationInWindow(xy)
    val full = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
    val qr = probe.optJSONObject("qr")
    val viewport = probe.optDouble("vw", 0.0)
    val viewportHeight = probe.optDouble("vh", 0.0)
    if (qr == null || viewport <= 0 || viewportHeight <= 0) {
        jwCaptureWindowRegion(activity, view, full, onResult); return
    }
    val sx = view.width / viewport; val sy = view.height / viewportHeight
    val left = xy[0] + (qr.optDouble("x") - probe.optDouble("vx", 0.0)) * sx
    val top = xy[1] + (qr.optDouble("y") - probe.optDouble("vy", 0.0)) * sy
    val region = Rect(left.toInt(), top.toInt(), (left + qr.optDouble("w") * sx).toInt(), (top + qr.optDouble("h") * sy).toInt())
    jwCaptureWindowRegion(activity, view, region) { image ->
        if (image != null) onResult(image)
        else if (isSsoUrl(view.url)) jwCaptureWindowRegion(activity, view, full, onResult)
    }
}
private val qrWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
internal fun jwCaptureWindowRegion(activity: Activity, view: WebView, region: Rect, onResult: (Bitmap?) -> Unit) {
    val location = IntArray(2); view.getLocationInWindow(location)
    val bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    val clipped = Rect(region)
    if (!clipped.intersect(bounds) || clipped.width() < 40 || clipped.height() < 40 || clipped.width().toLong() * clipped.height() > 8_000_000) return onResult(null)
    val sourceUrl = view.url
    val handler = Handler(Looper.getMainLooper())
    fun process(bitmap: Bitmap?) {
        if (bitmap == null) { onResult(null); return }
        qrWorker.execute {
            val qr = normalizeQr(bitmap)
            bitmap.recycle()
            handler.post { if (view.isAttachedToWindow && view.url == sourceUrl) onResult(qr) else qr?.recycle() }
        }
    }
    if (Build.VERSION.SDK_INT >= 26) {
        val bitmap = Bitmap.createBitmap(clipped.width(), clipped.height(), Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(activity.window, clipped, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) process(bitmap) else { bitmap.recycle(); onResult(null) }
            }, handler)
        }.onFailure { bitmap.recycle(); onResult(null) }
    } else {
        val bitmap = Bitmap.createBitmap(clipped.width(), clipped.height(), Bitmap.Config.ARGB_8888)
        runCatching {
            val canvas = Canvas(bitmap)
            canvas.translate((location[0] - clipped.left).toFloat(), (location[1] - clipped.top).toFloat())
            view.draw(canvas)
            process(bitmap)
        }.onFailure { bitmap.recycle(); onResult(null) }
    }
}
/**
 * 登录页「只留二维码」：这张页面本来是 PC 版式（全屏视频背景 + 左侧温馨提示 + 右侧 350px 卡片），
 * 在手机上又大又要滚。这里把无关元素隐藏、卡片底色去掉、二维码居中，变成一张干净的小页面。
 * 同时放开 maximum-scale（原来是 1.0 / user-scalable=0，所以之前完全缩不动）。
 */
internal const val QR_ONLY_SCRIPT = """
(function () {
  try {
    var head = document.head || document.getElementsByTagName('head')[0];
    if (!head) return;
    var meta = document.querySelector('meta[name=viewport]');
    if (!meta) {
      meta = document.createElement('meta');
      meta.setAttribute('name', 'viewport');
      head.appendChild(meta);
    }
    meta.setAttribute('content', 'width=device-width, initial-scale=1.0, minimum-scale=0.4, maximum-scale=3.0, user-scalable=yes');
    var old = document.getElementById('tonglu-qr-style');
    if (old && old.parentNode) old.parentNode.removeChild(old);
    var style = document.createElement('style');
    style.id = 'tonglu-qr-style';
    style.textContent = [
      'html,body{height:auto!important;min-height:0!important;min-width:0!important;width:100%!important;overflow:hidden!important;background:#ffffff!important;}',
      '.mainCommonSection{min-width:0!important;width:100%!important;height:auto!important;min-height:0!important;background:#ffffff!important;}',
      '.banner,video,#videoID,.footer,#book,.warn_parent{display:none!important;}',
      '.logo-box{position:static!important;width:100%!important;height:auto!important;margin:0!important;top:auto!important;left:auto!important;display:flex!important;justify-content:center!important;}',
      '.logo-box .abroad,.abroad{float:none!important;width:auto!important;max-width:100%!important;height:auto!important;margin:0 auto!important;display:flex!important;flex-direction:column!important;align-items:center!important;}',
      '.logoBox{height:auto!important;margin:4px 0 0!important;display:flex!important;align-items:center!important;justify-content:center!important;}',
      '.logoBox img{float:none!important;width:96px!important;height:27px!important;margin:0 6px 0 0!important;}',
      '.logoBox p{display:none!important;}',
      '.logoBox div{float:none!important;margin:0!important;color:#374151!important;font-size:13px!important;line-height:18px!important;}',
      '.within{width:auto!important;background:transparent!important;border-radius:0!important;padding:0!important;box-shadow:none!important;}',
      '.loginType{display:none!important;}',
      '.weChatBox,#qrcode{display:block!important;width:auto!important;height:auto!important;margin:2px auto!important;box-shadow:none!important;}',
      '.weChatBox img{width:200px!important;height:200px!important;box-shadow:none!important;}',
      '.impowerBox{margin:0 auto!important;}',
      '.guest-access-link{display:none!important;}'
    ].join('');
    head.appendChild(style);
  } catch (e) {}
})();
"""

/**
 * 每页都挂的东西：
 *  1) XHR/fetch 嗅探（主文档 + 所有同源 iframe 子框架，教务页面大多在子框架里），每秒重扫动态框架；
 *  2) 页面探针：每 1.2 秒把「当前 URL + 二维码位置」报给原生（判断登录是否成功、截二维码）。
 */
internal const val BOOTSTRAP_SCRIPT = """
(function () {
  if (!window.__tongluHooked) {
    window.__tongluHooked = true;
    window.__tongluLog = [];
    window.__tongluReport = function (url, body) {
      try {
        body = String(body || '');
        if (body.length < 30) return;
        window.__tongluLog.push({ u: String(url || '').slice(-140), n: body.length, h: body.slice(0, 1500) });
        if (window.__tongluLog.length > 80) window.__tongluLog.shift();
        TongluBridge.capture(String(url || ''), body.slice(0, 2000000));
      } catch (e) {}
    };
    window.__tongluDumpReq = function (req) {
      try {
        TongluBridge.dump('成绩查询请求 ' + req.method + ' ' + req.url +
          '\n请求头：' + JSON.stringify(req.headers) +
          '\n请求体：' + req.body);
      } catch (e) {}
    };
    window.__tongluPatch = function (win) {
      if (!win || win.__tongluPatched) return;
      try { win.__tongluPatched = true; } catch (e) { return; }
      var open = win.XMLHttpRequest.prototype.open;
      var send = win.XMLHttpRequest.prototype.send;
      win.XMLHttpRequest.prototype.open = function (method, target) {
        this.__tongluUrl = target; this.__tongluMethod = method; return open.apply(this, arguments);
      };
      var shr = win.XMLHttpRequest.prototype.setRequestHeader;
      win.XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
        try { this.__tongluHeaders = this.__tongluHeaders || {}; this.__tongluHeaders[k] = v; } catch (e) {}
        return shr.apply(this, arguments);
      };
      win.XMLHttpRequest.prototype.send = function () {
        var self = this;
        this.addEventListener('load', function () { try { window.__tongluReport(self.__tongluUrl, self.responseText); } catch (e) {} });
        // 记下「成绩查询」这个请求长什么样 —— 翻页时要照着它原样再发一次、只换页码。
        // 教务的翻页按钮是图标字体，合成事件（click / MouseEvent / jQuery trigger / touch）一律不认，
        // 实测五种触发方式都推不动它，只能走这条路。
        try {
          var u = String(this.__tongluUrl || '');
          if (u.indexOf('xscjcx') >= 0) {
            var b = arguments[0];
            var body = (b === null || b === undefined) ? '' : String(b);
            // 第一次 xscjcx 是「取表单元数据」（请求体 *searchMeta=1），不是真查成绩，
            // 拿它当翻页模板只会拿回第 1 页 —— 排除掉。
            if (body.indexOf('searchMeta') < 0) {
              win.__tongluScoreReq = {
                url: u,
                method: String(this.__tongluMethod || 'POST'),
                headers: this.__tongluHeaders || {},
                body: body
              };
              if (window.__tongluLoggedBody !== body) {
                window.__tongluLoggedBody = body;
                window.__tongluDumpReq(window.__tongluScoreReq);
              }
            }
          }
        } catch (e) {}
        return send.apply(this, arguments);
      };
      if (win.fetch) {
        var originalFetch = win.fetch;
        win.fetch = function () {
          var target = (arguments[0] && arguments[0].url) || arguments[0];
          return originalFetch.apply(this, arguments).then(function (response) {
            try { response.clone().text().then(function (text) { window.__tongluReport(target, text); }); } catch (e) {}
            return response;
          });
        };
      }
    };
    window.__tongluPatchAll = function () {
      window.__tongluPatch(window);
      for (var i = 0; i < window.frames.length; i++) {
        try { window.__tongluPatch(window.frames[i]); } catch (e) {}
      }
    };
  }
  try { window.__tongluPatchAll(); } catch (e) {}
  if (!window.__tongluFrameTimer) {
    window.__tongluFrameTimer = setInterval(function () { try { window.__tongluPatchAll(); } catch (e) {} }, 1200);
  }
  if (!window.__tongluProbeTimer) {
    window.__tongluProbeTimer = setInterval(function () {
      try {
        var qr = null;
        var selectors = ['#qrcode img', '#qrcode canvas', '#qrcode iframe', '.impowerBox .qrcode img', '.impowerBox .qrcode iframe', '.impowerBox .qrcode', '.impowerBox', '.weChatBox', '#qrcode'];
        for (var i = 0; i < selectors.length; i++) {
          var el = document.querySelector(selectors[i]);
          if (!el) continue;
          var box = el.getBoundingClientRect();
          if (box && box.width > 60 && box.height > 60 && box.bottom > 0 && box.right > 0 && box.top < window.innerHeight && box.left < window.innerWidth) {
            qr = { x: box.left, y: box.top, w: box.width, h: box.height };
            break;
          }
        }
        TongluBridge.probe(JSON.stringify({
          href: String(location.href || ''),
          host: String(location.host || ''),
          qr: qr,
          vx: window.visualViewport ? window.visualViewport.offsetLeft : 0,
          vy: window.visualViewport ? window.visualViewport.offsetTop : 0,
          vw: window.visualViewport ? window.visualViewport.width : window.innerWidth,
          vh: window.visualViewport ? window.visualViewport.height : window.innerHeight
        }));
      } catch (e) {}
    }, 1200);
  }
})();
"""

/**
 * 跳到第 [page] 页。
 *
 * 教务成绩网格默认每页只给 10 条（接口 `totalSize` 却是 61），让用户手动翻 6 页不现实。
 * 这里只做「页面上本来就有的那个翻页控件」的操作（填页码 / 点下一页图标），
 * 不发请求、不碰 Cookie —— 翻页后页面自己会去拿那一页，响应照旧走「抄一份」的链路。
 *
 * 教务成绩网格的翻页按钮是**图标字体**（没有「下一页」这几个字，按文字/类名都找不着），
 * 但页码输入框是标准的 input，旁边写着「跳转:」—— 改它的值再敲回车最稳。
 * 页面自己会去请求那一页，我们照旧只抄它返回的响应。
 */
/**
 * 把「分页条」的真实 DOM 结构原样落盘（走 TongluBridge.dump → files/jw/score_capture.log）。
 *
 * 教务这套网格的翻页控件是**图标字体**（按钮上没字、类名还是编译出来的），
 * 猜一次错一次，所以先把「分页条容器长什么样、里面有几个 input、有没有跳转按钮、
 * 是不是 datagrid/pagination 那类现成组件」看清楚，再决定怎么驱动它。
 */
internal const val PAGER_DUMP_SCRIPT = """
(function () {
  function walkFrames(win, depth, fn) {
    try { fn(win.document); } catch (e) {}
    if (depth > 3) return;
    for (var i = 0; i < win.frames.length; i++) {
      try { walkFrames(win.frames[i], depth + 1, fn); } catch (e) {}
    }
  }
  function deepestWith(doc, kw) {
    var best = null, bestLen = 100000000;
    var all = doc.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var t = all[i].textContent || '';
      if (t.indexOf(kw) >= 0 && t.length < bestLen) { best = all[i]; bestLen = t.length; }
    }
    return best;
  }
  function one(doc) {
    var anchor = deepestWith(doc, '总记录数');
    if (!anchor) return null;
    var node = anchor;
    for (var d = 0; d < 3 && node.parentElement; d++) node = node.parentElement;
    var out = [];
    out.push('=== 分页条容器 outerHTML ===');
    out.push((node.outerHTML || '').replace(/\s+/g, ' ').slice(0, 6000));
    var inputs = node.querySelectorAll('input');
    out.push('=== 容器内 input 数=' + inputs.length + ' ===');
    for (var i = 0; i < inputs.length; i++) {
      var el = inputs[i];
      out.push('input#' + i + ' type=' + el.type + ' cls=' + el.className + ' id=' + el.id +
        ' name=' + el.name + ' value=' + el.value + ' ph=' + el.placeholder +
        ' html=' + (el.outerHTML || '').replace(/\s+/g, ' ').slice(0, 300));
    }
    out.push('=== 容器内小元素（文字<=2 或带 title）===');
    var kids = node.querySelectorAll('*');
    var n = 0;
    for (var k = 0; k < kids.length && n < 60; k++) {
      var e = kids[k];
      var txt = (e.textContent || '').trim();
      var title = '';
      try { title = e.getAttribute('title') || e.getAttribute('aria-label') || ''; } catch (e2) {}
      if (txt.length > 2 && !title) continue;
      var cls = (typeof e.className === 'string') ? e.className : '';
      out.push(e.tagName + ' cls=' + cls + ' title=' + title + ' txt=' + txt +
        ' html=' + (e.outerHTML || '').replace(/\s+/g, ' ').slice(0, 240));
      n++;
    }
    return out.join('\n');
  }
  var out = [];
  walkFrames(window, 0, function (doc) {
    var s = one(doc);
    if (s) out.push('--- frame: ' + doc.location.href + '\n' + s);
  });
  if (out.length === 0) {
    var d2 = [];
    walkFrames(window, 0, function (doc) {
      var t = doc.body ? (doc.body.textContent || '') : '';
      d2.push('url=' + doc.location.href + ' bodyLen=' + t.length +
        ' 有总记录数=' + (t.indexOf('总记录数') >= 0) +
        ' 有跳转=' + (t.indexOf('跳转') >= 0) +
        ' input数=' + doc.querySelectorAll('input').length +
        ' 有datagrid=' + (t.indexOf('datagrid') >= 0) +
        ' 片段=' + t.slice(Math.max(0, t.indexOf('总记录数') - 200), t.indexOf('总记录数') + 200));
    });
    out.push('没找到「总记录数」：\n' + d2.join('\n'));
  }
  try { TongluBridge.dump(out.join('\n')); } catch (e) {}
})();
"""

internal fun jwGotoPageScript(page: Int): String = PAGER_DUMP_SCRIPT + """
(function () {
  /** 在主文档 + 所有能读的子框架里找第一个匹配的元素 */
  function q(win, depth, sel) {
    try { var el = win.document.querySelector(sel); if (el) return el; } catch (e) {}
    if (depth > 3) return null;
    for (var i = 0; i < win.frames.length; i++) {
      try { var r = q(win.frames[i], depth + 1, sel); if (r) return r; } catch (e) {}
    }
    return null;
  }
  /** 真·按一下：mousedown → mouseup → click，跟用户手指按在按钮上一样 */
  function hit(el) {
    ['mousedown', 'mouseup', 'click'].forEach(function (type) {
      el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window, button: 0 }));
    });
  }
  try {
    // ① 首选「下一页」。教务这套网格是 bh-pager，**每个翻页按钮都打了 pager-flag**
    //    （home/prev/next/end），按钮本身是图标字体、一个字都没有 —— 早先按文字找必然找不到，
    //    按 pager-flag 认最准，而且它就是「用户自己点了一下下一页」。
    var next = q(window, 0, '[pager-flag="next"]');
    if (next) { hit(next); TongluBridge.note('点了「下一页」'); return; }
    // ② 兜底：页码输入框（bh-pager-input / pager-flag=gotoPage）改值再敲回车
    var el = q(window, 0, 'input[pager-flag="gotoPage"]') || q(window, 0, 'input.bh-pager-input');
    if (!el) { TongluBridge.note('没找到翻页控件'); return; }
    el.focus();
    var setter = null;
    try { setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set; } catch (e) {}
    if (setter) setter.call(el, '$page'); else el.value = '$page';
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    ['keydown', 'keypress', 'keyup'].forEach(function (type) {
      var ev = new KeyboardEvent(type, { bubbles: true, cancelable: true, key: 'Enter' });
      Object.defineProperty(ev, 'keyCode', { get: function () { return 13; } });
      Object.defineProperty(ev, 'which', { get: function () { return 13; } });
      Object.defineProperty(ev, 'charCode', { get: function () { return 13; } });
      el.dispatchEvent(ev);
    });
    try { el.blur(); } catch (e) {}
    TongluBridge.note('填了第 $page 页并回车');
  } catch (e) {
    try { TongluBridge.note('跳页失败：' + e.message); } catch (e2) {}
  }
})();
"""

/**
 * 翻页探针（诊断用，不参与正式流程）：
 * 依次试用几种触发方式（原生 click / 本 realm 的 MouseEvent 三连 / jQuery trigger /
 * 触摸事件 / 页码框填 2 回车），每一步之后看「1-10 总记录数 61」这段字变没变。
 * 结果走 TongluBridge.dump 落盘 —— 一次就能确定这套 bh-pager 到底吃哪一种事件。
 */
internal const val PAGER_PROBE_SCRIPT = """
(function () {
  if (window.__tlProbing) return;
  window.__tlProbing = true;
  function q(win, depth, sel) {
    try { var el = win.document.querySelector(sel); if (el) return el; } catch (e) {}
    if (depth > 3) return null;
    for (var i = 0; i < win.frames.length; i++) {
      try { var r = q(win.frames[i], depth + 1, sel); if (r) return r; } catch (e) {}
    }
    return null;
  }
  function num() {
    var e = q(window, 0, '.bh-pager-num');
    return e ? String(e.textContent || '').trim() : '(找不到 bh-pager-num)';
  }
  function realm(el) { return el.ownerDocument.defaultView || window; }
  var steps = [];
  steps.push(['0 基线', function () {}]);
  steps.push(['1 el.click()', function (el) { el.click(); }]);
  steps.push(['2 MouseEvent 三连(本 realm)', function (el) {
    var w = realm(el);
    ['mousedown', 'mouseup', 'click'].forEach(function (t) {
      el.dispatchEvent(new w.MouseEvent(t, { bubbles: true, cancelable: true, view: w, button: 0 }));
    });
  }]);
  steps.push(['3 jQuery trigger', function (el) {
    var w = realm(el);
    if (w.jQuery) { w.jQuery(el).trigger('click'); } else { throw new Error('该 realm 没有 jQuery'); }
  }]);
  steps.push(['4 touchstart/touchend + click', function (el) {
    var w = realm(el);
    el.dispatchEvent(new w.Event('touchstart', { bubbles: true, cancelable: true }));
    el.dispatchEvent(new w.Event('touchend', { bubbles: true, cancelable: true }));
    el.dispatchEvent(new w.MouseEvent('click', { bubbles: true, cancelable: true, view: w, button: 0 }));
  }]);
  steps.push(['5 页码框填 2 + 回车', function () {
    var el = q(window, 0, 'input[pager-flag="gotoPage"]') || q(window, 0, 'input.bh-pager-input');
    if (!el) throw new Error('没有页码框');
    var w = realm(el);
    el.focus();
    var setter = Object.getOwnPropertyDescriptor(w.HTMLInputElement.prototype, 'value').set;
    if (setter) setter.call(el, '2'); else el.value = '2';
    el.dispatchEvent(new w.Event('input', { bubbles: true }));
    el.dispatchEvent(new w.Event('change', { bubbles: true }));
    ['keydown', 'keypress', 'keyup'].forEach(function (t) {
      var ev = new w.KeyboardEvent(t, { bubbles: true, cancelable: true, key: 'Enter' });
      Object.defineProperty(ev, 'keyCode', { get: function () { return 13; } });
      Object.defineProperty(ev, 'which', { get: function () { return 13; } });
      el.dispatchEvent(ev);
    });
  }]);
  var log = [];
  var i = 0;
  function step() {
    if (i >= steps.length) {
      window.__tlProbing = false;
      try { TongluBridge.dump('翻页探针：' + String(new Date()) + '\n' + log.join('\n')); } catch (e) {}
      try { TongluBridge.note('探针结束，当前 ' + num()); } catch (e) {}
      return;
    }
    var before = num();
    var name = steps[i][0];
    try { steps[i][1](q(window, 0, '[pager-flag="next"]')); } catch (e) { log.push(name + ' 抛错：' + e.message); }
    setTimeout(function () {
      var after = num();
      log.push(name + '：' + before + '  →  ' + after + (before === after ? '   （没变）' : '   ★变了'));
      i++;
      step();
    }, 2400);
  }
  step();
})();
"""

/**
 * 翻到第 [page] 页：照着页面自己那次成绩查询（URL / 请求头 / 请求体）原样再发一次，只换页码。
 *
 * 为什么不用「点下一页」：教务这套 bh-pager 的翻页按钮是图标字体，而且不吃合成事件 ——
 * 2026-09-30 用探针把 el.click() / 本 realm 的 MouseEvent 三连 / jQuery trigger /
 * touchstart+touchend / 页码框回车 全试了一遍，「1-10 总记录数 61」一次都没变，
 * 日志里也只有 pageNumber=1 那一次响应。
 *
 * 发出来的响应依旧走原来那条「抄一份」的链路（BOOTSTRAP_SCRIPT 里的 XHR 嗅探），
 * 所以解析、去重、合并全都不用改。取的是用户自己翻页看得到的那些成绩，不涉及任何凭证。
 */
internal fun jwFetchScorePageScript(page: Int): String = """
(function () {
  function locate(win, depth) {
    try {
      if (win.__tongluScoreReq) return { win: win, req: win.__tongluScoreReq };
      if (depth < 5) for (var i = 0; i < win.frames.length; i++) {
        var found = locate(win.frames[i], depth + 1); if (found) return found;
      }
    } catch (e) {}
    return null;
  }
  var source = locate(window, 0);
  var req = source && source.req;
  if (!req) { try { TongluBridge.note('还没看到成绩查询请求，先让教务页把成绩显示出来'); } catch (e) {} return; }
  function withPage(body, page, note) {
    var out = body;
    if (body.indexOf('pageNumber') >= 0) {
      out = body.replace(/"pageNumber"\s*:\s*(-?\d+)/, '"pageNumber":' + page)
                .replace(/(^|[?&])pageNumber=\d+/, '$1pageNumber=' + page);
    } else if (body.indexOf('pageNo') >= 0) {
      out = body.replace(/"pageNo"\s*:\s*(-?\d+)/, '"pageNo":' + page)
                .replace(/(^|[?&])pageNo=\d+/, '$1pageNo=' + page);
    } else if (body.indexOf('{') === 0) {
      out = body.replace('{', '{"pageNumber":' + page + ',');
      note.push('请求体里没有页码参数，插了一个进去');
    } else {
      out = body + '&pageNumber=' + page;
      note.push('请求体里没有页码参数，拼了一个在后面');
    }
    return out;
  }
  var note = [];
  try {
    var xhr = new source.win.XMLHttpRequest();
    xhr.open(req.method, req.url, true);
    var hs = req.headers || {};
    for (var k in hs) {
      if (Object.prototype.hasOwnProperty.call(hs, k)) {
        try { xhr.setRequestHeader(k, hs[k]); } catch (e) {}
      }
    }
    xhr.send(req.body === '' ? null : withPage(req.body, $page, note));
    TongluBridge.note('已请求第 $page 页' + (note.length ? '（' + note.join('；') + '）' : ''));
    TongluBridge.dump('重放第 $page 页：\n请求体=' + withPage(req.body, $page, note));
  } catch (e) {
    try { TongluBridge.note('请求第 $page 页失败：' + e.message); } catch (e2) {}
  }
})();
"""

/** 抓取当前页：收集主文档 + 所有能读的子框架 DOM，连同接口响应日志一起送到 TongluBridge.grab */
internal const val GRAB_SCRIPT = """
(function () {
  function collectFrames(win, depth, acc) {
    for (var i = 0; i < win.frames.length; i++) {
      var frame = win.frames[i];
      try {
        var doc = frame.document;
        acc.push({
          url: String(doc.URL || ''),
          html: String(doc.documentElement ? doc.documentElement.outerHTML : '').slice(0, 1200000)
        });
        if (depth < 4) collectFrames(frame, depth + 1, acc);
      } catch (e) {
        acc.push({ url: '(跨域框架，无法读取)', html: '' });
      }
    }
    return acc;
  }
  var acc = [{
    url: String(document.URL || ''),
    html: String(document.documentElement ? document.documentElement.outerHTML : '').slice(0, 1200000)
  }];
  collectFrames(window, 1, acc);
  var apiLog = [];
  try { apiLog = (window.__tongluLog || []).slice(-60); } catch (e) {}
  try {
    TongluBridge.grab(JSON.stringify({ frames: acc, apiLog: apiLog }));
  } catch (e) {
    try { TongluBridge.note('抓取失败：' + e.message); } catch (e2) {}
  }
})();
"""


