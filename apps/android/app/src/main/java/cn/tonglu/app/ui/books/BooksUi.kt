package cn.tonglu.app.ui.books

import androidx.compose.foundation.BorderStroke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.books.*
import cn.tonglu.app.ui.AppColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
internal fun BooksPage(
    title: String,
    onBack: () -> Unit,
    example: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(AppColors.CanvasOpaque).safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            actions()
        }
        if (example) Text("示例数据 · 界面预览", Modifier.padding(16.dp, 4.dp), color = AppColors.TextSecondary)
        content()
    }
}

@Composable
internal fun BookActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
    ) { Text(label) }
}

@Composable
internal fun BookSecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.heightIn(min = 48.dp), enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
        border = BorderStroke(1.dp, if (enabled) AppColors.TextSecondary else AppColors.Divider),
    ) { Text(label) }
}

@Composable
internal fun <T> BookOptions(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label(selected)) }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { value ->
                DropdownMenuItem(text = { Text(label(value)) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}

@Composable
internal fun BookField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    limit: Int,
    multiline: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        TextField(
            value = value, onValueChange = { if (it.length <= limit) onChange(it) },
            modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 5 else 1, enabled = enabled, keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppColors.Surface, unfocusedContainerColor = AppColors.Surface,
                disabledContainerColor = AppColors.Surface,
            ),
        )
    }
}

@Composable
internal fun BookSection(content: @Composable ColumnScope.() -> Unit) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun BookImage(model: Any?, description: String?, modifier: Modifier, fit: ContentScale = ContentScale.Crop) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(AppColors.ChipIdle), contentAlignment = Alignment.Center) {
        if (model == null) Icon(Icons.Outlined.MenuBook, contentDescription = description, tint = AppColors.TextMuted)
        else AsyncImage(model = model, contentDescription = description, modifier = Modifier.matchParentSize(), contentScale = fit)
    }
}

@Composable
internal fun BookErrorText(message: String?, onRetry: (() -> Unit)? = null) {
    if (message != null) {
        Text(message, color = AppColors.Danger, style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
internal fun BookLoading() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppColors.Accent)
    }
}

internal data class BookLoad<T>(val value: T? = null, val error: BookError? = null)

@Composable
internal fun <T> bookLoad(vararg keys: Any?, load: suspend () -> T): BookLoad<T> {
    var result by remember(*keys) { mutableStateOf(BookLoad<T>()) }
    LaunchedEffect(*keys) {
        result = bookResult(load).fold(
            onSuccess = { BookLoad(value = it) },
            onFailure = { BookLoad(error = it.toBookError()) },
        )
    }
    return result
}

internal class BookOperation {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    suspend fun <T> run(
        onCampus: () -> Unit,
        onConflict: (String) -> Unit,
        block: suspend () -> T,
        onSuccess: (T) -> Unit,
    ) {
        if (busy) return
        busy = true
        error = null
        notice = null
        try {
            bookResult(block).onSuccess(onSuccess).onFailure {
                val failure = it.toBookError()
                error = failure.message
                if (failure.conflict) onConflict(failure.message)
                if (failure.campusRequired) onCampus()
            }
        } finally {
            busy = false
        }
    }
}

internal val LocalBookOperation = staticCompositionLocalOf<BookOperation?> { null }

@Composable
internal fun bookPageOperation(): BookOperation = LocalBookOperation.current ?: remember { BookOperation() }

@Composable
internal fun rememberBookMutation(
    operation: BookOperation,
    onCampus: () -> Unit,
    onConflict: (String) -> Unit = {},
): (suspend () -> Unit, () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    return { block, done ->
        scope.launch { operation.run(onCampus, onConflict, block) { done() } }
    }
}

internal fun String.bookOptional(): String? = trim().takeIf(String::isNotEmpty)
