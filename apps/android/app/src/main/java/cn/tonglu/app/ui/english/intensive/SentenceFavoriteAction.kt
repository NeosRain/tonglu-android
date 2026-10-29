package cn.tonglu.app.ui.english.intensive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import cn.tonglu.app.data.english.intensive.IntensiveStudyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SentenceFavoriteAction(owner: String, articleKey: String, sentenceId: String, source: String) {
    val context = LocalContext.current
    val store = remember(owner) { IntensiveStudyStore(context, owner) }
    val scope = rememberCoroutineScope()
    var favorite by remember(articleKey, sentenceId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val changes by IntensiveStudyStore.changes.collectAsState()
    LaunchedEffect(owner, articleKey, sentenceId, changes) {
        try { favorite = store.favorites().any { it.articleKey == articleKey && it.sentenceId == sentenceId } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "句子收藏读取失败" }
    }
    IconButton(onClick = { if (!busy) scope.launch {
        busy = true
        val nextFavorite = !favorite
        try { store.favorite(articleKey, "文章精读", sentenceId, source, nextFavorite); favorite = nextFavorite; error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "句子收藏保存失败" }
        finally { busy = false }
    } }, enabled = !busy) {
        Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            error ?: if (favorite) "已收藏原句" else "收藏原句",
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
}
