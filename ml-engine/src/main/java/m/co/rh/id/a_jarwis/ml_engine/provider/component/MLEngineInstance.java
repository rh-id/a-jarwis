package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;

import androidx.annotation.NonNull;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderDisposable;

public class MLEngineInstance implements ProviderDisposable {
    private static final String TAG = "MLEngineInstance";

    private static final String BASE_PATH = "/ml-engine/engine";
    private static final String FACE_PATH = BASE_PATH + "/face";
    private static final String FACE_DETECT_PATH = FACE_PATH + "/detect";
    private static final String FACE_DETECT_FILE = "face_detection_yunet_2023mar.onnx";
    private static final int FACE_DETECT_WIDTH = 640;
    private static final int FACE_DETECT_HEIGHT = 640;
    private static final String FACE_RECOGNIZER_PATH = FACE_PATH + "/recognizer";
    private static final String FACE_RECOGNIZER_FILE = "face_recognition_sface_2021dec_int8.onnx";
    private static final String NEURAL_STYLE_TRANSFER_PATH = BASE_PATH + "/neural-style-transfer";
    private static final String NST_MOSAIC_FILE = "mosaic-9.onnx";
    private static final String NST_CANDY_FILE = "candy-9.onnx";
    private static final String NST_RAIN_PRINCESS_FILE = "rain-princess-9.onnx";
    private static final String NST_UDNIE_FILE = "udnie-9.onnx";
    private static final String NST_POINTILISM_FILE = "pointilism-9.onnx";
    private static final String PERSON_SEGMENTATION_PATH = BASE_PATH + "/person-segmentation";
    private static final String PERSON_SEGMENTATION_FILE = "human_segmentation_pphumanseg_2023mar_int8bq.onnx";
    private static final String OBJECT_SEGMENTATION_PATH = BASE_PATH + "/object-segmentation";
    private static final String EFFICIENT_SAM_FILE = "image_segmentation_efficientsam_ti_2025april.onnx";
    private static final String INPAINTING_PATH = BASE_PATH + "/inpainting";
    private static final String LAMA_FILE = "inpainting_lama_2025jan.onnx";
    private static final String AI_IMAGE_DETECT_PATH = BASE_PATH + "/ai-image-detect";
    private static final String AI_IMAGE_DETECT_FILE = "community_forensics_deepfakedet_vit_fp32_2026july.onnx";
    private static final String NAFNET_PATH = BASE_PATH + "/restore";
    private static final String NAFNET_FILE = "deblurring_nafnet_2025may.onnx";
    private static final String SWIN2SR_PATH = BASE_PATH + "/upscale";
    private static final String SWIN2SR_FILE = "swin2sr-realworld-sr-x4-64.onnx";
    private final Context mAppContext;
    private final ILogger mLogger;
    /**
     * Set by {@link #dispose(Context)}: the provider dispose cascade invokes
     * it twice (synchronously through the module dispose, asynchronously
     * through this instance's registry entry), the flag makes the second
     * invocation a no-op
     */
    private boolean mDisposed;
    /**
     * Lazy neural style transfer processor cache (keyed by model type),
     * an instance holds its ONNX model loaded in the native memory
     * (~32 MB worst case) for the app lifetime because the models are
     * never deleted once downloaded
     */
    private final ConcurrentHashMap<ModelType, STProcessor> mSTProcessors;
    /**
     * Lazy portrait segmentation processor cache (keyed by model type for
     * future person segmentation variants), an instance holds its ONNX model
     * loaded in the native memory (the file is ~1.7 MB) for the app lifetime
     * because the model is never deleted once downloaded
     */
    private final ConcurrentHashMap<ModelType, PersonSegProcessor> mPersonSegProcessors;
    /**
     * Lazy object segmentation processor cache (keyed by model type for
     * future EfficientSAM variants), an instance holds its ONNX model
     * loaded in the native memory (the fp32 file is ~46 MB) for the app
     * lifetime because the model is never deleted once downloaded
     */
    private final ConcurrentHashMap<ModelType, EfficientSamProcessor> mEfficientSamProcessors;
    /**
     * Lazy inpainting processor cache (keyed by model type for future LaMa
     * variants), an instance holds its ONNX model loaded in the native memory
     * (the file is ~93 MB) for the app lifetime because the model is never
     * deleted once downloaded
     */
    private final ConcurrentHashMap<ModelType, LamaProcessor> mLamaProcessors;
    /**
     * Lazy AI-generated-image detection processor cache (keyed by model type
     * for future detector variants), an instance holds its ONNX model loaded
     * in the native memory (the fp32 file is ~87 MB) for the app lifetime
     * because the model is never deleted once downloaded
     */
    private final ConcurrentHashMap<ModelType, AiImageDetectProcessor> mAiImageDetectProcessors;
    /**
     * Lazy NAFNet deblur processor cache (keyed by model type for future
     * restore variants, the file is ~87 MB). App-lifetime per the
     * teardown-poison note on {@link NafNetProcessor} — evicted only by
     * dispose; the retained dnn workspace is bounded by the working cap
     * (see {@link NafNetProcessor#suggestedWorkingCap(Context)}) instead.
     */
    private final ConcurrentHashMap<ModelType, NafNetProcessor> mNafNetProcessors;
    /**
     * Lazy Swin2SR upscale processor cache (keyed by model type for future
     * upscale variants, the file is ~51.3 MB). App-lifetime per the
     * teardown-poison note on {@link Swin2srProcessor} — evicted only by
     * dispose; the peak merge memory is bounded by the input cap (see
     * {@link Swin2srProcessor#suggestedUpscaleCap(Context)}) instead.
     */
    private final ConcurrentHashMap<ModelType, Swin2srProcessor> mSwin2srProcessors;
    /**
     * Lazy cached YuNet face detector, created on first face detection and
     * kept for the provider lifetime like the dnn processor caches above.
     * Released only by {@link #releaseFaceModels()} and
     * {@link #dispose(Context)}
     */
    private FaceDetectorYN mFaceDetector;
    /**
     * Lazy cached SFace face recognizer, created on first face comparison and
     * kept for the provider lifetime like the dnn processor caches above.
     * Released only by {@link #releaseFaceModels()} and
     * {@link #dispose(Context)}
     */
    private FaceRecognizerSF mFaceRecognizer;

