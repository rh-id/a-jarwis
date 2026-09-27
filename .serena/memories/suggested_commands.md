# Suggested Commands (Windows / PowerShell)

Gradle wrapper is `gradlew.bat` (use `.\gradlew.bat <task>` in PowerShell). JDK 17 must be the active JDK (AGP 9 requirement).

## Build / run
- Full build (what CI runs): `.\gradlew.bat build`
- Debug APK only: `.\gradlew.bat :app:assembleDebug`
- Fast compile check of one module: `.\gradlew.bat :base:assembleDebug`
- Clean: `.\gradlew.bat clean`

## Tests (no JVM unit tests exist in repo)
- Instrumented tests require a running emulator/device: `.\gradlew.bat connectedCheck`
- Single module: `.\gradlew.bat :ml-engine:connectedAndroidTest`

## Release bookkeeping
- Bump `versionCode`/`versionName` in `app/build.gradle`, then add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (Gradle copies it to `app/build/changelog.txt` for GitHub Release; build fails-soft if missing)

## Notes
- No lint/format/typecheck tools configured; don't invent commands for them.
- File search: use rg/`Get-ChildItem`; quotes needed for paths with spaces (`Remove-Item -LiteralPath`).
- `local.properties` holds `sdk.dir`; never commit.
