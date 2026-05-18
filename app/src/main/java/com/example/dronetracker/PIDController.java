package com.example.dronetracker;

public class PIDController {
    private float kp;
    private float ki;
    private float kd;
    private float integral;
    private float lastError;
    //private float maxOutput;
    //private float minOutput;

    //public PIDController(float kp, float ki, float kd) { this(kp, ki, kd, -1, 1); }

    //public PIDController(float kp, float ki, float kd, float minOutput, float maxOutput) {
    public PIDController(float kp, float ki, float kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
        //this.minOutput = minOutput;
        //this.maxOutput = maxOutput;
        this.integral = 0;
        this.lastError = 0;
    }

    public float compute(float error, float deltaTime) {
        integral += error * deltaTime;
        float derivative = (error - lastError) / deltaTime;
        float output = kp * error + ki * integral + kd * derivative;
        
//        output = Math.max(minOutput, Math.min(maxOutput, output));
//
//        if (output >= maxOutput || output <= minOutput) {
//            integral -= error * deltaTime;
//        }
        
        lastError = error;
        return output;
    }

    public void reset() {
        integral = 0;
        lastError = 0;
    }

    public void setTunings(float kp, float ki, float kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }
}