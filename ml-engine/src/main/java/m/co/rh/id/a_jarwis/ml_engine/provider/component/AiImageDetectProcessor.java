package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import m.co.rh.id.alogger.ILogger;

/**
 * Processor of the CommunityForensics DeepfakeDet-ViT binary deepfake
 * detector: a ViT-S/16 (hidden size 384, 6 heads, 12 layers, patch 16,
 * intermediate 1536) trained on 2.7M fakes spanning 4,803 generators
 * against 2.7M reals with JPEG/resize/blur augmentation (CVPR 2025,
 * arXiv:2411.04125, paper cross-generator OOD eval 0.987 mAP / 0.892
 * accuracy), loaded from the corrected fp32 ONNX export of
 * buildborderless/CommunityForensics-DeepfakeDet-ViT (MIT, licence primary
 * source = the upstream GitHub LICENSE file, Copyright 2025 Jeongsoo Park).
 * The weights are the CORRECTED 2026-07-22 regeneration of the export: the
 * stale artifacts (the deprecated ...-ONNX repos and the onnx_legacy/
 * folder of the model repo) hold WRONG weights and must never be used or
 * re-exported.
 * <p>
 * The model consumes a 384x384 RGB image, CLIP-normalized. The
 * preprocessing here mirrors the model card's reference snippet verbatim:
 * shortest-edge resize to 440 (bicubic, aspect ratio preserved), center
 * crop to 384x384, per-channel (x/255 - mean) / std with the CLIP values.
 * It emits ONE logit (num_labels=1, a sigmoid head).
 * <p>
 * The contract of {@link #classify(Bitmap)} is the probability that the
 * image is AI-generated: pFake in 0..1 (the sigmoid of the single output
 * logit), 1 - pFake is the real probability. The threshold policy (which
 * pFake counts as AI-generated) is deliberately NOT part of this
 * processor — the page-side band constants in AiDetectPage pick the
 * operating point (LIKELY_AI &gt;= 0.75, LIKELY_REAL &lt;= 0.35),
 * calibrated by the issue-#12 on-device measurement.
 * <p>
 * The fp32 build is the only viable candidate: the quantized variants are
 * a double NO-GO — OpenCV DNN cannot load them, and the model card itself
 * documents 10-70 percentage-point OOD divergence for the int8/q4 builds.
 * The constructor silently falls back to the classic OpenCV DNN engine
 * when the default engine cannot load the graph (a thrown load error or
 * an empty net); which engine actually runs the graph is observable
 * through behavior only, the finding is recorded in issue #12.
 * <p>
 * SPIKE (issue #12): round 1 (the jacoballessio distilled ViT, 224 input,
 * 2-logit head) measured 0/8 synthetic assets detected on-device (pFake
 * 0.39-0.44, all leaning real) with 0 false positives on the real group —
 * NO-GO 2026-10-31, the model was swapped to this checkpoint. Round 2 ran
 * on the same 20 curated images (api24 x86 32-bit AVD): detection 5/5 on
 * the 2026-era diffusion samples (pFake 0.986-1.000) and 2/3 on the
 * era-matched probes (StyleGAN face 1.000, StyleGAN2 cat 0.999, the 2022
 * Midjourney "Space opera" artwork 0.300 — the single false negative),
 * 0/9 false positives on the real+compressed group (max 0.009), 0/3 on
 * screenshots (max 0.092), latency mean 605 ms / max 690 ms per image,
 * ~278 MB native retained after the inference.
 * <p>
 * FINAL VERDICT (round 2): GO — the model shipped.
 * <p>
 * A processor instance must never have its {@link #classify(Bitmap)}
 * invoked concurrently, callers are expected to serialize their calls on
 * one instance (e.g. through a single-thread executor). Once
 * {@link #release()} is invoked the instance is dead: it must be discarded
 * and never used again, the {@link MLEngineInstance} cache recreates a fresh
 * processor on demand.
 */
