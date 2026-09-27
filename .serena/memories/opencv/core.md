# :opencv Module Core

Vendored OpenCV 4.8.0 Android SDK (upstream drop, Apache 2.0 headers). Namespace `org.opencv`.

- Treat as an immutable library drop: do NOT hand-edit `java/src` (upstream API) — app code consumes it from `:ml-engine` only
- Native build: cmake (`libcxx_helper/CMakeLists.txt`), target `opencv_jni_shared`, `-DANDROID_STL=c++_shared`, NDK 25.2.9519653, prebuilt jniLibs in `native/libs`
- Lint fully disabled here (slow/noisy); gradle file carries upstream integration notes (top of opencv/build.gradle)
- Upgrade path: replace module contents with newer OpenCV SDK `sdk/` folder and adjust versions; check `FaceDetectorYN`/`FaceRecognizerSF` API compatibility in `MLEngineInstance`/`FaceEngine`
