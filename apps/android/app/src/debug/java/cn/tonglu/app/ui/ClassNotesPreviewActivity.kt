package cn.tonglu.app.ui

import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import cn.tonglu.app.data.CourseDto
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only isolated note fixture. Never writes the normal classroom directory or calls a server. */
class ClassNotesPreviewActivity : ComponentActivity() {
    private var previousMode = ThemeMode.System
    private val previewContext by lazy { object : ContextWrapper(this) {
        override fun getFilesDir() = File(super.getFilesDir(), "qa-classnotes").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa_notes_" + name, mode)
    } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        previousMode = AppTheme.modeState
        AppTheme.setEnabled(previewContext, intent.getBooleanExtra("dark", false))
        val course = CourseDto("qa-course", "示例 · 课堂笔记走查", "QA", weekday=1, startPeriod=1, endPeriod=2, startWeek=1, endWeek=20)
        val root = File(previewContext.filesDir,"class-notes").apply { mkdirs() }
        val prefix = java.security.MessageDigest.getInstance("SHA-256").digest(("course:" + classNoteCourseKey(course)).toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
        val file = File(root, prefix + "-" + SimpleDateFormat("yyyyMMdd",Locale.US).format(Date()) + ".json")
        if (!file.exists()) file.writeText(JSONArray().put(JSONObject().put("id","qa-demo-note").put("time",System.currentTimeMillis())
            .put("text", "本地示例长笔记\n\n" + (1..16).joinToString("\n\n") { "第${it}段：这是专门用于界面走查的示例正文。点进笔记后应能查看全文、滚动续写，并保留原笔记标识。" })
            .put("image", "").put("audio", "")).toString())
        setContent {
            MaterialTheme(colorScheme = if (AppTheme.darkState) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary, LocalContext provides previewContext) {
                    ClassNotesDialog(course, onDismiss={ finish() })
                }
            }
        }
    }
    override fun onDestroy() { AppTheme.setMode(previewContext, previousMode); super.onDestroy() }
}
