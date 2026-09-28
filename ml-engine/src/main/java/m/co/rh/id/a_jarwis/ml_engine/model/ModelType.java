package m.co.rh.id.a_jarwis.ml_engine.model;

/**
 * Catalog of the AI models used by this engine.
 * All models are downloaded on demand, they are not bundled inside the APK.
 */
public enum ModelType {
    /**
     * YuNet face detection model (OpenCV Zoo, Apache-2.0),
     * from https://huggingface.co/opencv/face_detection_yunet
     */
    FACE_DETECT("face-detect",
            "Face Detection (YuNet)",
            "~0.2 MB",
            "https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx"),
    /**
     * SFace face recognition model (OpenCV Zoo, Apache-2.0),
     * from https://huggingface.co/opencv/face_recognition_sface
     */
    FACE_RECOGNIZER("face-recognizer",
            "Face Recognition (SFace)",
            "~9.4 MB",
            "https://huggingface.co/opencv/face_recognition_sface/resolve/main/face_recognition_sface_2021dec_int8.onnx"),
    /**
     * Mosaic fast neural style model (ONNX Model Zoo, Apache-2.0),
     * from https://huggingface.co/onnxmodelzoo/mosaic-9,
     * legacy on-device file name (before on-demand download): nst_mosaic_9.onnx
     */
    NST_MOSAIC("nst-mosaic",
            "Neural Style: Mosaic",
            "~6.4 MB",
            "https://huggingface.co/onnxmodelzoo/mosaic-9/resolve/main/mosaic-9.onnx"),
    /**
     * Candy fast neural style model (ONNX Model Zoo, Apache-2.0),
     * from https://huggingface.co/onnxmodelzoo/candy-9,
     * legacy on-device file name (before on-demand download): nst_candy_9.onnx
     */
    NST_CANDY("nst-candy",
            "Neural Style: Candy",
            "~6.4 MB",
            "https://huggingface.co/onnxmodelzoo/candy-9/resolve/main/candy-9.onnx"),
    /**
     * Rain Princess fast neural style model (ONNX Model Zoo, Apache-2.0),
     * from https://huggingface.co/onnxmodelzoo/rain-princess-9,
     * legacy on-device file name (before on-demand download): nst_rain_princess_9.onnx
     */
    NST_RAIN_PRINCESS("nst-rain-princess",
            "Neural Style: Rain Princess",
            "~6.4 MB",
            "https://huggingface.co/onnxmodelzoo/rain-princess-9/resolve/main/rain-princess-9.onnx"),
    /**
     * Udnie fast neural style model (ONNX Model Zoo, Apache-2.0),
     * from https://huggingface.co/onnxmodelzoo/udnie-9,
     * legacy on-device file name (before on-demand download): nst_udnie_9.onnx
     */
    NST_UDNIE("nst-udnie",
            "Neural Style: Udnie",
            "~6.4 MB",
            "https://huggingface.co/onnxmodelzoo/udnie-9/resolve/main/udnie-9.onnx"),
    /**
     * Pointilism fast neural style model (ONNX Model Zoo, Apache-2.0),
     * from https://huggingface.co/onnxmodelzoo/pointilism-9,
     * legacy on-device file name (before on-demand download): nst_pointilism_9.onnx
     */
    NST_POINTILISM("nst-pointilism",
            "Neural Style: Pointilism",
            "~6.4 MB",
            "https://huggingface.co/onnxmodelzoo/pointilism-9/resolve/main/pointilism-9.onnx");

    private final String mId;
    private final String mDisplayName;
    private final String mDisplaySize;
    private final String mDownloadUrl;

    ModelType(String id, String displayName, String displaySize, String downloadUrl) {
        mId = id;
        mDisplayName = displayName;
        mDisplaySize = displaySize;
        mDownloadUrl = downloadUrl;
    }

    /**
     * Stable unique id, used e.g. as WorkManager unique work name suffix
     */
    public String getId() {
        return mId;
    }

    /**
     * Human readable name to be displayed on UI
     */
    public String getDisplayName() {
        return mDisplayName;
    }

    /**
     * Approximate file size, display only
     */
    public String getDisplaySize() {
        return mDisplaySize;
    }

    public String getDownloadUrl() {
        return mDownloadUrl;
    }

    /**
     * @return ModelType matching given id, or null when unknown
     */
    public static ModelType fromId(String id) {
        if (id != null) {
            for (ModelType modelType : values()) {
                if (modelType.mId.equals(id)) {
                    return modelType;
                }
            }
        }
        return null;
    }
}
