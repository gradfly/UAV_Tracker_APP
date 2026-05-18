package com.example.dronetracker;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * YOLO Detector using LiteRT (TensorFlow Lite) Interpreter.
 */
public class YoloDetector {
    private static final String TAG = "YoloDetector";
    private static final String MODEL_FILE = "yolov8s.tflite";
    private static final int INPUT_SIZE = 640;
    private static final float CONFIDENCE_THRESHOLD = 0.3f;

    private Interpreter interpreter;
    private ByteBuffer inputBuffer;
    private int[] intValues;
    private float[][][] output;
    private final List<String> labels = Arrays.asList(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush"
    );

    public YoloDetector(Context context) {
        try {
            Interpreter.Options options = new Interpreter.Options();
            try {
                // 尝试开启 GPU 加速
                options.addDelegate(new GpuDelegate());
                Log.d(TAG, "Using GPU Delegate");
            } catch (Exception e) {
                // 如果 GPU 不可用，则使用多线程 CPU
                options.setNumThreads(4);
                Log.d(TAG, "GPU not available, using CPU with 4 threads");
            }

            interpreter = new Interpreter(loadModelFile(context), options);
            
            inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4);
            inputBuffer.order(ByteOrder.nativeOrder());
            intValues = new int[INPUT_SIZE * INPUT_SIZE];
            output = new float[1][84][8400];
            
            Log.d(TAG, "YOLO model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load YOLO model: " + e.getMessage(), e);
        }
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        try (AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
             FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
             FileChannel fileChannel = inputStream.getChannel()) {
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    public boolean isInitialized() {
        return interpreter != null;
    }

    public synchronized Detection detect(Bitmap bitmap, float touchX, float touchY, String targetLabel) {
        if (interpreter == null) return null;

        // 1. Preprocessing
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
        
        resizedBitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);
        inputBuffer.rewind();
        for (int pixelValue : intValues) {
            inputBuffer.putFloat(((pixelValue >> 16) & 0xFF) / 255.0f);
            inputBuffer.putFloat(((pixelValue >> 8) & 0xFF) / 255.0f);
            inputBuffer.putFloat((pixelValue & 0xFF) / 255.0f);
        }
        inputBuffer.rewind();

        // 2. Inference
        try {
            interpreter.run(inputBuffer, output);
        } catch (Exception e) {
            Log.e(TAG, "Inference failed: " + e.getMessage());
            return null;
        } finally {
            if (resizedBitmap != bitmap) {
                resizedBitmap.recycle();
            }
        }

        // 3. Post-processing
        List<Detection> detections = new ArrayList<>();
        float scaleX = (float) bitmap.getWidth() / INPUT_SIZE;
        float scaleY = (float) bitmap.getHeight() / INPUT_SIZE;

        for (int k = 0; k < 8400; k++) {
            float maxScore = 0f;
            int classId = -1;
            
            // Find class with highest score
            for (int c = 4; c < 84; c++) {
                if (output[0][c][k] > maxScore) {
                    maxScore = output[0][c][k];
                    classId = c - 4;
                }
            }

            if (maxScore > CONFIDENCE_THRESHOLD) {
                String label = labels.get(classId);
                
                // If we are tracking a specific label, filter out others
                if (targetLabel != null && !targetLabel.isEmpty() && !label.equals(targetLabel)) {
                    continue;
                }

                float cx = output[0][0][k];
                float cy = output[0][1][k];
                float w = output[0][2][k];
                float h = output[0][3][k];

                float left, top, right, bottom;
                // Robust coordinate handling
                if (cx > 1.1f || w > 1.1f) {
                    left = (cx - w / 2f) * scaleX;
                    top = (cy - h / 2f) * scaleY;
                    right = (cx + w / 2f) * scaleX;
                    bottom = (cy + h / 2f) * scaleY;
                } else {
                    left = (cx - w / 2f) * bitmap.getWidth();
                    top = (cy - h / 2f) * bitmap.getHeight();
                    right = (cx + w / 2f) * bitmap.getWidth();
                    bottom = (cy + h / 2f) * bitmap.getHeight();
                }

                RectF box = new RectF(left, top, right, bottom);
                Category category = Category.create(maxScore, classId, label, "");
                Detection d = Detection.create(Collections.singletonList(category), box);
                detections.add(d);
            }
        }

        if (detections.isEmpty()) {
            return null;
        }

        // 4. Find the detection closest to the touch point / previous position
        Detection bestMatch = null;
        float minDistance = Float.MAX_VALUE;

        for (Detection d : detections) {
            RectF box = d.boundingBox();
            if (box.contains(touchX, touchY)) {
                float dx = box.centerX() - touchX;
                float dy = box.centerY() - touchY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < minDistance) {
                    minDistance = dist;
                    bestMatch = d;
                }
            }
        }

        if (bestMatch == null) {
            for (Detection d : detections) {
                RectF box = d.boundingBox();
                float dx = Math.max(0, Math.max(box.left - touchX, touchX - box.right));
                float dy = Math.max(0, Math.max(box.top - touchY, touchY - box.bottom));
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                
                // If tracking, allow a larger search radius (800px)
                float radius = (targetLabel == null) ? 500 : 800;
                if (dist < radius && dist < minDistance) {
                    minDistance = dist;
                    bestMatch = d;
                }
            }
        }

        return bestMatch;
    }

    public synchronized Detection detect(Bitmap bitmap, float touchX, float touchY) {
        return detect(bitmap, touchX, touchY, null);
    }
}
