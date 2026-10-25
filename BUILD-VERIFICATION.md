# Build verification

Taimi Forest / 苔米林 sentence-study panel and original-sentence playback update, verified 2026-10-26. Independent Android-only source build using existing dependency caches, not a hermetic or byte-identical reproducibility claim.

| Check | Result |
|---|---|
| Windows x64 / JDK25 / SDK37 / NDK29.0.14206865 / Gradle9.7.1 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source | Clean Android tree before/after, unchanged fingerprint |
| Android source SHA-256 | `2e5d7bdd23c6f810077a45af5d123f45e14dc1a4240170ecff4bd2b41d02435f` |
| Receipt | `20261025T232253-11792-1792970573036625700` |
| JVM | 1049 passed; no failures/errors/skips |
| Python tooling | 8 passed, 1 explicitly skipped: excluded exam-corpus coverage |
| APK | Debug/all ABI, 198059441 bytes; SHA-256 `233647f781975258a40cac16a42347cbac13505bab9f6a16d7083791e7075db3` |
| Resource boundary | No bundled exam bodies, school maps or full learning packs |

Native bridge uses vendored Rust source and locked dependencies with existing build caches, without copied private native libraries. sherpa-onnx v1.13.8 AAR remains pinned to SHA-256 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96; wrapper distribution checksum remains pinned.

This public-package APK was not installed on a device in this round. The final official-client build had separate 320dp/font-1.6 emulator checks: fixed header/actions while a long sentence scrolls, visible AI configuration gate, system English speech active audio track, explicit stop, background stop, dismissal stop and return to the reader. All nine criteria passed on the final build. Audio tracks verify playback state, not pronunciation quality. The ready-analysis translation/summary/collapsible-grammar branch has code and unit checks, but no live-model success-path device check. No claim of actual GPU/AI accuracy improvement or physical-phone acceptance is made. Prompt v2 changes subsequent explicit requests; legacy validated cache access stays isolated by account. No automatic AI request was added.

Exact file hashes and bounded credential/resource checks passed. Source-only Gitleaks 8.30.1 found one reviewed upstream Rust PEM-header assertion without encoded key material, no unresolved finding. Scans are not an exhaustive security/rights guarantee. No private history, accounts, signing material or deployment scripts were exported. This debug APK is a verification artifact, not a published official update; Linux/macOS builds were not tested.
