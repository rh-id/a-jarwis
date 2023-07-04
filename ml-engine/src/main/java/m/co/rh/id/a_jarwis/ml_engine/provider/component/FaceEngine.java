package m.co.rh.id.a_jarwis.ml_engine.provider.component;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.hoko.blur.HokoBlur;
import com.hoko.blur.processor.BlurProcessor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import ai.djl.android.core.BitmapImageFactory;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import m.co.rh.id.a_jarwis.base.provider.component.helper.FileHelper;
import m.co.rh.id.a_jarwis.ml_engine.R;
import m.co.rh.id.a_jarwis.ml_engine.model.FaceDetectedObjects;
import m.co.rh.id.a_jarwis.ml_engine.model.FaceRecognizedObjects;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.BlurFaceWorkRequest;
import m.co.rh.id.a_jarwis.ml_engine.workmanager.Params;
import m.co.rh.id.alogger.ILogger;
import m.co.rh.id.aprovider.Provider;

public class FaceEngine {
    private static final String TAG = "FaceEngine";
    private static final String FACE_PATH = "/ml-engine/engine/face";
    private static final String FACE_DETECT_PATH = FACE_PATH + "/detect";
    private static final String FACE_DETECT_FILE = "ultra_light_face_20200320_version_rfb_640.onnx";
    private static final int FACE_DETECT_WIDTH = 640;
    private static final int FACE_DETECT_HEIGHT = 480;
    private static final String FACE_RECOGNITION_PATH = FACE_PATH + "/recognition";
    private static final String FACE_RECOGNITION_FILE = "arcfaceresnet100_20230419_11_int8.onnx";
    private static final int FACE_RECOGNITION_WIDTH = 112;
    private static final int FACE_RECOGNITION_HEIGHT = 112;
    private Context mAppContext;
    private ILogger mLogger;
    private FileHelper mFileHelper;
    private WorkManager mWorkManager;
    private ExecutorService mExecutorService;
    private BlurProcessor mBlurProcessor;
    private ZooModel<Image, FaceDetectedObjects> mFaceDetectModel;
    private ZooModel<Image, FaceRecognizedObjects> mFaceRecognitionModel;

    public FaceEngine(Provider provider) {
        mAppContext = provider.getContext().getApplicationContext();
        mLogger = provider.get(ILogger.class);
        mFileHelper = provider.get(FileHelper.class);
        mWorkManager = provider.get(WorkManager.class);
        mExecutorService = provider.get(ExecutorService.class);
        mBlurProcessor = HokoBlur.with(mAppContext)
                .scheme(HokoBlur.SCHEME_NATIVE) //different implementation, RenderScript、OpenGL、Native(default) and Java
                .mode(HokoBlur.MODE_GAUSSIAN) //blur algorithms，Gaussian、Stack(default) and Box
                .radius(15) //blur radius，max=25，default=5
                .sampleFactor(2.0f) //scale factor，if factor=2，the width and height of a originalBitmap will be scale to 1/2 sizes，default=5
                .forceCopy(false) //If scale factor=1.0f，the origin originalBitmap will be modified. You could set forceCopy=true to avoid it. default=false
                .needUpscale(true) //After blurring，the originalBitmap will be upscaled to origin sizes，default=true
                .translateX(0)//add x axis offset when blurring
                .translateY(0)//add y axis offset when blurring
                .processor(); //build a blur processor
        mFaceDetectModel = loadFaceDetect();
        mFaceRecognitionModel = loadFaceRecognition();
    }

