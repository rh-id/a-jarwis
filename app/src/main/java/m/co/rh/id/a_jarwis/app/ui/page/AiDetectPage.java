package m.co.rh.id.a_jarwis.app.ui.page;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.app.ui.page.nav.param.UriList;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.AiDetectEngine;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.anavigator.NavRoute;
import m.co.rh.id.anavigator.StatefulView;
import m.co.rh.id.anavigator.annotation.NavInject;
import m.co.rh.id.anavigator.component.INavigator;
import m.co.rh.id.anavigator.component.NavOnActivityResult;
import m.co.rh.id.anavigator.component.NavOnBackPressed;
import m.co.rh.id.anavigator.component.RequireComponent;
import m.co.rh.id.aprovider.Provider;

/**
 * AI image detector utility page: shows the picked image and how likely it
 * was AI-generated according to the on-device CommunityForensics
 * DeepfakeDet-ViT model (downloaded on demand by the home workflow gate).
 * No editing — the page decodes the image once, classifies it once and
 * shows the result card: the confidence band (spike-calibrated thresholds,
 * see {@link #BAND_THRESHOLD_LIKELY_AI}), the AI-generated percentage and
 * the disclaimer that the result is a probabilistic estimate, never
 * evidence. "Check another image" re-picks in place (the in-page picker,
 * the QrScannerPage re-pick pattern) and re-runs the pipeline; a failed
 * run surfaces a toast and the same button is the retry path.
 * <p>
 * The analyze pipeline (sampled decode + classify) runs as one RxJava
 * Single on the shared background executor behind a busy-guard progress
 * dialog, so a second analyze can never start while one is in flight (the
 * processor additionally serializes its calls, see AiDetectEngine). The
 * cached processor is kept for the page session and released on dispose
 * (see the AiDetectEngine release policy). The route args (the picked uri)
 * are the only persisted state: a process restore re-runs the pipeline
 * from them, the bitmap and the result live only in memory.
 */
public class AiDetectPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, NavOnActivityResult<Activity> {

    private static final String TAG = "AiDetectPage";

    /**
     * Max dimension (longest side) of the decoded bitmap used for the
     * preview and the classification, the same preview budget the editor
     * pages use (the model crops its 384x384 input out of it, a bigger
     * decode would only slow the pipeline down)
     */
    private static final int PREVIEW_MAX_DIM = 1280;
    /**
     * Request code of the in-page image picker, the "check another image"
     * action re-picks without leaving the page
     */
    private static final int REQUEST_CODE_PICK_IMAGE = 1;
    /**
     * Band threshold of the result card, spike-calibrated (issue #12,
     * validated by the round-2 spike run): pFake &gt;= this is reported as
     * likely AI-generated. The round-2 measurements endorsed these exact
     * values — the model's probabilities saturate near 0/1, so the unclear
     * band triggers rarely and the disclaimer carries the uncertainty (one
     * stylized-artwork false negative was observed and accepted)
     */
    private static final float BAND_THRESHOLD_LIKELY_AI = 0.75f;
    /**
     * Band threshold of the result card (see
     * {@link #BAND_THRESHOLD_LIKELY_AI}): pFake &lt;= this is reported as
     * likely camera-original, validated by the same round-2 spike run
     */
    private static final float BAND_THRESHOLD_LIKELY_REAL = 0.35f;

    /**
     * Result of the analyze pipeline, used while the pipeline is running:
     * the decoded bitmap (owned by the page after the delivery) and the
     * classification probability
     */
    private static class AnalyzeResult {
        private final Bitmap mBitmap;
        private final float mPFake;

        private AnalyzeResult(Bitmap bitmap, float pFake) {
            mBitmap = bitmap;
            mPFake = pFake;
        }
    }

    @NavInject
    private transient NavRoute mNavRoute;
    @NavInject
    private transient INavigator mNavigator;

