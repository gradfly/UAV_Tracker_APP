package com.example.dronetracker;

import android.graphics.Rect;

public class TrackingController {
    public static final float FOV_HORIZONTAL = 60.0f;//fpv摄像头水平视角
    public static final float FOV_VERTICAL = 45.0f;//fpv摄像头竖直视角

    private PIDController yawController;
    private PIDController throttleController;
    private PIDController pitchController;
    private float targetDistanceRatio;
    private boolean trackingEnabled;
    private int frameWidth;
    private int frameHeight;

    public TrackingController() {
        yawController = new PIDController(0.02f, 0.000f, 0.01f);//ki=0.001
        throttleController = new PIDController(0.02f, 0.000f, 0.01f);//ki=0.001
        pitchController = new PIDController(0.05f, 0.00f, 0.1f);//ki=0.01
        // 初始化默认距离，对应进度条中值50
        onProgressChanged(20);
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
            throttleController.reset();
            pitchController.reset();
        }
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    public void setTargetDistanceRatio(float ratio) {
        this.targetDistanceRatio = ratio * 10.0f;
    }

    /**
     * 根据UI进度条更新目标距离
     * @param progress SeekBar进度 (0-100)
     */
    public void onProgressChanged(int progress) {
        // 进度0-100 映射到 ratio 0.1-1.0
        float ratio = 0.1f + (progress / 100.0f * 0.9f);
        setTargetDistanceRatio(ratio);
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
        // verticalAngle 已经定义为“上正下负”，所以油门（throttle）输出应与 verticalAngle 同向
        // 如果目标在上方 (verticalAngle > 0)，无人机需要上升
        float throttleOutput = throttleController.compute(verticalAngle, deltaTime);

        float areaRatio = (float) (targetRect.width() * targetRect.height()) / (frameWidth * frameHeight);
        float estimatedDistance = 1.0f / (float) Math.sqrt(areaRatio + 0.01f);
        float sizeError = estimatedDistance - targetDistanceRatio;
        float pitchOutput = pitchController.compute(sizeError, deltaTime);

        DroneCommand command = new DroneCommand(yawOutput, throttleOutput, pitchOutput, 0);

        return new TrackingResult(horizontalAngle, verticalAngle, command);
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