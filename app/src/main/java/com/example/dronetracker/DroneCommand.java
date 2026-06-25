package com.example.dronetracker;

public class DroneCommand {
    private float yaw;
    private float Posz;
    private float Posx;
    private float roll;
    private float estimatedDistance;

    public static final byte[] TRACKING_STOP_COMMAND = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    public DroneCommand() {
        this.yaw = 0;
        this.Posz = 0;
        this.Posx = 0;
        this.roll = 0;
        this.estimatedDistance = 0;
    }

    public DroneCommand(float yaw, float Posz, float Posx, float roll, float estimatedDistance) {
        // 1. 处理 yaw
        float y = applyDeadzone(yaw, 3.0f);
        if (y != 0) {
            y = y - Math.signum(y) * 3.0f;
        }
        float halfFov = TrackingController.FOV_HORIZONTAL / 2.0f;
        this.yaw = clamp(y, -halfFov, halfFov);

        // 2. 处理 Posz
        float t = applyDeadzone(Posz, 2.0f);
        if (t != 0) {
            t = (t - Math.signum(t) * 2.0f) * 5.0f / (5.0f-2.0f);
        }
        this.Posz = clamp(t, -3.0f, 5.0f);

        // 3. 只有当 yaw 和 Posz 都为 0 时，才更新 Posx
        if (this.yaw == 0 && this.Posz == 0) {
            float p = applyDeadzone(Posx, 0.2f);
            if (p != 0) {
                // 如果超过死区，减去偏移量实现平滑启动
                p = p - Math.signum(p) * 0.2f;
            }
            this.Posx = clamp(p, -1.0f, 1.0f);
        } else {
            // 如果 yaw 或 Posz 不为 0，则强制 Posx 为 0
            this.Posx = 0;
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

    public float getPosz() {
        return Posz;
    }
    public float getPosx() {
        return Posx;
    }

//    public byte[] toBytes() {
//        byte[] data = new byte[5];
//        data[0] = (byte) 0xAA;
//        data[1] = (byte) ((yaw + 1) * 127);
//        data[2] = (byte) ((Posz + 1) * 127);
//        data[3] = (byte) ((Posx + 1) * 127);
//        data[4] = (byte) ((roll + 1) * 127);
//        return data;
//    }
    public byte[] toBytes() {
        byte[] data = new byte[4];
        data[0] = (byte) 0xAA;
        data[1] = (byte) (yaw * 1.5f);
        data[2] = (byte) (Posz  * estimatedDistance); // 映射 -5..5 到 -30..50，防止byte溢出
        data[3] = (byte) (Posx * 100);
        //data[4] = (byte) (roll * 100);
        return data;
    }

    @Override
    public String toString() {
        return String.format("Yaw: %.2f, Posz: %.2f, Posx: %.2f, Roll: %.2f",
                yaw, Posz, Posx, roll);
    }
}