package cn.tonglu.app.data.books

import android.content.Context
import android.net.Uri
import android.util.Log
import cn.tonglu.app.data.ApiFactory
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.TongluApi
import cn.tonglu.app.data.community.readEmojiFile
import cn.tonglu.app.data.community.requireHttpsUrl
import cn.tonglu.app.data.toFriendlyMessage
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException

internal class BooksRepository(private val viewModel: AppViewModel, private val sessionId: String) {
    private val photoUrls = mutableMapOf<String, String>()

    suspend fun <T> call(block: suspend TongluApi.(String) -> T): T =
        viewModel.communityAuthorized(sessionId, block)

    suspend fun list(
        myCourses: Boolean = false,
        course: String? = null,
        query: String = "",
        condition: BookCondition? = null,
        sort: String = "new",
        cursor: String? = null,
        includeReserved: Boolean = true,
        limit: Int = 20,
    ): BookPage {
        val filters = buildMap {
            put("sort", sort)
            put("includeReserved", if (includeReserved) "1" else "0")
            put("limit", limit.toString())
            course?.let { put("course", it) }
            query.trim().takeIf(String::isNotEmpty)?.let { text ->
                val isbn = normalizeBookIsbn(text)
                if (isbn == null) put("q", text) else put("isbn", isbn)
            }
            condition?.let { put("condition", it.name) }
            cursor?.let { put("cursor", it) }
        }
        return call { if (myCourses) booksForMyCourses(it, filters) else bookListings(it, filters) }
    }

    suspend fun countForCourse(course: String): Int {
        var cursor: String? = null
        var total = 0
        do {
            val page = list(course = course, cursor = cursor, includeReserved = false, limit = 50)
            total += page.items.size
            cursor = page.nextCursor
        } while (cursor != null)
        return total
    }

    suspend fun lookup(isbn: String): BookMetadata? = try {
        call { bookIsbn(it, isbn) }
    } catch (error: HttpException) {
        if (error.code() != 404) throw error
        null
    }

    suspend fun photoUrl(id: String): String {
        photoUrls[id]?.let { return it }
        val photo = call { bookPhoto(it, id) }
        if (photo.status != "READY") throw IOException("这张书籍图片尚不可用")
        requireHttpsUrl(photo.publicUrl)
        return photo.publicUrl.also { photoUrls[id] = it }
    }

    suspend fun upload(context: Context, uri: Uri): BookPhoto {
        val image = readEmojiFile(context, uri)
        val data = image.first
        val mime = image.second
        val ticket = call { beginBookPhoto(it, BeginBookPhoto(data.size, mime)) }
        requireHttpsUrl(ticket.uploadUrl)
        check(ticket.method == "PUT" && ticket.contentType == mime) { "图床上传参数异常，请重试" }
        withContext(Dispatchers.IO) {
            ApiFactory.storageClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(Request.Builder().url(ticket.uploadUrl).put(data.toRequestBody(mime.toMediaType())).build())
                .execute().use { if (!it.isSuccessful) throw IOException("图片直传失败（HTTP ${it.code}）") }
        }
        return call { completeBookPhoto(it, ticket.id, emptyMap()) }
    }
}

internal suspend fun <T> bookResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

internal fun Throwable.toBookError(): BookError {
    if (this !is HttpException) {
        // 联系方式和签名上传 URL 不进入日志。
        Log.w("TongluBooks", "二手书操作失败（${javaClass.simpleName}）")
        return BookError(toFriendlyMessage().takeIf { text -> text.any { it in '\u4e00'..'\u9fff' } }
            ?: "二手书服务暂时不可用，请稍后重试")
    }
    val status = code()
    Log.w("TongluBooks", "二手书请求失败（HTTP $status）")
    val body = try {
        response()?.errorBody()?.string()?.let(JsonParser::parseString)?.takeIf { it.isJsonObject }?.asJsonObject
    } catch (error: JsonParseException) {
        Log.w("TongluBooks", "二手书错误响应无法解析（${error.javaClass.simpleName}）")
        null
    } catch (error: IOException) {
        Log.w("TongluBooks", "二手书错误响应无法读取（${error.javaClass.simpleName}）")
        null
    }
    fun field(name: String) = body?.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    return bookHttpError(status, field("code"), field("message"))
}
