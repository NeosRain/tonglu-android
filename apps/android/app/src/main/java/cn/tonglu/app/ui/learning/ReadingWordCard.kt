package cn.tonglu.app.ui.learning

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.readingselection.characterAt
import cn.tonglu.app.data.speech.Accent
import cn.tonglu.app.data.speech.SpeechVoice
import cn.tonglu.app.ui.english.contextword.ContextWordCard
import cn.tonglu.app.ui.english.contextword.SpokenAccent
import cn.tonglu.app.ui.english.contextword.VoiceState
import cn.tonglu.app.ui.english.reading.ReaderSelection
import cn.tonglu.app.ui.english.reading.ReaderSelection.Companion.tokenAt

/**
 * 词卡浮层的**内容**（挂在 A 的 `ReadingDeepDiveContent(wordCard = …)` 插槽上）。
 *
 * 为什么是「句子上方 + 卡片下方」而不是两个层：
 * 选词和看释义是**同一次点段动作**的两步，不该让用户为了换一个词先收起浮层再点一次段落。
 * 句子常驻在上面（被点的词高亮），点别的词就地换卡 —— 层数不变，返回语义也不变（返回 = 收起）。
 *
 * ⚠️ 与 `EXECUTION-READER.md` 的一处**已知简化**：返回时直接收起整层，**不会**退回「选词面板」
 * （那需要把选词面板也记成一层，属 A 的状态机；本轮不动它）。
 * 换词走「点句子里的另一个词」，不需要返回。
 */
