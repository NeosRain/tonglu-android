package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.english.EnglishAi
import cn.tonglu.app.data.english.EnglishAiConfig
import cn.tonglu.app.data.english.EnglishAiTurn
import cn.tonglu.app.data.english.reading.*
import cn.tonglu.app.ui.english.reading.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 一次请求绑定对话实例和提问实例；取消、重试、换句或换号后的旧结果不能回填。 */
internal data class ReadingChatRequest(val conversation: ReadingConversationState, val question: ReadingChatMessage) {
    val context get() = ReadingAiContext(conversation.selection.sentence, conversation.selection.wordTokenId)
    val history = conversation.messages.filter { !it.pending && !it.failed }
        .takeLast(READING_MESSAGE_MAX).map { EnglishAiTurn(it.role, it.content) }

    fun isCurrent(state: ReadingDeepDiveState): Boolean = state.conversation === conversation &&
        conversation.phase is ReadingChatPhase.Sending && conversation.messages.any { it === question && it.pending }

    companion object {
        fun pending(conversation: ReadingConversationState): ReadingChatRequest? {
            if (conversation.phase !is ReadingChatPhase.Sending) return null
            return conversation.messages.lastOrNull { it.role == "user" && it.pending }?.let {
                ReadingChatRequest(conversation, it)
            }
        }
    }
}

internal fun interface ReadingChatModel {
    suspend fun answer(config: EnglishAiConfig, turns: List<EnglishAiTurn>): String
}

internal object UserReadingChatModel : ReadingChatModel {
    override suspend fun answer(config: EnglishAiConfig, turns: List<EnglishAiTurn>) =
        EnglishAi.chat(config, turns, maxTokens = 1600, temperature = 0.4)
}

/** 先读后写的门面；坏历史和读取失败不能被空会话覆盖。owner固定，密钥不进入存储接口。 */
internal class ReadingConversationBridge(
    private val owner: String,
    private val store: ReadingConversationStore,
    private val model: ReadingChatModel = UserReadingChatModel,
) {
    private val mutex = Mutex()
    private val restored = ConcurrentHashMap<String, Long>()
    private val restorationSequence = AtomicLong()
    init { require(owner.isNotBlank()) { "缺少对话归属" } }

    suspend fun restore(context: ReadingAiContext): ReadingConversationLoad = mutex.withLock {
        val id = readingThreadId(context)
        restored.remove(id)
        val result = store.load(owner, context)
        currentCoroutineContext().ensureActive()
        if (result !is ReadingConversationLoad.Corrupt) restored[id] = restorationSequence.incrementAndGet()
        result
    }

    suspend fun save(context: ReadingAiContext, messages: List<EnglishAiTurn>, draft: String): Boolean {
        val id = readingThreadId(context)
        val generation = restored[id] ?: return false
        return mutex.withLock {
            if (restored[id] != generation) return@withLock false
            store.save(owner, context, messages.takeLast(READING_MESSAGE_MAX), draft.take(READING_DRAFT_MAX))
            true
        }
    }

    suspend fun answer(request: ReadingChatRequest, config: EnglishAiConfig): String {
        require(config.ready()) { "请先配置自己的AI接口" }
        val answer = model.answer(config, request.context.turns(request.history, request.question.content))
        currentCoroutineContext().ensureActive()
        require(answer.isNotBlank()) { "AI没有返回可阅读的内容，请重试" }
        return answer
    }
}

/** 在途或失败提问保存在草稿，不当作已完成的历史，重进后仍可再次发送。 */
internal fun readingConversationDraft(conversation: ReadingConversationState): String {
    val unfinished = conversation.messages.lastOrNull { it.role == "user" && (it.pending || it.failed) }?.content
    return when {
        unfinished == null -> conversation.draft
        conversation.draft.isBlank() -> unfinished
        conversation.draft == unfinished -> unfinished
        else -> unfinished + "\n" + conversation.draft
    }.take(READING_DRAFT_MAX)
}
