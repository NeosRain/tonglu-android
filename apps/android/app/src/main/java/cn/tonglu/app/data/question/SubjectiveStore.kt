package cn.tonglu.app.data.question

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest

/**
 * 主观题（写作 / 翻译）的本地作答：草稿、提交快照、对照笔记。
 *
 * 🔴 **这里不产生任何「对错」** —— 主观题没有标准答案下标，机器判不了分。
 * 本仓库的红线是「**不伪造自动判分**」和「**答案绝不编**」：
 * 所以这个类里**没有** `correct` 字段、也不写进 `PracticeStore` 的作答记录
 * （那张表里的 `correct` 会被错题本和「今日已练」读走；塞一条假的进去，
 * 错题本就会冒出一道根本判不了的题）。
 * 用户真正需要的是「我写过什么、我提交了没有、我对照之后记了什么」—— 就这三件事。
 *
 * 按 owner 分桶（`SHA-256(owner)` 做文件名），与 `PracticeStore` / `DailySentenceStore`
 * 同一套约定：不分桶的话 A 写的草稿会原样出现在 B 的账号下（FINDINGS #5）。
 */
class SubjectiveStore(context: Context, private val owner: String) {
    private val app = context.applicationContext

    private val key = MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val file get() = File(app.filesDir, "practice/subjective/$key.json")

    /** 全部作答（按题目键）。读失败返回空 map —— 界面照常能用，只是没有历史内容。 */
    fun all(): Map<String, SubjectiveAnswer> = readRaw()?.let { parseAnswers(it) } ?: emptyMap()

    fun of(questionKey: String): SubjectiveAnswer = all()[questionKey] ?: SubjectiveAnswer()

    /** 存草稿。**不产生提交记录**：草稿就是草稿，不能当成交了作业。 */
    fun saveDraft(questionKey: String, text: String) {
        put(questionKey, of(questionKey).copy(draft = text))
    }

    /** 存笔记（通常是照原卷/解析对照之后写的）。同样不算提交。 */
    fun saveNote(questionKey: String, text: String) {
        put(questionKey, of(questionKey).copy(note = text))
    }

    /**
     * 提交：把当前草稿**定一份快照**并记下时间。
     *
     * 为什么是快照而不是只翻一个布尔：用户提交之后还会继续改草稿（想再写一版），
     * 只存「提交过」的话，回头看「我当时交了什么」就没了 —— 而**交的那版**才是
     * 当时真正拿出去的东西。草稿继续可改，提交快照不动。
     */
    fun submit(questionKey: String, text: String) {
        val now = System.currentTimeMillis()
        put(questionKey, of(questionKey).copy(submitted = text, submittedAt = now))
    }

    private fun put(questionKey: String, answer: SubjectiveAnswer) {
        val map = all().toMutableMap()
        val next = answer.copy(updatedAt = System.currentTimeMillis())
        if (next.draft.isBlank() && next.submitted.isBlank() && next.note.isBlank()) map.remove(questionKey)
        else map[questionKey] = next
        try {
            file.parentFile?.mkdirs()
            file.writeText(Gson().toJson(map))
        } catch (cause: Exception) {
            Log.w(TAG, "主观题作答写入失败", cause)
        }
    }

    private fun readRaw(): String? = try {
        if (file.exists()) file.readText() else null
    } catch (cause: Exception) {
        Log.w(TAG, "主观题作答读取失败", cause)
        null
    }

    private companion object {
        const val TAG = "SubjectiveStore"
    }
}

/**
 * 一道主观题的本地作答状态。
 *
 * 三个字段各自独立、**互不代替**：
 * - [draft]：正在写的东西，随时存；
 * - [submitted] / [submittedAt]：点过「提交」那一刻定下的版本；
 * - [note]：对照原卷/解析之后自己的笔记。
 */
data class SubjectiveAnswer(
    val draft: String = "",
    val submitted: String = "",
    /** 0 = 还没提交过。 */
    val submittedAt: Long = 0,
    val note: String = "",
    val updatedAt: Long = 0,
) {
    /**
     * 提交过没有。**正文和时间戳两个都要有** —— 只有时间戳没有正文，
     * 界面会显示一块空的「已提交的一版」，用户会以为自己交的东西丢了。
     * （这条口径是 `SubjectiveStoreTest` 逼出来的：最初只判 `submittedAt > 0`，被测试抓红。）
     */
    val hasSubmitted: Boolean get() = submittedAt > 0 && submitted.isNotBlank()
    /** 提交之后草稿又改过 —— 界面要能提醒「你交完之后又改了，要不要再交一次」。 */
    val draftNewerThanSubmit: Boolean get() = hasSubmitted && draft.isNotBlank() && draft != submitted
}

/**
 * 解析本地存的那份 JSON。**纯函数**，好单测：`Context` 在 JVM 测试里给不了，
 * 而「坏数据不能把界面打崩」正是最容易出事的地方（Gson 会绕过构造器塞 null）。
 */
internal fun parseAnswers(raw: String): Map<String, SubjectiveAnswer> {
    val type = object : TypeToken<Map<String, SubjectiveAnswer>>() {}.type
    val parsed = runCatching { Gson().fromJson<Map<String, SubjectiveAnswer>>(raw, type) }
        .getOrNull() ?: return emptyMap()
    if (parsed.isEmpty()) return emptyMap()
    // 逐条兜底：Gson 绕过构造器，缺字段时**非空的 String 也会被塞成 null** ——
    // 本项目已记过多次。宁可退回空串，也不让界面拿到 null 去渲染。
    return parsed.mapNotNull { (k, v) ->
        if (k.isBlank()) null
        else k to (v ?: SubjectiveAnswer()).copy(
            draft = v?.draft.orEmpty(),
            submitted = v?.submitted.orEmpty(),
            note = v?.note.orEmpty(),
        )
    }.toMap()
}
