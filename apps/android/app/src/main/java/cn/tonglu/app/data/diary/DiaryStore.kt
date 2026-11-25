package cn.tonglu.app.data.diary

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal class DiaryStore(private val file: File, private val replace: (File, File) -> Unit) {
    private val previous = File(file.path + ".previous")

    data class Snapshot(val data: DiaryData, val recovered: Boolean = false)

    fun load(): Snapshot = synchronized(diskLock) { read().snapshot }

    fun update(change: (DiaryData) -> DiaryData): Snapshot = synchronized(diskLock) {
        val before = read()
        val after = change(before.snapshot.data)
        if (after == before.snapshot.data) return@synchronized before.snapshot

        if (!before.snapshot.recovered) before.bytes?.let { write(previous, it) }
        write(file, encode(after))
        Snapshot(after)
    }

    private data class Read(val snapshot: Snapshot, val bytes: ByteArray?)

    private fun read(): Read {
        val bytes = readBytes(file) ?: return readPrevious()
        return try {
            Read(Snapshot(decode(bytes)), bytes)
        } catch (damaged: CorruptDiary) {
            readPrevious(damaged)
        }
    }

    private fun readPrevious(damaged: CorruptDiary? = null): Read {
        val backup = readBytes(previous)
        if (backup != null) {
            try {
                return Read(Snapshot(decode(backup), recovered = true), backup)
            } catch (backupError: CorruptDiary) {
                if (damaged == null) throw backupError
                damaged.addSuppressed(backupError)
            }
        }
        if (damaged != null) throw damaged
        return Read(Snapshot(DiaryData()), null)
    }

    private fun readBytes(target: File): ByteArray? = try {
        target.readBytes()
    } catch (error: FileNotFoundException) {
        if (target.exists()) throw error
        null
    }

    private fun write(target: File, bytes: ByteArray) {
        val staged = File(target.path + ".new")
        target.parentFile?.mkdirs()
        FileOutputStream(staged).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        replace(staged, target)
    }

    private fun encode(data: DiaryData): ByteArray {
        val payload = gson.toJson(data)
        return JsonObject().apply {
            addProperty("version", VERSION)
            addProperty("payload", payload)
            addProperty("sha256", hash(payload))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): DiaryData {
        try {
            val envelope = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val version = envelope.get("version")?.asInt ?: throw CorruptDiary()
            if (version != VERSION) throw IOException("此日记需要兼容的数据版本（$version）")
            val payload = envelope.get("payload")?.asString ?: throw CorruptDiary()
            if (hash(payload) != envelope.get("sha256")?.asString) throw CorruptDiary()
            return gson.fromJson(payload, DiaryData::class.java) ?: throw CorruptDiary()
        } catch (error: JsonParseException) {
            throw CorruptDiary(error)
        } catch (error: IllegalStateException) {
            throw CorruptDiary(error)
        } catch (error: IllegalArgumentException) {
            throw CorruptDiary(error)
        } catch (error: UnsupportedOperationException) {
            throw CorruptDiary(error)
        }
    }

    private class CorruptDiary(cause: Exception? = null) : IOException("日记数据损坏，无法读取", cause)

    companion object {
        private const val VERSION = 1
        private val diskLock = Any()
        private val gson = Gson()

        private fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun forOwner(context: Context, owner: String): DiaryStore =
            forAndroidFile(File(context.noBackupFilesDir, "goal-diary/${hash(owner)}.json"))

        fun forAndroidFile(file: File): DiaryStore = DiaryStore(file) { staged, target ->
            // 同目录原子替换，Android 7.0 也可用。重命名失败时保留当前文件。
            try {
                Os.rename(staged.path, target.path)
            } catch (error: ErrnoException) {
                throw IOException("日记写入未完成", error)
            }
        }
    }
}
