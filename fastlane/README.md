# Fastlane Metadata

This directory contains store listing metadata following the [fastlane supply](https://docs.fastlane.tools/actions/supply/) structure for Google Play.

## Directory Structure

```
fastlane/
└── metadata/
    └── android/
        ├── en-US/                                 # English (US) — source locale
        │   ├── title.txt                          # App title (max 30 characters)
        │   ├── short_description.txt              # Short description (max 80 characters)
        │   ├── full_description.txt               # Full store listing description
        │   ├── images/
        │   │   ├── featureGraphic.png             # Feature graphic (1024x500)
        │   │   ├── icon.png                       # App icon (512x512)
        │   │   └── phoneScreenshots/              # Phone screenshots
        │   └── changelogs/
        │       └── {versionCode}.txt              # Per-version release notes
        ├── de-DE/                                 # German
        ├── et/                                    # Estonian
        ├── fr-FR/                                 # French
        ├── id/                                    # Indonesian
        ├── is-IS/                                 # Icelandic
        ├── it-IT/                                 # Italian
        ├── nb-NO/                                 # Norwegian Bokmål
        ├── nn-NO/                                 # Norwegian Nynorsk
        ├── rm/                                    # Romansh
        └── zh-CN/                                 # Chinese (Simplified)
```

## Locale Content Policy

- **Descriptions** (`short_description.txt`, `full_description.txt`): translated per locale.
- **Changelogs**: kept in English in every locale (not translated).
- **Images**: `icon.png` and `featureGraphic.png` are identical copies of the en-US images; `phoneScreenshots/` are captured per locale in that locale's language (see Screenshots).

## Screenshots

Each locale ships 3 phone screenshots (1080×1920, raw emulator captures from the Pixel AVD):

1. `1.png` — Home screen
2. `2.png` — Blur Face editor with faces detected and blurred
3. `3.png` — Neural Style Transfer editor with the Mosaic style applied

Capture method per locale: switch the Android system locale (Settings provider
`system_locales` + zygote restart, requires `adb root`), then drive the app
(Blur Face → pick `/sdcard/Pictures/face_sample.jpg`; Apply Neural Style
Transfer → pick `style_sample.jpg` → Mosaic tile) and take `adb exec-out
screencap -p` captures after each screen settles. Copy each locale's results
into its `images/phoneScreenshots/`.

## How Changelogs Work

Changelog files are named by `versionCode` (e.g., `11.txt` corresponds to `versionCode 11` in `app/build.gradle`). The Gradle build (`app/build.gradle`) copies `metadata/android/en-US/changelogs/{versionCode}.txt` to `app/build/changelog.txt` for the in-app changelog.

## How to Update for a New Release

1. Bump `versionCode` and `versionName` in `app/build.gradle`
2. Create a new changelog file: `metadata/android/en-US/changelogs/{newVersionCode}.txt`
3. Copy the new changelog file into every other locale's `changelogs/` folder (English, untranslated)
4. Update `full_description.txt` or `short_description.txt` if features have changed, and translate the change into every locale
5. Replace screenshots in `images/phoneScreenshots/` if the UI has changed, and copy them to every locale

## References

- [fastlane supply documentation](https://docs.fastlane.tools/actions/supply/)
- [fastlane Android setup guide](https://docs.fastlane.tools/getting-started/android/setup/)
- Original reference: https://gitlab.com/-/snippets/1895688
