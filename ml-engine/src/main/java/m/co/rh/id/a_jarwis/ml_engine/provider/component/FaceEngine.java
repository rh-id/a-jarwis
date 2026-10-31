package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;

import m.co.rh.id.a_jarwis.ml_engine.model.BlurConfig;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import m.co.rh.id.a_jarwis.base.provider.component.helper.ImageHelper;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;
import m.co.rh.id.aprovider.ProviderValue;

/**
 * Face engine of the module: detects faces in a bitmap, compares two faces
 * for identity and composites a Gaussian blur over the detected or selected
 * face regions.
 * <p>
 * All face operations are synchronized on the engine monitor: the face
 * models will become shared cached instances (follow-up commit), and every
 * detector call mutates the shared detector via setInputSize, so two
 * concurrent face operations on one instance would race on the shared
 * native detector. Callers already run them from background executors,
 * the engine monitor makes the required serialization explicit instead of
 * relying on that discipline.
 */
public class FaceEngine {
    private static final String TAG = "FaceEngine";
    /**
     * Gaussian blur kernel size used to blur a full resolution face crop
     */
    private static final int BLUR_KERNEL_SIZE = 201;
    /**
     * Minimum Gaussian blur kernel size, OpenCV requires a positive odd kernel size
     */
    private static final int BLUR_KERNEL_SIZE_MIN = 3;
    /**
     * Gaussian blur sigma used to blur a full resolution face crop
     */
    private static final double BLUR_KERNEL_SIGMA = 100;
    /**
     * Gaussian blur kernel size relative to the face min dimension for each
     * strength level 1..5 (see BlurConfig strength constants), the kernel scales
     * with the face size so the blur strength is consistent across face sizes
     * (the previous fixed 201 kernel blurred small faces much stronger than
     * large ones), strength 3 on a typical face size approximates that look
     */
    private static final float[] STRENGTH_KERNEL_FACTOR = {0.35f, 0.50f, 0.67f, 0.85f, 1.00f};
    /**
     * Gaussian blur sigma relative to its kernel size, the previous fixed
     * kernel 201 / sigma 100 look is preserved with the same ratio.
     * Package-private so the portrait background blur reuses the same look
     */
    static final float BLUR_SIGMA_SCALE = 0.5f;
    /**
     * Feather width of the shape mask relative to the face crop min dimension
     */
    private static final float SHAPE_FEATHER_SCALE = 0.12f;
    /**
     * Rounded corner radius of the square shape mask relative to the face crop min dimension
     */
    private static final float SHAPE_SQUARE_CORNER_SCALE = 0.10f;

    private final ILogger mLogger;
    private final ImageHelper mImageHelper;
    private final ProviderValue<MLEngineInstance> mEngineInstance;

    public FaceEngine(Provider provider) {
        mLogger = provider.get(ILogger.class);
        mImageHelper = provider.get(ImageHelper.class);
        mEngineInstance = provider.lazyGet(MLEngineInstance.class);
    }

    /**
     * Check if faceImage detected in imageToBeSearch or not
     *
     * @param faceImage       image to be checked expected contains one face
     * @param imageToBeSearch image to be scanned
     * @return list of rectangle that this engine recognize from imageToBeSearch
     */
    public synchronized List<Rect> searchFace(Bitmap faceImage, Bitmap imageToBeSearch) {
        List<Rect> resultList = new ArrayList<>();
        Mat faceLocOwner = detectFaceRaw(faceImage);
        Mat imageSearch = null;
        try {
            if (faceLocOwner.rows() > 1 || faceLocOwner.rows() < 1) {
                throw new IllegalArgumentException("Face to be searched must be one only");
            }
            imageSearch = detectFaceRaw(imageToBeSearch);
            int rowSize = imageSearch.rows();
            if (rowSize > 0) {
                // row() returns a view sharing the owner's memory, the owner
                // stays alive until the views are released below
                Mat faceLoc = faceLocOwner.row(0);
                try {
                    for (int i = 0; i < rowSize; i++) {
                        Mat detectedFace = imageSearch.row(i);
                        try {
                            boolean faceSimilar = isFaceSimilar(faceImage, imageToBeSearch,
                                    faceLoc, detectedFace);
                            if (faceSimilar) {
                                resultList.add(faceDetectToRect(detectedFace));
                            }
                        } finally {
                            detectedFace.release();
                        }
                    }
                } finally {
                    faceLoc.release();
                }
            }
        } finally {
            if (imageSearch != null) {
                imageSearch.release();
            }
            faceLocOwner.release();
        }
        return resultList;
    }

