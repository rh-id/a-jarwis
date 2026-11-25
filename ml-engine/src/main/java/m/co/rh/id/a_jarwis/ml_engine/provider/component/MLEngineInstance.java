package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;

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
    private static final String DEPTH_ESTIMATION_PATH = BASE_PATH + "/depth-estimation";
    private static final String DEPTH_ESTIMATION_FILE = "depth_anything_v2_small.onnx";
    private static final String SUBJECT_SEG_PATH = BASE_PATH + "/subject-segmentation";
    private static final String SUBJECT_SEG_FILE = "isnet_general_use_1024_fp32.onnx";
    /**
     * Native-heap ceiling of the bounded settle poll before the ISNet
     * re-parse ({@link #settleNativeHeapBeforeSubjectSegReparse}): the
     * issue-#4 SPIKE #2 teardown run was lmk-killed on the 2 GB 32-bit AVD
     * when the reloaded net parsed while the previously released ~1 GB dnn
     * workspace had not been reclaimed yet (~1549 MB resident at death).
     * Waiting until the allocated native heap drops below this ceiling (or
     * the timeout expires) gives the finalizer that time on the
     * memory-constrained tiers; the ceiling stays far above the ISNet
     * steady state (~190 MB after parse) so a healthy process never waits
     */
    private static final long SUBJECT_SEG_SETTLE_HEAP_CEILING_BYTES =
            600L * 1024L * 1024L;
    /**
     * Timeout of the bounded settle poll, the wait must never turn into a
     * hang: after the timeout the re-parse proceeds regardless and the
     * page-level OOM/CvException self-heal (the MagicEraser toast pattern)
     * is the last resort
     */
    private static final long SUBJECT_SEG_SETTLE_TIMEOUT_MS = 2_000;
    /**
     * Poll interval of the bounded settle poll, each tick re-nudges the
     * finalizer (the release reclamation is finalizer-driven, see
     * {@link #nudgeNativeReclaim()})
     */
    private static final long SUBJECT_SEG_SETTLE_POLL_MS = 100;
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
     * Lazy depth estimation processor cache (keyed by model type for future
     * depth variants), an instance holds its ONNX model loaded in the native
     * memory (the fp32 file is ~99 MB, ~480-550 MB native retained after the
     * forwards and GC) — like the magic eraser's SAM/LaMa the retained dnn
     * workspace is far too big for an app-lifetime cache, so the lens blur
     * editor releases the processor through
     * {@link DepthEngine#releaseProcessor()} when its session ends
     * (the {@link #releaseDepthProcessor()} map remove + nudge pattern, the
     * AI-detector precedent) and the next use lazily re-parses the model.
     */
    private final ConcurrentHashMap<ModelType, DepthProcessor> mDepthProcessors;
    /**
     * Lazy subject segmentation processor cache (keyed by model type for
     * future subject-segmentation variants), an instance holds its ONNX
     * model loaded in the native memory (the fp32 file is ~171 MB, ~1 GB
     * native retained after the forwards and GC) — SESSION-LIFETIME, not
     * app-lifetime: unlike the PersonSeg processor (~1.7 MB file, negligible
     * workspace) the ISNet dnn workspace retained after the forwards is far
     * too big to keep resident next to the other heavy nets, so the
     * subject-background-removal editor releases the processor through
     * {@link #releaseSubjectSegProcessor()} when its session ends (the
     * {@link DepthProcessor} cache shape, release + nudge) and the next use
     * lazily re-parses the model behind the bounded native-heap settle (see
     * {@link #getSubjectSegProcessor()}).
     */
    private final ConcurrentHashMap<ModelType, SubjectSegProcessor> mSubjectSegProcessors;
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
        mDepthProcessors = new ConcurrentHashMap<>();
        mSubjectSegProcessors = new ConcurrentHashMap<>();
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
     * Returns the depth estimation processor, creating and caching it on
     * first access. A processor instance must never have its
     * computeDepth() invoked concurrently, callers are expected to serialize
     * their calls on one instance (e.g. through a single-thread executor).
     * The cached processor is session-lifetime, not app-lifetime: the
     * ~480-550 MB native dnn workspace the fp32 net retains after its
     * forwards must not outlive the lens-blur session, the engine facade
     * releases it through {@link #releaseDepthProcessor()} on editor dispose
     * and the next use lazily re-parses the ~99 MB model file.
     *
     * @return the cached depth estimation processor
     */
    DepthProcessor getDepthProcessor() {
        return mDepthProcessors.computeIfAbsent(ModelType.DEPTH_ESTIMATION,
                modelType -> new DepthProcessor(
                        requireModelFile(modelType).getAbsolutePath(), mLogger));
    }

    /**
     * Release the cached depth estimation processor (frees its native net,
     * see {@link DepthProcessor#release()}): OpenCV dnn retains a large
     * workspace inside the net after its inference (~480-550 MB native after
     * GC, measured by the issue-#6 spike), so the lens blur editor releases
     * the processor through DepthEngine.releaseProcessor() on editor dispose
     * instead of keeping it for the app lifetime. The next
     * {@link #getDepthProcessor()} lazily recreates the processor from the
     * model file on demand (a seconds-long re-parse, the same policy as the
     * magic eraser's SAM/LaMa releases).
     */
    void releaseDepthProcessor() {
        DepthProcessor processor = mDepthProcessors.remove(ModelType.DEPTH_ESTIMATION);
        if (processor != null) {
            processor.release();
            nudgeNativeReclaim();
        }
    }

    /**
     * Returns the subject segmentation processor, creating and caching it on
     * first access. A processor instance must never have its
     * computeMask() invoked concurrently, callers are expected to serialize
     * their calls on one instance (e.g. through a single-thread executor).
     * The cached processor is session-lifetime, not app-lifetime (see the
     * mSubjectSegProcessors javadoc): after a release the next call lazily
     * re-parses the ~171 MB model file — and BEFORE that re-parse the
     * bounded native-heap settle runs ({@link
     * #settleNativeHeapBeforeSubjectSegReparse()}): on a cold first create
     * the settle exits immediately (the heap is far below the ceiling), on a
     * recreate-after-release it gives the previous net's ~1 GB workspace
     * finalizer the time the 2 GB tier needs (the issue-#4 SPIKE #2
     * teardown evidence).
     *
     * @return the cached subject segmentation processor
     */
    SubjectSegProcessor getSubjectSegProcessor() {
        return mSubjectSegProcessors.computeIfAbsent(ModelType.SUBJECT_SEG,
                modelType -> {
                    settleNativeHeapBeforeSubjectSegReparse();
                    return new SubjectSegProcessor(
                            requireModelFile(modelType).getAbsolutePath(),
                            mLogger);
                });
    }

    /**
     * Release the cached subject segmentation processor (frees its native
     * net, see {@link SubjectSegProcessor#release()}): OpenCV dnn retains
     * a large workspace inside the net after its inference (~1 GB native
     * after GC, measured by the issue-#4 SPIKE #2), so the subject
     * background removal editor releases the processor when its session
     * ends instead of keeping it for the app lifetime (the mandatory
     * release-on-dispose + nudge pattern, the depth-processor precedent).
     * The next {@link #getSubjectSegProcessor()} lazily recreates the
     * processor from the model file behind the bounded native-heap settle
     * (a seconds-long re-parse).
     */
    void releaseSubjectSegProcessor() {
        SubjectSegProcessor processor =
                mSubjectSegProcessors.remove(ModelType.SUBJECT_SEG);
        if (processor != null) {
            processor.release();
            nudgeNativeReclaim();
        }
    }

    /**
     * Bounded native-heap settle before the ISNet model re-parse: nudge the
     * finalizer, then poll the allocated native heap until it drops below
     * {@link #SUBJECT_SEG_SETTLE_HEAP_CEILING_BYTES} or
     * {@link #SUBJECT_SEG_SETTLE_TIMEOUT_MS} elapse, re-nudging each poll
     * tick. Spike evidence (issue #4 SPIKE #2 teardown run, 2 GB 32-bit
     * AVD): a gc/runFinalization nudge followed immediately by the
     * re-parse left the released ~1 GB dnn workspace resident and the
     * RELOADED net's first forward was lmk-killed at ~1549 MB resident —
     * the same sequence on the 3 GB 64-bit AVD reclaimed to ~22 MB and
     * reloaded fine, so the reclamation works, it only needs time on the
     * memory-constrained tier. The wait never becomes a hang: after the
     * timeout the re-parse proceeds regardless (the page layer's
     * OOM/CvException catch with the toast self-heal, the MagicEraser
     * pattern, is the last resort), and the settle outcome is logged for
     * the field diagnostics.
     */
    private void settleNativeHeapBeforeSubjectSegReparse() {
        nudgeNativeReclaim();
        long heap = Debug.getNativeHeapAllocatedSize();
        if (heap <= SUBJECT_SEG_SETTLE_HEAP_CEILING_BYTES) {
            mLogger.d(TAG, "subject-seg native heap already settled ("
                    + mb(heap) + " MB), no settle wait");
            return;
        }
        long startElapsed = SystemClock.elapsedRealtime();
        long deadline = startElapsed + SUBJECT_SEG_SETTLE_TIMEOUT_MS;
        while (heap > SUBJECT_SEG_SETTLE_HEAP_CEILING_BYTES
                && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(SUBJECT_SEG_SETTLE_POLL_MS);
            nudgeNativeReclaim();
            heap = Debug.getNativeHeapAllocatedSize();
        }
        long waitedMs = SystemClock.elapsedRealtime() - startElapsed;
        if (heap <= SUBJECT_SEG_SETTLE_HEAP_CEILING_BYTES) {
            mLogger.d(TAG, "subject-seg native heap settled to " + mb(heap)
                    + " MB in " + waitedMs + " ms before the re-parse");
        } else {
            mLogger.e(TAG, "subject-seg native heap did not settle below "
                    + mb(SUBJECT_SEG_SETTLE_HEAP_CEILING_BYTES) + " MB within "
                    + SUBJECT_SEG_SETTLE_TIMEOUT_MS + " ms (still " + mb(heap)
                    + " MB), proceeding with the re-parse — a reload forward"
                    + " may fail on the memory-constrained tiers"
                    + " (page-level self-heal is the last resort)");
        }
    }

    /**
     * Human readable megabyte figure of a byte count, for the settle logs
     */
    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f",
                bytes / (1024.0 * 1024.0));
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
        for (DepthProcessor processor : mDepthProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release a depth estimation processor", e);
            }
        }
        for (SubjectSegProcessor processor : mSubjectSegProcessors.values()) {
            try {
                processor.release();
            } catch (Exception e) {
                mLogger.e(TAG, "Failed to release a subject segmentation processor", e);
            }
        }
        mSTProcessors.clear();
        mPersonSegProcessors.clear();
        mEfficientSamProcessors.clear();
        mLamaProcessors.clear();
        mAiImageDetectProcessors.clear();
        mNafNetProcessors.clear();
        mSwin2srProcessors.clear();
        mDepthProcessors.clear();
        mSubjectSegProcessors.clear();
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
            case DEPTH_ESTIMATION:
                return new File(filesDir, DEPTH_ESTIMATION_PATH
                        + "/" + DEPTH_ESTIMATION_FILE);
            case SUBJECT_SEG:
                return new File(filesDir, SUBJECT_SEG_PATH
                        + "/" + SUBJECT_SEG_FILE);
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
                "https://huggingface.co/onnx-community/swin2SR-realworld-sr-x4-64-bsrgan-psnr-ONNX/resolve/main/onnx/model.onnx"),
        /**
         * Depth Anything V2 Small monocular depth estimation, the single-file
         * fp32 ONNX export from
         * https://huggingface.co/onnx-community/depth-anything-v2-small
         * (upstream project https://github.com/DepthAnything/Depth-Anything-V2).
         * Licence: Apache-2.0, verified against the upstream GitHub LICENSE
         * file — the Small checkpoint is Apache-2.0 while the Base/Large
         * checkpoints are CC-BY-NC-4.0 (forbidden for this app), so ONLY the
         * Small variant may ever ship. Output: a 3-D (1, H, W) blob of raw
         * disparity-like values (higher = closer, verified visually on the
         * issue-#6 spike photos), read through the reshape(1, size(dims-2))
         * clone form because the OpenCV 5 Java bindings silently return zeros
         * for n-dimensional bulk reads and imagesFromBlob asserts dims==4
         * (see {@link DepthProcessor}).
         * <p>
         * Spike status (issue #6, phase 0 device run — FIXED conclusions):
         * fp32 GO, verified on 5 AVDs spanning API 24/26/29 32-bit x86 and
         * newer (per-AVD latencies are recorded in the issue-#6 spike record;
         * the landscape photo runs in the seconds range on the 32-bit x86
         * emulators), NO 64-bit device gate needed. Preprocessing: the
         * aspect-PRESERVING lower-bound convention — scale =
         * max(518/w, 518/h), both dims rounded UP to the next multiple of 14
         * (518 = 14 * 37, the ViT patch grid of the export, both dims end at
         * or above 518), bicubic resize, then /255 and the per-channel
         * ImageNet mean/std normalization, fed pre-normalized
         * (blobFromImage scalefactor 1, scalar mean 0, swapRB false) because
         * blobFromImage only supports a SCALAR mean (the
         * AiImageDetectProcessor manual-normalization precedent). Variant
         * NO-GOs kept for re-evaluation: fp16 (~49.6 MB,
         * https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_fp16.onnx)
         * fails inside the net (fastNorm CV_16F failure / resize2 assertion),
         * int8 (~27.3 MB,
         * .../onnx/model_int8.onnx) parses but is 2-3x slower AND OOMs on the
         * 32-bit devices, and the newer external-data export repo
         * (depth-anything-v2-small-ONNX: a ~127 kB model.onnx + a ~98.8 MB
         * model.onnx_data sibling) loads to an EMPTY net in OpenCV 5. Memory:
         * ~166 MB native after the parse, ~480-550 MB native retained after
         * the forwards and GC (the dnn workspace) — the release-on-dispose +
         * nudge pattern is MANDATORY (the AI-detector/SAM precedent, see the
         * mDepthProcessors javadoc), this is why the processor cache here is
         * session-lifetime instead of app-lifetime.
         */
        DEPTH_ESTIMATION("depth-estimation",
                "Depth Estimation (Depth Anything V2)",
                "~99 MB",
                "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"),
        /**
         * IS-Net general-use salient-object segmentation, the fp32 ONNX
         * export distributed by rembg
         * (https://github.com/danielgatis/rembg, upstream project
         * https://github.com/xuebinqin/DIS). Download URL: the OFFICIAL
         * canonical rembg release
         * https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx
         * with the file sha256 PINNED (60920e99c45464f2ba57bee2ad08c919a52
         * bbf852739e96947fbb4358c0d964a, 178,648,008 bytes) — the
         * ModelDownloader validates the downloaded bytes against the pin, so
         * any change of the file at the host fails the download
         * (tamper-evident; the server-driven HF ETag logic cannot
         * self-certify this way). Documentation note: the HF mirror
         * https://huggingface.co/jellybox/isnet-general-use was verified
         * byte-identical to this release during the issue-#4 spike.
         * <p>
         * Licence: Apache-2.0, primary source = the upstream
         * xuebinqin/DIS LICENSE.md (the isnet-general-use checkpoint was
         * trained and released by the DIS authors); the ONNX conversion is
         * distributed under the MIT licence of the rembg project.
         * <p>
         * Input: FIXED 1024x1024, RGB, aspect-DISTORTING resize (no
         * letterbox), rembg ISNetSession normalization (x/255 - 0.5) with
         * NO std division — different from the ImageNet-style
         * normalization of the rejected BiRefNet candidate — fed
         * pre-normalized through blobFromImage(scalefactor 1, scalar mean
         * 0, swapRB false, the manual-normalization convention). Output: a
         * single-channel CV_32F salience map, blob shape (1, 1, 1024,
         * 1024), extracted via {@code Dnn.imagesFromBlob}; postprocess =
         * min-max normalize to 0..255 (the DIS inference.py convention,
         * NOT a sigmoid — the output is a salience score, not a logit).
         * <p>
         * Spike record (issue #4, phase 0 device runs — FIXED
         * conclusions). Candidate 1 BiRefNet-lite: NO-GO — the fixed
         * 1024² fp32 graph holds ~404 MB native after the parse (20.4 s)
         * and its Swin forward workspace blew past 2 GB within seconds,
         * lmk process death on the 2 GB 32-bit AND the 3 GB 64-bit AVDs;
         * the fp16 variant is a functional NO-GO (fastNorm CV_16F
         * failure, the depth-spike precedent); URLs kept for the record:
         * https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx
         * (+ model_fp16.onnx). Candidate 2 (shipped) IS-Net general-use:
         * GO — 2 GB 32-bit tier peak native ~1.04 GB, cold forward 7.4 s /
         * warm ~5.5 s; 3 GB 64-bit tier peak ~1.02 GB, cold 3.6 s / warm
         * ~2.2 s (emulated x86; no 64-bit device gate needed).
         * <p>
         * Memory law: ~1 GB dnn workspace retained after the forwards —
         * SESSION-LIFETIME processor, release-on-dispose MANDATORY (the
         * DepthProcessor precedent, release +
         * {@link #nudgeNativeReclaim()}). Teardown is safe (64-bit proven:
         * 1022 → 22 MB reclaim after the nudge, the reloaded net forwarded
         * fine) BUT on the 2 GB tier a release→reload inside one session
         * was lmk-killed (old-workspace reclaim latency stacked with the
         * new parse, ~1549 MB resident at death) — the recreation after a
         * release therefore settles the native heap before the re-parse
         * (bounded poll, see {@link #getSubjectSegProcessor()}), and the
         * page layer catches OOM/CvException with the toast self-heal (the
         * MagicEraser pattern) as the last resort.
         */
        SUBJECT_SEG("subject-segmentation",
                "Subject Background Removal (ISNet)",
                "~171 MB",
                "https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-general-use.onnx",
                "60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a",
                178_648_008L);

        private final String mId;
        private final String mDisplayName;
        private final String mDisplaySize;
        private final String mDownloadUrl;
        private final String mPinnedSha256;
        private final long mPinnedLength;

        ModelType(String id, String displayName, String displaySize, String downloadUrl) {
            this(id, displayName, displaySize, downloadUrl, null, 0L);
        }

        ModelType(String id, String displayName, String displaySize, String downloadUrl,
                  String pinnedSha256, long pinnedLength) {
            mId = id;
            mDisplayName = displayName;
            mDisplaySize = displaySize;
            mDownloadUrl = downloadUrl;
            mPinnedSha256 = pinnedSha256;
            mPinnedLength = pinnedLength;
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
         * Pinned expected file sha256 hex digest used by the ModelDownloader
         * to validate the downloaded bytes, or null when the model is not
         * pinned (then the server-provided metadata is validated instead)
         */
        public String getPinnedSha256() {
            return mPinnedSha256;
        }

        /**
         * Pinned expected file length in bytes, or 0 when unknown.
         * Only meaningful together with a non-null {@link #getPinnedSha256()}
         */
        public long getPinnedLength() {
            return mPinnedLength;
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
