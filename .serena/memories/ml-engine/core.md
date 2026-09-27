# :ml-engine Module Core

All ML logic. Java 1.8 source/target (differs from other modules — intentional). `mlModelBinding = true` in gradle though models are plain ONNX in `res/raw` (no TFLite).

## Components (`provider/component`)
- `MLEngineInstance` (`registerAsync` — heavy): loads OpenCV (`OpenCVLoader.initDebug()`), copies ONNX from res/raw to `filesDir/ml-engine/engine/**` once; path & filename constants live here; getters create processors per call (NST) or FaceDetectorYN/FaceRecognizerSF
- `FaceEngine`: `detectFace`, `searchFace` (1 face vs N), `blurFace` (GaussianBlur k=201 σ=100 per face crop; excludes/includes identities via SFace cosine similarity ≥ 0.363); `enqueueBlurFace` = serialize `BlurFaceSerialFile` → WorkManager
- `STEngine` + `STProcessor`: NST via OpenCV DNN on ONNX models
- SimilarityScore model class

## Workers (`workmanager`)
- `BlurFaceWorkRequest`, `STApplyWorkRequest` extend `Worker`; input = serialized `*SerialFile` byte[] under `Params.SERIAL_FILE`; get deps via `BaseApplication.of(ctx).getProvider()`; save via MediaHelper.insertImage; temp cleanup in finally; Result.failure on exception
- Serial payload models: `BlurFaceSerialFile`, `STApplySerialFile`

## Registration
`MLEngineProviderModule`: MLEngineInstance async, FaceEngine/STEngine lazy.

## Adding an ML feature (canonical recipe)
1. model.onnx → `res/raw` + file constant + loader in MLEngineInstance
2. engine method (+ enqueue* serialization) in Face/STEngine or new engine
3. SerialFile model + Worker + Params key
4. register in MLEngineProviderModule; androidTest uses `MLEngineTestProviderModule`

Tests (androidTest only, need emulator): `FaceEngineTest`, `STEngineTest` (work-testing dep).
