package cn.tonglu.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.NetdiskBeginUploadDto
import cn.tonglu.app.data.NetdiskObjectDto
import cn.tonglu.app.data.NetdiskRequestBody
import cn.tonglu.app.data.NetdiskShareInfoDto
import cn.tonglu.app.data.NetdiskStatusDto
import cn.tonglu.app.data.OpenListClient
import cn.tonglu.app.data.OpenListStore
import cn.tonglu.app.data.netdiskGetFromStorage
import cn.tonglu.app.data.netdiskHumanSize
import cn.tonglu.app.data.netdiskPutToStorage
import cn.tonglu.app.data.netdiskRemaining
import cn.tonglu.app.data.netdiskSharedRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 网盘的两种接法。 */
private enum class NetdiskMode {
    /** 内置：存储是我们自己的 R2 桶，后端只签发预签名链接，字节直连。用户什么都不用填。**默认**。 */
    CLOUD,

    /** 自建：用户填自己那台 OpenList 实例的地址与账号。保留给已经有实例的人。 */
    SELF,
}

/**
 * 网盘。
 *
 * ## 两种接法，为什么默认是「内置」
 * 用户明确选了内置（原话「我肯定选内置啊」）。内置这条线的存储是**我们自己的 Cloudflare R2 桶**：
 * 服务端只签发预签名 URL，**上传下载的字节都由 App 直连存储**，不占服务器带宽。
 *
 * 更早的版本把文件放在**用户自己的阿里云盘**上，那时架构被迫拧成「客户端永远拿不到直链、
 * 字节必须由服务器回流」—— 因为云盘官方禁止「帐号被多 IP 访问」，把直链交给手机就等于把
 * 每个下载者的 IP 直接送到云盘面前（官方点名「302 重定向…有封禁账户的风险」）。
 * 换成 R2（自己的桶）之后这条约束就不存在了：多客户端直连正是对象存储的用途。
 * 完整推演见 `docs/plan/31-NETDISK-INBUILT.md`，传输层见 `data/NetdiskCloud.kt`。
 *
 * 「我自己的实例」那条是**保留**的自建能力（`data/OpenList.kt`，已单独走查过），
 * 它同样不经过我们任何服务器。
 *
 * ## 三个交互细节（别当风格问题改回去）
 * - 全屏用 [Dialog] + `usePlatformDefaultWidth = false` + `decorFitsSystemWindows = false`
 *   + `safeDrawingPadding()`：底部导航是叠加层，普通整屏覆盖会被它压住。
 * - 关闭必须走 [OverlayDismissGuard.arm]：全屏层收尾那一下的 UP 会落到刚露出来的顶栏上，
 *   把首页搜索框点开（就是「点返回同时点到搜索框」）。连同内部所有 AlertDialog 一起 arm。
 * - 上传/下载现在都**不经过我们的服务器**，所以进度条反映的是「手机 ↔ 存储」的真实速度，
 *   慢网络下推到 100MB 要一两分钟，界面必须留着进度、不能看起来像卡死。
 */
