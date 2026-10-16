package m.co.rh.id.a_jarwis.app.ui.page;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import co.rh.id.lib.rx3_utils.subject.SerialBehaviorSubject;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import m.co.rh.id.a_jarwis.R;
import m.co.rh.id.a_jarwis.base.provider.IStatefulViewProvider;
import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.a_jarwis.base.rx.RxDisposer;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.QREngine;
import m.co.rh.id.a_jarwis.ml_engine.provider.component.QrFrameDecoder;
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
 * Live QR code scanner. The camera preview fills the page and every frame is
 * analyzed through a grayscale Mat, decoded codes are deduplicated into a
 * result list overlay. A secondary action scans picked images instead.
 */
public class QrScannerPage extends StatefulView<Activity> implements RequireComponent<Provider>, NavOnBackPressed<Activity>, NavOnActivityResult<Activity> {

    private static final String TAG = "QrScannerPage";

    /**
     * Max dimension (longest side) of the decoded bitmap used for the
     * scan-from-image fallback, higher than the editor preview so small QR
     * codes are still found
     */
    private static final int SCAN_MAX_DIM = 2048;
    /**
     * Request code of the image picker launched by the scan-from-image action
     */
    private static final int REQUEST_CODE_SCAN_FROM_IMAGE = 1;
    /**
     * Minimum delay between two frame decodes, throttles the analysis to
     * roughly 10fps
     */
    private static final long MIN_DECODE_DELAY_MILLIS = 100;
    /**
     * Maximum number of unique decoded lines kept in the result list,
     * a scene full of codes cannot grow the list unbounded
     */
    private static final int MAX_RESULT_LINES = 50;