    public MLEngineInstance(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        mSTProcessors = new ConcurrentHashMap<>();
        mPersonSegProcessors = new ConcurrentHashMap<>();
        mEfficientSamProcessors = new ConcurrentHashMap<>();
        mLamaProcessors = new ConcurrentHashMap<>();
        mAiImageDetectProcessors = new ConcurrentHashMap<>();
        mNafNetProcessors = new ConcurrentHashMap<>();
        mSwin2srProcessors = new ConcurrentHashMap<>();
        if (OpenCVLoader.initLocal()) {
            mLogger.d("OpenCV", "OpenCV loaded");
        } else {
            mLogger.e("OpenCV", "Error Loading OpenCV");
        }
    }

    /**
     * Returns the neural style transfer processor of the given model type,
     * creating and caching it on first access. A processor instance must
     * never have its process() invoked concurrently, callers are expected
     * to serialize their calls on one instance (e.g. through a single-thread
     * executor)
     *
     * @param modelType the style-transfer model type
     * @return the cached processor of the given model type
     */
    STProcessor getSTProcessor(ModelType modelType) {
        return mSTProcessors.computeIfAbsent(modelType,
                key -> new STProcessor(
                        requireModelFile(key).getAbsolutePath(),
                        mLogger));
    }

    /**
     * Returns the portrait segmentation processor, creating and caching it on
     * first access. A processor instance must never have its
     * computeMask() invoked concurrently, callers are expected to serialize
     * their calls on one instance (e.g. through a single-thread executor)
     *
     * @return the cached portrait segmentation processor
     */
    PersonSegProcessor getPersonSegProcessor() {
        return mPersonSegProcessors.computeIfAbsent(ModelType.PERSON_SEG,
                modelType -> new PersonSegProcessor(
                        requireModelFile(modelType).getAbsolutePath(), mLogger));
    }

