# Jarwis

![Languages](https://img.shields.io/github/languages/top/rh-id/a-jarwis)
![Downloads](https://img.shields.io/github/downloads/rh-id/a-jarwis/total)
![GitHub release (by tag)](https://img.shields.io/github/downloads/rh-id/a-jarwis/latest/total)
![Release](https://img.shields.io/github/v/release/rh-id/a-jarwis)
![Android CI](https://github.com/rh-id/a-jarwis/actions/workflows/gradlew-build.yml/badge.svg)
![Release Build](https://github.com/rh-id/a-jarwis/actions/workflows/android-release.yml/badge.svg)
![Emulator Test](https://github.com/rh-id/a-jarwis/actions/workflows/android-emulator-test.yml/badge.svg)

The power of AI in the palm of my hands!

This is AI utility app for Android device, it basically uses AI model directly on-device to help on performing some tasks.  

## Features
### Face Detection & Recognition
<ul>
    <li>Detect and add blur effect on people face and export the result to gallery</li>
    <li>Detect and select which face to be blurred and export the result to gallery</li>
</ul>

### Neural Style Transfer
<ul>
    <li>Select neural style transfer theme to apply to the image(s) and export the result to gallery.
        (Themes: Mosaic,Candy,Rain Princess,Udnie,Pointilism)
    </li>
</ul>

### Background Blur & Removal (Portrait Segmentation)
<ul>
    <li>Pick a photo and segment the human inside it with the on-device PP-HumanSeg model
        (downloaded on demand)</li>
    <li>Blur everything except the person for a bokeh/portrait effect</li>
    <li>Cut the person out with a fully transparent background</li>
    <li>Remove mode exports a PNG preserving the alpha channel; blur mode exports the usual JPEG</li>
</ul>

### On-Demand AI Model Download
<ul>
    <li>AI models are not bundled with the app; when a feature is used for the first time, the
        required models are downloaded once over the internet from their official Hugging Face
        repositories (<code>ModelDownloadDialog</code> enqueues <code>ModelDownloadWorker</code>,
        which runs <code>ModelDownloader</code>)</li>
    <li>Each model is verified against its known sha256 checksum before use, and the download
        progress is streamed live to the UI via <code>ModelChangeNotifier</code> (RxJava)</li>
    <li>An internet connection is only needed for the initial download; afterwards all features
        work fully offline</li>
</ul>

## Architecture

The project follows a modular, layered architecture designed to separate concerns between the UI, business logic, and heavy ML processing.

### Modules

*   **`:app`**: The entry point. Contains the UI, Navigation, and Command implementations. It manages user interaction and delegates tasks.
*   **`:ml-engine`**: The core intelligence. Encapsulates the logic engines (`FaceEngine`, `STEngine`), the AI model catalog with on-demand model download from Hugging Face, and background workers (`WorkManager`).
*   **`:base`**: Shared infrastructure. Contains DI setup (`a-provider` modules), utilities (`FileHelper`, `MediaHelper`), and common UI components.

### Key Libraries

*   **a-navigator**: Handles navigation between pages/activities.
*   **a-provider**: A lightweight Dependency Injection (DI) framework.
*   **StatefulView**: A framework for building state-aware UI components.
*   **RxAndroid (RxJava 3)**: Manages asynchronous events and threading in the UI layer.
*   **WorkManager**: Handles deferrable, guaranteed background work (ML processing).
*   **OpenCV**: Powers the face detection and recognition capabilities.

## Logic Flow & Patterns

The application heavily utilizes the **Command Pattern** to decouple the UI from the execution logic.

### High-Level Workflow

1.  **User Interaction**: The user triggers an action (e.g., "Auto Blur Face") in the UI (`HomePage`).
2.  **Command Execution**: The UI invokes a specific **Command** (e.g., `BlurFaceCommand`).
3.  **Engine Delegation**: The Command prepares the data and delegates the task to a specific **Engine** (e.g., `FaceEngine`).
4.  **Background Work**: The Engine serializes the request and enqueues it to the **WorkManager**.
5.  **Processing**: The background **Worker** (`BlurFaceWorkRequest`) wakes up, deserializes the data, and runs the heavy ML algorithms.
6.  **Result**: The processed image is saved to the gallery, and the user is notified.

### Diagram

```mermaid
sequenceDiagram
    participant UI as HomePage (UI)
    participant Cmd as BlurFaceCommand
    participant Eng as FaceEngine
    participant WM as WorkManager
    participant Wkr as BlurFaceWorkRequest
    participant ML as OpenCV/ML Model

    UI->>Cmd: execute(imageUri)
    Cmd->>Eng: enqueueBlurFace(file, params)
    Eng->>WM: enqueue(OneTimeWorkRequest)
    Note over WM: Background Processing
    WM->>Wkr: doWork()
    Wkr->>Eng: blurFace(bitmap)
    Eng->>ML: detect/recognize/blur
    ML-->>Eng: Result Bitmap
    Eng-->>Wkr: Result
    Wkr->>UI: Save to Gallery / Notify
```

### Deep Dive: Face Detection Flow

1.  **HomePage**: Calls `mBlurFaceCommand.execute(uri)`.
2.  **BlurFaceCommand**: Copies the image to a temp file and calls `mFaceEngine.enqueueBlurFace(...)`.
3.  **FaceEngine**:
    *   Creates a `BlurFaceSerialFile` object containing file paths and configuration.
    *   Serializes this object to disk.
    *   Creates a `OneTimeWorkRequest` pointing to `BlurFaceWorkRequest`, passing the serialized file path as input data.
    *   Enqueues the request to `WorkManager`.
4.  **BlurFaceWorkRequest** (Background):
    *   Reads the serialized file.
    *   Loads the image bitmaps.
    *   Calls `faceEngine.blurFace()`.
    *   `FaceEngine` uses `OpenCV`'s `FaceDetectorYN` to find faces and `FaceRecognizerSF` to match identities (if excluding faces).
    *   Applies a blur effect to the detected regions.
    *   Saves the final image using `MediaHelper`.

### Deep Dive: Neural Style Transfer (NST) Flow

1.  **HomePage**: Pushes the picked image(s) to `StyleEditorPage`.
2.  **StyleEditorPage**: Interactive editor showing each picked image with a row of style tiles (Mosaic, Candy, Rain Princess, Udnie, Pointilism). Tapping a style renders a live stylized preview of the image; missing style models are prompted for download on demand.
3.  **STEngine**: Applies the selected theme by delegating to the cached per-theme `STProcessor` (OpenCV DNN) on a downscaled preview while editing, and at full resolution on save/share.
4.  **Save/Share**: Saves each stylized image to the gallery via `MediaHelper` or writes a temp JPEG for the Android share sheet.

### Deep Dive: Portrait Segmentation Flow

1.  **HomePage**: Checks `PERSON_SEG` model availability first (`HomeWorkflow` triggers the
    `ModelDownloadDialog` download on first use), then pushes the picked images to `PortraitEditorPage`.
2.  **PortraitEditorPage**: Interactive editor showing each picked image with a Blur/Remove mode
    toggle plus a strength slider (blur mode). Mask inference runs once per image; the result is
    engine-cached so mode/strength changes re-composite without re-running the model.
3.  **PersonSegEngine/PersonSegProcessor**: The PP-HumanSeg ONNX runs through OpenCV DNN
    (192x192 input); a soft person mask is derived from the two output channels, feathered at
    model resolution, then bilinear-upsampled so edges stay clean without haloing.
4.  **Effects**: Blur mode composites a Gaussian-blurred copy of the full image behind the person.
    Remove mode turns the mask into the alpha channel, exporting a **PNG** whose transparency
    survives the round trip.
5.  **Save/Share**: Saves each image to the gallery via `MediaHelper` (PNG in remove mode,
    JPEG in blur mode) or writes a temp file for the Android share sheet with the matching mime.

## Screenshots
<img src="https://github.com/rh-id/a-jarwis/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" height="512"/>
<img src="https://github.com/rh-id/a-jarwis/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" height="512"/>

## Support this project
Consider donation to support this project
<table>
  <tr>
    <td><a href="https://teer.id/rh-id">https://teer.id/rh-id</a></td>
  </tr>
</table>
