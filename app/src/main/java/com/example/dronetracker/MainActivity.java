package com.example.dronetracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mediapipe.tasks.components.containers.Detection;
import com.example.dronetracker.SimpleUVCCameraTextureView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 1;

    private SimpleUVCCameraTextureView surfaceView;
    private Camera camera;
    private VideoOverlayView overlayView;
    private TextView trackingStatus, horizontalAngle, verticalAngle, distanceValue, hintMessage;
    private SeekBar distanceSlider;
    private TextView sliderValueTip;
    private SwitchCompat switchUnlock, switchAutoMode;
    private View manualControlLayout, joystickLayout;
    private JoystickView joystickLeft, joystickRight;
    private Button bluetoothButton, trackingButton, switchCameraButton;
    private TextView logTextView;
    private View logPanel;
    private android.widget.ScrollView logScrollView;
    private Button btnTurnLeft, btnForward, btnTurnRight, btnLeft, btnTakeoff, btnRight, btnUp, btnBackward, btnDown;

    private ActivityResultLauncher<Intent> bluetoothEnableLauncher;

    private enum ControlMode { AUTO, MANUAL }
    private ControlMode currentControlMode = ControlMode.AUTO;

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
    private boolean isFlying = false;
    private long lastFlightCommandTime = 0;
    private long lastHintTime = 0;
    private float curLeftX = 0, curLeftY = 0, curRightY = 0;

    // USB Camera members
    private UVCCameraManager mCameraHelper;
    private boolean isCameraRequest = false;
    private boolean isCameraConnected = false;
    private boolean useUsbCamera = false;

    private long targetSessionId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothEnableLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        connectToDrone();
                    } else {
                        Toast.makeText(this, "蓝牙未启用", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        initViews();
        initManagers();
        checkPermissions();

        overlayView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.performClick();
            }
            handleTouchEvent(event);
            return true;
        });

        distanceSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 更新控制器中的距离参数
                trackingController.onProgressChanged(progress);
                
                // 更新 UI 提示
                float displayValue = 10.0f - (progress / 100.0f * 9.0f);
                updateSliderTip(progress, displayValue);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // 初始化距离参数，同步 UI 和控制器
        int initialProgress = distanceSlider.getProgress();
        trackingController.onProgressChanged(initialProgress);
        updateSliderTip(initialProgress, 10.0f - (initialProgress / 100.0f * 9.0f));

        bluetoothButton.setOnClickListener(v -> toggleBluetooth());
        bluetoothButton.setOnLongClickListener(v -> {
            if (logPanel != null) {
                int visibility = logPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
                logPanel.setVisibility(visibility);
                if (visibility == View.VISIBLE) {
                    Toast.makeText(this, "日志面板已开启", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        });
        trackingButton.setOnClickListener(v -> toggleTracking());
        switchCameraButton.setOnClickListener(v -> showCameraSwitchDialog());

        initFlightControls();
    }

    private void initFlightControls() {
        switchUnlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // 解锁命令
                sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x07});
                switchUnlock.setText("已解锁");
                int greenColor = ContextCompat.getColor(this, R.color.green);
                switchUnlock.setTextColor(greenColor);
                switchUnlock.setThumbTintList(ColorStateList.valueOf(greenColor));
                switchUnlock.setTrackTintList(ColorStateList.valueOf(greenColor));
            } else {
                // 上锁命令
                sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x08});
                switchUnlock.setText("已上锁");
                int whiteColor = ContextCompat.getColor(this, R.color.white);
                switchUnlock.setTextColor(whiteColor);
                switchUnlock.setThumbTintList(null); // 恢复默认
                switchUnlock.setTrackTintList(null); // 恢复默认
            }
        });

        switchAutoMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                switchAutoMode.setText("手动");
                int greenColor = ContextCompat.getColor(this, R.color.green);
                switchAutoMode.setTextColor(greenColor);
                switchAutoMode.setThumbTintList(ColorStateList.valueOf(greenColor));
                switchAutoMode.setTrackTintList(ColorStateList.valueOf(greenColor));
                
                manualControlLayout.setVisibility(View.INVISIBLE);
                joystickLayout.setVisibility(View.VISIBLE);
                currentControlMode = ControlMode.MANUAL;

                if (trackingButton.getText().toString().equals(getString(R.string.tracking_enabled))) {
                    trackingButton.performClick();
                }
            } else {
                switchAutoMode.setText("自动");
                int whiteColor = ContextCompat.getColor(this, R.color.white);
                switchAutoMode.setTextColor(whiteColor);
                switchAutoMode.setThumbTintList(null);
                switchAutoMode.setTrackTintList(null);
                currentControlMode = ControlMode.AUTO;
                
                manualControlLayout.setVisibility(View.VISIBLE);
                joystickLayout.setVisibility(View.GONE);
            }
        });

        btnTurnLeft.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x10}));
        btnForward.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x03}));
        btnTurnRight.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x11}));
        btnLeft.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x05}));
        btnTakeoff.setOnClickListener(v -> {
            if (!isFlying) {
                if (sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x02})) {
                    btnTakeoff.setText("降落");
                    isFlying = true;
                }
            } else {
                if (sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x09})) {
                    btnTakeoff.setText("起飞");
                    isFlying = false;
                }
            }
        });
        btnRight.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x06}));
        btnUp.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x12}));
        btnBackward.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x04}));
        btnDown.setOnClickListener(v -> sendFlightCommand(new byte[]{(byte) 0x80, (byte) 0x13}));
    }

    private boolean sendFlightCommand(byte[] data) {
        if (isBluetoothConnected && bluetoothManager != null) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFlightCommandTime < 2000) {
                return false;
            }
            bluetoothManager.sendRawData(data);
            lastFlightCommandTime = currentTime;
            return true;
        } else {
            Toast.makeText(this, "蓝牙未连接", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void initUsbCamera() {
        if (mCameraHelper != null) {
            return;
        }

        mCameraHelper = new UVCCameraManager(this, surfaceView);
        mCameraHelper.setDefaultFrameFormat(UVCCameraManager.FRAME_FORMAT_YUYV);
        mCameraHelper.setOnConnectListener(new UVCCameraManager.OnConnectListener() {
            @Override
            public void onAttachDev(android.hardware.usb.UsbDevice device) {
                runOnUiThread(() -> {
                    if (!isCameraRequest && surfaceView != null && surfaceView.isAvailable()) {
                        isCameraRequest = true;
                        try {
                            if (mCameraHelper != null) {
                                mCameraHelper.requestPermission(device);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to request USB permission", e);
                            isCameraRequest = false;
                        }
                    }
                });
            }

            @Override
            public void onDettachDev(android.hardware.usb.UsbDevice device) {
                runOnUiThread(() -> {
                    isCameraRequest = false;
                    isCameraConnected = false;
                    if (mCameraHelper != null) {
                        try {
                            mCameraHelper.closeCamera();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to close USB camera", e);
                        }
                    }
                    Toast.makeText(MainActivity.this, "USB摄像头已断开", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onConnectDev(android.hardware.usb.UsbDevice device, boolean isConnected) {
                isCameraConnected = isConnected;
                if (isConnected && useUsbCamera) {
                    runOnUiThread(() -> startUsbPreview());
                }
            }

            @Override
            public void onDisConnectDev(android.hardware.usb.UsbDevice device) {
                isCameraConnected = false;
            }
        });

        mCameraHelper.setOnPreviewFrameListener(nv21 -> {
            if (isTracking && !isProcessingFrame && useUsbCamera) {
                isProcessingFrame = true;
                backgroundHandler.post(() -> processUsbFrame(nv21));
            }
        });
    }

    private void startUsbPreview() {
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Activity is finishing, skipping USB preview start");
            return;
        }
        
        if (mCameraHelper == null) {
            Log.w(TAG, "Camera helper is null");
            return;
        }
        
        if (surfaceView == null) {
            Log.w(TAG, "SurfaceView is null");
            return;
        }
        
        if (!surfaceView.isAvailable()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "等待预览界面准备就绪...", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        try {
            mCameraHelper.startPreview(null);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int sidebarWidth = (int) (60 * getResources().getDisplayMetrics().density);
                int availableWidth = screenWidth - sidebarWidth;
                
                float aspect = 640.0f / 480.0f;
                android.view.ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
                lp.width = availableWidth;
                lp.height = (int) (availableWidth / aspect);
                surfaceView.setLayoutParams(lp);
                
                if (overlayView != null) {
                    overlayView.setLayoutParams(lp);
                }
                
                if (trackingController != null) {
                    trackingController.setFrameSize(lp.width, lp.height);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "启动USB预览失败", e);
            runOnUiThread(() -> {
                Toast.makeText(this, "启动USB预览失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void processUsbFrame(byte[] nv21) {
        if (targetRect == null) {
            isProcessingFrame = false;
            return;
        }

        int width = 640;
        int height = 480;

        try {
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 60, out);
            byte[] imageBytes = out.toByteArray();
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from USB frame");
                isProcessingFrame = false;
                return;
            }

            processFrameWithBitmap(bitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error processing USB frame", e);
            isProcessingFrame = false;
        }
    }

    private void processFrameWithBitmap(android.graphics.Bitmap bitmap) {
        long currentSession;
        float scaleX = (float) bitmap.getWidth() / overlayView.getWidth();
        float scaleY = (float) bitmap.getHeight() / overlayView.getHeight();
        
        float searchX, searchY;
        String currentLabel;
        synchronized (this) {
            if (targetRect == null) {
                bitmap.recycle();
                isProcessingFrame = false;
                return;
            }
            searchX = targetRect.centerX() * scaleX;
            searchY = targetRect.centerY() * scaleY;
            currentLabel = targetLabel;
            currentSession = targetSessionId;
        }

        Detection detection = yoloDetector.detect(bitmap, searchX, searchY, currentLabel);
        bitmap.recycle();

        if (detection != null) {
            android.graphics.RectF boxF = detection.boundingBox();
            Rect newRect = new Rect(
                    (int) (boxF.left / scaleX),
                    (int) (boxF.top / scaleY),
                    (int) (boxF.right / scaleX),
                    (int) (boxF.bottom / scaleY)
            );

            synchronized (this) {
                // 只有在 Session ID 匹配时才更新，防止覆盖用户手动选择的新目标
                if (isTracking && targetSessionId == currentSession) {
                    targetRect = newRect;
                } else {
                    isProcessingFrame = false;
                    return;
                }
            }

            runOnUiThread(() -> {
                if (isTracking) {
                    overlayView.setTargetRect(targetRect);
                    overlayView.setTargetName(currentLabel);
                }
            });
        }
        isProcessingFrame = false;
    }

    private void showCameraSwitchDialog() {
        String[] options = {getString(R.string.phone_camera), getString(R.string.usb_camera)};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.switch_camera)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) { // 手机摄像头
                        if (useUsbCamera) {
                            switchToPhoneCamera();
                        }
                    } else { // USB 摄像头
                        if (!useUsbCamera) {
                            switchToUsbCamera();
                        }
                    }
                })
                .show();
    }

    private void switchToPhoneCamera() {
        stopTrackingAndClearUI();
        if (mCameraHelper != null) {
            mCameraHelper.closeCamera();
        }
        useUsbCamera = false;
        initPhoneCamera();
    }

    private void switchToUsbCamera() {
        stopTrackingAndClearUI();
        
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release phone camera", e);
            } finally {
                camera = null;
            }
        }
        
        useUsbCamera = true;
        
        if (isCameraConnected && mCameraHelper != null) {
            startUsbPreview();
        } else {
            runOnUiThread(() -> {
                Toast.makeText(this, "USB摄像头未连接或未授权", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void initPhoneCamera() {
        if (surfaceView.isAvailable()) {
            initPhoneCameraWithSurface(surfaceView.getSurfaceTexture());
        }
    }

    private void initPhoneCameraWithSurface(android.graphics.SurfaceTexture surface) {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setPreviewSize(640, 480);
            camera.setParameters(params);
            Camera.Size previewSize = camera.getParameters().getPreviewSize();
            camera.setDisplayOrientation(90);
            camera.setPreviewTexture(surface);
            camera.startPreview();

            runOnUiThread(() -> {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                int sidebarWidth = (int) (60 * getResources().getDisplayMetrics().density);
                int availableWidth = screenWidth - sidebarWidth;
                
                float aspect = (float) previewSize.width / previewSize.height;

                android.view.ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
                lp.width = availableWidth;
                lp.height = (int) (availableWidth * aspect);

                overlayView.setLayoutParams(lp);
                trackingController.setFrameSize(lp.width, lp.height);
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to open phone camera", e);
            if (camera != null) {
                camera.release();
                camera = null;
            }
        }
    }

    private void updateSliderTip(int progress, float displayValue) {
        sliderValueTip.setText(String.format(java.util.Locale.CHINA, "%.1f", displayValue));

        int sliderWidth = distanceSlider.getWidth();
        if (sliderWidth == 0) return;

        int thumbOffset = (int) ((float) (100 - progress) / 100.0f * (sliderWidth - distanceSlider.getPaddingLeft() - distanceSlider.getPaddingRight()));

        int centerY = distanceSlider.getTop() + distanceSlider.getHeight() / 2;
        int tipY = centerY - (sliderWidth / 2) + thumbOffset + distanceSlider.getPaddingLeft() - (sliderValueTip.getHeight() / 2);

        sliderValueTip.setY(tipY);
    }

    private void initViews() {
        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                if (isFinishing() || isDestroyed()) {
                    Log.w(TAG, "Activity is finishing, skipping surface initialization");
                    return;
                }
                
                if (mCameraHelper == null) {
                    try {
                        initUsbCamera();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to initialize USB camera", e);
                    }
                }
                
                checkAndRegisterUsb();

                if (!useUsbCamera) {
                    try {
                        initPhoneCameraWithSurface(surface);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to initialize phone camera", e);
                    }
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                if (camera != null) {
                    try {
                        camera.stopPreview();
                        camera.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to release phone camera", e);
                    } finally {
                        camera = null;
                    }
                }

                if (mCameraHelper != null) {
                    try {
                        mCameraHelper.unregister();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to unregister USB", e);
                    }
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {}
        });
        overlayView = findViewById(R.id.overlayView);
        trackingStatus = findViewById(R.id.trackingStatus);
        horizontalAngle = findViewById(R.id.horizontalAngle);
        verticalAngle = findViewById(R.id.verticalAngle);
        distanceValue = findViewById(R.id.distanceValue);
        distanceSlider = findViewById(R.id.distanceSlider);
        sliderValueTip = findViewById(R.id.sliderValueTip);
        bluetoothButton = findViewById(R.id.bluetoothButton);
        trackingButton = findViewById(R.id.trackingButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
        hintMessage = findViewById(R.id.hintMessage);

        switchUnlock = findViewById(R.id.switch1);
        switchAutoMode = findViewById(R.id.switch2);

        btnTurnLeft = findViewById(R.id.btnTurnLeft);
        btnForward = findViewById(R.id.btnForward);
        btnTurnRight = findViewById(R.id.btnTurnRight);
        btnLeft = findViewById(R.id.btnLeft);
        btnTakeoff = findViewById(R.id.btnTakeoff);
        btnRight = findViewById(R.id.btnRight);
        btnUp = findViewById(R.id.btnUp);
        btnBackward = findViewById(R.id.btnBackward);
        btnDown = findViewById(R.id.btnDown);

        manualControlLayout = findViewById(R.id.manualControlLayout);
        joystickLayout = findViewById(R.id.joystickLayout);
        joystickLeft = findViewById(R.id.joystickLeft);
        joystickRight = findViewById(R.id.joystickRight);

        logPanel = findViewById(R.id.logPanel);
        logTextView = findViewById(R.id.logTextView);
        logScrollView = findViewById(R.id.logScrollView);
        TextView btnClearLog = findViewById(R.id.btnClearLog);
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> {
                if (logTextView != null) logTextView.setText("");
            });
        }

        // 左侧摇杆：垂直方向改为自动回中，并注释掉默认状态最低点的代码
        // joystickLeft.setInitialPosition(0, -1);
        joystickLeft.setAutoCenter(true, true);

        // 美国手 Mode 2: 左手控制升降(Y)和旋转(X)
        joystickLeft.setOnJoystickChangeListener((xPercent, yPercent) -> {
            if (checkTrackingState(xPercent, yPercent)) return;
            curLeftX = xPercent;
            curLeftY = yPercent;
            Log.d("Joystick", "Left Stick - Yaw: " + xPercent + ", Throttle: " + yPercent);
        });

        // 美国手 Mode 2: 右手控制前后(Y)和左右平移(X)
        joystickRight.setOnJoystickChangeListener((xPercent, yPercent) -> {
            if (checkTrackingState(xPercent, yPercent)) return;
            curRightY = yPercent;
            Log.d("Joystick", "Right Stick - Roll: " + xPercent + ", Pitch: " + yPercent);
        });

        // 设置距离滑块长度为屏幕高度的一半
        distanceSlider.post(() -> {
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int sliderWidth = (int) (screenHeight / 1.2);
            
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) distanceSlider.getLayoutParams();
            lp.width = sliderWidth;
            lp.height = (int) (60 * getResources().getDisplayMetrics().density);
            // 调整右边距使其旋转后依然居中在右侧60dp条内
            // 旋转中心在滑块中心，计算偏移量
            int sidebarWidth = (int) (60 * getResources().getDisplayMetrics().density);
            lp.rightMargin = -(sliderWidth / 2 - sidebarWidth / 2);
            distanceSlider.setLayoutParams(lp);

            // 确保在布局完成后更新一次位置
            sliderValueTip.post(() -> {
                int initialProgress = distanceSlider.getProgress();
                updateSliderTip(initialProgress, 10.0f - (initialProgress / 100.0f * 9.0f));
            });
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
        
        bluetoothManager.setOnDataReceivedListener(new BluetoothManager.OnDataReceivedListener() {
            @Override
            public void onDataSent(byte[] data) {
                addLog("TX", data);
            }

            @Override
            public void onDataReceived(byte[] data) {
                addLog("RX", data);
            }
        });
        
        mainHandler = new Handler(Looper.getMainLooper());
        
        startTrackingLoop();

        backgroundThread = new HandlerThread("VideoProcessor");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        lastTime = System.currentTimeMillis();
    }

    private void checkPermissions() {
        List<String> permissionList = new ArrayList<>();
        permissionList.add(Manifest.permission.CAMERA);
        permissionList.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissionList.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionList.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        String[] permissions = permissionList.toArray(new String[0]);

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        } else {
            checkAndRegisterUsb();
        }
    }

    private void checkAndRegisterUsb() {
        if (isFinishing() || isDestroyed()) {
            Log.w(TAG, "Activity is finishing, skipping USB registration");
            return;
        }
        
        if (mCameraHelper == null) {
            Log.w(TAG, "Camera helper is null");
            return;
        }
        
        if (surfaceView == null) {
            Log.w(TAG, "SurfaceView is null");
            return;
        }
        
        if (!surfaceView.isAvailable()) {
            Log.w(TAG, "SurfaceView is not available");
            return;
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted");
            return;
        }
        
        try {
            mCameraHelper.register();
        } catch (Exception e) {
            Log.e(TAG, "Failed to register USB monitor", e);
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
                bluetoothEnableLauncher.launch(enableBtIntent);
            } else {
                connectToDrone();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void connectToDrone() {
        List<BluetoothDevice> deviceList = new ArrayList<>();
        BottomSheetDialog bottomSheetDialog = new BottomSheetDialog(this);

        BluetoothDeviceAdapter deviceAdapter = new BluetoothDeviceAdapter(deviceList, device -> {
            bluetoothManager.stopScan();
            bluetoothButton.setText(R.string.connecting);
            bluetoothManager.connectToDevice(device);
            bottomSheetDialog.dismiss();
        });

        View bottomSheetView = LayoutInflater.from(this).inflate(R.layout.layout_bluetooth_bottom_sheet, null);
        bottomSheetDialog.setContentView(bottomSheetView);

        RecyclerView recyclerView = bottomSheetView.findViewById(R.id.deviceRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(deviceAdapter);

        bottomSheetDialog.setOnDismissListener(dialog -> bluetoothManager.stopScan());
        bottomSheetDialog.show();

        // 开始扫描 BLE 设备
        bluetoothManager.startScan(device -> {
            runOnUiThread(() -> {
                boolean exists = false;
                for (BluetoothDevice d : deviceList) {
                    if (d.getAddress().equals(device.getAddress())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    deviceList.add(device);
                    deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                }
            });
        });

        // 也可以先把已配对的设备加进去
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
            if (pairedDevices != null) {
                for (BluetoothDevice device : pairedDevices) {
                    if (!deviceList.contains(device)) {
                        deviceList.add(device);
                    }
                }
                deviceAdapter.notifyDataSetChanged();
            }
        }
    }

    // 蓝牙设备适配器
    private static class BluetoothDeviceAdapter extends RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder> {
        private final List<BluetoothDevice> devices;
        private final OnDeviceClickListener listener;

        interface OnDeviceClickListener {
            void onDeviceClick(BluetoothDevice device);
        }

        BluetoothDeviceAdapter(List<BluetoothDevice> devices, OnDeviceClickListener listener) {
            this.devices = devices;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bluetooth_device, parent, false);
            return new ViewHolder(view);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BluetoothDevice device = devices.get(position);
            holder.nameText.setText(device.getName() != null ? device.getName() : "Unknown Device");
            holder.addressText.setText(device.getAddress());
            holder.itemView.setOnClickListener(v -> listener.onDeviceClick(device));
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView nameText;
            TextView addressText;

            ViewHolder(View itemView) {
                super(itemView);
                nameText = itemView.findViewById(R.id.deviceName);
                addressText = itemView.findViewById(R.id.deviceAddress);
            }
        }
    }

    private void toggleTracking() {
        if (targetRect == null) {
            Toast.makeText(this, "请先点击视频选择跟踪目标", Toast.LENGTH_SHORT).show();
            return;
        }

        String trackingText = getString(R.string.tracking_enabled);
        if (trackingButton.getText().toString().equals(trackingText)) {
            // 当前正在跟踪 -> 切换为“暂停跟踪”
            trackingButton.setText("暂停跟踪");
            trackingStatus.setText("暂停跟踪");
            // 立即发送一次停止指令给无人机
            sendStopCommand();
        } else {
            // 当前是“未跟踪”或“暂停跟踪” -> 开启/恢复跟踪
            isTracking = true;
            trackingController.setTrackingEnabled(true);
            overlayView.setTrackingEnabled(true);
            trackingButton.setText(trackingText);
            trackingButton.setBackgroundColor(ContextCompat.getColor(this, R.color.green));
            trackingStatus.setText(trackingText);
            startTrackingLoop();
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
            
            // overlayView.setTargetRect(null);
            // overlayView.setTargetName("");
            //updateTargetMetrics(null);
        });

        sendStopCommand();
        // stopTrackingLoop();
    }

    private void handleTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();

            if (yoloDetector == null || !yoloDetector.isInitialized()) {
                Toast.makeText(this, "YOLO模型未加载，请检查assets目录下是否有yolov8s.tflite", Toast.LENGTH_LONG).show();
                return;
            }

            if (useUsbCamera) {
                // USB 摄像头截图处理
                if (mCameraHelper != null && getExternalCacheDir() != null) {
                    mCameraHelper.capturePicture(getExternalCacheDir().getAbsolutePath() + "/temp.jpg", new UVCCameraManager.CaptureCallback() {
                        @Override
                        public void onCaptureSuccess(String path) {
                            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(path);
                            if (bitmap != null) {
                                processTouchDetection(bitmap, x, y);
                            } else {
                                runOnUiThread(() -> Toast.makeText(MainActivity.this, "截图失败", Toast.LENGTH_SHORT).show());
                            }
                        }

                        @Override
                        public void onCaptureFailed(Exception e) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "截图失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                        }
                    });
                }
            } else if (camera != null) {
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
                    if (bitmap != rotatedBitmap) bitmap.recycle();

                    processTouchDetection(rotatedBitmap, x, y);
                });
            }
        }
    }

    private void processTouchDetection(android.graphics.Bitmap rotatedBitmap, float x, float y) {
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
                // 增加 Session ID，使正在运行的后台识别失效
                targetSessionId++;
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
                updateTargetMetrics(null);
                
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
        rotatedBitmap.recycle();
    }

    private void updateTargetMetrics(TrackingController.TrackingResult result) {
        Rect currentRect = targetRect;
        if (currentRect == null) return;

        // 获取当前View的中心点（即画面中心）
        int viewCenterX = overlayView.getWidth() / 2;
        int viewCenterY = overlayView.getHeight() / 2;

        int targetCenterX = currentRect.centerX();
        int targetCenterY = currentRect.centerY();

        // 计算像素距离
        int pixelDX = targetCenterX - viewCenterX;
        // 屏幕坐标系 Y 向下增加，要求“以上为正”，即 viewCenterY - targetCenterY
        int pixelDY = viewCenterY - targetCenterY;

        // 估算远近距离
        float areaRatio = (float) (currentRect.width() * currentRect.height()) / (overlayView.getWidth() * overlayView.getHeight());
        float estimatedDistance = 1.0f / (float) Math.sqrt(areaRatio + 0.01f);

        runOnUiThread(() -> {
            if (result != null) {
                DroneCommand cmd = result.command;
                horizontalAngle.setText(String.format(java.util.Locale.CHINA, "水平距离: %d px, Yaw：%d", pixelDX, (int)(cmd.getYaw()*10)));
                verticalAngle.setText(String.format(java.util.Locale.CHINA, "竖直距离: %d px, Throttle：%d", pixelDY, (int)(cmd.getThrottle()*estimatedDistance)));
                distanceValue.setText(String.format(java.util.Locale.CHINA, "远近距离: %.1f, Pitch：%d", estimatedDistance, (int)(cmd.getPitch()*20)));
            } else {
                horizontalAngle.setText(String.format(java.util.Locale.CHINA, "水平: %d px (已暂停)", pixelDX));
                verticalAngle.setText(String.format(java.util.Locale.CHINA, "竖直: %d px (已暂停)", pixelDY));
                distanceValue.setText(String.format(java.util.Locale.CHINA, "距离: %.1f (已暂停)", estimatedDistance));
            }
            overlayView.setAngles(pixelDX, pixelDY);
        });
    }

    private void startTrackingLoop() {
        if (trackingRunnable != null) return;
        
        lastTime = System.currentTimeMillis();
        trackingRunnable = new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                float deltaTime = (currentTime - lastTime) / 1000.0f;
                lastTime = currentTime;

                // 防止 deltaTime 过大导致 PID 剧烈波动
                if (deltaTime > 0.5f) deltaTime = 0.1f;

                // 1. 获取当前目标位置
                Rect currentRect;
                synchronized (MainActivity.this) {
                    currentRect = targetRect;
                }

                // 2. 只有在自动模式下才进行跟踪逻辑
                if (currentControlMode == ControlMode.AUTO && currentRect != null) {
                    // 触发帧处理 (仅限手机摄像头，USB摄像头由监听器触发)
                    if (!isProcessingFrame && !useUsbCamera && camera != null) {
                        isProcessingFrame = true;
                        try {
                            camera.setOneShotPreviewCallback((data, cam) -> {
                                backgroundHandler.post(() -> processFrameForTracking(data, cam));
                            });
                        } catch (Exception e) {
                            isProcessingFrame = false;
                        }
                    }

                    // 计算控制指令
                    TrackingController.TrackingResult result = trackingController.processTarget(currentRect, deltaTime);
                    
                    // 更新 UI (无论蓝牙是否连接)
                    String btnText = trackingButton.getText().toString();
                    if (btnText.equals(getString(R.string.tracking_enabled))) {
                        updateTargetMetrics(result);
                    } else {
                        updateTargetMetrics(null);
                    }

                    // 3. 如果蓝牙已连接，发送指令
                    if (isBluetoothConnected && bluetoothManager != null) {
                        byte[] sendData = new byte[4];
                        sendData[0] = (byte) 0xAA;
                        
                        if (btnText.equals(getString(R.string.tracking_enabled))) {
                            byte[] cmdBytes = result.command.toBytes();
                            System.arraycopy(cmdBytes, 1, sendData, 1, 3);
                        }
                        bluetoothManager.sendRawData(sendData);
                    }
                } else if (currentControlMode == ControlMode.MANUAL) {
                    // 手动模式：仅在蓝牙连接时发送摇杆数据
                    if (isBluetoothConnected && bluetoothManager != null) {
                        byte[] sendData = new byte[4];
                        sendData[0] = (byte) 0xAA;
                        sendData[1] = (byte) (curLeftX * 125);
                        sendData[2] = (byte) (curLeftY * 125);
                        sendData[3] = (byte) (curRightY * 125);
                        bluetoothManager.sendRawData(sendData);
                    }
                } else {
                    // 自动模式但无目标，若连接则发送心跳
                    if (isBluetoothConnected && bluetoothManager != null) {
                        bluetoothManager.sendRawData(new byte[]{(byte) 0xAA, 0, 0, 0});
                    }
                }

                mainHandler.postDelayed(this, 100);
            }
        };
        mainHandler.post(trackingRunnable);
    }

    private void processFrameForTracking(byte[] data, Camera camera) {
        long currentSession;
        synchronized (this) {
            if (targetRect == null) {
                isProcessingFrame = false;
                return;
            }
            currentSession = targetSessionId;
        }

        try {
            Camera.Parameters parameters = camera.getParameters();
            int width = parameters.getPreviewSize().width;
            int height = parameters.getPreviewSize().height;

            // 1. 将 NV21 数据转换为 Bitmap
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(data, android.graphics.ImageFormat.NV21, width, height, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 60, out);
            byte[] imageBytes = out.toByteArray();
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

            // 2. 旋转
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(90);
            android.graphics.Bitmap rotatedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            if (bitmap != rotatedBitmap) bitmap.recycle();

            // 3. 使用 YOLO 检测
            float scaleX = (float) rotatedBitmap.getWidth() / overlayView.getWidth();
            float scaleY = (float) rotatedBitmap.getHeight() / overlayView.getHeight();
            
            float searchX, searchY;
            String currentLabel;
            synchronized (this) {
                if (targetRect == null || targetSessionId != currentSession) {
                    rotatedBitmap.recycle();
                    isProcessingFrame = false;
                    return;
                }
                searchX = targetRect.centerX() * scaleX;
                searchY = targetRect.centerY() * scaleY;
                currentLabel = targetLabel;
            }

            Detection detection = yoloDetector.detect(rotatedBitmap, searchX, searchY, currentLabel);
            rotatedBitmap.recycle();

            if (detection != null) {
                android.graphics.RectF boxF = detection.boundingBox();
                Rect newRect = new Rect(
                        (int) (boxF.left / scaleX),
                        (int) (boxF.top / scaleY),
                        (int) (boxF.right / scaleX),
                        (int) (boxF.bottom / scaleY)
                );

                synchronized (this) {
                    // 再次检查 Session ID
                    if (isTracking && targetSessionId == currentSession) {
                        targetRect = newRect;
                    } else {
                        isProcessingFrame = false;
                        return;
                    }
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

    private void addLog(String type, byte[] data) {
        if (logTextView == null) return;
        
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        
        String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
        String logEntry = String.format("[%s] %s: %s\n", time, type, sb.toString().trim());
        
        runOnUiThread(() -> {
            logTextView.append(logEntry);
            
            // Limit log size to prevent memory issues
            if (logTextView.getLineCount() > 100) {
                String fullText = logTextView.getText().toString();
                int firstNewline = fullText.indexOf('\n');
                if (firstNewline != -1) {
                    logTextView.setText(fullText.substring(firstNewline + 1));
                }
            }
            
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkAndRegisterUsb();
            } else {
                Toast.makeText(this, "需要授予所有权限才能运行", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mCameraHelper != null && surfaceView != null && surfaceView.isAvailable()) {
            try {
                mCameraHelper.register();
            } catch (Exception e) {
                Log.e(TAG, "Failed to register USB in onResume", e);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mCameraHelper != null) {
            try {
                mCameraHelper.unregister();
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister USB in onStop", e);
            }
        }
    }

    private void sendManualJoystickCommand() {
        // 已整合进 startTrackingLoop
    }

    private boolean checkTrackingState(float x, float y) {
        if (Math.abs(x) < 0.05f && Math.abs(y) < 0.05f) return false;

        if (trackingButton.getText().toString().equals(getString(R.string.tracking_enabled))) {
            showHintAnimation();
            return true;
        }
        return false;
    }

    private void showHintAnimation() {
        if (System.currentTimeMillis() - lastHintTime < 2000) return;
        lastHintTime = System.currentTimeMillis();

        runOnUiThread(() -> {
            hintMessage.setVisibility(View.VISIBLE);
            hintMessage.setAlpha(1.0f);
            hintMessage.animate()
                    .alpha(0.0f)
                    .setDuration(1000)
                    .setStartDelay(1000)
                    .withEndAction(() -> hintMessage.setVisibility(View.GONE))
                    .start();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        stopTrackingLoop();
        
        if (backgroundThread != null) {
            try {
                backgroundThread.quitSafely();
            } catch (Exception e) {
                Log.e(TAG, "Failed to quit background thread", e);
            }
        }
        
        if (bluetoothManager != null) {
            try {
                bluetoothManager.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to disconnect bluetooth", e);
            }
        }
        
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release phone camera", e);
            } finally {
                camera = null;
            }
        }
        
        if (mCameraHelper != null) {
            try {
                mCameraHelper.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release USB camera helper", e);
            } finally {
                mCameraHelper = null;
            }
        }
    }
}