# Build verification

Taimi Forest / 苔米厸 0.20.0-beta.1 (200001), independently verified on 2026-10-26. Android-only source using existing dependency caches; not a hermetic or byte-identical reproducibility claim.

| Check | Result |
|---|---|
| Windows x64 / JDK25 / SDK37 / NDK29.0.14206865 / Gradle9.7.1 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source | Clean Android tree before/after, unchanged fingerprint |
| Android source SHA-256 | `34dd0431ab28b63ec4aa2e9d24eb1e6a41d3850c4c42a79ccbcd53649c9f95e0` |
| Receipt | `20261026T044908-42352-1792990148332604300` |
| JVM | 1059 passed; no failures/errors/skips |
| Python tooling | 8 passed; 1 explicitly skipped: excluded exam-corpus coverage |
| APK | Debug/all ABI, 194748696 bytes; SHA-256 `578387ddf6809bd55da54dade39f73c7aca970c2009f598665cebd102ec40440` |
| Resource boundary | No bundled exam bodies, school maps or full learning packs |

The standalone package is cn.tonglu.app.opensource and cannot replace the official app. Its debug APK was not device-tested this round. Official-client emulator checks were separate: direct course move/cancel/save, upper/lower edge resize, overlapping course retention, discrete weeks after cold restart, narrow-screen large-font details, horizontal scrolling without changing week and widget-picker/Main task isolation. Save/resize checks and final layout checks were performed on staged official-client builds; not all checks were repeated on the final package. Physical-phone behavior, all 30-period visual boundaries, automatic edge-scrolling and animation frame performance remain unverified. No actual AI/GPU quality claim. Linux/macOS builds were not tested.

Native bridge uses vendored Rust source and locked dependencies with existing caches, without copied private native libraries. sherpa-onnx v1.13.8 AAR SHA-256: 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96. Gradle distribution checksum is pinned in the wrapper. Launcher glyph generation uses Python and Pillow.

Exact manifest hashes and bounded resource/credential checks passed. Source-only Gitleaks 8.30.1 found one reviewed upstream PEM-header assertion, no encoded key or unresolved finding. These checks are not exhaustive guarantees. No private Git history, accounts, signing material, backend or operations scripts were exported.
