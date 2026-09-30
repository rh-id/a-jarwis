# :base Module Core

Shared infra; exposes common libs to all modules via `api` (a-provider, a-navigator(+dialog), a-logger, rx-utils, rxandroid/rxjava, work-runtime, appcompat/material/constraintlayout/drawerlayout/swiperefreshlayout/recyclerview/exifinterface, room-runtime). Room compiler runs here (schemaLocation set) though no in-repo entities.

## Key classes
- `BaseApplication`: `of(Context)` accessor, abstract `getProvider()`/`getNavigator(Activity)`, default WorkManager config
- `BaseProviderModule`: ExecutorService = WeightedThreadPool (maxWeight 5, from concurrent-utils), ScheduledExecutorService (cores), main Handler, ILogger = CompositeLogger(Android + File(cache/alogger/app.log) + Toast; VERBOSE in debug), WorkManager, NavExtDialogConfig, FileHelper, ImageHelper, MediaHelper(lazy); `dispose()` shuts executors with 1.5s grace
- `FileHelper`: pure file ops — temp files under `cache/tmp/<uuid>/`, `createTempFile` overloads (`(name, uri)` = raw byte copy of content), `copyFile(src, dst)`, `atomicMove(src, dst)` (rename with copy fallback — used by ModelDownloader to finalize downloaded models), `deleteTempFiles(prefix[, maxAgeMillis])` (best-effort, age-cutoff), `createImageTempFile(name)`, log file accessor
- `ImageHelper`: decode helpers (`decodeFullBitmap` OOM-capped to 4096 longest side, `decodeDownscaledBitmap(uri, maxDim)`, `decodeImageDimension` — all EXIF-rotation aware), `cropBitmap`, temp-file creators (JPEG), `copyImage` (try-with-resources)
- `MediaHelper`: `insertImage(Bitmap, title, desc)` saves to device gallery (MediaStore) — editor pages use this for output; thumbnail failure isolated
- `SerializeUtils`: java-serialization ↔ byte[] (kept for tests/serialization; no longer used by workers)
- `RxDisposer`: dispose subscriptions for SVs
- `ui/component/AppBarSV`: shared app bar SV
- `util/`: UiUtils (shareFile via FileProvider), SerializeUtils; `constants/`: Routes (route strings, HOME_PAGE="/", editor routes under `/editor/**`), Constants
- `IStatefulViewProvider` + `RxProviderModule`

Dependencies added here with `api` propagate to every module — put shared deps here deliberately.
