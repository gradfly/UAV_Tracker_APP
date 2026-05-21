package com.example.dronetracker;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.example.dronetracker.SimpleUVCCameraTextureView;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class UVCCameraManager implements TextureView.SurfaceTextureListener {
    private static final String TAG = "UVCCameraManager";

    public static final int FRAME_FORMAT_YUYV = UVCCamera.FRAME_FORMAT_YUYV;
    public static final int FRAME_FORMAT_MJPEG = UVCCamera.FRAME_FORMAT_MJPEG;

    public interface OnPreviewFrameListener {
        void onPreviewFrame(byte[] data);
    }

    public interface OnConnectListener {
        void onAttachDev(UsbDevice device);
        void onDettachDev(UsbDevice device);
        void onConnectDev(UsbDevice device, boolean isConnected);
        void onDisConnectDev(UsbDevice device);
    }

    private Context mContext;
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private SimpleUVCCameraTextureView mTextureView;
    private Surface mPreviewSurface;

    private OnPreviewFrameListener mPreviewFrameListener;
    private OnConnectListener mConnectListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mIsConnected = new AtomicBoolean(false);
    private boolean mIsPreviewing = false;
    private int mFrameFormat = FRAME_FORMAT_YUYV;
    private int mPreviewWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH;
    private int mPreviewHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT;

    public UVCCameraManager(Context context, SimpleUVCCameraTextureView textureView) {
        mContext = context;
        mTextureView = textureView;
        if (mTextureView != null) {
            mTextureView.setSurfaceTextureListener(this);
        }
    }

    public void setOnConnectListener(OnConnectListener listener) {
        mConnectListener = listener;
    }

    public void setOnPreviewFrameListener(OnPreviewFrameListener listener) {
        mPreviewFrameListener = listener;
    }

    public void setDefaultFrameFormat(int format) {
        mFrameFormat = format;
    }

    public void register() {
        if (mUSBMonitor == null) {
            mUSBMonitor = new USBMonitor(mContext, mOnDeviceConnectListener);
        }
        mUSBMonitor.register();
    }

    public void unregister() {
        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();
        }
    }

    public void requestPermission(UsbDevice device) {
        if (mUSBMonitor != null) {
            mUSBMonitor.requestPermission(device);
        }
    }

    public void startPreview(TextureView textureView) {
        if (mTextureView != null && mTextureView.isAvailable()) {
            startPreviewInternal();
        }
    }

    public void closeCamera() {
        releaseCamera();
    }

    public boolean isConnected() {
        return mIsConnected.get();
    }

    public boolean isPreviewing() {
        return mIsPreviewing;
    }

    private void startPreviewInternal() {
        if (mUVCCamera == null || !mIsConnected.get()) {
            return;
        }

        try {
            SurfaceTexture st = mTextureView.getSurfaceTexture();
            if (st != null) {
                if (mPreviewSurface != null) {
                    mPreviewSurface.release();
                }
                mPreviewSurface = new Surface(st);

                mUVCCamera.setPreviewDisplay(mPreviewSurface);

                try {
                    mUVCCamera.setPreviewSize(mPreviewWidth, mPreviewHeight, mFrameFormat);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Failed to set preview size with format " + mFrameFormat + ", trying default");
                    try {
                        mUVCCamera.setPreviewSize(mPreviewWidth, mPreviewHeight, UVCCamera.DEFAULT_PREVIEW_MODE);
                    } catch (IllegalArgumentException e1) {
                        Log.e(TAG, "Failed to set preview size", e1);
                        return;
                    }
                }

                mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21);
                mUVCCamera.startPreview();
                mIsPreviewing = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start preview", e);
        }
    }

    private final IFrameCallback mIFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(ByteBuffer frame) {
            if (frame != null) {
                frame.clear();
                byte[] data = new byte[frame.remaining()];
                frame.get(data);
                mLastFrameData = data;
                if (mPreviewFrameListener != null) {
                    mPreviewFrameListener.onPreviewFrame(data);
                }
            }
        }
    };

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            mMainHandler.post(() -> {
                if (mConnectListener != null) {
                    mConnectListener.onAttachDev(device);
                }
            });
        }

        @Override
        public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            mMainHandler.post(() -> {
                releaseCamera();

                new Thread(() -> {
                    try {
                        mUVCCamera = new UVCCamera();
                        mUVCCamera.open(ctrlBlock);

                        mMainHandler.post(() -> {
                            mIsConnected.set(true);
                            if (mConnectListener != null) {
                                mConnectListener.onConnectDev(device, true);
                            }
                            startPreviewInternal();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to open camera", e);
                        mMainHandler.post(() -> {
                            if (mConnectListener != null) {
                                mConnectListener.onConnectDev(device, false);
                            }
                        });
                    }
                }).start();
            });
        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            mMainHandler.post(() -> {
                mIsConnected.set(false);
                mIsPreviewing = false;
                if (mConnectListener != null) {
                    mConnectListener.onDisConnectDev(device);
                }
                releaseCamera();
            });
        }

        @Override
        public void onDettach(UsbDevice device) {
            mMainHandler.post(() -> {
                mIsConnected.set(false);
                mIsPreviewing = false;
                if (mConnectListener != null) {
                    mConnectListener.onDettachDev(device);
                }
            });
        }

        @Override
        public void onCancel(UsbDevice device) {
            Log.d(TAG, "onCancel");
        }
    };

    private void releaseCamera() {
        if (mUVCCamera != null) {
            try {
                mUVCCamera.stopPreview();
                mUVCCamera.close();
                mUVCCamera.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release camera", e);
            }
            mUVCCamera = null;
        }
        if (mPreviewSurface != null) {
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
        mIsConnected.set(false);
        mIsPreviewing = false;
    }

    public void release() {
        releaseCamera();
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "Surface texture available: " + width + "x" + height);
        if (mUVCCamera != null && mIsConnected.get()) {
            startPreviewInternal();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "Surface texture size changed: " + width + "x" + height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "Surface texture destroyed");
        stopPreview();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void capturePicture(String path, CaptureCallback callback) {
        if (mUVCCamera == null || !mIsConnected.get()) {
            if (callback != null) {
                callback.onCaptureFailed(new Exception("Camera not connected"));
            }
            return;
        }

        new Thread(() -> {
            try {
                android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                        mLastFrameData,
                        android.graphics.ImageFormat.NV21,
                        mPreviewWidth,
                        mPreviewHeight,
                        null
                );
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, mPreviewWidth, mPreviewHeight), 90, out);
                byte[] imageBytes = out.toByteArray();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(path);
                fos.write(imageBytes);
                fos.close();

                if (callback != null) {
                    mMainHandler.post(() -> callback.onCaptureSuccess(path));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to capture picture", e);
                if (callback != null) {
                    mMainHandler.post(() -> callback.onCaptureFailed(e));
                }
            }
        }).start();
    }

    public interface CaptureCallback {
        void onCaptureSuccess(String path);
        void onCaptureFailed(Exception e);
    }

    private byte[] mLastFrameData;

    public void stopPreview() {
        if (mUVCCamera != null && mIsPreviewing) {
            try {
                mUVCCamera.stopPreview();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop preview", e);
            }
            mIsPreviewing = false;
        }
    }
}