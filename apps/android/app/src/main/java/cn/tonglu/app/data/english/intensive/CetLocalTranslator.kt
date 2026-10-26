package cn.tonglu.app.data.english.intensive

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-initiated model download; source text and resulting translations stay on the device. */
class CetLocalTranslator(context: Context, owner: String) : AutoCloseable {
    private val store = context.getSharedPreferences("cet-translation-" + IntensiveCodec.hash(owner.toByteArray()), 0)
    private val engine = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.CHINESE).build())
    private val mutex = Mutex()
    private var ready = false
    private fun key(text: String) = "mlkit-en-zh-v1:" + IntensiveCodec.hash(text.toByteArray())
    fun cached(text: String): String? = store.getString(key(text), null)?.takeIf { it.isNotBlank() }
    suspend fun translate(text: String): String = mutex.withLock {
        cached(text)?.let { return@withLock it }
        if (!ready) {
            engine.downloadModelIfNeeded(DownloadConditions.Builder().build()).resultAwait()
            ready = true
        }
        engine.translate(text).resultAwait().also {
            require(it.isNotBlank()) { "本机翻译未返回译文" }
            store.edit().putString(key(text), it).apply()
        }
    }
    override fun close() = engine.close()
}

private suspend fun <T> Task<T>.resultAwait(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
