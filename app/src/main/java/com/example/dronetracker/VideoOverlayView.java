package com.example.dronetracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

public class VideoOverlayView extends View {
    private Paint paint;
    private Rect targetRect;
    private float horizontalAngle;
    private float verticalAngle;
    private boolean trackingEnabled;

    public VideoOverlayView(Context context) {
        super(context);
        init();
    }

    public VideoOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(3);
        paint.setStyle(Paint.Style.STROKE);
        paint.setTextSize(36);
        paint.setAntiAlias(true);
        targetRect = null;
        horizontalAngle = 0;
        verticalAngle = 0;
        trackingEnabled = false;
    }

    public void setTargetRect(Rect rect) {
        this.targetRect = rect;
        invalidate();
    }

    public void setAngles(float horizontal, float vertical) {
        this.horizontalAngle = horizontal;
        this.verticalAngle = vertical;
        invalidate();
    }

    public void setTrackingEnabled(boolean enabled) {
        this.trackingEnabled = enabled;
        invalidate();
    }

    private String targetName;

    public void setTargetName(String name) {
        this.targetName = name;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(2);
        canvas.drawLine(width / 2f, 0, width / 2f, height, paint);
        canvas.drawLine(0, height / 2f, width, height / 2f, paint);

        if (targetRect != null) {
            paint.setColor(trackingEnabled ? Color.GREEN : Color.RED);
            paint.setStrokeWidth(4);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(targetRect, paint);

            int rectCenterX = targetRect.centerX();
            int rectCenterY = targetRect.centerY();
            paint.setColor(Color.YELLOW);
            canvas.drawCircle(rectCenterX, rectCenterY, 8, paint);
            
            // 绘制目标名称
            if (targetName != null) {
                paint.setColor(trackingEnabled ? Color.GREEN : Color.RED);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(32);
                canvas.drawText(targetName, targetRect.left, targetRect.top - 10, paint);
            }

            paint.setColor(Color.WHITE);
            paint.setTextSize(28);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}