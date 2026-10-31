package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import m.co.rh.id.a_jarwis.ml_engine.model.ModelCatalog;
import m.co.rh.id.a_jarwis.ml_engine.model.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

/**
 * Instrumented test of {@link AiImageDetectProcessor}, which will execute on
 * an Android device (model auto-downloaded via {@link ModelTestHelper}).
 * It verifies the release contract of a directly constructed processor
 * (never through the shared cache): classify works, a released processor
 * refuses further inference (fail fast instead of touching the freed
 * native net) and releasing more than once is a safe no-op.
 * <p>
 * The model-evaluation methodology and the measurement results of the
 * issue-#12 spike (round 1: the jacoballessio distilled ViT, NO-GO
 * 2026-10-31 with 0/8 synthetic assets detected on-device; round 2: the
 * CommunityForensics DeepfakeDet-ViT, GO) are recorded in the project's
 * spike records, not duplicated here.
 */
@RunWith(AndroidJUnit4.class)
public class AiImageDetectProcessorTest {

    private static Provider mMLEngineProvider;
    private static ILogger mLogger;

    @BeforeClass
    public static void beforeAnyTest() throws IOException {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mMLEngineProvider = Provider.createProvider(appContext, new MLEngineTestProviderModule());
        ModelTestHelper.installModels(mMLEngineProvider, ModelType.AI_IMAGE_DETECT);
        mLogger = mMLEngineProvider.get(ILogger.class);
    }

    @AfterClass
    public static void afterAnyTest() {
        // if provider null it means there is problem creating it
        assertNotNull(mMLEngineProvider);
        // the provider dispose cascade releases the cached processor
        // (see MLEngineInstance.dispose), the suite stays retry safe
        mMLEngineProvider.dispose();
    }

    /**
     * The release contract of a directly constructed
     * {@link AiImageDetectProcessor}: it must return a finite pFake, must
     * refuse further inference once released (fail fast instead of touching
     * the freed native net) and releasing more than once must be a safe
     * no-op. The processor is constructed directly (never through the
     * engine instance) so the cached instance behind
     * {@link #mMLEngineProvider} is never touched and the released instance
     * is discarded here.
     */
    @Test
    public void aiImageDetectProcessor_releaseContract() {
        Context context = mMLEngineProvider.getContext();
        AiImageDetectProcessor processor = new AiImageDetectProcessor(
                ModelCatalog.getModelFile(context, ModelType.AI_IMAGE_DETECT)
                        .getAbsolutePath(), mLogger);
        Bitmap input = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        input.eraseColor(Color.RED);
        try {
            float pFake = processor.classify(input);
            assertTrue("pFake must be finite in 0..1, got " + pFake,
                    !Float.isNaN(pFake) && pFake >= 0f && pFake <= 1f);
            processor.release();
            try {
                processor.classify(input);
                fail("a released processor must refuse inference");
            } catch (IllegalStateException expected) {
                // the documented released-processor contract
            }
            // releasing more than once is a safe no-op
            processor.release();
        } finally {
            input.recycle();
            // the released net wrapper is deleted by its finalizer, nudge
            // the runtime so the native reclamation happens here instead of
            // an arbitrary later GC (same hygiene as the release contract
            // test of PersonSegEngineTest)
            System.gc();
            Runtime.getRuntime().runFinalization();
        }
    }
}
