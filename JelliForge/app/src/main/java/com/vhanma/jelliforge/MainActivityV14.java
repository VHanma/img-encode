package com.vhanma.jelliforge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivityV14 extends Activity implements SensorEventListener {
    private static final int PICK_IMAGES = 41;
    private static final int SAVE_AS_PNG = 42;
    private static final int MAX_PHOTOS = 12;
    private static final int MW = 40;
    private static final int MH = 40;
    private static final int VX = MW + 1;
    private static final int VY = MH + 1;
    private static final int VERTS = VX * VY;

    private EditorView editor;
    private TextView statusView;
    private Button playButton;
    private Button toolButton;
    private Button effectButton;
    private Button materialButton;
    private Button heatButton;
    private Button linkButton;
    private Button focusButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private volatile Uri lastGalleryUri;
    private volatile String lastGalleryMime = "image/png";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 8, 12));
        getWindow().setNavigationBarColor(Color.rgb(8, 8, 12));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 8, 12));

        TextView title = new TextView(this);
        title.setText("JelliForge v1.4 • Rival 3 Mesh");
        title.setTextSize(24f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(14), dp(9), dp(14), dp(2));
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Hard Jello now rests still. Paint it, Touch/flick it, then SAVE PHOTO or SAVE ANIMATION.");
        statusView.setTextSize(13f);
        statusView.setTextColor(Color.rgb(198, 198, 216));
        statusView.setPadding(dp(14), 0, dp(14), dp(5));
        root.addView(statusView);

        editor = new EditorView();
        root.addView(editor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout save = row();
        addButton(save, "+ Photos", v -> pickPhotos());
        addButton(save, "Open Saved", v -> loadProject());
        addButton(save, "SAVE PROJECT", v -> saveProject(true));
        addButton(save, "SAVE PHOTO", v -> saveSelectedToGallery());
        addButton(save, "SAVE ANIMATION", v -> exportSelectedAnimation());
        addButton(save, "Save Grid", v -> saveGridToGallery());
        addButton(save, "Grid GIF", v -> exportGridAnimation());
        addButton(save, "Batch GIFs", v -> exportBatchAnimations());
        addButton(save, "Save As…", v -> startSaveAs());
        addButton(save, "Open Last Save", v -> openLastGalleryItem());
        root.addView(scroller(save));

        LinearLayout tools = row();
        toolButton = addButton(tools, "Tool: Paint", v -> {
            String n = editor.cycleTool();
            toolButton.setText("Tool: " + n);
            status(n.equals("Touch") ? "Touch mode: the painted mesh follows your finger, then rebounds." : "Tool: " + n + ".");
        });
        effectButton = addButton(tools, "Effect: Jellify", v -> {
            String n = editor.cycleEffect();
            effectButton.setText("Effect: " + n);
            status("New marks use " + n + ".");
        });
        materialButton = addButton(tools, "Material: Hard Jello", v -> {
            String n = editor.cycleMaterial();
            materialButton.setText("Material: " + n);
            status(n + " selected.");
        });
        focusButton = addButton(tools, "Focus Photo", v -> {
            editor.setFocusSelected(!editor.isFocusSelected());
            focusButton.setText(editor.isFocusSelected() ? "Show Grid" : "Focus Photo");
        });
        heatButton = addButton(tools, "Heatmap: ON", v -> {
            editor.setHeatmap(!editor.isHeatmap());
            heatButton.setText(editor.isHeatmap() ? "Heatmap: ON" : "Heatmap: OFF");
        });
        linkButton = addButton(tools, "Link All: OFF", v -> {
            editor.setLinkAll(!editor.isLinkAll());
            linkButton.setText(editor.isLinkAll() ? "Link All: ON" : "Link All: OFF");
        });
        root.addView(scroller(tools));

        LinearLayout edit = row();
        addButton(edit, "Undo", v -> status(editor.undo() ? "Undo." : "Nothing to undo."));
        addButton(edit, "Redo", v -> status(editor.redo() ? "Redo." : "Nothing to redo."));
        addButton(edit, "JIGGLE", v -> { editor.jiggleCurrent(); status("Jelly impulse applied."); });
        addButton(edit, "Settle", v -> { editor.settleMotion(); status("Jelly settled."); });
        addButton(edit, "Smart Jellify", v -> { editor.smartJellify(); requestAutosave(); });
        addButton(edit, "Clear Effects", v -> { editor.clearCurrent(); requestAutosave(); });
        playButton = addButton(edit, "Pause", v -> {
            editor.togglePlaying();
            playButton.setText(editor.isPlaying() ? "Pause" : "Play");
        });
        root.addView(scroller(edit));

        LinearLayout sliders = row();
        addSlider(sliders, "Brush", 36, editor::setBrushProgress);
        addSlider(sliders, "Intensity", 78, editor::setIntensityProgress);
        addSlider(sliders, "Feather", 82, editor::setFeatherProgress);
        addSlider(sliders, "Bounce", 62, editor::setBounceProgress);
        addSlider(sliders, "Speed", 50, editor::setSpeedProgress);
        root.addView(scroller(sliders));

        setContentView(root);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(6), dp(2), dp(6), dp(2));
        return r;
    }

    private HorizontalScrollView scroller(View child) {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(child, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private Button addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(45));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        row.addView(b);
        return b;
    }

    private interface SliderSetter { void set(int value); }
    private void addSlider(LinearLayout row, String name, int initial, SliderSetter setter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(5), 0, dp(5), 0);
        TextView label = new TextView(this);
        label.setText(name + ": " + initial + "%");
        label.setTextColor(Color.rgb(224, 224, 234));
        label.setTextSize(11f);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(initial);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(150), dp(38)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                int v = Math.max(1, p);
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

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void status(String s) { statusView.setText(s); }

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_IMAGES);
    }

    private void startSaveAs() {
        if (editor.getPhotoCount() == 0) { Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_TITLE, "JelliForge_WhatYouSee_" + System.currentTimeMillis() + ".png");
        startActivityForResult(i, SAVE_AS_PNG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == SAVE_AS_PNG) {
            Uri target = data.getData();
            if (target == null) return;
            EditorSnapshot snap = editor.snapshot();
            worker.execute(() -> {
                Bitmap frame = null;
                try {
                    frame = renderSingle(snap, snap.selected, snap.phase, 2800);
                    try (OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                        if (out == null || !frame.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG write failed");
                        out.flush();
                    }
                    runOnUiThread(() -> status("Saved exact deformed frame to the location you chose."));
                } catch (Exception e) {
                    runOnUiThread(() -> status("Save As failed: " + e.getMessage()));
                } finally { if (frame != null) frame.recycle(); }
            });
            return;
        }
        if (requestCode != PICK_IMAGES) return;

        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int n = 0; n < clip.getItemCount() && uris.size() < MAX_PHOTOS; n++) uris.add(clip.getItemAt(n).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;

        status("Loading photos…");
        worker.execute(() -> {
            ArrayList<Bitmap> decoded = new ArrayList<>();
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                Bitmap b = decodeScaled(uri, 2200);
                if (b != null) decoded.add(b);
            }
            runOnUiThread(() -> {
                int room = Math.max(0, MAX_PHOTOS - editor.getPhotoCount());
                int added = Math.min(room, decoded.size());
                for (int i = 0; i < added; i++) editor.addPhoto(decoded.get(i));
                for (int i = added; i < decoded.size(); i++) decoded.get(i).recycle();
                status(editor.getPhotoCount() + " photos loaded. Paint Jellify, then Touch or JIGGLE.");
                requestAutosave();
            });
        });
    }

    private Bitmap decodeScaled(Uri uri, int maxSide) {
        try {
            ContentResolver cr = getContentResolver();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = cr.openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, opts); }
        } catch (Exception e) { return null; }
    }

    private File projectDir() { return new File(getFilesDir(), "jelliforge_saved_project"); }
    private void requestAutosave() { if (editor != null && editor.getPhotoCount() > 0) saveProject(false); }

    private void saveProject(boolean announce) {
        if (editor.getPhotoCount() == 0) { if (announce) Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show(); return; }
        if (announce) status("Saving editable project…");
        worker.execute(() -> {
            try {
                editor.saveProject(projectDir());
                if (announce) runOnUiThread(() -> { status("Project saved with photos, painted effects and settings."); Toast.makeText(this, "Project saved", Toast.LENGTH_SHORT).show(); });
            } catch (Exception e) { if (announce) runOnUiThread(() -> status("Project save failed: " + e.getMessage())); }
        });
    }

    private void loadProject() {
        File dir = projectDir();
        if (!new File(dir, "project.json").exists()) { Toast.makeText(this, "No saved project yet.", Toast.LENGTH_SHORT).show(); return; }
        worker.execute(() -> {
            try {
                LoadedProject loaded = readProject(dir);
                runOnUiThread(() -> { editor.replaceProject(loaded); syncButtons(); status("Project restored. Painted Jellify mask is intact."); });
            } catch (Exception e) { runOnUiThread(() -> status("Open failed: " + e.getMessage())); }
        });
    }

    private void syncButtons() {
        toolButton.setText("Tool: " + editor.toolName());
        effectButton.setText("Effect: " + editor.effectName());
        materialButton.setText("Material: " + editor.materialName());
        heatButton.setText(editor.isHeatmap() ? "Heatmap: ON" : "Heatmap: OFF");
        linkButton.setText(editor.isLinkAll() ? "Link All: ON" : "Link All: OFF");
        focusButton.setText(editor.isFocusSelected() ? "Show Grid" : "Focus Photo");
    }

    private LoadedProject readProject(File dir) throws Exception {
        String text;
        try (InputStream in = new FileInputStream(new File(dir, "project.json"))) { text = readUtf8(in); }
        JSONObject root = new JSONObject(text);
        LoadedProject out = new LoadedProject();
        out.selected = root.optInt("selected", 0);
        out.linkAll = root.optBoolean("linkAll", false);
        out.heatmap = root.optBoolean("heatmap", true);
        out.focus = root.optBoolean("focusSelected", false);
        out.tool = root.optInt("tool", EditorView.TOOL_PAINT);
        out.effect = root.optInt("effect", EditorView.EFFECT_JELLIFY);
        out.material = root.optInt("material", EditorView.MATERIAL_HARD);
        out.brush = (float) root.optDouble("brushRadius", 0.124);
        out.intensity = (float) root.optDouble("intensity", 0.78);
        out.feather = (float) root.optDouble("feather", 0.82);
        out.bounce = (float) root.optDouble("bounce", 0.62);
        out.speed = (float) root.optDouble("speed", 1.0);
        JSONArray photos = root.getJSONArray("photos");
        for (int i = 0; i < photos.length() && out.photos.size() < MAX_PHOTOS; i++) {
            JSONObject pj = photos.getJSONObject(i);
            Bitmap bitmap = BitmapFactory.decodeFile(new File(dir, pj.getString("file")).getAbsolutePath());
            if (bitmap == null) continue;
            PhotoItem item = new PhotoItem(bitmap);
            JSONArray marks = pj.optJSONArray("marks");
            if (marks != null) {
                for (int m = 0; m < marks.length(); m++) {
                    JSONObject j = marks.getJSONObject(m);
                    item.marks.add(new BrushMark((float) j.optDouble("x", .5), (float) j.optDouble("y", .5),
                            (float) j.optDouble("radius", .1), (float) j.optDouble("intensity", .5),
                            (float) j.optDouble("feather", .8), j.optInt("effect", 5)));
                }
            }
            item.mesh.dirty = true;
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

    private Uri beginGalleryItem(String name, String mime, int width, int height) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, mime);
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/JelliForge/");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        v.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        v.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L);
        if (width > 0) v.put(MediaStore.Images.Media.WIDTH, width);
        if (height > 0) v.put(MediaStore.Images.Media.HEIGHT, height);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IOException("MediaStore could not create output");
        return uri;
    }

    private void finishGalleryItem(Uri uri, String mime) throws IOException {
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (getContentResolver().update(uri, done, null, null) <= 0) throw new IOException("Could not finalize Gallery item");
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new IOException("Saved item could not be verified");
        }
        getContentResolver().notifyChange(uri, null);
        lastGalleryUri = uri;
        lastGalleryMime = mime;
    }

    private void abortGalleryItem(Uri uri) { if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { } }

    private void savePng(Bitmap frame, String name) throws Exception {
        Uri uri = null;
        try {
            uri = beginGalleryItem(name, "image/png", frame.getWidth(), frame.getHeight());
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null || !frame.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
                out.flush();
            }
            finishGalleryItem(uri, "image/png");
        } catch (Exception e) { abortGalleryItem(uri); throw e; }
    }

    private void saveSelectedToGallery() {
        if (editor.getPhotoCount() == 0) return;
        final EditorSnapshot snap = editor.snapshot();
        status("Freezing exactly what you see…");
        worker.execute(() -> {
            Bitmap frame = null;
            try {
                frame = renderSingle(snap, snap.selected, snap.phase, 3000);
                savePng(frame, "JelliForge_Frame_" + System.currentTimeMillis() + ".png");
                runOnUiThread(() -> { status("Saved exact deformed frame to DCIM/JelliForge."); Toast.makeText(this, "Effect baked into PNG", Toast.LENGTH_LONG).show(); });
            } catch (Exception e) { runOnUiThread(() -> status("Save failed: " + e.getMessage())); }
            finally { if (frame != null) frame.recycle(); }
        });
    }

    private void saveGridToGallery() {
        if (editor.getPhotoCount() == 0) return;
        final EditorSnapshot snap = editor.snapshot();
        worker.execute(() -> {
            Bitmap frame = null;
            try {
                frame = renderGrid(snap, snap.phase, 2400);
                savePng(frame, "JelliForge_Grid_" + System.currentTimeMillis() + ".png");
                runOnUiThread(() -> status("Grid saved with current deformation baked in."));
            } catch (Exception e) { runOnUiThread(() -> status("Grid save failed: " + e.getMessage())); }
            finally { if (frame != null) frame.recycle(); }
        });
    }

    private void exportSelectedAnimation() {
        if (editor.getPhotoCount() == 0) return;
        EditorSnapshot snap = editor.snapshot();
        status("Baking real spring-mesh motion into GIF…");
        worker.execute(() -> exportGif(snap, snap.selected, false));
    }

    private void exportGridAnimation() {
        if (editor.getPhotoCount() == 0) return;
        EditorSnapshot snap = editor.snapshot();
        status("Baking grid spring motion into GIF…");
        worker.execute(() -> exportGif(snap, -1, true));
    }

    private void exportBatchAnimations() {
        if (editor.getPhotoCount() == 0) return;
        EditorSnapshot base = editor.snapshot();
        worker.execute(() -> {
            int made = 0;
            for (int i = 0; i < base.photos.size(); i++) {
                EditorSnapshot copy = base.deepCopy();
                if (exportGifInternal(copy, i, false, String.format(Locale.US, "JelliForge_%02d_%d.gif", i + 1, System.currentTimeMillis()))) made++;
            }
            int done = made;
            runOnUiThread(() -> status("Batch complete: " + done + "/" + base.photos.size() + " animated GIFs."));
        });
    }

    private void exportGif(EditorSnapshot snap, int index, boolean grid) {
        String name = grid ? "JelliForge_GridMotion_" + System.currentTimeMillis() + ".gif" : "JelliForge_JellyMotion_" + System.currentTimeMillis() + ".gif";
        boolean ok = exportGifInternal(snap, index, grid, name);
        runOnUiThread(() -> status(ok ? "Animated effect saved to Gallery." : "Animated save failed."));
    }

    private boolean exportGifInternal(EditorSnapshot snap, int index, boolean grid, String name) {
        Uri uri = null;
        try {
            if (grid) for (PhotoSnapshot p : snap.photos) ensureVisibleMotion(p, snap.bounce);
            else ensureVisibleMotion(snap.photos.get(Math.max(0, Math.min(index, snap.photos.size() - 1))), snap.bounce);
            Bitmap first = grid ? renderGrid(snap, 0f, 1080) : renderSingle(snap, index, 0f, 960);
            int width = first.getWidth(), height = first.getHeight();
            uri = beginGalleryItem(name, "image/gif", width, height);
            try (OutputStream raw = getContentResolver().openOutputStream(uri, "w"); BufferedOutputStream out = new BufferedOutputStream(raw)) {
                GifEncoder332 enc = new GifEncoder332(out, width, height, 50);
                enc.start();
                enc.addFrame(first);
                first.recycle();
                final int frames = 56;
                for (int f = 1; f < frames; f++) {
                    advanceSnapshot(snap, 0.050f);
                    float phase = f * 0.30f * snap.speed;
                    Bitmap frame = grid ? renderGrid(snap, phase, 1080) : renderSingle(snap, index, phase, 960);
                    enc.addFrame(frame);
                    frame.recycle();
                }
                enc.finish();
            }
            finishGalleryItem(uri, "image/gif");
            return true;
        } catch (Exception e) { abortGalleryItem(uri); return false; }
    }

    private void openLastGalleryItem() {
        if (lastGalleryUri == null) { Toast.makeText(this, "Save something first.", Toast.LENGTH_SHORT).show(); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastGalleryUri, lastGalleryMime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open JelliForge save"));
        } catch (Exception e) { status("Saved, but no installed viewer accepted the file."); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }
    @Override protected void onPause() { super.onPause(); if (sensorManager != null) sensorManager.unregisterListener(this); requestAutosave(); }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }
    @Override public void onSensorChanged(SensorEvent event) { if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) editor.setTilt(event.values[0], event.values[1]); }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static void ensureVisibleMotion(PhotoSnapshot p, float bounce) {
        p.mesh.ensureMask(p.marks);
        float energy = 0f;
        for (int i = 0; i < VERTS; i++) energy += Math.abs(p.mesh.vx[i]) + Math.abs(p.mesh.vy[i]) + Math.abs(p.mesh.dx[i]) * 3f + Math.abs(p.mesh.dy[i]) * 3f;
        if (energy > 0.08f) return;
        for (int gy = 0; gy <= MH; gy++) {
            float ny = gy / (float) MH;
            for (int gx = 0; gx <= MW; gx++) {
                int idx = gy * VX + gx;
                float w = p.mesh.weight[idx];
                if (w < 0.02f) continue;
                float nx = gx / (float) MW;
                p.mesh.vx[idx] += (float) Math.sin(nx * 5.4f + ny * 2.1f) * (0.13f + bounce * 0.12f) * w;
                p.mesh.vy[idx] += (float) Math.cos(nx * 2.6f - ny * 4.2f) * (0.17f + bounce * 0.14f) * w;
            }
        }
    }

    private static void advanceSnapshot(EditorSnapshot s, float dt) {
        for (PhotoSnapshot p : s.photos) {
            p.mesh.ensureMask(p.marks);
            stepMesh(p.mesh, s.material, s.bounce, s.speed, dt);
        }
    }

    private static void stepMesh(SoftMesh m, int material, float bounce, float speed, float dt) {
        float restore, neighbor, damping, maxDisp;
        if (material == EditorView.MATERIAL_SOFT) { restore = 24f; neighbor = 54f; damping = 7.2f; maxDisp = 0.22f; }
        else if (material == EditorView.MATERIAL_SLIME) { restore = 10f; neighbor = 24f; damping = 4.8f; maxDisp = 0.30f; }
        else { restore = 48f; neighbor = 96f; damping = 11.5f; maxDisp = 0.17f; }
        damping *= 1.22f - 0.62f * clampStatic(bounce, 0f, 1f);
        float timeScale = 0.72f + 0.42f * clampStatic(speed, 0.45f, 2f);
        int substeps = 2;
        float h = Math.min(0.020f, dt * timeScale / substeps);
        for (int sub = 0; sub < substeps; sub++) {
            for (int gy = 0; gy <= MH; gy++) {
                for (int gx = 0; gx <= MW; gx++) {
                    int i = gy * VX + gx;
                    float w = m.weight[i];
                    if (w <= 0.0005f) {
                        m.vx[i] *= 0.70f; m.vy[i] *= 0.70f;
                        m.dx[i] *= 0.78f; m.dy[i] *= 0.78f;
                        continue;
                    }
                    float sumX = 0f, sumY = 0f; int c = 0;
                    if (gx > 0) { int n = i - 1; sumX += m.dx[n]; sumY += m.dy[n]; c++; }
                    if (gx < MW) { int n = i + 1; sumX += m.dx[n]; sumY += m.dy[n]; c++; }
                    if (gy > 0) { int n = i - VX; sumX += m.dx[n]; sumY += m.dy[n]; c++; }
                    if (gy < MH) { int n = i + VX; sumX += m.dx[n]; sumY += m.dy[n]; c++; }
                    float avgX = c == 0 ? 0f : sumX / c;
                    float avgY = c == 0 ? 0f : sumY / c;
                    float local = 0.30f + 0.70f * w;
                    float ax = (-restore * m.dx[i] + neighbor * (avgX - m.dx[i]) - damping * m.vx[i]) * local;
                    float ay = (-restore * m.dy[i] + neighbor * (avgY - m.dy[i]) - damping * m.vy[i]) * local;
                    m.vx[i] += ax * h;
                    m.vy[i] += ay * h;
                }
            }
            for (int i = 0; i < VERTS; i++) {
                m.dx[i] = clampStatic(m.dx[i] + m.vx[i] * h, -maxDisp, maxDisp);
                m.dy[i] = clampStatic(m.dy[i] + m.vy[i] * h, -maxDisp, maxDisp);
                m.vx[i] = clampStatic(m.vx[i], -3.2f, 3.2f);
                m.vy[i] = clampStatic(m.vy[i], -3.2f, 3.2f);
            }
        }
    }

    private Bitmap renderSingle(EditorSnapshot s, int index, float phase, int maxSide) {
        if (s.photos.isEmpty()) throw new IllegalStateException("No photos");
        index = Math.max(0, Math.min(index, s.photos.size() - 1));
        PhotoSnapshot p = s.photos.get(index);
        float aspect = p.bitmap.getWidth() / (float) p.bitmap.getHeight();
        int w, h;
        if (aspect >= 1f) { w = maxSide; h = Math.max(1, Math.round(maxSide / aspect)); }
        else { h = maxSide; w = Math.max(1, Math.round(maxSide * aspect)); }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.rgb(20, 20, 25));
        drawSnapshotItem(c, p, new RectF(0, 0, w, h), phase);
        return out;
    }

    private Bitmap renderGrid(EditorSnapshot s, float phase, int maxWidth) {
        int w = maxWidth;
        int h = Math.max(1, Math.round(w * s.viewAspect));
        if (h > 2400) { float k = 2400f / h; h = 2400; w = Math.max(1, Math.round(w * k)); }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.rgb(20, 20, 25));
        for (int i = 0; i < s.photos.size(); i++) drawSnapshotItem(c, s.photos.get(i), photoRect(i, s.photos.size(), w, h, s.photos.get(i).bitmap), phase);
        return out;
    }

    private static void drawSnapshotItem(Canvas c, PhotoSnapshot p, RectF rect, float phase) {
        p.mesh.ensureMask(p.marks);
        float[] verts = buildSnapshotVerts(p, rect, phase);
        c.drawBitmapMesh(p.bitmap, MW, MH, verts, 0, null, 0, null);
    }

    private static float[] buildSnapshotVerts(PhotoSnapshot p, RectF rect, float phase) {
        float[] verts = new float[VERTS * 2];
        float minSide = Math.min(rect.width(), rect.height());
        int k = 0;
        for (int gy = 0; gy <= MH; gy++) {
            float ny = gy / (float) MH;
            for (int gx = 0; gx <= MW; gx++) {
                float nx = gx / (float) MW;
                int idx = gy * VX + gx;
                float px = rect.left + nx * rect.width();
                float py = rect.top + ny * rect.height();
                float w = p.mesh.weight[idx];
                float ox = p.mesh.dx[idx] * rect.width() * w;
                float oy = p.mesh.dy[idx] * rect.height() * w;
                for (BrushMark m : p.marks) {
                    if (m.effect == EditorView.EFFECT_JELLIFY) continue;
                    float dx = (nx - m.x) * rect.width();
                    float dy = (ny - m.y) * rect.height();
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float radius = Math.max(2f, m.radius * minSide);
                    if (dist >= radius) continue;
                    float local = m.intensity * SoftMesh.falloff(dist / radius, m.feather);
                    float amp = minSide * 0.050f * local;
                    float d = Math.max(0.001f, dist), ux = dx / d, uy = dy / d;
                    float seed = m.x * 9.3f + m.y * 13.7f;
                    float radial = clampStatic(dist / radius, 0f, 1f);
                    switch (m.effect) {
                        case EditorView.EFFECT_PULSE: { float q = (float) Math.sin(phase + seed + radial * 4.2f); ox += ux * amp * q; oy += uy * amp * q; break; }
                        case EditorView.EFFECT_TWIST: { float q = (float) Math.sin(phase + seed + radial * 3.4f); ox += -uy * amp * q * 1.15f; oy += ux * amp * q * 1.15f; break; }
                        case EditorView.EFFECT_WAVE: ox += amp * .64f * (float) Math.sin(phase * 1.08f + ny * 15f + seed); oy += amp * .64f * (float) Math.sin(phase * 1.23f + nx * 14f + seed * .7f); break;
                        case EditorView.EFFECT_ELASTIC: { float q = (float) Math.sin(phase * 1.15f + seed + radial * 4.6f); ox += ux * amp * q * .55f + amp * .32f * (float) Math.sin(phase * 1.31f + seed); oy += uy * amp * q * .55f + amp * .32f * (float) Math.cos(phase * 1.17f + seed); break; }
                        default: ox += amp * .72f * (float) Math.sin(phase * 1.05f + seed + ny * 4f); oy += amp * .72f * (float) Math.cos(phase * 1.16f + seed + nx * 4f); break;
                    }
                }
                verts[k++] = px + clampStatic(ox, -rect.width() * .28f, rect.width() * .28f);
                verts[k++] = py + clampStatic(oy, -rect.height() * .28f, rect.height() * .28f);
            }
        }
        return verts;
    }

    private static RectF photoRect(int index, int count, int w, int h, Bitmap bitmap) {
        int cols = count <= 1 ? 1 : count <= 4 ? 2 : count <= 9 ? 3 : 4;
        int rows = (int) Math.ceil(count / (double) cols);
        float gap = Math.max(4f, Math.min(w, h) * .008f);
        float cellW = w / (float) cols, cellH = h / (float) rows;
        int col = index % cols, row = index / cols;
        return fitRect(new RectF(col * cellW + gap, row * cellH + gap, (col + 1) * cellW - gap, (row + 1) * cellH - gap), bitmap);
    }

    private static RectF fitRect(RectF cell, Bitmap bitmap) {
        float ia = bitmap.getWidth() / (float) bitmap.getHeight();
        float ca = cell.width() / cell.height();
        if (ia > ca) { float hh = cell.width() / ia, cy = cell.centerY(); return new RectF(cell.left, cy - hh / 2f, cell.right, cy + hh / 2f); }
        float ww = cell.height() * ia, cx = cell.centerX(); return new RectF(cx - ww / 2f, cell.top, cx + ww / 2f, cell.bottom);
    }

    private static float clampStatic(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private final class EditorView extends View {
        static final int TOOL_PAINT = 0, TOOL_ERASE = 1, TOOL_OVERRIDE = 2, TOOL_TOUCH = 3;
        static final int EFFECT_WOBBLE = 0, EFFECT_PULSE = 1, EFFECT_TWIST = 2, EFFECT_WAVE = 3, EFFECT_ELASTIC = 4, EFFECT_JELLIFY = 5;
        static final int MATERIAL_HARD = 0, MATERIAL_SOFT = 1, MATERIAL_SLIME = 2;
        private static final int HISTORY_LIMIT = 36;

        private final ArrayList<PhotoItem> photos = new ArrayList<>();
        private final ArrayList<HistoryState> undo = new ArrayList<>();
        private final ArrayList<HistoryState> redo = new ArrayList<>();
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint heatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selected = -1, activeTile = -1, tool = TOOL_PAINT, effect = EFFECT_JELLIFY, material = MATERIAL_HARD;
        private boolean playing = true, linkAll, heatmap = true, focus;
        private float brush = .124f, intensity = .78f, feather = .82f, bounce = .62f, speed = 1f;
        private float cursorX, cursorY, lastStampX = -10f, lastStampY = -10f, prevTouchX, prevTouchY, touchTravel;
        private RectF activeRect;
        private boolean cursorVisible, strokeActive;
        private long startMs = SystemClock.uptimeMillis(), lastFrameMs = startMs, prevTouchMs;
        private float tiltX, tiltY;

        EditorView() {
            super(MainActivityV14.this);
            setBackgroundColor(Color.rgb(20, 20, 25));
            border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(dp(3));
            cursorPaint.setStyle(Paint.Style.STROKE); cursorPaint.setStrokeWidth(dp(2)); cursorPaint.setColor(Color.WHITE);
        }

        int getPhotoCount() { synchronized (photos) { return photos.size(); } }
        boolean isPlaying() { return playing; }
        boolean isLinkAll() { return linkAll; }
        boolean isHeatmap() { return heatmap; }
        boolean isFocusSelected() { return focus; }
        void setLinkAll(boolean v) { linkAll = v; invalidate(); }
        void setHeatmap(boolean v) { heatmap = v; invalidate(); }
        void setFocusSelected(boolean v) { focus = !photos.isEmpty() && v; invalidate(); }
        void setBrushProgress(int v) { brush = .025f + clamp(v / 100f, .01f, 1f) * .275f; invalidate(); }
        void setIntensityProgress(int v) { intensity = clamp(v / 100f, .03f, 1f); invalidate(); }
        void setFeatherProgress(int v) { feather = clamp(.35f + v / 100f * .65f, .35f, 1f); invalidate(); }
        void setBounceProgress(int v) { bounce = clamp(v / 100f, .05f, 1f); }
        void setSpeedProgress(int v) { speed = .45f + clamp(v / 100f, 0f, 1f) * 1.55f; }

        void addPhoto(Bitmap b) { synchronized (photos) { if (photos.size() >= MAX_PHOTOS) return; photos.add(new PhotoItem(b)); selected = photos.size() - 1; } undo.clear(); redo.clear(); invalidate(); }

        void replaceProject(LoadedProject p) {
            synchronized (photos) {
                for (PhotoItem old : photos) if (!old.bitmap.isRecycled()) old.bitmap.recycle();
                photos.clear(); photos.addAll(p.photos);
                selected = photos.isEmpty() ? -1 : Math.max(0, Math.min(p.selected, photos.size() - 1));
                linkAll = p.linkAll; heatmap = p.heatmap; focus = p.focus && !photos.isEmpty();
                tool = clampInt(p.tool, TOOL_PAINT, TOOL_TOUCH); effect = clampInt(p.effect, EFFECT_WOBBLE, EFFECT_JELLIFY); material = clampInt(p.material, MATERIAL_HARD, MATERIAL_SLIME);
                brush = clamp(p.brush, .025f, .30f); intensity = clamp(p.intensity, .03f, 1f); feather = clamp(p.feather, .35f, 1f); bounce = clamp(p.bounce, .05f, 1f); speed = clamp(p.speed, .45f, 2f);
                for (PhotoItem item : photos) { item.mesh.dirty = true; item.ensureMask(); item.mesh.resetMotion(); }
            }
            undo.clear(); redo.clear(); invalidate();
        }

        void saveProject(File dir) throws Exception {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create project folder");
            File[] old = dir.listFiles(); if (old != null) for (File f : old) if (f.getName().startsWith("photo_") && f.getName().endsWith(".png")) f.delete();
            JSONObject root = new JSONObject(); JSONArray photoArray = new JSONArray();
            synchronized (photos) {
                root.put("version", 5); root.put("selected", selected); root.put("linkAll", linkAll); root.put("heatmap", heatmap); root.put("focusSelected", focus);
                root.put("tool", tool); root.put("effect", effect); root.put("material", material); root.put("brushRadius", brush); root.put("intensity", intensity); root.put("feather", feather); root.put("bounce", bounce); root.put("speed", speed);
                for (int i = 0; i < photos.size(); i++) {
                    PhotoItem item = photos.get(i); String file = String.format(Locale.US, "photo_%02d.png", i);
                    try (OutputStream out = new FileOutputStream(new File(dir, file))) { if (!item.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Photo save failed"); }
                    JSONObject pj = new JSONObject(); pj.put("file", file); JSONArray marks = new JSONArray();
                    for (BrushMark m : item.marks) { JSONObject j = new JSONObject(); j.put("x", m.x); j.put("y", m.y); j.put("radius", m.radius); j.put("intensity", m.intensity); j.put("feather", m.feather); j.put("effect", m.effect); marks.put(j); }
                    pj.put("marks", marks); photoArray.put(pj);
                }
            }
            root.put("photos", photoArray);
            File temp = new File(dir, "project.json.tmp"); try (OutputStream out = new FileOutputStream(temp)) { out.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            File dest = new File(dir, "project.json"); if (dest.exists() && !dest.delete()) throw new IOException("Could not replace project"); if (!temp.renameTo(dest)) throw new IOException("Could not finalize project");
        }

        String cycleTool() { tool = (tool + 1) % 4; invalidate(); return toolName(); }
        String toolName() { return tool == TOOL_ERASE ? "Erase" : tool == TOOL_OVERRIDE ? "Override" : tool == TOOL_TOUCH ? "Touch" : "Paint"; }
        String cycleEffect() { effect = (effect + 1) % 6; invalidate(); return effectName(); }
        String effectName() { return MainActivityV14.effectName(effect); }
        String cycleMaterial() { material = (material + 1) % 3; return materialName(); }
        String materialName() { return MainActivityV14.materialName(material); }

        void togglePlaying() { playing = !playing; if (playing) { startMs = SystemClock.uptimeMillis(); lastFrameMs = startMs; postInvalidateOnAnimation(); } else invalidate(); }
        float phase() { return playing ? (SystemClock.uptimeMillis() - startMs) / 430f * speed : 0f; }

        void setTilt(float x, float y) {
            float nx = clamp(-x / 9.81f, -1f, 1f), ny = clamp(y / 9.81f, -1f, 1f);
            float ix = nx - tiltX, iy = ny - tiltY; tiltX = nx; tiltY = ny;
            if (Math.abs(ix) + Math.abs(iy) > .020f) synchronized (photos) {
                for (PhotoItem p : photos) { p.ensureMask(); for (int i = 0; i < VERTS; i++) { float w = p.mesh.weight[i]; if (w > .01f) { p.mesh.vx[i] += ix * .42f * w; p.mesh.vy[i] += iy * .42f * w; } } }
            }
            if (playing) postInvalidateOnAnimation();
        }

        void settleMotion() { synchronized (photos) { for (PhotoItem p : photos) p.mesh.resetMotion(); } invalidate(); }

        void jiggleCurrent() {
            synchronized (photos) {
                if (photos.isEmpty()) return;
                if (linkAll) for (PhotoItem p : photos) jiggle(p); else jiggle(photos.get(selected < 0 ? 0 : selected));
            }
            if (!playing) { playing = true; startMs = SystemClock.uptimeMillis(); lastFrameMs = startMs; playButton.setText("Pause"); }
            postInvalidateOnAnimation();
        }

        private void jiggle(PhotoItem p) { p.ensureMask(); PhotoSnapshot snap = new PhotoSnapshot(p.bitmap, copyMarks(p.marks), p.mesh.copy()); ensureVisibleMotion(snap, bounce); p.mesh.copyMotionFrom(snap.mesh); }

        void smartJellify() {
            synchronized (photos) {
                if (photos.isEmpty()) return; pushUndoLocked();
                if (linkAll) for (PhotoItem p : photos) smartOne(p); else smartOne(photos.get(selected < 0 ? 0 : selected));
            }
            invalidate();
        }

        private void smartOne(PhotoItem p) {
            Bitmap b = p.bitmap; float[] best = {-1,-1,-1}; float[] bx = {.5f,.35f,.65f}, by = {.5f,.45f,.55f};
            for (int gy = 1; gy <= 7; gy++) for (int gx = 1; gx <= 7; gx++) {
                float x = gx / 8f, y = gy / 8f; int px = Math.min(b.getWidth()-2, Math.max(1, Math.round(x*(b.getWidth()-1)))); int py = Math.min(b.getHeight()-2, Math.max(1, Math.round(y*(b.getHeight()-1))));
                float score = Math.abs(luma(b.getPixel(px+1,py))-luma(b.getPixel(px-1,py))) + Math.abs(luma(b.getPixel(px,py+1))-luma(b.getPixel(px,py-1)));
                int slot = best[1] < best[0] ? 1 : 0; if (best[2] < best[slot]) slot = 2; if (score > best[slot]) { best[slot]=score; bx[slot]=x; by[slot]=y; }
            }
            for (int i=0;i<3;i++) p.marks.add(new BrushMark(bx[i], by[i], Math.max(brush,.17f), Math.max(.52f,intensity*(.78f+i*.07f)), feather, EFFECT_JELLIFY));
            p.mesh.dirty = true; p.ensureMask();
        }

        private float luma(int c) { return Color.red(c)*.2126f + Color.green(c)*.7152f + Color.blue(c)*.0722f; }

        void clearCurrent() { synchronized (photos) { if (photos.isEmpty()) return; pushUndoLocked(); if (linkAll) for (PhotoItem p:photos){p.marks.clear();p.mesh.clear();} else {PhotoItem p=photos.get(selected<0?0:selected);p.marks.clear();p.mesh.clear();} } invalidate(); }

        boolean undo() { synchronized (photos) { if (undo.isEmpty()) return false; redo.add(snapshotHistory()); restoreHistory(undo.remove(undo.size()-1)); } requestAutosave(); invalidate(); return true; }
        boolean redo() { synchronized (photos) { if (redo.isEmpty()) return false; undo.add(snapshotHistory()); restoreHistory(redo.remove(redo.size()-1)); } requestAutosave(); invalidate(); return true; }
        private void pushUndoLocked() { undo.add(snapshotHistory()); while (undo.size()>HISTORY_LIMIT) undo.remove(0); redo.clear(); }
        private HistoryState snapshotHistory() { HistoryState h=new HistoryState(); h.selected=selected; for(PhotoItem p:photos) h.marks.add(copyMarks(p.marks)); return h; }
        private void restoreHistory(HistoryState h) { selected=photos.isEmpty()?-1:Math.max(0,Math.min(h.selected,photos.size()-1)); for(int i=0;i<photos.size();i++){PhotoItem p=photos.get(i);p.marks.clear();if(i<h.marks.size())p.marks.addAll(copyMarks(h.marks.get(i)));p.mesh.dirty=true;p.ensureMask();p.mesh.resetMotion();} }

        EditorSnapshot snapshot() {
            synchronized (photos) {
                EditorSnapshot s = new EditorSnapshot(); s.selected = photos.isEmpty()?0:Math.max(0,Math.min(selected,photos.size()-1)); s.material=material; s.bounce=bounce; s.speed=speed; s.phase=phase(); s.viewAspect=getWidth()>0?Math.max(.35f,Math.min(2.4f,getHeight()/(float)getWidth())):1f;
                for(PhotoItem p:photos){p.ensureMask();s.photos.add(new PhotoSnapshot(p.bitmap,copyMarks(p.marks),p.mesh.copy()));}
                return s;
            }
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); long now=SystemClock.uptimeMillis(); float dt=clamp((now-lastFrameMs)/1000f,0f,.040f); lastFrameMs=now;
            if(playing&&dt>0) synchronized(photos){for(PhotoItem p:photos){p.ensureMask();stepMesh(p.mesh,material,bounce,speed,dt);}}
            drawScene(c,getWidth(),getHeight(),phase(),true,heatmap,focus); if(playing)postInvalidateOnAnimation();
        }

        private void drawScene(Canvas c,int w,int h,float ph,boolean selection,boolean heat,boolean useFocus){
            c.drawColor(Color.rgb(20,20,25)); synchronized(photos){if(photos.isEmpty()){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.rgb(178,178,194));p.setTextSize(Math.max(24f,w*.035f));p.setTextAlign(Paint.Align.CENTER);c.drawText("Add photo → Paint Jellify → Touch / JIGGLE",w/2f,h/2f,p);return;}
                if(useFocus&&focus&&selected>=0&&selected<photos.size()){RectF r=fitRect(new RectF(dp(6),dp(6),w-dp(6),h-dp(6)),photos.get(selected).bitmap);drawLiveItem(c,photos.get(selected),r,ph,selection,heat);}else for(int i=0;i<photos.size();i++){RectF r=photoRect(i,photos.size(),w,h,photos.get(i).bitmap);drawLiveItem(c,photos.get(i),r,ph,selection&&i==selected,heat&&i==selected);} }
            if(cursorVisible&&activeRect!=null&&tool!=TOOL_TOUCH){float rp=brush*Math.min(activeRect.width(),activeRect.height());cursorPaint.setColor(tool==TOOL_ERASE?Color.WHITE:heatColor(intensity));c.drawCircle(cursorX,cursorY,rp,cursorPaint);}
        }

        private void drawLiveItem(Canvas c,PhotoItem p,RectF r,float ph,boolean selectedTile,boolean showHeat){p.ensureMask();PhotoSnapshot ps=new PhotoSnapshot(p.bitmap,p.marks,p.mesh);c.drawBitmapMesh(p.bitmap,MW,MH,buildSnapshotVerts(ps,r,ph),0,null,0,null);if(showHeat)drawHeat(c,p,r);if(selectedTile){border.setColor(Color.rgb(135,225,255));c.drawRoundRect(r,dp(6),dp(6),border);}}

        private void drawHeat(Canvas c,PhotoItem p,RectF r){float min=Math.min(r.width(),r.height());for(BrushMark m:p.marks){float cx=r.left+m.x*r.width(),cy=r.top+m.y*r.height(),rp=Math.max(dp(3),m.radius*min);RadialGradient g=new RadialGradient(cx,cy,rp,new int[]{withAlpha(heatColor(m.intensity),118),withAlpha(heatColor(m.intensity*.72f),88),withAlpha(heatColor(m.intensity*.42f),62),withAlpha(heatColor(Math.max(.06f,m.intensity*.16f)),38),Color.TRANSPARENT},new float[]{0,.30f,.56f,.82f,1},Shader.TileMode.CLAMP);heatPaint.setShader(g);c.drawCircle(cx,cy,rp,heatPaint);heatPaint.setShader(null);}}
        private int heatColor(float v){v=clamp(v,0,1);if(v<.34f){float t=v/.34f;return Color.rgb(Math.round(70+185*t),230,55);}if(v<.67f){float t=(v-.34f)/.33f;return Color.rgb(255,Math.round(225-80*t),35);}float t=(v-.67f)/.33f;return Color.rgb(255,Math.round(145-120*t),Math.round(25-18*t));}
        private int withAlpha(int c,int a){return Color.argb(a,Color.red(c),Color.green(c),Color.blue(c));}

        @Override public boolean onTouchEvent(MotionEvent e){if(photos.isEmpty())return true;float x=e.getX(),y=e.getY();int a=e.getActionMasked();
            if(a==MotionEvent.ACTION_DOWN){activeTile=findTile(x,y);if(activeTile<0)return true;selected=activeTile;activeRect=currentRect(activeTile);cursorX=x;cursorY=y;cursorVisible=true;lastStampX=lastStampY=-10f;touchTravel=0;prevTouchX=x;prevTouchY=y;prevTouchMs=SystemClock.uptimeMillis();if(tool!=TOOL_TOUCH){synchronized(photos){pushUndoLocked();}strokeActive=true;stampScreen(x,y,true);}else{strokeActive=false;}invalidate();return true;}
            if(a==MotionEvent.ACTION_MOVE&&activeTile>=0&&activeRect!=null){cursorX=x;cursorY=y;cursorVisible=true;if(tool==TOOL_TOUCH){long now=SystemClock.uptimeMillis();float dt=Math.max(.008f,(now-prevTouchMs)/1000f);float dx=(x-prevTouchX)/Math.max(1f,activeRect.width()),dy=(y-prevTouchY)/Math.max(1f,activeRect.height());touchTravel+=(float)Math.sqrt(dx*dx+dy*dy);dragMeshAt(x,y,dx,dy,dt);prevTouchX=x;prevTouchY=y;prevTouchMs=now;}else if(strokeActive)stampScreen(x,y,false);invalidate();return true;}
            if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){if(tool==TOOL_TOUCH&&touchTravel<.008f)pokeMeshAt(x,y,.55f+bounce*.65f);boolean edited=strokeActive;cursorVisible=false;strokeActive=false;activeTile=-1;activeRect=null;lastStampX=lastStampY=-10f;if(edited)requestAutosave();invalidate();return true;}return true;}

        private RectF currentRect(int idx){synchronized(photos){if(focus&&selected>=0&&selected<photos.size())return fitRect(new RectF(dp(6),dp(6),getWidth()-dp(6),getHeight()-dp(6)),photos.get(selected).bitmap);return photoRect(idx,photos.size(),getWidth(),getHeight(),photos.get(idx).bitmap);}}
        private int findTile(float x,float y){synchronized(photos){if(focus&&selected>=0&&selected<photos.size()){RectF r=currentRect(selected);return r.contains(x,y)?selected:-1;}for(int i=0;i<photos.size();i++)if(photoRect(i,photos.size(),getWidth(),getHeight(),photos.get(i).bitmap).contains(x,y))return i;}return -1;}

        private void stampScreen(float sx,float sy,boolean force){if(activeRect==null||!activeRect.contains(sx,sy))return;float nx=clamp((sx-activeRect.left)/activeRect.width(),0,1),ny=clamp((sy-activeRect.top)/activeRect.height(),0,1);if(!force&&lastStampX>-1){float dx=nx-lastStampX,dy=ny-lastStampY,dist=(float)Math.sqrt(dx*dx+dy*dy),spacing=Math.max(.004f,brush*.22f);if(dist<spacing)return;int steps=Math.min(24,Math.max(1,(int)Math.ceil(dist/spacing)));for(int i=1;i<=steps;i++){float t=i/(float)steps;stampNormalized(lastStampX+dx*t,lastStampY+dy*t);}}else stampNormalized(nx,ny);lastStampX=nx;lastStampY=ny;}
        private void stampNormalized(float nx,float ny){synchronized(photos){if(linkAll)for(PhotoItem p:photos)applyTool(p,nx,ny);else if(activeTile>=0&&activeTile<photos.size())applyTool(photos.get(activeTile),nx,ny);}}
        private void applyTool(PhotoItem p,float nx,float ny){if(tool==TOOL_ERASE){eraseAt(p,nx,ny);return;}if(tool==TOOL_OVERRIDE)eraseOverlap(p,nx,ny);BrushMark m=new BrushMark(nx,ny,brush,intensity,feather,effect);p.marks.add(m);if(p.marks.size()>3200)p.marks.remove(0);if(effect==EFFECT_JELLIFY)p.mesh.applyMark(m);}
        private void eraseAt(PhotoItem p,float nx,float ny){for(int i=p.marks.size()-1;i>=0;i--){BrushMark m=p.marks.get(i);float dx=m.x-nx,dy=m.y-ny;if(Math.sqrt(dx*dx+dy*dy)<=brush*.78f+m.radius*.30f)p.marks.remove(i);}p.mesh.dirty=true;}
        private void eraseOverlap(PhotoItem p,float nx,float ny){for(int i=p.marks.size()-1;i>=0;i--){BrushMark m=p.marks.get(i);float dx=m.x-nx,dy=m.y-ny;if(Math.sqrt(dx*dx+dy*dy)<=brush*.82f+m.radius*.45f)p.marks.remove(i);}p.mesh.dirty=true;}

        private void dragMeshAt(float sx,float sy,float dx,float dy,float dt){if(activeRect==null||!activeRect.contains(sx,sy))return;float nx=clamp((sx-activeRect.left)/activeRect.width(),0,1),ny=clamp((sy-activeRect.top)/activeRect.height(),0,1);synchronized(photos){if(linkAll)for(PhotoItem p:photos)dragOne(p,nx,ny,dx,dy,dt);else if(activeTile>=0&&activeTile<photos.size())dragOne(photos.get(activeTile),nx,ny,dx,dy,dt);}}
        private void dragOne(PhotoItem p,float nx,float ny,float dx,float dy,float dt){p.ensureMask();float rad=Math.max(.055f,brush*1.05f),gain=material==MATERIAL_HARD?.92f:material==MATERIAL_SOFT?1.05f:1.18f;for(int gy=0;gy<=MH;gy++){float py=gy/(float)MH;for(int gx=0;gx<=MW;gx++){int i=gy*VX+gx;float w=p.mesh.weight[i];if(w<.01f)continue;float px=gx/(float)MW,ddx=px-nx,ddy=py-ny,d=(float)Math.sqrt(ddx*ddx+ddy*ddy);if(d>=rad)continue;float f=(1f-d/rad);f=f*f*w;p.mesh.dx[i]=clamp(p.mesh.dx[i]+dx*f*gain,-.32f,.32f);p.mesh.dy[i]=clamp(p.mesh.dy[i]+dy*f*gain,-.32f,.32f);float follow=.26f+bounce*.26f;p.mesh.vx[i]=p.mesh.vx[i]*.35f+(dx/dt)*f*follow;p.mesh.vy[i]=p.mesh.vy[i]*.35f+(dy/dt)*f*follow;}}}
        private void pokeMeshAt(float sx,float sy,float power){if(activeRect==null||!activeRect.contains(sx,sy))return;float nx=clamp((sx-activeRect.left)/activeRect.width(),0,1),ny=clamp((sy-activeRect.top)/activeRect.height(),0,1);synchronized(photos){if(linkAll)for(PhotoItem p:photos)pokeOne(p,nx,ny,power);else if(activeTile>=0&&activeTile<photos.size())pokeOne(photos.get(activeTile),nx,ny,power);}}
        private void pokeOne(PhotoItem p,float nx,float ny,float power){p.ensureMask();float rad=Math.max(.07f,brush*1.25f);for(int gy=0;gy<=MH;gy++){float py=gy/(float)MH;for(int gx=0;gx<=MW;gx++){int i=gy*VX+gx,widx=i;float w=p.mesh.weight[widx];if(w<.01f)continue;float px=gx/(float)MW,dx=px-nx,dy=py-ny,d=(float)Math.sqrt(dx*dx+dy*dy);if(d>=rad)continue;float f=(1-d/rad)*w;if(d<.002f){dx=.1f;dy=.1f;d=.1414f;}p.mesh.vx[i]+=dx/d*power*f*.22f;p.mesh.vy[i]+=dy/d*power*f*.22f;}}}

        private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
        private int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    }

    private static ArrayList<BrushMark> copyMarks(ArrayList<BrushMark> src){ArrayList<BrushMark> out=new ArrayList<>(src.size());for(BrushMark m:src)out.add(m.copy());return out;}
    private static String effectName(int e){switch(e){case 0:return "Wobble";case 1:return "Pulse";case 2:return "Twist";case 3:return "Wave";case 4:return "Elastic";default:return "Jellify";}}
    private static String materialName(int m){return m==1?"Soft Jello":m==2?"Slime":"Hard Jello";}

    private static final class PhotoItem {
        final Bitmap bitmap; final ArrayList<BrushMark> marks=new ArrayList<>(); final SoftMesh mesh=new SoftMesh();
        PhotoItem(Bitmap b){bitmap=b;}
        void ensureMask(){mesh.ensureMask(marks);}
    }

    private static final class SoftMesh {
        final float[] dx=new float[VERTS],dy=new float[VERTS],vx=new float[VERTS],vy=new float[VERTS],weight=new float[VERTS];
        boolean dirty=true;
        void clear(){Arrays.fill(weight,0);resetMotion();dirty=false;}
        void resetMotion(){Arrays.fill(dx,0);Arrays.fill(dy,0);Arrays.fill(vx,0);Arrays.fill(vy,0);}
        void copyMotionFrom(SoftMesh o){System.arraycopy(o.dx,0,dx,0,VERTS);System.arraycopy(o.dy,0,dy,0,VERTS);System.arraycopy(o.vx,0,vx,0,VERTS);System.arraycopy(o.vy,0,vy,0,VERTS);}
        SoftMesh copy(){SoftMesh n=new SoftMesh();System.arraycopy(dx,0,n.dx,0,VERTS);System.arraycopy(dy,0,n.dy,0,VERTS);System.arraycopy(vx,0,n.vx,0,VERTS);System.arraycopy(vy,0,n.vy,0,VERTS);System.arraycopy(weight,0,n.weight,0,VERTS);n.dirty=dirty;return n;}
        void ensureMask(ArrayList<BrushMark> marks){if(!dirty)return;Arrays.fill(weight,0);for(BrushMark m:marks)if(m.effect==EditorView.EFFECT_JELLIFY)applyMark(m);dirty=false;}
        void applyMark(BrushMark m){int minX=Math.max(0,(int)Math.floor((m.x-m.radius)*MW)),maxX=Math.min(MW,(int)Math.ceil((m.x+m.radius)*MW));int minY=Math.max(0,(int)Math.floor((m.y-m.radius)*MH)),maxY=Math.min(MH,(int)Math.ceil((m.y+m.radius)*MH));for(int gy=minY;gy<=maxY;gy++){float y=gy/(float)MH;for(int gx=minX;gx<=maxX;gx++){float x=gx/(float)MW,ddx=x-m.x,ddy=y-m.y,d=(float)Math.sqrt(ddx*ddx+ddy*ddy);if(d>=m.radius)continue;float w=m.intensity*falloff(d/Math.max(.001f,m.radius),m.feather);int i=gy*VX+gx;if(w>weight[i])weight[i]=w;}}}
        static float falloff(float d,float f){d=clampStatic(d,0,1);f=clampStatic(f,.35f,1f);float core=.58f-.43f*f;if(d<=core)return 1f;float t=clampStatic((d-core)/Math.max(.001f,1-core),0,1),inv=1-t;return inv*inv*(3-2*inv);}
    }

    private static final class BrushMark {
        final float x,y,radius,intensity,feather; final int effect;
        BrushMark(float x,float y,float r,float i,float f,int e){this.x=x;this.y=y;radius=r;intensity=i;feather=f;effect=e;}
        BrushMark copy(){return new BrushMark(x,y,radius,intensity,feather,effect);}
    }

    private static final class HistoryState { int selected; final ArrayList<ArrayList<BrushMark>> marks=new ArrayList<>(); }
    private static final class LoadedProject {
        final ArrayList<PhotoItem> photos=new ArrayList<>(); int selected,tool,effect=EditorView.EFFECT_JELLIFY,material=EditorView.MATERIAL_HARD; boolean linkAll,heatmap=true,focus; float brush=.124f,intensity=.78f,feather=.82f,bounce=.62f,speed=1f;
    }

    private static final class PhotoSnapshot {
        final Bitmap bitmap; final ArrayList<BrushMark> marks; final SoftMesh mesh;
        PhotoSnapshot(Bitmap b,ArrayList<BrushMark> m,SoftMesh s){bitmap=b;marks=m;mesh=s;}
        PhotoSnapshot deepCopy(){return new PhotoSnapshot(bitmap,copyMarks(marks),mesh.copy());}
    }
    private static final class EditorSnapshot {
        final ArrayList<PhotoSnapshot> photos=new ArrayList<>(); int selected,material; float bounce,speed,phase,viewAspect=1f;
        EditorSnapshot deepCopy(){EditorSnapshot n=new EditorSnapshot();n.selected=selected;n.material=material;n.bounce=bounce;n.speed=speed;n.phase=phase;n.viewAspect=viewAspect;for(PhotoSnapshot p:photos)n.photos.add(p.deepCopy());return n;}
    }
}
