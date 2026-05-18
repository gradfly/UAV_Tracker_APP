package com.example.dronetracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mediapipe.tasks.components.containers.Detection;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private VideoOverlayView overlayView;
    private TextView trackingStatus, horizontalAngle, verticalAngle, distanceValue;
    private SeekBar distanceSlider;
    private Button bluetoothButton, trackingButton;

    private BluetoothManager bluetoothManager;
    private TrackingController trackingController;
    private YoloDetector yoloDetector;
    private Handler mainHandler;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Runnable trackingRunnable;
    private long lastTime;

    private volatile Rect targetRect;
    private String targetLabel;
    private boolean isTracking = false;
    private boolean isBluetoothConnected = false;
    private boolean isProcessingFrame = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initManagers();
        checkPermissions();

        overlayView.setOnTouchListener((v, event) -> {
            handleTouchEvent(event);
            return true;
        });

        distanceSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float ratio = 1.0f - (progress / 100.0f);
                trackingController.setTargetDistanceRatio(ratio);
                distanceValue.setText(String.format("远近距离: %.1f", ratio * 10));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        bluetoothButton.setOnClickListener(v -> toggleBluetooth());
        trackingButton.setOnClickListener(v -> toggleTracking());
    }

    private void initViews() {
        surfaceHolder = ((SurfaceView) findViewById(R.id.surfaceView)).getHolder();
        surfaceHolder.addCallback(this);
        overlayView = findViewById(R.id.overlayView);
        trackingStatus = findViewById(R.id.trackingStatus);
        horizontalAngle = findViewById(R.id.horizontalAngle);
        verticalAngle = findViewById(R.id.verticalAngle);
        distanceValue = findViewById(R.id.distanceValue);
        distanceSlider = findViewById(R.id.distanceSlider);
        bluetoothButton = findViewById(R.id.bluetoothButton);
        trackingButton = findViewById(R.id.trackingButton);

        // 设置距离滑块长度为屏幕高度的一半
        distanceSlider.post(() -> {
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int sliderWidth = screenHeight / 2;
            
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) distanceSlider.getLayoutParams();
            lp.width = sliderWidth;
            lp.height = (int) (60 * getResources().getDisplayMetrics().density);
            // 调整右边距使其旋转后依然居中在右侧60dp条内
            // 旋转中心在滑块中心，计算偏移量
            int sidebarWidth = (int) (60 * getResources().getDisplayMetrics().density);
            lp.rightMargin = -(sliderWidth / 2 - sidebarWidth / 2);
            distanceSlider.setLayoutParams(lp);
        });
    }

    private void initManagers() {
        bluetoothManager = new BluetoothManager(this);
        bluetoothManager.setOnConnectionChangeListener(new BluetoothManager.OnConnectionChangeListener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    isBluetoothConnected = true;
                    bluetoothButton.setText(R.string.bluetooth_disconnect);
                    bluetoothButton.setBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.green));
                    Toast.makeText(MainActivity.this, "蓝牙已连接", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    isBluetoothConnected = false;
                    bluetoothButton.setText(R.string.bluetooth_connect);
                    bluetoothButton.setBackgroundColor(ContextCompat.getColor(MainActivity.this, R.color.primary_color));
                });
            }

            @Override
            public void onConnectionFailed() {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "蓝牙连接失败", Toast.LENGTH_SHORT).show();
                });
            }
        });

        trackingController = new TrackingController();
        yoloDetector = new YoloDetector(this);
        mainHandler = new Handler(Looper.getMainLooper());

        backgroundThread = new HandlerThread("VideoProcessor");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        lastTime = System.currentTimeMillis();
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    @SuppressLint("MissingPermission")
    private void toggleBluetooth() {
        if (isBluetoothConnected) {
            bluetoothManager.disconnect();
        } else {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!adapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                connectToDrone();
            }
        }
    }

    private void connectToDrone() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();

        for (BluetoothDevice device : pairedDevices) {
            if (device.getName().contains("Drone") || device.getName().contains("drone")) {
                bluetoothButton.setText(R.string.connecting);
                bluetoothManager.connectToDevice(device);
                return;
            }
        }

        Toast.makeText(this, "未找到无人机蓝牙设备", Toast.LENGTH_SHORT).show();
    }

    private void toggleTracking() {
        if (!isTracking) {
            if (targetRect == null) {
                Toast.makeText(this, "请先点击视频选择跟踪目标", Toast.LENGTH_SHORT).show();
                return;
            }
            isTracking = true;
            trackingController.setTrackingEnabled(true);
            overlayView.setTrackingEnabled(true);
            trackingButton.setText(R.string.tracking_enabled);
            trackingButton.setBackgroundColor(ContextCompat.getColor(this, R.color.green));
            trackingStatus.setText(R.string.tracking_enabled);
            startTrackingLoop();
        } else {
            // 如果当前正在跟踪，点击按钮则停止跟踪并清除目标
            stopTrackingAndClearUI();
        }
    }

    private void stopTrackingAndClearUI() {
        isTracking = false;
        trackingController.setTrackingEnabled(false);
        overlayView.setTrackingEnabled(false);
        
        synchronized (this) {
            targetRect = null;
            targetLabel = null;
        }

        runOnUiThread(() -> {
            trackingButton.setText(R.string.tracking_disabled);
            trackingButton.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_color));
            trackingStatus.setText(R.string.tracking_disabled);
            
            overlayView.setTargetRect(null);
            overlayView.setTargetName("");
            horizontalAngle.setText(R.string.horizontal_angle);
            verticalAngle.setText(R.string.vertical_angle);
            distanceValue.setText(R.string.distance);
        });

        sendStopCommand();
        stopTrackingLoop();
    }

    private void handleTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();

            if (yoloDetector == null || !yoloDetector.isInitialized()) {
                Toast.makeText(this, "YOLO模型未加载，请检查assets目录下是否有yolov8s.tflite", Toast.LENGTH_LONG).show();
                return;
            }

            // 1. 捕获当前预览画面
            camera.setOneShotPreviewCallback((data, cam) -> {
                Camera.Parameters parameters = cam.getParameters();
                int width = parameters.getPreviewSize().width;
                int height = parameters.getPreviewSize().height;

                // 2. 将 NV21 数据转换为 Bitmap
                android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(data, android.graphics.ImageFormat.NV21, width, height, null);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, out);
                byte[] imageBytes = out.toByteArray();
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                // 3. 旋转 Bitmap 以匹配竖屏显示 (90度)
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(90);
                android.graphics.Bitmap rotatedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);

                // 4. 使用 YOLO 检测点击位置的物体
                float scaleX = (float) rotatedBitmap.getWidth() / overlayView.getWidth();
                float scaleY = (float) rotatedBitmap.getHeight() / overlayView.getHeight();
                float scaledX = x * scaleX;
                float scaledY = y * scaleY;

                Log.d(TAG, String.format("Touch at (%.1f, %.1f), View size: %dx%d, Bitmap size: %dx%d, Scaled touch: (%.1f, %.1f)",
                        x, y, overlayView.getWidth(), overlayView.getHeight(), 
                        rotatedBitmap.getWidth(), rotatedBitmap.getHeight(), scaledX, scaledY));

                Detection detection = yoloDetector.detect(rotatedBitmap, scaledX, scaledY);

                if (detection != null) {
                    android.graphics.RectF boxF = detection.boundingBox();
                    
                    synchronized (MainActivity.this) {
                        // 将检测到的框从 Bitmap 坐标映射回 View 坐标
                        targetRect = new Rect(
                                (int) (boxF.left / scaleX),
                                (int) (boxF.top / scaleY),
                                (int) (boxF.right / scaleX),
                                (int) (boxF.bottom / scaleY)
                        );
                        targetLabel = detection.categories().get(0).categoryName();
                    }
                    
                    runOnUiThread(() -> {
                        overlayView.setTargetRect(targetRect);
                        overlayView.setTargetName(targetLabel);
                        updateTargetMetrics();
                        
                        // 锁定目标后，自动进入“跟踪中”状态
                        isTracking = true;
                        trackingController.setTrackingEnabled(true);
                        overlayView.setTrackingEnabled(true);
                        trackingButton.setText(R.string.tracking_enabled);
                        trackingButton.setBackgroundColor(ContextCompat.getColor(this, R.color.green));
                        trackingStatus.setText(R.string.tracking_enabled);
                        
                        Toast.makeText(this, "已锁定并开启跟踪: " + targetLabel, Toast.LENGTH_SHORT).show();
                        startTrackingLoop();
                    });
                } else {
                    stopTrackingAndClearUI();
                    runOnUiThread(() -> Toast.makeText(this, "未检测到物体", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void updateTargetMetrics() {
        Rect currentRect = targetRect;
        if (currentRect == null) return;

        // 获取当前View的中心点（即画面中心）
        int viewCenterX = overlayView.getWidth() / 2;
        int viewCenterY = overlayView.getHeight() / 2;

        int targetCenterX = currentRect.centerX();
        int targetCenterY = currentRect.centerY();

        // 计算像素距离
        int pixelDX = targetCenterX - viewCenterX;
        int pixelDY = targetCenterY - viewCenterY;

        // 估算远近距离（基于目标框大小，实际应根据YOLO检测到的物体类别和已知尺寸计算）
        float areaRatio = (float) (currentRect.width() * currentRect.height()) / (overlayView.getWidth() * overlayView.getHeight());
        float estimatedDistance = 1.0f / (float) Math.sqrt(areaRatio + 0.01f);

        runOnUiThread(() -> {
            horizontalAngle.setText(String.format("水平距离: %d px", pixelDX));
            verticalAngle.setText(String.format("竖直距离: %d px", pixelDY));
            distanceValue.setText(String.format("远近距离: %.1f", estimatedDistance));
            overlayView.setAngles(pixelDX, pixelDY);
        });
    }

    private void startTrackingLoop() {
        if (trackingRunnable != null) return; // 避免重复启动
        
        lastTime = System.currentTimeMillis();
        trackingRunnable = new Runnable() {
            @Override
            public void run() {
                if (targetRect == null) {
                    stopTrackingLoop();
                    return;
                }

                long currentTime = System.currentTimeMillis();
                float deltaTime = (currentTime - lastTime) / 1000.0f;
                lastTime = currentTime;

                Rect currentRect;
                synchronized (MainActivity.this) {
                    currentRect = targetRect;
                }

                if (currentRect != null) {
                    // 如果不正在处理帧，则请求新帧进行实时检测更新位置
                    if (!isProcessingFrame && camera != null) {
                        isProcessingFrame = true;
                        try {
                            camera.setOneShotPreviewCallback((data, cam) -> {
                                backgroundHandler.post(() -> processFrameForTracking(data, cam));
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set preview callback: " + e.getMessage());
                            isProcessingFrame = false;
                        }
                    }

                    // 即使未开启无人机控制，也计算控制量以获取水平/垂直角度
                    TrackingController.TrackingResult result = trackingController.processTarget(currentRect, deltaTime);

                    // 实时更新UI数值和覆盖层角度
                    updateTargetMetrics();

                    // 只有在开启跟踪且蓝牙连接时才发送实际指令
                    if (isTracking && isBluetoothConnected) {
                        bluetoothManager.sendCommand(result.command);
                    }
                }

                mainHandler.postDelayed(this, 30);
            }
        };
        mainHandler.post(trackingRunnable);
    }

    private void processFrameForTracking(byte[] data, Camera camera) {
        if (targetRect == null) {
            isProcessingFrame = false;
            return;
        }

        try {
            Camera.Parameters parameters = camera.getParameters();
            int width = parameters.getPreviewSize().width;
            int height = parameters.getPreviewSize().height;

            // 1. 将 NV21 数据转换为 Bitmap
            // 优化：降低 JPEG 质量到 60 (YOLO 对此不敏感但压缩速度更快)
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(data, android.graphics.ImageFormat.NV21, width, height, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 60, out);
            byte[] imageBytes = out.toByteArray();
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            // 2. 旋转并缩放
            // 优化：在旋转的同时进行缩放可以减少内存拷贝
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(90);
            // 这里我们保持原样，因为 YoloDetector 内部还会进行一次缩放
            android.graphics.Bitmap rotatedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            
            // 释放中间过程的 bitmap
            if (bitmap != rotatedBitmap) bitmap.recycle();

            // 3. 使用 YOLO 检测当前画面中靠近上一次位置且类别相同的物体
            float scaleX = (float) rotatedBitmap.getWidth() / overlayView.getWidth();
            float scaleY = (float) rotatedBitmap.getHeight() / overlayView.getHeight();
            
            float searchX, searchY;
            String currentLabel;
            synchronized (this) {
                if (targetRect == null) {
                    rotatedBitmap.recycle();
                    return;
                }
                searchX = targetRect.centerX() * scaleX;
                searchY = targetRect.centerY() * scaleY;
                currentLabel = targetLabel;
            }

            Detection detection = yoloDetector.detect(rotatedBitmap, searchX, searchY, currentLabel);
            rotatedBitmap.recycle();

            if (detection != null && isTracking) {
                android.graphics.RectF boxF = detection.boundingBox();
                Rect newRect = new Rect(
                        (int) (boxF.left / scaleX),
                        (int) (boxF.top / scaleY),
                        (int) (boxF.right / scaleX),
                        (int) (boxF.bottom / scaleY)
                );

                synchronized (this) {
                    if (!isTracking) return;
                    targetRect = newRect;
                }

                runOnUiThread(() -> {
                    if (isTracking) {
                        overlayView.setTargetRect(targetRect);
                        overlayView.setTargetName(currentLabel);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame for tracking: " + e.getMessage());
        } finally {
            isProcessingFrame = false;
        }
    }

    private void stopTrackingLoop() {
        if (trackingRunnable != null) {
            mainHandler.removeCallbacks(trackingRunnable);
            trackingRunnable = null;
        }
    }

    private void sendStopCommand() {
        if (isBluetoothConnected) {
            bluetoothManager.sendCommand(new DroneCommand());
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            // 极致优化：将预览分辨率降低到 640x480
            // 这将像素处理量降至最低，基本可以实现 YOLO 的实时满帧检测
            params.setPreviewSize(640, 480);
            camera.setParameters(params);
            Camera.Size previewSize = camera.getParameters().getPreviewSize();
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.startPreview();

            // 调整SurfaceView比例防止拉伸
            runOnUiThread(() -> {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                float aspect = (float) previewSize.width / previewSize.height; // 640/480
                
                View surfaceView = findViewById(R.id.surfaceView);
                android.view.ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
                lp.width = screenWidth;
                lp.height = (int) (screenWidth * aspect);
                surfaceView.setLayoutParams(lp);
                
                overlayView.setLayoutParams(lp);
            });

            trackingController.setFrameSize(previewSize.height, previewSize.width);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open camera", e);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                connectToDrone();
            } else {
                Toast.makeText(this, "蓝牙未启用", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "需要授予所有权限", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopTrackingLoop();
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
        bluetoothManager.disconnect();
        if (camera != null) {
            camera.stopPreview();
            camera.release();
        }
    }
}