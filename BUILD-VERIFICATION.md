# 0.18.0 preview source verification

Verified 2026-10-25 on Windows x64, JDK25, SDK37, NDK29.0.14206865, Gradle9.7.1. The standalone open-source variant uses existing public dependency caches; this is not a hermetic or byte-reproducible-build claim.

- compileDebugKotlin / testDebugUnitTest / assembleDebug: exit0, clean Android tree, sourceConsistent=true.
- JVM: 1037 tests, no failures/errors/skips.
- Python tooling: 8 passed, 1 explicitly skipped (unshipped exam-pack coverage).
- Native arm64-v8a and x86_64 bridge built from source with locked Cargo dependencies.
- No exam body assets are packaged. Empty catalog is a declared no-exam-texts policy; audits reject hidden leftover article files. Personal article handling and word-review code remain.
- AAR v1.13.8 checksum: 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96.
- Gradle distribution checksum: acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a.

Android source SHA-256: `9b9eb735a85672b669b6f5397efca63c2823000d6bf7d19461b381e812e58d95`

Receipt: `20261025T152328-25052-1792941808719222600`

Debug verification APK: 194890835 bytes; SHA-256 `012da5ed6c7b8329a572e2be2140fad91fd5a4659a67a184c2f2f0d5ca76e513`. This is not the original-signed official update APK.

The production variant has separate original-signature, version, artifact and emulator checks. The current open-source namespace/API variant was compiled and unit-tested here, and was not reinstalled for device UX acceptance this round. Physical-phone, Linux/macOS and arbitrary model/voice quality are not claimed.

Credential-pattern and manifest audits have no unresolved findings. Gitleaks reports the previously reviewed upstream PEM-header test assertion, which contains no encoded private key. No private history is copied. Scanning is not a comprehensive security or rights guarantee.
