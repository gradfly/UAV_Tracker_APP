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
        this.yaw = clamp(applyDeadzone(yaw, 0.05f), -0.3f, 0.3f);
        this.throttle = clamp(applyDeadzone(throttle, 0.05f), -0.3f, 0.3f);
        this.pitch = clamp(applyDeadzone(pitch, 0.05f), -0.3f, 0.3f);
        this.roll = clamp(roll, -1, 1);
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
        data[1] = (byte) (yaw * 150);
        data[2] = (byte) (throttle  * 100);
        data[3] = (byte) (pitch * 100);
        //data[4] = (byte) (roll * 100);
        return data;
    }

    @Override
    public String toString() {
        return String.format("Yaw: %.2f, Throttle: %.2f, Pitch: %.2f, Roll: %.2f",
                yaw, throttle, pitch, roll);
    }
}