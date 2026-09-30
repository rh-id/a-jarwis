# OpenCV Integration (in :ml-engine)

OpenCV 5.0.0.1 via the official Maven Central AAR `org.opencv:opencv:5.0.0.1` (published by the OpenCV team). Declared in `gradle/libs.versions.toml` (`opencv` version + library), consumed in `ml-engine/build.gradle` as `implementation libs.opencv`. No local module — the vendored OpenCV 4.8.0 SDK (`:opencv`, ~196MB) was removed 2026-09.

- Java API unchanged for everything this app uses: `OpenCVLoader.initLocal()`, `android.Utils`, `core.Mat/Size/CvType`, `imgproc.Imgproc`, `objdetect.FaceDetectorYN`/`FaceRecognizerSF`, `dnn.Dnn.readNetFromONNX/blobFromImage/imagesFromBlob`
- JNI lib renamed `opencv_java4` → `opencv_java5` in 5.x, but `OpenCVLoader.initLocal()` handles loading internally (app never calls `System.loadLibrary` directly)
- OpenCV 5 DNN has a new default engine with automatic fallback to classic; if a model ever fails to load/infer, force classic via `OPENCV_FORCE_DNN_ENGINE=1` or `Dnn.readNetFromONNX(path, ENGINE_CLASSIC)`
- Upgrade path: bump the `opencv` version in `libs.versions.toml`. Watch the official 4→5 migration guide for future 5.x changes (module splits: calib3d→geometry/calib/stereo, features2d→features, ML/G-API/Haar/HOG→contrib — none affect current usage)
- AAR ships all 4 ABIs; trim via `abiFilters` in `:app` if APK size matters
