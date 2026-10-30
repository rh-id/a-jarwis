package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;

import androidx.annotation.NonNull;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class MLEngineInstance {
    private static final String TAG = "MLEngineInstance";

    public static final String BASE_PATH = "/ml-engine/engine";
    public static final String FACE_PATH = BASE_PATH + "/face";
    public static final String FACE_DETECT_PATH = FACE_PATH + "/detect";
    public static final String FACE_DETECT_FILE = "face_detection_yunet_2023mar.onnx";
    public static final int FACE_DETECT_WIDTH = 640;
    public static final int FACE_DETECT_HEIGHT = 640;
    public static final String FACE_RECOGNIZER_PATH = FACE_PATH + "/recognizer";
    public static final String FACE_RECOGNIZER_FILE = "face_recognition_sface_2021dec_int8.onnx";
    public static final String NEURAL_STYLE_TRANSFER_PATH = BASE_PATH + "/neural-style-transfer";
    public static final String NST_MOSAIC_FILE = "mosaic-9.onnx";
    public static final String NST_CANDY_FILE = "candy-9.onnx";
    public static final String NST_RAIN_PRINCESS_FILE = "rain-princess-9.onnx";
    public static final String NST_UDNIE_FILE = "udnie-9.onnx";
    public static final String NST_POINTILISM_FILE = "pointilism-9.onnx";
    public static final String PERSON_SEGMENTATION_PATH = BASE_PATH + "/person-segmentation";
    public static final String PERSON_SEGMENTATION_FILE = "human_segmentation_pphumanseg_2023mar_int8bq.onnx";
    public static final String OBJECT_SEGMENTATION_PATH = BASE_PATH + "/object-segmentation";
    public static final String EFFICIENT_SAM_FILE = "image_segmentation_efficientsam_ti_2025april.onnx";
    public static final String INPAINTING_PATH = BASE_PATH + "/inpainting";
    public static final String LAMA_FILE = "inpainting_lama_2025jan.onnx";
    private final Context mAppContext;
    private final ILogger mLogger;
    /**
     * Lazy per-theme neural style transfer processor cache,
     * an instance holds its ONNX model loaded in the native memory
     * (~32 MB worst case) for the app lifetime because the models are
     * never deleted once downloaded
     */
    private final ConcurrentHashMap<Integer, STProcessor> mSTProcessors;
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

    public MLEngineInstance(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        mSTProcessors = new ConcurrentHashMap<>();
        mPersonSegProcessors = new ConcurrentHashMap<>();
        mEfficientSamProcessors = new ConcurrentHashMap<>();
        mLamaProcessors = new ConcurrentHashMap<>();
        if (OpenCVLoader.initLocal()) {
            mLogger.d("OpenCV", "OpenCV loaded");
        } else {
            mLogger.e("OpenCV", "Error Loading OpenCV");
        }
    }

    /**
     * Returns the neural style transfer processor of the given theme, creating
     * and caching it on first access. A processor instance must never have its
     * process() invoked concurrently, callers are expected to serialize their
     * calls on one instance (e.g. through a single-thread executor)
     *
     * @param theme neural style transfer theme constant defined in {@link STEngine}
     * @return the cached processor of the given theme
     */
    public STProcessor getSTProcessor(int theme) {
        return mSTProcessors.computeIfAbsent(theme,
                themeKey -> new STProcessor(
                        requireModelFile(ModelCatalog.fromTheme(themeKey)).getAbsolutePath(),
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
    public PersonSegProcessor getPersonSegProcessor() {
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
    public EfficientSamProcessor getEfficientSamProcessor() {
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
    public LamaProcessor getLamaProcessor() {
        return mLamaProcessors.computeIfAbsent(ModelType.LAMA,
                modelType -> new LamaProcessor(
                        requireModelFile(modelType).getAbsolutePath(), mLogger));
    }

    /**
     * Release the cached object segmentation processor (frees its native
     * net, see {@link EfficientSamProcessor#release()}): OpenCV dnn retains
     * a large workspace inside the net after its inference, so an editor
     * page releases the processor when it is done segmenting instead of
     * keeping it for the app lifetime. The next
     * {@link #getEfficientSamProcessor()} lazily recreates the processor
     * from the model file on demand.
     */
    public void releaseEfficientSamProcessor() {
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
     * inside the net after its inference, so an editor page releases the
     * processor after every erase instead of keeping it for the app
     * lifetime. The next {@link #getLamaProcessor()} lazily recreates the
     * processor from the model file on demand.
     */
    public void releaseLamaProcessor() {
        LamaProcessor processor = mLamaProcessors.remove(ModelType.LAMA);
        if (processor != null) {
            processor.release();
            nudgeNativeReclaim();
        }
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

    public FaceRecognizerSF getFaceRecognizerModel() {
        return FaceRecognizerSF.create(
                requireModelFile(ModelType.FACE_RECOGNIZER).getAbsolutePath(), "");
    }

    public FaceDetectorYN getFaceDetectModel() {
        return FaceDetectorYN.create(
                requireModelFile(ModelType.FACE_DETECT).getAbsolutePath(), "",
                new Size(FACE_DETECT_WIDTH, FACE_DETECT_HEIGHT), 0.6f, 0.5f);
    }

    public boolean isFaceDetectAvailable() {
        return ModelCatalog.isAvailable(mAppContext, ModelType.FACE_DETECT);
    }

    public boolean isFaceRecognizerAvailable() {
        return ModelCatalog.isAvailable(mAppContext, ModelType.FACE_RECOGNIZER);
    }

    public boolean isNSTAvailable(int theme) {
        return ModelCatalog.isAvailable(mAppContext, ModelCatalog.fromTheme(theme));
    }

    public boolean isPersonSegAvailable() {
        return ModelCatalog.isAvailable(mAppContext, ModelType.PERSON_SEG);
    }

    /**
     * @return true only when BOTH the object segmentation model and the
     * inpainting model are downloaded, the magic eraser needs the two of them
     */
    public boolean isMagicEraserAvailable() {
        return ModelCatalog.isAvailable(mAppContext, ModelType.EFFICIENT_SAM)
                && ModelCatalog.isAvailable(mAppContext, ModelType.LAMA);
    }

    /**
     * These models are downloaded on demand (see ModelDownloadWorker),
     * callers are expected to check availability first (see ModelCatalog.isAvailable),
     * hence this should never be hit on normal flow
     */
    @NonNull
    private File requireModelFile(ModelType modelType) {
        // isAvailable also runs the legacy file name migration when needed
        if (!ModelCatalog.isAvailable(mAppContext, modelType)) {
            File file = ModelCatalog.getModelFile(mAppContext, modelType);
            String message = "Model not available: " + modelType.getDisplayName()
                    + ", expected file: " + file.getAbsolutePath()
                    + ". Please download the model first.";
            mLogger.e(TAG, message);
            throw new IllegalStateException(message);
        }
        return ModelCatalog.getModelFile(mAppContext, modelType);
    }
}
