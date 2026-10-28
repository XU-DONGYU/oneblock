package com.oneblock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 左边那个小箭头：从下往上顶在目标帖子作者头像的下沿，表示「点一下打的是这个人」。
 */
public class ArrowView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ArrowView(Context c, int color) {
        super(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        paint.setStrokeWidth(dp(2.6f));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float pad = dp(3f);
        float headW = dp(7f);
        float headH = dp(10f);

        float tipY = pad;
        // 箭杆：从底往上，停在箭头下方一点
        canvas.drawLine(cx, h - pad, cx, tipY + headH * 0.7f, paint);
        // 两翼
        canvas.drawLine(cx - headW, tipY + headH, cx, tipY, paint);
        canvas.drawLine(cx + headW, tipY + headH, cx, tipY, paint);
    }
}