@Composable
internal fun NetdiskScreen(onClose: () -> Unit, viewModel: AppViewModel) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val store = remember { OpenListStore(appContext) }
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(NetdiskMode.CLOUD) }

    // ---- 公用消息 ----
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    // ---- 内置网盘（后端）----
    var cloudStatus by remember { mutableStateOf<NetdiskStatusDto?>(null) }
    var cloudObjects by remember { mutableStateOf<List<NetdiskObjectDto>>(emptyList()) }
    var cloudLoading by remember { mutableStateOf(false) }
    var cloudUpload by remember { mutableStateOf<String?>(null) }
    var cloudPercent by remember { mutableIntStateOf(0) }
    var cloudDownloading by remember { mutableStateOf<NetdiskObjectDto?>(null) }
    var pendingDownload by remember { mutableStateOf<NetdiskObjectDto?>(null) }
    var pendingDelete by remember { mutableStateOf<NetdiskObjectDto?>(null) }

    /**
     * 上传成功后弹出的**分享卡片**。
     *
     * 为什么必须自动弹出：用户的原话是「自动设置密码，自动设置链接」——
     * 分享是「上传」的**自然结果**，不是需要额外发现的功能。
     * 少这一步，就退回到「只能在同一个 App 里点下载」，那网盘就没有意义了。
     */
    var shareCard by remember { mutableStateOf<NetdiskShareInfoDto?>(null) }
    var shareRenewing by remember { mutableStateOf(false) }

    fun copyToClipboard(label: String, text: String) {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            hint = "复制不了，请长按手动选"
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        hint = "已复制$label"
    }

    // ---- 自建实例（保留能力）----
    // The built-in drive does not use OpenList credentials. Never open its encrypted
    // container in composition: a broken/restored keyset would crash even the cloud tab.
    var saved by remember { mutableStateOf<OpenListStore.Account?>(null) }
    val account = saved
    var client by remember { mutableStateOf<OpenListClient?>(null) }
    var selfCredentialsLoaded by remember { mutableStateOf(false) }
    var selfCredentialsLoading by remember { mutableStateOf(false) }
    var selfCredentialsError by remember { mutableStateOf<String?>(null) }
    var selfReadAttempt by remember { mutableIntStateOf(0) }
    var base by remember { mutableStateOf(account?.base.orEmpty()) }
    var username by remember { mutableStateOf(account?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    var path by remember(account) { mutableStateOf(account?.root ?: "/") }
    var entries by remember(account) { mutableStateOf<List<OpenListClient.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var uploadName by remember { mutableStateOf<String?>(null) }
    var uploadPercent by remember { mutableIntStateOf(0) }
    var selfPendingDelete by remember { mutableStateOf<OpenListClient.Entry?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val close = {
        OverlayDismissGuard.arm()
        onClose()
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(4000)
            hint = null
        }
    }

    // ------------------------------------------------------------ 内置网盘

    fun reloadCloud() {
        cloudLoading = true
        error = null
        viewModel.netdiskCall({ message ->
            error = message
            cloudLoading = false
        }) { api, bearer ->
            val status = api.netdiskStatus(bearer)
            val list = api.netdiskObjects(bearer)
            cloudStatus = status
            cloudObjects = list.objects.orEmpty()
            cloudLoading = false
        }
    }

    /**
     * 内置网盘的主体。
     *
     * 注意它是 `LazyListScope` 的**局部扩展函数**、不是 `@Composable`：
     * `LazyColumn` 的 content 参数不是 composable 作用域，直接写 `@Composable fun` 编译不过。
     * 选择器由调用方以 `onPickFile` 传入 —— 局部 `val` 不能前向引用。
     */
    fun androidx.compose.foundation.lazy.LazyListScope.cloudBody(
        onPickFile: () -> Unit,
        onDownload: (NetdiskObjectDto) -> Unit,
        onShare: (NetdiskObjectDto) -> Unit,
    ) {
        val status = cloudStatus
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("临时互传 · 小文件", style = MaterialTheme.typography.titleMedium)
                    Text(
                        // 「24 小时内可以反复下载」这句必须写：分享链接本身只活 10 分钟，
                        // 不写清楚，用户会把「链接过期」读成「文件没了」，那这个网盘就不敢用了。
                        "上传的文件最多保留 ${status?.ttlHours ?: 24} 小时，到期自动销毁；" +
                            "这期间可以反复下载、下载多少次都行。" +
                            "文件存在我们的对象存储上，不占你手机空间，上传下载都不经过我们的服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status != null) {
                        // 分两行说清**两个不同的「已用」**：
                        //  · 第一行是**我自己**的文件（谁传的归谁，别人一行都看不见）；
                        //  · 第二行是**所有人共用**的临时空间 —— 这一行的总量才是用户问的「2G」。
                        // 2026-10-18 反馈「说好 2G，显示 1.95G」是**单位口径**问题：服务端曾按
                        // 十进制配 2000MB，而这里按 1024 进制格式化 → 1.95 GB。现在服务端按
                        // 字节配 2GiB（2147483648 B），并且总量/已用/剩余用同一套格式化，
                        // 界面上三个数不会出现两种单位。
                        Text(
                            "我的文件：已用 ${netdiskHumanSize(status.usedBytes)}" +
                                " · 在存 ${status.liveObjects} 个" +
                                " · 单个上限 ${netdiskHumanSize(status.maxFileBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val sharedPercent = if (status.maxTotalBytes > 0) {
                            (status.sharedUsedBytes * 100 / status.maxTotalBytes).toInt()
                        } else {
                            0
                        }
                        Text(
                            "临时空间（所有人共用）· 总量 ${netdiskHumanSize(status.maxTotalBytes)}" +
                                " · 已用 ${netdiskHumanSize(status.sharedUsedBytes)}" +
                                " · 剩余 ${netdiskHumanSize(netdiskSharedRemaining(status))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sharedPercent >= 80) {
                            Text(
                                "临时空间快满了，稍后再试（内容 24 小时内自动清除）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (!status.storageReachable) {
                            Text("存储暂时连不上，上传会失败", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (cloudLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPickFile,
                    enabled = cloudUpload == null,
                    modifier = Modifier.weight(1f),
                ) { Text("上传文件") }
                OutlinedButton(onClick = { reloadCloud() }, enabled = cloudUpload == null) { Text("刷新") }
            }
        }
        cloudUpload?.let { name ->
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("正在上传 $name · $cloudPercent%", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { cloudPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        cloudDownloading?.let { item ->
            item {
                Text("正在下载 ${item.name}…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!cloudLoading && cloudObjects.isEmpty()) {
            item {
                Text(
                    "现在还没有文件。上传一个，别人登录后就能在这里看到并下载；24 小时后自动消失。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(cloudObjects, key = { it.id }) { item ->
            ListItem(
                headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text("${netdiskHumanSize(item.size)} · ${netdiskRemaining(item.remainingMs)}")
                },
                leadingContent = {
                    Icon(Icons.Outlined.InsertDriveFile, contentDescription = null)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 「分享」排在「下载」前面：这是网盘的主要用途，
                        // 只在自己设备上下过的网盘等于没网盘（用户 2026-10-03 的原话）。
                        TextButton(
                            onClick = { onShare(item) },
                            // ⚠️ 别写 `shareRenewing == null`：它是 Boolean，那个比较**恒为 false**，
                            // 于是每一行的「分享」都是禁用的 —— 界面上看不出来（文字不灰到能察觉），
                            // 点了没反应，走查才会暴露。Kotlin 只给警告不给错，编译照样过。
                            enabled = cloudDownloading == null && !shareRenewing,
                        ) { Text("分享") }
                        TextButton(
                            onClick = { onDownload(item) },
                            enabled = cloudDownloading == null,
                        ) { Text("下载") }
                        TextButton(onClick = { pendingDelete = item }) { Text("删除") }
                    }
                },
            )
        }
    }

    /**
     * 上传三步：**申请 → 直传存储 → 确认**。
     *
     * 为什么不是一个请求搞定：直传要求客户端先拿到一条带签名的地址，
     * 而「这条地址属于哪次上传、能不能给」只有服务端知道（要过配额与大小上限）。
     * 中间那一步的字节**完全绕开我们的服务器**，代价是必须有个第三步来复核 ——
     * 因为声明的大小是客户端说了算的，服务端只能事后再 HEAD 一次。
     *
     * 失败时不管卡在哪一步，都不留垃圾：服务端对未确认（PENDING）的行有专门清理，
     * 而且确认那一步发现对象不存在/超限时会顺手把对象删掉。
     */
    val pickCloudFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(appContext, uri)
        val body = NetdiskRequestBody(appContext.contentResolver, uri) { written, total ->
            if (total > 0) {
                val percent = ((written * 100) / total).toInt()
                if (percent != cloudPercent) cloudPercent = percent
            }
        }
        val size = body.contentLength()
        if (size <= 0L) {
            error = "读不到这个文件的大小，换一个文件试试"
            return@rememberLauncherForActivityResult
        }
        cloudUpload = name
        cloudPercent = 0
        error = null
        hint = null
        viewModel.netdiskCall({ message ->
            error = message
            cloudUpload = null
        }) { api, bearer ->
            // ① 申请：服务端校配额、落一条 PENDING 行、返回预签名 PUT 地址
            val ticket = api.netdiskBeginUpload(
                bearer,
                NetdiskBeginUploadDto(name = name, size = size),
            )
            // ② 直传：字节从手机直接到存储（在 IO 线程上跑，别占主线程）
            withContext(Dispatchers.IO) {
                netdiskPutToStorage(ticket.uploadUrl, body)
            }
            // ③ 确认：服务端 HEAD 复核真实大小，通过后才进列表。
            //    回执里已经带了自动生成的**链接 + 提取码** —— 分享是上传的自然结果，
            //    不该让用户再去列表里找一个「分享」按钮才发现。
            val done = api.netdiskCompleteUpload(bearer, ticket.id)
            cloudUpload = null
            reloadCloud()
            if (!done.shareUrl.isNullOrBlank()) {
                shareCard = NetdiskShareInfoDto(
                    id = done.id,
                    name = done.name,
                    shareUrl = done.shareUrl,
                    shareCode = done.shareCode.orEmpty(),
                    shareViews = 0,
                    shareViewedAt = null,
                    remainingMs = done.remainingMs,
                )
            }
        }
    }

    val saveCloud = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val target = pendingDownload
        pendingDownload = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        cloudDownloading = target
        error = null
        viewModel.netdiskCall({ message ->
            error = message
            cloudDownloading = null
        }) { api, bearer ->
            // ① 取一条 10 分钟的直链（capability，短时有效是刻意的）
            val link = api.netdiskObjectUrl(bearer, target.id)
            // ② 直接从存储取字节写进用户选的位置 —— 不经过我们的服务器
            withContext(Dispatchers.IO) {
                val out = appContext.contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("写不进所选位置")
                out.use { sink -> netdiskGetFromStorage(link.url, sink) }
            }
            cloudDownloading = null
            hint = "已保存 ${target.name}"
        }
    }

    fun deleteCloud(item: NetdiskObjectDto) {
        error = null
        hint = null
        viewModel.netdiskCall({ message -> error = message }) { api, bearer ->
            api.netdiskDelete(bearer, item.id)
            hint = "已删除 ${item.name}"
            reloadCloud()
        }
    }

    /**
     * 打开某个文件的分享卡片。
     *
     * 先用**列表里已有的** `shareUrl`（不额外发请求）；没有才去 `ensure` 补一把 ——
     * 上传回执带回来的、但用户可能没注意到卡片就关掉了，所以这里要能随时补。
     */
    fun shareCloud(item: NetdiskObjectDto) {
        val known = item.shareUrl
        if (!known.isNullOrBlank()) {
            shareCard = NetdiskShareInfoDto(
                id = item.id,
                name = item.name,
                shareUrl = known,
                shareCode = item.shareCode.orEmpty(),
                shareViews = item.shareViews,
                shareViewedAt = item.shareViewedAt,
                remainingMs = item.remainingMs,
            )
            return
        }
        error = null
        viewModel.netdiskCall({ message -> error = message }) { api, bearer ->
            shareCard = api.netdiskEnsureShare(bearer, item.id)
            reloadCloud()
        }
    }

    /** 换一把新钥匙（链接发错人时用）。旧链接立刻失效，文件本身不动。 */
    fun renewShareCard() {
        val card = shareCard ?: return
        shareRenewing = true
        error = null
        viewModel.netdiskCall(
            { message -> error = message; shareRenewing = false },
        ) { api, bearer ->
            shareCard = api.netdiskRenewShare(bearer, card.id)
            shareRenewing = false
            hint = "已换新链接，旧链接已作废"
            reloadCloud()
        }
    }

    // ------------------------------------------------------------ 自建实例（保留）

    suspend fun report(e: Exception, fallback: String) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        val expired = e is OpenListClient.OpenListException && e.unauthorized
        if (expired) {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                saved = null
                client = null
                entries = emptyList()
                error = "登录已过期，请重新连接"
            } catch (clearError: Exception) {
                if (clearError is kotlinx.coroutines.CancellationException) throw clearError
                Log.w("TongluNetdisk", "自建凭据清除失败：${clearError.javaClass.simpleName}")
                error = "登录已过期，但本机登录信息未能清除，请稍后重试"
            }
        } else {
            error = e.message ?: fallback
        }
    }

    fun currentClient(): OpenListClient? {
        val c = client
        if (c == null) error = "请先连接网盘"
        return c
    }

    LaunchedEffect(mode) {
        if (mode == NetdiskMode.CLOUD && cloudStatus == null) reloadCloud()
    }

    LaunchedEffect(mode, selfReadAttempt) {
        if (mode != NetdiskMode.SELF || selfCredentialsLoaded) return@LaunchedEffect
        selfCredentialsLoading = true
        selfCredentialsError = null
        try {
            val restored = withContext(Dispatchers.IO) {
                val stored = store.read()
                stored to stored?.let { OpenListClient(it.base, it.token) }
            }
            saved = restored.first
            client = restored.second
            base = restored.first?.base.orEmpty()
            username = restored.first?.username.orEmpty()
            selfCredentialsLoaded = true
        } catch (readError: Exception) {
            if (readError is kotlinx.coroutines.CancellationException) throw readError
            // Keep the encrypted container and shared MasterKey intact; no automatic reset.
            Log.w("TongluNetdisk", "自建凭据读取失败：${readError.javaClass.simpleName}")
            selfCredentialsError = "自建网盘的本机登录信息暂时无法读取，内置网盘仍可使用。"
        } finally {
            selfCredentialsLoading = false
        }
    }

    LaunchedEffect(client, path, mode) {
        if (mode != NetdiskMode.SELF) return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { c.list(path) }.sortedForDisplay()
        } catch (e: Exception) {
            entries = emptyList()
            report(e, "读取目录失败")
        } finally {
            loading = false
        }
    }

    fun doLogin() {
        val address = base.trim()
        val user = username.trim()
        val secret = password
        when {
            address.isEmpty() -> error = "请填写服务器地址"
            user.isEmpty() -> error = "请填写账号"
            secret.isEmpty() -> error = "请填写口令"
            else -> {
                loggingIn = true
                error = null
                scope.launch {
                    try {
                        val connected = withContext(Dispatchers.IO) {
                            val probe = OpenListClient(address)
                            val result = probe.login(user, secret)
                            store.saveSession(probe.base, user, result.token, result.root)
                            val stored = store.read() ?: throw java.io.IOException("登录信息未能保存，请重试")
                            stored to OpenListClient(stored.base, stored.token)
                        }
                        password = ""
                        saved = connected.first
                        client = connected.second
                    } catch (e: Exception) {
                        report(e, "连接失败")
                    } finally {
                        loggingIn = false
                    }
                }
            }
        }
    }

    fun doLogout() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                entries = emptyList()
                error = null
                hint = null
                saved = null
                client = null
            } catch (clearError: Exception) {
                if (clearError is kotlinx.coroutines.CancellationException) throw clearError
                Log.w("TongluNetdisk", "自建退出失败：${clearError.javaClass.simpleName}")
                error = "本机登录信息未能清除，请稍后重试"
            }
        }
    }

    fun refreshSelf() {
        val c = currentClient() ?: return
        val dir = path
        scope.launch {
            loading = true
            error = null
            try {
                entries = withContext(Dispatchers.IO) { c.list(dir) }.sortedForDisplay()
            } catch (e: Exception) {
                report(e, "刷新失败")
            } finally {
                loading = false
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val c = client
        if (uri == null) return@rememberLauncherForActivityResult
        if (c == null) {
            error = "请先连接网盘"
            return@rememberLauncherForActivityResult
        }
        val name = displayName(appContext, uri)
        val dir = path
        scope.launch {
            uploadName = name
            uploadPercent = 0
            error = null
            hint = null
            try {
                withContext(Dispatchers.IO) {
                    c.upload(dir, name, appContext.contentResolver, uri) { written, total ->
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt()
                            if (percent != uploadPercent) uploadPercent = percent
                        }
                    }
                }
                entries = withContext(Dispatchers.IO) { c.list(dir) }.sortedForDisplay()
                hint = "已上传 $name"
            } catch (e: Exception) {
                report(e, "上传失败")
            } finally {
                uploadName = null
            }
        }
    }

    fun doDelete(entry: OpenListClient.Entry) {
        val c = currentClient() ?: return
        val dir = path
        scope.launch {
            error = null
            hint = null
            try {
                withContext(Dispatchers.IO) { c.remove(dir, listOf(entry.name)) }
                entries = withContext(Dispatchers.IO) { c.list(dir) }.sortedForDisplay()
                hint = "已删除 ${entry.name}"
            } catch (e: Exception) {
                report(e, "删除失败")
            }
        }
    }

    fun doMkdir() {
        val c = currentClient() ?: return
        val name = newFolderName.trim()
        if (name.isEmpty()) {
            error = "文件夹名不能为空"
            return
        }
        val dir = path
        newFolderOpen = false
        newFolderName = ""
        scope.launch {
            error = null
            hint = null
            try {
                withContext(Dispatchers.IO) { c.mkdir(OpenListClient.join(dir, name)) }
                entries = withContext(Dispatchers.IO) { c.list(dir) }.sortedForDisplay()
                hint = "已新建 $name"
            } catch (e: Exception) {
                report(e, "新建失败")
            }
        }
    }

    fun copyLink(entry: OpenListClient.Entry) {
        val c = currentClient() ?: return
        val target = OpenListClient.join(path, entry.name)
        scope.launch {
            error = null
            hint = null
            try {
                val url = withContext(Dispatchers.IO) { c.directLink(target) }
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("直链", url))
                hint = "已复制直链，可以粘贴到浏览器或发给别人"
            } catch (e: Exception) {
                report(e, "取直链失败")
            }
        }
    }

    // ------------------------------------------------------------ 界面

    // 弹层自识别左边缘拖动：Dialog 是独立 Window，系统预测性返回的进度进不来。
    // 这一层原先只有 `onDismissRequest`，等于「返回键能关，但一点动画都没有」——
    // 正是用户说的「有的界面还没适配」。
    val motion = rememberOverlayMotion(
        onClose = close,
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回首页") }
                    Column(Modifier.weight(1f)) {
                        Text("网盘", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (mode == NetdiskMode.CLOUD) "小文件临时互传 · 24 小时内自动销毁"
                            else "连接你自己的 OpenList · 文件不经我们服务器",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (mode == NetdiskMode.SELF && account != null) {
                        TextButton(onClick = { doLogout() }) { Text("退出登录") }
                    }
                }

                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = mode == NetdiskMode.CLOUD,
                        onClick = { mode = NetdiskMode.CLOUD },
                        label = { Text("内置网盘") },
                    )
                    FilterChip(
                        selected = mode == NetdiskMode.SELF,
                        onClick = { mode = NetdiskMode.SELF },
                        label = { Text("我自己的实例") },
                    )
                }

                // 错误与提示放在列表**外面**：目录很长时，放在列表尾部等于用户看不见。
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    hint?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }

                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (mode == NetdiskMode.CLOUD) {
                        cloudBody(
                            onPickFile = { pickCloudFile.launch(arrayOf("*/*")) },
                            onDownload = { item -> pendingDownload = item; saveCloud.launch(item.name) },
                            onShare = { item -> shareCloud(item) },
                        )
                    } else if (selfCredentialsLoading) {
                        item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    } else if (selfCredentialsError != null) {
                        item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(selfCredentialsError.orEmpty())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { selfReadAttempt++ }) { Text("重试读取") }
                                        TextButton(onClick = { mode = NetdiskMode.CLOUD }) { Text("使用内置网盘") }
                                    }
                                }
                            }
                        }
                    } else if (account == null) {
                        item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("连接你的网盘", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "填你自己 OpenList（或 Alist）实例的地址与账号。地址、账号、口令只存在这台手机上，" +
                                            "不上传、不进日志；文件从手机直连你的服务器。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        value = base,
                                        onValueChange = { base = it },
                                        label = { Text("服务器地址") },
                                        placeholder = { Text("https://你的服务器地址") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = username,
                                        onValueChange = { username = it },
                                        label = { Text("账号") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = password,
                                        onValueChange = { password = it },
                                        label = { Text("口令") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Button(
                                        onClick = { doLogin() },
                                        enabled = !loggingIn,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(if (loggingIn) "连接中…" else "连接") }
                                    if (loggingIn) LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    } else {
                        item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        "${account.username} @ ${account.base}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (path == account.root) "根目录 ${account.root}" else path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { pickFile.launch(arrayOf("*/*")) },
                                    enabled = uploadName == null,
                                    modifier = Modifier.weight(1f),
                                ) { Text("上传文件") }
                                OutlinedButton(
                                    onClick = { newFolderName = ""; newFolderOpen = true },
                                    enabled = uploadName == null,
                                ) { Text("新建文件夹") }
                                IconButton(onClick = { refreshSelf() }, enabled = uploadName == null) {
                                    Icon(Icons.Outlined.Refresh, "刷新目录")
                                }
                            }
                        }
                        uploadName?.let { name ->
                            item {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "正在上传 $name · $uploadPercent%",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    LinearProgressIndicator(
                                        progress = { uploadPercent / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        if (path != account.root) {
                            item {
                                TextButton(onClick = { OpenListClient.parentOf(path)?.let { path = it } }) {
                                    Icon(Icons.Outlined.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("上级目录")
                                }
                            }
                        }
                        if (!loading && entries.isEmpty()) {
                            item {
                                Text(
                                    "这个目录还是空的。可以上传文件，或新建一个文件夹。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(entries, key = { it.name }) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(if (entry.isDir) "文件夹" else OpenListClient.humanSize(entry.size))
                                },
                                leadingContent = {
                                    Icon(
                                        if (entry.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                        contentDescription = null,
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!entry.isDir) {
                                            TextButton(onClick = { copyLink(entry) }) { Text("直链") }
                                        }
                                        TextButton(onClick = { selfPendingDelete = entry }) { Text("删除") }
                                    }
                                },
                                modifier = Modifier.clickable {
                                    if (entry.isDir) path = OpenListClient.join(path, entry.name)
                                },
                            )
                        }
                    }

                    item {
                        Text(
                            if (mode == NetdiskMode.CLOUD) {
                                "内置网盘最长留存 24 小时，到期自动销毁；" +
                                    "请不要放长期资料或隐私文件。"
                            } else {
                                "网盘走的是自建实例：谁有服务器谁就能用一台，我们不提供公共存储，也不经手任何文件。" +
                                    "上传的文件保留在你自己的服务器上，卸载 App 不会删掉它们。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        pendingDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { OverlayDismissGuard.arm(); pendingDelete = null },
                title = { Text("删除「${item.name}」？") },
                text = { Text("删除后无法恢复（本来也会在到期时自动销毁）。") },
                confirmButton = {
                    TextButton(onClick = {
                        OverlayDismissGuard.arm()
                        pendingDelete = null
                        deleteCloud(item)
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { OverlayDismissGuard.arm(); pendingDelete = null }) { Text("取消") }
                },
            )
        }

        /**
         * 分享卡片。上传完自动弹、列表行点「分享」也能再打开 —— 所以它**不能**是一次性提示。
         *
         * 文案上刻意强调「24 小时内随便下」：用户最容易误会的是把「取件码 10 分钟」
         * 读成「文件 10 分钟就没了」，那样就等于我们的网盘不敢用。
         */
        shareCard?.let { card ->
            // 倒计时：**起算值由服务端给**（remainingMs），这里只按 30 秒一格往下数。
            // 刻意不做「到点自动关卡片」—— 卡片关掉不等于文件销毁，
            // 反倒会让人以为「弹窗关了 = 链接还在」，还是让它把 0 明明白白显示出来。
            var elapsedMs by remember(card.shareUrl) { mutableStateOf(0L) }
            LaunchedEffect(card.shareUrl) {
                while (true) {
                    delay(30_000)
                    elapsedMs += 30_000
                }
            }
            val leftMs = (card.remainingMs - elapsedMs).coerceAtLeast(0L)
            Dialog(
                onDismissRequest = { OverlayDismissGuard.arm(); shareCard = null },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (card.name.isNotBlank()) card.name else "把这两样发给对方",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "对方点开链接、填 4 位提取码就能下 —— 不用装同路、不用登录。" +
                                "30 分钟内最多换 5 次下载链接，文件本身最长保留 24 小时。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 倒计时。写「还有 X 后自动销毁」而不是只写「24 小时」：
                        // 用户要的就是看着它在走，而且他知道什么时候该催对方下。
                        Text(
                            if (leftMs <= 0L) {
                                "已到销毁时间，文件马上从列表消失"
                            } else {
                                "${netdiskRemaining(leftMs)}后自动销毁，这期间可以反复下载"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (leftMs <= 0L) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        // 一键复制两样：发给别人时最常见的动作是「链接 + 提取码一起发」，
                        // 分成两个小「复制」按钮就得粘两次、还容易漏掉提取码（2026-10-03 用户反馈）。
                        Button(
                            onClick = {
                                copyToClipboard(
                                    "链接和提取码",
                                    "链接：${card.shareUrl}\n提取码：${card.shareCode}",
                                )
                            },
                            enabled = card.shareUrl.isNotBlank() && card.shareCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("复制链接和提取码") }
                        ShareField(
                            label = "链接",
                            value = card.shareUrl,
                            onCopy = { copyToClipboard("链接", card.shareUrl) },
                        )
                        ShareField(
                            label = "提取码",
                            value = card.shareCode,
                            onCopy = { copyToClipboard("提取码", card.shareCode) },
                        )
                        if (card.shareViews > 0) {
                            Text(
                                "已有 ${card.shareViews} 人下载过",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { renewShareCard() }, enabled = !shareRenewing) {
                                Text(if (shareRenewing) "换码中…" else "换一把链接")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                OverlayDismissGuard.arm(); shareCard = null
                            }) { Text("完成") }
                        }
                    }
                }
            }
        }

        selfPendingDelete?.let { entry ->
            AlertDialog(
                onDismissRequest = { OverlayDismissGuard.arm(); selfPendingDelete = null },
                title = { Text("删除「${entry.name}」？") },
                text = {
                    Text(
                        if (entry.isDir) "会连同里面的全部内容一起删除，无法恢复。"
                        else "删除后无法恢复。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        OverlayDismissGuard.arm()
                        selfPendingDelete = null
                        doDelete(entry)
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { OverlayDismissGuard.arm(); selfPendingDelete = null }) { Text("取消") }
                },
            )
        }

        if (newFolderOpen) {
            AlertDialog(
                onDismissRequest = { OverlayDismissGuard.arm(); newFolderOpen = false },
                title = { Text("新建文件夹") },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it.take(80) },
                        label = { Text("名称") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { OverlayDismissGuard.arm(); doMkdir() }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { OverlayDismissGuard.arm(); newFolderOpen = false }) { Text("取消") }
                },
            )
        }
    }
}

/** 文件夹在前、其余按名字排 —— 和文件管理器一致，别按上传时间乱排。 */
private fun List<OpenListClient.Entry>.sortedForDisplay(): List<OpenListClient.Entry> =
    sortedWith(
        compareByDescending<OpenListClient.Entry> { it.isDir }
            .thenBy { it.name.lowercase() },
    )

/**
 * 从 `content://` 取显示文件名。
 *
 * 一定要 `substringAfterLast('/')`：少数 provider 会给出带路径的名字，
 * 而这个名字会被拼进存储路径里。
 */
private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()
    return fromProvider?.trim()?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "上传文件"
}

/**
 * 分享卡片里的一行「标签 + 值 + 复制」。
 *
 * 值用 `TextField` 而不是 `Text`：链接很长，用户要能**长按全选**再复制，
 * 而且必须一眼看清（等宽字体 + 可滚动），不然发出去常漏掉后半段。
 * `readOnly` 而非 `enabled=false` —— 后者灰字且不能选。
 */
@Composable
private fun ShareField(label: String, value: String, onCopy: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = onCopy, enabled = value.isNotBlank()) { Text("复制") }
        },
    )
}
