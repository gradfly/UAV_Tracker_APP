package com.example.dronetracker;

import android.graphics.Rect;

public class TrackingController {
    private static final float FOV_HORIZONTAL = 45.0f;
    private static final float FOV_VERTICAL = 60.0f;

    private PIDController yawController;
    private PIDController throttleController;
    private PIDController pitchController;
    private float targetDistanceRatio;
    private boolean trackingEnabled;
    private int frameWidth;
    private int frameHeight;

    public TrackingController() {
        yawController = new PIDController(0.02f, 0.001f, 0.01f);
        throttleController = new PIDController(0.02f, 0.001f, 0.01f);
        pitchController = new PIDController(0.5f, 0.01f, 0.1f);
        targetDistanceRatio = 0.5f;
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
        this.targetDistanceRatio = Math.max(0, Math.min(1, ratio));
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
        float verticalOffset = (float) (targetCenterY - frameCenterY) / frameHeight;

        float horizontalAngle = horizontalOffset * FOV_HORIZONTAL;
        float verticalAngle = verticalOffset * FOV_VERTICAL;

        float yawOutput = yawController.compute(horizontalAngle, deltaTime);
        float throttleOutput = throttleController.compute(-verticalAngle, deltaTime);

        float currentSizeRatio = (float) (targetRect.width() * targetRect.height()) / (frameWidth * frameHeight);
        float targetSizeRatio = targetDistanceRatio * 0.3f;
        float sizeError = targetSizeRatio - currentSizeRatio;
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