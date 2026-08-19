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
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivityV11 extends Activity implements SensorEventListener {
    private static final int PICK_IMAGES = 41;
    private static final int MAX_PHOTOS = 12;

    private EditorView editor;
    private TextView statusView;
    private Button playButton;
    private Button linkButton;
    private Button toolButton;
    private Button effectButton;
    private Button heatButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SensorManager sensorManager;
    private Sensor accelerometer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 12, 16));
        getWindow().setNavigationBarColor(Color.rgb(12, 12, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 12, 16));

        TextView title = new TextView(this);
        title.setText("JelliForge v1.1");
        title.setTextSize(25f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(14), dp(10), dp(14), dp(2));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("Paint motion directly. Red = strongest, green = weakest.");
        statusView.setTextSize(13f);
        statusView.setTextColor(Color.rgb(195, 195, 210));
        statusView.setPadding(dp(14), 0, dp(14), dp(6));
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new EditorView();
        root.addView(editor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        primary.setPadding(dp(6), dp(4), dp(6), dp(2));

        Button add = button("+ Photos");
        add.setOnClickListener(v -> pickPhotos());
        primary.addView(add);

        Button open = button("Open Saved");
        open.setOnClickListener(v -> loadProject());
        primary.addView(open);

        Button save = button("SAVE PROJECT");
        save.setOnClickListener(v -> saveProject(true));
        primary.addView(save);

        Button still = button("Export PNG");
        still.setOnClickListener(v -> saveStill());
        primary.addView(still);

        Button gif = button("Grid GIF");
        gif.setOnClickListener(v -> exportGridGif());
        primary.addView(gif);

        Button batch = button("Batch GIFs");
        batch.setOnClickListener(v -> exportBatchGifs());
        primary.addView(batch);

        playButton = button("Pause");
        playButton.setOnClickListener(v -> {
            editor.togglePlaying();
            playButton.setText(editor.isPlaying() ? "Pause" : "Play");
        });
        primary.addView(playButton);
        root.addView(scroller(primary));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(dp(6), dp(2), dp(6), dp(2));

        toolButton = button("Tool: Paint");
        toolButton.setOnClickListener(v -> {
            String name = editor.cycleTool();
            toolButton.setText("Tool: " + name);
            status("Tool: " + name + ".");
        });
        tools.addView(toolButton);

        effectButton = button("Effect: Wobble");
        effectButton.setOnClickListener(v -> {
            String name = editor.cycleEffect();
            effectButton.setText("Effect: " + name);
            status("New brush marks use " + name + ". Existing marks stay unchanged.");
        });
        tools.addView(effectButton);

        heatButton = button("Heatmap: ON");
        heatButton.setOnClickListener(v -> {
            editor.setHeatmap(!editor.isHeatmap());
            heatButton.setText(editor.isHeatmap() ? "Heatmap: ON" : "Heatmap: OFF");
        });
        tools.addView(heatButton);

        linkButton = button("Link All: OFF");
        linkButton.setOnClickListener(v -> {
            editor.setLinkAll(!editor.isLinkAll());
            linkButton.setText(editor.isLinkAll() ? "Link All: ON" : "Link All: OFF");
            status(editor.isLinkAll() ? "Brush strokes mirror across all photos." : "Editing selected photo only.");
        });
        tools.addView(linkButton);

        Button undo = button("Undo");
        undo.setOnClickListener(v -> {
            if (!editor.undo()) status("Nothing to undo.");
            else status("Undo.");
        });
        tools.addView(undo);

        Button redo = button("Redo");
        redo.setOnClickListener(v -> {
            if (!editor.redo()) status("Nothing to redo.");
            else status("Redo.");
        });
        tools.addView(redo);

        Button smart = button("Smart Motion");
        smart.setOnClickListener(v -> {
            editor.smartMotion();
            status("Smart Motion placed soft effect regions on high-contrast areas.");
        });
        tools.addView(smart);

        Button clear = button("Clear Effects");
        clear.setOnClickListener(v -> {
            editor.clearCurrent();
            status(editor.isLinkAll() ? "Effects cleared from all photos." : "Effects cleared from selected photo.");
        });
        tools.addView(clear);
        root.addView(scroller(tools));

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.HORIZONTAL);
        sliders.setPadding(dp(8), 0, dp(8), dp(6));
        addSlider(sliders, "Brush", 36, value -> editor.setBrushProgress(value));
        addSlider(sliders, "Intensity", 78, value -> editor.setIntensityProgress(value));
        addSlider(sliders, "Feather", 82, value -> editor.setFeatherProgress(value));
        root.addView(scroller(sliders));

        setContentView(root);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private HorizontalScrollView scroller(View child) {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(child, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private interface SliderSetter { void set(int value); }

    private void addSlider(LinearLayout row, String name, int initial, SliderSetter setter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), 0, dp(6), 0);
        TextView label = new TextView(this);
        label.setText(name + ": " + initial + "%");
        label.setTextColor(Color.rgb(220, 220, 230));
        label.setTextSize(11f);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(initial);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(165), dp(38)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = Math.max(1, progress);
                label.setText(name + ": " + v + "%");
                setter.set(v);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        box.addView(label);
        box.addView(bar);
        row.addView(box);
        setter.set(initial);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
                int room = Math.max(0, MAX_PHOTOS - editor.getPhotoCount());
                int added = Math.min(room, decoded.size());
                for (int i = 0; i < added; i++) editor.addPhoto(decoded.get(i));
                for (int i = added; i < decoded.size(); i++) decoded.get(i).recycle();
                status(editor.getPhotoCount() + " photo" + (editor.getPhotoCount() == 1 ? "" : "s") + " loaded. Paint directly on a tile.");
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

    private File projectDir() {
        return new File(getFilesDir(), "jelliforge_saved_project");
    }

    private void saveProject(boolean announce) {
        if (editor.getPhotoCount() == 0) {
            if (announce) Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (announce) status("Saving editable project…");
        worker.execute(() -> {
            try {
                editor.saveProject(projectDir());
                if (announce) runOnUiThread(() -> {
                    status("Project saved. Open Saved will restore the photos and every painted effect.");
                    Toast.makeText(this, "Project saved", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (announce) runOnUiThread(() -> status("Project save failed: " + e.getMessage()));
            }
        });
    }

    private void loadProject() {
        File dir = projectDir();
        File project = new File(dir, "project.json");
        if (!project.exists()) {
            Toast.makeText(this, "No saved project yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Opening saved project…");
        worker.execute(() -> {
            try {
                LoadedProject loaded = readProject(dir);
                runOnUiThread(() -> {
                    editor.replaceProject(loaded);
                    toolButton.setText("Tool: " + editor.toolName());
                    effectButton.setText("Effect: " + editor.effectName());
                    linkButton.setText(editor.isLinkAll() ? "Link All: ON" : "Link All: OFF");
                    heatButton.setText(editor.isHeatmap() ? "Heatmap: ON" : "Heatmap: OFF");
                    status("Saved project restored with editable effect marks.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Open failed: " + e.getMessage()));
            }
        });
    }

    private LoadedProject readProject(File dir) throws Exception {
        String text;
        try (InputStream in = new FileInputStream(new File(dir, "project.json"))) {
            text = readUtf8(in);
        }
        JSONObject root = new JSONObject(text);
        LoadedProject out = new LoadedProject();
        out.selected = root.optInt("selected", 0);
        out.linkAll = root.optBoolean("linkAll", false);
        out.heatmap = root.optBoolean("heatmap", true);
        out.tool = root.optInt("tool", EditorView.TOOL_PAINT);
        out.effect = root.optInt("effect", 0);
        out.brushRadius = (float) root.optDouble("brushRadius", 0.11);
        out.intensity = (float) root.optDouble("intensity", 0.78);
        out.feather = (float) root.optDouble("feather", 0.82);

        JSONArray photos = root.getJSONArray("photos");
        for (int i = 0; i < photos.length() && out.photos.size() < MAX_PHOTOS; i++) {
            JSONObject pj = photos.getJSONObject(i);
            File imageFile = new File(dir, pj.getString("file"));
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            if (bitmap == null) continue;
            PhotoItem item = new PhotoItem(bitmap);
            JSONArray marks = pj.optJSONArray("marks");
            if (marks != null) {
                for (int m = 0; m < marks.length(); m++) {
                    JSONObject mj = marks.getJSONObject(m);
                    item.marks.add(new BrushMark(
                            (float) mj.optDouble("x", 0.5),
                            (float) mj.optDouble("y", 0.5),
                            (float) mj.optDouble("radius", 0.1),
                            (float) mj.optDouble("intensity", 0.5),
                            (float) mj.optDouble("feather", 0.8),
                            mj.optInt("effect", 0)));
                }
            }
            out.photos.add(item);
        }
        return out;
    }

    private String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private void saveStill() {
        if (editor.getPhotoCount() == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Saving clean PNG without the heatmap overlay…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                Bitmap frame = editor.renderComposite(editor.currentPhase(), 1600);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "JelliForge_v11_" + System.currentTimeMillis() + ".png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JelliForge");
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Could not create PNG");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
                }
                frame.recycle();
                runOnUiThread(() -> {
                    status("PNG saved to Pictures/JelliForge.");
                    Toast.makeText(this, "PNG saved", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (uri != null) getContentResolver().delete(uri, null, null);
                runOnUiThread(() -> status("PNG save failed: " + e.getMessage()));
            }
        });
    }

    private void exportGridGif() {
        if (editor.getPhotoCount() == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Encoding animated grid GIF…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "JelliForge_v11_Grid_" + System.currentTimeMillis() + ".gif");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JelliForge");
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Could not create GIF");
                try (OutputStream raw = getContentResolver().openOutputStream(uri);
                     BufferedOutputStream out = new BufferedOutputStream(raw)) {
                    int frames = 36;
                    GifEncoder enc = null;
                    for (int i = 0; i < frames; i++) {
                        float phase = (float) (Math.PI * 2.0 * i / frames);
                        Bitmap frame = editor.renderComposite(phase, 950);
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
        int count = editor.getPhotoCount();
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
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, String.format(Locale.US, "JelliForge_v11_%02d_%d.gif", index + 1, System.currentTimeMillis()));
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JelliForge");
                    uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IOException("Output create failed");
                    try (OutputStream raw = getContentResolver().openOutputStream(uri);
                         BufferedOutputStream out = new BufferedOutputStream(raw)) {
                        int frames = 30;
                        GifEncoder enc = null;
                        for (int f = 0; f < frames; f++) {
                            float phase = (float) (Math.PI * 2.0 * f / frames);
                            Bitmap frame = editor.renderSingle(index, phase, 760);
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
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) editor.setTilt(event.values[0], event.values[1]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private final class EditorView extends View {
        static final int TOOL_PAINT = 0;
        static final int TOOL_ERASE = 1;
        static final int TOOL_OVERRIDE = 2;
        static final int TOOL_PREVIEW = 3;
        private static final int MW = 30;
        private static final int MH = 30;
        private static final int HISTORY_LIMIT = 30;

        private final ArrayList<PhotoItem> photos = new ArrayList<>();
        private final ArrayList<HistoryState> undoStack = new ArrayList<>();
        private final ArrayList<HistoryState> redoStack = new ArrayList<>();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint heatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selected = -1;
        private int activeTile = -1;
        private RectF activeRect;
        private boolean playing = true;
        private boolean linkAll = false;
        private boolean heatmap = true;
        private long startMs = SystemClock.uptimeMillis();
        private volatile float tiltX;
        private volatile float tiltY;
        private int tool = TOOL_PAINT;
        private int currentEffect = 0;
        private float brushRadius = 0.11f;
        private float intensity = 0.78f;
        private float feather = 0.82f;
        private float lastStampX = -10f;
        private float lastStampY = -10f;
        private float cursorX;
        private float cursorY;
        private boolean cursorVisible;
        private boolean strokeActive;

        EditorView() {
            super(MainActivityV11.this);
            setBackgroundColor(Color.rgb(20, 20, 25));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(3));
            cursorPaint.setStyle(Paint.Style.STROKE);
            cursorPaint.setStrokeWidth(dp(2));
            cursorPaint.setColor(Color.WHITE);
        }

        int getPhotoCount() { synchronized (photos) { return photos.size(); } }
        boolean isPlaying() { return playing; }
        boolean isLinkAll() { return linkAll; }
        boolean isHeatmap() { return heatmap; }
        void setLinkAll(boolean value) { linkAll = value; invalidate(); }
        void setHeatmap(boolean value) { heatmap = value; invalidate(); }

        void setBrushProgress(int value) {
            float t = clamp(value / 100f, 0.01f, 1f);
            brushRadius = 0.025f + t * 0.275f;
            invalidate();
        }

        void setIntensityProgress(int value) {
            intensity = clamp(value / 100f, 0.03f, 1f);
            invalidate();
        }

        void setFeatherProgress(int value) {
            feather = clamp(0.35f + (value / 100f) * 0.65f, 0.35f, 1f);
            invalidate();
        }

        void addPhoto(Bitmap bitmap) {
            synchronized (photos) {
                if (photos.size() >= MAX_PHOTOS) return;
                photos.add(new PhotoItem(bitmap));
                selected = photos.size() - 1;
            }
            undoStack.clear();
            redoStack.clear();
            invalidate();
        }

        void replaceProject(LoadedProject loaded) {
            synchronized (photos) {
                for (PhotoItem p : photos) if (!p.bitmap.isRecycled()) p.bitmap.recycle();
                photos.clear();
                photos.addAll(loaded.photos);
                selected = photos.isEmpty() ? -1 : Math.max(0, Math.min(loaded.selected, photos.size() - 1));
                linkAll = loaded.linkAll;
                heatmap = loaded.heatmap;
                tool = clampInt(loaded.tool, TOOL_PAINT, TOOL_PREVIEW);
                currentEffect = clampInt(loaded.effect, 0, 4);
                brushRadius = clamp(loaded.brushRadius, 0.025f, 0.30f);
                intensity = clamp(loaded.intensity, 0.03f, 1f);
                feather = clamp(loaded.feather, 0.35f, 1f);
            }
            undoStack.clear();
            redoStack.clear();
            invalidate();
        }

        void saveProject(File dir) throws Exception {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create project folder");
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) if (f.getName().startsWith("photo_") && f.getName().endsWith(".png")) f.delete();
            }

            JSONObject root = new JSONObject();
            JSONArray photoArray = new JSONArray();
            synchronized (photos) {
                root.put("version", 2);
                root.put("selected", selected);
                root.put("linkAll", linkAll);
                root.put("heatmap", heatmap);
                root.put("tool", tool);
                root.put("effect", currentEffect);
                root.put("brushRadius", brushRadius);
                root.put("intensity", intensity);
                root.put("feather", feather);

                for (int i = 0; i < photos.size(); i++) {
                    PhotoItem item = photos.get(i);
                    String fileName = String.format(Locale.US, "photo_%02d.png", i);
                    File imageFile = new File(dir, fileName);
                    try (OutputStream out = new FileOutputStream(imageFile)) {
                        if (!item.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Photo save failed");
                    }
                    JSONObject pj = new JSONObject();
                    pj.put("file", fileName);
                    JSONArray marks = new JSONArray();
                    for (BrushMark mark : item.marks) {
                        JSONObject mj = new JSONObject();
                        mj.put("x", mark.x);
                        mj.put("y", mark.y);
                        mj.put("radius", mark.radius);
                        mj.put("intensity", mark.intensity);
                        mj.put("feather", mark.feather);
                        mj.put("effect", mark.effect);
                        marks.put(mj);
                    }
                    pj.put("marks", marks);
                    photoArray.put(pj);
                }
            }
            root.put("photos", photoArray);
            File temp = new File(dir, "project.json.tmp");
            try (OutputStream out = new FileOutputStream(temp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            File dest = new File(dir, "project.json");
            if (dest.exists() && !dest.delete()) throw new IOException("Could not replace old project");
            if (!temp.renameTo(dest)) throw new IOException("Could not finalize project");
        }

        void togglePlaying() {
            playing = !playing;
            if (playing) {
                startMs = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
            } else invalidate();
        }

        float currentPhase() {
            return playing ? (SystemClock.uptimeMillis() - startMs) / 430f : 0f;
        }

        void setTilt(float x, float y) {
            tiltX = clamp(-x / 9.81f, -1f, 1f);
            tiltY = clamp(y / 9.81f, -1f, 1f);
            if (playing) postInvalidateOnAnimation();
        }

        String cycleTool() {
            tool = (tool + 1) % 4;
            invalidate();
            return toolName();
        }

        String cycleEffect() {
            currentEffect = (currentEffect + 1) % 5;
            invalidate();
            return effectName();
        }

        String toolName() {
            switch (tool) {
                case TOOL_ERASE: return "Erase";
                case TOOL_OVERRIDE: return "Override";
                case TOOL_PREVIEW: return "Preview";
                default: return "Paint";
            }
        }

        String effectName() { return MainActivityV11.effectName(currentEffect); }

        void clearCurrent() {
            synchronized (photos) {
                if (photos.isEmpty()) return;
                pushUndoLocked();
                if (linkAll) {
                    for (PhotoItem p : photos) p.marks.clear();
                } else if (selected >= 0 && selected < photos.size()) {
                    photos.get(selected).marks.clear();
                }
            }
            invalidate();
        }

        void smartMotion() {
            synchronized (photos) {
                if (photos.isEmpty()) return;
                pushUndoLocked();
                if (linkAll) {
                    for (PhotoItem p : photos) seedSmartMarks(p);
                } else if (selected >= 0 && selected < photos.size()) {
                    seedSmartMarks(photos.get(selected));
                }
            }
            invalidate();
        }

        private void seedSmartMarks(PhotoItem item) {
            float[] bestScore = {-1f, -1f, -1f};
            float[] bestX = {0.5f, 0.35f, 0.65f};
            float[] bestY = {0.5f, 0.45f, 0.55f};
            Bitmap b = item.bitmap;
            for (int gy = 1; gy <= 7; gy++) {
                for (int gx = 1; gx <= 7; gx++) {
                    float nx = gx / 8f;
                    float ny = gy / 8f;
                    int px = Math.min(b.getWidth() - 2, Math.max(1, Math.round(nx * (b.getWidth() - 1))));
                    int py = Math.min(b.getHeight() - 2, Math.max(1, Math.round(ny * (b.getHeight() - 1))));
                    float dx = luma(b.getPixel(px + 1, py)) - luma(b.getPixel(px - 1, py));
                    float dy = luma(b.getPixel(px, py + 1)) - luma(b.getPixel(px, py - 1));
                    float score = Math.abs(dx) + Math.abs(dy);
                    int slot = 0;
                    for (int k = 1; k < 3; k++) if (bestScore[k] < bestScore[slot]) slot = k;
                    if (score > bestScore[slot]) {
                        bestScore[slot] = score;
                        bestX[slot] = nx;
                        bestY[slot] = ny;
                    }
                }
            }
            for (int i = 0; i < 3; i++) {
                item.marks.add(new BrushMark(bestX[i], bestY[i], Math.max(brushRadius, 0.16f),
                        Math.max(intensity * (0.78f + i * 0.07f), 0.48f), feather, currentEffect));
            }
        }

        private float luma(int c) {
            return Color.red(c) * 0.2126f + Color.green(c) * 0.7152f + Color.blue(c) * 0.0722f;
        }

        boolean undo() {
            synchronized (photos) {
                if (undoStack.isEmpty()) return false;
                redoStack.add(snapshotLocked());
                HistoryState state = undoStack.remove(undoStack.size() - 1);
                restoreLocked(state);
            }
            invalidate();
            return true;
        }

        boolean redo() {
            synchronized (photos) {
                if (redoStack.isEmpty()) return false;
                undoStack.add(snapshotLocked());
                HistoryState state = redoStack.remove(redoStack.size() - 1);
                restoreLocked(state);
            }
            invalidate();
            return true;
        }

        private void pushUndoLocked() {
            undoStack.add(snapshotLocked());
            while (undoStack.size() > HISTORY_LIMIT) undoStack.remove(0);
            redoStack.clear();
        }

        private HistoryState snapshotLocked() {
            HistoryState state = new HistoryState();
            state.selected = selected;
            for (PhotoItem p : photos) {
                ArrayList<BrushMark> copy = new ArrayList<>(p.marks.size());
                for (BrushMark m : p.marks) copy.add(m.copy());
                state.marks.add(copy);
            }
            return state;
        }

        private void restoreLocked(HistoryState state) {
            selected = photos.isEmpty() ? -1 : Math.max(0, Math.min(state.selected, photos.size() - 1));
            for (int i = 0; i < photos.size(); i++) {
                photos.get(i).marks.clear();
                if (i < state.marks.size()) {
                    for (BrushMark m : state.marks.get(i)) photos.get(i).marks.add(m.copy());
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawScene(canvas, getWidth(), getHeight(), currentPhase(), true, heatmap);
            if (playing) postInvalidateOnAnimation();
        }

        private void drawScene(Canvas canvas, int w, int h, float phase, boolean showSelection, boolean showHeat) {
            canvas.drawColor(Color.rgb(20, 20, 25));
            synchronized (photos) {
                int n = photos.size();
                if (n == 0) {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Color.rgb(175, 175, 190));
                    p.setTextSize(Math.max(25f, w * 0.036f));
                    p.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("Add photos, choose Paint, then draw motion.", w / 2f, h / 2f, p);
                    return;
                }
                for (int i = 0; i < n; i++) {
                    RectF rect = photoRect(i, n, w, h, photos.get(i).bitmap);
                    drawItem(canvas, photos.get(i), rect, phase, showSelection && i == selected,
                            showHeat && i == selected);
                }
            }
            if (cursorVisible && activeRect != null && tool != TOOL_PREVIEW) {
                float radiusPx = brushRadius * Math.min(activeRect.width(), activeRect.height());
                cursorPaint.setColor(tool == TOOL_ERASE ? Color.WHITE : heatColor(intensity));
                canvas.drawCircle(cursorX, cursorY, radiusPx, cursorPaint);
            }
        }

        private void drawItem(Canvas canvas, PhotoItem item, RectF rect, float phase, boolean selectedTile, boolean showHeat) {
            float[] verts = buildVerts(item, rect, phase);
            canvas.drawBitmapMesh(item.bitmap, MW, MH, verts, 0, null, 0, null);
            if (showHeat) drawHeatmap(canvas, item, rect);
            if (selectedTile) {
                border.setColor(Color.rgb(135, 225, 255));
                canvas.drawRoundRect(rect, dp(6), dp(6), border);
            }
        }

        private void drawHeatmap(Canvas canvas, PhotoItem item, RectF rect) {
            float minSide = Math.min(rect.width(), rect.height());
            for (BrushMark m : item.marks) {
                float cx = rect.left + m.x * rect.width();
                float cy = rect.top + m.y * rect.height();
                float radiusPx = Math.max(dp(3), m.radius * minSide);
                int c0 = withAlpha(heatColor(m.intensity), 118);
                int c1 = withAlpha(heatColor(m.intensity * 0.72f), 88);
                int c2 = withAlpha(heatColor(m.intensity * 0.42f), 62);
                int c3 = withAlpha(heatColor(Math.max(0.06f, m.intensity * 0.16f)), 38);
                RadialGradient gradient = new RadialGradient(cx, cy, radiusPx,
                        new int[]{c0, c1, c2, c3, Color.TRANSPARENT},
                        new float[]{0f, 0.30f, 0.56f, 0.82f, 1f}, Shader.TileMode.CLAMP);
                heatPaint.setShader(gradient);
                canvas.drawCircle(cx, cy, radiusPx, heatPaint);
                heatPaint.setShader(null);
            }
        }

        private int heatColor(float value) {
            float v = clamp(value, 0f, 1f);
            if (v < 0.34f) {
                float t = v / 0.34f;
                return Color.rgb(Math.round(70 + 185 * t), 230, 55);
            }
            if (v < 0.67f) {
                float t = (v - 0.34f) / 0.33f;
                return Color.rgb(255, Math.round(225 - 80 * t), 35);
            }
            float t = (v - 0.67f) / 0.33f;
            return Color.rgb(255, Math.round(145 - 120 * t), Math.round(25 - 18 * t));
        }

        private int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        private float[] buildVerts(PhotoItem item, RectF rect, float phase) {
            float[] verts = new float[(MW + 1) * (MH + 1) * 2];
            int k = 0;
            float globalPhase = phase + tiltX * 0.75f - tiltY * 0.45f;
            float minSide = Math.min(rect.width(), rect.height());
            for (int y = 0; y <= MH; y++) {
                float ny = y / (float) MH;
                for (int x = 0; x <= MW; x++) {
                    float nx = x / (float) MW;
                    float px = rect.left + nx * rect.width();
                    float py = rect.top + ny * rect.height();
                    float ox = tiltX * rect.width() * 0.0035f * (float) Math.sin(globalPhase + ny * 2.7f);
                    float oy = tiltY * rect.height() * 0.0035f * (float) Math.cos(globalPhase + nx * 2.7f);

                    for (BrushMark mark : item.marks) {
                        float dxPx = (nx - mark.x) * rect.width();
                        float dyPx = (ny - mark.y) * rect.height();
                        float distance = (float) Math.sqrt(dxPx * dxPx + dyPx * dyPx);
                        float radiusPx = Math.max(2f, mark.radius * minSide);
                        if (distance >= radiusPx) continue;
                        float fall = softFalloff(distance / radiusPx, mark.feather);
                        float local = mark.intensity * fall;
                        if (local <= 0.001f) continue;
                        float d = Math.max(0.001f, distance);
                        float ux = dxPx / d;
                        float uy = dyPx / d;
                        float seed = mark.x * 9.3f + mark.y * 13.7f;
                        float s = (float) Math.sin(globalPhase + seed + distance / Math.max(8f, radiusPx) * 5f);
                        float amp = minSide * 0.052f * local;

                        switch (mark.effect) {
                            case 1:
                                ox += ux * amp * s;
                                oy += uy * amp * s;
                                break;
                            case 2:
                                ox += -uy * amp * s * 1.15f;
                                oy += ux * amp * s * 1.15f;
                                break;
                            case 3:
                                ox += amp * 0.65f * (float) Math.sin(globalPhase * 1.08f + ny * 15f + seed);
                                oy += amp * 0.65f * (float) Math.sin(globalPhase * 1.23f + nx * 14f + seed * 0.7f);
                                break;
                            case 4:
                                ox += ux * amp * s * 0.55f + amp * 0.32f * (float) Math.sin(globalPhase * 1.31f + seed);
                                oy += uy * amp * s * 0.55f + amp * 0.32f * (float) Math.cos(globalPhase * 1.17f + seed);
                                break;
                            default:
                                ox += amp * 0.72f * (float) Math.sin(globalPhase * 1.05f + seed + ny * 4f);
                                oy += amp * 0.72f * (float) Math.cos(globalPhase * 1.16f + seed + nx * 4f);
                                break;
                        }
                    }

                    ox = clamp(ox, -rect.width() * 0.18f, rect.width() * 0.18f);
                    oy = clamp(oy, -rect.height() * 0.18f, rect.height() * 0.18f);
                    verts[k++] = px + ox;
                    verts[k++] = py + oy;
                }
            }
            return verts;
        }

        private float softFalloff(float normalizedDistance, float featherAmount) {
            float d = clamp(normalizedDistance, 0f, 1f);
            float f = clamp(featherAmount, 0.35f, 1f);
            float core = 0.58f - 0.43f * f;
            if (d <= core) return 1f;
            float t = clamp((d - core) / Math.max(0.001f, 1f - core), 0f, 1f);
            float inv = 1f - t;
            return inv * inv * (3f - 2f * inv);
        }

        RectF photoRect(int index, int count, int w, int h, Bitmap bitmap) {
            int cols = count <= 1 ? 1 : (count <= 4 ? 2 : (count <= 9 ? 3 : 4));
            int rows = (int) Math.ceil(count / (double) cols);
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
            }
            float ww = cell.height() * imageAspect;
            float cx = cell.centerX();
            return new RectF(cx - ww / 2f, cell.top, cx + ww / 2f, cell.bottom);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (photos.isEmpty()) return true;
            float x = event.getX();
            float y = event.getY();
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                activeTile = findTile(x, y);
                if (activeTile < 0) return true;
                selected = activeTile;
                synchronized (photos) {
                    activeRect = photoRect(activeTile, photos.size(), getWidth(), getHeight(), photos.get(activeTile).bitmap);
                }
                cursorX = x;
                cursorY = y;
                cursorVisible = true;
                if (tool != TOOL_PREVIEW) {
                    synchronized (photos) { pushUndoLocked(); }
                    strokeActive = true;
                    lastStampX = -10f;
                    lastStampY = -10f;
                    stampScreenPoint(x, y, true);
                }
                invalidate();
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE && activeTile >= 0 && activeRect != null) {
                cursorX = x;
                cursorY = y;
                cursorVisible = true;
                if (strokeActive && tool != TOOL_PREVIEW) stampScreenPoint(x, y, false);
                invalidate();
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                cursorVisible = false;
                strokeActive = false;
                activeTile = -1;
                activeRect = null;
                lastStampX = -10f;
                lastStampY = -10f;
                invalidate();
                return true;
            }
            return true;
        }

        private void stampScreenPoint(float sx, float sy, boolean force) {
            if (activeRect == null || !activeRect.contains(sx, sy)) return;
            float nx = clamp((sx - activeRect.left) / Math.max(1f, activeRect.width()), 0f, 1f);
            float ny = clamp((sy - activeRect.top) / Math.max(1f, activeRect.height()), 0f, 1f);

            if (!force && lastStampX > -1f) {
                float dx = nx - lastStampX;
                float dy = ny - lastStampY;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float spacing = Math.max(0.004f, brushRadius * 0.22f);
                if (dist < spacing) return;
                int steps = Math.min(24, Math.max(1, (int) Math.ceil(dist / spacing)));
                for (int i = 1; i <= steps; i++) {
                    float t = i / (float) steps;
                    stampNormalized(lastStampX + dx * t, lastStampY + dy * t);
                }
            } else {
                stampNormalized(nx, ny);
            }
            lastStampX = nx;
            lastStampY = ny;
        }

        private void stampNormalized(float nx, float ny) {
            synchronized (photos) {
                if (linkAll) {
                    for (PhotoItem item : photos) applyTool(item, nx, ny);
                } else if (activeTile >= 0 && activeTile < photos.size()) {
                    applyTool(photos.get(activeTile), nx, ny);
                }
            }
        }

        private void applyTool(PhotoItem item, float nx, float ny) {
            if (tool == TOOL_ERASE) {
                eraseAt(item, nx, ny);
                return;
            }
            if (tool == TOOL_OVERRIDE) eraseOverlap(item, nx, ny, true);
            item.marks.add(new BrushMark(nx, ny, brushRadius, intensity, feather, currentEffect));
            if (item.marks.size() > 3200) item.marks.remove(0);
        }

        private void eraseAt(PhotoItem item, float nx, float ny) {
            for (int i = item.marks.size() - 1; i >= 0; i--) {
                BrushMark m = item.marks.get(i);
                float dx = m.x - nx;
                float dy = m.y - ny;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float reach = brushRadius * 0.78f + m.radius * 0.30f;
                if (d <= reach) item.marks.remove(i);
            }
        }

        private void eraseOverlap(PhotoItem item, float nx, float ny, boolean broad) {
            for (int i = item.marks.size() - 1; i >= 0; i--) {
                BrushMark m = item.marks.get(i);
                float dx = m.x - nx;
                float dy = m.y - ny;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float reach = brushRadius * (broad ? 0.92f : 0.70f) + m.radius * 0.22f;
                if (d <= reach) item.marks.remove(i);
            }
        }

        private int findTile(float x, float y) {
            synchronized (photos) {
                for (int i = 0; i < photos.size(); i++) {
                    RectF rect = photoRect(i, photos.size(), getWidth(), getHeight(), photos.get(i).bitmap);
                    if (rect.contains(x, y)) return i;
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
            if (outH > 1600) {
                float scale = 1600f / outH;
                outH = 1600;
                outW = Math.max(1, Math.round(outW * scale));
            }
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            drawScene(canvas, outW, outH, phase, false, false);
            return out;
        }

        Bitmap renderSingle(int index, float phase, int maxSide) {
            PhotoItem item;
            synchronized (photos) {
                if (index < 0 || index >= photos.size()) throw new IllegalArgumentException("Bad photo index");
                item = photos.get(index);
            }
            float aspect = item.bitmap.getWidth() / (float) item.bitmap.getHeight();
            int w;
            int h;
            if (aspect >= 1f) {
                w = maxSide;
                h = Math.max(1, Math.round(maxSide / aspect));
            } else {
                h = maxSide;
                w = Math.max(1, Math.round(maxSide * aspect));
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.rgb(20, 20, 25));
            drawItem(canvas, item, new RectF(0, 0, w, h), phase, false, false);
            return out;
        }

        private float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
        private int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    }

    private static String effectName(int effect) {
        switch (effect) {
            case 1: return "Pulse";
            case 2: return "Twist";
            case 3: return "Wave";
            case 4: return "Elastic";
            default: return "Wobble";
        }
    }

    private static final class PhotoItem {
        final Bitmap bitmap;
        final ArrayList<BrushMark> marks = new ArrayList<>();
        PhotoItem(Bitmap bitmap) { this.bitmap = bitmap; }
    }

    private static final class BrushMark {
        final float x;
        final float y;
        final float radius;
        final float intensity;
        final float feather;
        final int effect;

        BrushMark(float x, float y, float radius, float intensity, float feather, int effect) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.intensity = intensity;
            this.feather = feather;
            this.effect = effect;
        }

        BrushMark copy() { return new BrushMark(x, y, radius, intensity, feather, effect); }
    }

    private static final class HistoryState {
        int selected;
        final ArrayList<ArrayList<BrushMark>> marks = new ArrayList<>();
    }

    private static final class LoadedProject {
        final ArrayList<PhotoItem> photos = new ArrayList<>();
        int selected;
        boolean linkAll;
        boolean heatmap = true;
        int tool;
        int effect;
        float brushRadius;
        float intensity;
        float feather;
    }

    private static final class GifEncoder {
        private final OutputStream out;
        private final int width;
        private final int height;
        private final int delayCs;
        private boolean started;

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
            out.write(0xF7);
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
            private int buffer;
            private int bitCount;
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
