package cn.tonglu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.ai.AssistantPersona
import cn.tonglu.app.data.english.AiScope
import cn.tonglu.app.data.english.AiScopeThreads
import cn.tonglu.app.data.english.EnglishAi
import cn.tonglu.app.data.english.EnglishAiConfig
import cn.tonglu.app.data.english.EnglishAiException
import cn.tonglu.app.data.english.EnglishAiTurn
import cn.tonglu.app.data.english.aiScopeTurns
import kotlinx.coroutines.launch

/**
 * 通用的「问 AI」全屏面板（docs/plan/56 §6.2 通用问答 / §6.3 A2）。
 *
 * 与单词讲解是两个入口，但共用同一套底层（EnglishAi.chat + 加密存储的 Key）。
 *
 * 界面上必须**显式写出正在问什么**（「正在问：第 3 题」）—— 这是 §6.2 的硬要求，
 * 不是装饰：用户得知道 AI 看到的是哪一段，不能让它看起来像读了全书。
 *
 * 没配 Key：底部直接给「去设置」，不发请求。
 * 失败：把厂商的原因说出来（密钥 / 余额 / 限流 / 断流），不吞成「请求失败」。
 */
@Composable
internal fun AiScopeDialog(
    owner: String,
    /** 分桶键：同一次会话里稳定即可（题号 / 章节 id）。 */
    scopeKey: String,
    scope: AiScope,
    title: String,
    config: EnglishAiConfig,
    quickQuestions: List<String>,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope2 = rememberCoroutineScope()
    var history by remember(owner, scopeKey) { mutableStateOf(AiScopeThreads.history(owner, scopeKey)) }
    var asked by remember(owner, scopeKey) { mutableIntStateOf(AiScopeThreads.asked(owner, scopeKey)) }
    var input by remember(owner, scopeKey) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /**
     * 上一次**失败**的问题（FINDINGS #7）。
     *
     * 失败的时候这一条没有进 history（append 只发生在拿到回答之后），
     * 所以重试**不能**从 history 里找 —— 那样找到的是上一条，点了「重试」却答另一个问题。
     */
    var retryable by remember(owner, scopeKey) { mutableStateOf<String?>(null) }
    /** 在飞的那一条请求。「重新开始」要能把它掐掉（FINDINGS #8）。 */
    var job by remember(owner, scopeKey) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /**
     * 会话代号，每次「重新开始」+1。
     *
     * 光 cancel 还不够保险：请求有可能卡在 cancel 不到地方（读流 / 重试退避）。
     * 回来时代号对不上就说明会话已经换过了，这条答案直接作废 ——
     * 宁可丢一次回答，也不能让「清空了却又冒出一条」。
     */
    var generation by remember(owner, scopeKey) { mutableIntStateOf(0) }
    val listState = rememberScrollState()
    val left = (EnglishAi.MAX_USER_TURNS - asked).coerceAtLeast(0)

    fun dismiss() { OverlayDismissGuard.arm(); onDismiss() }
    fun send(question: String) {
        val text = question.trim()
        if (text.isBlank() || busy || left <= 0) return
        if (!config.ready()) { error = "还没有配置 API"; return }
        input = ""; error = null; busy = true
        val mine = generation
        job = scope2.launch {
            try {
                val answer = EnglishAi.chat(config, aiScopeTurns(scope, history, text))
                // 会话已经被「重新开始」清过了：这趟答案不许再落回来（FINDINGS #8）
                if (mine != generation) return@launch
                AiScopeThreads.append(owner, scopeKey, EnglishAiTurn("user", text))
                AiScopeThreads.append(owner, scopeKey, EnglishAiTurn("assistant", answer))
                history = AiScopeThreads.history(owner, scopeKey)
                asked = AiScopeThreads.asked(owner, scopeKey)
                retryable = null
            } catch (cause: EnglishAiException) {
                if (mine == generation) { error = cause.message; retryable = text }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) {
                android.util.Log.e("AiScope", "chat failed", cause)
                if (mine == generation) {
                    error = "请求失败：${cause.message ?: cause::class.java.simpleName}"
                    retryable = text
                }
            } finally { if (mine == generation) busy = false }
        }
    }
    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollTo(listState.maxValue) }

    // 弹层自识别左边缘拖动：Dialog 是独立 Window，系统预测性返回的**进度进不来**
    // （只拿得到「已经返回完」那一次回调），所以自己认。原来那条裸 BackHandler 必须删掉 ——
    // 它和外壳都注册在 OnBackPressedDispatcher 上，同时存在会一次返回退两层。
    val motion = rememberOverlayMotion(
        onClose = { dismiss() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dismiss() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = {
                        // 顺序要紧：先把在飞的那条掐掉、再清会话。反过来的话，
                        // 旧回答会在几秒后落到「已经清空」的会话上（FINDINGS #8）。
                        job?.cancel(); job = null; busy = false
                        generation++
                        AiScopeThreads.reset(owner, scopeKey); history = emptyList(); asked = 0
                        error = null; retryable = null
                    }) { Text("重新开始") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "AI 设置") }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(listState)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 「正在问什么」必须显眼：这是本面板与「随便一个聊天框」的区别。
                    Surface(shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("正在问：${scope.label}", fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("${AssistantPersona.NAME}只看到这一段的文字，不会读你的整份资料。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    if (config.ready()) Text("本次使用：${cn.tonglu.app.data.english.EnglishAiPresets.find(config.presetId).name} · ${config.model}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (history.isEmpty() && !busy) {
                        Text("点下面的问题直接问，也可以自己输入。同一个位置最多问 ${EnglishAi.MAX_USER_TURNS} 条。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            quickQuestions.forEach { q -> EnglishChip(selected = false, onClick = { send(q) }, label = { Text(q) }) }
                        }
                    }
                    history.forEach { turn -> ScopeBubble(turn) }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("${AssistantPersona.NAME}正在回答…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!config.ready()) TextButton(onClick = onOpenSettings) { Text("去设置") }
                            // 失败那一条没进 history，所以重试发 retryable，不是 history 里最后一条
                            else retryable?.let { failed ->
                                TextButton(onClick = { error = null; send(failed) }) { Text("重试") }
                            }
                        }
                    }
                    if (left <= 0) Text("这一轮 ${EnglishAi.MAX_USER_TURNS} 条已经用完，点右上角「重新开始」可以再问。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                if (!config.ready()) {
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("填了自己的 API 才能用 AI 讲解（密钥只存本机）", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onOpenSettings) { Text("去设置") }
                        }
                    }
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text(if (left > 0) "还能问 $left 条" else "本轮已用完") },
                        enabled = !busy && left > 0, maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, keyboardType = KeyboardType.Text),
                        keyboardActions = KeyboardActions(onSend = { send(input) }))
                    IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !busy && left > 0) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeBubble(turn: EnglishAiTurn) {
    val mine = turn.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = MaterialTheme.shapes.large,
            color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(0.92f)) {
            Column(Modifier.padding(14.dp)) {
                Text(if (mine) "我问" else AssistantPersona.NAME, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(turn.content, lineHeight = 22.sp)
            }
        }
    }
}
