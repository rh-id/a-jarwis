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
            "https://huggingface.co/onnxmodelzoo/pointilism-9/resolve/main/pointilism-9.onnx"),
    /**
     * PP-HumanSeg portrait segmentation model (OpenCV Zoo, Apache-2.0),
     * from https://huggingface.co/opencv/human_segmentation_pphumanseg.
     * The INT8 "bq" build is required: the larger float build is noticeably
     * slower on device for no visible gain, while other quantizations of this
     * checkpoint degrade heavily (mIoU tests are in the repo's document table)
     */
    PERSON_SEG("person-segmentation",
            "Portrait Segmentation (PP-HumanSeg)",
            "~1.7 MB",
            "https://huggingface.co/opencv/human_segmentation_pphumanseg/resolve/main/human_segmentation_pphumanseg_2023mar_int8bq.onnx"),
    /**
     * EfficientSAM promptable object segmentation model (OpenCV Zoo, Apache-2.0),
     * from https://huggingface.co/opencv/image_segmentation_efficientsam.
     * Both the fp32 and the int8 build are verified on device through OpenCV 5
     * DNN (int8 is functionally correct — the original all-zero observation
     * was the n-dim bulk blob access bug, fixed by reading every blob through
     * a reshaped 2-D view). The fp32 build ships: on the verification device
     * (x86_64 emulator) it is ~2.4x faster and more accurate than int8
     * (6.9 s vs 16.4 s per inference, IOU 0.99 vs 0.87), int8's only advantage
     * is the one-time download (20 vs 46 MB), and the best on-device
     * speed/quality trade-off wins (the PERSON_SEG precedent). The int8 URL
     * is recorded for re-evaluation on real ARM hardware:
     * https://huggingface.co/opencv/image_segmentation_efficientsam/resolve/main/image_segmentation_efficientsam_ti_2025april_int8.onnx
     */
    EFFICIENT_SAM("efficient-sam", "Object Mask (EfficientSAM)", "~46 MB",
            "https://huggingface.co/opencv/image_segmentation_efficientsam/resolve/main/image_segmentation_efficientsam_ti_2025april.onnx"),
    /**
     * LaMa image inpainting model (OpenCV Zoo, Apache-2.0),
     * from https://huggingface.co/opencv/inpainting_lama,
     * the reference requires OpenCV >= 5.0
     */
    LAMA("lama", "LaMa Inpainting", "~93 MB",
            "https://huggingface.co/opencv/inpainting_lama/resolve/main/inpainting_lama_2025jan.onnx");

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
