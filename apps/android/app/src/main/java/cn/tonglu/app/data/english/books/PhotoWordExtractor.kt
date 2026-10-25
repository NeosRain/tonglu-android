package cn.tonglu.app.data.english.books

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OCR 候选词：保留识别原文、所在行与置信度；confidence < 0.7 由界面标「低置信」。 */
data class OcrCandidate(
    val surface: String,
    val confidence: Float,
    val lineText: String,
    val lowConfidence: Boolean = confidence < 0.7f,
) {
    val key: String get() = PersonalBookWord.normalize(surface)
}

/**
 * EF09 拍照识词的本地 OCR（ML Kit on-device，拉丁模型）。
 *
 * 🔴 照片只在本机识别，**不上传**；识别结果只是**候选**，
 * 必须经用户校对（改词/补释义）并由用户确认后才进词书 —— 低置信识别绝不静默当正确词。
 */
object PhotoWordExtractor {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** 词形过滤：拉丁字母起头，可含 ' ’ -，总长 2–32；排除纯单字母与数字串。 */
    private val wordRegex = Regex("^[A-Za-z][A-Za-z'’\\-]{1,31}$")

    suspend fun extract(context: Context, uri: Uri): List<OcrCandidate> {
        val image = InputImage.fromFilePath(context, uri)
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        val seen = LinkedHashMap<String, OcrCandidate>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val raw = element.text.trim().trim('.', ',', ';', ':', '"', '\'', '（', '）', '(', ')')
                    val word = raw.trimEnd('\'', '’')
                    if (!word.matches(wordRegex)) continue
                    val key = PersonalBookWord.normalize(word)
                    if (key.isBlank()) continue
                    // 同词取置信度更高的一次出现；原型/释义由用户校对补充
                    val candidate = OcrCandidate(word, element.confidence, line.text)
                    val old = seen[key]
                    if (old == null || candidate.confidence > old.confidence) seen[key] = candidate
                }
            }
        }
        return seen.values.sortedWith(compareByDescending<OcrCandidate> { it.confidence }.thenBy { it.surface })
    }
}
