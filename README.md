# 苔米厸 · Taimi Forest · Android

<img src="apps/android/app/src/main/artwork/store-icon-512.png" width="96" alt="苔米厸厸字标" />

让学习、工作和人与人的连接轻松一点。这是苔米厸（Taimi Forest）安卓客户端的完整源码，包含单词学习、阅读与精读、课表与桌面小组件、番茄钟、个人书库、校园工具和局域网传输。

技术缩写 **TML**，内部代号 **taimilin**。名称与兼容约定见 [品牌说明](docs/BRAND.md)。

**项目代码采用 GPL-3.0-only。** 第三方代码、词典和素材沿用各自许可，见 [THIRD_PARTY.md](THIRD_PARTY.md)。这是独立安卓源码仓库，后端、网页、管理台和运维暂不在公开范围内。

## 构建

首批验证环境为 Windows x64；Linux/macOS 的原生编译入口已按平台实现，但尚未实机验证。

需要 Git、Python 3.11+、JDK 25、Android SDK Platform 37 / Build Tools 37、NDK 29.0.14206865、Rust stable（建议使用 rustup）。Gradle Wrapper 固定版本，依赖版本在 `apps/android/gradle/libs.versions.toml`；Rust 依赖由 Cargo.lock 固定。

1. 安装 SDK 和 NDK，将 `ANDROID_HOME` 指向 SDK，`JAVA_HOME` 指向 JDK；将 Cargo/Rust 和 Python 加到 PATH。Windows 还需 MSVC Build Tools 的 C++ 工具链。
2. 安装 Rust Android targets：`rustup target add aarch64-linux-android x86_64-linux-android`。
3. 执行 `python scripts/tts_prepare_assets.py --only aar`，从 sherpa-onnx 官方 release 获取编译依赖，不会下载声线模型。
4. 在仓库根执行 `python .workbuddy/build.py compileDebugKotlin testDebugUnitTest assembleDebug --max-workers=2 "-Pkotlin.compiler.execution.strategy=in-process"`。

首次构建需要网络下载 Gradle/Maven/Cargo 依赖。原生桥会为 arm64-v8a 与 x86_64 编译；默认 APK 仅打包 arm64-v8a，模拟器可加 `-PabiAll=true`。APK 位于 `apps/android/app/build/outputs/apk/debug/`，receipt 位于 `.workbuddy/ai/runtime/builds/`，包含真实退出码、源码指纹与新产物哈希。

Android Studio 可打开 `apps/android`。`local.properties`、构建缓存、签名私钥和口令均不入库。`ANDROID_NDK_HOME` 可显式选择 NDK，`PYTHON` 可选择供 Gradle 调用的 Python 可执行程序。

## 开源构建与官方版本

- 默认包名为 `cn.tonglu.app.opensource`，可与官方 App 并存，不覆盖用户现装版本。
- 默认 API 地址为 `https://example.invalid/api/v1/`，本地功能可以使用；需要服务器的登录、社区、同步等必须配置兼容后端。可在命令后追加 `-PAPI_BASE_URL=https://your-host.example/api/v1/`。本仓库不附带后端，也不承诺第三方自建版本可访问官方服务。
- 构建正式包须自行提供 `RELEASE_KEYSTORE_PATH`、`RELEASE_STORE_PASSWORD`，可选 `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`。**官方私钥不公开**；自行签名的包不能作为官方 App 的更新包分发。
- 资源、语音下载和更新服务的客户端代码完整保留，联网服务的可用性及使用条件由相应服务决定。开源源码不提供账号、服务器额度或官方签名。

## 内容范围

开源构建不附带真题正文、整篇译文、PDF、模型权重、学校地图图片或混合许可的完整资料包。四六级目录保留来源链接、全文指纹及数值语法位置；用户点选后从固定上游版本读取资料，仅放入应用私有缓存，全文匹配后才能应用语法分色。在线可访问不代表取得教材再分发许可；来源可能受网络影响，目录并非所有年份套卷题型齐全。个人文章仍可导入。

精读使用 Google Translate（ML Kit）按需下载语言模型并在本机翻译；原文和译文不发送给 Google，SDK 可能联网更新模型并发送运行/使用指标，见[条款与隐私](https://developers.google.com/ml-kit/terms)及[Google Translate 文档](https://cloud.google.com/translate)。模型就绪后可断网使用，结果只作为粗略辅助理解。Google SDK、模型和商标适用各自条款，并未被本项目重新许可为GPL。

保留了两个 BSD-3-Clause 精简词书、原创练习题、阅读器/词库/精读代码、原生传输源码、测试、Room schema、原创品牌素材和相关工具。语音模型及角色声音实验不在仓库中。

`scripts/gen_launcher_icons.py` 可用 Pillow 按内置几何图形重新生成薄荷色厸字标资源。`scripts/intensive_reading.py` 和对应 Python 测试保留预处理、CUDA 分析、翻译、校验和打包逻辑；它不是 Android 构建的前置步骤，运行前需要自备有权使用的材料、CUDA PyTorch/Stanza/Transformers 环境和模型。外部输入放 `work/intensive-input/`，模型/中间结果放 `work/intensive-reading/`；不因脚本能处理某份真题就取得再分发权。个人文章仍可通过客户端原有导入流程使用。

`SOURCE-MANIFEST.json` 记录源码快照、逐文件指纹、资源排除和针对开源构建的明确改动。私有项目的历史、账号配置、部署日志和签名从未复制到本仓库。检查记录见 [BUILD-VERIFICATION.md](BUILD-VERIFICATION.md)。

原生依赖的首次构建较慢，`-j 2` 限制 Rust 并发；不会调用 GPU 编译 Kotlin/Rust。Python 边界回归可执行 `python -m unittest discover -s scripts/tests -p test_intensive_reading.py` 和 `python -m unittest discover -s scripts/tests -p test_cet_remote_catalog.py`；公开源码没有整批原文输入，整批真题覆盖验证明确跳过，不能把它算作通过。数值目录不是Android构建期间重新生成的；重新分析需自备可使用材料与GPU环境。

## 参与

欢迎提交能复现的问题、小而完整的修复和体验建议。请说明版本、设备、复现步骤、期望结果；日志先去除账号、Cookie、令牌和私人正文。校园接入只使用官方入口及本机 WebView，不接受绕过统一认证、导出校园 Cookie 的实现。

提交贡献时，请确认你有权提交并按 GPL-3.0-only 提供贡献；已有第三方文件继续保留原始版权与许可。品牌和第三方标识不因代码开源而自动获得商标授权。
