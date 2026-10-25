package cn.tonglu.app.data

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import retrofit2.HttpException
import java.io.IOException

/** 只管理应用账号凭据，不保存或同步教务登录信息。 */
class SessionManager(
    private val store: SessionStore,
    private val api: TongluApi,
    private val onRefreshed: (LocalSession) -> Unit,
) {
    private val mutationMutex = Mutex()
    private val refreshMutex = Mutex()
    @Volatile private var current: LocalSession? = null

    suspend fun restore(): LocalSession? = mutationMutex.withLock {
        withContext(Dispatchers.IO) { store.read() }.also { current = it }
    }

    suspend fun signIn(response: AuthResponse): LocalSession {
        val session = response.toSession()
        currentCoroutineContext().ensureActive()
        return mutationMutex.withLock {
            persist(session)
            session
        }
    }

    suspend fun updateProfile(sessionId: String, user: UserDto): LocalSession = mutationMutex.withLock {
        val session = requireSession(sessionId)
        if (user.id != session.userId) throw SessionChangedException()
        session.copy(displayName = user.displayName, avatarUrl = user.avatarUrl).also { persist(it); onRefreshed(it) }
    }

    /**
     * 改用户名后同步本地会话。
     *
     * 🔴 为什么必须做这一步：access token 里的 `username` 是**签发那一刻的快照**，
     * 改名接口不会（也不该）去改已经发出去的令牌。不更新本地会话的话，
     * 「我的」页会一直显示旧用户名，直到下一次 refresh —— 用户会以为改名没成功。
     */
    suspend fun rename(sessionId: String, user: UserDto): LocalSession = mutationMutex.withLock {
        val session = requireSession(sessionId)
        if (user.id != session.userId) throw SessionChangedException()
        session.copy(username = user.username, displayName = user.displayName, avatarUrl = user.avatarUrl)
            .also { persist(it); onRefreshed(it) }
    }

    /** 存储写入和内存更新一起完成，避免取消发生在两者之间。IO 不阻塞 UI。 */
    private suspend fun persist(session: LocalSession) = withContext(NonCancellable + Dispatchers.IO) {
        store.save(session)
        current = session
    }

    private fun requireSession(sessionId: String): LocalSession =
        current?.takeIf { it.sessionId == sessionId } ?: throw SessionChangedException()

    suspend fun clear(sessionId: String, failedToken: String? = null): LocalSession? = mutationMutex.withLock {
        val session = current?.takeIf {
            it.sessionId == sessionId && (failedToken == null || it.token == failedToken)
        } ?: return@withLock null
        withContext(NonCancellable + Dispatchers.IO) {
            store.clear()
            current = null
        }
        session
    }

    /** exp 只用于安排续期，是否有权限仍由服务器决定。旧凭据没有 exp 时按 401 处理。 */
    suspend fun ensureFresh(sessionId: String): LocalSession {
        val session = requireSession(sessionId)
        return if (session.refreshToken != null && session.expiresSoon()) {
            refresh(sessionId, session.token)
        } else session
    }

    /** 同一登录态的并发 401 只刷新一次，之后每个原请求最多重试一次。 */
    suspend fun <T> authorized(sessionId: String, request: suspend (String) -> T): T {
        var session = ensureFresh(sessionId)
        try {
            return request("Bearer ${session.token}").also { requireSession(sessionId) }
        } catch (error: HttpException) {
            if (error.code() != 401) throw error
            if (session.refreshToken == null) expire(sessionId, session.token)
            session = refresh(sessionId, session.token)
        }
        return try {
            request("Bearer ${session.token}").also { requireSession(sessionId) }
        } catch (error: HttpException) {
            if (error.code() == 401) expire(sessionId, session.token)
            throw error
        }
    }

    private suspend fun refresh(sessionId: String, failedToken: String): LocalSession = refreshMutex.withLock {
        val session = requireSession(sessionId)
        // 等待锁时另一个请求已经完成了轮换，直接使用它保存的新凭据。
        if (session.token != failedToken) return@withLock session
        val refreshToken = session.refreshToken ?: expire(sessionId, session.token)
        val response = try {
            api.refresh(RefreshRequest(refreshToken))
        } catch (error: HttpException) {
            if (error.code() == 401) expire(sessionId, session.token)
            throw error
        }
        if (response.user.id != session.userId && session.userId.isNotBlank()) {
            throw IOException("服务器返回的账号与当前账号不一致")
        }
        val updated = response.toSession(sessionId)
        if (updated.refreshToken == null) throw IOException("服务器未返回新的登录凭据，请稍后重试")
        val saved = withContext(NonCancellable) {
            mutationMutex.withLock mutation@{
                if (current?.sessionId != sessionId) return@mutation false
                persist(updated)
                onRefreshed(updated)
                true
            }
        }
        if (!saved) {
            // 用户在刷新途中退出了；不能让返回的令牌恢复登录态。
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) {
                    try { api.logout(LogoutRequest(updated.refreshToken)) }
                    catch (error: Exception) { if (error is CancellationException) throw error }
                }
            }
            throw SessionChangedException()
        }
        currentCoroutineContext().ensureActive()
        updated
    }

    private suspend fun expire(sessionId: String, failedToken: String): Nothing {
        if (clear(sessionId, failedToken) == null) throw SessionChangedException()
        throw SessionExpiredException(sessionId)
    }
}

class SessionChangedException : CancellationException("账号已切换，本次请求已取消")
class SessionExpiredException(val sessionId: String) : IOException("登录已过期，请重新登录")

private fun AuthResponse.toSession(sessionId: String = java.util.UUID.randomUUID().toString()): LocalSession {
    // Gson can populate null into non-null Kotlin fields when a response is incomplete.
    val token: String? = accessToken
    val profile: UserDto? = user
    if (token.isNullOrBlank() || profile == null || (profile.id as String?).isNullOrBlank() ||
        (profile.username as String?).isNullOrBlank()) {
        throw IOException("服务器返回的登录信息不完整")
    }
    return LocalSession(token, profile.username, (profile.displayName as String?) ?: "同学", profile.id,
        refreshToken?.takeIf { it.isNotBlank() }, sessionId, profile.avatarUrl)
}

private fun LocalSession.expiresSoon(): Boolean = runCatching {
    val encoded = token.split('.').getOrNull(1) ?: return@runCatching false
    val body = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    val exp = JSONObject(body).optLong("exp", 0)
    exp > 0 && exp <= System.currentTimeMillis() / 1_000 + 60
}.getOrDefault(false)