@Composable
internal fun ReadingWordCardPanel(
    selection: ReaderSelection,
    state: ReadingWordCardState,
    notice: String?,
    onPickWord: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onSpeak: (SpokenAccent) -> Unit,
    onRequestAnalysis: () -> Unit,
    onAskAi: () -> Unit,
    /** 「加入今日」。C03 接入队列后由宿主提供真实实现（此前是 `{}`，按钮不可点）。 */
    onQueue: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语境词卡", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                cn.tonglu.app.ui.english.intensive.SentenceFavoriteAction(state.owner,
                    selection.sentence.articleId + ":" + selection.sentence.revision,
                    "${selection.sentence.start}:${selection.sentence.end}:${selection.sentence.hash}", selection.sentence.text)
                TextButton(onClick = onClose) { Text("收起") }
            }
            WordPickSentence(selection, state.surface, onPickWord)

            val chosen = selection.wordTokenId != null
            if (!chosen) {
                Text("点上面句子里的一个词，看它在**这一句**里的释义、音标与发音。",
                    fontSize = 13.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                return@Column
            }

            notice?.let {
                Text(it, fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            state.favoriteError?.let {
                Text("收藏没有成功：$it", fontSize = 12.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }

                    cn.tonglu.app.ui.english.books.WordCollectionActions(
                        owner = state.owner,
                        word = cn.tonglu.app.data.english.books.PersonalBookWord(
                            surface = state.surface, lemma = state.lemma ?: state.surface,
                            meaning = state.definition.orEmpty(), sentence = state.sentence,
                            articleId = selection.sentence.articleId,
                            sourcePosition = "${selection.sentence.start + state.hitStart}-${selection.sentence.start + state.hitEnd}"),
                        revision = state.favorite, existingOnly = true,
                        onCount = { if (!state.favoriteSaving) state.applyFavorite(it > 0) })
            when {
                // 查询失败与「未收录」必须分开说：前者是坏了，后者是词书里真没有。
                state.loadError != null -> Column(Modifier.padding(top = 10.dp)) {
                    Text("查词没有成功：${state.loadError}", fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.error)
                    Text("这不是「词书未收录」，是本地词库读取出错。", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                state.loading -> Text("正在查词书…", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                state.libraryMissing -> Text(
                    "本机还没有导入任何词库，所以查不了释义。到「英语 → 词库」下载一本之后回来即可，发音不受影响。",
                    fontSize = 13.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                else -> Column(Modifier.padding(top = 10.dp)) {
                    ContextWordCard(
                        surface = state.surface,
                        lemma = state.lemma,
                        lookup = state.lookup,
                        bookName = state.bookName,
                        definition = state.definition,
                        phoneticUk = state.phoneticUk,
                        // 词书只给一条音标时它是**通用**的，不是美音 —— 交给 B 的
                        // `phoneticRows` 判：（uk, us）相等或只有一条时一律降级成「通用音标」，
                        // 绝不复制成英美两条（那条规则的坑见 ContextWordRules）。
                        phoneticUs = state.phoneticGeneral,
                        phoneticSource = null,
                        sentence = state.sentence,
                        hitStart = state.hitStart,
                        hitEnd = state.hitEnd,
                        ukVoice = state.ukVoice,
                        usVoice = state.usVoice,
                        favorite = state.favorite,
                        favoriteSaving = state.favoriteSaving,
                        queue = state.queue,
                        analysis = state.analysis,
                        analysisText = state.analysisText,
                        analysisError = state.analysisError,
                        onToggleFavorite = onToggleFavorite,
                        onSpeak = onSpeak,
                        // 队列已接入（C03）：状态机给 IDLE/SAVING/QUEUED/FAILED，宿主负责真写库。
                        onQueue = onQueue,
                        onAskAi = onAskAi,
                        onRequestAnalysis = onRequestAnalysis,
                    )

                    TextButton(onClick = onOpenAiSettings, modifier = Modifier.padding(top = 4.dp)) {
                        Text("AI 设置", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * 句子 + 可点词。
 *
 * 用 `TextLayoutResult.getOffsetForPosition` 把**手指落点**换成**字符偏移**，
 * 再用 [tokenAt] 换成词元 —— 全程按 UTF-16 偏移定位，**不做字符串首匹配**。
 * 这一点在重复词上就是判据：`"the cat saw the dog"` 里两个 `the` 必须各自命中自己那一个
 * （`ReadingWordCardStateTest` 与走查都钉这条）。
 *
 * 落在词与词之间的空格上时 `tokenAt` 返回 null —— **本次点击什么也不做**，
 * 而不是随手挑一个最近的词（挑错等于用户查的不是他点的那个词）。
 */
@Composable
private fun WordPickSentence(selection: ReaderSelection, current: String, onPick: (Int) -> Unit) {
    var layout by remember(selection) { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            if (current.isBlank()) "点句子里要查的词" else "点别的词换一张卡（当前：$current）",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            selection.sentence.text,
            fontSize = 17.sp, lineHeight = 26.sp,
            color = MaterialTheme.colorScheme.onSurface,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                .pointerInput(selection) {
                    detectTapGestures { position ->
                        // 同一个落点判定（BUGS S118）：最近的**游标**可能落在下一个词上，
                        // 直接用它会出现「点这个词跳到那个词」或「点了没反应」。
                        val offset = layout?.characterAt(position, selection.sentence.text.length)
                            ?: return@detectTapGestures
                        tokenAt(selection.tokens, offset)?.let { onPick(it.id) }
                    }
                },
        )
    }
}

/**
 * 声线清单 → 词卡要显示的三态。
 *
 * 判据来自**真实清单**（[SpeechCatalog.voices]）而不是"应该是有的"：
 * - 有可用条目 → READY
 * - 有模型但没下载（`modelId` 非空、`available=false`）→ NOT_DOWNLOADED
 * - 只剩占位条目（`modelId` 为空，即该口音还没有离线模型）或系统朗读也没就绪 → UNSUPPORTED
 *
 * 🔴 英音**不许**因为美音可用就变 READY：两个口音各算各的，谁也不拿谁顶替。
 */
internal fun voiceStateOf(voices: List<SpeechVoice>): VoiceState = when {
    voices.any { it.available } -> VoiceState.READY
    voices.any { it.modelId.isNotBlank() } -> VoiceState.NOT_DOWNLOADED
    else -> VoiceState.UNSUPPORTED
}

/** 口音映射。系统英式声线必须落到 en-GB，不能用笼统的 "en" 糊过去。 */
internal fun localeOf(accent: Accent): String = if (accent == Accent.British) "en-GB" else "en"
