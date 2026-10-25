package cn.tonglu.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import cn.tonglu.app.data.speech.SpeechPurpose

/** 例句保留原排版，点击英文单词朗读该词。 */
@Composable
internal fun ClickableExample(text: String, onRead: (String, SpeechPurpose) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val annotated = remember(text, accent) {
        buildAnnotatedString {
            exampleTokens(text).forEach { token ->
                if (token.spoken) {
                    pushStringAnnotation("单词", token.text)
                    withStyle(SpanStyle(color = accent)) { append(token.text) }
                    pop()
                } else append(token.text)
            }
        }
    }
    Column {
        ClickableText(annotated, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            onClick = { offset -> annotated.getStringAnnotations("单词", offset, offset).firstOrNull()?.let {
                onRead(it.item, SpeechPurpose.Word)
            } })
        TextButton(onClick = { onRead(text, SpeechPurpose.Sentence) }) { Text("朗读整句") }
        Text("点例句中的英文单词可单独朗读", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