    /**
     * Returns the object segmentation processor, creating and caching it on
     * first access. A processor instance must never have its
     * computeRawMask() invoked concurrently, callers are expected to serialize
     * their calls on one instance (e.g. through a single-thread executor)
     *
     * @return the cached object segmentation processor
     */
    EfficientSamProcessor getEfficientSamProcessor() {
        return mEfficientSamProcessors.computeIfAbsent(ModelType.EFFICIENT_SAM,
                modelType -> new EfficientSamProcessor(
                        requireModelFile(modelType).getAbsolutePath(), mLogger));
    }

    /**
     * Returns the inpainting processor, creating and caching it on
     * first access. A processor instance must never have its
     * inpaint() invoked concurrently, callers are expected to serialize
     * their calls on one instance (e.g. through a single-thread executor)
     *
     * @return the cached inpainting processor
     */
    LamaProcessor getLamaProcessor() {
        return mLamaProcessors.computeIfAbsent(ModelType.LAMA,
                modelType -> new LamaProcessor(
                        requireModelFile(modelType).getAbsolutePath(), mLogger));
    }

    /**
     * Returns the AI-generated-image detection processor, creating and
     * caching it on first access. A processor instance must never have its
     * classify() invoked concurrently, callers are expected to serialize
     * their calls on one instance (e.g. through a single-thread executor)
     *
     * @return the cached AI-generated-image detection processor
     */
    AiImageDetectProcessor getAiImageDetectProcessor() {
        return mAiImageDetectProcessors.computeIfAbsent(ModelType.AI_IMAGE_DETECT,
                modelType -> new AiImageDetectProcessor(
                        requireModelFile(modelType).getAbsolutePath(), mLogger));
    }

    /**
     * Returns the NAFNet deblur processor, creating and caching it on first
     * access. The creation runs a 512-px synthetic warm-up forward (see
     * {@link NafNetProcessor#warmUp()}), so every later forward — of any
     * size — is safe (cold-first-forward law). A warm-up throw propagates
     * and leaves the processor uncached (the next call retries).
     * Calls on one instance must be serialized (e.g. single-thread
     * executor); the instance is app-lifetime per the teardown-poison note
     * on {@link NafNetProcessor} — nobody but the dispose cascade may
     * release it.
     *
     * @return the cached NAFNet deblur processor
     */
    NafNetProcessor getNafNetProcessor() {
        return mNafNetProcessors.computeIfAbsent(ModelType.NAFNET_DEBLUR,
                modelType -> {
                    NafNetProcessor processor = new NafNetProcessor(
                            requireModelFile(modelType).getAbsolutePath(),
                            mLogger);
                    processor.warmUp();
                    return processor;
                });
    }

    /**
     * Returns the Swin2SR upscale processor, creating and caching it on first
     * access. Calls on one instance must be serialized (e.g. single-thread
     * executor); the instance is app-lifetime per the teardown-poison note
     * on {@link Swin2srProcessor} — nobody but the dispose cascade may
     * release it.
     *
     * @return the cached Swin2SR upscale processor
     */
    Swin2srProcessor getSwin2srProcessor() {
        return mSwin2srProcessors.computeIfAbsent(ModelType.SWIN2SR_UPSCALE,
                modelType -> new Swin2srProcessor(
                        requireModelFile(modelType).getAbsolutePath(), mLogger));
    }

