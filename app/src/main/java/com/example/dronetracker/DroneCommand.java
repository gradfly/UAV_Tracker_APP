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
        this.yaw = clamp(yaw, -1, 1);
        this.throttle = clamp(throttle, -1, 1);
        this.pitch = clamp(pitch, -1, 1);
        this.roll = clamp(roll, -1, 1);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = clamp(yaw, -1, 1);
    }

    public float getThrottle() {
        return throttle;
    }

    public void setThrottle(float throttle) {
        this.throttle = clamp(throttle, -1, 1);
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = clamp(pitch, -1, 1);
    }

    public float getRoll() {
        return roll;
    }

    public void setRoll(float roll) {
        this.roll = clamp(roll, -1, 1);
    }

    public byte[] toBytes() {
        byte[] data = new byte[5];
        data[0] = (byte) 0xAA;
        data[1] = (byte) ((yaw + 1) * 127);
        data[2] = (byte) ((throttle + 1) * 127);
        data[3] = (byte) ((pitch + 1) * 127);
        data[4] = (byte) ((roll + 1) * 127);
        return data;
    }

    @Override
    public String toString() {
        return String.format("Yaw: %.2f, Throttle: %.2f, Pitch: %.2f, Roll: %.2f",
                yaw, throttle, pitch, roll);
    }
}