    /**
     * Process bitmap using face detection engine and return a list of rectangle indicating location of faces
     */
    public synchronized List<Rect> detectFace(Bitmap bitmap) {
        List<Rect> rectList = new ArrayList<>();
        Mat faceOutput = detectFaceRaw(bitmap);
        try {
            int totalFace = faceOutput.rows();
            for (int i = 0; i < totalFace; i++) {
                Mat detectedFace = faceOutput.row(i);
                try {
                    rectList.add(faceDetectToRect(detectedFace));
                } finally {
                    detectedFace.release();
                }
            }
        } finally {
            faceOutput.release();
        }
        return rectList;
    }

    /**
     * Process bitmap using face detection engine, blur and process the bitmap.
     * Retained intentionally for future reference-image-based flows
     * (blur all detected faces except/similar to the given face crops),
     * currently unused by the app.
     *
     * @return blurred image or null if face is not detected
     */
    public synchronized Bitmap blurFace(Bitmap originalBitmap, Collection<Bitmap> faces, boolean isExclude) {
        Bitmap result = null;
        Mat rectList = detectFaceRaw(originalBitmap);
        try {
            if (rectList.rows() > 0) {
                result = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(result);
                canvas.drawBitmap(originalBitmap, 0, 0, null);
                boolean excludedFaceEmpty = faces == null || faces.isEmpty();
                int size = rectList.rows();
                for (int i = 0; i < size; i++) {
                    Mat faceLoc = rectList.row(i);
                    try {
                        if (!excludedFaceEmpty) {
                            boolean skipBlur = false;
                            for (Bitmap bitmap : faces) {
                                Mat excludeFaceMat = detectFaceRaw(bitmap);
                                try {
                                    Mat excludeFaceLoc = excludeFaceMat.row(0);
                                    try {
                                        boolean faceSimilar = isFaceSimilar(originalBitmap, bitmap,
                                                faceLoc, excludeFaceLoc);
                                        if (faceSimilar) {
                                            if (isExclude) {
                                                skipBlur = true;
                                                break;
                                            }
                                        } else {
                                            if (!isExclude) {
                                                skipBlur = true;
                                                break;
                                            }
                                        }
                                    } finally {
                                        excludeFaceLoc.release();
                                    }
                                } finally {
                                    excludeFaceMat.release();
                                }
                            }
                            if (skipBlur) {
                                continue;
                            }
                        }
                        Rect faceLocRect = faceDetectToRect(faceLoc);
                        Bitmap faceCrop = cropBitmap(originalBitmap, faceLocRect);
                        Mat faceCropRaw = new Mat();
                        try {
                            Utils.bitmapToMat(faceCrop, faceCropRaw);
                            Imgproc.GaussianBlur(faceCropRaw, faceCropRaw,
                                    new Size(BLUR_KERNEL_SIZE, BLUR_KERNEL_SIZE), BLUR_KERNEL_SIGMA);
                            Bitmap blurredFace = Bitmap.createBitmap(faceCropRaw.cols(), faceCropRaw.rows(), Bitmap.Config.ARGB_8888);
                            Utils.matToBitmap(faceCropRaw, blurredFace);
                            canvas.drawBitmap(blurredFace, null, faceLocRect, null);
                            faceCrop.recycle();
                            blurredFace.recycle();
                        } finally {
                            faceCropRaw.release();
                        }
                    } finally {
                        faceLoc.release();
                    }
                }
            }
        } finally {
            rectList.release();
        }
        return result;
    }

