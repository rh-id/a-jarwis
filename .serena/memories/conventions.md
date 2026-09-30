# Conventions

## Naming / style
- Instance fields prefixed `m` (mLogger, mProvider, mFaceEngine); `private static final String TAG = "<ClassName>"` in every class that logs
- Private constructor + static util methods for helpers; `Routes`-style constant holders use private ctor
- Sparse javadoc, English; no comments unless non-obvious

## DI (a-provider) — strict patterns
- Services take `Provider` in constructor and pull deps from it; never pass deps manually
- Registration verbs: `registerLazy` (cheap singletons: engines, helpers), `registerAsync` (heavy init: MLEngineInstance, ILogger, WorkManager, RxDisposer), `registerPool` (per-instance: IStatefulViewProvider→StatefulViewProvider), `ProviderValue`/`provider.lazyGet` for deferred heavy deps
- Every module has its own `ProviderModule` (`BaseProviderModule`, `MLEngineProviderModule`, ...); new service ⇒ register in the owning module; composition root is `:app` `AppProviderModule`

## UI (a-navigator StatefulView)
- Pages extend `StatefulView<Activity>`; implement `RequireComponent<Provider>` and pull deps in `provideComponent()` via `IStatefulViewProvider`
- `@NavInject` fields (transient) for INavigator and child SVs; view fields transient; only serializable *state* fields survive recreation (editor pages: one `SerialBehaviorSubject<EditorState>` of only-Serializable fields)
- `createView()` inflates layout from module R; shared app bar = `AppBarSV`
- Navigation: add route constant to `:base` `Routes`, register factory in `NavigatorProvider` navMap; pass data via Serializable param classes in `app/ui/page/nav/param` (`UriList`, `SelectedChoice`)
- Single activity handles ALL configChanges — never add setContentView or fragment transactions

## Async
- RxJava3: page-scoped `mRxDisposer.add("<unique key>", ...)` with `Schedulers.from(mExecutorService)` for executor work (see FaceEditorPage/StyleEditorPage); dispose via `RxDisposer`
- App-wide threads from Provider: `ExecutorService` (WeightedThreadPool, maxWeight 5), `ScheduledExecutorService`; never create raw threads/executors ad hoc
- Bitmaps: `recycle()` when done; cropping via `ImageHelper.cropBitmap`

## Background work (WorkManager) — model download only
- WorkManager is ONLY `ModelDownloadWorker`: unique-per-model (`ExistingWorkPolicy.KEEP`), network-constrained, retry/backoff; UI observes via `ModelDownloadDialog` + `ModelChangeNotifier` hot flows
- Interactive ML features run synchronously in the editor pages (FaceEditorPage/StyleEditorPage) on `Schedulers.from(mExecutorService)` with a modal progress dialog as busy-guard — no background workers, no command layer

## Logging
- Always `ILogger` from Provider (composite: Logcat + file + toast); string resources from `m.co.rh.id.a_jarwis.base.R.string` for user-visible text

## ML models
- Downloaded on demand into `filesDir/ml-engine/engine/**` (`ModelType`/`ModelCatalog`/`ModelDownloader` via `ModelDownloadWorker`); never bundled in the APK; legacy `nst_*_9.onnx` files are renamed in place by `ModelCatalog.migrateLegacyIfNeeded`; access models only through `MLEngineInstance`
