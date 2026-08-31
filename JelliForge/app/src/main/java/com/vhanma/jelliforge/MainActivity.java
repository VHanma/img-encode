package com.vhanma.jelliforge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int PICK_IMAGES = 41;
    private static final int MAX_PHOTOS = 12;

    private JelliCanvasView canvasView;
    private TextView statusView;
    private Button playButton;
    private Button linkButton;
    private Button effectButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SensorManager sensorManager;
    private Sensor accelerometer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(14, 14, 18));
        getWindow().setNavigationBarColor(Color.rgb(14, 14, 18));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(14, 14, 18));

        TextView title = new TextView(this);
        title.setText("JelliForge");
        title.setTextSize(26f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(12), dp(16), dp(4));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("Load up to 12 photos. Drag any photo to make it move.");
        statusView.setTextSize(14f);
        statusView.setTextColor(Color.rgb(190, 190, 205));
        statusView.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        canvasView = new JelliCanvasView();
        root.addView(canvasView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(8), dp(8), dp(8), dp(12));
        scroller.addView(controls, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button add = button("+ Photos");
        add.setOnClickListener(v -> pickPhotos());
        controls.addView(add);

        playButton = button("Pause");
        playButton.setOnClickListener(v -> {
            canvasView.togglePlaying();
            playButton.setText(canvasView.isPlaying() ? "Pause" : "Play");
        });
        controls.addView(playButton);

        linkButton = button("Link All: OFF");
        linkButton.setOnClickListener(v -> {
            canvasView.setLinkAll(!canvasView.isLinkAll());
            linkButton.setText(canvasView.isLinkAll() ? "Link All: ON" : "Link All: OFF");
            status("Link All " + (canvasView.isLinkAll() ? "mirrors edits across every photo." : "disabled. Editing selected photo only."));
        });
        controls.addView(linkButton);

        effectButton = button("Effect: Wobble");
        effectButton.setOnClickListener(v -> {
            String mode = canvasView.cycleEffect();
            effectButton.setText("Effect: " + mode);
            status("Effect set to " + mode + (canvasView.isLinkAll() ? " for all photos." : " for selected photo."));
        });
        controls.addView(effectButton);

        Button auto = button("Smart Motion");
        auto.setOnClickListener(v -> {
            canvasView.smartMotion();
            status("Motion anchors seeded from high-contrast image regions.");
        });
        controls.addView(auto);

        Button reset = button("Reset");
        reset.setOnClickListener(v -> {
            canvasView.resetCurrent();
            status("Deformation reset.");
        });
        controls.addView(reset);

        Button still = button("Save Still");
        still.setOnClickListener(v -> saveStill());
        controls.addView(still);

        Button gif = button("Export Grid GIF");
        gif.setOnClickListener(v -> exportGridGif());
        controls.addView(gif);

        Button batch = button("Batch GIFs");
        batch.setOnClickListener(v -> exportBatchGifs());
        controls.addView(batch);

        root.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void status(String text) {
        statusView.setText(text);
    }

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_IMAGES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGES || resultCode != RESULT_OK || data == null) return;

        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount() && uris.size() < MAX_PHOTOS; i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        status("Loading " + uris.size() + " photo" + (uris.size() == 1 ? "" : "s") + "…");
        worker.execute(() -> {
            ArrayList<Bitmap> decoded = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) { }
                Bitmap b = decodeScaled(uri, 1800);
                if (b != null) decoded.add(b);
            }
            runOnUiThread(() -> {
                int room = Math.max(0, MAX_PHOTOS - canvasView.getPhotoCount());
                int added = Math.min(room, decoded.size());
                for (int i = 0; i < added; i++) canvasView.addPhoto(decoded.get(i));
                for (int i = added; i < decoded.size(); i++) decoded.get(i).recycle();
                status(canvasView.getPhotoCount() + " photo" + (canvasView.getPhotoCount() == 1 ? "" : "s") + " loaded. Drag inside any tile to animate it.");
            });
        });
    }

    private Bitmap decodeScaled(Uri uri, int maxSide) {
        ContentResolver cr = getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = cr.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void saveStill() {
        if (canvasView.getPhotoCount() == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Saving current grid…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                Bitmap frame = canvasView.renderComposite(canvasView.currentPhase(), 1400);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "JelliForge_" + System.currentTimeMillis() + ".png");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JelliForge");
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("Could not create output file");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
                }
                frame.recycle();
                Uri finalUri = uri;
                runOnUiThread(() -> {
                    status("Saved still image to Pictures/JelliForge.");
                    Toast.makeText(this, "Saved PNG", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                runOnUiThread(() -> status("Save failed: " + e.getMessage()));
            }
        });
    }

    private void exportGridGif() {
        if (canvasView.getPhotoCount() == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Encoding animated grid GIF…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "JelliForge_Grid_" + System.currentTimeMillis() + ".gif");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JelliForge");
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("Could not create GIF");
                try (OutputStream raw = getContentResolver().openOutputStream(uri);
                     BufferedOutputStream out = new BufferedOutputStream(raw)) {
                    int frames = 36;
                    GifEncoder enc = null;
                    for (int i = 0; i < frames; i++) {
                        float phase = (float) (Math.PI * 2.0 * i / frames);
                        Bitmap frame = canvasView.renderComposite(phase, 900);
                        if (enc == null) {
                            enc = new GifEncoder(out, frame.getWidth(), frame.getHeight(), 55);
                            enc.start();
                        }
                        enc.addFrame(frame);
                        frame.recycle();
                    }
                    if (enc != null) enc.finish();
                }
                runOnUiThread(() -> {
                    status("Grid GIF saved to Pictures/JelliForge.");
                    Toast.makeText(this, "Grid GIF saved", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                runOnUiThread(() -> status("GIF export failed: " + e.getMessage()));
            }
        });
    }

    private void exportBatchGifs() {
        int count = canvasView.getPhotoCount();
        if (count == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Batch exporting " + count + " GIFs…");
        worker.execute(() -> {
            int made = 0;
            for (int index = 0; index < count; index++) {
                Uri uri = null;
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, String.format(Locale.US, "JelliForge_%02d_%d.gif", index + 1, System.currentTimeMillis()));
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JelliForge");
                    uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IOException("Output create failed");
                    try (OutputStream raw = getContentResolver().openOutputStream(uri);
                         BufferedOutputStream out = new BufferedOutputStream(raw)) {
                        int frames = 30;
                        GifEncoder enc = null;
                        for (int f = 0; f < frames; f++) {
                            float phase = (float) (Math.PI * 2.0 * f / frames);
                            Bitmap frame = canvasView.renderSingle(index, phase, 720);
                            if (enc == null) {
                                enc = new GifEncoder(out, frame.getWidth(), frame.getHeight(), 60);
                                enc.start();
                            }
                            enc.addFrame(frame);
                            frame.recycle();
                        }
                        if (enc != null) enc.finish();
                    }
                    made++;
                } catch (Exception e) {
                    if (uri != null) getContentResolver().delete(uri, null, null);
                }
            }
            int finalMade = made;
            runOnUiThread(() -> {
                status("Batch complete: " + finalMade + " GIF" + (finalMade == 1 ? "" : "s") + " saved.");
                Toast.makeText(this, "Batch GIF export complete", Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            canvasView.setTilt(event.values[0], event.values[1]);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private final class JelliCanvasView extends View {
        private static final int MW = 20;
        private static final int MH = 20;
        private final ArrayList<PhotoItem> photos = new ArrayList<>();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selected = -1;
        private int activeTile = -1;
        private float downX, downY;
        private RectF activeRect;
        private final ArrayList<WarpPoint> activePoints = new ArrayList<>();
        private boolean playing = true;
        private boolean linkAll = false;
        private long startMs = SystemClock.uptimeMillis();
        private volatile float tiltX = 0f;
        private volatile float tiltY = 0f;

        JelliCanvasView() {
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(20, 20, 25));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(3));
            shade.setColor(Color.argb(95, 0, 0, 0));
        }

        int getPhotoCount() { synchronized (photos) { return photos.size(); } }
        boolean isPlaying() { return playing; }
        boolean isLinkAll() { return linkAll; }
        void setLinkAll(boolean value) { linkAll = value; invalidate(); }

        void addPhoto(Bitmap b) {
            synchronized (photos) {
                if (photos.size() >= MAX_PHOTOS) return;
                photos.add(new PhotoItem(b));
                selected = photos.size() - 1;
            }
            invalidate();
        }

        void togglePlaying() {
            playing = !playing;
            if (playing) {
                startMs = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
            } else invalidate();
        }

        float currentPhase() {
            return playing ? (SystemClock.uptimeMillis() - startMs) / 420f : 0f;
        }

        void setTilt(float x, float y) {
            tiltX = clamp(-x / 9.81f, -1f, 1f);
            tiltY = clamp(y / 9.81f, -1f, 1f);
            if (playing) postInvalidateOnAnimation();
        }

        String cycleEffect() {
            synchronized (photos) {
                if (photos.isEmpty()) return "Wobble";
                if (selected < 0) selected = 0;
                int next = (photos.get(selected).mode + 1) % 5;
                if (linkAll) {
                    for (PhotoItem p : photos) p.mode = next;
                } else {
                    photos.get(selected).mode = next;
                }
                invalidate();
                return modeName(next);
            }
        }

        void resetCurrent() {
            synchronized (photos) {
                if (photos.isEmpty()) return;
                if (linkAll) {
                    for (PhotoItem p : photos) p.points.clear();
                } else if (selected >= 0 && selected < photos.size()) {
                    photos.get(selected).points.clear();
                }
            }
            invalidate();
        }

        void smartMotion() {
            synchronized (photos) {
                if (photos.isEmpty()) return;
                if (linkAll) {
                    for (PhotoItem p : photos) seedSmartPoints(p);
                } else if (selected >= 0 && selected < photos.size()) {
                    seedSmartPoints(photos.get(selected));
                } else {
                    for (PhotoItem p : photos) seedSmartPoints(p);
                }
            }
            invalidate();
        }

        private void seedSmartPoints(PhotoItem item) {
            item.points.clear();
            float[] bestScore = {-1f, -1f, -1f};
            float[] bestX = {0.5f, 0.35f, 0.65f};
            float[] bestY = {0.5f, 0.45f, 0.55f};
            float[] bestGx = {0f, 0f, 0f};
            float[] bestGy = {0f, 0f, 0f};
            Bitmap b = item.bitmap;
            for (int gy = 1; gy <= 6; gy++) {
                for (int gx = 1; gx <= 6; gx++) {
                    float nx = gx / 7f;
                    float ny = gy / 7f;
                    int px = Math.min(b.getWidth() - 2, Math.max(1, Math.round(nx * (b.getWidth() - 1))));
                    int py = Math.min(b.getHeight() - 2, Math.max(1, Math.round(ny * (b.getHeight() - 1))));
                    float left = luma(b.getPixel(px - 1, py));
                    float right = luma(b.getPixel(px + 1, py));
                    float up = luma(b.getPixel(px, py - 1));
                    float down = luma(b.getPixel(px, py + 1));
                    float dx = right - left;
                    float dy = down - up;
                    float score = Math.abs(dx) + Math.abs(dy);
                    int slot = 0;
                    for (int k = 1; k < 3; k++) if (bestScore[k] < bestScore[slot]) slot = k;
                    if (score > bestScore[slot]) {
                        bestScore[slot] = score;
                        bestX[slot] = nx;
                        bestY[slot] = ny;
                        bestGx[slot] = dx;
                        bestGy[slot] = dy;
                    }
                }
            }
            for (int i = 0; i < 3; i++) {
                float mag = Math.max(1f, Math.abs(bestGx[i]) + Math.abs(bestGy[i]));
                float dx = -bestGy[i] / mag * (0.08f + 0.02f * i);
                float dy = bestGx[i] / mag * (0.08f + 0.02f * i);
                item.points.add(new WarpPoint(bestX[i], bestY[i], dx, dy, 0.28f + 0.03f * i));
            }
        }

        private float luma(int c) {
            return Color.red(c) * 0.2126f + Color.green(c) * 0.7152f + Color.blue(c) * 0.0722f;
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            drawScene(c, getWidth(), getHeight(), currentPhase(), true);
            if (playing) postInvalidateOnAnimation();
        }

        private void drawScene(Canvas c, int w, int h, float phase, boolean showSelection) {
            c.drawColor(Color.rgb(20, 20, 25));
            synchronized (photos) {
                int n = photos.size();
                if (n == 0) {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Color.rgb(175, 175, 190));
                    p.setTextSize(Math.max(28f, w * 0.04f));
                    p.setTextAlign(Paint.Align.CENTER);
                    c.drawText("Add several photos, then bend the grid.", w / 2f, h / 2f, p);
                    return;
                }
                for (int i = 0; i < n; i++) {
                    RectF rect = photoRect(i, n, w, h, photos.get(i).bitmap);
                    drawItem(c, photos.get(i), rect, phase, showSelection && i == selected);
                }
            }
        }

        private void drawItem(Canvas c, PhotoItem item, RectF rect, float phase, boolean selectedTile) {
            float[] verts = buildVerts(item, rect, phase);
            c.drawBitmapMesh(item.bitmap, MW, MH, verts, 0, null, 0, null);
            if (selectedTile) {
                border.setColor(Color.rgb(140, 225, 255));
                c.drawRoundRect(rect, dp(6), dp(6), border);
            }
        }

        private float[] buildVerts(PhotoItem item, RectF rect, float phase) {
            float[] verts = new float[(MW + 1) * (MH + 1) * 2];
            int k = 0;
            float globalPhase = phase + tiltX * 0.8f - tiltY * 0.5f;
            for (int y = 0; y <= MH; y++) {
                float ny = y / (float) MH;
                for (int x = 0; x <= MW; x++) {
                    float nx = x / (float) MW;
                    float px = rect.left + nx * rect.width();
                    float py = rect.top + ny * rect.height();
                    float ox = tiltX * rect.width() * 0.006f * (float) Math.sin(globalPhase + ny * 3f);
                    float oy = tiltY * rect.height() * 0.006f * (float) Math.cos(globalPhase + nx * 3f);

                    for (WarpPoint wp : item.points) {
                        float dxn = nx - wp.x;
                        float dyn = ny - wp.y;
                        float dist2 = dxn * dxn + dyn * dyn;
                        float r2 = Math.max(0.01f, wp.radius * wp.radius);
                        float fall = (float) Math.exp(-dist2 / (r2 * 0.55f));
                        float s = (float) Math.sin(globalPhase + Math.sqrt(dist2) * 9.5f);
                        float cx = wp.dx * rect.width();
                        float cy = wp.dy * rect.height();
                        float strength = (float) Math.sqrt(wp.dx * wp.dx + wp.dy * wp.dy) + 0.045f;

                        if (item.mode == 0) { // Wobble
                            ox += cx * fall * s;
                            oy += cy * fall * (float) Math.sin(globalPhase * 1.13f + Math.sqrt(dist2) * 7f);
                        } else if (item.mode == 1) { // Pulse
                            float d = (float) Math.sqrt(dist2) + 0.001f;
                            ox += (dxn / d) * rect.width() * strength * fall * s * 0.55f;
                            oy += (dyn / d) * rect.height() * strength * fall * s * 0.55f;
                        } else if (item.mode == 2) { // Twist
                            float angle = strength * 4.5f * fall * s;
                            float vx = dxn * rect.width();
                            float vy = dyn * rect.height();
                            float rx = (float) (vx * Math.cos(angle) - vy * Math.sin(angle));
                            float ry = (float) (vx * Math.sin(angle) + vy * Math.cos(angle));
                            ox += (rx - vx) * 0.55f;
                            oy += (ry - vy) * 0.55f;
                        } else if (item.mode == 3) { // Wave
                            ox += cx * fall * (float) Math.sin(globalPhase + ny * 13f) * 0.45f;
                            oy += (Math.abs(cy) + rect.height() * strength * 0.35f) * fall * (float) Math.sin(globalPhase * 1.2f + nx * 14f);
                        } else { // Elastic
                            float d = (float) Math.sqrt(dist2) + 0.001f;
                            ox += cx * fall * s * 0.7f + (dxn / d) * rect.width() * strength * fall * s * 0.25f;
                            oy += cy * fall * s * 0.7f + (dyn / d) * rect.height() * strength * fall * s * 0.25f;
                        }
                    }
                    verts[k++] = px + ox;
                    verts[k++] = py + oy;
                }
            }
            return verts;
        }

        RectF photoRect(int index, int n, int w, int h, Bitmap bitmap) {
            int cols = n <= 1 ? 1 : (n <= 4 ? 2 : (n <= 9 ? 3 : 4));
            int rows = (int) Math.ceil(n / (double) cols);
            float gap = Math.max(4f, Math.min(w, h) * 0.008f);
            float cellW = w / (float) cols;
            float cellH = h / (float) rows;
            int col = index % cols;
            int row = index / cols;
            RectF cell = new RectF(col * cellW + gap, row * cellH + gap,
                    (col + 1) * cellW - gap, (row + 1) * cellH - gap);
            float imageAspect = bitmap.getWidth() / (float) bitmap.getHeight();
            float cellAspect = cell.width() / cell.height();
            if (imageAspect > cellAspect) {
                float hh = cell.width() / imageAspect;
                float cy = cell.centerY();
                return new RectF(cell.left, cy - hh / 2f, cell.right, cy + hh / 2f);
            } else {
                float ww = cell.height() * imageAspect;
                float cx = cell.centerX();
                return new RectF(cx - ww / 2f, cell.top, cx + ww / 2f, cell.bottom);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (photos.isEmpty()) return true;
            float x = e.getX();
            float y = e.getY();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                activeTile = findTile(x, y);
                if (activeTile < 0) return true;
                selected = activeTile;
                downX = x;
                downY = y;
                synchronized (photos) {
                    activeRect = photoRect(activeTile, photos.size(), getWidth(), getHeight(), photos.get(activeTile).bitmap);
                    float nx = clamp((x - activeRect.left) / activeRect.width(), 0f, 1f);
                    float ny = clamp((y - activeRect.top) / activeRect.height(), 0f, 1f);
                    activePoints.clear();
                    if (linkAll) {
                        for (PhotoItem p : photos) {
                            WarpPoint wp = new WarpPoint(nx, ny, 0f, 0f, 0.30f);
                            p.points.add(wp);
                            activePoints.add(wp);
                        }
                    } else {
                        WarpPoint wp = new WarpPoint(nx, ny, 0f, 0f, 0.30f);
                        photos.get(activeTile).points.add(wp);
                        activePoints.add(wp);
                    }
                }
                invalidate();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && activeTile >= 0 && activeRect != null) {
                float dx = clamp((x - downX) / Math.max(1f, activeRect.width()), -0.65f, 0.65f);
                float dy = clamp((y - downY) / Math.max(1f, activeRect.height()), -0.65f, 0.65f);
                synchronized (photos) {
                    for (WarpPoint wp : activePoints) {
                        wp.dx = dx;
                        wp.dy = dy;
                        wp.radius = clamp(0.24f + (float) Math.sqrt(dx * dx + dy * dy) * 0.45f, 0.22f, 0.52f);
                    }
                }
                invalidate();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                activeTile = -1;
                activeRect = null;
                activePoints.clear();
                invalidate();
                return true;
            }
            return true;
        }

        private int findTile(float x, float y) {
            synchronized (photos) {
                for (int i = 0; i < photos.size(); i++) {
                    RectF r = photoRect(i, photos.size(), getWidth(), getHeight(), photos.get(i).bitmap);
                    if (r.contains(x, y)) return i;
                }
            }
            return -1;
        }

        Bitmap renderComposite(float phase, int maxWidth) {
            int vw = Math.max(1, getWidth());
            int vh = Math.max(1, getHeight());
            if (vw <= 1 || vh <= 1) { vw = 1080; vh = 1080; }
            int outW = Math.min(maxWidth, vw);
            int outH = Math.max(1, Math.round(outW * (vh / (float) vw)));
            if (outH > 1400) {
                float s = 1400f / outH;
                outH = 1400;
                outW = Math.max(1, Math.round(outW * s));
            }
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            drawScene(c, outW, outH, phase, false);
            return out;
        }

        Bitmap renderSingle(int index, float phase, int maxSide) {
            PhotoItem item;
            synchronized (photos) {
                if (index < 0 || index >= photos.size()) throw new IllegalArgumentException("Bad photo index");
                item = photos.get(index);
            }
            float aspect = item.bitmap.getWidth() / (float) item.bitmap.getHeight();
            int w, h;
            if (aspect >= 1f) {
                w = maxSide;
                h = Math.max(1, Math.round(maxSide / aspect));
            } else {
                h = maxSide;
                w = Math.max(1, Math.round(maxSide * aspect));
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.rgb(20, 20, 25));
            drawItem(c, item, new RectF(0, 0, w, h), phase, false);
            return out;
        }

        private String modeName(int mode) {
            switch (mode) {
                case 1: return "Pulse";
                case 2: return "Twist";
                case 3: return "Wave";
                case 4: return "Elastic";
                default: return "Wobble";
            }
        }

        private float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    private static final class PhotoItem {
        final Bitmap bitmap;
        final ArrayList<WarpPoint> points = new ArrayList<>();
        int mode = 0;
        PhotoItem(Bitmap bitmap) { this.bitmap = bitmap; }
    }

    private static final class WarpPoint {
        final float x, y;
        float dx, dy, radius;
        WarpPoint(float x, float y, float dx, float dy, float radius) {
            this.x = x; this.y = y; this.dx = dx; this.dy = dy; this.radius = radius;
        }
    }

    private static final class GifEncoder {
        private final OutputStream out;
        private final int width;
        private final int height;
        private final int delayCs;
        private boolean started = false;

        GifEncoder(OutputStream out, int width, int height, int delayMs) {
            this.out = out;
            this.width = width;
            this.height = height;
            this.delayCs = Math.max(2, Math.round(delayMs / 10f));
        }

        void start() throws IOException {
            if (started) return;
            out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
            writeShort(width);
            writeShort(height);
            out.write(0xF7); // global 256-color table, 8-bit color resolution
            out.write(0);
            out.write(0);
            writePalette332();
            writeLoopExtension();
            started = true;
        }

        void addFrame(Bitmap bitmap) throws IOException {
            if (!started) start();
            Bitmap frame = bitmap;
            Bitmap scaled = null;
            if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
                scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
                frame = scaled;
            }
            int[] pixels = new int[width * height];
            frame.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] indexed = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                int a = Color.alpha(c);
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);
                if (a < 255) {
                    r = (r * a + 20 * (255 - a)) / 255;
                    g = (g * a + 20 * (255 - a)) / 255;
                    b = (b * a + 25 * (255 - a)) / 255;
                }
                indexed[i] = (byte) (((r >> 5) << 5) | ((g >> 5) << 2) | (b >> 6));
            }
            writeGraphicControl();
            writeImageDescriptor();
            writeLzw(indexed);
            if (scaled != null) scaled.recycle();
        }

        void finish() throws IOException {
            if (!started) return;
            out.write(0x3B);
            out.flush();
            started = false;
        }

        private void writePalette332() throws IOException {
            for (int i = 0; i < 256; i++) {
                int r = ((i >> 5) & 7) * 255 / 7;
                int g = ((i >> 2) & 7) * 255 / 7;
                int b = (i & 3) * 255 / 3;
                out.write(r);
                out.write(g);
                out.write(b);
            }
        }

        private void writeLoopExtension() throws IOException {
            out.write(0x21);
            out.write(0xFF);
            out.write(11);
            out.write("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
            out.write(3);
            out.write(1);
            writeShort(0);
            out.write(0);
        }

        private void writeGraphicControl() throws IOException {
            out.write(0x21);
            out.write(0xF9);
            out.write(4);
            out.write(0);
            writeShort(delayCs);
            out.write(0);
            out.write(0);
        }

        private void writeImageDescriptor() throws IOException {
            out.write(0x2C);
            writeShort(0);
            writeShort(0);
            writeShort(width);
            writeShort(height);
            out.write(0);
        }

        private void writeShort(int value) throws IOException {
            out.write(value & 0xFF);
            out.write((value >> 8) & 0xFF);
        }

        private void writeLzw(byte[] data) throws IOException {
            out.write(8);
            ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.max(1024, data.length / 2));
            BitPacker bits = new BitPacker(compressed);
            final int clear = 256;
            final int end = 257;
            int codeSize = 9;
            int nextCode = 258;
            HashMap<Integer, Integer> dict = new HashMap<>(4096);

            bits.write(clear, codeSize);
            if (data.length == 0) {
                bits.write(end, codeSize);
            } else {
                int prefix = data[0] & 0xFF;
                for (int i = 1; i < data.length; i++) {
                    int value = data[i] & 0xFF;
                    int key = (prefix << 8) | value;
                    Integer found = dict.get(key);
                    if (found != null) {
                        prefix = found;
                    } else {
                        bits.write(prefix, codeSize);
                        if (nextCode < 4096) {
                            dict.put(key, nextCode++);
                            if (nextCode == (1 << codeSize) && codeSize < 12) codeSize++;
                        } else {
                            bits.write(clear, codeSize);
                            dict.clear();
                            codeSize = 9;
                            nextCode = 258;
                        }
                        prefix = value;
                    }
                }
                bits.write(prefix, codeSize);
                bits.write(end, codeSize);
            }
            bits.flush();
            byte[] bytes = compressed.toByteArray();
            int offset = 0;
            while (offset < bytes.length) {
                int len = Math.min(255, bytes.length - offset);
                out.write(len);
                out.write(bytes, offset, len);
                offset += len;
            }
            out.write(0);
        }

        private static final class BitPacker {
            private final ByteArrayOutputStream out;
            private int buffer = 0;
            private int bitCount = 0;
            BitPacker(ByteArrayOutputStream out) { this.out = out; }
            void write(int code, int bits) {
                buffer |= (code << bitCount);
                bitCount += bits;
                while (bitCount >= 8) {
                    out.write(buffer & 0xFF);
                    buffer >>>= 8;
                    bitCount -= 8;
                }
            }
            void flush() {
                if (bitCount > 0) {
                    out.write(buffer & 0xFF);
                    buffer = 0;
                    bitCount = 0;
                }
            }
        }
    }
}