    // component
    private transient Provider mSvProvider;
    private transient ILogger mLogger;
    private transient ExecutorService mExecutorService;
    private transient RxDisposer mRxDisposer;
    private transient AiDetectEngine mAiDetectEngine;
    private transient ImageHelper mImageHelper;
    /**
     * Ownership token of the in-flight analyze result (re-initialized in
     * provideComponent because Java deserialization does not run the
     * constructor): the task registers the produced result here before
     * emitting and the delivery takes it OUT with an atomic exchange —
     * exactly one of the result delivery and the dispose cleanup owns the
     * bitmap, so a page disposed mid-analyze never delivers an
     * already-recycled result and never double-recycles a bitmap. No
     * bitmap outlives the page beyond the next GC at worst: a result set
     * in the theoretical window between dispose's exchange and the field
     * cleanup misses the explicit recycle and is reclaimed by GC instead,
     * bounded to one preview bitmap
     */
    private transient AtomicReference<AnalyzeResult> mPendingResult;

    // view
    private transient MaterialToolbar mToolbar;
    private transient AppCompatImageView mImageView;
    private transient View mContainerResult;
    private transient AppCompatTextView mTextBand;
    private transient AppCompatTextView mTextProbability;
    private transient AppCompatTextView mTextDisclaimer;
    private transient View mButtonPickAnother;
    /**
     * Busy-guard progress dialog of the analyze pipeline (the
     * dialog_face_editor_progress layout, the QrScannerPage pattern), its
     * isShowing is the gate every analyze relies on
     */
    private transient AlertDialog mProgressDialog;
    private transient ProgressBar mProgressBar;
    private transient AppCompatTextView mTextProgress;

