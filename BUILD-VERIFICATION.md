# 0.18.0 stable source verification

Verified 2026-10-25 on Windows x64, JDK25, SDK37, NDK29.0.14206865 and Gradle9.7.1. This standalone variant uses public dependency caches and its own build outputs; no hermetic or byte-reproducibility claim.

- compileDebugKotlin, testDebugUnitTest, assembleDebug: exit0; clean Android tree and unchanged source fingerprint.
- JVM: 1037 tests, 0 failures/errors/skips.
- Python: 8 passed, 1 explicitly skipped (excluded full exam corpus).
- Native arm64-v8a and x86_64 bridge built from retained source/Cargo.lock.
- Exam bodies excluded; explicit empty-policy and leftover-file rejection are tested. Personal article and dictionary code retained.
- Official APK signature/version and emulator regression are separate checks in the production variant. This standalone namespace/API variant was compiled and unit-tested, not reinstalled for UX this round.

Android source SHA-256: `1a472fb21fd717e71f522c000984f3cc416a500ae99c21be2a7b547b6a4124e3`

Receipt: `20261025T153749-32008-1792942669465776600`

Debug test artifact: 194890827 bytes; SHA-256 `33f339ff3c4250f8f4866ae55f2d7a1d86f82881c73b95f0e77af289ede284a2`. This is not an official signed update.

Dependency pins: sherpa-onnx AAR v1.13.8 SHA256 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96; Gradle9.7.1 distribution SHA256 acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a.

Manifest audits use exact bytes (clone with core.autocrlf=false to prevent local newline conversions). Private Git history is not copied. Credential scans have no unresolved findings; the retained upstream PEM-header test assertion is a reviewed false positive and contains no encoded private key. Scans are not a comprehensive security/rights guarantee. Physical-phone, Linux/macOS, arbitrary live AI and voice quality are not claimed here.
