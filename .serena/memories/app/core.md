# :app Module Core

Entry module, namespace `m.co.rh.id.a_jarwis`. See `core` for module graph.

## App skeleton
- `MainActivity`: only activity, singleTop, all configChanges declared (state via a-navigator)
- `MainApplication`: creates Provider from `AppProviderModule`, global crash handler (log via ILogger → dispose provider → chain default handler), WorkManager `Configuration.Provider` using provider executors
- `AppProviderModule` = composition root; registers Base/Rx/Settings/MLEngine modules (no Command modules anymore), `IStatefulViewProvider` pool, NavigatorProvider LAST (order matters)

## Navigation
- `NavigatorProvider`: builds `Navigator` for MainActivity; navMap Routes→StatefulViewFactory; NavExtDialogConfig merged; loading view = page_splash layout
- To add a page: constant in `:base` `Routes` + factory here + page class in `ui/page`

## Pages (`ui/page`)
- `SplashPage` (start route) → `HomePage` (route `/`, drawer menu: settings, donations; 2 feature buttons: Blur Face, Apply Neural Style Transfer)
- `HomeWorkflow` (helper held by HomePage): WRITE_EXTERNAL_STORAGE request (≤ S_V2), blur flow checks FACE_DETECT model availability first, then ACTION_GET_CONTENT multi-pick → pushes an editor with `UriList`
- `editor/FaceEditorPage` (route `/editor/faceEditor`): interactive face-blur editor — FaceEngine.detectFace on a downscaled preview, tap a face to toggle its blur, per-image `BlurConfig` (ellipse/square + strength 1..5), full-res composite on save/share
- `editor/StyleEditorPage` (route `/editor/styleEditor`): interactive NST editor — per-image style tiles (0=original, `STEngine.THEME_*` 1..5), live preview via `STEngine.apply`, apply-to-all (disabled while original selected), on-demand model download per style (checks `ModelCatalog.isAvailable`, pushes ModelDownloadDialog, re-renders on POSITIVE)
- `common/`: only `ModelDownloadDialog` (pushed dialog for on-demand model download with progress; pops `SelectedChoice.POSITIVE` when done) — used by both editors + HomeWorkflow
- params in `ui/page/nav/param`: `UriList`, `SelectedChoice` (Serializable; uris stored as strings)

## Editor page architecture (shared by both editors)
- `StatefulView` + `RequireComponent<Provider>` + `NavOnBackPressed`; deps pulled in `provideComponent()` via `IStatefulViewProvider`
- Single non-transient `final SerialBehaviorSubject<EditorState>` holding ONLY-Serializable state (String uris, ints, Booleans); views/bitmaps stay transient, never in the navigator snapshot
- Background work via `mRxDisposer.add("<unique key>", ...)` + `Schedulers.from(mExecutorService)`, observed on main thread with stale-guard re-check
- Copy-on-pick: content uris → temp files (prefix `editor_source_` / `st_editor_source_`, 1h age purge on createView, deleted on dispose)
- Modal progress dialog = busy-guard (`isWorking()`); save via `MediaHelper.insertImage`, share via `ImageHelper.createImageTempFile(String,Bitmap)` + `UiUtils.shareFile`

No tests in :app.
