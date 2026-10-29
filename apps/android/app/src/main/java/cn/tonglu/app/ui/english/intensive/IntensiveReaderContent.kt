package cn.tonglu.app.ui.english.intensive

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import cn.tonglu.app.data.english.*
import cn.tonglu.app.data.english.books.PersonalBookWord
import cn.tonglu.app.data.english.reading.StoredReadingHistory
import cn.tonglu.app.ui.EnglishAiSettingsDialog
import cn.tonglu.app.ui.english.books.WordCollectionActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.luminance
import cn.tonglu.app.R
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.english.intensive.*
import cn.tonglu.app.data.speech.*
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import cn.tonglu.app.ui.english.reading.readingRoleColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IntensiveReader(article: IntensiveArticle, preferences: SharedPreferences, owner: String, onLookup: (String) -> Unit) {
    val context = LocalContext.current
    val entry = article.entry
    val dictionary=remember(owner) { IntensiveWordLookup(context,owner) }
    val sentences = remember(article) { article.paragraphs.flatMap { it.sentences } }
    val study = remember(owner) { IntensiveStudyStore(context, owner) }
    val aiStore = remember { EnglishAiStore(context) }
    var aiConfig by remember { mutableStateOf(aiStore.load()) }
    var aiSettingsOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var historySentence by remember { mutableStateOf<String?>(null) }
    var records by remember(entry.progressKey, owner) { mutableStateOf<List<StoredReadingHistory>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var translated by remember(article, owner) { mutableStateOf(sentences.mapNotNull { row ->
        row.translation.takeIf { it.isNotBlank() }?.let { row.id to it }
    }.toMap()) }
    var translationBusy by remember { mutableStateOf(false) }
    var translationError by remember { mutableStateOf<String?>(null) }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    val syntax = true
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) { listOf("主语", "谓语", "宾语", "定语", "状语", "补语", "从句").associateWith { readingRoleColor(it, scheme) } }
    fun translateRows(rows: List<IntensiveSentence>) {
        if (translationBusy) return
        if (!aiConfig.ready()) { aiSettingsOpen = true; return }
        translationJob = scope.launch {
            translationBusy = true; translationError = null
            try {
                for (row in rows) if (translated[row.id].isNullOrBlank()) {
                    val value = study.generate(entry.progressKey, entry.subtitle + " · " + entry.title, row.id, row.text, "translation", aiConfig).text
                    translated = translated + (row.id to value)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("CetTranslation", "AI translation failed", e)
                translationError = "AI 翻译未完成：${e.message ?: "请检查网络与 AI 配置"}；已完成的译文已保存。"
            } finally { translationBusy = false }
        }
    }
    LaunchedEffect(entry.progressKey, owner) {
        try {
            records = study.history().filter { it.articleKey == entry.progressKey }
            val saved = records.filter { it.kind == "translation" }.groupBy { it.sentenceId }.mapValues { it.value.first().text }
            translated = translated + saved
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { translationError = "历史译文读取失败，请从历史入口重试" }
    }
    var showTranslation by rememberSaveable(entry.progressKey) { mutableStateOf(false) }
    var selectedId by rememberSaveable(entry.progressKey) { mutableStateOf<String?>(null) }
    var selectedTokenId by rememberSaveable(entry.progressKey) { mutableIntStateOf(0) }
    var focusedId by rememberSaveable(entry.progressKey) { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf(false) }
    var original by rememberSaveable { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var read by remember(entry.progressKey) { mutableStateOf(preferences.getStringSet(entry.progressKey, emptySet()).orEmpty().toSet()) }
    var fontSize by rememberSaveable { mutableIntStateOf(preferences.getInt("readerFont",19).coerceIn(16,24)) }
    var voice by rememberSaveable { mutableStateOf(preferences.getString("articleVoice","system-tts") ?: "system-tts") }
    val speech = remember { SpeechService(context) { problem = "朗读失败，请检查英语语音数据或更换声音" } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(speech,lifecycle) {
        speech.start()
        val observer = LifecycleEventObserver { _,event -> if (event==Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.shutdown() }
    }
    val voices = remember(settings) { listOf(SpeechCatalog.systemVoice().copy(name="系统美式（本机自带）")) +
        SpeechCatalog.builtinVoices(context).filter { it.available && it.installed && it.accent in setOf(Accent.American,Accent.British) } }
    fun speak(text: String) {
        problem=null
        val selected = voices.firstOrNull { it.id==voice }
        if (selected==null) { problem="文章声音已不可用，请在阅读设置里重新选择";return }
        when(val result=speech.speak(SpeechRequest(text=text,voiceId=selected.id,accent=selected.accent,purpose=SpeechPurpose.Sentence))) {
            SpeechResult.Spoken -> Unit
            is SpeechResult.Unavailable -> problem=result.reason
            is SpeechResult.Failed -> problem=result.hint
            is SpeechResult.Degraded -> problem=result.reason
        }
    }
    fun mark(sentence: IntensiveSentence) {
        read=if(sentence.id in read) read-sentence.id else read+sentence.id
        preferences.edit().putStringSet(entry.progressKey,read).apply()
    }
    val paragraphKey="paragraph:"+entry.progressKey
    val restored = remember(entry.progressKey) { preferences.getInt(paragraphKey,0).coerceIn(0,article.paragraphs.lastIndex) }
    val scroll=rememberLazyListState(initialFirstVisibleItemIndex=if(restored>0) restored+1 else 0)
    LaunchedEffect(scroll,entry.progressKey) {
        snapshotFlow { (scroll.firstVisibleItemIndex-1).coerceIn(0,article.paragraphs.lastIndex) }.distinctUntilChanged().collect {
            preferences.edit().putInt(paragraphKey,it).apply()
        }
    }
    LazyColumn(state=scroll,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp),
        verticalArrangement=Arrangement.spacedBy(26.dp)) {
        item(key="reading-header") {
            Text(entry.subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("已读 "+read.size+" / "+entry.sentences+" 句",
                style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={
                    showTranslation = !showTranslation
                    if (showTranslation) translateRows(sentences) else translationJob?.cancel()
                }) { Text(if(showTranslation) "隐藏译文" else "AI 译文") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={speech.stop()}) { Icon(Icons.Outlined.Stop,"停止朗读") }
                Box {
                    IconButton(onClick={more=true}) { Icon(Icons.Outlined.MoreVert,"阅读更多操作") }
                    DropdownMenu(more,onDismissRequest={more=false}) {
                        DropdownMenuItem(text={Text("历史")},onClick={more=false;historySentence=null;historyOpen=true})
                        DropdownMenuItem(text={Text("AI 设置")},onClick={more=false;aiSettingsOpen=true})
                        DropdownMenuItem(text={Text("字体与声音")},onClick={more=false;settings=true})
                        if(article.answers.isNotEmpty()) DropdownMenuItem(text={Text("填空原题与答案")},onClick={more=false;original=true})
                        DropdownMenuItem(text={Text("材料与分析说明")},onClick={more=false;source=true})
                    }
                }
            }
            LinearProgressIndicator(progress={read.size.toFloat()/entry.sentences.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth().height(2.dp))
            Text("点句子看翻译与语法，点词可查释义。",Modifier.padding(top=12.dp),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("主语", "谓语", "宾语").forEach { role -> Text(role, color = colors.getValue(role), fontSize = 12.sp) }
            }
            if (showTranslation && article.entry.remote != null) Text("AI 译文与分析会保存在本机历史中；相同输入可复用结果。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (translationBusy) Text("正在生成 AI 译文…", style = MaterialTheme.typography.bodySmall)
            translationError?.let { Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { translateRows(sentences) }) { Text("重试翻译") } }
            if(entry.qualityNotice.isNotBlank()) Text(entry.qualityNotice,Modifier.padding(top=8.dp),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            problem?.let { Text(it,color=MaterialTheme.colorScheme.error)
                TextButton(onClick={speech.openSystemSettings()}) { Text("语音引擎设置") } }
        }
        items(article.paragraphs.size,key={ "paragraph-"+it }) { index ->
            val paragraph=article.paragraphs[index]
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text((index+1).toString().padStart(2,'0'),Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick={speak(paragraph.text)},modifier=Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp,"朗读第 "+(index+1)+" 段",Modifier.size(19.dp))
                    }
                }
                val highlight=MaterialTheme.colorScheme.secondaryContainer
                val ranges=remember(paragraph) { intensiveSentenceRanges(paragraph) }
                val annotated=remember(paragraph,focusedId,highlight,syntax,colors) {
                    buildAnnotatedString {
                        append(paragraph.text)
                        if (syntax) ranges.forEach { range -> range.sentence.tokens.forEach { token ->
                            colors[token.role]?.let { addStyle(SpanStyle(color = it), range.start + token.start, range.start + token.end) }
                        } }
                        ranges.firstOrNull { it.sentence.id==focusedId }?.let { addStyle(SpanStyle(background=highlight),it.start,it.end) }
                    }
                }
                SelectionContainer {
                    ClickableText(annotated,style=MaterialTheme.typography.bodyLarge.copy(fontSize=fontSize.sp,lineHeight=(fontSize*1.7f).sp,
                        color=MaterialTheme.colorScheme.onSurface),onClick={ offset ->
                        ranges.firstOrNull { offset >= it.start && offset < it.end }?.let { range ->
                            speech.stop(); focusedId = range.sentence.id; selectedId = range.sentence.id
                            val local = offset - range.start
                            selectedTokenId = range.sentence.tokens.firstOrNull { local >= it.start && local < it.end && it.pos != "标点" }?.id ?: 0
                        }
                    })
                }
                val paragraphTranslation = paragraph.translation.takeIf { it.isNotBlank() }
                    ?: paragraph.sentences.mapNotNull { translated[it.id] }.joinToString(" ")
                if(showTranslation && paragraphTranslation.isNotBlank()) {
                    Text(paragraphTranslation,fontSize=(fontSize-1).sp,lineHeight=(fontSize*1.6f).sp,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))

                }
            }
        }
        item { Text("机器辅助译文与语法，仅供核对。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val selected=sentences.firstOrNull { it.id==selectedId }
    if(selected!=null) {
        val position=sentences.indexOf(selected)
        ReadingDetailSheet(onClose={speech.stop();selectedId=null}) { requestClose ->
            Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("逐句精读",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                Text((position+1).toString()+" / "+sentences.size,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick=requestClose) { Icon(Icons.Outlined.Close,"关闭句子精读") }
            }
            key(selected.id) {
                SentenceDetails(entry,selected,preferences,dictionary,{speak(selected.text)},
                    { speech.stop();onLookup(it) },Modifier.weight(1f), translation = translated[selected.id],
                    translationBusy = translationBusy, onTranslate = { translateRows(listOf(selected)) },
                    owner = owner, study = study, aiConfig = aiConfig, initialTokenId = selectedTokenId,
                    onAiSettings = { aiSettingsOpen = true },
                    onHistory = { historySentence = selected.id; historyOpen = true })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(modifier=Modifier.weight(1f),enabled=position>0,onClick={speech.stop();selectedTokenId=0;selectedId=sentences[position-1].id;focusedId=selectedId}) { Text("上一句") }
                TextButton(modifier=Modifier.weight(1f),onClick={mark(selected)}) { Text(if(selected.id in read) "✓ 已读" else "标为已读") }
                TextButton(modifier=Modifier.weight(1f),enabled=position<sentences.lastIndex,onClick={speech.stop();selectedTokenId=0;selectedId=sentences[position+1].id;focusedId=selectedId}) { Text("下一句") }
            }
        }
    }
    if(settings) ReadingDetailSheet(onClose={settings=false}) { requestClose ->
        Row(Modifier.padding(start=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("阅读设置",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            IconButton(onClick=requestClose) { Icon(Icons.Outlined.Close,"关闭阅读设置") }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text("正文字号",style=MaterialTheme.typography.labelLarge)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedButton(onClick={fontSize=(fontSize-1).coerceAtLeast(16);preferences.edit().putInt("readerFont",fontSize).apply()},enabled=fontSize>16) { Text("A−") }
                    Text(fontSize.toString(),Modifier.padding(horizontal=20.dp))
                    OutlinedButton(onClick={fontSize=(fontSize+1).coerceAtMost(24);preferences.edit().putInt("readerFont",fontSize).apply()},enabled=fontSize<24) { Text("A+") }
                }
            }
            item { Text("文章朗读声音",style=MaterialTheme.typography.labelLarge)
                Text("独立于单词音色设置。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(voices,key={it.id}) { option ->
                TextButton(onClick={speech.stop();voice=option.id;preferences.edit().putString("articleVoice",voice).apply()},
                    modifier=Modifier.fillMaxWidth()) { Text((if(option.id==voice) "✓ " else "")+option.name,Modifier.fillMaxWidth()) }
            }
        }
    }
    if(source || original) ReadingDetailSheet(onClose={source=false;original=false}) { requestClose ->
        Row(Modifier.padding(start=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(original) "填空原题与答案" else "材料与分析说明",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            IconButton(onClick=requestClose) { Icon(Icons.Outlined.Close,"关闭说明") }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(original) {
                item { SelectionContainer { Text(article.original,lineHeight=28.sp) } }
                item { Text(article.answers.joinToString("\n")) }
            } else {
                item { Text(article.notice,lineHeight=26.sp) }
                item { SelectionContainer { Text(article.license+"\n"+article.sourceUrl,style=MaterialTheme.typography.bodySmall) } }
                item { Text(if (entry.remote != null) "句法：GPU 预分析位置，默认分色；AI 使用你配置的接口。原卷与个人学习历史只保存在本机。"
                    else "句法：Stanza EWT；翻译：opus-mt-en-zh。电脑 GPU 预计算，手机离线查看。",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Text("AI 请求由手机直接发送到你配置的服务，不经苔米厸中转。译文与点评按版本保存在本机；同源同配置同输入复用缓存。", style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (aiSettingsOpen) EnglishAiSettingsDialog(aiStore, aiConfig,
        onSaved = { aiConfig = it }, onDismiss = { aiSettingsOpen = false })
    if (historyOpen) ReadingHistoryScreen(owner, onClose = { historyOpen = false },
        articleKey = entry.progressKey, sentenceId = historySentence)

}

@Composable
private fun SentenceDetails(entry: IntensiveEntry,sentence: IntensiveSentence,preferences: SharedPreferences,
    dictionary: IntensiveWordLookup,play: ()->Unit,lookup: (String)->Unit,modifier: Modifier,
    translation: String? = null, translationBusy: Boolean = false, onTranslate: () -> Unit = {},
    owner: String, study: IntensiveStudyStore, aiConfig: EnglishAiConfig,
    onAiSettings: () -> Unit, onHistory: () -> Unit, initialTokenId: Int = 0) {
    val scope = rememberCoroutineScope()
    var historyRows by remember(entry.progressKey, sentence.id) { mutableStateOf<List<StoredReadingHistory>>(emptyList()) }
    var favorite by remember(entry.progressKey, sentence.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var studyError by remember(sentence.id) { mutableStateOf<String?>(null) }
    var aiResult by remember(sentence.id) { mutableStateOf<String?>(null) }
    var savedNotice by remember(sentence.id) { mutableStateOf<String?>(null) }
    suspend fun refreshRecords() {
        historyRows = study.history().filter { it.articleKey == entry.progressKey && it.sentenceId == sentence.id }
        aiResult = historyRows.firstOrNull { it.kind in setOf("analysis", "review") }?.text
        favorite = study.favorites().any { it.articleKey == entry.progressKey && it.sentenceId == sentence.id }
    }
    fun analyze(kind: String, draft: String = "") {
        if (!aiConfig.ready()) { onAiSettings(); return }
        if (saving) return
        scope.launch {
            saving = true; studyError = null
            try {
                if (kind == "review") study.manual(entry.progressKey, entry.subtitle + " · " + entry.title, sentence.id, sentence.text, draft)
                val result = study.generate(entry.progressKey, entry.subtitle + " · " + entry.title, sentence.id, sentence.text, kind, aiConfig, draft)
                aiResult = result.text; refreshRecords()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { studyError = e.message ?: "AI 分析未完成，请重试" }
            finally { saving = false }
        }
    }
    var tokenId by rememberSaveable(sentence.id) { mutableIntStateOf(initialTokenId) }
    var reference by rememberSaveable(sentence.id) { mutableStateOf(true) }
    val noteKey=remember(entry.progressKey,sentence.id) { intensiveTranslationKey(entry,sentence) }
    var note by remember(noteKey) { mutableStateOf(preferences.getString(noteKey,"") ?: "") }
    val studyChanges by IntensiveStudyStore.changes.collectAsState()
    LaunchedEffect(noteKey, studyChanges) {
        try {
            refreshRecords()
            // Preserve and import the old local draft instead of discarding existing work.
            if (note.isNotBlank() && historyRows.none { it.kind == "manual" }) {
                study.manual(entry.progressKey, entry.subtitle + " · " + entry.title, sentence.id, sentence.text, note)
                refreshRecords()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { studyError = "学习记录读取失败，请重试" }
    }
    var writing by rememberSaveable(noteKey) { mutableStateOf(note.isNotBlank()) }
    val editorFocus=remember(noteKey) { FocusRequester() }
    val keyboard=LocalSoftwareKeyboardController.current
    var focusEditor by remember(noteKey) { mutableStateOf(false) }
    LaunchedEffect(focusEditor,writing) {
        if(focusEditor && writing) { editorFocus.requestFocus();keyboard?.show();focusEditor=false }
    }
    var gloss by remember(sentence.id,tokenId) { mutableStateOf<IntensiveGlossResult?>(null) }
    var loading by remember(sentence.id,tokenId) { mutableStateOf(false) }
    val selectedToken=sentence.tokens.firstOrNull { it.id==tokenId }
    var meaningDraft by remember(sentence.id, tokenId) { mutableStateOf("") }
    LaunchedEffect(sentence.id,tokenId,dictionary) {
        val token=selectedToken ?: return@LaunchedEffect
        if(!token.lemma.matches(Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*"))) return@LaunchedEffect
        loading=true
        try { gloss=dictionary.lookup(token.lemma) }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            android.util.Log.w("IntensiveLookup","lookup failed",e)
            gloss=IntensiveGlossResult(null,"词典读取失败，请重新点选重试")
        } finally { loading=false }
    }
    LazyColumn(modifier,contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            val background=MaterialTheme.colorScheme.secondaryContainer
            val scheme = MaterialTheme.colorScheme
            val annotated=remember(sentence,tokenId,background,scheme) {
                buildAnnotatedString {
                    append(sentence.text)
                    sentence.tokens.forEach { token ->
                        if (token.role in setOf("主语", "谓语", "宾语", "定语", "状语", "补语", "从句"))
                            addStyle(SpanStyle(color = readingRoleColor(token.role, scheme)), token.start, token.end)
                    }
                    sentence.tokens.firstOrNull { it.id==tokenId }?.let { addStyle(SpanStyle(background=background),it.start,it.end) }
                }
            }
            SelectionContainer {
                ClickableText(annotated,style=MaterialTheme.typography.titleLarge.copy(fontSize=21.sp,lineHeight=34.sp,color=MaterialTheme.colorScheme.onSurface),
                    onClick={ offset -> sentence.tokens.firstOrNull { offset>=it.start && offset<it.end && it.pos!="标点" }?.let { tokenId=it.id } })
            }
            Row {
                TextButton(onClick=play) { Icon(Icons.AutoMirrored.Outlined.VolumeUp,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("听这一句") }
                TextButton(onClick={reference=!reference}) { Text(if(reference) "隐藏参考译文" else "查看参考译文") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!saving) scope.launch {
                    saving = true
                    val nextFavorite = !favorite
                    try {
                        study.favorite(entry.progressKey, entry.subtitle + " · " + entry.title, sentence.id, sentence.text, nextFavorite)
                        favorite = nextFavorite
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { studyError = "句子收藏保存失败，请重试" }
                    finally { saving = false }
                } }, enabled = !saving) {
                    Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        if (favorite) "已收藏这个句子" else "收藏这个句子",
                        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { analyze("analysis") }, enabled = !saving) { Text("AI 分析") }
                TextButton(onClick = onHistory) { Text("历史 · ${historyRows.size}") }
            }
        }
        sentence.tokens.firstOrNull { it.id==tokenId }?.let { token -> item {
            Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(token.text+" · "+token.pos,Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                        if(gloss?.gloss==null && !loading) TextButton(onClick={lookup(token.lemma)},
                            enabled=token.lemma.matches(Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*"))) { Text("查词库") }
                    }
                    val head=sentence.tokens.firstOrNull { it.id==token.head }?.text
                    Text(token.role+(if(head!=null) " · 关联 "+head else " · 句子中心"),style=MaterialTheme.typography.bodyMedium)
                    if(token.form!=token.text) Text("解析形式："+token.form,style=MaterialTheme.typography.bodySmall)
                    if(loading) Text("正在查词…",style=MaterialTheme.typography.bodySmall)
                    gloss?.gloss?.let { result ->
                        HorizontalDivider()
                        Text(result.chinese,lineHeight=27.sp)
                        result.us?.let { Text("美 /"+it+"/",style=MaterialTheme.typography.bodySmall) }
                        result.uk?.let { Text("英 /"+it+"/",style=MaterialTheme.typography.bodySmall) }
                        Text(result.source,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(!loading && gloss!=null && gloss?.gloss==null && gloss?.problem==null)
                        Text("本机词库暂未收录此词。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    gloss?.problem?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                    if (!loading && gloss?.gloss == null) OutlinedTextField(meaningDraft,
                        onValueChange = { if (it.length <= 1000) meaningDraft = it }, label = { Text("填写释义后加入词书") }, modifier = Modifier.fillMaxWidth())
                    WordCollectionActions(owner, PersonalBookWord(surface = token.text, lemma = token.lemma,
                        meaning = gloss?.gloss?.chinese ?: meaningDraft, sentence = sentence.text,
                        articleId = entry.id, sourcePosition = sentence.id + ":" + token.start + "-" + token.end), exam = entry.exam)
                }
            }
        } }
        if(reference) item {
            val value = translation?.takeIf { it.isNotBlank() } ?: sentence.translation
            Text(if (entry.remote != null) (if (value.isNotBlank()) "AI 译文 · 已保存" else "AI 译文") else "参考译文",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if (value.isNotBlank()) {
                SelectionContainer { Text(value,fontSize=18.sp,lineHeight=29.sp,modifier=Modifier.padding(top=6.dp)) }

            }
            else {
                Text("使用已配置的 AI 接口，生成结果会保存在历史中。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onTranslate, enabled = !translationBusy) { Text(if (translationBusy) "正在 AI 翻译…" else "AI 翻译这句") }
            }
        }
        item {
            Text("句子主干",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val parts=remember(sentence) { intensiveMainClause(sentence) }
            if(parts.isEmpty()) Text("点选原句中的词语查看成分。",style=MaterialTheme.typography.bodyMedium)
            parts.forEach { (role,text) -> Row(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(role,Modifier.widthIn(min=40.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                Text(text,Modifier.weight(1f),lineHeight=26.sp)
            } }
            Text("机器句法分析，需结合语境核对。",Modifier.padding(top=10.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        studyError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        aiResult?.let { result -> item {
            Text("AI 分析 · 已保存", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(result, lineHeight = 28.sp) }
        } }
        item {
            TextButton(onClick={writing=!writing;focusEditor=writing}) { Text(if(writing) "收起我的翻译" else "写我的翻译") }
            if(writing) OutlinedTextField(value=note,onValueChange={ next ->
                if(next.length<=IntensiveTranslationLimit) { note=next;preferences.edit().putString(noteKey,next).apply() }
            },modifier=Modifier.fillMaxWidth().focusRequester(editorFocus),label={Text("我的翻译")},minLines=3,maxLines=7,
                supportingText={Text("草稿自动保存 · 保存译文后加入历史 · "+note.length+"/"+IntensiveTranslationLimit)})
            if (writing) Row {
                TextButton(onClick = { if (!saving) scope.launch {
                    saving = true; studyError = null
                    try {
                        study.manual(entry.progressKey, entry.subtitle + " · " + entry.title, sentence.id, sentence.text, note)
                        refreshRecords(); savedNotice = "这一版译文已保存到历史"
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { studyError = e.message ?: "译文未保存，请重试" }
                    finally { saving = false }
                } }, enabled = !saving && note.isNotBlank()) { Text("保存译文") }
                TextButton(onClick = { analyze("review", note) }, enabled = !saving && note.isNotBlank()) { Text("AI 点评我的翻译") }
            }
            savedNotice?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun ReadingDetailSheet(onClose: ()->Unit,content: @Composable ColumnScope.(requestClose: ()->Unit)->Unit) {
    val motion=rememberOverlayMotion(onClose={OverlayDismissGuard.arm();onClose()},predictive=OverlayMotion.enabledState,selfDrag=true)
    val maxHeight=LocalConfiguration.current.screenHeightDp.dp*.84f
    Dialog(onDismissRequest={motion.requestClose()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().overlayMotion(motion,fade=true).imePadding()) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha=.32f)).clickable { motion.requestClose() })
            Surface(onClick={},modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max=maxHeight),
                shape=RoundedCornerShape(topStart=24.dp,topEnd=24.dp),color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    Box(Modifier.padding(vertical=10.dp).width(36.dp).height(4.dp).align(Alignment.CenterHorizontally)
                        .background(MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(2.dp)))
                    content { motion.requestClose() }
                }
            }
        }
    }
}