class AiImageDetectProcessor {
    private static final String TAG = "AiImageDetectProcessor";
    /**
     * The model input resolution, the center-crop target of the model
     * card's reference preprocessing: the aspect-preserving shortest-edge
     * resize to {@link #RESIZE_SHORTEST_EDGE} happens first, the central
     * square is cropped from it (no letterbox padding anywhere in the
     * pipeline)
     */
    private static final int MODEL_INPUT_SIZE = 384;
    /**
     * Shortest-edge resize target of the model card's reference
     * preprocessing: the image is scaled so its shorter side is this long
     * (aspect ratio preserved, bicubic like the card's pipeline), then the
     * central {@link #MODEL_INPUT_SIZE} square is cropped
     */
    private static final int RESIZE_SHORTEST_EDGE = 440;
    /**
     * CLIP normalization of the model input (the values from the model
     * card, RGB channel order), applied per channel after the 0..1 scaling:
     * (x/255 - mean) / std. The normalization is done manually because
     * blobFromImage only supports a SCALAR mean
     */
    private static final double[] CLIP_MEAN = {0.4815, 0.4578, 0.4082};
    private static final double[] CLIP_STD = {0.2686, 0.2613, 0.2758};
    /**
     * The model emits exactly 1 logit (num_labels=1, a sigmoid head): the
     * logit of the AI-generated ("fake") class, pFake = sigmoid(logit)
     */
    private static final int LOGIT_COUNT = 1;

    private final ILogger mLogger;
    private Net mModelNet;
    private final String mTag;

    public AiImageDetectProcessor(String modelPath, ILogger logger) {
        mLogger = logger;
        mTag = modelPath.substring(modelPath.lastIndexOf("/") + 1);
        Net net = null;
        try {
            net = Dnn.readNetFromONNX(modelPath);
            if (net.empty()) {
                // readNetFromONNX does not reliably throw on an unparseable
                // graph, an empty net is its silent failure form
                net = null;
            }
        } catch (Exception e) {
            // spike checkpoint (issue #12): the default-engine failure is a
            // documented spike finding, not a crash — the classic engine is
            // retried below
            mLogger.e(TAG, "Default DNN engine failed to load " + mTag, e);
            net = null;
        }
        if (net == null) {
            try {
                net = Dnn.readNetFromONNX(modelPath, Dnn.ENGINE_CLASSIC);
            } catch (Exception e) {
                // the classic retry is the last resort, its failure (e.g. a
                // CvException of an unparseable graph) must surface as the
                // documented both-engines contract, not as a raw OpenCV
                // exception escaping the constructor
                throw new IllegalStateException("Failed to load " + mTag
                        + " with both the default and the classic DNN engine", e);
            }
            if (net.empty()) {
                throw new IllegalStateException("Failed to load " + mTag
                        + " with both the default and the classic DNN engine");
            }
        }
        mModelNet = net;
    }

    /**
     * Release the native network of this processor: the parsed model weights
     * plus the dnn workspace OpenCV retains inside the net after the
     * forward() calls. The OpenCV 5.0.0.1 Java bindings expose no public
     * native delete for cv::dnn::Net ({@code Net.finalizeNet()} only prepares
     * the inference session, it frees nothing), the native peer is deleted by
     * the net wrapper's finalizer once this processor drops its reference —
     * {@link MLEngineInstance} nudges the runtime right after releasing so
     * the reclamation happens at that predictable point instead of an
     * arbitrary later GC.
     * <p>
     * A released processor is dead: it must be discarded and never used
     * again, the {@link MLEngineInstance} cache recreates a fresh processor
     * on demand (the model file is re-parsed, a seconds-long operation).
     * Releasing more than once is a safe no-op. Synchronized on the same
     * monitor as {@link #classify(Bitmap)}, so a release can never run
     * concurrently with an inference (that would be a native use-after-free
     * when a provider dispose races an in-flight task).
     */
    public synchronized void release() {
        if (mModelNet == null) {
            return;
        }
        // this was the last strong reference to the net wrapper
        mModelNet = null;
    }

    /**
     * A released processor holds no net anymore: using it would be a bug of
     * the caller (a released instance must be discarded, the engine instance
     * recreates it on demand), fail fast with a clear message instead of a
     * null native pointer deep inside the inference
     */
    private void checkNotReleased() {
        if (mModelNet == null) {
            throw new IllegalStateException(TAG
                    + " already released, a released processor must be"
                    + " discarded (MLEngineInstance recreates it on demand)");
        }
    }