    /**
     * Process the bitmap by compositing a Gaussian blur only at the given rects.
     * Used by the face editor to blur only the faces selected by the user.
     *
     * @param originalBitmap bitmap to be processed
     * @param rectsToBlur    rects (in originalBitmap coordinate) to be blurred
     * @param config         blur shape and strength configuration,
     *                       null falls back to the default configuration
     * @return a copy of the originalBitmap with the blur composited at the given rects,
     * or a copy of the originalBitmap when the given rects is null or empty
     */
    public synchronized Bitmap blurFacesAt(Bitmap originalBitmap, List<Rect> rectsToBlur, BlurConfig config) {
        BlurConfig blurConfig = config == null ? new BlurConfig() : config;
        Bitmap result = Bitmap.createBitmap(originalBitmap.getWidth(),
                originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(originalBitmap, 0, 0, null);
        if (rectsToBlur != null && !rectsToBlur.isEmpty()) {
            for (Rect faceLocRect : rectsToBlur) {
                Bitmap faceCrop = cropBitmap(originalBitmap, faceLocRect);
                Bitmap blurredFace = null;
                Mat faceCropRaw = new Mat();
                try {
                    // the crop dimension is the face dimension at full resolution
                    int kernelSize = computeFullKernelSize(
                            Math.min(faceCrop.getWidth(), faceCrop.getHeight()),
                            blurConfig.getStrength());
                    Utils.bitmapToMat(faceCrop, faceCropRaw);
                    Imgproc.GaussianBlur(faceCropRaw, faceCropRaw,
                            new Size(kernelSize, kernelSize), kernelSize * BLUR_SIGMA_SCALE);
                    blurredFace = Bitmap.createBitmap(faceCropRaw.cols(), faceCropRaw.rows(), Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(faceCropRaw, blurredFace);
                    applyShapeMask(blurredFace, blurConfig.getShape());
                    canvas.drawBitmap(blurredFace, null, faceLocRect, null);
                } finally {
                    faceCrop.recycle();
                    if (blurredFace != null) {
                        blurredFace.recycle();
                    }
                    faceCropRaw.release();
                }
            }
        }
        return result;
    }

    /**
     * Blur the given face crop using a Gaussian kernel scaled by the given scale factor.
     * Used by the face editor to pre-blur a face crop of a downscaled preview bitmap,
     * so the preview visually matches the saved full resolution result. The full
     * resolution kernel is first computed from the face size (see computeFullKernelSize)
     * then scaled down by the given scale factor, and the crop is masked with the
     * configured feathered shape which is drawn relative to the crop size.
     *
     * @param faceCrop face crop to be blurred
     * @param scale    preview to full resolution scale factor,
     *                 1.0 means the crop is a full resolution crop
     * @param config   blur shape and strength configuration,
     *                 null falls back to the default configuration
     * @return the blurred face crop
     */
    public synchronized Bitmap blurFaceCrop(Bitmap faceCrop, float scale, BlurConfig config) {
        BlurConfig blurConfig = config == null ? new BlurConfig() : config;
        if (scale <= 0f) {
            // a failed dimension decode falls back to a full resolution crop
            scale = 1f;
        }
        int cropMinDim = Math.min(faceCrop.getWidth(), faceCrop.getHeight());
        // the preview crop dimension back-scaled to the full resolution face dimension
        int fullFaceMinDim = Math.round(cropMinDim / scale);
        int fullKernelSize = computeFullKernelSize(fullFaceMinDim, blurConfig.getStrength());
        // scale the full resolution kernel down to the preview resolution,
        // keep the kernel odd (OpenCV requires a positive odd kernel size)
        int kernelSize = Math.round(fullKernelSize * scale);
        if (kernelSize < BLUR_KERNEL_SIZE_MIN) {
            kernelSize = BLUR_KERNEL_SIZE_MIN;
        }
        if (kernelSize % 2 == 0) {
            kernelSize++;
        }
        Mat faceCropRaw = new Mat();
        try {
            Utils.bitmapToMat(faceCrop, faceCropRaw);
            Imgproc.GaussianBlur(faceCropRaw, faceCropRaw, new Size(kernelSize, kernelSize),
                    kernelSize * BLUR_SIGMA_SCALE);
            Bitmap blurredFace = Bitmap.createBitmap(faceCropRaw.cols(), faceCropRaw.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(faceCropRaw, blurredFace);
            applyShapeMask(blurredFace, blurConfig.getShape());
            return blurredFace;
        } finally {
            faceCropRaw.release();
        }
    }

    /**
     * Compute the Gaussian blur kernel size for a face of the given minimum dimension
     * at full resolution, the kernel scales with the face size so the blur strength is
     * consistent across face sizes. The kernel is clamped to
     * {@code [BLUR_KERNEL_SIZE_MIN, BLUR_KERNEL_SIZE]} and forced odd,
     * OpenCV requires a positive odd kernel size.
     *
     * @param fullFaceMinDim face min dimension (width or height) at full resolution
     * @param strength       blur strength, expected within the BlurConfig strength range,
     *                       out of range values are clamped
     * @return the Gaussian blur kernel size to be used
     */
    static int computeFullKernelSize(int fullFaceMinDim, int strength) {
        int strengthIndex = strength - 1;
        if (strengthIndex < 0) {
            strengthIndex = 0;
        } else if (strengthIndex >= STRENGTH_KERNEL_FACTOR.length) {
            strengthIndex = STRENGTH_KERNEL_FACTOR.length - 1;
        }
        int kernelSize = Math.round(fullFaceMinDim * STRENGTH_KERNEL_FACTOR[strengthIndex]);
        if (kernelSize < BLUR_KERNEL_SIZE_MIN) {
            kernelSize = BLUR_KERNEL_SIZE_MIN;
        }
        if (kernelSize > BLUR_KERNEL_SIZE) {
            kernelSize = BLUR_KERNEL_SIZE;
        }
        if (kernelSize % 2 == 0) {
            kernelSize++;
        }
        return kernelSize;
    }

    /**
     * Multiply the alpha channel of the given blurred face crop with a feathered mask
     * of the given shape, so the blurred area blends smoothly into the image instead of
     * ending with a hard edge. The mask is drawn relative to the crop size, so the
     * preview and the full resolution crops are geometrically identical (relative)
     * and the preview parity holds.
     *
     * @param blurredFace blurred face crop to be masked, modified in place
     * @param shape       one of the BlurConfig shape constants,
     *                    unknown or reserved shapes fall back to the ellipse
     */
    private static void applyShapeMask(Bitmap blurredFace, int shape) {
        int width = blurredFace.getWidth();
        int height = blurredFace.getHeight();
        int minDim = Math.min(width, height);
        Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas maskCanvas = new Canvas(mask);
            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            maskPaint.setColor(Color.WHITE);
            // feathered shape edge so the blur blends into the surrounding image
            maskPaint.setMaskFilter(new BlurMaskFilter(minDim * SHAPE_FEATHER_SCALE,
                    BlurMaskFilter.Blur.NORMAL));
            if (shape == BlurConfig.SHAPE_SQUARE) {
                maskCanvas.drawRoundRect(0, 0, width, height,
                        minDim * SHAPE_SQUARE_CORNER_SCALE, minDim * SHAPE_SQUARE_CORNER_SCALE,
                        maskPaint);
            } else {
                maskCanvas.drawOval(0, 0, width, height, maskPaint);
            }
            Canvas blurCanvas = new Canvas(blurredFace);
            Paint xferPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            xferPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            blurCanvas.drawBitmap(mask, 0, 0, xferPaint);
            xferPaint.setXfermode(null);
        } finally {
            mask.recycle();
        }
    }

    /**
     * Compare 2 faces and check if it is similar
     *
     * @param face1Loc face location row of the detectFaceRaw output of image1Src,
     * never released by this method
     * @param face2Loc face location row of the detectFaceRaw output of image2Src,
     * never released by this method
     * @return true if similar, otherwise false
     */
    protected synchronized boolean isFaceSimilar(Bitmap image1Src, Bitmap image2Src,
                                                 Mat face1Loc, Mat face2Loc) {
        FaceRecognizerSF faceRecognizerSF = mEngineInstance.get().getFaceRecognizerModel();
        Mat image1 = new Mat();
        Mat image2 = new Mat();
        Mat alignedF1 = new Mat();
        Mat alignedF2 = new Mat();
        Mat feature1 = new Mat();
        Mat feature2 = new Mat();
        Mat feature1Match = null;
        Mat feature2Match = null;
        try {
            Utils.bitmapToMat(image1Src, image1);
            Utils.bitmapToMat(image2Src, image2);
            Imgproc.cvtColor(image1, image1, Imgproc.COLOR_RGBA2RGB);
            Imgproc.cvtColor(image2, image2, Imgproc.COLOR_RGBA2RGB);
            faceRecognizerSF.alignCrop(image1, face1Loc, alignedF1);
            faceRecognizerSF.alignCrop(image2, face2Loc, alignedF2);
            // the feature() forward output aliases the net's internal buffer
            // (OutputArray assign shares data by refcount), the next feature()
            // call overwrites it — the clone takes the result out before that
            // next forward
            faceRecognizerSF.feature(alignedF1, feature1);
            feature1Match = feature1.clone();
            faceRecognizerSF.feature(alignedF2, feature2);
            feature2Match = feature2.clone();
            double cosScore = faceRecognizerSF.match(feature1Match, feature2Match, FaceRecognizerSF.FR_COSINE);
            /*
                two faces have same identity if the cosine distance is greater than or equal to 0.363,
                or the normL2 distance is less than or equal to 1.128.
             */
            mLogger.d(TAG, "score:" + cosScore);
            return cosScore >= 0.363;
        } finally {
            image1.release();
            image2.release();
            alignedF1.release();
            alignedF2.release();
            if (feature1 != null) {
                feature1.release();
            }
            if (feature2 != null) {
                feature2.release();
            }
            if (feature1Match != null) {
                feature1Match.release();
            }
            if (feature2Match != null) {
                feature2Match.release();
            }
        }
    }

    protected Rect faceDetectToRect(Mat detectedFace) {
        int x = (int) detectedFace.get(0, 0)[0];
        int y = (int) detectedFace.get(0, 1)[0];
        int w = (int) detectedFace.get(0, 2)[0];
        int h = (int) detectedFace.get(0, 3)[0];
        return new Rect(x, y, x + w, y + h);
    }

    /**
     * Detect faces in the given bitmap with the YuNet face detector, the
     * detector input size is set from the bitmap dimensions on every call
     * (see the class javadoc why the calls are synchronized).
     *
     * @param bitmap image to be scanned, never recycled by this method
     * @return the raw detector output, one row per detected face (see
     * faceDetectToRect for the row layout), the caller owns it and must
     * release() it
     */
    protected synchronized Mat detectFaceRaw(Bitmap bitmap) {
        FaceDetectorYN faceDetectorYN = mEngineInstance.get().getFaceDetectModel();
        faceDetectorYN.setInputSize(new Size(bitmap.getWidth(), bitmap.getHeight()));
        Mat faceInput = new Mat();
        Mat faceOutput = new Mat();
        try {
            Utils.bitmapToMat(bitmap, faceInput);
            Imgproc.cvtColor(faceInput, faceInput, Imgproc.COLOR_RGBA2RGB);
            faceDetectorYN.detect(faceInput, faceOutput);
        } catch (RuntimeException | OutOfMemoryError e) {
            // the caller only receives a Mat on the success path, a failed
            // detection must not leak the empty output header either
            faceOutput.release();
            throw e;
        } finally {
            faceInput.release();
        }
        return faceOutput;
    }

    private Bitmap cropBitmap(final Bitmap originalBmp, Rect dest) {
        return mImageHelper.cropBitmap(originalBmp, dest);
    }
}
