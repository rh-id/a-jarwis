package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.io.IOException;
import java.io.InputStream;

import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.test.R;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of {@link SubjectSegProcessor}, which will execute on an
 * Android device (model auto-downloaded via {@link ModelTestHelper}),
 * verifying the salience mask computation (dimensions, value range,
 * subject/non-subject gate, interior hardening, raw-signal contrast gate)
 * and the release contract of a directly constructed processor. The
 * synthetic input is the deterministic subject-on-contrast-background
 * composition proven to segment in the issue-#4 SPIKE #2 device runs; the
 * real-photo coverage is the two bundled portrait fixtures, both crops of
 * the SAME Pexels photo 7273411 by kaboompics (person in a white shirt
 * against a white wall — the AC2 anchor, the lowest subject–background
 * VISUAL contrast of the fixture set, NOT the lowest raw salience
 * contrast): subject_seg_portrait.jpg is the full 1600x2400 frame and
 * subject_seg_portrait_closeup.jpg is an 800x1300 crop of the same photo
 * with the head + shirt filling ~85% of the frame (subject touching the
 * frame edges, the full-frame-subject over-rejection probe). Pexels
 * License: free to use, commercial use allowed, attribution not required.
 * <p>
 * The {@link org.junit.FixMethodOrder} NAME_ASCENDING order is load-bearing
 * (the MagicEraserTest pattern): the value tests run first on the shared
 * engine-cached processor and the release-contract test runs LAST,
 * deliberately freeing the engine cache first so its ~1 GB dnn workspace
 * never stacks under the directly constructed processor's own forward
 * workspace on the memory-constrained tiers.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SubjectSegProcessorTest {

    private static final String TAG = "SubjectSegProcessorTest";
    /**
     * Normalized frame position of the shirt interior probe of the portrait
     * fixture: the person is centered and the white shirt spans roughly the
     * bottom third of the frame, so the probe around (0.5, 0.85) was chosen
     * to lie fully inside the shirt — far from the feathered boundary,
     * where a legitimate dip would defeat the probe
     */
    private static final float SHIRT_PROBE_X = 0.5f;
    private static final float SHIRT_PROBE_Y = 0.85f;
    /**
     * Half size (pixels) of the square alpha probe block of the shirt
     * interior test (the SubjectSegEngineTest PROBE_HALF_SIZE pattern): a
     * block MINIMUM instead of a single pixel so one stray soft pixel can
     * never fail the probe, and instead of an exact histogram so FP
     * reordering inside the OpenCV kernels cannot flip it
     */
    private static final int SHIRT_PROBE_HALF_SIZE = 16;
    /**
     * Exclusive lower bound of "the shirt interior is a hardened opaque
     * plateau" (the interior-hardening acceptance criterion): before the
     * hardening the translucent ISNet fabric interior carries the white
     * wall through the shirt folds, the hardened interior must stay at
     * (nearly) full alpha
     */
    private static final int HARDENED_SHIRT_MIN_ALPHA = 250;

    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider, ModelType.SUBJECT_SEG);
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // release the engine-cached processor and nudge the runtime BEFORE
        // the provider dispose: the ~1 GB native dnn workspace must not be
        // inherited by the heavy suites that run later in the same
        // instrumentation process (the DepthProcessorTest hygiene pattern —
        // a plain dispose cascade release + single nudge proved not enough
        // there, the explicit gc/runFinalization prelude is the
        // FaceEngineTest release precedent)
        mMLEngineProvider.get(MLEngineInstance.class).releaseSubjectSegProcessor();
        System.gc();
        Runtime.getRuntime().runFinalization();
        mMLEngineProvider.dispose();
    }

    /**
     * The mask is the min-max normalized salience back-scaled to the input
     * bitmap resolution, not a sigmoid of logits: a single 8bit channel
     * spanning the full 0..255 range (the min-max pins the raw extremes to
     * 0 and 255, the feather only softens the segmentation edge band) with
     * a clear background (low) and subject (high) separation.
     */
    @Test
    public void computeMask_matchesInputDimensionsAndRange() {
        Bitmap bitmap = buildSubjectBitmap(1024, 1024);
        SubjectSegProcessor processor = cachedProcessor();
        Mat mask = processor.computeMask(bitmap);
        try {
            // upsampled to the input bitmap resolution for compositing
            assertEquals(bitmap.getWidth(), mask.cols());
            assertEquals(bitmap.getHeight(), mask.rows());
            assertEquals(1, mask.channels());
            assertEquals(CvType.CV_8U, mask.depth());
            // scan every pixel (CV_8U single channel bulk row read; the map
            // is a 2-D Mat, the documented OpenCV 5 zero-read trap only
            // affects n-dimensional blobs)
            int cols = mask.cols();
            int rows = mask.rows();
            byte[] row = new byte[cols];
            int min = 255;
            int max = 0;
            for (int y = 0; y < rows; y++) {
                mask.get(y, 0, row);
                for (int x = 0; x < cols; x++) {
                    int value = row[x] & 0xFF;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            assertTrue("mask min pixel " + min + " below 0", min >= 0);
            assertTrue("mask max pixel " + max + " above 255", max <= 255);
            assertTrue("mask is flat (min " + min + ", max " + max
                            + "), the salience separation got lost",
                    max - min > 200);
            assertTrue("no subject pixel found (max " + max + ")", max >= 250);
            assertTrue("no background pixel found (min " + min + ")", min <= 5);
        } finally {
            mask.release();
            bitmap.recycle();
        }
    }

    /**
     * The synthetic subject-on-contrast-background composition (proven to
     * segment in the SPIKE #2 runs) must be reported as containing a
     * subject: the segmented rect covers far more than the minimum subject
     * area ratio.
     */
    @Test
    public void hasSubject_trueOnSyntheticSubject() {
        Bitmap bitmap = buildSubjectBitmap(1024, 1024);
        SubjectSegProcessor processor = cachedProcessor();
        Mat mask = processor.computeMask(bitmap);
        try {
            assertTrue("synthetic subject image must contain a subject",
                    SubjectSegProcessor.hasSubject(mask));
        } finally {
            mask.release();
            bitmap.recycle();
        }
    }

    /**
     * The subject area gate itself (deterministic, no model involved):
     * an empty mask and a mask covering less than the
     * {@link SubjectSegProcessor#MIN_SUBJECT_AREA_RATIO} of the image area
     * are rejected, a mask covering clearly more is accepted.
     */
    @Test
    public void hasSubject_areaGateSyntheticMats() {
        // fully empty mask: no subject
        Mat empty = new Mat(256, 256, CvType.CV_8UC1, Scalar.all(0));
        try {
            assertFalse("an empty mask must not count as a subject",
                    SubjectSegProcessor.hasSubject(empty));
        } finally {
            empty.release();
        }
        // a tiny dot below the area gate (0.01% of the area < 0.2%)
        Mat speck = new Mat(256, 256, CvType.CV_8UC1, Scalar.all(0));
        try {
            speck.rowRange(128, 130).colRange(128, 130).setTo(Scalar.all(255));
            assertFalse("a sub-threshold speck must not count as a subject",
                    SubjectSegProcessor.hasSubject(speck));
        } finally {
            speck.release();
        }
        // a solid block above the area gate (1% of the area > 0.2%)
        Mat block = new Mat(256, 256, CvType.CV_8UC1, Scalar.all(0));
        try {
            block.rowRange(100, 156).colRange(100, 156).setTo(Scalar.all(255));
            assertTrue("a 1% block must count as a subject",
                    SubjectSegProcessor.hasSubject(block));
        } finally {
            block.release();
        }
    }

    /**
     * The lowest subject–background VISUAL contrast anchor (the bundled
     * portrait fixture: a person in a white shirt against a white wall,
     * the AC2 scenario — visually the weakest subject of the acceptance
     * set, while its RAW salience contrast 0.785 is NOT the lowest: dog
     * 0.583, shoe 0.643 and the bundled close-up 0.685 are lower) must
     * survive the raw-signal contrast gate: the mask is NOT zeroed and
     * the verdict stays true — the over-rejection guard of the max-mean
     * gate on a genuine but visually weak subject.
     */
    @Test
    public void computeMask_portraitFindsSubject() {
        Bitmap bitmap = decodeTestImage(R.raw.subject_seg_portrait);
        SubjectSegProcessor processor = cachedProcessor();
        Mat mask = processor.computeMask(bitmap);
        try {
            assertTrue("the lowest-contrast real subject anchor must not be"
                            + " zeroed by the raw-signal contrast gate (nonzero"
                            + " pixels " + Core.countNonZero(mask) + ")",
                    Core.countNonZero(mask) > 0);
            assertTrue("the portrait must be reported as containing a"
                    + " subject", SubjectSegProcessor.hasSubject(mask));
        } finally {
            mask.release();
            bitmap.recycle();
        }
    }

    /**
     * The interior hardening, the mandatory acceptance criterion: the shirt
     * interior of the portrait fixture — translucent ISNet output before
     * the fix, the white wall bleeding through the fabric folds — must come
     * out a (nearly) fully opaque plateau AFTER the harden + feather +
     * upsample tail. The probe is a BLOCK minimum inside the shirt (the
     * SubjectSegEngineTest PROBE_HALF_SIZE pattern, stable under FP
     * reordering — never a single pixel and never an exact-histogram
     * assertion): the person is centered and the shirt spans roughly the
     * bottom third of the frame, so the block around (0.5, 0.85) was chosen
     * to lie fully inside the shirt.
     */
    @Test
    public void computeMask_portraitShirtInteriorHardened() {
        Bitmap bitmap = decodeTestImage(R.raw.subject_seg_portrait);
        SubjectSegProcessor processor = cachedProcessor();
        Mat mask = processor.computeMask(bitmap);
        try {
            int minAlpha = maskBlockMinAlpha(mask, SHIRT_PROBE_X,
                    SHIRT_PROBE_Y, SHIRT_PROBE_HALF_SIZE);
            mLogger.d(TAG, "portrait shirt probe min alpha=" + minAlpha);
            assertTrue("the hardened shirt interior must be an opaque"
                            + " plateau (block min alpha " + minAlpha + ")",
                    minAlpha >= HARDENED_SHIRT_MIN_ALPHA);
        } finally {
            mask.release();
            bitmap.recycle();
        }
    }

    /**
     * The full-frame-subject over-rejection probe (the bundled close-up
     * fixture: an 800x1300 crop of the SAME portrait photo, head + shirt
     * filling ~85% of the frame, the subject touching the frame edges): a
     * full-frame subject pushes the raw salience mean against its max, the
     * documented over-rejection class of the max-mean contrast gate — the
     * mask must NOT be zeroed and the verdict must stay true.
     */
    @Test
    public void computeMask_portraitCloseupFullFrameSubjectKept() {
        Bitmap bitmap = decodeTestImage(R.raw.subject_seg_portrait_closeup);
        SubjectSegProcessor processor = cachedProcessor();
        Mat mask = processor.computeMask(bitmap);
        try {
            assertTrue("the full-frame subject must not be zeroed by the"
                            + " raw-signal contrast gate (nonzero pixels "
                            + Core.countNonZero(mask) + ")",
                    Core.countNonZero(mask) > 0);
            assertTrue("the full-frame close-up must still be reported as"
                    + " containing a subject",
                    SubjectSegProcessor.hasSubject(mask));
        } finally {
            mask.release();
            bitmap.recycle();
        }
    }

    /**
     * The raw-signal contrast gate on the shapeless input it was built
     * for: a uniform gray frame carries no salience peak, its min-max
     * normalization used to inflate the shapeless noise into a
     * full-contrast mask that PASSED the 0.002 area gate with the measured
     * ratio 0.0056 — the mask must now be all-zero and the verdict false.
     */
    @Test
    public void computeMask_uniformGrayRejectedByContrastGate() {
        Bitmap bitmap = Bitmap.createBitmap(1024, 1024,
                Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(128, 128, 128));
        SubjectSegProcessor processor = cachedProcessor();
        Mat mask = processor.computeMask(bitmap);
        try {
            assertEquals("a uniform gray frame must produce an all-zero"
                            + " mask (nonzero pixels "
                            + Core.countNonZero(mask) + ")",
                    0, Core.countNonZero(mask));
            assertFalse("a uniform gray frame must not count as a subject",
                    SubjectSegProcessor.hasSubject(mask));
        } finally {
            mask.release();
            bitmap.recycle();
        }
    }

    /**
     * The raw-signal contrast gate on the second shapeless class: a
     * subject-less linear gradient (the same ingredients as the synthetic
     * composition's background, WITHOUT any subject shape) softly grades
     * the salience map, its min-max stretch used to pass the 0.002 area
     * gate with the measured ratios 0.0022-0.0049 — the mask must now be
     * all-zero and the verdict false.
     */
    @Test
    public void computeMask_linearGradientRejectedByContrastGate() {
        Bitmap bitmap = buildGradientBitmap(1024, 1024);
        SubjectSegProcessor processor = cachedProcessor();
        Mat mask = processor.computeMask(bitmap);
        try {
            assertEquals("a subject-less linear gradient must produce an"
                            + " all-zero mask (nonzero pixels "
                            + Core.countNonZero(mask) + ")",
                    0, Core.countNonZero(mask));
            assertFalse("a subject-less linear gradient must not count as"
                    + " a subject", SubjectSegProcessor.hasSubject(mask));
        } finally {
            mask.release();
            bitmap.recycle();
        }
    }

    /**
     * The shared segmentation tail feathers at the model resolution BEFORE
     * the upsample: the result carries the target dimensions and keeps the
     * soft edge band (intermediate values between the pure background 0 and
     * the pure subject 255).
     */
    @Test
    public void segMaskPostprocess_feathersThenUpsamples() {
        Mat mask = new Mat(1024, 1024, CvType.CV_8UC1, Scalar.all(0));
        try {
            // a solid subject block well inside the frame
            mask.rowRange(400, 624).colRange(400, 624).setTo(Scalar.all(255));
            SegMaskPostprocess.featherAndUpsample(mask, 1024, 512, 256);
            assertEquals(512, mask.cols());
            assertEquals(256, mask.rows());
            assertEquals(1, mask.channels());
            assertEquals(CvType.CV_8U, mask.depth());
            byte[] row = new byte[mask.cols()];
            int min = 255;
            int max = 0;
            int softPixelCount = 0;
            for (int y = 0; y < mask.rows(); y++) {
                mask.get(y, 0, row);
                for (int x = 0; x < mask.cols(); x++) {
                    int value = row[x] & 0xFF;
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                    if (value > 0 && value < 255) {
                        softPixelCount++;
                    }
                }
            }
            assertTrue("postprocessed mask lost the subject plateau (max "
                    + max + ")", max >= 250);
            assertTrue("postprocessed mask lost the background (min " + min
                    + ")", min <= 5);
            assertTrue("postprocessed mask has no soft edge ("
                    + softPixelCount + " soft pixels)", softPixelCount > 0);
        } finally {
            mask.release();
        }
    }

    /**
     * The release contract of a directly constructed
     * {@link SubjectSegProcessor}: it must refuse further inference once
     * released (fail fast BEFORE touching the native net) and releasing
     * more than once must be a safe no-op. The "processor produces a
     * usable mask before release" half of the contract is proven by the
     * value tests above (they run through the same processor class via the
     * engine cache); THIS test deliberately runs NO forward of its own —
     * the first device run was lmk-killed exactly there: its 1024² forward
     * stacked a second ~820 MB workspace under the not-yet-reclaimed ~1 GB
     * engine workspace (the 2 GB tier's post-release reclaim latency, the
     * SPIKE #2 teardown evidence), while a parse-only second instance
     * (~190 MB) always fits. Runs LAST even alphabetically, after the
     * engine cache has been freed.
     */
    @Test
    public void subjectSegProcessor_releaseContract() {
        // free the engine cache first (best effort, the 2 GB tier's
        // reclaim may lag — the parse-only second instance fits regardless)
        mMLEngineProvider.get(MLEngineInstance.class).releaseSubjectSegProcessor();
        System.gc();
        Runtime.getRuntime().runFinalization();
        SubjectSegProcessor processor = new SubjectSegProcessor(
                mMLEngineProvider.get(MLEngineInstance.class)
                        .getModelFile(ModelType.SUBJECT_SEG)
                        .getAbsolutePath(), mLogger);
        try {
            processor.release();
            try {
                // the refusal must fire BEFORE any native work: the net
                // reference is null, an inference would be a native
                // use-after-free if the guard did not exist
                processor.computeMask(Bitmap.createBitmap(64, 64,
                        Bitmap.Config.ARGB_8888));
                fail("a released processor must refuse inference");
            } catch (IllegalStateException expected) {
                // the documented released-processor contract
            }
            // releasing more than once is a safe no-op
            processor.release();
        } finally {
            // the released net wrapper is deleted by its finalizer, nudge
            // the runtime so the native reclamation happens here instead of
            // an arbitrary later GC (same hygiene as the PersonSegEngineTest
            // release contract test)
            System.gc();
            Runtime.getRuntime().runFinalization();
        }
    }

    /**
     * The engine-cached processor (created through the engine instance, the
     * package-private cache path the engine methods use)
     */
    private static SubjectSegProcessor cachedProcessor() {
        return mMLEngineProvider.get(MLEngineInstance.class)
                .getSubjectSegProcessor();
    }

    /**
     * The deterministic subject-on-contrast-background composition of the
     * SPIKE #2 runs: diagonal gradient background, a solid high-chroma
     * subject rectangle, a bright overlapping circle and a dark top band.
     */
    private static Bitmap buildSubjectBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width, height,
                Color.rgb(240, 240, 245), Color.rgb(40, 44, 52),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);
        paint.setColor(Color.rgb(210, 60, 40));
        canvas.drawRect(width * 0.18f, height * 0.30f,
                width * 0.68f, height * 0.88f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(width * 0.52f, height * 0.42f,
                width * 0.10f, paint);
        paint.setColor(Color.rgb(20, 20, 24));
        canvas.drawRect(0, 0, width, height * 0.08f, paint);
        return bitmap;
    }

    /**
     * A subject-less linear gradient over the whole frame (the same
     * ingredients as {@link #buildSubjectBitmap}'s background, WITHOUT the
     * subject rectangle, circle and dark band): the shapeless input class
     * of the raw-signal contrast gate tests.
     */
    private static Bitmap buildGradientBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, width, height,
                Color.rgb(240, 240, 245), Color.rgb(40, 44, 52),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        return bitmap;
    }

    /**
     * Minimum alpha of the square probe block centred on the given
     * normalized frame position of the mask (the block-min probe is stable
     * under FP reordering, see SHIRT_PROBE_HALF_SIZE), clamped to the mask
     * bounds. Bulk row reads of the CV_8U mask (the 2-D Mat row-read
     * pattern; the OpenCV 5 zero-read trap only affects n-dimensional
     * blobs).
     */
    private static int maskBlockMinAlpha(Mat mask, float positionX,
                                         float positionY, int halfSize) {
        int centerX = Math.round(mask.cols() * positionX);
        int centerY = Math.round(mask.rows() * positionY);
        int xStart = Math.max(0, centerX - halfSize);
        int xEnd = Math.min(mask.cols() - 1, centerX + halfSize);
        int yStart = Math.max(0, centerY - halfSize);
        int yEnd = Math.min(mask.rows() - 1, centerY + halfSize);
        int min = 255;
        byte[] row = new byte[mask.cols()];
        for (int y = yStart; y <= yEnd; y++) {
            mask.get(y, 0, row);
            for (int x = xStart; x <= xEnd; x++) {
                min = Math.min(min, row[x] & 0xFF);
            }
        }
        return min;
    }

    /**
     * Decode a bundled androidTest raw fixture bitmap (the
     * DepthEngineTest.decodeTestImage pattern).
     */
    private static Bitmap decodeTestImage(int rawId) {
        Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        try (InputStream in = context.getResources().openRawResource(rawId)) {
            return BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to decode the raw"
                    + " fixture resource " + rawId, e);
        }
    }
}
