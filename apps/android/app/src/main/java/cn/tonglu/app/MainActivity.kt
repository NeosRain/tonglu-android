package cn.tonglu.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.notify.ClassReminder
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.TongluApp
import cn.tonglu.app.ui.theme.ThemeAccent
import cn.tonglu.app.ui.theme.ThemeWallpaper
import cn.tonglu.app.ui.theme.ThemeWallpaperHost
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) ensureReminderRunning() }

    /**
     * 走查用的「下载并安装模型」入口（`adb shell am start -a ...TTS_INSTALL --es modelId zh-aishell3`）。
     *
     * **只在 debug 包里存在**（调用处有 `BuildConfig.DEBUG`）。之所以要有它：
     * 下载链路（临时文件 → 字节数 → sha256 → 解压 → 就位）是这套方案里最容易坏的一环，
     * 尤其英文模型带 `espeak-ng-data` 这棵子目录树，光看界面「下载完成」证明不了
     * 文件真的解到了正确位置。用 adb 触发 + 看日志，才能拿到可核对的证据。
     */
    private fun handleTtsInstallIntent(intent: android.content.Intent?) {
        if (intent?.action != "cn.tonglu.app.action.TTS_INSTALL") return
        val wanted = intent.getStringExtra("modelId")
        Thread {
            val store = cn.tonglu.app.data.speech.TtsModelStore
            val model = store.list().firstOrNull { it.id == wanted }
            if (model == null) {
                android.util.Log.w("TongluTtsInstall", "清单里没有模型：$wanted（有：${store.list().map { it.id }}）")
                return@Thread
            }
            android.util.Log.i("TongluTtsInstall", "开始下载 ${model.id} ${model.bytes} 字节")
            val started = System.currentTimeMillis()
            val error = store.download(this, model) { done, total ->
                // 每 5% 打一条，免得刷屏
                if (total <= 0 || done % (total / 20 + 1) == 0L || done == total) {
                    android.util.Log.i("TongluTtsInstall", "进度 ${done}/${total}")
                }
            }
            val ms = System.currentTimeMillis() - started
            android.util.Log.i(
                "TongluTtsInstall",
                "result=${error ?: "OK"} 用时=${ms}ms 已安装=${store.isInstalled(this, model)}",
            )
        }.apply { isDaemon = true }.start()
    }

    private fun handleSelfTestIntent(intent: android.content.Intent?) {
        if (intent?.action != "cn.tonglu.app.action.TTS_SELFTEST") return
        val wanted = intent.getStringExtra("modelId")
        Thread {
            val store = cn.tonglu.app.data.speech.TtsModelStore
            // 没指定就自检**第一条已安装**的模型；一条都没装就明确说，不假装失败在系统上。
            val model = if (wanted.isNullOrBlank()) {
                store.list().firstOrNull { store.isInstalled(this, it) }
            } else {
                store.list().firstOrNull { it.id == wanted }
            }
            if (model == null) {
                android.util.Log.w("TongluTtsSelfTest", "没有可自检的已安装模型（wanted=$wanted）")
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this, "还没有装好的离线模型（wanted=$wanted），先在声音设置里下载一条",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return@Thread
            }
            val file = java.io.File(filesDir, "tts_selftest_${model.id}.wav")
            val result = cn.tonglu.app.data.speech.BuiltinTts.selfTest(this, model, file)
            android.util.Log.i(
                "TongluTtsSelfTest",
                "model=${model.id} ok=${result.ok} bytes=${result.bytes} " +
                    "sampleRate=${result.sampleRate} msg=${result.message}",
            )
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (result.ok) "离线语音自检通过（${model.id}）：${result.bytes} 字节 / ${result.sampleRate}Hz"
                    else "离线语音自检失败（${model.id}）：${result.message}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 旧版 Android 同样从屏幕边缘绘制；各控件自行避让状态栏与挖孔。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // 先把本机保存的自定义作息灌进 PeriodTable，UI 与提醒都以它为准
        runCatching { cn.tonglu.app.data.ScheduleStore(this, cn.tonglu.app.data.SessionStore.LOCAL_OWNER).applyPeriodTable() }
        requestNotificationPermissionIfNeeded()
        // 只在调试包：给走查一个能直接验证离线语音的入口，省得每次手工点进声音设置页。
        //   adb shell am start -n cn.tonglu.app/.MainActivity -a cn.tonglu.app.action.TTS_SELFTEST
        // 结果同时写 logcat（TongluTtsSelfTest）与 files/tts_selftest.wav ——
        // 「有没有真的出声音」要拿 wav 的字节数说话，不能只看"点了没报错"。
        if (BuildConfig.DEBUG) {
            handleSelfTestIntent(intent)
            handleTtsInstallIntent(intent)
        }
        // 深色开关先同步一次：必须在 `setContent` **之前**，否则第一帧按浅色画完再跳一下（闪白）。
        cn.tonglu.app.ui.AppTheme.syncFrom(this)
        // 主题色/壁纸同样要在第一帧之前读好：否则会先按默认蓝、无壁纸画一帧再跳变。
        ThemeAccent.load(this)
        ThemeWallpaper.load(this)
        setContent {
            // 读全局开关：用户在外观页一拨，这里重组 —— 页面配色 / Miuix / 顶栏底栏玻璃一起变。
            val dark = cn.tonglu.app.ui.AppTheme.darkState
            val controller = remember(dark) {
                ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            }
            // 🔴 壁纸启用时，把 Miuix 的**全部表面槽**统一改成磨砂玻璃 —— 这一步不做，
            //    「大部分卡片没有透明度」就一定复现：全站的 `MiuixCard` 默认色来自
            //    `MiuixTheme.color.surfaceContainer`（CardDefaults.defaultColors，反编译确认），
            //    只改 `AppColors.Surface` 管不到它们。弹层/对话框走 surface / surfaceContainerHigh。
            //    没壁纸时原样返回，默认路径逐字节不变。
            val miuixColors = controller.currentColors().let { base ->
                if (!cn.tonglu.app.ui.theme.ThemeCanvas.wallpaperActive) base else base.copy(
                    surface = cn.tonglu.app.ui.theme.ThemeCanvas.resolveCard(base.surface, dark),
                    surfaceVariant = cn.tonglu.app.ui.theme.ThemeCanvas.resolveCard(base.surfaceVariant, dark),
                    surfaceContainer = cn.tonglu.app.ui.theme.ThemeCanvas.resolveCard(base.surfaceContainer, dark),
                    surfaceContainerHigh = cn.tonglu.app.ui.theme.ThemeCanvas.resolveCard(base.surfaceContainerHigh, dark),
                    surfaceContainerHighest = cn.tonglu.app.ui.theme.ThemeCanvas.resolveCard(base.surfaceContainerHighest, dark),
                )
            }
            MiuixTheme(colors = miuixColors) {
                MaterialTheme(
                    colorScheme = if (dark) darkColorScheme(
                        // 深色下也走用户主题色；默认路径仍等于 GlassTokens.AccentDark（#4173CC）。
                        primary = cn.tonglu.app.ui.AppColors.Accent,
                        onPrimary = cn.tonglu.app.ui.AppColors.OnAccent,
                        background = cn.tonglu.app.ui.glass.GlassTokens.ContainerDark,
                        surface = Color(0xFF1D2027),
                        onSurface = Color(0xFFEDEFF3),
                        onBackground = Color(0xFFEDEFF3),
                        onSurfaceVariant = Color(0xFFA8AEBA),
                    ) else lightColorScheme(
                        // 🔴 这里以前是**再抄一遍** `Color(0xFF3978E8)` —— 抄第二次就是分叉的起点：
                        //    2026-10-11 改浅色强调色（4.18:1 → 5.17:1）时，就是因为这一行
                        //    差点被漏掉，而「新蓝没覆盖到 Material 组件」肉眼很难发现。
                        //    现在统一从语义槽取，只有 AppColors 一处真源。
                        primary = cn.tonglu.app.ui.AppColors.Accent,
                        onPrimary = cn.tonglu.app.ui.AppColors.OnAccent,
                        background = Color(0xFFF5F6F8),
                        surface = Color.White,
                        onSurface = Color(0xFF20242B),
                    ),
                ) {
                    val viewModel: AppViewModel = viewModel()
                    // 🔴 预测性返回时下层会被视差推走（`underlayParallax()` 往左移 + 缩小），
                    //    它让开的位置露出的是**窗口底色** —— 而窗口底色来自 `themes.xml` 的
                    //    `@color/tonglu_canvas`（浅色 `#F5F6F8`，且**没有 `values-night/`**），
                    //    深色模式下就成了一片纯白（用户 2026-10-08 报的「右边出现纯白空白」）。
                    //
                    //    这里在 Compose 根部垫一层**不参与位移**的主题底色，把缝补上。
                    //    ⚠️ 别改成 `values-night/colors.xml`：深色是 App 自己的开关
                    //    （`AppTheme`），跟系统深色**没有关系** —— 系统是浅色时用户照样能开 App 深色。
                    //
                    // 🔴 `LocalContentColor` 必须在这里显式提供（2026-10-11 用户报「登入页最上面的字
                    //    和最下面的字看不出来」）：Material3 的 `MaterialTheme` **不提供**
                    //    `LocalContentColor`，它的默认值是 `Color.Black`，只有 `Surface`/`Button`
                    //    这类组件才会自己设一次。而全 App 约 **685 处** `Text(...)` 没写 `color`，
                    //    全靠这个默认值 —— 浅色下黑字正常，深色下就是黑压黑：
                    //    实测登入页标题「登录应用账号」**1.17:1**（文字 luma=0，底 `#15171C` luma=23）。
                    //    接上语义色槽后两套主题同时正确（浅色 `#17181C` / 深色 `#EDEFF3`）。
                    //    ⚠️ 只在「定 colorScheme 的地方」提供 —— `ArticleReader` 有一层自己的
                    //    `MaterialTheme`（纸张可为墨黑而不随 App 深浅），它必须单独提供，见该文件。
                    CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
                        // 根层壁纸宿主：不透明底色 + 可选壁纸（已模糊）+ 压暗/薄雾。
                        // 页面自己的 Scaffold 在 TongluApp 里会对壁纸让出透明，见那里的注释。
                        ThemeWallpaperHost(Modifier.fillMaxSize()) {
                            TongluApp(viewModel)
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            ensureReminderRunning()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) ensureReminderRunning() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 有课表就重排课前提醒（每次打开 App 都会补排未来几天的闹钟） */
    private fun ensureReminderRunning() {
        ClassReminder.scheduleAllAsync(this)
    }
}

