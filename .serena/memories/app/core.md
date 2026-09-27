# :app Module Core

Entry module, namespace `m.co.rh.id.a_jarwis`. See `core` for module graph.

## App skeleton
- `MainActivity`: only activity, singleTop, all configChanges declared (state via a-navigator)
- `MainApplication`: creates Provider from `AppProviderModule`, global crash handler (log via ILogger → dispose provider → chain default handler), WorkManager `Configuration.Provider` using provider executors
- `AppProviderModule` = composition root; registers Base/Command/Rx/Settings/MLEngine modules, `IStatefulViewProvider` pool, NavigatorProvider LAST (order matters)

## Navigation
- `NavigatorProvider`: builds `Navigator` for MainActivity; navMap Routes→StatefulViewFactory; NavExtDialogConfig merged; loading view = page_splash layout
- To add a page: constant in `:base` `Routes` + factory here + page class in `ui/page`

## Commands (ui-facing API, in `provider/command`)
- `BlurFaceCommand` (auto/exclude/selective blur): copies uri→temp file (FileHelper), `Single.fromFuture` on ExecutorService, delegates `FaceEngine.enqueueBlurFace`
- `STApplyCommand`: same shape for neural style transfer via `STEngine`

## Pages (`ui/page`)
- `SplashPage` (start route) → `HomePage` (route `/`, drawer menu: settings, donations, blur/NST buttons)
- `common/`: SelectFaceImagePage, SelectSTThemePage, ShowMessagePage; params in `ui/page/nav/param` (FileList, MessageText, SelectedChoice, SelectedTheme — Serializable)
- Gallery grid: `ui/imageitem` (ImageListAdapter + ImageItemSV + ImageItem model)

No tests in :app.
