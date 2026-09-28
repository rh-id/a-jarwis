# :base Module Core

Shared infra; exposes common libs to all modules via `api` (a-provider, a-navigator(+dialog), a-logger, rx-utils, rxandroid/rxjava, work-runtime, appcompat/material/constraintlayout/drawerlayout/swiperefreshlayout/recyclerview/exifinterface, room-runtime). Room compiler runs here (schemaLocation set) though no in-repo entities.

## Key classes
- `BaseApplication`: `of(Context)` accessor, abstract `getProvider()`/`getNavigator(Activity)`, default WorkManager config
- `BaseProviderModule`: ExecutorService = WeightedThreadPool (maxWeight 5, from concurrent-utils), ScheduledExecutorService (cores), main Handler, ILogger = CompositeLogger(Android + File(cache/alogger/app.log) + Toast; VERBOSE in debug), WorkManager, NavExtDialogConfig, FileHelper, MediaHelper(lazy); `dispose()` shuts executors with 1.5s grace
- `FileHelper`: temp files under `cache/tmp/<uuid>/`, `createTempFile(name, uri)` copies content, `copyFile(src, dst)`, `atomicMove(src, dst)` (rename with copy fallback — used by ModelDownloader to finalize downloaded models), log file accessor
- `MediaHelper`: saves Bitmap to device gallery (MediaStore) — workers use this for output
- `SerializeUtils`: java-serialization ↔ byte[] (used for WorkManager payloads)
- `RxDisposer`: dispose subscriptions for SVs
- `ui/component/AppBarSV`: shared app bar SV
- `util/`: BitmapUtils (crop), UiUtils; `constants/`: Routes (route strings, HOME_PAGE="/"), Constants
- `IStatefulViewProvider` + `RxProviderModule`

Dependencies added here with `api` propagate to every module — put shared deps here deliberately.
