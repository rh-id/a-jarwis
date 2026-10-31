# Jarwis — Project Core

On-device Android AI utility app (GitHub `rh-id/a-jarwis`, branch `master`). Features: face blur, neural style transfer (5 themes), portrait mode (PP-HumanSeg background blur/remove), QR censor editor + live QR scanner, Magic Eraser (EfficientSAM+LaMa object removal), AI image detector (CommunityForensics), settings/theming (Material You dynamic colors). Package root `m.co.rh.id.a_jarwis`.

## Module graph (dependency direction)
- `:app` → `:base`, `:settings`, `:ml-engine` (UI entry, interactive editor pages)
- `:ml-engine` → `:base` (engines + WorkManager workers; OpenCV comes from the official Maven AAR, not a module)
- `:settings` → `:base` (settings pages)
- `:base` → nothing in-repo (shared infra; exposes a-provider/a-navigator/etc via `api`)

## Non-obvious invariants
- Pure Java (no Kotlin sources). minSdk 21, compileSdk 37 / app targetSdk 37, JDK 17; core-library desugaring (desugar_jdk_libs 2.1.5) enabled in ALL four modules (`:ml-engine` stays Java 1.8, see `tech_stack`).
- Single activity (`MainActivity`, singleTop, all `configChanges` declared) — navigation is NOT fragments/intents: a-navigator `StatefulView` pages, routes in `:base` `Routes.java`, factories registered in `NavigatorProvider`.
- DI = a-provider. Composition root: `AppProviderModule` (registers Base/Rx/Settings/MLEngine modules; NavigatorProvider registered LAST intentionally).
- ML runs synchronously in the five editor pages (`FaceEditorPage`/`StyleEditorPage`/`PortraitEditorPage`/`MagicEraserEditorPage`/`QrCensorEditorPage`) plus `AiDetectPage` on the shared background executor with a progress-dialog busy-guard, and per-frame in `QrScannerPage` on its dedicated single-thread analyzer executor; WorkManager is only for on-demand model download (`ModelDownloadWorker`). Never run ML on main thread. Model gates (`ModelDownloadDialog` via HomeWorkflow; per-style in StyleEditorPage): FACE_DETECT (blur), NST, PERSON_SEG (portrait), EFFICIENT_SAM+LAMA (magic eraser), AI_IMAGE_DETECT (AI detect); both QR features need NO model (in-box OpenCV `QRCodeDetector`).
- WorkManager initialized on-demand (`Configuration.Provider` on app; default initializer removed in manifest).
- Long de-facto invariants: no unit tests (androidTest only, need emulator); no lint/format config.

## Where to go next
- Module details: `app/core` (pages/editors/navigation), `base/core` (provider modules, helpers), `ml-engine/core` (engines, models, model download), `settings/core`, `ml-engine/opencv` (OpenCV integration)
- Versions/libs: `tech_stack`; code style/patterns: `conventions`; commands: `suggested_commands`; done-checklist: `task_completion`
