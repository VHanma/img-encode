package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

/** Instrument-style software CRT renderer for the exact XY stream. */
final class V4ScopeView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fastBeam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slowBeam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeFast = new Paint();
    private final Paint fadeSlow = new Paint();

    private Bitmap fastLayer;
    private Bitmap slowLayer;
    private Canvas fastCanvas;
    private Canvas slowCanvas;
    private volatile float[] trace;
    private volatile String diagnostics = "";
    private float persistence = 0.78f;
    private float intensity = 0.72f;
    private float bloom = 0.58f;

    V4ScopeView(Activity context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.BLACK);

        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        grid.setColor(Color.argb(48, 80, 180, 120));

        fastBeam.setStyle(Paint.Style.STROKE);
        fastBeam.setStrokeCap(Paint.Cap.ROUND);
        fastBeam.setStrokeJoin(Paint.Join.ROUND);
        fastBeam.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        slowBeam.setStyle(Paint.Style.STROKE);
        slowBeam.setStrokeCap(Paint.Cap.ROUND);
        slowBeam.setStrokeJoin(Paint.Join.ROUND);
        slowBeam.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        label.setColor(Color.argb(205, 175, 255, 205));
        label.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);

        fadeFast.setStyle(Paint.Style.FILL);
        fadeSlow.setStyle(Paint.Style.FILL);
    }

    void setTrace(float[] xy) {
        trace = xy;
        invalidate();
    }

    void setDiagnostics(String text) {
        diagnostics = text == null ? "" : text;
        invalidate();
    }

    void setPersistence(int value) {
        persistence = clamp(value / 100f, 0.02f, 0.995f);
    }

    void setIntensity(int value) {
        intensity = clamp(value / 100f, 0.08f, 1f);
    }

    void setBloom(int value) {
        bloom = clamp(value / 100f, 0f, 1f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        fastLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        slowLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        fastCanvas = new Canvas(fastLayer);
        slowCanvas = new Canvas(slowLayer);
        fastCanvas.drawColor(Color.BLACK);
        slowCanvas.drawColor(Color.BLACK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float size = Math.min(w, h) * 0.455f;

        drawGrid(canvas, cx, cy, size);
        if (fastCanvas == null || slowCanvas == null || fastLayer == null || slowLayer == null) return;

        int fastAlpha = clamp(Math.round((1f - persistence) * 190f), 3, 150);
        int slowAlpha = clamp(Math.round((1f - Math.min(0.997f, persistence + 0.15f)) * 105f), 1, 55);
        fadeFast.setColor(Color.argb(fastAlpha, 0, 0, 0));
        fadeSlow.setColor(Color.argb(slowAlpha, 0, 0, 0));
        fastCanvas.drawRect(0, 0, w, h, fadeFast);
        slowCanvas.drawRect(0, 0, w, h, fadeSlow);

        float[] xy = trace;
        if (xy != null && xy.length >= 4) drawTrace(xy, cx, cy, size);

        Paint layerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(slowLayer, 0, 0, layerPaint);
        canvas.drawBitmap(fastLayer, 0, 0, layerPaint);

        if (!diagnostics.isEmpty()) {
            canvas.drawText(diagnostics, 10f, h - 12f, label);
        }
    }

    private void drawGrid(Canvas canvas, float cx, float cy, float size) {
        for (int i = -4; i <= 4; i++) {
            float x = cx + size * i / 4f;
            float y = cy + size * i / 4f;
            canvas.drawLine(x, cy - size, x, cy + size, grid);
            canvas.drawLine(cx - size, y, cx + size, y, grid);
        }
        canvas.drawCircle(cx, cy, size, grid);
    }

    private void drawTrace(float[] xy, float cx, float cy, float size) {
        int n = xy.length / 2;
        int step = Math.max(1, n / 28000);
        float longJump2 = 0.075f * 0.075f;

        fastBeam.setShadowLayer(3f + bloom * 8f, 0f, 0f, Color.rgb(35, 255, 120));
        slowBeam.setShadowLayer(2f + bloom * 12f, 0f, 0f, Color.rgb(20, 210, 95));

        for (int i = step; i < n; i += step) {
            int a = (i - step) * 2;
            int b = i * 2;
            float x0n = xy[a];
            float y0n = xy[a + 1];
            float x1n = xy[b];
            float y1n = xy[b + 1];
            float dx = x1n - x0n;
            float dy = y1n - y0n;
            float d2 = dx * dx + dy * dy;

            float x0 = cx + x0n * size;
            float y0 = cy + y0n * size;
            float x1 = cx + x1n * size;
            float y1 = cy + y1n * size;

            if (d2 > longJump2) {
                // A single-beam scope will traverse this physically, but the software preview
                // suppresses it to approximate a Z-blanked / ultra-fast flyback rather than
                // falsely advertising it as intended image content.
                int alpha = clamp(Math.round(32f * intensity), 5, 42);
                fastBeam.setColor(Color.argb(alpha, 100, 255, 160));
                fastBeam.setStrokeWidth(0.8f);
                fastCanvas.drawPoint(x1, y1, fastBeam);
                continue;
            }

            float d = (float) Math.sqrt(Math.max(1e-8f, d2));
            float dwell = clamp(0.0065f / d, 0.16f, 1.7f);
            int fastA = clamp(Math.round((36f + 96f * dwell) * intensity), 8, 210);
            int slowA = clamp(Math.round((11f + 42f * dwell) * intensity), 3, 92);
            float width = clamp(0.75f + dwell * 0.62f + bloom * 0.35f, 0.75f, 2.35f);

            fastBeam.setColor(Color.argb(fastA, 125, 255, 175));
            slowBeam.setColor(Color.argb(slowA, 65, 245, 125));
            fastBeam.setStrokeWidth(width);
            slowBeam.setStrokeWidth(width + 0.7f + bloom * 1.2f);

            fastCanvas.drawLine(x0, y0, x1, y1, fastBeam);
            slowCanvas.drawLine(x0, y0, x1, y1, slowBeam);

            if (d2 < 0.000025f) {
                float r = 0.65f + dwell * 0.5f;
                fastCanvas.drawCircle(x1, y1, r, fastBeam);
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
