package cn.tonglu.app.ui.profile

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.ProfilePrivacyDto
import cn.tonglu.app.data.ProfileStatsDto
import cn.tonglu.app.data.PublicProfileDto
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.ThemeMode
import cn.tonglu.app.ui.ThemeSystemBars
import cn.tonglu.app.ui.appMaterialColors

/** Debug 内存示例，不登录、不联网、不写入正式资料。 */
class PublicProfilePreviewActivity : ComponentActivity() {
    private var previous = ThemeMode.System
    private val previewContext by lazy {
        object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("qa_public_profile_$name", mode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        previous = AppTheme.modeState
        AppTheme.setEnabled(previewContext, intent.getStringExtra("theme") == "dark")
        val scenario = intent.getStringExtra("scenario") ?: "full"
        val sample = previewProfile(scenario)
        setContent {
            ThemeSystemBars()
            MaterialTheme(colorScheme = appMaterialColors()) {
                CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
                    val illustration = remember { if (sample.coverUrl == null) null else sampleCover().asImageBitmap() }
                    PublicProfilePage(sample, ::finish, illustration, example = true, actions = {
                        Text(when {
                            sample.own -> "本人视角"
                            scenario == "guest" -> "游客视角"
                            else -> "已关注"
                        }, color = AppColors.TextSecondary, modifier = Modifier.padding(10.dp))
                    }) {
                        item {
                            Column(Modifier.padding(16.dp)) {
                                Text(if (scenario == "private") "仅自己可见" else "帖子", style = MaterialTheme.typography.titleMedium)
                                Text("示例帖子 · 校园散步记录", modifier = Modifier.padding(top = 12.dp))
                                Text("沿着操场外的小路走到图书馆，路过落叶和晚霞。", color = AppColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        AppTheme.setMode(previewContext, previous)
        super.onDestroy()
    }
}

private fun previewProfile(scenario: String): PublicProfileDto {
    val own = scenario == "private"
    return PublicProfileDto(
        id = "example-profile", username = "example_forest", displayName = "林间来信",
        avatarUrl = null, avatarColor = "#527B6C", coverUrl = if (scenario == "no-cover") null else "preview",
        bio = "把辽东的晴天装进相机。\n在图书馆角落慢慢读书。", profileTags = listOf("摄影", "读书", "24级"),
        own = own, verifiedStudent = true, level = if (own) null else 5,
        stats = ProfileStatsDto(3, if (own) null else 26, if (own) null else 11, if (own) null else 18),
        followedBoardsVisible = !own, viewerFollowing = true.takeUnless { own || scenario == "guest" },
        privacy = ProfilePrivacyDto("PRIVATE", false, false, false, false).takeIf { own },
        canSetCover = true.takeIf { own }, ownGrowthLevel = 5.takeIf { own },
    )
}

private fun sampleCover(): Bitmap = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888).also { bitmap ->
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 900f, 300f, Color.rgb(182, 211, 188), Color.rgb(244, 217, 161), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, 900f, 300f, paint)
    paint.shader = null
    paint.color = Color.rgb(255, 237, 182)
    canvas.drawCircle(683f, 89f, 49f, paint)
    paint.color = Color.rgb(111, 151, 129)
    canvas.drawPath(Path().apply { moveTo(0f, 215f); quadTo(222f, 69f, 474f, 204f); quadTo(640f, 125f, 900f, 203f); lineTo(900f, 300f); lineTo(0f, 300f); close() }, paint)
    paint.color = Color.rgb(59, 109, 93)
    canvas.drawPath(Path().apply { moveTo(0f, 262f); quadTo(247f, 187f, 395f, 245f); quadTo(648f, 273f, 900f, 206f); lineTo(900f, 300f); lineTo(0f, 300f); close() }, paint)
}