    /**
     * Classify the given bitmap: probability that the image is AI-generated.
     *
     * @param input bitmap to be classified, never recycled by this method
     * @return pFake in 0..1, the sigmoid of the model's single output logit
     * (the probability of the AI-generated class), 1 - result is the
     * probability of the real class
     */
    public synchronized float classify(Bitmap input) {
        checkNotReleased();
        Mat rgba = null;
        Mat rgb = null;
        Mat resized = null;
        Mat crop = null;
        Mat crop32 = null;
        Mat normalized = null;
        Mat blob = null;
        Mat output = null;
        float pFake;
        try {
            // bitmapToMat produces an RGBA-ordered CV_8UC4 (verified OpenCV 5 behavior)
            rgba = new Mat();
            Utils.bitmapToMat(input, rgba);
            rgb = new Mat();
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            // shortest-edge resize to RESIZE_SHORTEST_EDGE preserving the
            // aspect ratio, bicubic like the card's reference pipeline
            double scale = RESIZE_SHORTEST_EDGE
                    / (double) Math.min(input.getWidth(), input.getHeight());
            int resizedWidth = (int) Math.round(input.getWidth() * scale);
            int resizedHeight = (int) Math.round(input.getHeight() * scale);
            resized = new Mat();
            Imgproc.resize(rgb, resized, new Size(resizedWidth, resizedHeight),
                    0, 0, Imgproc.INTER_CUBIC);
            // center crop to the model input size (see MODEL_INPUT_SIZE);
            // a submat shares the parent data, the clone detaches so the
            // resized buffer can be released
            int left = (resizedWidth - MODEL_INPUT_SIZE) / 2;
            int top = (resizedHeight - MODEL_INPUT_SIZE) / 2;
            crop = resized.submat(top, top + MODEL_INPUT_SIZE,
                    left, left + MODEL_INPUT_SIZE).clone();
            // per-channel CLIP normalization, mirroring the model card's
            // reference snippet verbatim: (x/255 - mean) / std per channel.
            // Done manually because blobFromImage only supports a SCALAR
            // mean: first the 0..1 scaling, then the split channels are
            // converted with out = in * (1/std) - mean/std (one convertTo
            // per channel), then merged back
            crop32 = new Mat();
            crop.convertTo(crop32, CvType.CV_32F, 1.0 / 255.0, 0.0);
            normalized = new Mat();
            List<Mat> channels = new ArrayList<>();
            Core.split(crop32, channels);
            try {
                for (int c = 0; c < channels.size(); c++) {
                    channels.get(c).convertTo(channels.get(c), CvType.CV_32F,
                            1.0 / CLIP_STD[c], -CLIP_MEAN[c] / CLIP_STD[c]);
                }
                Core.merge(channels, normalized);
            } finally {
                for (Mat channel : channels) {
                    channel.release();
                }
            }
            // the Mat is already RGB-ordered (COLOR_RGBA2RGB above) and
            // fully normalized, so swapRB stays false (matching the card's
            // RGB input) and blobFromImage only wraps it into the NCHW blob
            // at the model input size
            blob = Dnn.blobFromImage(normalized, 1.0,
                    new Size(MODEL_INPUT_SIZE, MODEL_INPUT_SIZE),
                    new Scalar(0, 0, 0), false, false);
            mModelNet.setInput(blob);
            output = mModelNet.forward();
            float[] logits = new float[LOGIT_COUNT];
            // the logit is read through the reshaped 1x1 2-D view: bulk get
            // on n-dimensional Mats silently reads zeros in the OpenCV
            // 5.0.0.1 Java bindings (found by the EfficientSamProcessor
            // spike), reshape shares the continuous blob memory without a
            // copy
            output.reshape(1, 1).get(0, 0, logits);
            pFake = fakeProbability(logits[0]);
        } finally {
            // every Mat is released on every path: an inference failing with a
            // native "Insufficient memory" CvException must not leak the
            // (multi-megabyte) image blob
            if (rgba != null) {
                rgba.release();
            }
            if (rgb != null) {
                rgb.release();
            }
            if (resized != null) {
                resized.release();
            }
            if (crop != null) {
                crop.release();
            }
            if (crop32 != null) {
                crop32.release();
            }
            if (normalized != null) {
                normalized.release();
            }
            if (blob != null) {
                blob.release();
            }
            if (output != null) {
                output.release();
            }
        }
        return pFake;
    }

    /**
     * Numerically stable sigmoid of the single output logit, returning the
     * probability of the AI-generated ("fake") class: for a non-negative
     * logit the exp() of the mirrored (negative) argument is safe from
     * overflow, for a negative logit the plain form is (exp(logit)
     * underflows to 0 gracefully, sigmoid just saturates at the correct
     * limit)
     */
    private static float fakeProbability(float logit) {
        if (logit >= 0) {
            double expNeg = Math.exp(-logit);
            return (float) (1.0 / (1.0 + expNeg));
        }
        double expPos = Math.exp(logit);
        return (float) (expPos / (1.0 + expPos));
    }
}
