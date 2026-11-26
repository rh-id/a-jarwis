package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import m.co.rh.id.a_jarwis.ml_engine.provider.MLEngineTestProviderModule;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.MLEngineInstance.ModelType;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.ModelDownloadWorker;
import m.co.rh.id.aprovider.Provider;

/**
 * Self-contained instrumented test of the storage management flow
 * (ModelStorageManager + ModelUsageStore): it downloads the smallest
 * catalog model (face-detect, ~0.2 MB) through the ModelDownloader DIRECTLY
 * inside the test method, uses it (which marks the last-used store),
 * deletes it through ModelStorageManager and asserts every observable
 * outcome. It does NOT rely on the other test classes' installs and does
 * NOT leave a model deleted that another class installed: whatever was on
 * the disk before the test is restored through a final ModelDownloader
 * download before the provider is disposed (the ModelTestHelper-based
 * suites assume the models persist across the whole connectedCheck run).
 * Requires network access on the test device.
 */
@RunWith(AndroidJUnit4.class)
public class ModelStorageManagerTest {

    @Test
    public void deleteModel_fullFlow() throws Exception {
        Context appContext = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getTargetContext();
        Provider provider = Provider.createProvider(appContext,
                new MLEngineTestProviderModule());
        try {
            MLEngineInstance engineInstance = provider.get(MLEngineInstance.class);
            ModelStorageManager storageManager =
                    provider.get(ModelStorageManager.class);
            ModelUsageStore usageStore = provider.get(ModelUsageStore.class);
            ModelDownloader modelDownloader = provider.get(ModelDownloader.class);
            File modelFile = engineInstance.getModelFile(ModelType.FACE_DETECT);

            // download (idempotent when already present, so a repeat run or a
            // model left over by a previous test run never breaks this test)
            // and assert the file is really on the disk and reported available
            modelDownloader.download(appContext, ModelType.FACE_DETECT.getId());
            assertTrue("Model file missing after download: "
                            + modelFile.getAbsolutePath(),
                    modelFile.exists());
            assertTrue(engineInstance.isModelAvailable(ModelType.FACE_DETECT));

            // use the model through the real load path: requireModelFile
            // marks the last-used store as a side effect
            engineInstance.getFaceDetectModel();
            assertTrue("Last-used not marked by the real model load",
                    usageStore.getLastUsed(ModelType.FACE_DETECT.getId()) > 0);

            // delete and assert every observable outcome
            assertTrue(storageManager.deleteModel(ModelType.FACE_DETECT.getId()));
            assertFalse("Model file still exists after deleteModel",
                    modelFile.exists());
            assertFalse("Model still reported available after deleteModel",
                    engineInstance.isModelAvailable(ModelType.FACE_DETECT));
            assertEquals("Last-used not cleared by deleteModel",
                    0L, usageStore.getLastUsed(ModelType.FACE_DETECT.getId()));
            WorkManager workManager = provider.get(WorkManager.class);
            List<WorkInfo> workInfos = workManager.getWorkInfosForUniqueWork(
                            ModelDownloadWorker.uniqueWorkName(ModelType.FACE_DETECT.getId()))
                    .get(10, TimeUnit.SECONDS);
            assertNotNull(workInfos);
            for (WorkInfo workInfo : workInfos) {
                assertFalse("Download work still active after deleteModel",
                        workInfo.getState() == WorkInfo.State.ENQUEUED
                                || workInfo.getState() == WorkInfo.State.RUNNING);
            }

            // the unknown id contract
            try {
                storageManager.deleteModel("unknown-model-id");
                throw new AssertionError("Unknown model id did not throw");
            } catch (IllegalArgumentException expected) {
                // expected, the delete refuses ids outside the catalog
            }

            // restore the pre-test state: an existing model (installed by
            // another suite class) or the one deleted above is re-downloaded
            // (~0.2 MB), so this test never leaves face-detect missing
            modelDownloader.download(appContext, ModelType.FACE_DETECT.getId());
            assertTrue(engineInstance.isModelAvailable(ModelType.FACE_DETECT));
        } finally {
            provider.dispose();
        }
    }

    /**
     * Self-contained check of the storage page's download action:
     * startDownload must enqueue the model's unique download work (visible
     * as an ENQUEUED WorkInfo). The test WorkManager
     * (WorkManagerTestInitHelper) does NOT execute the worker, so no model
     * file appears and no network transfer runs — the assertion is purely
     * about the enqueue, no cleanup needed beyond the provider dispose (the
     * test WorkManager is per-provider, initialized by
     * MLEngineTestProviderModule)
     */
    @Test
    public void startDownload_enqueuesUniqueWork() throws Exception {
        Context appContext = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().getTargetContext();
        Provider provider = Provider.createProvider(appContext,
                new MLEngineTestProviderModule());
        try {
            ModelStorageManager storageManager =
                    provider.get(ModelStorageManager.class);
            WorkManager workManager = provider.get(WorkManager.class);

            // the unknown id contract mirrors deleteModel
            try {
                storageManager.startDownload("unknown-model-id");
                throw new AssertionError("Unknown model id did not throw");
            } catch (IllegalArgumentException expected) {
                // expected, the enqueue refuses ids outside the catalog
            }

            storageManager.startDownload(ModelType.FACE_DETECT.getId());
            List<WorkInfo> workInfos = workManager.getWorkInfosForUniqueWork(
                            ModelDownloadWorker.uniqueWorkName(
                                    ModelType.FACE_DETECT.getId()))
                    .get(10, TimeUnit.SECONDS);
            assertNotNull(workInfos);
            boolean hasEnqueuedWork = false;
            for (WorkInfo workInfo : workInfos) {
                if (workInfo.getState() == WorkInfo.State.ENQUEUED) {
                    hasEnqueuedWork = true;
                    break;
                }
            }
            assertTrue("Download work not ENQUEUED after startDownload",
                    hasEnqueuedWork);

            // KEEP semantics: a re-enqueue while pending is a no-op, the
            // unique work stays a single ENQUEUED record
            storageManager.startDownload(ModelType.FACE_DETECT.getId());
            List<WorkInfo> workInfosAfterReenqueue =
                    workManager.getWorkInfosForUniqueWork(
                            ModelDownloadWorker.uniqueWorkName(
                                    ModelType.FACE_DETECT.getId()))
                            .get(10, TimeUnit.SECONDS);
            assertNotNull(workInfosAfterReenqueue);
            assertEquals(1, workInfosAfterReenqueue.size());
            assertEquals(WorkInfo.State.ENQUEUED,
                    workInfosAfterReenqueue.get(0).getState());
        } finally {
            provider.dispose();
        }
    }
}
