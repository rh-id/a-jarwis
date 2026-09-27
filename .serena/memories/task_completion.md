# Task Completion Checklist

No formatter/linter/static analyzer configured — do not invent or add commands.

1. Compile + lint everything (CI-equivalent): `.\gradlew.bat build`
   - Minimum for touched modules: `.\gradlew.bat :app:assembleDebug` (pulls :base/:settings/:ml-engine)
   - No native/CMake modules anymore (OpenCV is a prebuilt Maven AAR since 2026-09)
2. Tests: only instrumented tests exist (`base` SerializeUtilsTest; `ml-engine` FaceEngineTest/STEngineTest). They need an emulator:
   `.\gradlew.bat connectedCheck`
3. If release intended: bump versionCode/versionName in `app/build.gradle` AND create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (its absence breaks release-artifact changelog generation in CI).

Version-control notes: repo uses `master` (not main); CI triggers on tags `v*` for releases; never commit `local.properties`.
