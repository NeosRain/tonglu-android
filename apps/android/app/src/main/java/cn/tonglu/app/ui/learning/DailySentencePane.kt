package cn.tonglu.app.ui.learning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.SpeechProblem
import cn.tonglu.app.data.english.DailySentence
import cn.tonglu.app.data.english.DailySentenceSet
import cn.tonglu.app.data.english.DailySentenceStore
import cn.tonglu.app.data.english.EnglishRepository
import cn.tonglu.app.data.english.EnglishWord
import cn.tonglu.app.data.speech.Accent
import cn.tonglu.app.data.speech.SpeechPreference
import cn.tonglu.app.data.speech.SpeechPurpose
import cn.tonglu.app.data.speech.SpeechRequest
import cn.tonglu.app.data.speech.SpeechResult
import cn.tonglu.app.data.speech.SpeechService
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 每日例句（docs/plan/56 §6.3 A3）。
 *
 * 三条纪律，界面上要**看得出来**：
 * 1. 每句都能点开「这些词今天出现过」→ 回到真实词条。**不拿一个「CET6」标签冒充难度。**
 * 2. 无 Key 照常可用 —— 句子取自用户自己词书里的例句；有 Key 才多一个「让 AI 换一句」。
 * 3. 朗读复用全 App 统一的 SpeechService，失败原因（没引擎 / 缺语音包 / 静音）分开说，
 *    不吞成「点了一下没反应」。
 */
