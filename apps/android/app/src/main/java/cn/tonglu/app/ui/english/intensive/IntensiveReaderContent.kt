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
    val translator = remember(owner) { CetLocalTranslator(context, owner) }
    DisposableEffect(translator) { onDispose { translator.close() } }
    val scope = rememberCoroutineScope()
    var translated by remember(article, owner) { mutableStateOf(sentences.mapNotNull { row ->
        (row.translation.takeIf { it.isNotBlank() } ?: translator.cached(row.text))?.let { row.id to it }
    }.toMap()) }
    var translationBusy by remember { mutableStateOf(false) }
    var translationError by remember { mutableStateOf<String?>(null) }
    var translationJob by remember { mutableStateOf<Job?>(null) }
    var syntax by rememberSaveable(entry.progressKey) { mutableStateOf(true) }
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) { listOf("主语", "谓语", "宾语", "定语", "状语", "补语", "从句").associateWith { readingRoleColor(it, scheme) } }
    fun translateRows(rows: List<IntensiveSentence>) {
        if (translationBusy) return
        translationJob = scope.launch {
            translationBusy = true; translationError = null
            try {
                for (row in rows) if (translated[row.id].isNullOrBlank()) {
                    val value = translator.translate(row.text)
                    translated = translated + (row.id to value)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("CetTranslation", "local translation failed", e)
                translationError = "本机翻译暂未完成，请检查网络后重试；已生成的译文会保留。"
            } finally { translationBusy = false }
        }
    }
    var showTranslation by rememberSaveable(entry.progressKey) { mutableStateOf(false) }
    var selectedId by rememberSaveable(entry.progressKey) { mutableStateOf<String?>(null) }
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
                }) { Text(if(showTranslation) "隐藏译文" else "Google 翻译") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={speech.stop()}) { Icon(Icons.Outlined.Stop,"停止朗读") }
                Box {
                    IconButton(onClick={more=true}) { Icon(Icons.Outlined.MoreVert,"阅读更多操作") }
                    DropdownMenu(more,onDismissRequest={more=false}) {
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
                TextButton(onClick = { syntax = !syntax }) { Text(if (syntax) "语法分色：开" else "语法分色：关") }
                listOf("主语", "谓语", "宾语").forEach { role -> Text(role, color = colors.getValue(role), fontSize = 12.sp) }
            }
            if (showTranslation && article.entry.remote != null) Text("本机机器译文仅辅助理解，请结合原文核对。首次需下载语言模型，建议使用 Wi‑Fi。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (translationBusy) Text("正在准备模型或本机翻译…", style = MaterialTheme.typography.bodySmall)
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
                        intensiveSentenceAt(paragraph,offset)?.let { speech.stop();focusedId=it.id;selectedId=it.id }
                    })
                }
                val paragraphTranslation = paragraph.translation.takeIf { it.isNotBlank() }
                    ?: paragraph.sentences.mapNotNull { translated[it.id] }.joinToString(" ")
                if(showTranslation && paragraphTranslation.isNotBlank()) {
                    Text(paragraphTranslation,fontSize=(fontSize-1).sp,lineHeight=(fontSize*1.6f).sp,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                    if (entry.remote != null) GoogleTranslationAttribution()
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
                    translationBusy = translationBusy, onTranslate = { translateRows(listOf(selected)) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(modifier=Modifier.weight(1f),enabled=position>0,onClick={speech.stop();selectedId=sentences[position-1].id;focusedId=selectedId}) { Text("上一句") }
                TextButton(modifier=Modifier.weight(1f),onClick={mark(selected)}) { Text(if(selected.id in read) "✓ 已读" else "标为已读") }
                TextButton(modifier=Modifier.weight(1f),enabled=position<sentences.lastIndex,onClick={speech.stop();selectedId=sentences[position+1].id;focusedId=selectedId}) { Text("下一句") }
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
                item { Text(if (entry.remote != null) "句法：Stanza EWT GPU 预分析位置；译文：按需下载模型后在本机生成。原卷从标明来源读取，仅保存在应用私有缓存。"
                    else "句法：Stanza EWT；翻译：opus-mt-en-zh。电脑 GPU 预计算，手机离线查看。",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                if (entry.remote != null) item {
                    Text("Google Translate 本机机器翻译：原文及译文不发送至 Google；SDK 可能联网更新模型并发送运行和使用指标。Google 不对机器译文的准确性、可靠性或适用性作保证，请核对原文。",
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val uri = LocalUriHandler.current
                    TextButton(onClick={uri.openUri("https://developers.google.com/ml-kit/terms")}) { Text("翻译条款与隐私") }
                    TextButton(onClick={uri.openUri("https://cloud.google.com/translate")}) { Text("Google Translate 说明") }
                }
            }
        }
    }
}

@Composable
private fun GoogleTranslationAttribution() {
    val image = if (MaterialTheme.colorScheme.surface.luminance() < .5f)
        R.drawable.google_translate_white else R.drawable.google_translate_color
    Image(painterResource(image), "powered by Google Translate",
        Modifier.padding(top=8.dp).height(16.dp).aspectRatio(11f))
}

@Composable
private fun SentenceDetails(entry: IntensiveEntry,sentence: IntensiveSentence,preferences: SharedPreferences,
    dictionary: IntensiveWordLookup,play: ()->Unit,lookup: (String)->Unit,modifier: Modifier,
    translation: String? = null, translationBusy: Boolean = false, onTranslate: () -> Unit = {}) {
    var tokenId by rememberSaveable(sentence.id) { mutableIntStateOf(0) }
    var reference by rememberSaveable(sentence.id) { mutableStateOf(true) }
    val noteKey=remember(entry.progressKey,sentence.id) { intensiveTranslationKey(entry,sentence) }
    var note by remember(noteKey) { mutableStateOf(preferences.getString(noteKey,"") ?: "") }
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
                }
            }
        } }
        if(reference) item {
            Text(if (entry.remote != null) "本机译文 · 机器结果请核对" else "参考译文",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val value = translation?.takeIf { it.isNotBlank() } ?: sentence.translation
            if (value.isNotBlank()) {
                SelectionContainer { Text(value,fontSize=18.sp,lineHeight=29.sp,modifier=Modifier.padding(top=6.dp)) }
                if (entry.remote != null) GoogleTranslationAttribution()
            }
            else {
                Text("首次需下载语言模型，建议使用 Wi‑Fi。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onTranslate, enabled = !translationBusy) { Text(if (translationBusy) "正在本机翻译…" else "使用 Google 翻译这句") }
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
        item {
            TextButton(onClick={writing=!writing;focusEditor=writing}) { Text(if(writing) "收起我的翻译" else "写我的翻译") }
            if(writing) OutlinedTextField(value=note,onValueChange={ next ->
                if(next.length<=IntensiveTranslationLimit) { note=next;preferences.edit().putString(noteKey,next).apply() }
            },modifier=Modifier.fillMaxWidth().focusRequester(editorFocus),label={Text("我的翻译")},minLines=3,maxLines=7,
                supportingText={Text("仅本机自动保存 · "+note.length+"/"+IntensiveTranslationLimit)})
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
