package cn.tonglu.app.ui

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.tonglu.app.BuildConfig
import cn.tonglu.app.data.update.*
import cn.tonglu.app.ui.update.UpdateSettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Debug-only, separate package. Loopback fixture transport never changes production manifest trust. */
class UpdateQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG || packageName != "cn.tonglu.app.updateqa") { finish(); return }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppUpdates.setAutomatic(this, false)
        val fixture = JSONObject(String(Base64.decode(requireNotNull(intent.getStringExtra("fixture")), Base64.DEFAULT), Charsets.UTF_8))
        fun url(o: JSONObject): String = o.getString("url").also {
            val uri = URI(it)
            require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 18765 && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null)
        }
        fun patch(o: JSONObject) = UpdatePatch(o.getLong("baseCode"), o.getString("baseSha256"), url(o), o.getString("sha256"), o.getLong("size"))
        val candidates = fixture.getJSONArray("deltas").let { array -> List(array.length()) { patch(array.getJSONObject(it)) } }
        val release = UpdateRelease(fixture.getLong("versionCode"), fixture.getString("versionName"), url(fixture),
            fixture.getString("sha256"), fixture.getLong("size"), 24, "仅本机更新闭环样本", candidates.firstOrNull(), candidates)
        val updateDirectory = File(requireNotNull(getExternalFilesDir(null)), "app-updates")
        // Only this separate QA package's target files; run-as cannot access the OEM's FUSE mount.
        listOf("apk", "tmld", "apk.pending").forEach { extension ->
            val file = File(updateDirectory, "${release.versionCode}.$extension")
            check(!file.exists() || file.delete()) { "无法清理本次走查的旧下载" }
        }
        setContent {
            MaterialTheme {
                val state by AppUpdates.state.collectAsState()
                LaunchedEffect(state) {
                    withContext(Dispatchers.IO) {
                        File(filesDir, "qa-update-state.json").writeText(JSONObject()
                            .put("installedCode", BuildConfig.VERSION_CODE).put("targetCode", release.versionCode)
                            .put("downloading", state.downloading).put("ready", state.ready).put("progress", state.progress)
                            .put("transfer", state.transfer?.name ?: "none").put("bytes", state.transferBytes ?: 0)
                            .put("outputSha256", if (state.ready) ApkDelta.sha256(File(updateDirectory, "${release.versionCode}.apk")) else "")
                            .put("notice", state.transferNotice ?: "").put("error", state.error ?: "").toString())
                    }
                }
                CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
                    Text("本机增量更新走查 · ${BuildConfig.VERSION_CODE}")
                    Button(enabled = !state.downloading && !state.ready, onClick = { AppUpdates.download(this@UpdateQaActivity, release) }) { Text("下载走查样本") }
                    UpdateSettingsCard()
                }
                }
            }
        }
    }
}
