package cn.tonglu.app.data.english

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * 背单词会话断点（EF05 增补：会话中途退出 / 进程被杀后恢复，不误报完成）。
 *
 * 为什么不塞进 Room：断点是**一次未完成会话**的临时事实，至多一份、随时可弃，
 * 完成即清 —— 给它建表是把临时状态当永久数据养。落盘到按账号哈希隔离的本机文件，
 * 与个人词书（B，`data/english/books`）同一套存储纪律。
 *
 * 只记「进度事实」（答到第几题、对几题、词单 id），**不记答案本身**——
 * 恢复后每道题照常重新作答，不存在「恢复出来一条已经判过对错的假记录」。
 *
 * 完成语义（🔴 不得误报完成）：答完最后一题的瞬间断点即被清除（调用方负责），
 * 所以「有断点」永远意味着「确实没答完」。恢复路径若发现 index 越界，
 * 视为损坏断点直接丢弃，绝不渲染完成页。
 */
class EnglishPracticeCheckpoint(context: Context, owner: String) {

    /**
     * @param review 会话是复习还是学新词（恢复时原样带回，仅供界面口径，不影响作答行为）
     * @param mode   题型（cards/choice/cloze…）；恢复时还原，例句题的词单当初已按题型筛过
     * @param bookId 会话所属词书；与当前词书不符的断点直接丢弃（答到别的书里是假进度）
     * @param wordIds 会话词单（有序）；恢复时按此顺序从词库取回
     * @param index  已答完的题数（下一题下标）
     * @param correctCount 已记住/答对次数
     */
    data class Data(
        val review: Boolean,
        val mode: String,
        val bookId: String,
        val wordIds: List<String> = emptyList(),
        val index: Int = 0,
        val correctCount: Int = 0,
        val savedAt: Long = 0,
    )

    private val gson = Gson()
    private val file: AtomicFile

    init {
        require(owner.isNotBlank()) { "缺少账号归属" }
        val account = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
            .joinToString("") { "%02x".format(it) }
        file = AtomicFile(File(context.applicationContext.filesDir, "english/practice/$account.json"))
    }

    /** 读断点。没有 / 损坏 / 解析失败 / 字段不合法一律返回 null（当没断点处理，不假装恢复）。 */
    fun load(): Data? = try {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
        else file.openRead().bufferedReader().use { gson.fromJson(it, Data::class.java) }
            ?.takeIf { it.mode.isNotBlank() && it.bookId.isNotBlank() && it.index >= 0 }
    } catch (error: Exception) {
        android.util.Log.w("EnglishPractice", "练习断点读取失败，按无断点处理", error)
        null
    }

    fun save(data: Data) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson(data).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    /** 完成或明确放弃（换词书）时清除断点。清不掉只记日志——它最多导致多恢复一次，不产生假完成。 */
    fun clear() {
        try { file.delete() } catch (error: Exception) {
            android.util.Log.w("EnglishPractice", "练习断点清除失败", error)
        }
    }
}
