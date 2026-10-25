package cn.tonglu.app.ui.learning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.english.books.PersonalBookWord
import cn.tonglu.app.data.english.books.PersonalBooksSnapshot
import cn.tonglu.app.data.english.books.PersonalWordBookStore
import kotlinx.coroutines.launch

/**
 * 一次「收藏到个人词书」要落库的全部字段。
 *
 * EF04 修复A 要求**原型、释义、来源原句与文章位置一个不少** ——
 * 所以从词卡到弹层用一份显式草稿传递，而不是让弹层自己回头再查一遍词卡状态
 * （弹层开着的时候用户可能已经点了别的词，回头查就会把 A 词写进 B 词的草稿）。
 */
data class CollectWordDraft(
    val surface: String,
    val lemma: String,
    val meaning: String,
    val sentence: String,
    val articleId: String,
    /** 词在**原文**里的 UTF-16 区间（句子起点 + 句内偏移），用于词书里回看定位；拿不到时为空串。 */
    val sourcePosition: String,
) {
    fun toBookWord() = PersonalBookWord(
        surface = surface, lemma = lemma, meaning = meaning,
        sentence = sentence, articleId = articleId, sourcePosition = sourcePosition)
}

/**
 * 精读点词「收藏」的目标书选择（EF04 修复A 接线端，组件归 A，数据层归 B）。
 *
 * 契约（work/workbuddy-B/HANDOFF.md）：
 * - 先选书（列出全部个人词书）或新建一本，**未选书不写入**；
 * - `store.add` 成功才报「已收藏」；返回 false（单本内去重命中）报「已在这本书里」；
 * - 异常**原样提示**，不静默吞、不假报成功。
 *
 * 收藏不再是切换语义：取消收藏在个人词书管理里做（修复B），
 * 词卡上的星标只负责「加入」，连点不会再把词取消掉。
 */
@Composable
internal fun CollectToBookDialog(
    owner: String,
    word: CollectWordDraft,
    onDismiss: () -> Unit,
    /** alreadyThere=true = 那本书早就有这个词（store 返回 false），宿主按「已收藏」口径提示。 */
    onCollected: (bookName: String, alreadyThere: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(owner) { PersonalWordBookStore(context, owner) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(owner) { mutableStateOf<PersonalBooksSnapshot?>(null) }
    var loadError by remember(owner) { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loadError = null
            try { snapshot = store.snapshot() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { loadError = cause.message ?: "词书读取失败" }
        }
    }
    LaunchedEffect(owner) { reload() }

    fun addTo(bookId: String, bookName: String) {
        if (saving) return
        saving = true; error = null
        scope.launch {
            try {
                val fresh = store.add(bookId, word.toBookWord())
                saving = false
                onCollected(bookName, !fresh)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { saving = false; error = cause.message ?: "收藏失败，请重试" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("收藏到个人词书") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("「${word.surface}」", fontWeight = FontWeight.SemiBold)
                if (word.sentence.isNotBlank()) {
                    Text("来源原句会一并保存，之后可在词书里回看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                loadError?.let {
                    Text("读取失败：$it", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { reload() }) { Text("重试") }
                }
                val books = snapshot?.books.orEmpty()
                if (creating) {
                    OutlinedTextField(value = newName, onValueChange = { if (it.length <= 40) newName = it },
                        label = { Text("词书名（1–40 字）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !saving, onClick = { creating = false; newName = ""; error = null }) { Text("返回选书") }
                        TextButton(enabled = !saving && newName.isNotBlank(), onClick = {
                            scope.launch {
                                saving = true; error = null
                                try {
                                    val book = store.create(newName)
                                    store.add(book.id, word.toBookWord())
                                    saving = false
                                    onCollected(book.name, false)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (cause: Exception) { saving = false; error = cause.message ?: "新建失败，请重试" }
                            }
                        }) { Text("新建并收藏") }
                    }
                } else {
                    if (snapshot != null && books.isEmpty()) {
                        Text("还没有个人词书，先新建一本。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    books.forEach { book ->
                        Row(Modifier.fillMaxWidth()
                            .clickable(enabled = !saving) { addTo(book.id, book.name) }
                            .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(book.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${book.words.size} 词", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    TextButton(enabled = !saving && books.size < PersonalWordBookStore.MAX_BOOKS,
                        onClick = { creating = true; error = null }) { Text("＋ 新建词书") }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("个人词书仅保存在本机（按账号隔离），不上传服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } },
    )
}