    private ZooModel<Image, FaceRecognizedObjects> loadFaceRecognition() {
        File faceRecognitionParent = new File(mAppContext.getFilesDir(), FACE_RECOGNITION_PATH);
        File faceRecognitionFile = new File(faceRecognitionParent, "/" + FACE_RECOGNITION_FILE);
        if (!faceRecognitionFile.exists()) {
            try {
                faceRecognitionParent.mkdirs();
                faceRecognitionFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.arcfaceresnet100_20230419_11_int8, faceRecognitionFile);
            } catch (IOException e) {
                mLogger.e(TAG, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        try {
            FaceRecognitionTranslator translator =
                    new FaceRecognitionTranslator();
            Criteria<Image, FaceRecognizedObjects> criteria = Criteria.builder()
                    .setTypes(Image.class, FaceRecognizedObjects.class)
                    .optModelPath(faceRecognitionFile.toPath())
                    .optEngine("OnnxRuntime")
                    .optTranslator(translator)
                    .build();
            return criteria.loadModel();
        } catch (Exception e) {
            mLogger.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    private ZooModel<Image, FaceDetectedObjects> loadFaceDetect() {
        File faceDetectParent = new File(mAppContext.getFilesDir(), FACE_DETECT_PATH);
        File faceDetectFile = new File(faceDetectParent, "/" + FACE_DETECT_FILE);
        if (!faceDetectFile.exists()) {
            try {
                faceDetectParent.mkdirs();
                faceDetectFile.createNewFile();
                mFileHelper
                        .copyRawtoFile(R.raw.ultra_light_face_20200320_version_rfb_640, faceDetectFile);
            } catch (IOException e) {
                mLogger.e(TAG, e.getMessage(), e);
                throw new RuntimeException(e);
            }
        }
        try {
            FaceDetectionTranslator translator =
                    new FaceDetectionTranslator();
            Criteria<Image, FaceDetectedObjects> criteria = Criteria.builder()
                    .setTypes(Image.class, FaceDetectedObjects.class)
                    .optModelPath(faceDetectFile.toPath())
                    .optEngine("OnnxRuntime")
                    .optTranslator(translator)
                    .build();
            return criteria.loadModel();
        } catch (Exception e) {
            mLogger.e(TAG, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Process bitmap using face detection engine and return a list of rectangle indicating location of faces
     */
    public List<Rect> detectFace(Bitmap bitmap) {
        List<Rect> rectList = new ArrayList<>();
        FaceDetectedObjects faceDetectedObjects = null;
        try (Predictor<Image, FaceDetectedObjects> predictor = mFaceDetectModel.newPredictor()) {
            faceDetectedObjects = predictor.predict(fromBitmap(bitmap));
        } catch (Exception e) {
            mLogger.e(TAG, e.getMessage(), e);
        }
        if (faceDetectedObjects != null) {
            int faces = faceDetectedObjects.getNumberOfObjects();
            if (faces > 0) {
                List<BoundingBox> boundingBoxes = faceDetectedObjects.getBoundingBoxes();
                for (int i = 0; i < faces; i++) {
                    BoundingBox boundingBox = boundingBoxes.get(i);
                    Rectangle rectangle = boundingBox.getBounds();
                    double x = rectangle.getX();
                    double y = rectangle.getY();
                    double rightD = x + rectangle.getWidth();
                    double bottomD = y + rectangle.getHeight();
                    int left = (int) x;
                    int top = (int) y;
                    int right = (int) rightD;
                    int bottom = (int) bottomD;
                    rectList.add(new Rect(left, top, right, bottom));
                    mLogger.d(TAG, "rect:" + left + "," + top + "," + right + "," + bottom);
                }
            }
        }

        return rectList;
    }

    /**
     * Process bitmap using face detection engine, blur and process the bitmap.
     *
     * @return blurred image or null if face is not detected
     */
    public Bitmap blurFace(Bitmap originalBitmap) {
        Bitmap result = null;
        List<Rect> rectList = detectFace(originalBitmap);
        if (!rectList.isEmpty()) {
            result = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawBitmap(originalBitmap, 0, 0, null);
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (Rect rect : rectList) {
                tasks.add(mExecutorService.submit(
                        () -> {
                            Bitmap faceCrop = cropBitmap(originalBitmap, rect);
                            Bitmap blurredFace = mBlurProcessor.blur(faceCrop);
                            canvas.drawBitmap(blurredFace, null, rect, null);
                            faceCrop.recycle();
                            blurredFace.recycle();
                            return true;
                        }
                ));
            }
            while (!tasks.isEmpty()) {
                Future<Boolean> task = tasks.get(0);
                if (task.isDone()) {
                    try {
                        task.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        tasks.remove(task);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Enqueue blur face to be executed in workmanager
     *
     * @param file image file to be executed
     */
    public void enqueueBlurFace(File file) {
        OneTimeWorkRequest oneTimeWorkRequest = new OneTimeWorkRequest.Builder(BlurFaceWorkRequest.class)
                .setInputData(
                        new Data.Builder()
                                .putString(Params.ARGS_FILE_PATH, file.getAbsolutePath())
                                .build()
                )
                .build();
        mWorkManager.enqueue(oneTimeWorkRequest);
    }

    public void compareFaces(File imageFile1, File imageFile2) {
        // TODO
    }

    private Bitmap cropBitmap(final Bitmap originalBmp, Rect dest) {
        return Bitmap.createBitmap(originalBmp, dest.left, dest.top, dest.width(), dest.height());
    }

    private Image fromBitmap(Bitmap bitmap) {
        return BitmapImageFactory.getInstance().fromImage(bitmap);
    }

    private static class FaceDetectionTranslator implements Translator<Image, FaceDetectedObjects> {

        @Override
        public NDList processInput(TranslatorContext ctx, Image input) {
            int imageWidth = input.getWidth();
            int imageHeight = input.getHeight();
            ctx.setAttachment("imageWidth", imageWidth);
            ctx.setAttachment("imageHeight", imageHeight);
            NDArray ndArray = input.toNDArray(ctx.getNDManager()); // Converts image to RGB NDArray
            ndArray = NDImageUtils.resize(ndArray, FACE_DETECT_WIDTH, FACE_DETECT_HEIGHT);
            ndArray = NDImageUtils.toTensor(ndArray); // Converts an image NDArray of shape HWC in the range [0, 255] to a DataType.FLOAT32 tensor NDArray of shape CHW in the range [0, 1].
            ndArray = NDImageUtils.normalize(ndArray, new float[]{0, 127f, 127f},
                    new float[]{1, 128f, 128f}); // Normalizes an image NDArray of shape CHW or NCHW with mean and standard deviation
            return new NDList(ndArray);
        }

        @Override
        public FaceDetectedObjects processOutput(TranslatorContext ctx, NDList list) {
            NDArray scores = list.get(0);
            NDArray boxes = list.get(1);
            long loop = boxes.size(0);
            // Filter the scores with 0.4 threshold and then sort it
            List<BoxScoreWrapper> boxCollection = new ArrayList<>();
            for (int i = 0; i < loop; i++) {
                NDArray scoreArr = scores.get(i);
                Number[] scoresNum = scoreArr.toArray();

                double probability2 = scoresNum[1].doubleValue();
                if (probability2 >= 0.4) {
                    boxCollection.add(new BoxScoreWrapper(probability2, boxes.get(i)));
                }
                scoreArr.close();
            }
            Collections.sort(boxCollection);
            List<BoxScoreWrapper> selectedBox = calcNms(boxCollection);
            List<String> classNames = new ArrayList<>();
            List<Double> probabilities = new ArrayList<>();
            List<BoundingBox> rectangles = new ArrayList<>();
            int size = selectedBox.size();
            int imageWidth = (int) ctx.getAttachment("imageWidth");
            int imageHeight = (int) ctx.getAttachment("imageHeight");
            for (int i = 0; i < size; i++) {
                BoxScoreWrapper boxScoreWrapper = selectedBox.get(i);
                Number[] box = boxScoreWrapper.box.toArray();
                classNames.add("Face");
                probabilities.add(boxScoreWrapper.score);
                double x = box[0].doubleValue() * imageWidth;
                double y = box[1].doubleValue() * imageHeight;
                double x1 = box[2].doubleValue() * imageWidth;
                double y1 = box[3].doubleValue() * imageHeight;
                rectangles.add(new Rectangle(x, y, x1 - x, y1 - y));
                boxScoreWrapper.box.close();
            }
            selectedBox.clear();
            return new FaceDetectedObjects(classNames, probabilities, rectangles);
        }


        private List<BoxScoreWrapper> calcNms(List<BoxScoreWrapper> boxScoreWrappers) {
            double iouThres = 0.5;
            List<BoxScoreWrapper> selected = new ArrayList<>();
            List<BoxScoreWrapper> inputBoxes = new ArrayList<>(boxScoreWrappers);
            List<BoxScoreWrapper> tesBoxes = new ArrayList<>();
            while (!inputBoxes.isEmpty()) {
                BoxScoreWrapper current = inputBoxes.remove(0);
                selected.add(current);
                if (inputBoxes.size() == 0) {
                    break;
                }
                tesBoxes.addAll(inputBoxes);
                while (!tesBoxes.isEmpty()) {
                    BoxScoreWrapper testBox = tesBoxes.remove(0);
                    NDArray iouArray = ioUOf(current.box, testBox.box);
                    Number[] iouLogic = iouArray.gte(iouThres).toArray();
                    boolean removed = iouLogic[0].intValue() == 1 &&
                            iouLogic[1].intValue() == 1;
                    if (removed) {
                        inputBoxes.remove(testBox);
                    }
                    iouArray.close();
                }
            }
            return selected;
        }

        private NDArray ioUOf(NDArray boxes1, NDArray boxes2) {
            NDList boxes1Split = boxes1.split(2);
            NDList boxes2Split = boxes2.split(2);
            NDArray boxes1XY = boxes1Split.get(0);
            NDArray boxes1X1Y1 = boxes1Split.get(1);
            NDArray boxes2XY = boxes2Split.get(0);
            NDArray boxes2X1Y1 = boxes2Split.get(1);

            NDArray overlapLeftTop = boxes1XY.maximum(boxes2XY);
            NDArray overlapRightBottom = boxes1X1Y1.minimum(boxes2X1Y1);
            NDArray intersectionArea = areaOf(overlapLeftTop, overlapRightBottom);
            NDArray area1 = areaOf(boxes1XY, boxes1X1Y1);
            NDArray area2 = areaOf(boxes2XY, boxes2X1Y1);
            double epsilon = 0.00001; //a small number to avoid 0 as denominator
            return intersectionArea.div(area1.add(area2).sub(intersectionArea).add(epsilon));
        }

        private NDArray areaOf(NDArray leftTop, NDArray rightBottom) {
            return rightBottom.sub(leftTop).clip(0f, Float.MAX_VALUE);
        }

        private static class BoxScoreWrapper implements Comparable<BoxScoreWrapper> {
            private double score;
            private NDArray box;

            public BoxScoreWrapper(double score, NDArray box) {
                this.score = score;
                this.box = box;
            }

            @Override
            public int compareTo(BoxScoreWrapper boxScoreWrapper) {
                return Double.compare(boxScoreWrapper.score, score);
            }

            @NonNull
            @Override
            public String toString() {
                Number[] box = this.box.toArray();
                return "score:" + score + ";box0:" + box[0] + ";box1:" + box[1] +
                        ";box2:" + box[2] + ";box3:" + box[3];
            }
        }
    }

    private static class FaceRecognitionTranslator implements Translator<Image, FaceRecognizedObjects> {

        @Override
        public FaceRecognizedObjects processOutput(TranslatorContext ctx, NDList list) throws Exception {
            // TODO
            return null;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
            // TODO
            return null;
        }
    }
}