    // runtime, never serialized: a process restore re-runs the pipeline
    // from the persisted route args
    /**
     * The preview bitmap currently shown on mImageView, recycled on dispose
     * and when a "check another image" run delivers a new one
     */
    private transient Bitmap mPreviewBitmap;

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mAiDetectEngine = mSvProvider.get(AiDetectEngine.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mPendingResult = new AtomicReference<>();
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_ai_detect, container, false);
        mToolbar = rootView.findViewById(R.id.toolbar);
        mToolbar.setNavigationOnClickListener(v -> mNavigator.pop());
        mImageView = rootView.findViewById(R.id.image_view);
        mContainerResult = rootView.findViewById(R.id.container_result);
        mTextBand = rootView.findViewById(R.id.text_band);
        mTextProbability = rootView.findViewById(R.id.text_probability);
        mTextDisclaimer = rootView.findViewById(R.id.text_disclaimer);
        mTextDisclaimer.setText(activity.getString(R.string.msg_ai_detect_disclaimer));
        mButtonPickAnother = rootView.findViewById(R.id.button_pick_another);
        mButtonPickAnother.setOnClickListener(v -> pickImage());
        Uri sourceUri = parseRouteArgs();
        if (sourceUri == null) {
            // no picked image in the route args (should not happen, the
            // home workflow always pushes exactly one), nothing to show
            mLogger.e(TAG, "No source uri in the route args, popping");
            mNavigator.pop();
            return rootView;
        }
        startAnalyze(activity, sourceUri);
        return rootView;
    }

    @Override
    public void dispose(Activity activity) {
        super.dispose(activity);
        if (mProgressDialog != null) {
            if (mProgressDialog.isShowing()) {
                mProgressDialog.dismiss();
            }
            mProgressDialog = null;
        }
        mProgressBar = null;
        mTextProgress = null;
        if (mPreviewBitmap != null) {
            mPreviewBitmap.recycle();
            mPreviewBitmap = null;
        }
        if (mPendingResult != null) {
            // recycle an analyze result the delivery never consumed (the
            // atomic exchange: exactly one of the result delivery and this
            // cleanup owns the bitmap, see mPendingResult)
            AnalyzeResult pendingResult = mPendingResult.getAndSet(null);
            if (pendingResult != null) {
                pendingResult.mBitmap.recycle();
            }
            mPendingResult = null;
        }
        // release the cached processor with this page: the ~87 MB net plus
        // the dnn workspace OpenCV retains inside it must not stay reserved
        // after the page closes, the next session lazily re-parses the model
        // file on demand (see the AiDetectEngine release policy). The
        // release runs on the background executor — it blocks on the
        // processor monitor while a forward may still be in flight (seconds
        // of main-thread stall otherwise) — the same dispose-time pattern
        // as MagicEraserEditorPage. The engine instance is captured first:
        // dispose nulls the page fields below while the submitted release
        // may run after them
        if (mExecutorService != null && mAiDetectEngine != null) {
            AiDetectEngine engine = mAiDetectEngine;
            try {
                mExecutorService.submit(engine::release);
            } catch (RejectedExecutionException e) {
                // the executor is already shut down, the provider dispose
                // cascade releases the processor through MLEngineInstance
                // anyway (the engine caches nothing itself)
            }
        }
        mImageView = null;
        mContainerResult = null;
        mTextBand = null;
        mTextProbability = null;
        mTextDisclaimer = null;
        mButtonPickAnother = null;
        mToolbar = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mExecutorService = null;
        mRxDisposer = null;
        mAiDetectEngine = null;
        mImageHelper = null;
    }

    @Override
    public void onBackPressed(View currentView, Activity activity, INavigator navigator) {
        navigator.pop();
    }

    @Override
    public void onActivityResult(View currentView, Activity activity, INavigator INavigator, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PICK_IMAGE
                && resultCode == Activity.RESULT_OK) {
            Uri pickedUri = firstPickedUri(data);
            if (pickedUri != null) {
                startAnalyze(activity, pickedUri);
            }
        }
    }

    // analyze pipeline

    private void startAnalyze(Activity activity, Uri sourceUri) {
        if (isWorking()) {
            return;
        }
        showProgressDialog(activity.getString(R.string.msg_ai_detect_analyze_busy));
        mRxDisposer.add("startAnalyze", Single.fromCallable(() -> analyzeTask(sourceUri))
                .subscribeOn(Schedulers.from(mExecutorService))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((result, throwable) -> {
                    hideProgressDialog();
                    ILogger logger = mLogger;
                    if (throwable != null) {
                        // the error is logged and toasted, the "check
                        // another image" button stays as the retry path
                        if (logger != null) {
                            logger.e(TAG, throwable.getMessage(), throwable);
                            logger.i(TAG, activity.getString(R.string.error_ai_detect_failed));
                        }
                        return;
                    }
                    if (mImageView == null || mPendingResult == null) {
                        // the page was disposed while the analyze ran and
                        // the dispose cleanup already recycled the pending
                        // result (the ownership exchange, see mPendingResult)
                        return;
                    }
                    // take the ownership out of the pending reference: a
                    // dispose racing this delivery gets null instead and
                    // never double-recycles the bitmap
                    AnalyzeResult ownedResult = mPendingResult.getAndSet(null);
                    if (ownedResult != result) {
                        return;
                    }
                    if (mPreviewBitmap != null && mPreviewBitmap != ownedResult.mBitmap) {
                        mPreviewBitmap.recycle();
                    }
                    mPreviewBitmap = ownedResult.mBitmap;
                    mImageView.setImageBitmap(mPreviewBitmap);
                    showResult(activity, ownedResult.mPFake);
                }));
    }

    /**
     * Decode the given image sampled to the preview budget and classify it.
     * Runs on a background thread.
     *
     * @throws RuntimeException wrapping the failure (the RxJava error
     * channel), the decode or the classify failure is never delivered as a
     * raw fatal (OutOfMemoryError is caught and wrapped here)
     */
    private AnalyzeResult analyzeTask(Uri sourceUri) {
        Bitmap bitmap = null;
        try {
            bitmap = mImageHelper.decodeDownscaledBitmap(sourceUri, PREVIEW_MAX_DIM);
            float pFake = mAiDetectEngine.classify(bitmap);
            AnalyzeResult analyzeResult = new AnalyzeResult(bitmap, pFake);
            // register before emitting, the dispose cleanup recycles a
            // result the delivery never consumed (see mPendingResult)
            mPendingResult.set(analyzeResult);
            return analyzeResult;
        } catch (Exception | OutOfMemoryError e) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Fill the result card from the given pFake reading: the confidence
     * band string, the AI-generated percentage (whole percent) and the
     * disclaimer (set once in createView)
     */
    private void showResult(Activity activity, float pFake) {
        mTextBand.setText(bandText(activity, pFake));
        mTextProbability.setText(activity.getString(
                R.string.msg_ai_detect_probability, Math.round(pFake * 100f)));
        mContainerResult.setVisibility(View.VISIBLE);
    }

    /**
     * The confidence band of one pFake reading (see the band threshold
     * constants): likely AI-generated, likely camera-original or unclear
     */
    private static String bandText(Activity activity, float pFake) {
        if (pFake >= BAND_THRESHOLD_LIKELY_AI) {
            return activity.getString(R.string.band_likely_ai);
        }
        if (pFake <= BAND_THRESHOLD_LIKELY_REAL) {
            return activity.getString(R.string.band_likely_real);
        }
        return activity.getString(R.string.band_unclear);
    }

    // image picking

    /**
     * Open the system image picker for the "check another image" action
     * (single pick, the detector analyzes one image at a time), a no-op
     * while an analyze is in flight
     */
    private void pickImage() {
        if (isWorking()) {
            return;
        }
        Activity activity = mNavigator.getActivity();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        activity.startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE);
    }

    /**
     * @return the first picked uri of the picker result, or null when the
     * pick yielded nothing (the detector analyzes a single image)
     */
    private static Uri firstPickedUri(Intent data) {
        if (data == null) {
            return null;
        }
        ClipData clipData = data.getClipData();
        if (clipData != null && clipData.getItemCount() > 0) {
            return clipData.getItemAt(0).getUri();
        }
        return data.getData();
    }

    /**
     * Parse the picked image uri out of the route args (the home workflow
     * pushes exactly one, see HomeWorkflow.pushAiDetect)
     *
     * @return the source uri, or null when the args hold no image
     */
    private Uri parseRouteArgs() {
        if (mNavRoute != null && mNavRoute.getRouteArgs() instanceof UriList) {
            ArrayList<String> uris = ((UriList) mNavRoute.getRouteArgs()).getUris();
            if (!uris.isEmpty()) {
                // the detector analyzes a single image, only the first
                // picked uri is used
                return Uri.parse(uris.get(0));
            }
        }
        return null;
    }

    // busy-guard progress dialog (the QrScannerPage pattern)

    private boolean isWorking() {
        return mProgressDialog != null && mProgressDialog.isShowing();
    }

    private void showProgressDialog(String message) {
        if (mProgressDialog == null) {
            Activity activity = mNavigator.getActivity();
            View contentView = activity.getLayoutInflater()
                    .inflate(R.layout.dialog_face_editor_progress, null);
            mProgressBar = contentView.findViewById(R.id.progressBar);
            mTextProgress = contentView.findViewById(R.id.text_progress);
            mProgressDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_ai_detect)
                    .setView(contentView)
                    .setCancelable(false)
                    .create();
            mProgressDialog.show();
        }
        // single image per run, the progress is indeterminate
        mProgressBar.setIndeterminate(true);
        mTextProgress.setText(message);
    }

    private void hideProgressDialog() {
        if (mProgressDialog != null && mProgressDialog.isShowing()) {
            mProgressDialog.dismiss();
        }
        mProgressDialog = null;
        mProgressBar = null;
        mTextProgress = null;
    }
}
