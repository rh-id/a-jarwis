# Tech Stack

## Build
- Gradle 9.6.0 (wrapper), AGP 9.4.1, Groovy DSL build files, version catalog `gradle/libs.versions.toml`
- JDK 17 required (CI uses adoptium 17); compileSdk/targetSdk 37; minSdk 21
- App identity: `m.co.rh.id.a_jarwis`; versionCode/versionName live in `app/build.gradle` (not catalog)
- Release signing only via CI env vars: `SIGNING_KEY` (base64 keystore), `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`

## Language
- Java 17 (app/base/settings). EXCEPTION: `:ml-engine` uses source/target Java 1.8 — deliberate, don't "upgrade" blindly.
- Core-library desugaring (`desugar_jdk_libs` 2.1.5) enabled in ALL FOUR modules.

## Key libraries (all via catalog)
- In-house (JitPack `com.github.rh-id`): a-provider v0.0.27 (DI), a-navigator v0.0.72 + a-navigator-extension-dialog, a-logger v0.0.4, rx-utils v0.0.3, concurrent-utils v0.0.4 (WeightedThreadPool)
- RxJava 3.1.12 / RxAndroid 3.0.2; WorkManager 2.10.5 (on-demand init); Material 1.13.0; Room 2.7.2 (runtime `api` + annotationProcessor compiler in `:base`; schemaLocation set — declared but UNUSED, no in-repo entities: dead dependency)
- CameraX 1.4.2 (core/camera2/lifecycle/view, `:app` only — the live QR scanner; NO ML Kit)
- AndroidX appcompat/material/constraintlayout/drawerlayout/swiperefreshlayout/recyclerview/exifinterface; leakcanary-plumber 2.14
- Test: JUnit 4.13.2, Mockito 4.11, espresso 3.7.0, androidx.test-junit, work-testing (androidTest only); zxing core 3.5.3 is `androidTestImplementation` in `:ml-engine` ONLY (test oracle for the QR-censor effectiveness tests — must never reach the shipped APK)

## ML stack
- OpenCV 5.0.0.1 official Android AAR from Maven Central (`org.opencv:opencv:5.0.0.1`, catalog entry `libs.opencv`), consumed by `:ml-engine` directly; no local module (previously vendored 4.8.0 SDK — removed 2026-09)
- ONNX models are NOT in the APK — downloaded on demand (`ModelType`/`ModelCatalog`/`ModelDownloader` via `ModelDownloadWorker`) into `filesDir/ml-engine/engine/**`; legacy bundled `nst_*_9.onnx` files are renamed in place by `ModelCatalog.migrateLegacyIfNeeded`
- Run via OpenCV `FaceDetectorYN`/`FaceRecognizerSF` and DNN (`STProcessor`); processors cached per theme by `MLEngineInstance`
- `AiImageDetectProcessor` runs the AI_IMAGE_DETECT model: 384x384 input (shortest-edge resize 440 + center crop, CLIP-normalized), single logit → pFake = sigmoid(logit); constructor silently falls back to the classic OpenCV DNN engine when the default engine cannot load the graph
- QR features (censor/scanner) use the in-box `org.opencv.objdetect.QRCodeDetector` — no model file at all

## CI (.github/workflows)
- `gradlew-build.yml`: `./gradlew build` on push/PR → master
- `android-emulator-test.yml`: `connectedCheck`, matrix api-level 26/31/36 on `ubuntu-latest` (KVM, emulator-runner v2.38, plain script — no heap hacks); failing jobs upload the connected test reports as artifacts (`androidTest-results-<api>`)
- `android-release.yml`: on tag `v*` → build, GitHub Release with debug+release APKs + `app/build/changelog.txt` (generated from fastlane changelog, see `task_completion`)
