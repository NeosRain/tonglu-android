plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 🔴 API 走 www 子域，**不是主域**。2026-10-10 上午博客迁移时主域 newrain.top
// 被 301 到 blog.newrain.top（`/api/v1/*` 反代没跟过去），打主域会
// 301→ blog 的 404，症状是「App 内所有接口 404」。
// 后端本身活得好好的：https://www.newrain.top/api/v1/health → {"status":"ok","db":"up"}
// 证书 SAN 覆盖 newrain.top + www.newrain.top，两个域名都受信。
val configuredApiBaseUrl = providers.gradleProperty("API_BASE_URL")
    .orElse("https://example.invalid/api/v1/")
    .get()
    .let { if (it.endsWith("/")) it else "$it/" }

android {
    namespace = "cn.tonglu.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.tonglu.app.opensource"
        minSdk = 24
        targetSdk = 36
        // 88 / 0.6.74 = 校园地图（三张官方平面图，2400 宽 WebP，离线可看）+ 首页地图卡文案修正；
        //              并含 87 之后的登录/注册页返回键修复（S57）与深色逐屏对比度收口。
        // 87 / 0.6.73 = 登录/注册页按返回键直接退桌面（S57）+ 夜间隔离子/品牌蓝收口。
        // 86 / 0.6.72 = 自定义用户名：注册可自取（3–24 位，`[a-z0-9_]`，保留前缀 `u_`）、
        //              登录三合一（用户名 / 邮箱 / 旧派生 ID）、老用户登录后引导改名。
        // 上一版：85 / 0.6.71 = 新增「今日课表」桌面小部件（Glance）+ 修复 OverlayMotion 编译错。
        // 不 bump 的话装机会被当成「同版本」拒绝，而用户看到的症状是「全是 404」，
        // 极容易误判成缓存问题。
        // 92 / 0.6.78 = 个人 PDF/EPUB 导入与书架章节（U09 主体）：我的文章与书籍支持导入
        //              TXT/Markdown/PDF/EPUB，一本书一个入口（章节 / PDF 页码），PDF 原版逐页
        //              渲染、有文字层可进精读；Room v5→v6 加 7 列（7 条 ALTER，不重建库）。
        //              点词落点问题（BUGS S118）未定位根因，未在本版修改命中规则。
        // 93 / 0.6.79 = 修掉 S118：点词落点判定不再直接拿 getOffsetForPosition（那是最近**游标**，
        //              落点靠近字间分界时会落到右侧字符的框外，被旧守卫当成「没点中」静默吞掉）。
        //              改为向左右各看一格、取真正框住落点的字符；正文/结构面板/词卡换词三处共用。
        // 94 / 0.6.80 = 英式离线声线修复（S119）、开屏玻璃方块 3D 化、四六级入口收敛到考试日历、
        //              竞赛「可能要来」预测分区与官网链接、「取消备考」状态回退修复、
        //              「我的消息/我的等级」状态栏安全区、夜间阅读不再变灰（对比度 3.33→12.89）。
        // 版本号唯一口径见 docs/VERSIONING.md；0.16起为核心公式×100+预览序号/正式99，
        //              不再手动 +1。0.7.0 = 扫码登录（网页发起 → App 扫码确认 → 网页自动登录）
        //              这一条新功能按 MINOR 计，同时带上 0.6.80 之后的题库导入/公开分享等。
        // 800 / 0.8.0 = 新增独立日历与完整课表小组件，保留原今日课表。
        // 900 / 0.9.0 = 长按添加小组件、扫码入口前置，连点发音及游客布局修复。
        // 904 / 0.9.4 = 小组件反馈收口（S149–S151）：预览改本机快照直接绘制、今日与整周
        //              可滚动、活会话刷新、添加页保留前台并核对真实绑定、紧凑节高档位。
        //              候选包 rc.1–rc.3 已在真机 K100 走过，rc 号不进更新渠道。
        // 905 / 0.9.5 = 修候选包与正式包同号导致装过 rc 的设备收不到更新（S154）：
        //              客户端同号下允许「本机预发布 → 清单正式」，并补齐小组件旧判据（S155）。
        // 906 / 0.9.6 = 顶栏加号改宽度动画让搜索栏伸缩；修切夜间模式后打开 App 一闪退回桌面（S156）。
        // 1100 / 0.11.0 = 试卷/文章区分与逐页 OCR 校对；PDF 横滑翻页与逐层返回；
        //              OCR 质量追修（中英双模型 + 版面还原）；番茄钟三列滚轮与侧滑设置；
        //              今日/整周小组件裁切与清晰度；网盘容量口径与分享链。
        // 1101 / 0.11.1 = 试卷/文章区分与逐页 OCR 校对；PDF 横滑翻页与逐层返回；
        //              OCR 质量追修（中英双模型 + 版面还原）；番茄钟三列滚轮与侧滑设置；
        //              今日/整周小组件裁切与清晰度；网盘容量口径与分享链。
        // M0 内测候选：Android 收口首页/学习/我的，社区入口隐藏，数据与后端保留。
        // 每日预览进preview、每周正式进stable；0.16起预览序号1–98、正式槽99。
        versionCode = 230004
        versionName = "0.23.0-beta.4"
        buildConfigField("String", "API_BASE_URL", "\"$configuredApiBaseUrl\"")
    }

    // Keep the existing emulator certificate, with locks inside the ignored workspace.
    val localDebugKey = rootProject.file("../../work/android-home/installed-debug.keystore")
    if (localDebugKey.exists()) signingConfigs.getByName("debug") {
        storeFile = localDebugKey
    }

    /**
     * 正式签名：keystore 与口令**一律从环境变量读**，不写进本文件、不进 git
     * （红线：凭据不进仓库）。本机口令在 `work/android-home/release-secrets.env`
     * （该目录已被 .gitignore 忽略）。
     *
     * 缺任一变量时**直接报错**而不是退回 debug 签名 —— 静默退回会让
     * 「以为发了正式包」实际发出的是 debug 签名包，用户后续装不上。
     */
    val releaseStorePath = (project.findProperty("releaseStoreFile") as String?)
        ?: System.getenv("RELEASE_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "tonglu"
    if (!listOf(releaseStorePath, releaseStorePassword).any { it.isNullOrBlank() }) {
        // 🔴 PKCS12 **不支持独立的 key 口令** —— keytool 建库时 keypass 被静默
        // 忽略，私钥实际是用 store 口令加密的。若这里把 keyPassword 设成别的值，
        // `packageRelease` 会报「Given final block not properly padded」（=
        // 解密失败，**看起来像密码错，其实在说 keypass 不匹配**）。
        // 所以：key 口令缺省时一律跟 store 口令走。
        val keyPass = releaseKeyPassword?.takeIf { it.isNotBlank() } ?: releaseStorePassword
        // 🔴 路径基准是`apps/android`（rootProject），不是仓库根也不是 app 模块
        //（`file()` 会解析成 apps/android/app/work/...）。环境变量里给仓库根相对
        // 路径（如 work/android-home/release.keystore）时要退回上一级。
        val store = rootProject.file(releaseStorePath!!).takeIf { it.exists() }
            ?: rootProject.file("../../$releaseStorePath").takeIf { it.exists() }
            ?: throw GradleException(
                "找不到 release keystore。已试：rootProject/$releaseStorePath " +
                    "与../../$releaseStorePath（环境变量里请给仓库根相对路径）"
            )
        signingConfigs.create("release") {
            this.storeFile = store
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = keyPass
        }
    } else {
        logger.warn(
            "未提供 release 签名凭据（RELEASE_KEYSTORE_PATH / RELEASE_STORE_PASSWORD）：" +
                "debug 构建不受影响，但**打 release 包会被下面的硬校验拦下**。"
        )
    }

    /**
     * 🔴 「真的要打 release 包」而凭据缺失时 —— **直接失败**，不要静默出个别的包。
     *
     * 实测（2026-10-11）：上面那段 `findByName("release")?.let{}` 只是**悄悄跳过**，
     * 后果是 `assembleRelease` **报 BUILD SUCCESSFUL**，却只产出
     * `app-release-unsigned.apk`（未签名）—— 文件名变了、失败没有，
     * 很容易被当成「出包成功」直接拿走发。
     *
     * 同一类事故在线上真的发生过一次：`app-updates/85/tonglu-0.6.71.apk` 是
     * **release 构建类型 + debug 证书**（见 BUGS S55）。用户从它更新到正式签名的
     * 0.6.72 会在签名校验处失败，只能卸载重装 —— 签名不能改，发出去就收不回。
     *
     * 只拦打包任务：debug / 单测 / lint 等不碰打包的流程不受影响。
     */
    // 🔴 缺凭据时**不许出 release 包**（背景见上面那段 KDoc 与 BUGS S55）。
    //
    // 为什么是一个**独立任务**，而不是 `packageRelease.doFirst`：
    //   `doFirst` 只在任务「真的执行」时才跑，而打包任务很常见是 `UP-TO-DATE` 被跳过
    //   —— 那样守卫等于不存在（2026-10-11 实测：加了 doFirst 仍然 BUILD SUCCESSFUL）。
    //   独立任务不声明输入/输出，因此**每次都会执行**。
    val checkReleaseSigning = tasks.register("checkReleaseSigning") {
        group = "verification"
        description = "缺正式签名凭据时让 release 打包失败（防止发出未签名 / debug 签名的包）"
        val missing = signingConfigs.findByName("release") == null
        doLast {
            if (missing) {
                throw GradleException(
                    "拒绝打 release 包：缺少正式签名凭据 RELEASE_KEYSTORE_PATH / RELEASE_STORE_PASSWORD。" +
                        "本机在 work/android-home/release-secrets.env，" +
                        "走 build_head_release.py / build_a31.py 会自动读取。" +
                        " 凭据缺失时 AGP 会产出**未签名包**并报「成功」，那种包发出去用户装不上。"
                )
            }
        }
    }
    tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }
        .configureEach { dependsOn(checkReleaseSigning) }

    /**
     * 离线语音那颗 `libonnxruntime.so` 有四个 ABI（arm64 22MB / armv7 15MB /
     * x86 26MB / x86_64 25MB），全打进去光 `lib/` 就99MB、整包 139MB。
     *
     * 🔴 **ABI 列表只能在这里声明一次**。`ndk.abiFilters` 在 `defaultConfig`
     * 与 `buildType` 里是**叠加**而不是覆盖 —— 之前在 `defaultConfig` 写了
     * `arm64-v8a + armeabi-v7a`、又在 debug 里 `+= listOf("arm64-v8a")`，
     * 结果 debug 包照样带着 armeabi-v7a（实测 95MB），“debug 只打 arm64”没生效。
     *
     * 默认只打真机的 arm64-v8a（本项目自用，验证机是小米 12S）。
     * 要在模拟器（MuMu 是 x86_64）上验收离线语音时加 `-PabiAll=true`。
     */
    val allAbi = (project.findProperty("abiAll") as String?)?.toBoolean() ?: false
    val targetAbis = if (allAbi) {
        listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    } else {
        listOf("arm64-v8a")
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            ndk { abiFilters += targetAbis }
        }
        release {
            // 🔴 先别开 R8（isMinifyEnabled = true）：项目没有 proguard-rules.pro，
            // 而 Compose / Room-KSP / sherpa-onnx(JNI) / kotlinx.serialization
            // 都依赖反射，开R8 要先补 keep 规则并真机回归。当前 release 唯一
            // 的体积杠杆是 ABI（见上方 targetAbis），不是混淆。
            // 🔴 开 R8 的前置条件（三条缺一不可）：① 写 proguard-rules.pro
            // ② assembleRelease 装真机回归 ③ 留崩溃日志渠道。
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            ndk { abiFilters += targetAbis }
            // 🔴 只在凭据齐了才挂 release 签名。否则 assembleRelease 会拿
            // debug 签名出包（看着成功了，实际发出去的是 debug 包）。
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // onnx 模型与词典本来就是压缩不掉的二进制，让 aapt 再去压一遍只会拖慢首次加载。
    androidResources { noCompress += listOf("book", "onnx", "fst", "txt") }

    /**
     * JVM 单测里 Android 的 `org.json` 是 stub（每个方法都抛 "not mocked"），
     * 而备份 / 计划的边界检查大量依赖 JSON。用 `returnDefaultValues` 会让
     * `JSONObject.getString` 静默返回 null —— 测试「通过」了但什么都没验。
     *
     * 所以不用那个开关，改用 `testImplementation("org.json:json")`：真实现，
     * 解析失败会真抛，测试才锁得住东西（见 dependencies 里的注释）。
     */
    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = false
        }
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation("junit:junit:4.13.2")
    // 真实现版 org.json：Android 在 JVM 单测里给的是 stub（方法一律抛 not mocked），
    // 备份 / 计划的 JSON 边界测不了。这份只在单测 classpath 上，不进 APK。
    testImplementation("org.json:json:20240303")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    // HTTP dependencies remain shared with the application API.
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.miuix.ui)
    implementation(libs.android.liquid.glass)
    implementation(libs.kyant.shapes)
    // 课表解析（教务系统 HTML 表格）
    implementation(libs.jsoup)
    // PDF 文字提取；原版页面由系统 PdfRenderer 绘制。许可与 NOTICE 随 APK 打包。
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation(libs.markdown.m3)
    implementation(libs.reader.images)
    implementation(libs.reader.network)
    implementation("com.google.zxing:core:3.5.4")
    // 扫码登录：CameraX 取景 + zxing core 解码（只用 core，不引 mlkit）
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // EF09 拍照识词：ML Kit 文本识别（on-device，拉丁文模型；不联网、照片不出本机）
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    // 任务B 追修 OCR 质量：试卷封面/中文说明需要本机中文模型（on-device，图片不上传）。
    // 许可证：Google ML Kit（随 Google Play 服务/App 内嵌模型条款）；不调用云端。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")

    // 桌面小部件（今日课表）。Glance 1.2.0 稳定版，minSdk 23 ≤ 本项目 24。
    // 只加 appwidget + material3（GlanceTheme 取色）；不引 wear-tiles。
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // 离线语音合成：sherpa-onnx（Apache-2.0）官方 Android AAR，含 onnxruntime 与各 ABI 的 so。
    // 不用 maven：官方只发布在 GitHub release（`work/tts/prepare_assets.py` 负责取回）。
    implementation(files("libs/sherpa-onnx.aar"))

    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

val buildLocalSendNative = tasks.register<Exec>("buildLocalSendNative") {
    inputs.dir(rootProject.file("native/localsend"))
    inputs.file(rootProject.file("../../scripts/build_localsend.py"))
    outputs.dir(layout.buildDirectory.dir("generated/localsend"))
    workingDir(rootProject.file("../.."))
    commandLine(providers.environmentVariable("PYTHON").orElse("python").get(),
        rootProject.file("../../scripts/build_localsend.py").absolutePath)
}
tasks.named("preBuild") { dependsOn(buildLocalSendNative) }
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/localsend").get().asFile)

// 两个 assets 源目录都要挂上：
//   content/learning  —— 学习内容（catalog.json / books / licenses），跨端共享
//   src/main/assets   —— 客户端自己的离线前端资源（web/katex.min.js 等）
// `assets.srcDirs(...)` 是「设置」而非「追加」，只写一个就会把另一个挤掉 ——
// 之前这里只有 content/learning，所以 src/main/assets 一直是失效的。
android.sourceSets.getByName("main").assets.srcDirs(
    rootProject.file("../../content/learning"),
    file("src/main/assets"),
)


