# :ml-engine Module Core

All ML logic. Java 1.8 source/target (differs from other modules — intentional). Models are NOT bundled in the APK — they are DOWNLOADED on demand into `filesDir/ml-engine/engine/**`.

## Components
- `MLEngineInstance` (`registerAsync` — heavy): `OpenCVLoader.initLocal()` (NOT initDebug); path constants under `filesDir/ml-engine/engine/**` (face/detect, face/recognizer, neural-style-transfer); `getSTProcessor(theme)` caches one STProcessor per theme (`ConcurrentHashMap.computeIfAbsent`, ~32MB native for all 5, app lifetime); model getters throw when the model file is missing
- `model/ModelType` enum: FACE_DETECT, FACE_RECOGNIZER, NST_MOSAIC/CANDY/RAIN_PRINCESS/UDNIE/POINTILISM — id/displayName/displaySize/downloadUrl (HuggingFace)
- `model/ModelCatalog` (static): `isAvailable` (also runs legacy `nst_*_9.onnx` filename migration), `getModelFile`, `fromTheme`
- `model/BlurConfig`: Serializable blur shape (ellipse/square) + strength 1..5, normalized after deserialization
- `ModelDownloader`: pure java.net HTTP + SHA-256 (HF ETag) + resumable (Range header); ALL download events broadcast via `ModelChangeNotifier`
- `FaceEngine`: `detectFace`, `searchFace` (1 face vs N); legacy `blurFace`/`isFaceSimilar` (SFace cosine ≥ 0.363) retained for future reference-image flows. For the editor: `blurFacesAt(Bitmap, List<Rect>, BlurConfig)` (full-res save path) + `blurFaceCrop(Bitmap, scale, BlurConfig)` (preview path) — kernel size scales with face min dimension (strength factors 0.35..1.00, clamped [3,201], forced odd, sigma = kernel*0.5), feathered ellipse/rounded-square masks via BlurMaskFilter + PorterDuff DST_IN
- `STEngine` + `STProcessor`: NST via OpenCV DNN. Synchronous `apply(Bitmap, theme)` + per-theme applyMosaic/applyCandy/applyRainPrincess/applyUdnie/applyPointilism (used by STEngineTest); `enqueueST` DELETED. `STProcessor.process()` must NEVER run concurrently on one instance — callers serialize via page busy-guards; inference always at 540px longest edge, upscaled back

## Workers (`workmanager`)
- `ModelDownloadWorker` — the ONLY WorkManager worker left: unique work per model (`ExistingWorkPolicy.KEEP`), network-connected constraint, exponential backoff; input `Params.MODEL_TYPE` (model id string) + failure output `Params.ERROR_MESSAGE`; get deps via `BaseApplication.of(ctx).getProvider()`
- `ModelChangeNotifier` (`provider/notifier/`): hot progress/succeeded/failed flows — consumers must be subscribed at emit time

## Registration
`MLEngineProviderModule`: MLEngineInstance async; FaceEngine/STEngine/ModelDownloader/ModelChangeNotifier lazy.

## Adding an ML feature (canonical recipe)
1. model file: add a `ModelType` entry (id/displayName/displaySize/downloadUrl → downloaded via ModelDownloadWorker) OR bundle + register path constants in MLEngineInstance
2. engine method in FaceEngine/STEngine or a new engine; gate on `ModelCatalog.isAvailable` and push `ModelDownloadDialog` (`:app`) for on-demand download
3. register in MLEngineProviderModule; androidTest uses `MLEngineTestProviderModule` (+ `ModelTestHelper.installModels` — blocking download, needs network)

Tests (androidTest only, need emulator + network): `FaceEngineTest`, `STEngineTest` (work-testing dep).
