# Tech Stack

## Build
- Gradle 9.6.0 (wrapper), AGP 9.4.1, Groovy DSL build files, version catalog `gradle/libs.versions.toml`
- JDK 17 required (CI uses adoptium 17); compileSdk/targetSdk 34; minSdk 21
- App identity: `m.co.rh.id.a_jarwis`; versionCode/versionName live in `app/build.gradle` (not catalog)
- Release signing only via CI env vars: `SIGNING_KEY` (base64 keystore), `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`

## Language
- Java 17 (app/base/settings). EXCEPTION: `:ml-engine` uses source/target Java 1.8 — deliberate, don't "upgrade" blindly.

## Key libraries (all via catalog)
- In-house (JitPack `com.github.rh-id`): a-provider (DI), a-navigator + a-navigator-extension-dialog, a-logger, rx-utils, concurrent-utils (WeightedThreadPool)
- RxJava 3.1.4 / RxAndroid 3.0.0; WorkManager 2.8.1 (on-demand init); Room 2.5.2 (runtime+annotationProcessor in `:base`; schemaLocation set, no in-repo entities)
- AndroidX appcompat/material/constraintlayout/drawerlayout/swiperefreshlayout/recyclerview/exifinterface; leakcanary-plumber
- Test: JUnit4, Mockito 4.11, espresso, androidx.test-junit, work-testing (androidTest only)

## ML stack
- OpenCV 5.0.0.1 official Android AAR from Maven Central (`org.opencv:opencv:5.0.0.1`, catalog entry `libs.opencv`), consumed by `:ml-engine` directly; no local module (previously vendored 4.8.0 SDK — removed 2026-09)
- ONNX models in `ml-engine/src/main/res/raw/`: `face_detection_yunet_2023mar.onnx`, `face_recognition_sface_2021dec_int8.onnx`, `nst_{mosaic,candy,rain_princess,udnie,pointilism}_9.onnx`
- Models copied at runtime to `filesDir/ml-engine/engine/**` by `MLEngineInstance` (once, if absent); run via OpenCV `FaceDetectorYN`/`FaceRecognizerSF` and DNN (`STProcessor`)

## CI (.github/workflows)
- `gradlew-build.yml`: `./gradlew build` on push/PR → master
- `android-emulator-test.yml`: `connectedCheck`, matrix api-level 26/29, macos runner
- `android-release.yml`: on tag `v*` → build, GitHub Release with debug+release APKs + `app/build/changelog.txt` (generated from fastlane changelog, see `task_completion`)
