package cn.tonglu.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.SessionStore
import kotlinx.coroutines.delay
import java.security.MessageDigest

/** 同窗口就地编辑；收起后不拦截原文，草稿按身份与锚点独立保存。 */
@Composable
internal fun ReaderNotePanel(
    draftIdentity: String,
    title: String,
    quote: String,
    initialBody: String,
    modifier: Modifier = Modifier,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val owner = SessionStore.currentOwner(context)
    val prefs = remember(context) { context.getSharedPreferences("reading_note_drafts", android.content.Context.MODE_PRIVATE) }
    val key = remember(owner, draftIdentity) {
        MessageDigest.getInstance("SHA-256").digest((owner + "\u0000" + draftIdentity).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    var text by rememberSaveable(key) { mutableStateOf(prefs.getString(key, null) ?: initialBody) }
    var collapsed by rememberSaveable(key) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draftSaved by remember { mutableStateOf(prefs.contains(key)) }
    val latestText = remember(key) { arrayOf(text) }
    SideEffect { latestText[0] = text }
    // 不用 Compose 状态保存 done：清理草稿不能被一次组合销毁重新写回。
    val completed = remember(key) { booleanArrayOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(key) {
        onDispose { if (!completed[0]) prefs.edit().putString(key, latestText[0]).apply() }
    }
    LaunchedEffect(key, text) {
        draftSaved = false
        delay(400)
        if (!completed[0]) { prefs.edit().putString(key, text).apply(); draftSaved = true }
    }
    Surface(modifier = modifier.fillMaxWidth().animateContentSize(), color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("阅读笔记", fontWeight = FontWeight.SemiBold)
                    Text(if (draftSaved) "草稿已存本机" else "正在保存草稿…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { keyboard?.hide(); collapsed = !collapsed }) {
                    Text(if (collapsed) "继续写" else "收起看原文")
                }
                TextButton(onClick = { keyboard?.hide(); onDismiss() }) { Text("关闭") }
            }
            if (!collapsed) {
                if (quote.isNotBlank()) Text(quote, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
                OutlinedTextField(value = text, onValueChange = { text = it; error = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 190.dp),
                    placeholder = { Text("写下想法，支持 Markdown") }, maxLines = 7)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("删除") }
                    TextButton(enabled = text.isNotBlank(), onClick = {
                        try {
                            completed[0] = true
                            onSave(text.trim())
                            prefs.edit().remove(key).apply()
                            keyboard?.hide()
                        } catch (e: Exception) {
                            completed[0] = false
                            error = "保存失败，草稿已保留，请重试"
                        }
                    }) { Text("保存笔记") }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除这条笔记？") }, text = { Text("删除正式笔记，并清除此处草稿。") },
        confirmButton = { TextButton(onClick = {
            try {
                completed[0] = true
                onDelete?.invoke()
                prefs.edit().remove(key).apply()
            } catch (e: Exception) { completed[0] = false; error = "删除失败，内容已保留" }
            confirmDelete = false
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}
