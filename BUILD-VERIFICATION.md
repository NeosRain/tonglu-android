# Build verification

Taimi Forest / 苔米厸 0.21.0-beta.1 (210001), independently built and checked on 2026-10-27 (Asia/Shanghai). Existing caches were used; this is not a hermetic/byte-identical reproducibility claim.

| Check | Actual result |
|---|---|
| Windows x64 / JDK25 / SDK37 / NDK29 / Gradle9.7.1 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source | Clean Android tree, unchanged fingerprint before/after |
| Android SHA-256 | `d9cb4c6c35eb5416dfd3a1420294dc2a54409d23109a624cb469dfe80c7d9029` |
| Receipt | `20261026T235524-33808-1793058924313825000` (UTC run ID, local date above) |
| JVM | 1068 passed, zero failures/errors; one symlink case skipped because Windows disallows creation |
| Python | 14 passed; one full-corpus case explicitly skipped (no exam source texts included) |
| Debug APK | All ABI; 240665330 bytes; SHA-256 `da21ec4aa2469df13abfdfe8ba88c27b3e22e919e837c0cc1a89e821427794b7` |
| Resource boundary | No exam bodies, whole translated passages, PDFs, model weights, school maps or full mixed-license packs |

The source contains an 80-entry pinned-source CET catalog with hashes and numeric grammar offsets. Explicit user selection downloads an upstream source into private local cache; full text must match before applying existing CUDA grammar coordinates. Coverage is partial across years/suites/types. The English home prioritizes reading. Installed/obsolete update APKs are cleaned without deleting pending/future packages or study data.

Official-client API35 checks (separate from this public APK) opened four representative CET articles from two PDF files and one Markdown file and verified their SHA-256. With airplane mode enabled, 24 previously untranslated sentences were translated locally (cache 40 to 64). Sentence navigation/read marks, cold persistence, 320dp/1.6 font progress and wrapped legend, syntax toggle, and audio active-to-stopped were checked. Translation is rough machine assistance, not verified expert translation. The public cn.tonglu.app.opensource APK was not installed. Physical phone, every catalog entry through Android PDFBox, older API devices and frame performance remain untested. A final attribution UI check belongs to the official release receipt.

Google ML Kit SDKs/models and Google marks retain their separate terms, attribution and privacy notices in THIRD_PARTY.md and the application. No claim that these SDKs or downloaded weights are GPL is made. Supplied Google badges are unmodified and separately fingerprinted.

Exact manifest audit passed. Gitleaks 8.30.1 reported only the reviewed upstream PEM-header assertion at native/localsend/vendor/core/src/crypto/cert.rs:248; no encoded private key. Private history, credentials, signing material, backend and operations files were not exported. These checks are bounded, not exhaustive security or legal guarantees. Linux/macOS builds were not tested.
