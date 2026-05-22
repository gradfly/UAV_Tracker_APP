package com.example.dronetracker;

public class DroneCommand {
    private float yaw;
    private float throttle;
    private float pitch;
    private float roll;

    public DroneCommand() {
        this.yaw = 0;
        this.throttle = 0;
        this.pitch = 0;
        this.roll = 0;
    }

    public DroneCommand(float yaw, float throttle, float pitch, float roll) {
        // 1. 计算平滑后的 pitch
        float p = applyDeadzone(pitch, 0.4f);
        if (p != 0) {
            // 如果超过死区，减去偏移量实现平滑启动（从0开始增加）
            p = p - Math.signum(p) * 0.4f;
        }
        this.pitch = clamp(p, -0.4f, 0.4f);

        // 2. 只有当 pitch 为 0 时，才更新 yaw 和 throttle
        if (this.pitch == 0) {
            // 处理 yaw
            float y = applyDeadzone(yaw, 0.25f);
            if (y != 0) {
                y = y - Math.signum(y) * 0.25f;
            }
            this.yaw = clamp(y, -0.6f, 0.6f);

            // 处理 throttle
            float t = applyDeadzone(throttle, 0.3f);
            if (t != 0) {
                t = t - Math.signum(t) * 0.2f;
            }
            this.throttle = clamp(t, -0.6f, 0.8f);
        } else {
            // 如果 pitch 不为 0，则强制 yaw 和 throttle 为 0
            this.yaw = 0;
            this.throttle = 0;
        }
        
        this.roll = 0; // 暂不使用 roll
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

//    public byte[] toBytes() {
//        byte[] data = new byte[5];
//        data[0] = (byte) 0xAA;
//        data[1] = (byte) ((yaw + 1) * 127);
//        data[2] = (byte) ((throttle + 1) * 127);
//        data[3] = (byte) ((pitch + 1) * 127);
//        data[4] = (byte) ((roll + 1) * 127);
//        return data;
//    }
    public byte[] toBytes() {
        byte[] data = new byte[4];
        data[0] = (byte) 0xAA;
        data[1] = (byte) (yaw * 80);
        data[2] = (byte) (throttle  * 100);
        data[3] = (byte) (pitch * 50);
        //data[4] = (byte) (roll * 100);
        return data;
    }

    @Override
    public String toString() {
        return String.format("Yaw: %.2f, Throttle: %.2f, Pitch: %.2f, Roll: %.2f",
                yaw, throttle, pitch, roll);
    }
}