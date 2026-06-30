package com.example.dronetracker;

public class DroneCommand {
    private float yaw;
    private float throttle;
    private float pitch;
    private float roll;
    private float estimatedDistance;

    public static final byte[] TRACKING_STOP_COMMAND = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    public DroneCommand() {
        this.yaw = 0;
        this.throttle = 0;
        this.pitch = 0;
        this.roll = 0;
        this.estimatedDistance = 0;
    }

    public DroneCommand(float yaw, float throttle, float pitch, float roll, float estimatedDistance) {
        // 1. 处理 yaw
        float y = applyDeadzone(yaw, 3.0f);
        if (y != 0) {
            y = y - Math.signum(y) * 3.0f;
        }
        float halfFov = TrackingController.FOV_HORIZONTAL / 2.0f;
        this.yaw = clamp(y, -halfFov, halfFov);

        // 2. 处理 Throttle
        float t = applyDeadzone(throttle, 2.0f);
        if (t != 0) {
            t = (t - Math.signum(t) * 2.0f);
        }
        this.throttle = clamp(t, -15.0f, 20.0f);

        // 3. 只有当 yaw 和 Throttle 都为 0 时，才更新 Pitch
        if (this.yaw == 0 && this.throttle == 0) {
            float p = applyDeadzone(pitch, 0.2f);
            if (p != 0) {
                // 如果超过死区，减去偏移量实现平滑启动
                p = p - Math.signum(p) * 0.2f;
            }
            this.pitch = clamp(p, -1.0f, 1.0f);
        } else {
            // 如果 yaw 或 Throttle 不为 0，则强制 Pitch 为 0
            this.pitch = 0;
        }
        
        this.roll = 0; // 暂不使用 roll
        this.estimatedDistance = estimatedDistance;
    }

    private float applyDeadzone(float value, float deadzone) {
        return Math.abs(value) < deadzone ? 0 : value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public float getYaw() {
        return yaw;
    }

    public float getThrottle() {
        return throttle;
    }
    public float getPitch() {
        return pitch;
    }

    public byte[] toBytes() {
        byte[] data = new byte[4];
        data[0] = (byte) 0xAA;
        data[1] = (byte) (yaw * 10f);
        data[2] = (byte) (throttle  * estimatedDistance); // 映射 -5..5 到 -30..50，防止byte溢出
        data[3] = (byte) (pitch * 20);
        //data[4] = (byte) (roll * 100);
        return data;
    }

    @Override
    public String toString() {
        return String.format("Yaw: %.2f, Throttle: %.2f, Pitch: %.2f, Roll: %.2f",
                yaw, throttle, pitch, roll);
    }
}