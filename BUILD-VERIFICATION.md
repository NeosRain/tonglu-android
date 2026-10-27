# Build verification

Taimi Forest / 苔米厸 0.21.0-beta.2 (210002), independently built on 2026-10-27 (Asia/Shanghai). Existing dependency/native caches were used; this is not a hermetic or byte-identical reproducibility claim.

| Check | Actual result |
|---|---|
| Windows x64 / JDK25 / SDK37 / NDK29 / Gradle9.7.1 | compileDebugKotlin, testDebugUnitTest, assembleDebug: exit 0 |
| Source | Clean Android tree; unchanged fingerprint before/after |
| Android SHA-256 | `09e1ce7ce5e0444adaacb38b630215bc37c1c87f17bc734b0227a8bede56c45c` |
| Receipt | `20261027T135307-17504-1793109187655669900` |
| JVM | 1080 passed; zero failures/errors; one Windows symlink permission case skipped |
| Python | 14 passed; one full-corpus coverage case skipped because exam source texts are not included |
| Debug APK | All ABI; 240686262 bytes; SHA-256 `76d44c830ae291ffebc2c895307df141f47bc282def2c850f25f2f388ffb35d8` |
| Resource boundary | No exam bodies, translated passages, PDFs, model weights, school maps or full mixed-license learning packs |

This update adds provider model discovery/search and revised AI configuration, full timetable add/edit/manage controls, keyboard-safe course editing, and a predictive-back underlay that settles at its resting position. The launcher uses one flat mint 厸 mark, welcome copy is shorter, and My page offers local feedback drafting/copying followed by manual submission on GitHub.

Separate official-client API35 checks verified model-list and chat flows against a local fixture (not a paid provider), course add/edit/discrete-week persistence/delete while preserving all 14 original courses, a real campus-map back gesture, feedback drafting/copy/keyboard layout and 320dp/1.6 font rendering. Final opaque feedback and course-management backgrounds were inspected. Startup frame 13 shows the flat mark and new text. These staged checks are not a claim that this public APK was installed. Real paid AI, actual cancellation gestures, physical phone behavior and frame performance remain untested.

The existing 80-entry pinned-source CET catalog contains hashes and numeric grammar offsets only. Explicit selection downloads upstream material into private cache; coverage is partial. ML Kit translation is rough machine assistance. Google SDKs/models/marks retain separate terms, attribution and privacy notices in THIRD_PARTY.md; no GPL relicensing claim is made.

Exact source-manifest audit passed. Gitleaks scanned the fresh exported source and found only the reviewed upstream PEM-header assertion at native/localsend/vendor/core/src/crypto/cert.rs:248, with no encoded private key. A rejected earlier scan included ignored local build caches; its findings were not treated as source validation. Private history, backend, operations, credentials and signing material were not exported. These checks are bounded, not exhaustive security or legal guarantees. Linux/macOS builds were not tested.