    /**
     * Release the cached object segmentation processor (frees its native
     * net, see {@link EfficientSamProcessor#release()}): OpenCV dnn retains
     * a large workspace inside the net after its inference, so the magic
     * eraser page releases the processor through
     * MagicEraserEngine.releaseSamProcessor() when it is done segmenting
     * instead of keeping it for the app lifetime. The next
     * {@link #getEfficientSamProcessor()} lazily recreates the processor
     * from the model file on demand.
     */
    void releaseEfficientSamProcessor() {
        EfficientSamProcessor processor =
                mEfficientSamProcessors.remove(ModelType.EFFICIENT_SAM);
        if (processor != null) {
            processor.release();
            nudgeNativeReclaim();
        }
    }

    /**
     * Release the cached inpainting processor (frees its native net, see
     * {@link LamaProcessor#release()}): OpenCV dnn retains a large workspace
     * inside the net after its inference, so the magic eraser page releases
     * the processor through MagicEraserEngine.releaseLamaProcessor() after
     * every erase instead of keeping it for the app lifetime. The next
     * {@link #getLamaProcessor()} lazily recreates the processor from the
     * model file on demand.
     */
    void releaseLamaProcessor() {
        LamaProcessor processor = mLamaProcessors.remove(ModelType.LAMA);
        if (processor != null) {
            processor.release();
            nudgeNativeReclaim();
        }
    }

    /**
     * Release the cached AI-generated-image detection processor (frees its
     * native net, see {@link AiImageDetectProcessor#release()}): OpenCV dnn
     * retains a large workspace inside the net after its inference, so the
     * detector page releases the processor through AiDetectEngine.release()
     * when it is done detecting instead of keeping it for the app lifetime.
     * The next {@link #getAiImageDetectProcessor()} lazily recreates the
     * processor from the model file on demand.
     */
    void releaseAiImageDetectProcessor() {
        AiImageDetectProcessor processor =
                mAiImageDetectProcessors.remove(ModelType.AI_IMAGE_DETECT);
        if (processor != null) {
            processor.release();
            nudgeNativeReclaim();
        }
    }

    /**
     * Release the cached face models (frees their native memory): the ~37 MB
     * SFace recognizer plus the YuNet detector are only worth their native
     * memory during face-editing sessions, so the face editor page releases
     * them on dispose through FaceEngine.releaseFaceModels() at the cost of
     * a seconds-long model re-parse in the next face session (the same
     * policy as the magic eraser's SAM/LaMa releases). The next
     * {@link #getFaceDetectModel()} and {@link #getFaceRecognizerModel()}
     * lazily recreate them from the model files on demand.
     */
    synchronized void releaseFaceModels() {
        if (mFaceDetector == null && mFaceRecognizer == null) {
            return;
        }
        mFaceDetector = null;
        mFaceRecognizer = null;
        nudgeNativeReclaim();
    }

    /**
     * Make the runtime reclaim the native net a release() just dropped: the
     * net wrapper's finalizer deletes the native net (its large dnn
     * workspace included), and on a 32-bit device the address space is the
     * scarce resource — a native allocation failure never triggers a GC by
     * itself, so the finalization is requested explicitly at this
     * predictable point instead of being left to an arbitrary later GC.
     */
    private static void nudgeNativeReclaim() {
        System.gc();
        Runtime.getRuntime().runFinalization();
    }

    /**
     * Returns the SFace face recognition model, creating and caching it on
     * first access. Cached for the provider lifetime like the dnn processors
     * (the seconds-long model re-parse is paid once per provider, not per
     * call). The returned instances are SHARED — callers must serialize
     * their face operations (FaceEngine's engine monitor does this), nobody
     * but the engine and {@link #releaseFaceModels()} may hold or release
     * this recognizer.
     *
     * @return the cached face recognition model
     */
    synchronized FaceRecognizerSF getFaceRecognizerModel() {
        if (mFaceRecognizer == null) {
            mFaceRecognizer = FaceRecognizerSF.create(
                    requireModelFile(ModelType.FACE_RECOGNIZER).getAbsolutePath(), "");
        }
        return mFaceRecognizer;
    }

