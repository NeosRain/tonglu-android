package cn.tonglu.app.ui

import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.data.LocalSession
import cn.tonglu.app.data.PostAuthorDto
import cn.tonglu.app.data.PostDetailDto

/** 仅本地内存示例；不实例化API/ViewModel/数据库，不存在有效令牌。 */
class CommentPreviewActivity : ComponentActivity() {
    private var previousMode = ThemeMode.System
    private val previewContext by lazy {
        object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("comment_preview_" + name, mode)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previousMode = AppTheme.modeState
        AppTheme.setEnabled(previewContext, intent.getBooleanExtra("dark", false))
        setContent {
            val scheme = if (AppTheme.darkState) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
                    var showPost by remember { mutableStateOf(true) }
                    val sample = remember {
                        AppUiState(ready = true, previewMode = true,
                            session = LocalSession(token = "", username = "本地示例", displayName = "本地示例", userId = "local-comment-preview"),
                            activePost = PostDetailDto(id = "local-example-comment", category = "GENERAL",
                                title = "仅本地示例 · 不入库不联网",
                                body = "用于验证评论输入、emoji、键盘和返回。所有数据仅在内存。\n\n发送按钮仅模拟失败，绝不发布到广场。",
                                author = PostAuthorDto("local-comment-preview", "本地示例")))
                    }
                    val sampleEmoji = remember { PreviewEmojiGateway(intent.getBooleanExtra("emojiDenied", false), intent.getBooleanExtra("emojiUnavailable", false)) }
                    Box(Modifier.fillMaxSize().background(AppColors.Canvas)) {
                        Column(Modifier.align(Alignment.Center)) {
                            Text("本地预览主页 · 示例")
                            TextButton(onClick = { showPost = true }) { Text("打开评论示例") }
                        }
                        if (showPost) PostDetailScreen(sample, isLocalPreview = true, emojiGateway = sampleEmoji,
                            onBack = { showPost = false }, onRefresh = {}, onReport = {}, onMore = {},
                            onOrder = {}, onCommentAction = { _, _ -> },
                            onReply = { _, _, done -> done(false) })
                    }
                }
            }
        }
    }
    override fun onDestroy() {
        AppTheme.setMode(previewContext, previousMode)
        super.onDestroy()
    }
}

/** 内存协议夹具；图片面板用系统字形占位，绝不请求这些示例URL。 */
private class PreviewEmojiGateway(private val denied: Boolean, private val unavailable: Boolean) : cn.tonglu.app.data.community.EmojiGateway {
    private val assets = listOf("😀", "🎉", "❤️").mapIndexed { index, glyph ->
        cn.tonglu.app.data.community.EmojiAsset("sample-$index", "https://example.invalid/sample-$index.png", reviewState = "CLEAR", glyph = glyph)
    }
    private val saved = mutableSetOf<String>()
    override suspend fun capabilities(sessionId: String): cn.tonglu.app.data.community.CommunityCapabilities {
        if (unavailable) throw retrofit2.HttpException(retrofit2.Response.error<Unit>(404, okhttp3.ResponseBody.create(null, "{}")))
        return cn.tonglu.app.data.community.CommunityCapabilities(!denied, "等级超过3级或学生认证后可发图片")
    }
    override suspend fun pack(sessionId: String): cn.tonglu.app.data.community.EmojiPack {
        if (unavailable) throw retrofit2.HttpException(retrofit2.Response.error<Unit>(404, okhttp3.ResponseBody.create(null, "{}")))
        return cn.tonglu.app.data.community.EmojiPack("sample", "本地示例 · 不下载素材，不入库不联网", "示例声明", "https://example.invalid", "本地内存", assets)
    }
    override suspend fun favorites(sessionId: String) = assets.filter { it.id in saved }
    override suspend fun mine(sessionId: String) = assets.take(1).map { it.copy(reviewState = "UNREVIEWED") }
    override suspend fun favorite(sessionId: String, id: String) { saved.add(id) }
    override suspend fun unfavorite(sessionId: String, id: String) { saved.remove(id) }
    override suspend fun remove(sessionId: String, id: String) { saved.remove(id) }
    override suspend fun upload(sessionId: String, data: ByteArray, mime: String) =
        error("本地示例不上传，未发送任何文件")
}
