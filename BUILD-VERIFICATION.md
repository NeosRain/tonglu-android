# Build verification

Taimi Forest / 苔米厸 0.20.0-beta.2 (200002), independently verified on 2026-10-26. Existing dependency caches were used; this is not a hermetic or byte-identical reproducibility claim.

| Check | Result |
|---|---|
| Windows x64 / JDK25 / SDK37 / NDK29 / Gradle9.7.1 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source | Clean Android tree before/after; unchanged fingerprint |
| Android source SHA-256 | `f0e59aea683bbab418e05be9dc5eeb8bcea85aebd96bcdb8ca3ba074e38a9871` |
| Receipt | `20261026T054306-19036-1792993386528879400` |
| JVM | 1059 passed; zero errors/failures/skips |
| Python | 8 passed; 1 explicitly skipped for excluded exam corpus |
| APK | All ABI debug; 194748696 bytes; SHA-256 `7dd12d2adc8008c1d1e0791267ba0ae417328a37006551085e846f65148e1987` |
| Resource boundary | No exam bodies, school maps or full mixed-license learning packs |

This source fixes widget root/footer entry and uses actual calendar widget dimensions. Overlapping-course selection shares course colors, card-origin transitions and whole-card detail entry. Official-client API35 emulator checks covered three widget types, date/week navigation, both overlapping courses, narrow large-font scrolling and test-data cleanup. The public cn.tonglu.app.opensource APK was not device-installed; it cannot replace the official app. Physical HyperOS, older API widget hosts and frame-performance were not tested. These checks do not prove actual AI/GPU output quality. Linux/macOS builds were not tested.

Native bridge uses vendored Rust source and locked dependencies, without copied private native libraries. Gradle distribution checksum is pinned; sherpa-onnx v1.13.8 AAR SHA-256 is 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96. The launcher generator uses Pillow.

Exact manifest hashes and bounded resource/credential checks passed. Gitleaks 8.30.1 source scan found one reviewed upstream PEM-header assertion, without an encoded private key or unresolved finding. Checks are not exhaustive security/rights guarantees. No private repository history, credentials, signing material, backend or operations scripts were exported.
