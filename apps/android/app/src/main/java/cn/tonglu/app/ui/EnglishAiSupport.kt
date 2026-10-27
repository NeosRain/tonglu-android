package cn.tonglu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.ai.AssistantPersona
import cn.tonglu.app.data.english.*
import kotlinx.coroutines.launch

/**
 * 单词 AI 讲解的界面层：一个单词一轮对话，最多问 5 条。
 *
 * 两条硬约束：
 * - 没配密钥时不发请求，直接引导去「AI 设置」（避免点了没反应）。
 * - 失败要把厂商的原因说出来（密钥错 / 余额不足 / 地址错），不能只说「请求失败」。
 */
@Composable
internal fun EnglishAiDialog(
    owner: String,
    word: EnglishWord,
    config: EnglishAiConfig,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var history by remember(owner, word.id) { mutableStateOf(EnglishAiThreads.history(owner, word.id)) }
    var asked by remember(owner, word.id) { mutableIntStateOf(EnglishAiThreads.asked(owner, word.id)) }
    var input by remember(owner, word.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /**
     * 上一次**失败**的问题（与 `AiScopeDialog` 同一条 FINDINGS #7）。
     *
     * 失败那一条并不进 history（append 只发生在拿到回答之后），所以重试**不能**
     * 从 history 里找 —— 那样拿到的是上一条，点了「重试」却在答另一个问题。
     */
    var retryable by remember(owner, word.id) { mutableStateOf<String?>(null) }
    /** 在飞的那一条请求。「重新开始」要能把它掐掉（FINDINGS #8）。 */
    var job by remember(owner, word.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /**
     * 会话代号，每次「重新开始」+1（FINDINGS #8）。
     *
     * 光 cancel 还不够保险：请求可能卡在 cancel 不到的地方（读流 / 重试退避）。
     * 回来时代号对不上就说明会话已经换过了，这条答案直接作废 ——
     * 宁可丢一次回答，也不能「清空了却又冒出一条」。
     */
    var generation by remember(owner, word.id) { mutableIntStateOf(0) }
    val listState = rememberScrollState()
    val left = (EnglishAi.MAX_USER_TURNS - asked).coerceAtLeast(0)

    fun dismiss() { OverlayDismissGuard.arm(); onDismiss() }
    fun send(question: String) {
        val text = question.trim()
        if (text.isBlank() || busy || left <= 0) return
        if (!config.ready()) { error = "还没有配置 API"; return }
        input = ""
        error = null
        busy = true
        val mine = generation
        job = scope.launch {
            try {
                val turns = buildList {
                    add(EnglishAi.systemTurn())
                    add(EnglishAi.contextTurn(word))
                    addAll(history)
                    add(EnglishAiTurn("user", text))
                }
                val answer = EnglishAi.chat(config, turns)
                // 会话已被「重新开始」清过：这趟答案不许再落回来（FINDINGS #8）
                if (mine != generation) return@launch
                EnglishAiThreads.append(owner, word.id, EnglishAiTurn("user", text))
                EnglishAiThreads.append(owner, word.id, EnglishAiTurn("assistant", answer))
                history = EnglishAiThreads.history(owner, word.id)
                asked = EnglishAiThreads.asked(owner, word.id)
                retryable = null
            } catch (cause: EnglishAiException) {
                if (mine == generation) { error = cause.message; retryable = text }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) {
                android.util.Log.e("EnglishAi", "chat failed", cause)
                if (mine == generation) {
                    error = "请求失败：${cause.message ?: cause::class.java.simpleName}"
                    retryable = text
                }
            } finally { if (mine == generation) busy = false }
        }
    }
    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollTo(listState.maxValue) }
    // 弹层自识别左边缘拖动（Dialog 收不到系统进度，见 AiScopePanel 里的同一段说明）。
    val motion = rememberOverlayMotion(
        onClose = { dismiss() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dismiss() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text(word.english, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = {
                        // 顺序要紧：先把在飞的那条掐掉、会话代号 +1，**再**清历史
                        // （FINDINGS #8）。只清历史的话请求回来照样 append，
                        // 用户看到的是「清空了却又冒出一条」。
                        job?.cancel(); job = null; busy = false
                        generation++
                        EnglishAiThreads.reset(owner, word.id); history = emptyList(); asked = 0
                        error = null; retryable = null
                    }) { Text("重新开始") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "AI 设置") }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(listState).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("问 AI：${word.english} · ${word.chinese}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 每次提问都写明用的是哪家、哪个模型（需求：不能让用户猜钱花在哪）
                    if (config.ready()) Text("本次使用：${EnglishAiPresets.find(config.presetId).name} · ${config.model}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (history.isEmpty() && !busy) {
                        Text("点下面的问题直接问，也可以自己输入。同一个单词最多问 ${EnglishAi.MAX_USER_TURNS} 条。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            EnglishAi.quickQuestions.forEach { question ->
                                EnglishChip(selected = false, onClick = { send(question) }, label = { Text(question) })
                            }
                        }
                    }
                    history.forEach { turn -> AiBubble(turn) }
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
private fun AiBubble(turn: EnglishAiTurn) {
    val mine = turn.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = MaterialTheme.shapes.large, color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth(0.92f)) {
            Column(Modifier.padding(14.dp)) {
                Text(if (mine) "我问" else AssistantPersona.NAME, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(turn.content, lineHeight = 22.sp)
            }
        }
    }
}

/** 供应商 / 地址 / 模型 / 密钥。密钥进加密存储，界面默认打码。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EnglishAiSettingsDialog(
    store: EnglishAiStore,
    initial: EnglishAiConfig,
    onSaved: (EnglishAiConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var config by remember { mutableStateOf(initial.migrated()) }
    var revealed by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(if(config.isDeepSeek()) EnglishAi.deepSeekModels else emptyList()) }
    var fromServer by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val drafts=remember { mutableMapOf<String,EnglishAiConfig>() }
    val busy=testing || fetching
    val scope = rememberCoroutineScope()
    val motion=rememberOverlayMotion(onClose=onDismiss,predictive=OverlayMotion.enabledState,selfDrag=true)
    fun applyPreset(id: String) {
        drafts[config.presetId]=config
        val preset = EnglishAiPresets.find(id)
        config = if (id == "custom") config.copy(presetId = id)
        else drafts[id] ?: EnglishAiConfig.of(preset)
        models=if(config.isDeepSeek()) EnglishAi.deepSeekModels else emptyList()
        fromServer=false
        result = null
    }
    Dialog(onDismissRequest={motion.requestClose()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion,fade=false),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={motion.requestClose()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"关闭 AI 设置") }
                    Text("AI 设置",style=MaterialTheme.typography.titleLarge)
                }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("选择服务商，填入密钥，再从接口获取模型。请求由手机直连你的服务商。密钥仅保存在本机加密存储，不进入学习备份。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务商", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        EnglishAiPresets.all.forEach { preset ->
                            FilterChip(selected=config.presetId==preset.id,enabled=!busy,onClick={applyPreset(preset.id)},
                                label={Text(preset.name,maxLines=1)})
                        }
                    }
                    EnglishAiPresets.find(config.presetId).let { preset ->
                        if (preset.note.isNotBlank()) Text(preset.note, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(config.baseUrl, onValueChange = { config = config.copy(baseUrl = it);models=emptyList();fromServer=false;result=null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("接口根地址") }, singleLine = true,enabled=!busy,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),
                    placeholder = { Text("https://api.deepseek.com") })
                OutlinedTextField(config.apiKey, onValueChange = { config = config.copy(apiKey = it);models=emptyList();fromServer=false;result=null },
                    modifier = Modifier.fillMaxWidth(), label = { Text(if(config.presetId=="local") "API Key（无鉴权可留空）" else "API Key") }, singleLine = true,enabled=!busy,
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { revealed = !revealed }) {
                        Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (revealed) "隐藏密钥" else "显示密钥") } })
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={fetching=true;result=null;scope.launch {
                        try { models=EnglishAi.models(config);fromServer=true;result="已获取 ${models.size} 个模型，请选择。" }
                        catch(e:kotlinx.coroutines.CancellationException) { throw e }
                        catch(e:EnglishAiException) { result=e.message }
                        catch(_:Exception) { result="获取失败，请检查地址和密钥格式。" }
                        finally { fetching=false }
                    }},enabled=config.connectionReady()&&!busy,modifier=Modifier.weight(1f)) { Text(if(fetching) "获取中…" else "获取模型") }
                    OutlinedButton(onClick={picker=true},enabled=models.isNotEmpty()&&!busy,modifier=Modifier.weight(1f)) { Text("选择模型") }
                }
                if(models.isNotEmpty()) Text(if(fromServer) "模型列表来自当前接口，按账户权限返回。" else "下列为官方预设；获取后可查看账户实际可用列表。",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(config.model,onValueChange={config=config.copy(model=it)},modifier=Modifier.fillMaxWidth(),
                    label={Text("模型标识")},supportingText={Text("可从列表选择，也可手动填写。")},singleLine=true,enabled=!busy)
                if(config.isDeepSeek()) {
                    Text("思考强度",style=MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("none" to "关闭","low" to "低","high" to "高","max" to "最高").forEach { (value,label) ->
                            FilterChip(selected=config.reasoningEffort==value,onClick={config=config.copy(reasoningEffort=value)},enabled=!busy,label={Text(label)})
                        }
                    }
                    Text("日常问答默认关闭思考；开启后输出预算至少为 8192 tokens，可能耗时更长、消耗更多额度。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result?.let { Text(it, color = if (it.startsWith("可用")||it.startsWith("已获取")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { testing = true; result = null; scope.launch {
                    try {
                        EnglishAi.chat(config, listOf(EnglishAiTurn("system", "只回复两个字：可用"), EnglishAiTurn("user", "可用吗")))
                        result = "可用：接口通了，模型有响应。"
                    } catch (cause: EnglishAiException) { result = cause.message ?: "连接失败" }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { result = "连接失败，请检查地址和密钥格式。" }
                    finally { testing = false }
                } }, enabled = config.ready() && !busy,modifier=Modifier.fillMaxWidth()) { Text(if (testing) "测试中…" else "测试连接") }
                Text("测试会发送一条简短请求，可能消耗服务商额度。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(enabled=!busy&&config.apiKey.isNotBlank(),onClick={
                    config=config.copy(apiKey="");drafts[config.presetId]=config;store.clearKey();onSaved(store.load())
                }) { Text("清除已保存的密钥") }
                Spacer(Modifier.height(12.dp))
            }
                HorizontalDivider()
                Button(onClick={
                    val value=config.copy(baseUrl=config.baseUrl.trim(),model=config.model.trim(),apiKey=config.apiKey.trim()).migrated()
                    try { EnglishAi.endpoint(value.baseUrl);store.save(value);onSaved(value);motion.requestClose() }
                    catch(e:EnglishAiException) { result=e.message }
                },enabled=config.ready()&&!busy,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)) { Text("保存配置") }
            }
        }
    }
    if(picker) AiModelPicker(models,onSelect={config=config.copy(model=it.id);picker=false;result=null},onClose={picker=false})
}

@Composable
private fun AiModelPicker(models:List<EnglishAi.Model>,onSelect:(EnglishAi.Model)->Unit,onClose:()->Unit) {
    var filter by remember { mutableStateOf("") }
    val visible=remember(models,filter) { models.filter { it.name.contains(filter,true)||it.id.contains(filter,true) } }
    val motion=rememberOverlayMotion(onClose=onClose,predictive=OverlayMotion.enabledState,selfDrag=true)
    Dialog(onDismissRequest={motion.requestClose()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion,fade=false),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={motion.requestClose()}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"关闭模型列表") }
                    Text("选择模型",style=MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(filter,onValueChange={filter=it},label={Text("搜索模型名称或标识")},singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp))
                Text("${visible.size} 个模型",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)) {
                    items(visible,key={it.id}) { model ->
                        TextButton(onClick={onSelect(model)},modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(model.name,maxLines=2,overflow=TextOverflow.Ellipsis)
                                if(model.name!=model.id) Text(model.id,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                model.contextWindow?.let { Text("上下文 $it tokens",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}
