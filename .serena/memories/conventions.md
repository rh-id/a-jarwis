# Conventions

## Naming / style
- Instance fields prefixed `m` (mLogger, mProvider, mFaceEngine); `private static final String TAG = "<ClassName>"` in every class that logs
- Private constructor + static util methods for helpers; `Routes`-style constant holders use private ctor
- Sparse javadoc, English; no comments unless non-obvious

## DI (a-provider) — strict patterns
- Services take `Provider` in constructor and pull deps from it; never pass deps manually
- Registration verbs: `registerLazy` (cheap singletons: commands, engines), `registerAsync` (heavy init: MLEngineInstance, ILogger, WorkManager, RxDisposer), `registerPool` (per-instance: IStatefulViewProvider→StatefulViewProvider), `ProviderValue`/`provider.lazyGet` for deferred heavy deps
- Every module has its own `ProviderModule` (`BaseProviderModule`, `MLEngineProviderModule`, ...); new service ⇒ register in the owning module; composition root is `:app` `AppProviderModule`

## UI (a-navigator StatefulView)
- Pages extend `StatefulView<Activity>`; implement `RequireComponent<Provider>` and pull deps in `provideComponent()` via `IStatefulViewProvider`
- `@NavInject` fields (transient) for INavigator and child SVs; view fields transient; only serializable *state* fields survive recreation
- `createView()` inflates layout from module R; shared app bar = `AppBarSV`
- Navigation: add route constant to `:base` `Routes`, register factory in `NavigatorProvider` navMap; pass data via Serializable param classes in `app/ui/page/nav/param`
- Single activity handles ALL configChanges — never add setContentView or fragment transactions

## Async
- RxJava3: `Single.fromFuture(mExecutorService.submit(...))` wrapping executor work (see BlurFaceCommand); dispose via `RxDisposer`
- App-wide threads from Provider: `ExecutorService` (WeightedThreadPool, maxWeight 5), `ScheduledExecutorService`; never create raw threads/executors ad hoc
- Bitmaps: `recycle()` when done; cropping via `BitmapUtils`

## Background work (WorkManager) — Command→Engine→Worker
- Engine serializes request object (`*SerialFile`, java serialization) to temp file, puts serialized temp-file path byte[] into `Data` keyed by `Params.SERIAL_FILE`, enqueues `OneTimeWorkRequest`
- Worker: gets Provider via `BaseApplication.of(getApplicationContext()).getProvider()`, deserializes, runs engine, saves via `MediaHelper.insertImage`, deletes temp files in `finally`, `Result.failure()` on exception, user-facing progress via ILogger

## Logging
- Always `ILogger` from Provider (composite: Logcat + file + toast); string resources from `m.co.rh.id.a_jarwis.base.R.string` for user-visible text

## ML models
- Raw resource → copy once to `filesDir` path built from `MLEngineInstance` constants; access models only through `MLEngineInstance`