    /**
     * Returns the YuNet face detection model, creating and caching it on
     * first access. Cached for the provider lifetime like the dnn processors
     * (the seconds-long model re-parse is paid once per provider, not per
     * call). The detector is a stateful shared instance: FaceEngine sets its
     * input size from the bitmap dimensions before every detect
     * (setInputSize mutates the shared detector), so the returned instances
     * are SHARED — callers must serialize their face operations (FaceEngine's
     * engine monitor does this), nobody but the engine and
     * {@link #releaseFaceModels()} may hold or release this detector.
     *
     * @return the cached face detection model
     */
    synchronized FaceDetectorYN getFaceDetectModel() {
        if (mFaceDetector == null) {
            mFaceDetector = FaceDetectorYN.create(
                    requireModelFile(ModelType.FACE_DETECT).getAbsolutePath(), "",
                    new Size(FACE_DETECT_WIDTH, FACE_DETECT_HEIGHT), 0.6f, 0.5f);
        }
        return mFaceDetector;
    }

    /**
     * Internal per-model availability gate: true when the model file of the
     * given type is downloaded and usable. The engine facades
     * (FaceEngine, PersonSegEngine, MagicEraserEngine, AiDetectEngine,
     * PhotoRestoreEngine and the style-transfer facade) call it directly
     * through their engine-held MLEngineInstance handles, the instrumented
     * tests use it the same way (same package).
     *
     * @param modelType model type to check
     * @return true when the model file is already downloaded and usable,
     * running the legacy file name migration when needed
     * (see {@link #migrateLegacyIfNeeded})
     */
    boolean isModelAvailable(ModelType modelType) {
        return isAvailable(mAppContext, modelType);
    }

    /**
     * The public generic availability gate of the model-download machinery
     * (ModelDownloadDialog): true when the model file behind the given
     * opaque model id (as obtained from the engine facades) is downloaded
     * and usable. Returns false for an unknown id.
     *
     * @param modelId opaque model id as obtained from the engine facades
     * @return true when the model file is already downloaded and usable,
     * false for an unknown id
     */
    public boolean isModelAvailable(String modelId) {
        ModelType modelType = ModelType.fromId(modelId);
        return modelType != null && isAvailable(mAppContext, modelType);
    }

    /**
     * Display name of the model behind the given opaque model id, used by
     * the download UI (the ModelDownloadDialog prompt). Returns the raw id
     * when it is unknown, so the UI always shows something.
     *
     * @param modelId opaque model id as obtained from the engine facades
     * @return human readable model name, or the raw id when unknown
     */
    public String displayNameFor(String modelId) {
        ModelType modelType = ModelType.fromId(modelId);
        return modelType == null ? modelId : modelType.getDisplayName();
    }

    /**
     * Approximate display size of the model behind the given opaque model
     * id, used by the download UI (the ModelDownloadDialog prompt). Returns
     * the raw id when it is unknown, so the UI always shows something.
     *
     * @param modelId opaque model id as obtained from the engine facades
     * @return approximate file size, or the raw id when unknown
     */
    public String displaySizeFor(String modelId) {
        ModelType modelType = ModelType.fromId(modelId);
        return modelType == null ? modelId : modelType.getDisplaySize();
    }

    /**
     * Pure path computation of the target file of the given model type
     * inside the app filesDir, following the same path structure used when
     * loading the models. It only throws
     * {@link IllegalArgumentException} on an unknown model type, it never
     * touches the disk — callers that need an existence-checked access use
     * {@link #requireModelFile} instead.
     *
     * @param modelType model type to resolve
     * @return the target file of the given model type
     */
    File getModelFile(ModelType modelType) {
        return getModelFile(mAppContext, modelType);
    }

