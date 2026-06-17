package com.example.dronetracker;

public class DroneCommand {
    private float yaw;
    private float Posz;
    private float pitch;
    private float roll;

    public static final byte[] TRACKING_STOP_COMMAND = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    public DroneCommand() {
        this.yaw = 0;
        this.Posz = 0;
        this.pitch = 0;
        this.roll = 0;
    }

    public DroneCommand(float yaw, float Posz, float pitch, float roll) {
        // 1. 处理 yaw
        float y = applyDeadzone(yaw, 6.0f);
        if (y != 0) {
            y = y - Math.signum(y) * 6.0f;
        }
        float halfFov = TrackingController.FOV_HORIZONTAL / 2.0f;
        this.yaw = clamp(y, -halfFov, halfFov);

        // 2. 处理 Posz
        float t = applyDeadzone(Posz, 1.2f);
        if (t != 0) {
            t = t - Math.signum(t) * 1.2f;
        }
        this.Posz = clamp(t, -3.0f, 5.0f);

        // 3. 只有当 yaw 和 Posz 都为 0 时，才更新 pitch
        if (this.yaw == 0 && this.Posz == 0) {
            float p = applyDeadzone(pitch, 0.4f);
            if (p != 0) {
                // 如果超过死区，减去偏移量实现平滑启动
                p = p - Math.signum(p) * 0.4f;
            }
            this.pitch = clamp(p, -0.4f, 0.4f);
        } else {
            // 如果 yaw 或 Posz 不为 0，则强制 pitch 为 0
            this.pitch = 0;
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

    public float getPosz() {
        return Posz;
    }
    public float getPitch() {
        return pitch;
    }

//    public byte[] toBytes() {
//        byte[] data = new byte[5];
//        data[0] = (byte) 0xAA;
//        data[1] = (byte) ((yaw + 1) * 127);
//        data[2] = (byte) ((Posz + 1) * 127);
//        data[3] = (byte) ((pitch + 1) * 127);
//        data[4] = (byte) ((roll + 1) * 127);
//        return data;
//    }
    public byte[] toBytes() {
        byte[] data = new byte[4];
        data[0] = (byte) 0xAA;
        data[1] = (byte) (yaw );
        data[2] = (byte) (Posz  * 10); // 映射 -5..5 到 -30..50，防止byte溢出
        data[3] = (byte) (pitch * 50);
        //data[4] = (byte) (roll * 100);
        return data;
    }

    @Override
    public String toString() {
        return String.format("Yaw: %.2f, Posz: %.2f, Pitch: %.2f, Roll: %.2f",
                yaw, Posz, pitch, roll);
    }
}