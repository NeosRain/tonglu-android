package cn.tonglu.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 学校入口和文件导入共用预览；仅用户确认后保存，不安装/执行远端适配脚本。 */
@Composable
fun ScheduleImportScreen(
    onClose: () -> Unit,
    onSave: (List<ParsedCourse>, Boolean, (Boolean, String) -> Unit) -> Unit,
    onSaveExternal: (ExternalSchedule, Boolean, (Boolean, String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val owner = SessionStore.currentOwner(context)
    val prefs = remember { context.getSharedPreferences("schedule_school", android.content.Context.MODE_PRIVATE) }
    var route by rememberSaveable { mutableStateOf("home") }
    var showSchools by rememberSaveable { mutableStateOf(false) }
    var schoolQuery by rememberSaveable { mutableStateOf("") }
    var school by rememberSaveable(owner) { mutableStateOf(prefs.getString("name@$owner", "").orEmpty()) }
    var address by rememberSaveable(owner, stateSaver = Saver<String, String>(
        save = { runCatching { ExternalScheduleParser.officialUrl(it) }.getOrDefault("") }, restore = { it },
    )) { mutableStateOf(prefs.getString("url@$owner", "").orEmpty()) }
    var payload by rememberSaveable { mutableStateOf("") }
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    var readAttempt by remember { mutableIntStateOf(0) }
    var parsed by remember { mutableStateOf<ExternalSchedule?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(parsed) { if (parsed != null) listState.animateScrollToItem(if (message == null) 4 else 5) }
    fun parse(text: String) {
        scope.launch {
            busy = true; parsed = null; message = null
            try { parsed = withContext(Dispatchers.Default) { ExternalScheduleParser.parse(text) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "无法识别课表，请检查文件" }
            finally { busy = false }
        }
    }
    LaunchedEffect(uri, readAttempt) {
        val selected = uri ?: return@LaunchedEffect
        busy = true; message = null; parsed = null
        try {
            parsed = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(android.net.Uri.parse(selected))?.use {
                    ExternalScheduleParser.read(it)
                } ?: error("文件无法打开，请重新选择")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { message = error.message ?: "读取文件失败，请重新选择" }
        finally { busy = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            uri = null
            uri = selected.toString()
            readAttempt++
        }
    }
    BackHandler(enabled = route != "home") { route = "home" }
    if (showSchools) AlertDialog(onDismissRequest = { showSchools = false }, title = { Text("辽宁高校") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(schoolQuery, { schoolQuery = it.take(80) }, label = { Text("搜索学校") }, singleLine = true)
            Text("首批 6 所官方入口，课表读取待各校实测。未列出的学校可填写官方地址或导入文件。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(SchoolDirectory.search(schoolQuery)) { entry ->
                    TextButton(onClick = { school = entry.name; address = entry.url; showSchools = false; message = null }, modifier = Modifier.fillMaxWidth()) { Text(entry.name) }
                }
                if (SchoolDirectory.search(schoolQuery).isEmpty()) item { Text("未找到，可在下方填写学校官方地址") }
            }
        } }, confirmButton = { TextButton(onClick = { showSchools = false }) { Text("关闭") } })
    when (route) {
        "liaodong" -> LiaodongScheduleImportScreen(onClose = { route = "home" }, onSave = onSave)
        "browser" -> CampusBrowserScreen(address, school.ifBlank { "学校教务" }, { route = "home" },
            initialDesktopMode = true, onReadPage = { html -> route = "home"; uri = null; parse(html) })
        else -> Surface(Modifier.fillMaxSize(), color = AppColors.CanvasOpaque) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text("导入课表", fontWeight = FontWeight.SemiBold)
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Text("选择适合你的导入方式", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("课程、周次与作息先预览，再保存。学校登录只在本机网页完成。", color = AppColors.TextSecondary)
                    }
                    item {
                        OutlinedCard(onClick = { route = "liaodong" }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text("辽东学院", fontWeight = FontWeight.SemiBold)
                                Text("保留现有教务导入方式", color = AppColors.TextSecondary)
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceOpaque)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("其他学校 · 官方教务", fontWeight = FontWeight.SemiBold)
                                OutlinedButton(onClick = { showSchools = true }) { Text("选择辽宁高校") }
                                OutlinedTextField(school, { school = it.take(80) }, label = { Text("学校名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(address, { address = it.take(2048) }, label = { Text("学校官方教务 HTTPS 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                SchoolDirectory.liaoning.firstOrNull { it.name == school && it.url == address }?.let { entry ->
                                    Text(entry.guide + "\n官方入口已核对 · 课表读取待实测", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                }
                                Text("在官方页面自行登录，打开课表后点「读取课表」。支持通用 HTML 表格；无法识别时可使用导出文件。", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                Button(enabled = !busy, onClick = {
                                    try { address = ExternalScheduleParser.officialUrl(address); route = "browser" }
                                    catch (error: Exception) { message = error.message }
                                }) { Text("打开学校教务") }
                            }
                        }
                    }
                    item {
                        Text("从文件导入", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("拾光单课表 JSON、金智/正方 JSON、HTML 课表。文件只在本机解析，最多 2 MB。", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        Text("迁移课程、周次、固定作息和开学日期；颜色、备注与学分暂不迁移。", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("选择课表文件") }
                        OutlinedTextField(payload, { payload = it.take(64_000) }, label = { Text("或粘贴课表 JSON / HTML") }, modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), minLines = 2, maxLines = 5)
                        TextButton(enabled = !busy && payload.isNotBlank(), onClick = { uri = null; parse(payload) }) { Text("解析并预览") }
                    }
                    if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    message?.let { text -> item { Text(text, color = AppColors.TextSecondary) } }
                    parsed?.let { data ->
                        item {
                            Text("导入预览 · ${data.courses.size} 条课程", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            if (data.courses.any { it.source == "demo" }) Text("示例课表 · 仅用于体验", color = AppColors.Accent)
                            Text(data.format + " · " + (if (data.periods.isEmpty()) "沿用当前作息，请核对下方时间" else "包含 ${data.periods.size} 节作息") +
                                (data.termStart?.let { " · 开学 $it" } ?: " · 沿用当前开学日期"), color = AppColors.TextSecondary)
                            if (data.periods.isNotEmpty()) Text(data.periods.joinToString("\n") { "第${it.period}节 ${it.start}–${it.end}" }, style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(replace, { replace = it }, enabled = !busy)
                                Text("替换原导入课程（手动课程保留）", modifier = Modifier.weight(1f))
                            }
                            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                busy = true
                                onSaveExternal(data, replace) { ok, result ->
                                    busy = false; message = result
                                    if (ok) {
                                        val safeAddress = runCatching { ExternalScheduleParser.officialUrl(address) }.getOrDefault("")
                                        val saved = prefs.edit().putString("name@$owner", school).putString("url@$owner", safeAddress).commit()
                                        if (!saved) message = "$result；学校入口未记住，请重新填写"
                                        parsed = null; uri = null; payload = ""
                                    }
                                }
                            }) { Text(if (data.courses.any { it.source == "demo" }) "导入示例课表（${data.courses.size}条）" else "确认导入 ${data.courses.size} 条课程") }
                        }
                        items(data.courses) { course ->
                            Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceOpaque)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(course.name, fontWeight = FontWeight.SemiBold)
                                    Text("${ScheduleText.weekdayLabel(course.weekday)} · ${course.startPeriod}-${course.endPeriod}节 · ${course.weekLabel()}")
                                    val slots = data.periods.ifEmpty { PeriodTable.slots() }
                                    Text("${slots.firstOrNull { it.period == course.startPeriod }?.start ?: "?"}–${slots.firstOrNull { it.period == course.endPeriod }?.end ?: "?"} · ${course.location ?: "未填写教室"}", color = AppColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