    /**
     * Dispose every cached processor, called from the provider dispose
     * cascade: it runs synchronously through
     * {@link MLEngineProviderModule#dispose(Provider)} and again
     * asynchronously through this instance's ProviderDisposable entry in the
     * provider registry (idempotent, the second call no-ops). It may run on
     * the teardown caller thread (main or instrumentation), the final
     * {@link #nudgeNativeReclaim()} at teardown is accepted, and it is safe
     * against in-flight forwards because every release is a reference-drop
     * under the processor's own monitor (see the processor release()
     * contracts). Every cached processor is released inside its own
     * try/catch so one failing entry cannot abort the rest of the cascade.
     *
     * @param context the context of the provider, unused here (releasing
     *                the cached processors and face models needs no context)
     */
    @Override
    public synchronized void dispose(Context context) {
        if (mDisposed) {
            return;
        }
        mDisposed = true;
        for (STProcessor processor : mSTProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release a style transfer processor", e);
            }
        }
        for (PersonSegProcessor processor : mPersonSegProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release a portrait segmentation processor", e);
            }
        }
        for (EfficientSamProcessor processor : mEfficientSamProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release an object segmentation processor", e);
            }
        }
        for (LamaProcessor processor : mLamaProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release an inpainting processor", e);
            }
        }
        for (AiImageDetectProcessor processor : mAiImageDetectProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release an AI image detection processor", e);
            }
        }
        for (NafNetProcessor processor : mNafNetProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release a NAFNet deblur processor", e);
            }
        }
        for (Swin2srProcessor processor : mSwin2srProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release a Swin2SR upscale processor", e);
            }
        }
        mSTProcessors.clear();
        mPersonSegProcessors.clear();
        mEfficientSamProcessors.clear();
        mLamaProcessors.clear();
        mAiImageDetectProcessors.clear();
        mNafNetProcessors.clear();
        mSwin2srProcessors.clear();
        mFaceDetector = null;
        mFaceRecognizer = null;
        nudgeNativeReclaim();
    }

    /**
     * Maps each {@link ModelType} to its target file inside the app filesDir,
     * following the same path structure used when loading the models. Pure
     * path computation, it never touches the disk.
     *
     * @param context   any context, only its filesDir is used
     * @param modelType model type to resolve
     * @return the target file of the given model type
     */
    private static File getModelFile(Context context, ModelType modelType) {
        File filesDir = context.getFilesDir();
        switch (modelType) {
            case FACE_DETECT:
                return new File(filesDir, FACE_DETECT_PATH
                        + "/" + FACE_DETECT_FILE);
            case FACE_RECOGNIZER:
                return new File(filesDir, FACE_RECOGNIZER_PATH
                        + "/" + FACE_RECOGNIZER_FILE);
            case NST_MOSAIC:
                return new File(filesDir, NEURAL_STYLE_TRANSFER_PATH
                        + "/" + NST_MOSAIC_FILE);
            case NST_CANDY:
                return new File(filesDir, NEURAL_STYLE_TRANSFER_PATH
                        + "/" + NST_CANDY_FILE);
            case NST_RAIN_PRINCESS:
                return new File(filesDir, NEURAL_STYLE_TRANSFER_PATH
                        + "/" + NST_RAIN_PRINCESS_FILE);
            case NST_UDNIE:
                return new File(filesDir, NEURAL_STYLE_TRANSFER_PATH
                        + "/" + NST_UDNIE_FILE);
            case NST_POINTILISM:
                return new File(filesDir, NEURAL_STYLE_TRANSFER_PATH
                        + "/" + NST_POINTILISM_FILE);
            case PERSON_SEG:
                return new File(filesDir, PERSON_SEGMENTATION_PATH
                        + "/" + PERSON_SEGMENTATION_FILE);
            case EFFICIENT_SAM:
                return new File(filesDir, OBJECT_SEGMENTATION_PATH
                        + "/" + EFFICIENT_SAM_FILE);
            case LAMA:
                return new File(filesDir, INPAINTING_PATH
                        + "/" + LAMA_FILE);
            case AI_IMAGE_DETECT:
                return new File(filesDir, AI_IMAGE_DETECT_PATH
                        + "/" + AI_IMAGE_DETECT_FILE);
            case NAFNET_DEBLUR:
                return new File(filesDir, NAFNET_PATH
                        + "/" + NAFNET_FILE);
            case SWIN2SR_UPSCALE:
                return new File(filesDir, SWIN2SR_PATH
                        + "/" + SWIN2SR_FILE);
            default:
                throw new IllegalArgumentException("Unknown model type: " + modelType);
        }
    }

    /**
     * @param context   any context, only its filesDir is used
     * @param modelType model type to check
     * @return true when the model file is already downloaded and usable.
     * Runs the legacy file name migration (see {@link #migrateLegacyIfNeeded})
     * when needed
     */
    private static boolean isAvailable(Context context, ModelType modelType) {
        File modelFile = getModelFile(context, modelType);
        if (modelFile.exists()) {
            return true;
        }
        return migrateLegacyIfNeeded(modelType, modelFile);
    }

    /**
     * Installs before on-demand download bundled the NST models inside the APK under the
     * legacy nst_*_9.onnx names. When the model is missing under its current name but the
     * legacy file exists in the same directory, rename it instead of re-downloading ~32 MB.
     * Returns true when after this call the model file exists.
     */
    private static boolean migrateLegacyIfNeeded(ModelType modelType, File modelFile) {
        String legacyFileName = getLegacyFileName(modelType);
        if (legacyFileName == null) {
            return false;
        }
        File legacyFile = new File(modelFile.getParentFile(), legacyFileName);
        if (!legacyFile.exists()) {
            return false;
        }
        if (legacyFile.renameTo(modelFile)) {
            return true;
        }
        // benign race when two threads migrate at the same time,
        // the second rename fails, just check whether the first one succeeded
        return modelFile.exists();
    }

    private static String getLegacyFileName(ModelType modelType) {
        switch (modelType) {
            case NST_MOSAIC:
                return "nst_mosaic_9.onnx";
            case NST_CANDY:
                return "nst_candy_9.onnx";
            case NST_RAIN_PRINCESS:
                return "nst_rain_princess_9.onnx";
            case NST_UDNIE:
                return "nst_udnie_9.onnx";
            case NST_POINTILISM:
                return "nst_pointilism_9.onnx";
            default:
                // face models never had a legacy name
                return null;
        }
    }

    /**
     * These models are downloaded on demand (see ModelDownloadWorker),
     * callers are expected to check availability first (see isModelAvailable),
     * hence this should never be hit on normal flow
     */
    @NonNull
    private File requireModelFile(ModelType modelType) {
        // isAvailable also runs the legacy file name migration when needed
        if (!isAvailable(mAppContext, modelType)) {
            File file = getModelFile(mAppContext, modelType);
            String message = "Model not available: " + modelType.getDisplayName()
                    + ", expected file: " + file.getAbsolutePath()
                    + ". Please download the model first.";
            mLogger.e(TAG, message);
            throw new IllegalStateException(message);
        }
        return getModelFile(mAppContext, modelType);
    }

    /**
     * Internal model-catalog key owned by {@link MLEngineInstance},
     * not part of the public API: outside code references models only
     * through the opaque id strings obtained from the engine facades
     * ({@link ModelType#getId()}), the download machinery resolves them
     * back through {@link ModelType#fromId(String)}.
     * Catalog of the AI models used by this engine.
     * All models are downloaded on demand (see ModelDownloader),
     * they are not bundled inside the APK.
     */
    enum ModelType {
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
                "https://huggingface.co/opencv/inpainting_lama/resolve/main/inpainting_lama_2025jan.onnx"),
        /**
         * AI-generated-image detector, the CommunityForensics DeepfakeDet-ViT
         * binary deepfake classifier, from
         * https://huggingface.co/buildborderless/CommunityForensics-DeepfakeDet-ViT
         * (the corrected fp32 build onnx/model.onnx), upstream project
         * https://github.com/JeongsooP/Community-Forensics. Licence: MIT,
         * primary source = the upstream GitHub LICENSE file (Copyright 2025
         * Jeongsoo Park). Architecture: ViT-S/16 (hidden size 384, 6 heads,
         * 12 layers, patch 16, intermediate 1536), 384x384 RGB input
         * (shortest-edge resize to 440, center crop, CLIP normalization),
         * num_labels=1 — a single output logit, pFake = sigmoid(logit).
         * Training: 2.7M fakes across 4,803 generators against 2.7M reals with
         * JPEG/resize/blur augmentation; paper cross-generator OOD eval
         * 0.987 mAP / 0.892 accuracy (CVPR 2025, arXiv:2411.04125). Weights
         * history: the hosted export is the CORRECTED 2026-07-22 regeneration —
         * the stale artifacts (the deprecated ...-ONNX repos and the
         * onnx_legacy/ folder of the model repo) hold WRONG weights and must
         * not be used or re-exported. Quantized variants are a double NO-GO:
         * OpenCV DNN cannot load them, and the model card itself documents
         * 10-70 percentage-point OOD divergence for the int8/q4 builds.
         * Spike status: Round 1 (jacoballessio distilled, 0/8 synthetic
         * detected on-device): NO-GO 2026-10-31, model swapped; Round 2
         * (this model): GO 2026-10-31 — 5/5 modern AI samples detected
         * on-device (pFake 0.986-1.000), 0/9 false positives on the
         * real+compressed group, 605 ms mean latency per image.
         */
        AI_IMAGE_DETECT("ai-image-detect",
                "AI Image Detector (CommunityForensics)",
                "~87 MB",
                "https://huggingface.co/buildborderless/CommunityForensics-DeepfakeDet-ViT/resolve/main/onnx/model.onnx"),
        /**
         * NAFNet photo-restoration (deblur/denoise) model, the official OpenCV
         * export from https://huggingface.co/opencv/deblurring_nafnet — the
         * only candidate whose dynamic input shape loads through OpenCV 5 DNN
         * unmodified (any HxW). Licence: dual MIT (megvii-model) + Apache-2.0
         * (BasicSR Authors), primary source = the HF repo LICENSE file (not
         * the HF repo tag). 64-bit devices only (unreliable 32-bit failure
         * mode); forward memory scales at ~8.3 KB per input pixel, so the
         * working-cap + tiled design of {@link m.co.rh.id.a_jarwis.ml_engine.provider.component.NafNetProcessor}
         * is mandatory (PSNR 25.6 dB at the 512 px cap, reference impl).
         */
        NAFNET_DEBLUR("nafnet-deblur",
                "Photo Restore (NAFNet)",
                "~87.5 MB",
                "https://huggingface.co/opencv/deblurring_nafnet/resolve/main/deblurring_nafnet_2025may.onnx"),
        /**
         * Swin2SR real-world 4x super-resolution (SwinV2), the fp32 ONNX export
         * from https://huggingface.co/onnx-community/swin2SR-realworld-sr-x4-64-bsrgan-psnr-ONNX
         * (upstream project https://github.com/mv-lab/swin2sr). FIXED export
         * shape 64x64 -&gt; 256x256 (the -64 in the repo name): inference tiles at
         * the input resolution, merged on a 4x output canvas by
         * {@link m.co.rh.id.a_jarwis.ml_engine.provider.component.Swin2srProcessor};
         * preprocess rgb_div255 per the phase-0 spike (issue #5). Licence:
         * Apache-2.0, primary source = the upstream mv-lab/swin2sr LICENSE (not
         * the HF tag). App-lifetime cached (teardown-poison law); peak merge
         * memory ~47 B per output pixel (~789 MB at the 1024 cap) -&gt; RAM-tiered
         * input cap.
         */
        SWIN2SR_UPSCALE("swin2sr-upscale",
                "Photo Restore Upscale (Swin2SR)",
                "~51.3 MB",
                "https://huggingface.co/onnx-community/swin2SR-realworld-sr-x4-64-bsrgan-psnr-ONNX/resolve/main/onnx/model.onnx");

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
}
