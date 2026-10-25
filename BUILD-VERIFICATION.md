# Build verification

Taimi Forest / 苔米林 direct English reading update, verified 2026-10-26. Independent Android-only source build using existing dependency caches; not a hermetic or byte-identical reproducibility claim.

| Check | Result |
|---|---|
| Windows x64 / JDK25 / SDK37 / NDK29.0.14206865 / Gradle9.7.1 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source | Clean Android tree before/after; unchanged fingerprint |
| Android source SHA-256 | `da7253a02b192c34855e5a97f90e75d5e9cb9f6e6e4bd0f2424e3b652fa99f14` |
| Receipt | `20261025T225543-36656-1792968943035886400` |
| JVM | 1043 passed; no failures/errors/skips |
| Python tools | 8 passed; 1 explicitly skipped: excluded exam-corpus coverage |
| APK | Debug/all ABI, 198059441 bytes; SHA-256 `ce61bb1a33b8f64e9eecb8c60c25d2ca8c1fe8bbd0400bd01aa2e1ec5c483113` |
| Resource boundary | No bundled exam bodies, school maps or full learning packs |
| App identity | 苔米林 / Taimi Forest; cn.tonglu.app.opensource |

The native bridge uses vendored Rust source/locked Cargo dependencies and existing build caches. sherpa-onnx v1.13.8 AAR remains pinned to SHA-256 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96; Gradle distribution checksum is pinned in the wrapper. No private prebuilt .so was copied for this update.

This public-package APK was not installed on a device in this round. The official-client build has separate emulator evidence for default word lookup, long-press sentence selection, preserved position, persisted typography, full-height settings and 320dp/font-1.6 translation dismissal. The final official-client delta only reserves translation-control space and was reinstalled/rechecked. These checks do not establish successful remote AI translation/grammar quality, physical-phone acceptance or Linux/macOS builds. The APK is a test artifact, not a published official update.

Exact file hashes, resource boundaries and bounded credential checks passed. Source-only Gitleaks 8.30.1 scan has one reviewed upstream Rust PEM-header test assertion without encoded key material, no unresolved findings. Scans are not an exhaustive security/rights guarantee. No private history, accounts, signing material or deployment scripts were exported.
