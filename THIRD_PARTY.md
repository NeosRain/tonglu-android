# Third-party code and content

The root GPL-3.0-only license applies to project-authored work, not a relicensing of upstream work. Preserve the following notices when redistributing. Dependency source versions are pinned in the Gradle version catalog, Cargo.lock and the notices below.

| Component | Terms and retained source/notices |
|---|---|
| Gradle Wrapper | Apache-2.0; `licenses/Gradle-APACHE.txt`; https://github.com/gradle/gradle. Distribution version is fixed in gradle-wrapper.properties. |
| Miuix 0.9.4 | Apache-2.0; `licenses/Miuix-APACHE.txt`; https://github.com/compose-miuix-ui/miuix/tree/v0.9.4 |
| Backdrop 2.0.1 / Shapes 1.2.0 | Apache-2.0; `licenses/Backdrop-APACHE.txt`, `licenses/Shapes-APACHE.txt`; https://github.com/Kyant0/AndroidLiquidGlass/tree/2.0.1 and https://github.com/Kyant0/Shapes/tree/1.2.0 |
| LocalSend Rust core v1.18.2 / af0416be50770a97760f7070684bc667b759a15c | Apache-2.0; `apps/android/native/localsend/vendor/core/LICENSE`, `NOTICE.md`; upstream https://github.com/localsend/localsend/tree/v1.18.2/packages/core |
| Project JNI bridge | Existing Apache-2.0 grant retained under `apps/android/native/localsend/LICENSE`; linked application distributed under GPL-3.0-only |
| Rust transitive dependencies | Cargo.lock, `native/localsend/dependency-licenses/`, and `app/src/main/assets/licenses/localsend-dependencies.zip`; retain per-crate license choices |
| JustByHeart adapted learning workflow | MIT; revision and changes in `assets/english/NOTICE.txt`, full `assets/licenses/JustByHeart.txt`; https://github.com/cdz-hy/JustByHeart |
| KyleBing English dictionary / two compact derivatives | BSD-3-Clause; `assets/licenses/EnglishVocabulary.txt`, `english/source.json`, `english/bundled/catalog.json`; https://github.com/KyleBing/english-vocabulary at c4c6c80879ff17d7025c28fb853a4991c8e6be6a |
| life-decision-guide | MIT skill / [CC BY 4.0 prose](https://creativecommons.org/licenses/by/4.0/) as indicated by upstream; retained `assets/learning/life-decision-guide-{LICENSE,NOTICE.md}`; https://github.com/eternity4719/HowToLiveBetter at fcc93eb9a4bdc26e8a4e4c94bdaae0a89265927b |
| PDFBox-Android | Apache-2.0; `assets/licenses/PdfBox-Android.txt` and `PdfBox-Android-NOTICE.txt` |
| KaTeX 0.16.11 | MIT; `licenses/KaTeX-MIT.txt` and `assets/web/LICENSES.txt`; https://github.com/KaTeX/KaTeX |
| marked 12.0.2 | MIT; `licenses/marked-MIT.txt` and `assets/web/LICENSES.txt`; https://github.com/markedjs/marked |
| DOMPurify 3.1.6 | Apache-2.0 OR MPL-2.0; Apache option retained in `licenses/DOMPurify-APACHE.txt`; https://github.com/cure53/DOMPurify |
| sherpa-onnx 1.13.8 | Apache-2.0 engine; downloaded separately by `scripts/tts_prepare_assets.py`; source https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8, license in `licenses/sherpa-onnx-APACHE.txt`. AAR contains additional upstream runtime dependencies under their own licenses. No AAR/model binary is committed here. |
| AndroidX, Compose, Kotlin, Coroutines, Miuix, Backdrop, shapes, Retrofit, OkHttp, Gson, Room, Glance, jsoup, Coil, Markdown renderer and other Maven dependencies | Downloaded by Gradle, not vendored as binaries. Exact versions and coordinates in `apps/android/gradle/libs.versions.toml` / app Gradle. Preserve upstream source/license notices if distributing binaries. |

Paths beginning `assets/` are relative to `apps/android/app/src/main/`.

Full copyrighted exam passages, derived translations/grammar, school map artwork and mixed-license learning packs are excluded from this source release. Their online availability is not treated as an open license. Model weights, anime character models/recordings, private files and user data are also excluded. The English speech index contains public resource metadata only, no character recordings or model weights; external audio/model use has separately stated terms in the application.

GPL source release does not itself verify every optional external model for every use. Do not describe model licensing, speaker/character rights and engine licensing as one permission.
