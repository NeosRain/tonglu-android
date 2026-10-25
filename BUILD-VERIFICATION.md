# Build verification

Verified on 2026-10-25. This is a standalone Android source build, separate from the original monorepo build. It uses existing public dependency/SDK caches; it is not a claim of a hermetic or byte-for-byte reproducible build.

| Check | Result |
|---|---|
| Windows x64 / JDK 25 / Android SDK 37 / Gradle 9.7.1 / NDK 29.0.14206865 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source consistency | Clean Android tree before/after, unchanged source fingerprint |
| Android source SHA-256 | `6b194690f9de2caf9db601e3b0c4cccbf1056d628836b9337b3b7b30bd6c915b` |
| Build receipt ID | `20261025T101459-46476-1792923299515332000` |
| JVM tests | 1037 tests, 0 failures/errors/skips |
| Optional Python tooling | 7 passed, 1 explicitly skipped: full exam-pack coverage, because those texts are excluded |
| Native bridge | arm64-v8a and x86_64 built from vendored Rust source with locked Cargo dependencies; no copied private .so |
| sherpa-onnx AAR | Downloaded from upstream v1.13.8; 50,129,134 bytes; SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`, matches upstream release digest |
| Gradle distribution | Wrapper pins SHA-256 `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a` from the official distribution checksum |
| Verification APK | Debug/all-ABI test artifact, 194,890,803 bytes; SHA-256 `1aa6bce0fbe9413908a5ca33bb3ef517c8ede11215900171c3ccb342be4597f9` |
| APK resource boundary | Empty intensive catalog present; excluded exam bodies, school maps and learning packs absent |

The independent `cn.tonglu.app.opensource` package was installed on an Android 15 x86_64 emulator, with pulled-back APK bytes matched to the build. Guest startup, the empty learning catalog, map-source notice and excluded-exam notice were checked. The final source also corrects the home map card to say online viewing. This verification APK is not an official signed update and is not published as a release artifact.

The original UI change had separate emulator checks for continuous reading, sentence/word selection, local translation draft recovery, keyboard focus and 320dp/font-1.6 layouts. No current physical-phone UX acceptance is claimed. User accounts, production server flows, every optional model/voice and Linux/macOS builds were not verified here.

The exact exported file manifest was checked for hashes, private paths and credential patterns. Gitleaks 8.30.1 found one reviewed false positive: the upstream Rust unit test checks a generated PEM header with `starts_with`; it contains no encoded private key. Pattern scans are not proof that every possible secret or rights issue is detectable. No private Git history is copied into this repository.
