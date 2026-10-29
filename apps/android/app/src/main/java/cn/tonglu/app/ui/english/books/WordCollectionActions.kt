package cn.tonglu.app.ui.english.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.english.books.*
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WordCollectionActions(owner: String, word: PersonalBookWord, exam: String? = null,
    revision: Any? = null, existingOnly: Boolean = false, onCount: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(owner) { PersonalWordBookStore(context, owner) }
    val scope = rememberCoroutineScope()
    var count by remember(owner, word.key) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember(word.key) { mutableStateOf<String?>(null) }
    var destination by remember(word.key) { mutableStateOf<String?>(null) }
    var previousWord by remember(word.key) { mutableStateOf<PersonalBookWord?>(null) }
    val changes by PersonalWordBookStore.changes.collectAsState()
    val updatedCount by rememberUpdatedState(onCount)
    LaunchedEffect(owner, word.key, revision, changes) {
        try { previousWord = store.collectedWord(word.key); count = store.count(previousWord?.key ?: word.key); updatedCount(count) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { problem = "收藏状态读取失败，请重新打开词卡" }
    }
    fun save(again: Boolean) {
        if (busy) return
        scope.launch {
            busy = true; problem = null
            try {
                val canonical = word.copy(lemma = previousWord?.lemma ?: word.lemma,
                    meaning = word.meaning.ifBlank { previousWord?.meaning.orEmpty() })
                destination = store.collect(canonical, exam, again)
                count = store.count(canonical.key)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { problem = e.message ?: "未能保存，请重试" }
            finally { busy = false }
        }
    }
    if (existingOnly && count == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = AppColors.SurfaceOpaque) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { save(false) }, enabled = !busy && count == 0 && word.meaning.isNotBlank()) {
                    Icon(if (count > 0) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        if (count > 0) "已加入单词书" else "加入单词书",
                        tint = if (count > 0) AppColors.Accent else AppColors.TextSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(if (count > 0) "已加入 · $count 次" else "加入${collectionBookName(exam)}",
                        color = if (count > 0) AppColors.Accent else AppColors.TextPrimary)
                }
                if (count > 0) TextButton(onClick = { save(true) }, enabled = !busy && (word.meaning.isNotBlank() || previousWord?.meaning?.isNotBlank() == true)) {
                    Text("＋1", color = AppColors.Accent)
                }
            }
        }
        destination?.let { Text("已保存到《$it》", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary) }
        problem?.let { Text(it, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall) }
    }
}
