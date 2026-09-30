# Jarwis — Project Core

On-device Android AI utility app (GitHub `rh-id/a-jarwis`, branch `master`). Features: face detection/blur (optional exclusion by identity), neural style transfer (5 themes). Package root `m.co.rh.id.a_jarwis`.

## Module graph (dependency direction)
- `:app` → `:base`, `:settings`, `:ml-engine` (UI entry, interactive editor pages)
- `:ml-engine` → `:base` (engines + WorkManager workers; OpenCV comes from the official Maven AAR, not a module)
- `:settings` → `:base` (settings pages)
- `:base` → nothing in-repo (shared infra; exposes a-provider/a-navigator/etc via `api`)

## Non-obvious invariants
- Pure Java (no Kotlin sources). minSdk 21, compile/targetSdk 34, JDK 17.
- Single activity (`MainActivity`, singleTop, all `configChanges` declared) — navigation is NOT fragments/intents: a-navigator `StatefulView` pages, routes in `:base` `Routes.java`, factories registered in `NavigatorProvider`.
- DI = a-provider. Composition root: `AppProviderModule` (registers Base/Rx/Settings/MLEngine modules; NavigatorProvider registered LAST intentionally).
- ML runs synchronously in interactive editor pages (`FaceEditorPage`/`StyleEditorPage`) on the shared background executor with a progress-dialog busy-guard; WorkManager is only for on-demand model download (`ModelDownloadWorker`). Never run ML on main thread.
- WorkManager initialized on-demand (`Configuration.Provider` on app; default initializer removed in manifest).
- Long de-facto invariants: no unit tests (androidTest only, need emulator); no lint/format config.

## Where to go next
- Module details: `app/core` (pages/editors/navigation), `base/core` (provider modules, helpers), `ml-engine/core` (engines, models, model download), `settings/core`, `ml-engine/opencv` (OpenCV integration)
- Versions/libs: `tech_stack`; code style/patterns: `conventions`; commands: `suggested_commands`; done-checklist: `task_completion`
