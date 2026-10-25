package cn.tonglu.app.ui.english.intensive

import android.content.Context
import cn.tonglu.app.data.english.intensive.IntensiveCodec
import cn.tonglu.app.data.english.intensive.IntensiveGloss
import cn.tonglu.app.data.english.intensive.decodeIntensiveGlossBook
import cn.tonglu.app.ui.learning.ReadingWordBank
import cn.tonglu.app.ui.learning.WordLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

internal data class IntensiveGlossResult(val gloss: IntensiveGloss?,val problem: String?=null)

/** Read public bundled dictionaries without importing a book or changing study settings. */
internal class IntensiveWordLookup(context: Context,owner: String) {
    private val app=context.applicationContext
    private val imported=ReadingWordBank(app,owner)
    private val mutex=Mutex()
    private var bundled: Map<String,IntensiveGloss>?=null
    private var bundleProblem: String?=null
    suspend fun lookup(lemma: String): IntensiveGlossResult = withContext(Dispatchers.IO) {
        var importedProblem: String?=null
        try {
            val hit=imported.lookup(lemma)
            if(hit is WordLookup.Found) return@withContext IntensiveGlossResult(
                IntensiveGloss(hit.hit.english,hit.hit.chinese,hit.hit.phoneticUk,hit.hit.phoneticGeneral,"已导入词库"))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            importedProblem="已导入词库读取失败"
            android.util.Log.w("IntensiveLookup","imported dictionary",e)
        }
        val words=mutex.withLock {
            bundled ?: run {
                val manifest=app.assets.open("english/bundled/catalog.json").bufferedReader().use { JSONObject(it.readText()) }
                val books=manifest.getJSONArray("books")
                val rows=linkedMapOf<String,IntensiveGloss>()
                val failed=mutableListOf<String>()
                for(index in 0 until books.length()) {
                    val book=books.getJSONObject(index)
                    try {
                        val asset=book.getString("asset")
                        require(asset.matches(Regex("[a-z0-9-]+\\.book")))
                        val bytes=app.assets.open("english/bundled/"+asset).use { it.readBytes() }
                        require(bytes.size==book.getInt("bytes") && IntensiveCodec.hash(bytes)==book.getString("sha256"))
                        val unit=decodeIntensiveGlossBook(bytes,book.getString("sha256"),book.getInt("count"),book.getString("name"))
                        unit.forEach { (key,value)->rows.putIfAbsent(key,value) }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) {
                        failed+=book.optString("id")
                        android.util.Log.w("IntensiveLookup","bundled dictionary "+book.optString("id"),e)
                    }
                }
                bundleProblem=if(failed.isNotEmpty()) "部分随包词库暂时无法读取" else null
                rows.toMap().also { bundled=it }
            }
        }
        val gloss=words[lemma.trim().lowercase(Locale.ROOT)]
        IntensiveGlossResult(gloss,listOfNotNull(importedProblem,bundleProblem).joinToString("；").takeIf { it.isNotEmpty() })
    }
}
