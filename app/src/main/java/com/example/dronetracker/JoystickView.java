package com.example.dronetracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {
    private float centerX;
    private float centerY;
    private float baseRadius;
    private float hatRadius;
    private float joystickX;
    private float joystickY;
    private float initialXPercent = 0;
    private float initialYPercent = 0;
    private boolean autoCenterX = true;
    private boolean autoCenterY = true;
    private OnJoystickChangeListener listener;

    public interface OnJoystickChangeListener {
        void onJoystickMove(float xPercent, float yPercent);
    }

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        joystickX = 0;
        joystickY = 0;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 2.2f;
        hatRadius = baseRadius / 2.5f;
        joystickX = centerX + initialXPercent * baseRadius;
        joystickY = centerY - initialYPercent * baseRadius;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint paint = new Paint();
        paint.setAntiAlias(true);

        // Draw base
        paint.setColor(Color.argb(100, 100, 100, 100));
        canvas.drawCircle(centerX, centerY, baseRadius, paint);

        // Draw hat
        paint.setColor(Color.argb(200, 200, 200, 200));
        canvas.drawCircle(joystickX, joystickY, hatRadius, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float dx = x - centerX;
                float dy = y - centerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                if (distance < baseRadius) {
                    joystickX = x;
                    joystickY = y;
                } else {
                    joystickX = centerX + (dx / distance) * baseRadius;
                    joystickY = centerY + (dy / distance) * baseRadius;
                }
                invalidate();
                if (listener != null) {
                    float xPercent = (joystickX - centerX) / baseRadius;
                    float yPercent = -(joystickY - centerY) / baseRadius; // Invert Y for standard coordinate
                    listener.onJoystickMove(xPercent, yPercent);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (autoCenterX) joystickX = centerX + initialXPercent * baseRadius;
                if (autoCenterY) joystickY = centerY - initialYPercent * baseRadius;
                invalidate();
                if (listener != null) {
                    float xPercent = (joystickX - centerX) / baseRadius;
                    float yPercent = -(joystickY - centerY) / baseRadius;
                    listener.onJoystickMove(xPercent, yPercent);
                }
                break;
        }
        return true;
    }

    public void setAutoCenter(boolean x, boolean y) {
        this.autoCenterX = x;
        this.autoCenterY = y;
    }

    public void setInitialPosition(float xPercent, float yPercent) {
        this.initialXPercent = xPercent;
        this.initialYPercent = yPercent;
    }

    public void setOnJoystickChangeListener(OnJoystickChangeListener listener) {
        this.listener = listener;
    }
}
