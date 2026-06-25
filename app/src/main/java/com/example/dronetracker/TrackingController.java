package com.example.dronetracker;

import android.graphics.Rect;

public class TrackingController {
//    public static final float FOV_HORIZONTAL = 140.0f;//fpv摄像头水平视角
//    public static final float FOV_VERTICAL = 80.0f;//fpv摄像头竖直视角
    public static final float FOV_HORIZONTAL = 50.0f;//手机摄像头水平视角
    public static final float FOV_VERTICAL = 100.0f;//手机摄像头竖直视角

    private PIDController yawController;
    private PIDController PoszController;
    private PIDController PosxController;
    private float targetDistanceRatio;
    private boolean trackingEnabled;
    private int frameWidth;
    private int frameHeight;

    private float lastYaw = 0;
    private float lastPosz = 0;
    private float lastPosx = 0;
    private static final float ALPHA = 0.4f; // 滤波系数，0.1-1.0，越小越平滑

    public TrackingController() {
        yawController = new PIDController(0.8f, 0.000f, 0.01f);//ki=0.001
        PoszController = new PIDController(0.15f, 0.000f, 0.05f);//ki=0.001, 映射50度到5.0输出
        PosxController = new PIDController(0.3f, 0.00f, 0.1f);//ki=0.01
        // 初始化默认距离，对应进度条中值50
        onProgressChanged(60);
        trackingEnabled = false;
        frameWidth = 1920;
        frameHeight = 1080;
    }

    public void setFrameSize(int width, int height) {
        this.frameWidth = width;
        this.frameHeight = height;
    }

    public void setTrackingEnabled(boolean enabled) {
        this.trackingEnabled = enabled;
        if (!enabled) {
            yawController.reset();
            PoszController.reset();
            PosxController.reset();
            lastYaw = 0;
            lastPosz = 0;
            lastPosx = 0;
        }
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    public void setTargetDistanceRatio(float value) {
        this.targetDistanceRatio = value;
    }

    /**
     * 根据UI进度条更新目标距离
     * @param progress SeekBar进度 (0-100)
     */
    public void onProgressChanged(int progress) {
        // 进度0-100 映射到 10.0-1.0，与UI显示逻辑完全一致
        float value = 10.0f - (progress / 100.0f * 9.0f);
        setTargetDistanceRatio(value);
    }

    public float getTargetDistanceRatio() {
        return targetDistanceRatio;
    }

    public TrackingResult processTarget(Rect targetRect, float deltaTime) {
        if (!trackingEnabled || targetRect == null) {
            return new TrackingResult(0, 0, new DroneCommand());
        }

        int targetCenterX = targetRect.centerX();
        int targetCenterY = targetRect.centerY();

        int frameCenterX = frameWidth / 2;
        int frameCenterY = frameHeight / 2;

        float horizontalOffset = (float) (targetCenterX - frameCenterX) / frameWidth;
        // 屏幕坐标系 Y 向下增加，目标在水平线以上时 targetCenterY < frameCenterY
        // 为了使“以上为正”，需要取负值：-(targetCenterY - frameCenterY)
        float verticalOffset = (float) (frameCenterY - targetCenterY) / frameHeight;

        float horizontalAngle = horizontalOffset * FOV_HORIZONTAL;
        float verticalAngle = verticalOffset * FOV_VERTICAL;

        float yawOutput = yawController.compute(horizontalAngle, deltaTime);
        // verticalAngle 已经定义为“上正下负”，所以油门（Posz）输出应与 verticalAngle 同向
        // 如果目标在上方 (verticalAngle > 0)，无人机需要上升
        float PoszOutput = PoszController.compute(verticalAngle, deltaTime);

        float areaRatio = (float) (targetRect.width() * targetRect.height()) / (frameWidth * frameHeight);
        float estimatedDistance = 1.0f / (float) Math.sqrt(areaRatio + 0.01f);
        float sizeError = estimatedDistance - targetDistanceRatio;
        float PosxOutput = PosxController.compute(sizeError, deltaTime);

        // 一阶低通滤波，平滑输出指令
        lastYaw = lastYaw + clamp(ALPHA * (yawOutput - lastYaw), -5f, 5f);
        lastPosz = lastPosz + clamp(ALPHA * (PoszOutput - lastPosz), -5f, 5f);
        lastPosx = lastPosx + clamp(ALPHA * (PosxOutput - lastPosx), -3f, 3f);

        DroneCommand command = new DroneCommand(lastYaw, lastPosz, lastPosx, 0, estimatedDistance);

        return new TrackingResult(horizontalAngle, verticalAngle, command);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class TrackingResult {
        public final float horizontalAngle;
        public final float verticalAngle;
        public final DroneCommand command;

        public TrackingResult(float horizontalAngle, float verticalAngle, DroneCommand command) {
            this.horizontalAngle = horizontalAngle;
            this.verticalAngle = verticalAngle;
            this.command = command;
        }
    }
}