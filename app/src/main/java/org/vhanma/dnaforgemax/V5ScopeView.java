package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

/** High-definition dual-decay CRT view for the exact v5 XY stream. */
final class V5ScopeView extends View {
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeFast = new Paint();
    private final Paint fadeSlow = new Paint();
    private final Paint composite = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private Bitmap fastLayer;
    private Bitmap slowLayer;
    private Canvas fastCanvas;
    private Canvas slowCanvas;
    private volatile float[] trace;
    private volatile String diagnostics = "";
    private float persistence = 0.84f;
    private float intensity = 0.82f;
    private float bloom = 0.46f;
    private float beamWidth = 0.90f;

    V5ScopeView(Activity context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.BLACK);

        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f);
        grid.setColor(Color.argb(42, 78, 178, 118));

        beam.setStyle(Paint.Style.STROKE);
        beam.setStrokeCap(Paint.Cap.ROUND);
        beam.setStrokeJoin(Paint.Join.ROUND);
        beam.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        text.setColor(Color.argb(215, 186, 255, 210));
        text.setTextSize(11.5f * getResources().getDisplayMetrics().scaledDensity);
    }

    void setResult(V5Engine.Result result) {
        trace = result == null ? null : result.xy;
        if (result != null) {
            diagnostics = String.format(java.util.Locale.US,
                    "PATHS %d  SKEL %d  FILL %d  FLY %d  CONT %.0f%%",
                    result.contourPaths, result.skeletonPaths, result.fillCenters,
                    result.flybacks, result.continuity * 100f);
        }
        invalidate();
    }

    void setTrace(float[] xy, String diag) {
        trace = xy;
        diagnostics = diag == null ? "" : diag;
        invalidate();
    }

    void setPersistence(int v) { persistence = clamp(v / 100f, 0.10f, 0.995f); }
    void setIntensity(int v) { intensity = clamp(v / 100f, 0.10f, 1f); }
    void setBloom(int v) { bloom = clamp(v / 100f, 0f, 1f); }
    void setBeamWidth(int v) { beamWidth = 0.45f + 1.25f * clamp(v / 100f, 0f, 1f); }

    void clearPhosphor() {
        if (fastCanvas != null) fastCanvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC);
        if (slowCanvas != null) slowCanvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        fastLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        slowLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        fastCanvas = new Canvas(fastLayer);
        slowCanvas = new Canvas(slowLayer);
        fastCanvas.drawColor(Color.BLACK);
        slowCanvas.drawColor(Color.BLACK);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float cx = w * 0.5f, cy = h * 0.5f;
        float size = Math.min(w, h) * 0.458f;
        drawGrid(canvas, cx, cy, size);
        if (fastCanvas == null || slowCanvas == null) return;

        int fa = clamp(Math.round((1f - persistence) * 180f), 2, 120);
        int sa = clamp(Math.round((1f - Math.min(0.998f, persistence + 0.12f)) * 86f), 1, 40);
        fadeFast.setColor(Color.argb(fa, 0, 0, 0));
        fadeSlow.setColor(Color.argb(sa, 0, 0, 0));
        fastCanvas.drawRect(0, 0, w, h, fadeFast);
        slowCanvas.drawRect(0, 0, w, h, fadeSlow);

        float[] xy = trace;
        if (xy != null && xy.length >= 4) drawTrace(xy, cx, cy, size);

        canvas.drawBitmap(slowLayer, 0, 0, composite);
        canvas.drawBitmap(fastLayer, 0, 0, composite);
        if (!diagnostics.isEmpty()) canvas.drawText(diagnostics, 10f, h - 11f, text);
    }

    private void drawGrid(Canvas c, float cx, float cy, float size) {
        for (int i = -4; i <= 4; i++) {
            float x = cx + size * i / 4f;
            float y = cy + size * i / 4f;
            c.drawLine(x, cy - size, x, cy + size, grid);
            c.drawLine(cx - size, y, cx + size, y, grid);
        }
        c.drawCircle(cx, cy, size, grid);
        c.drawCircle(cx, cy, size * 0.5f, grid);
    }

    private void drawTrace(float[] xy, float cx, float cy, float size) {
        int n = xy.length / 2;
        int step = Math.max(1, n / 36000);
        float flyback2 = 0.075f * 0.075f;
        beam.setShadowLayer(2.2f + bloom * 7.5f, 0f, 0f, Color.rgb(50, 255, 130));
        glow.setShadowLayer(4f + bloom * 13f, 0f, 0f, Color.rgb(18, 225, 92));

        for (int i = step; i < n; i += step) {
            int a = (i - step) * 2, b = i * 2;
            float x0n = xy[a], y0n = xy[a + 1];
            float x1n = xy[b], y1n = xy[b + 1];
            float dx = x1n - x0n, dy = y1n - y0n;
            float d2 = dx * dx + dy * dy;
            float x0 = cx + x0n * size, y0 = cy + y0n * size;
            float x1 = cx + x1n * size, y1 = cy + y1n * size;

            if (d2 > flyback2) {
                // Suppress intended one-sample flybacks in the software view. A 2-channel
                // physical scope still traverses them because there is no Z blanking channel.
                beam.setColor(Color.argb(Math.max(2, Math.round(14f * intensity)), 110, 255, 165));
                beam.setStrokeWidth(0.55f);
                fastCanvas.drawPoint(x1, y1, beam);
                continue;
            }

            float speed = (float) Math.sqrt(Math.max(1e-9f, d2));
            float dwell = clamp(0.0052f / speed, 0.20f, 2.1f);
            int ba = clamp(Math.round((42f + 102f * dwell) * intensity), 7, 225);
            int ga = clamp(Math.round((8f + 38f * dwell) * intensity), 2, 86);
            float w = clamp(beamWidth + 0.22f * dwell, 0.55f, 2.4f);

            beam.setColor(Color.argb(ba, 128, 255, 178));
            glow.setColor(Color.argb(ga, 52, 245, 118));
            beam.setStrokeWidth(w);
            glow.setStrokeWidth(w + 0.9f + bloom * 1.7f);
            fastCanvas.drawLine(x0, y0, x1, y1, beam);
            slowCanvas.drawLine(x0, y0, x1, y1, glow);

            if (d2 < 0.000012f) {
                fastCanvas.drawCircle(x1, y1, 0.55f + dwell * 0.35f, beam);
            }
        }
    }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
