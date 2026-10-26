# Build verification

Taimi Forest / 苔米林 0.19.0-beta.1 (190001), independently verified on 2026-10-26. Android-only source, using existing dependency caches; not a hermetic or byte-identical reproducibility claim.

| Check | Result |
|---|---|
| Windows x64 / JDK25 / SDK37 / NDK29.0.14206865 / Gradle9.7.1 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source | Clean Android tree before/after, unchanged fingerprint |
| Android source SHA-256 | `f8d371375c5f1c1d876745d7d861ef1f03b7a7a149ba2d3168dda00803e5a5dd` |
| Receipt | `20261026T000008-30432-1792972808801570700` |
| JVM | 1049 passed; no failures/errors/skips |
| Python tooling | 8 passed; 1 explicitly skipped: excluded exam-corpus coverage |
| APK | Debug/all ABI, 194908371 bytes; SHA-256 `372039e68cea15c170be01b39910ce6e7d84dee004c5b7caa34e3cb0661ac7e2` |
| Resource boundary | No bundled exam bodies, school maps or full learning packs |

The standalone package is cn.tonglu.app.opensource and does not replace the official app. Its debug APK was not installed on a device in this round and is not an official update. The official release candidate uses the unchanged original signing certificate and separate build evidence. Source changes include the new app names, direct English word/sentence selection, bilingual translation controls, independent English typography, collapsible grammar and system English sentence playback. Detailed official-client emulator evidence covers reading positions, narrow/large-font controls and audio stop on explicit action/background/dismissal; this is not a claim that the public package was device-tested. Actual remote AI/GPU accuracy and a live-model success-path device check remain unverified. Linux/macOS builds were not tested.

Native bridge uses vendored Rust source and locked dependencies with existing build caches, without copied private native libraries. sherpa-onnx v1.13.8 AAR is pinned to SHA-256 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96; Gradle distribution checksum is pinned in the wrapper.

Exact source-manifest hashes and bounded resource/credential checks passed. Source-only Gitleaks 8.30.1 found one reviewed upstream generated-PEM-header assertion, no encoded private key or unresolved finding. Scans are not exhaustive security/rights guarantees. No private Git history, accounts, signing material, backend or operations scripts were exported.