    /**
     * Serializable scanner state of this page, only holds serializable fields
     * so it survives the navigator snapshot
     */
    private static class ScannerState implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * Unique decoded lines in discovery order
         */
        private ArrayList<String> mResultLines;
    }

    /**
     * Scan result of a single image, used while the scan-from-image pipeline
     * is running
     */
    private static class ScanResult {
        private final int mIndex;
        /**
         * Decoded QR texts of this image, empty when nothing was decoded
         */
        private ArrayList<String> mTexts;

        private ScanResult(int index) {
            mIndex = index;
        }
    }

    /**
     * RecyclerView of the results overlay, clamps its measured height to one
     * third of the screen. The LinearLayout parent of the overlay passes an
     * UNSPECIFIED height spec, so the cap is computed from the screen height
     * directly: content below the cap keeps the wrap-content behaviour, content
     * above it is clamped and the list scrolls internally (a RecyclerView
     * scrolls whenever its content exceeds its measured bounds).
     */
    public static class BoundedResultRecyclerView extends RecyclerView {

        public BoundedResultRecyclerView(@NonNull Context context) {
            super(context);
        }

        public BoundedResultRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
            super(context, attrs);
        }

        public BoundedResultRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs,
                                         int defStyle) {
            super(context, attrs, defStyle);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int heightMode = MeasureSpec.getMode(heightSpec);
            if (heightMode == MeasureSpec.EXACTLY) {
                // a parent dictating the exact height wins over the cap, the
                // current overlay parent (a wrap_content LinearLayout) never
                // sends EXACTLY; the cap only applies to the self-sized paths
                // so a future 0dp+weight refactor cannot be silently clamped
                super.onMeasure(widthSpec, heightSpec);
                return;
            }
            int maxListHeight = Math.round(
                    getResources().getDisplayMetrics().heightPixels / 3f);
            if (heightMode == MeasureSpec.AT_MOST) {
                maxListHeight = Math.min(maxListHeight, MeasureSpec.getSize(heightSpec));
            }
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST));
        }
    }

    /**
     * RecyclerView adapter of the decoded lines, each row shows the decoded
     * text with a copy action; URLs inside the text are native link spans
     * (Linkify + LinkMovementMethod) and open through their span tap.
     */
    private static class ScanResultAdapter extends RecyclerView.Adapter<ScanResultAdapter.ResultViewHolder> {

        /**
         * Click callback of the adapter actions, implemented by the page
         */
        private interface ResultListener {
            void onCopyLine(int position);
        }

        private final ArrayList<String> mLines;
        private final ResultListener mListener;

        private ScanResultAdapter(ArrayList<String> lines, ResultListener listener) {
            mLines = lines;
            mListener = listener;
        }

        @NonNull
        @Override
        public ResultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_qr_scan_result, parent, false);
            return new ResultViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ResultViewHolder holder, int position) {
            String text = mLines.get(position);
            // native link rendering: Linkify turns the decoded URLs into
            // URLSpans with the framework link colour and underline, and the
            // LinkMovementMethod dispatches the ACTION_VIEW intent for a
            // tapped link span on its own
            holder.mTextResult.setText(text);
            Linkify.addLinks(holder.mTextResult, Linkify.WEB_URLS);
            holder.mTextResult.setMovementMethod(LinkMovementMethod.getInstance());
            holder.mTextResult.setOnTouchListener(mLinkTouchDelegate);
            holder.mButtonCopy.setOnClickListener(view -> mListener.onCopyLine(
                    holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return mLines.size();
        }

        /**
         * Touch delegate of the linkified text, the pitfall of a
         * MovementMethod TextView inside a RecyclerView: once the movement
         * method consumes a DOWN, a drag that starts over the text can end up
         * owned by the TextView instead of the list. This delegate consumes
         * only the DOWNs that land OUTSIDE a link span (so plain text never
         * enters the movement method and the RecyclerView stays free to scroll
         * the drag, it still intercepts on move even when a child consumed
         * the down), while a DOWN on a link span is forwarded to the TextView
         * so the tap opens the link.
         */
        private final View.OnTouchListener mLinkTouchDelegate = (view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && !isTouchOnLink((TextView) view, event)) {
                return true;
            }
            return false;
        };

        /**
         * @return true when the given touch lands on a link span of the text
         * view (same offset math as the Touch movement method helper)
         */
        private static boolean isTouchOnLink(TextView view, MotionEvent event) {
            Layout layout = view.getLayout();
            if (!(view.getText() instanceof Spannable) || layout == null) {
                return false;
            }
            int x = (int) event.getX();
            int y = (int) event.getY();
            x -= view.getTotalPaddingLeft();
            y -= view.getTotalPaddingTop();
            x += view.getScrollX();
            y += view.getScrollY();
            int line = layout.getLineForVertical(y);
            int offset = layout.getOffsetForHorizontal(line, x);
            Spannable buffer = (Spannable) view.getText();
            URLSpan[] spans = buffer.getSpans(offset, offset, URLSpan.class);
            return spans.length > 0;
        }

        private static class ResultViewHolder extends RecyclerView.ViewHolder {

            private final AppCompatTextView mTextResult;
            private final View mButtonCopy;

            private ResultViewHolder(@NonNull View itemView) {
                super(itemView);
                mTextResult = itemView.findViewById(R.id.text_result);
                mButtonCopy = itemView.findViewById(R.id.button_copy);
            }
        }
    }

    @NavInject
    private transient NavRoute mNavRoute;
    @NavInject
    private transient INavigator mNavigator;

    private final SerialBehaviorSubject<ScannerState> mScannerState;

    // component
    private transient Provider mSvProvider;
    private transient ILogger mLogger;
    private transient Handler mMainHandler;
    private transient ExecutorService mExecutorService;
    private transient RxDisposer mRxDisposer;
    private transient ImageHelper mImageHelper;

    // view
    private transient MaterialToolbar mToolbar;
    private transient PreviewView mPreviewView;
    private transient RecyclerView mRecyclerResult;
    private transient View mButtonCopyAll;
    private transient ScanResultAdapter mResultAdapter;
    private transient AlertDialog mProgressDialog;
    private transient ProgressBar mProgressBar;
    private transient AppCompatTextView mTextProgress;

    // camera runtime, never serialized
    /**
     * Dedicated single-thread executor of the image analyzer, created on
     * createView and shut down on dispose so no camera thread outlives the page
     */
    private transient ExecutorService mAnalyzerExecutor;
    private transient ProcessCameraProvider mCameraProvider;
    /**
     * Frame decoder of the analyzer, owns the reusable rotation Mat (released
     * on dispose through the analyzer executor, see below)
     */
    private transient QrFrameDecoder mFrameDecoder;
    /**
     * Y plane byte buffer of the analyzer, reused across frames, reallocated
     * only when the frame plane layout grows
     */
    private transient byte[] mYPlaneBuffer;
    /**
     * Single-in-flight guard of the frame decode, with STRATEGY_KEEP_ONLY_LATEST
     * this guarantees never decoding two frames concurrently
     */
    private transient AtomicBoolean mDecoding;
    /**
     * Timestamp of the last frame decode start, throttles the analysis to
     * roughly 10fps
     */
    private transient long mLastDecodeMillis;

    public QrScannerPage() {
        mScannerState = new SerialBehaviorSubject<>();
    }

    @Override
    public void provideComponent(Provider provider) {
        mSvProvider = provider.get(IStatefulViewProvider.class);
        mLogger = mSvProvider.get(ILogger.class);
        mMainHandler = mSvProvider.get(Handler.class);
        mExecutorService = mSvProvider.get(ExecutorService.class);
        mRxDisposer = mSvProvider.get(RxDisposer.class);
        mImageHelper = mSvProvider.get(ImageHelper.class);
        mDecoding = new AtomicBoolean(false);
    }

    @Override
    protected View createView(Activity activity, ViewGroup container) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.page_qr_scanner, container, false);
        mToolbar = rootView.findViewById(R.id.toolbar);
        mToolbar.setNavigationOnClickListener(v -> mNavigator.pop());
        mPreviewView = rootView.findViewById(R.id.preview_view);
        mRecyclerResult = rootView.findViewById(R.id.recycler_result);
        mRecyclerResult.setLayoutManager(new LinearLayoutManager(activity));
        mButtonCopyAll = rootView.findViewById(R.id.button_copy_all);
        mButtonCopyAll.setOnClickListener(v -> copyAllLines(activity));
        View scanFromImageButton = rootView.findViewById(R.id.button_scan_from_image);
        scanFromImageButton.setOnClickListener(v -> pickImage());
        ScannerState scannerState = mScannerState.getValue();
        if (scannerState == null) {
            // first open, start with an empty result list
            scannerState = new ScannerState();
            scannerState.mResultLines = new ArrayList<>();
            mScannerState.onNext(scannerState);
        } else if (scannerState.mResultLines == null) {
            // a snapshot persisted by the previous build deserializes with a
            // null list (the field was added under a fixed serialVersionUID),
            // treat it as empty like the editor pages treat their null
            // rects/config on restore
            scannerState.mResultLines = new ArrayList<>();
            mScannerState.onNext(scannerState);
        }
        mResultAdapter = new ScanResultAdapter(scannerState.mResultLines,
                new ScanResultAdapter.ResultListener() {
                    @Override
                    public void onCopyLine(int position) {
                        copyLine(activity, position);
                    }
                });
        mRecyclerResult.setAdapter(mResultAdapter);
        mRxDisposer.add("createView_onScannerStateChanged", mScannerState.getSubject()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onScannerStateChanged));
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // the home tile requests the permission before pushing this page,
            // a missing permission here means it was revoked mid-session
            mLogger.i(TAG, activity.getString(R.string.msg_camera_permission_missing));
            mNavigator.pop();
            return rootView;
        }
        startCamera(activity);
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
        if (mCameraProvider != null) {
            // unbinds every use case so the camera is released before the
            // analyzer executor shuts down
            try {
                mCameraProvider.unbindAll();
            } catch (Exception e) {
                // the camera is already released (e.g. the activity is gone),
                // nothing to do
            }
            mCameraProvider = null;
        }
        QrFrameDecoder frameDecoder = mFrameDecoder;
        mFrameDecoder = null;
        if (mAnalyzerExecutor != null) {
            if (frameDecoder != null) {
                try {
                    // release the decoder on the analyzer thread itself so it
                    // strictly happens after any in-flight decode finished
                    // (FIFO executor), a decode exceeding this page's lifetime
                    // can never read released native memory
                    mAnalyzerExecutor.execute(frameDecoder::release);
                } catch (RejectedExecutionException e) {
                    // precondition: this executor is exclusively shut down by
                    // this method, after the release submit above, so in the
                    // current lifecycle this branch is unreachable. Do not
                    // inline-release a decoder here if that precondition ever
                    // changes: shutdown() lets already-running tasks continue
                    // and an inline release would race them (the F2 race).
                    frameDecoder.release();
                }
            }
            // no new analyzer task can be submitted after the shutdown, no
            // main-thread blocking wait is needed
            mAnalyzerExecutor.shutdown();
            mAnalyzerExecutor = null;
        } else if (frameDecoder != null) {
            // no analyzer thread exists, nothing can be in flight
            frameDecoder.release();
        }
        mYPlaneBuffer = null;
        mResultAdapter = null;
        mRecyclerResult = null;
        mPreviewView = null;
        mToolbar = null;
        mButtonCopyAll = null;
        mDecoding = null;
        if (mSvProvider != null) {
            mSvProvider.dispose();
            mSvProvider = null;
        }
        mLogger = null;
        mMainHandler = null;
        mExecutorService = null;
        mRxDisposer = null;
        mImageHelper = null;
    }

    @Override
    public void onBackPressed(View currentView, Activity activity, INavigator navigator) {
        navigator.pop();
    }

    @Override
    public void onActivityResult(View currentView, Activity activity, INavigator INavigator, int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_SCAN_FROM_IMAGE
                && resultCode == Activity.RESULT_OK) {
            startImageScan(activity, data);
        }
    }

    private void onScannerStateChanged(ScannerState scannerState) {
        if (mResultAdapter != null) {
            mResultAdapter.notifyDataSetChanged();
        }
        if (mRecyclerResult != null) {
            // the results overlay is only visible when there is something to show
            mRecyclerResult.setVisibility(scannerState.mResultLines.isEmpty()
                    ? View.GONE : View.VISIBLE);
        }
        if (mButtonCopyAll != null) {
            // copying all lines only makes sense with at least two results,
            // a single row already has its own copy action
            mButtonCopyAll.setVisibility(scannerState.mResultLines.size() >= 2
                    ? View.VISIBLE : View.GONE);
        }
    }

    // camera pipeline

    private void startCamera(Activity activity) {
        mAnalyzerExecutor = Executors.newSingleThreadExecutor();
        mFrameDecoder = new QrFrameDecoder(mSvProvider);
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(activity);
        cameraProviderFuture.addListener(() -> {
            ProcessCameraProvider cameraProvider;
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (Exception e) {
                mLogger.e(TAG, e.getMessage(), e);
                return;
            }
            if (mPreviewView == null) {
                // the page was disposed while the provider future was pending
                return;
            }
            mCameraProvider = cameraProvider;
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            imageAnalysis.setAnalyzer(mAnalyzerExecutor, this::analyzeImageProxy);
            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle((LifecycleOwner) activity,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
            } catch (Exception e) {
                mLogger.e(TAG, e.getMessage(), e);
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    /**
     * Convert the camera frame into an upright grayscale Mat and decode it.
     * Runs on the analyzer executor, must close the given ImageProxy on every
     * path.
     */
    private void analyzeImageProxy(ImageProxy imageProxy) {
        try {
            // capture the component references so a straggling task that races
            // the page dispose never dereferences a nulled field
            QrFrameDecoder frameDecoder = mFrameDecoder;
            ILogger logger = mLogger;
            Handler mainHandler = mMainHandler;
            AtomicBoolean decoding = mDecoding;
            if (frameDecoder == null || logger == null || mainHandler == null
                    || decoding == null || decoding.get()) {
                // the page is disposed or a decode is still in flight
                return;
            }
            long currentMillis = System.currentTimeMillis();
            if (currentMillis - mLastDecodeMillis < MIN_DECODE_DELAY_MILLIS) {
                // inside the throttle window, roughly 10fps max
                return;
            }
            mLastDecodeMillis = currentMillis;
            decoding.set(true);
            ImageProxy.PlaneProxy plane = imageProxy.getPlanes()[0];
            byte[] yPlane = yPlaneBytes(plane,
                    imageProxy.getWidth(), imageProxy.getHeight());
            if (yPlane == null) {
                // unsupported plane layout, skip the frame
                return;
            }
            List<QREngine.QrDecodeResult> results = frameDecoder.decode(yPlane,
                    imageProxy.getWidth(), imageProxy.getHeight(),
                    plane.getRowStride(),
                    imageProxy.getImageInfo().getRotationDegrees());
            if (!results.isEmpty()) {
                ArrayList<String> texts = new ArrayList<>();
                for (QREngine.QrDecodeResult result : results) {
                    texts.add(result.getText());
                }
                mainHandler.post(() -> appendResultLines(texts));
            }
        } catch (Exception | OutOfMemoryError | UnsatisfiedLinkError e) {
            // UnsatisfiedLinkError: the OpenCV native library init is submitted
            // at provider startup and may still be in flight on a cold start,
            // skipping this frame is enough, the next one succeeds once the
            // init has landed
            ILogger logger = mLogger;
            if (logger != null) {
                logger.e(TAG, e.getMessage(), e);
            }
        } finally {
            imageProxy.close();
            AtomicBoolean decoding = mDecoding;
            if (decoding != null) {
                decoding.set(false);
            }
        }
    }

    /**
     * Copy the Y plane of the camera frame into the reusable byte buffer,
     * keeping the plane layout (rowStride-wide rows) so the engine-side frame
     * decoder can honour the rowStride. The copy decouples the bytes from the
     * camera-owned ImageProxy memory which is invalidated on close.
     *
     * @return the plane bytes or null when the plane layout is unsupported
     */
    private byte[] yPlaneBytes(ImageProxy.PlaneProxy plane, int width, int height) {
        int rowStride = plane.getRowStride();
        if (width <= 0 || height <= 0 || rowStride < width) {
            return null;
        }
        if (mYPlaneBuffer == null || mYPlaneBuffer.length < rowStride * height) {
            mYPlaneBuffer = new byte[rowStride * height];
        }
        byte[] yPlane = mYPlaneBuffer;
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        for (int row = 0; row < height; row++) {
            int offset = row * rowStride;
            int readLength = Math.min(rowStride, buffer.capacity() - offset);
            if (readLength <= 0) {
                break;
            }
            buffer.position(offset);
            buffer.get(yPlane, offset, readLength);
        }
        return yPlane;
    }

    // scan from image fallback

    private void pickImage() {
        Activity activity = mNavigator.getActivity();
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        activity.startActivityForResult(intent, REQUEST_CODE_SCAN_FROM_IMAGE);
    }

    private void startImageScan(Activity activity, Intent data) {
        if (isWorking()) {
            return;
        }
        ArrayList<String> uris = collectPickedUris(data);
        int total = uris.size();
        if (total == 0) {
            return;
        }
        // count of the decoded texts across the batch, the user gets a
        // feedback toast when the whole batch yielded nothing
        AtomicInteger decodedCount = new AtomicInteger();
        showProgressDialog(total, activity.getString(R.string.detecting_qr_progress, 0, total));
        mRxDisposer.add("startImageScan", Flowable.range(0, total)
                .subscribeOn(Schedulers.from(mExecutorService))
                .map(index -> scanImageTask(uris, index))
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        scanResult -> {
                            updateProgressDialog(scanResult.mIndex + 1, total,
                                    activity.getString(R.string.detecting_qr_progress,
                                            scanResult.mIndex + 1, total));
                            decodedCount.addAndGet(scanResult.mTexts.size());
                            appendResultLines(scanResult.mTexts);
                        },
                        throwable -> {
                            hideProgressDialog();
                            mLogger.e(TAG, throwable.getMessage(), throwable);
                        },
                        () -> {
                            hideProgressDialog();
                            if (decodedCount.get() == 0) {
                                // the composite logger shows the info level
                                // messages as a toast
                                mLogger.i(TAG,
                                        activity.getString(R.string.no_qr_result));
                            }
                        }
                ));
    }

    /**
     * Decode the image at the given index and scan it for QR codes.
     * Runs on a background thread.
     */
    private ScanResult scanImageTask(ArrayList<String> uris, int index) {
        ScanResult scanResult = new ScanResult(index);
        Bitmap bitmap = null;
        try {
            bitmap = mImageHelper.decodeDownscaledBitmap(Uri.parse(uris.get(index)),
                    SCAN_MAX_DIM);
            List<QREngine.QrDecodeResult> qrResults = mFrameDecoder.decodeBitmap(bitmap);
            ArrayList<String> texts = new ArrayList<>();
            for (QREngine.QrDecodeResult qrResult : qrResults) {
                texts.add(qrResult.getText());
            }
            scanResult.mTexts = texts;
        } catch (Exception | OutOfMemoryError e) {
            mLogger.e(TAG, e.getMessage(), e);
            // keep the scan usable for the remaining images
            scanResult.mTexts = new ArrayList<>();
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
        return scanResult;
    }

    private ArrayList<String> collectPickedUris(Intent data) {
        ArrayList<String> uriList = new ArrayList<>();
        if (data == null) {
            return uriList;
        }
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            int count = clipData.getItemCount();
            for (int i = 0; i < count; i++) {
                uriList.add(clipData.getItemAt(i).getUri().toString());
            }
        } else if (data.getData() != null) {
            uriList.add(data.getData().toString());
        }
        return uriList;
    }

    // results list

    /**
     * Append genuinely new decoded lines to the result list, deduplicated by
     * text and bounded to MAX_RESULT_LINES entries, then emit the state
     */
    private void appendResultLines(List<String> newLines) {
        if (newLines == null || newLines.isEmpty()) {
            return;
        }
        ScannerState scannerState = mScannerState.getValue();
        if (scannerState == null || scannerState.mResultLines == null) {
            return;
        }
        ArrayList<String> resultLines = scannerState.mResultLines;
        boolean changed = false;
        for (String line : newLines) {
            if (line != null && !resultLines.contains(line)
                    && resultLines.size() < MAX_RESULT_LINES) {
                resultLines.add(line);
                changed = true;
            }
        }
        if (changed) {
            mScannerState.onNext(scannerState);
        }
    }

    private void copyLine(Activity activity, int position) {
        ScannerState scannerState = mScannerState.getValue();
        if (scannerState == null || position < 0
                || position >= scannerState.mResultLines.size()) {
            return;
        }
        copyText(activity, scannerState.mResultLines.get(position));
    }

    private void copyAllLines(Activity activity) {
        ScannerState scannerState = mScannerState.getValue();
        if (scannerState == null || scannerState.mResultLines.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String line : scannerState.mResultLines) {
            if (text.length() > 0) {
                text.append("\n");
            }
            text.append(line);
        }
        copyText(activity, text.toString());
    }

    private void copyText(Activity activity, String text) {
        ClipboardManager clipboard = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    activity.getString(R.string.title_qr_scan_result), text));
        }
        // the composite logger shows the info level messages as a toast
        mLogger.i(TAG, activity.getString(R.string.copied_to_clipboard));
    }

    private boolean isWorking() {
        return mProgressDialog != null && mProgressDialog.isShowing();
    }

    private void showProgressDialog(int max, String message) {
        if (mProgressDialog == null) {
            Activity activity = mNavigator.getActivity();
            View contentView = activity.getLayoutInflater()
                    .inflate(R.layout.dialog_face_editor_progress, null);
            mProgressBar = contentView.findViewById(R.id.progressBar);
            mTextProgress = contentView.findViewById(R.id.text_progress);
            mProgressDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_qr_scan)
                    .setView(contentView)
                    .setCancelable(false)
                    .create();
            mProgressDialog.show();
        }
        mProgressBar.setIndeterminate(false);
        mProgressBar.setMax(max);
        mProgressBar.setProgress(0);
        mTextProgress.setText(message);
    }

    private void updateProgressDialog(int progress, int max, String message) {
        if (mProgressBar != null) {
            mProgressBar.setIndeterminate(false);
            mProgressBar.setMax(max);
            mProgressBar.setProgress(progress);
        }
        if (mTextProgress != null) {
            mTextProgress.setText(message);
        }
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
