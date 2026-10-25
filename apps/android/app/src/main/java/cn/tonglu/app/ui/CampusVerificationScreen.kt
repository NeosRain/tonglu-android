package cn.tonglu.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.PreparedProofPhoto
import cn.tonglu.app.data.ProofPhoto
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun CampusVerificationScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit, onLogin: () -> Unit) {
    // 返回由外层统一处理，本页表单没有独立的返回层级。
    val context = LocalContext.current
    val identity = state.campusIdentity
    val busy = state.identityBusy
    val lifecycleOwner = LocalLifecycleOwner.current
    val blue = AppColors.Accent
    var method by rememberSaveable(state.session?.sessionId) { mutableStateOf("CHSI") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // 证明材料不写 SavedState、SharedPreferences 或 Room。
    var name by remember { mutableStateOf("") }
    var reportCode by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var consent by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf("") }
    var countdownEnd by rememberSaveable { mutableLongStateOf(0L) }
    var seconds by remember { mutableIntStateOf(0) }
    var withdrawId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) { photo = it; consent = false } }
    var photoLoading by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf<String?>(null) }
    val preparedResult by produceState<Pair<Uri, PreparedProofPhoto>?>(initialValue = null, key1 = photo) {
        value = null
        photoError = null
        val target = photo
        photoLoading = target != null
        try { if (target != null) value = target to withContext(Dispatchers.IO) { ProofPhoto.prepare(context.applicationContext, target) } }
        catch (error: Exception) { if (error is CancellationException) throw error; photoError = error.message ?: "照片无法读取，请重新选择" }
        finally { photoLoading = false }
    }
    // URI 切换后的重组也不能短暂使用上一张照片的预览或上传内容。
    val prepared = preparedResult?.takeIf { it.first == photo }?.second
    DisposableEffect(lifecycleOwner, state.session?.sessionId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && state.session != null) viewModel.loadCampusIdentity() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.session?.sessionId) { if (state.session != null) viewModel.loadCampusIdentity() }
    LaunchedEffect(countdownEnd) {
        while (true) {
            seconds = ((countdownEnd - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
            if (seconds == 0) break
            delay(500)
        }
    }
    fun openOfficial(url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    Surface(Modifier.fillMaxSize(), color = AppColors.Canvas) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("校园身份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (state.session != null) TextButton(onClick = viewModel::loadCampusIdentity, enabled = !busy && !state.identityLoading) { Text("刷新") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.session == null) {
                    MiuixCard(insideMargin = PaddingValues(18.dp)) {
                        Text("先登录应用账号", style = MaterialTheme.typography.titleMedium)
                        Text("认证结果与应用账号关联。本机课表和成绩仍可直接使用。", modifier = Modifier.padding(vertical = 12.dp))
                        Button(onClick = onLogin) { Text("登录 / 注册") }
                    }
                } else {
                    MiuixCard(insideMargin = PaddingValues(18.dp)) {
                        Text(identity?.summary() ?: "认证信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        identity?.validUntil?.let { Text("在读身份复核期限：${it.take(10)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                        Text("任选适合你的方式。校园邮箱证明学校关联，学籍报告与人工材料用于核验在读身份。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                        if (state.identityLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = blue)
                    }
                    state.identityMessage?.let { Text(it, Modifier.fillMaxWidth().background(blue.copy(alpha = .08f), RoundedCornerShape(14.dp)).padding(14.dp), color = blue) }
                    if (identity != null) {
                        val pending = identity.applications.any { it.status == "PENDING" }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("CHSI" to "学信网报告", "MANUAL" to "人工认证", "EMAIL" to "校园邮箱").forEach { (key, title) ->
                                FilterChip(selected = method == key, onClick = { method = key; consent = false }, label = { Text(title) }, enabled = !busy)
                            }
                        }
                        MiuixCard(insideMargin = PaddingValues(18.dp)) {
                            if (method == "EMAIL") {
                                Text("校园邮箱", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (!identity.emailAvailable) Text("学校邮箱域名或发信通道尚未配置。可以先选择学信网报告或人工认证。", Modifier.padding(top = 10.dp))
                                else {
                                    Text("支持：${identity.campusEmailDomains.joinToString { "@$it" }}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                                    OutlinedTextField(email, { email = it.take(254) }, label = { Text("学校邮箱") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy)
                                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("验证码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !busy)
                                        TextButton(onClick = {
                                            val recipient = email.trim().lowercase()
                                            viewModel.requestCampusEmailCode(recipient) { ok -> if (ok) { sentTo = recipient; countdownEnd = System.currentTimeMillis() + 60000 } }
                                        }, enabled = !busy && seconds == 0 && email.isNotBlank()) { Text(if (seconds > 0) "${seconds}s" else "发送") }
                                    }
                                    Button(onClick = { viewModel.bindCampusEmail(email, code) }, enabled = !busy && code.length == 6 && sentTo == email.trim().lowercase(), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("验证邮箱") }
                                }
                            } else {
                                Text(if (method == "CHSI") "学籍在线验证报告" else "人工核验材料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(identity.schoolName, color = blue, modifier = Modifier.padding(top = 8.dp))
                                if (method == "CHSI") {
                                    Text("自行在学信网申请学籍报告，提供报告在线验证码。审核员会在官网核对，不需要你的学信网密码。", modifier = Modifier.padding(top = 10.dp))
                                    TextButton(onClick = { openOfficial("https://www.chsi.com.cn/xlcx/rhsq.jsp") }) { Text("打开学信网官方申请说明") }
                                } else Text("选择学生证、电子校园卡或在读证明照片，遮住与认证无关的信息。上传时会缩小图片并移除照片位置元数据。", modifier = Modifier.padding(top = 10.dp))
                                OutlinedTextField(name, { name = it.take(40) }, label = { Text("证明上的姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy && !pending)
                                if (method == "CHSI") OutlinedTextField(reportCode, { reportCode = it.filter(Char::isLetterOrDigit).take(16) }, label = { Text("16 位在线验证码") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !busy && !pending)
                                else {
                                    OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !busy && !pending && identity.manualAvailable, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(if (photo == null) "选择一张证明照片" else "已选照片 · 重新选择") }
                                    if (photoLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = blue)
                                    prepared?.let { proof ->
                                        Image(proof.preview.asImageBitmap(), "将提交的证明照片", modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 12.dp), contentScale = ContentScale.Fit)
                                        TextButton(onClick = { photo = null; consent = false }, enabled = !busy) { Text("移除照片") }
                                    }
                                    photoError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                                    if (!identity.manualAvailable) Text("人工材料通道尚未配置，请使用学信网报告", color = MaterialTheme.colorScheme.error)
                                }
                                OutlinedTextField(note, { note = it.take(500) }, label = { Text("补充说明（可选）") }, maxLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !busy && !pending)
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy && !pending)
                                    Text("同意仅用于校园身份核验，不公开姓名或材料。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                }
                                Text("未审材料最长保留 30 天，处理后保留 7 天供复核。撤回即移除材料；认证结果与审核记录继续保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val valid = consent && name.trim().length >= 2 && (if (method == "CHSI") reportCode.length == 16 else prepared != null && !photoLoading && identity.manualAvailable)
                                Button(onClick = {
                                    viewModel.submitCampusVerification(method, name, reportCode, note, prepared?.jpeg) { ok -> if (ok) { name = ""; reportCode = ""; note = ""; photo = null; consent = false } }
                                }, enabled = valid && !busy && !pending, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text(if (busy) "正在处理…" else if (pending) "已有申请待审核" else "提交审核") }
                            }
                        }
                        if (identity.applications.isNotEmpty()) {
                            Text("申请记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            identity.applications.forEach { application ->
                                MiuixCard(insideMargin = PaddingValues(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (application.method == "CHSI") "学信网报告" else "人工认证", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                        Text(application.statusLabel(), color = blue, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text("${application.schoolName} · ${application.createdAt.take(10)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                                    application.decision?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                                    if (application.status == "PENDING") TextButton(onClick = { withdrawId = application.id }, enabled = !busy) { Text("撤回申请") }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (withdrawId != null) AlertDialog(onDismissRequest = { withdrawId = null }, title = { Text("撤回申请？") }, text = { Text("原始材料将移除。你可以重新补充材料后提交。") }, confirmButton = { TextButton(onClick = { withdrawId?.let(viewModel::withdrawCampusVerification); withdrawId = null }) { Text("撤回") } }, dismissButton = { TextButton(onClick = { withdrawId = null }) { Text("取消") } })
}