@Composable
internal fun DailySentencePane(owner: String, onClose: () -> Unit, onLookupWord: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember { EnglishRepository(context) }
    // 仓储必须跟着 owner 重建：换账号后还拿着旧 owner 的仓储，读写的就是别人的桶（FINDINGS #5）
    val store = remember(owner) { DailySentenceStore(context, owner) }
    val scope = rememberCoroutineScope()

    var set by remember(owner) { mutableStateOf<DailySentenceSet?>(null) }
    var loading by remember { mutableStateOf(true) }
    var favorites by remember(owner) { mutableStateOf(store.favorites()) }
    // 例句线的完成记录：今天朗读过几句（挖空没有判定，记不了「答案」，见 store 里的说明）。
    var spokenToday by remember(owner) { mutableIntStateOf(store.spokenToday()) }
    var clozeOn by remember(owner) { mutableStateOf(false) }
    var wordsById by remember(owner) { mutableStateOf<Map<String, EnglishWord>>(emptyMap()) }
    var expanded by remember(owner) { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var speechProblem by remember { mutableStateOf<SpeechProblem?>(null) }

    val speech = remember { SpeechService(context) { problem -> speechProblem = problem } }
    val speechPreference = remember(owner) { SpeechPreference(context) }
    DisposableEffect(speech) { speech.start(); onDispose { speech.shutdown() } }

    LaunchedEffect(owner) {
        loading = true
        try {
            // 取词口径与背词页一致：优先没背过的词，不够再补到期词，避免「首页说学这些、背词页说学那些」。
            // 这里只取一次当前值（本页是一次性展示），不长期订阅。
            val settings = repository.dao.settings(owner).first()
            val bookId = settings?.bookId ?: "kylebing-cet4"
            val fresh = repository.dao.newWordsWithExample(owner, bookId, 40)
            val due = repository.dao.dueWordsWithExample(owner, bookId, System.currentTimeMillis(), 40)
            val pool = (fresh + due).distinctBy { it.id }
            // 词库还没下或没例句 → 句子自然为空，界面如实说明，不硬编几句假句充数。
            wordsById = pool.associateBy { it.english.lowercase() }
            set = store.today(pool)
        } catch (cause: Exception) {
            android.util.Log.w("DailySentence", "生成失败", cause)
            notice = "例句生成失败：${cause.message ?: cause::class.java.simpleName}"
        } finally { loading = false }
    }

    fun dismiss() { OverlayDismissGuard.arm(); onClose() }
    fun speak(text: String, id: String) {
        when (val result = speech.speak(SpeechRequest(
            text = text, accent = speechPreference.accent(owner), voiceId = speechPreference.voiceId(owner),
            locale = if (speechPreference.accent(owner) == Accent.British) "en-GB" else "en",
            purpose = SpeechPurpose.Word, utteranceId = id,
        ))) {
            SpeechResult.Spoken -> { store.markSpoken(id); spokenToday = store.spokenToday() }
            is SpeechResult.Failed -> speechProblem = result.problem
            is SpeechResult.Unavailable -> notice = result.reason
            is SpeechResult.Degraded -> notice = result.reason
        }
    }

    // 弹层自识别左边缘拖动（Dialog 收不到系统进度，见 EnglishHubScreen 里的说明）。
    val motion = rememberOverlayMotion(
        onClose = { dismiss() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dismiss() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回英语") }
                    Text("每日例句", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { clozeOn = !clozeOn }) { Text(if (clozeOn) "看原文" else "挖空练习") }
                }
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                val current = set
                if (current == null || current.sentences.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("今天还没有可用的例句。", fontWeight = FontWeight.SemiBold)
                        Text("例句取自你自己词书里的词条。先下载一本词库、并选几个还没背过的词，这里就会有内容。" +
                            "换了词书或换一天，句子会跟着更新。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    return@Column
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("今天 ${current.localDate} 的 ${current.sentences.size} 句 · 今日已朗读 $spokenToday 句" +
                        "（同一句里的词今天出现过，点开可核对）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    current.sentences.forEach { sentence ->
                        SentenceCard(
                            sentence = sentence,
                            cloze = clozeOn,
                            favorite = sentence.id in favorites,
                            expanded = expanded == sentence.id,
                            words = sentence.wordIds.mapNotNull { wordsById[it.lowercase()] },
                            onSpeak = { speak(sentence.text, sentence.id) },
                            onToggleFavorite = {
                                val added = store.toggleFavorite(sentence.id)
                                favorites = store.favorites()
                                notice = if (added) "已收藏这句" else "已取消收藏"
                            },
                            onToggleExpand = { expanded = if (expanded == sentence.id) null else sentence.id },
                            onLookup = { onLookupWord(it) },
                        )
                    }
                    Text("这些句子是词书里原有的例句，用来在真实语境里复习。AI 讲解是可选的，" +
                        "没有配置 API 也能正常用、能朗读。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    speechProblem?.let { problem ->
        val (hint, openable) = cn.tonglu.app.data.speechHint(problem)
        AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { speechProblem = null },
            title = { Text("发不出声音") }, text = { Text(hint) },
            confirmButton = { TextButton(onClick = { speechProblem = null }) { Text("知道了") } },
            dismissButton = { if (openable) Row {
                TextButton(onClick = { if (!speech.installVoiceData() && !speech.openSystemSettings())
                    notice = "这台设备没有可跳转的语音设置页，请到系统设置里搜索「文字转语音」。"; speechProblem = null }) { Text("语音数据") }
                TextButton(onClick = { if (!speech.openSystemSettings())
                    notice = "这台设备没有可跳转的语音设置页，请到系统设置里搜索「文字转语音」。"; speechProblem = null }) { Text("系统设置") }
            } })
    }
    notice?.let { text -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = { notice = null }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } }) }
}

@Composable
private fun SentenceCard(
    sentence: DailySentence,
    cloze: Boolean,
    favorite: Boolean,
    expanded: Boolean,
    words: List<EnglishWord>,
    onSpeak: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleExpand: () -> Unit,
    onLookup: (String) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (cloze) sentence.cloze() else sentence.text,
                fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp)
            if (!cloze && sentence.translation.isNotBlank()) {
                Text(sentence.translation, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (cloze) Text("把横线处补全，再点「看原文」对照。",
                style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSpeak) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("朗读")
                }
                TextButton(onClick = onToggleFavorite) {
                    Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (favorite) "已收藏" else "收藏")
                }
                TextButton(onClick = onToggleExpand) { Text(if (expanded) "收起覆盖词" else "这些词今天出现过") }
            }
            if (expanded) {
                if (words.isEmpty()) {
                    Text("这些词的词条已不在当前词书里（可能换了词书）。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else words.forEach { word ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(word.english, fontWeight = FontWeight.Medium)
                        Text(word.chinese, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        TextButton(onClick = { onLookup(word.english) }) { Text("查词") }
                    }
                }
                // 难度只说得出「词在词书里的相对位置」—— 明确不宣称这是 CEFR / 四六级难度。
                Text("难度按词在词书中的位置估：${sentence.level}。不是 CEFR / 四六级等级。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
