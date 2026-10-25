# Build verification

Taimi Forest (苔米林 / TML / taimilin) branding snapshot verified on 2026-10-26, independently of the private official client. Existing SDK/dependency caches were used; this is not a hermetic or byte-for-byte reproducibility claim.

| Check | Result |
|---|---|
| Windows x64 / JDK 25 / SDK 37 / Gradle 9.7.1 / NDK 29.0.14206865 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source consistency | Clean Android source before/after, unchanged fingerprint |
| Android source SHA-256 | `0bfe68573ed039aa10b07888dac90b027025168e1e30d34b6fe9617f2307cad5` |
| Receipt ID | `20261025T222637-21796-1792967197565929500` |
| JVM tests | 1037 passed; 0 failures/errors/skips |
| Python tooling | 8 passed, 1 explicitly skipped (excluded exam corpus) |
| APK | Debug/all ABI, 195960658 bytes; SHA-256 `531c449f5361ff331af92122a78f767de4167cdc8eacf873925ac25235fb6fba` |
| APK names/package | 苔米林 / Taimi Forest; cn.tonglu.app.opensource |
| Resource boundary | No bundled exam bodies, school maps or full learning packs |
| Dependency integrity | sherpa-onnx v1.13.8 AAR SHA-256 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96; Gradle checksum pinned in wrapper |

Native bridge uses vendored Rust source and locked dependencies, with existing build caches. No private prebuilt native libraries were copied for this update. This debug APK is a verification artifact, not an official signed update; the official release channels were not changed.

The official client with the same branding UI had separate emulator checks for preserved installation/data, pulled-back APK hash and full startup wordmark. This public-package build was not installed on a device in this round. Small-screen/large-font branding, physical phones and Linux/macOS builds were not verified here. Previous snapshots' device checks do not stand in for testing this package.

Exact manifest hashes and bounded credential/resource scans passed. Gitleaks 8.30.1's source-only scan found one reviewed upstream Rust test assertion containing a PEM header, without an encoded key. An initial scan including active build caches could not read locked files and was not counted as a completed scan; the separate exported source snapshot was then scanned successfully. Scans are not an exhaustive security or rights guarantee. No private Git history, accounts or signing material are included.
