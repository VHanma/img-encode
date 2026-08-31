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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivityV13 extends Activity implements SensorEventListener {
    private static final int PICK_IMAGES = 41;
    private static final int SAVE_AS_PNG = 42;
    private static final int MAX_PHOTOS = 12;

    private EditorView editor;
    private TextView statusView;
    private Button playButton;
    private Button linkButton;
    private Button toolButton;
    private Button effectButton;
    private Button heatButton;
    private Button materialButton;
    private Button focusButton;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private volatile Uri lastGalleryUri;
    private volatile String lastGalleryMime = "image/png";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 9, 13));
        getWindow().setNavigationBarColor(Color.rgb(9, 9, 13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(9, 9, 13));

        TextView title = new TextView(this);
        title.setText("JelliForge v1.3 • Rival 3");
        title.setTextSize(24f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(14), dp(9), dp(14), dp(2));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setText("Hard Jello is default. Paint a region, switch Tool to Touch, then drag/flick it.");
        statusView.setTextSize(13f);
        statusView.setTextColor(Color.rgb(197, 197, 214));
        statusView.setPadding(dp(14), 0, dp(14), dp(5));
        root.addView(statusView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new EditorView();
        root.addView(editor, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout saveRow = horizontalRow();
        Button add = button("+ Photos");
        add.setOnClickListener(v -> pickPhotos());
        saveRow.addView(add);

        Button open = button("Open Saved");
        open.setOnClickListener(v -> loadProject());
        saveRow.addView(open);

        Button saveProject = button("SAVE PROJECT");
        saveProject.setOnClickListener(v -> saveProject(true));
        saveRow.addView(saveProject);

        Button savePhoto = button("SAVE PHOTO");
        savePhoto.setOnClickListener(v -> saveSelectedToGallery());
        saveRow.addView(savePhoto);

        Button saveGrid = button("Save Grid");
        saveGrid.setOnClickListener(v -> saveGridToGallery());
        saveRow.addView(saveGrid);

        Button saveAs = button("Save As…");
        saveAs.setOnClickListener(v -> startSaveAs());
        saveRow.addView(saveAs);

        Button openLast = button("Open Last Save");
        openLast.setOnClickListener(v -> openLastGalleryItem());
        saveRow.addView(openLast);
        root.addView(scroller(saveRow));

        LinearLayout exportRow = horizontalRow();
        Button selectedGif = button("Photo GIF");
        selectedGif.setOnClickListener(v -> exportSelectedGif());
        exportRow.addView(selectedGif);

        Button gridGif = button("Grid GIF");
        gridGif.setOnClickListener(v -> exportGridGif());
        exportRow.addView(gridGif);

        Button batch = button("Batch GIFs");
        batch.setOnClickListener(v -> exportBatchGifs());
        exportRow.addView(batch);

        playButton = button("Pause");
        playButton.setOnClickListener(v -> {
            editor.togglePlaying();
            playButton.setText(editor.isPlaying() ? "Pause" : "Play");
        });
        exportRow.addView(playButton);
        root.addView(scroller(exportRow));

        LinearLayout toolRow = horizontalRow();
        toolButton = button("Tool: Paint");
        toolButton.setOnClickListener(v -> {
            String name = editor.cycleTool();
            toolButton.setText("Tool: " + name);
            status(name.equals("Touch") ? "Touch mode: grab, drag and flick painted Jellify regions." : "Tool: " + name + ".");
        });
        toolRow.addView(toolButton);

        effectButton = button("Effect: Jellify");
        effectButton.setOnClickListener(v -> {
            String name = editor.cycleEffect();
            effectButton.setText("Effect: " + name);
            status("New marks use " + name + ". Existing marks keep their own effect.");
        });
        toolRow.addView(effectButton);

        materialButton = button("Material: Hard Jello");
        materialButton.setOnClickListener(v -> {
            String name = editor.cycleMaterial();
            materialButton.setText("Material: " + name);
            status(name + " changes spring stiffness, recoil and settling.");
        });
        toolRow.addView(materialButton);

        focusButton = button("Focus Photo");
        focusButton.setOnClickListener(v -> {
            editor.setFocusSelected(!editor.isFocusSelected());
            focusButton.setText(editor.isFocusSelected() ? "Show Grid" : "Focus Photo");
            status(editor.isFocusSelected() ? "Focus mode: selected photo fills the editor for precise painting." : "Grid mode restored.");
        });
        toolRow.addView(focusButton);

        heatButton = button("Heatmap: ON");
        heatButton.setOnClickListener(v -> {
            editor.setHeatmap(!editor.isHeatmap());
            heatButton.setText(editor.isHeatmap() ? "Heatmap: ON" : "Heatmap: OFF");
        });
        toolRow.addView(heatButton);

        linkButton = button("Link All: OFF");
        linkButton.setOnClickListener(v -> {
            editor.setLinkAll(!editor.isLinkAll());
            linkButton.setText(editor.isLinkAll() ? "Link All: ON" : "Link All: OFF");
            status(editor.isLinkAll() ? "New brush strokes mirror across all photos." : "Editing selected photo only.");
        });
        toolRow.addView(linkButton);
        root.addView(scroller(toolRow));

        LinearLayout editRow = horizontalRow();
        Button undo = button("Undo");
        undo.setOnClickListener(v -> status(editor.undo() ? "Undo." : "Nothing to undo."));
        editRow.addView(undo);

        Button redo = button("Redo");
        redo.setOnClickListener(v -> status(editor.redo() ? "Redo." : "Nothing to redo."));
        editRow.addView(redo);

        Button smart = button("Smart Jellify");
        smart.setOnClickListener(v -> {
            editor.smartMotion();
            requestAutosave();
            status("Smart Jellify seeded coherent gelatin regions from strong image detail.");
        });
        editRow.addView(smart);

        Button clear = button("Clear Effects");
        clear.setOnClickListener(v -> {
            editor.clearCurrent();
            requestAutosave();
            status(editor.isLinkAll() ? "Effects cleared from all photos." : "Effects cleared from selected photo.");
        });
        editRow.addView(clear);

        Button resetMotion = button("Settle Jelly");
        resetMotion.setOnClickListener(v -> {
            editor.settleMotion();
            status("Transient jelly motion settled without deleting painted regions.");
        });
        editRow.addView(resetMotion);
        root.addView(scroller(editRow));

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setPadding(dp(8), 0, dp(8), dp(6));
        addSlider(sliderRow, "Brush", 36, value -> editor.setBrushProgress(value));
        addSlider(sliderRow, "Intensity", 78, value -> editor.setIntensityProgress(value));
        addSlider(sliderRow, "Feather", 82, value -> editor.setFeatherProgress(value));
        addSlider(sliderRow, "Bounce", 68, value -> editor.setBounceProgress(value));
        addSlider(sliderRow, "Speed", 50, value -> editor.setSpeedProgress(value));
        root.addView(scroller(sliderRow));

        setContentView(root);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(6), dp(2), dp(6), dp(2));
        return row;
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(45));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private interface SliderSetter { void set(int value); }

    private void addSlider(LinearLayout row, String name, int initial, SliderSetter setter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(5), 0, dp(5), 0);
        TextView label = new TextView(this);
        label.setText(name + ": " + initial + "%");
        label.setTextColor(Color.rgb(222, 222, 232));
        label.setTextSize(11f);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(initial);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(150), dp(38)));
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

    private void startSaveAs() {
        if (editor.getPhotoCount() == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_TITLE, "JelliForge_Photo_" + System.currentTimeMillis() + ".png");
        startActivityForResult(i, SAVE_AS_PNG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == SAVE_AS_PNG) {
            Uri target = data.getData();
            if (target == null) return;
            status("Writing selected photo to chosen location…");
            worker.execute(() -> {
                try {
                    Bitmap frame = editor.renderSelected(editor.currentPhase(), 2400);
                    try (OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                        if (out == null || !frame.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG write failed");
                        out.flush();
                    } finally {
                        frame.recycle();
                    }
                    runOnUiThread(() -> {
                        status("Saved to the location you chose.");
                        Toast.makeText(this, "File saved", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> status("Save As failed: " + e.getMessage()));
                }
            });
            return;
        }

        if (requestCode != PICK_IMAGES) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount() && uris.size() < MAX_PHOTOS; i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        status("Loading " + uris.size() + " photo" + (uris.size() == 1 ? "" : "s") + "…");
        worker.execute(() -> {
            ArrayList<Bitmap> decoded = new ArrayList<>();
            for (Uri uri : uris) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                Bitmap b = decodeScaled(uri, 2000);
                if (b != null) decoded.add(b);
            }
            runOnUiThread(() -> {
                int room = Math.max(0, MAX_PHOTOS - editor.getPhotoCount());
                int added = Math.min(room, decoded.size());
                for (int i = 0; i < added; i++) editor.addPhoto(decoded.get(i));
                for (int i = added; i < decoded.size(); i++) decoded.get(i).recycle();
                status(editor.getPhotoCount() + " photo" + (editor.getPhotoCount() == 1 ? "" : "s") + " loaded. Paint Jellify, then Touch to grab it.");
                requestAutosave();
            });
        });
    }

    private Bitmap decodeScaled(Uri uri, int maxSide) {
        ContentResolver cr = getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
            int sample = 1;
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = cr.openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, opts); }
        } catch (Exception e) {
            return null;
        }
    }

    private File projectDir() {
        return new File(getFilesDir(), "jelliforge_saved_project");
    }

    private void requestAutosave() {
        if (editor != null && editor.getPhotoCount() > 0) saveProject(false);
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
                    status("Editable project saved. Open Saved restores photos, paint and settings.");
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
                    syncButtonsFromEditor();
                    status("Saved project restored. Transient jelly motion starts settled.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Open failed: " + e.getMessage()));
            }
        });
    }

    private void syncButtonsFromEditor() {
        toolButton.setText("Tool: " + editor.toolName());
        effectButton.setText("Effect: " + editor.effectName());
        materialButton.setText("Material: " + editor.materialName());
        linkButton.setText(editor.isLinkAll() ? "Link All: ON" : "Link All: OFF");
        heatButton.setText(editor.isHeatmap() ? "Heatmap: ON" : "Heatmap: OFF");
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
        out.focusSelected = root.optBoolean("focusSelected", false);
        out.tool = root.optInt("tool", EditorView.TOOL_PAINT);
        out.effect = root.has("effect") ? root.optInt("effect", EditorView.EFFECT_JELLIFY) : EditorView.EFFECT_JELLIFY;
        out.material = root.optInt("material", EditorView.MATERIAL_HARD);
        out.brushRadius = (float) root.optDouble("brushRadius", 0.11);
        out.intensity = (float) root.optDouble("intensity", 0.78);
        out.feather = (float) root.optDouble("feather", 0.82);
        out.bounce = (float) root.optDouble("bounce", 0.68);
        out.speed = (float) root.optDouble("speed", 1.0);
        int nextGroup = Math.max(1, root.optInt("nextGroup", 1));

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
                    float x = (float) mj.optDouble("x", 0.5);
                    float y = (float) mj.optDouble("y", 0.5);
                    float radius = (float) mj.optDouble("radius", 0.1);
                    float strength = (float) mj.optDouble("intensity", 0.5);
                    float feather = (float) mj.optDouble("feather", 0.8);
                    int effect = mj.optInt("effect", 0);
                    int group;
                    if (mj.has("group")) {
                        group = Math.max(1, mj.optInt("group", nextGroup++));
                    } else {
                        group = inferLegacyGroup(item, x, y, radius, effect);
                        if (group <= 0) group = nextGroup++;
                    }
                    nextGroup = Math.max(nextGroup, group + 1);
                    item.marks.add(new BrushMark(x, y, radius, strength, feather, effect, group));
                    if (effect == EditorView.EFFECT_JELLIFY) item.bodyFor(group);
                }
            }
            out.photos.add(item);
        }
        out.nextGroup = nextGroup;
        return out;
    }

    private int inferLegacyGroup(PhotoItem item, float x, float y, float radius, int effect) {
        if (effect != EditorView.EFFECT_JELLIFY) return -1;
        for (int i = item.marks.size() - 1, checked = 0; i >= 0 && checked < 180; i--, checked++) {
            BrushMark old = item.marks.get(i);
            if (old.effect != effect) continue;
            float dx = old.x - x;
            float dy = old.y - y;
            float reach = (old.radius + radius) * 0.60f;
            if (dx * dx + dy * dy <= reach * reach) return old.group;
        }
        return -1;
    }

    private String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private Uri beginGalleryItem(String displayName, String mime, int width, int height) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/JelliForge/");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L);
        values.put(MediaStore.Images.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L);
        if (width > 0) values.put(MediaStore.Images.Media.WIDTH, width);
        if (height > 0) values.put(MediaStore.Images.Media.HEIGHT, height);
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = getContentResolver().insert(collection, values);
        if (uri == null) throw new IOException("Android MediaStore could not create the Gallery item");
        return uri;
    }

    private void finishGalleryItem(Uri uri, String mime) throws IOException {
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (getContentResolver().update(uri, done, null, null) <= 0) throw new IOException("Gallery item could not be finalized");
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new IOException("Gallery could not verify the saved item");
        }
        getContentResolver().notifyChange(uri, null);
        try {
            Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri);
            sendBroadcast(scan);
        } catch (Exception ignored) { }
        lastGalleryUri = uri;
        lastGalleryMime = mime;
    }

    private void abortGalleryItem(Uri uri) {
        if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
    }

    private void saveBitmapToGallery(Bitmap frame, String name) throws Exception {
        Uri uri = null;
        try {
            uri = beginGalleryItem(name, "image/png", frame.getWidth(), frame.getHeight());
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null || !frame.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG encode failed");
                out.flush();
            }
            finishGalleryItem(uri, "image/png");
        } catch (Exception e) {
            abortGalleryItem(uri);
            throw e;
        }
    }

    private void saveSelectedToGallery() {
        if (editor.getPhotoCount() == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Saving selected photo to Gallery → DCIM/JelliForge…");
        worker.execute(() -> {
            Bitmap frame = null;
            try {
                frame = editor.renderSelected(editor.currentPhase(), 2600);
                saveBitmapToGallery(frame, "JelliForge_Photo_" + System.currentTimeMillis() + ".png");
                runOnUiThread(() -> {
                    status("Selected photo verified in MediaStore: DCIM/JelliForge.");
                    Toast.makeText(this, "Photo saved to Gallery", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> status("Gallery save failed: " + e.getMessage() + ". Try Save As… for a system file picker."));
            } finally {
                if (frame != null) frame.recycle();
            }
        });
    }

    private void saveGridToGallery() {
        if (editor.getPhotoCount() == 0) {
            Toast.makeText(this, "Add photos first.", Toast.LENGTH_SHORT).show();
            return;
        }
        status("Saving full grid to Gallery…");
        worker.execute(() -> {
            Bitmap frame = null;
            try {
                frame = editor.renderGrid(editor.currentPhase(), 2200);
                saveBitmapToGallery(frame, "JelliForge_Grid_" + System.currentTimeMillis() + ".png");
                runOnUiThread(() -> status("Grid saved and verified in DCIM/JelliForge."));
            } catch (Exception e) {
                runOnUiThread(() -> status("Grid save failed: " + e.getMessage()));
            } finally {
                if (frame != null) frame.recycle();
            }
        });
    }

    private void exportSelectedGif() {
        if (editor.getPhotoCount() == 0) return;
        status("Encoding selected photo GIF…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                Bitmap first = editor.renderSelected(0f, 900);
                int width = first.getWidth();
                int height = first.getHeight();
                uri = beginGalleryItem("JelliForge_Photo_" + System.currentTimeMillis() + ".gif", "image/gif", width, height);
                try (OutputStream raw = getContentResolver().openOutputStream(uri, "w"); BufferedOutputStream out = new BufferedOutputStream(raw)) {
                    GifEncoder332 enc = new GifEncoder332(out, width, height, 48);
                    enc.start();
                    enc.addFrame(first);
                    first.recycle();
                    int frames = 47;
                    for (int i = 1; i < frames; i++) {
                        float phase = (float) (Math.PI * 2.0 * i / frames);
                        Bitmap frame = editor.renderSelected(phase, 900);
                        enc.addFrame(frame);
                        frame.recycle();
                    }
                    enc.finish();
                }
                finishGalleryItem(uri, "image/gif");
                runOnUiThread(() -> status("Selected GIF saved to Gallery."));
            } catch (Exception e) {
                abortGalleryItem(uri);
                runOnUiThread(() -> status("Photo GIF failed: " + e.getMessage()));
            }
        });
    }

    private void exportGridGif() {
        if (editor.getPhotoCount() == 0) return;
        status("Encoding grid GIF…");
        worker.execute(() -> {
            Uri uri = null;
            try {
                Bitmap first = editor.renderGrid(0f, 1040);
                int width = first.getWidth();
                int height = first.getHeight();
                uri = beginGalleryItem("JelliForge_Grid_" + System.currentTimeMillis() + ".gif", "image/gif", width, height);
                try (OutputStream raw = getContentResolver().openOutputStream(uri, "w"); BufferedOutputStream out = new BufferedOutputStream(raw)) {
                    GifEncoder332 enc = new GifEncoder332(out, width, height, 52);
                    enc.start();
                    enc.addFrame(first);
                    first.recycle();
                    int frames = 43;
                    for (int i = 1; i < frames; i++) {
                        float phase = (float) (Math.PI * 2.0 * i / frames);
                        Bitmap frame = editor.renderGrid(phase, 1040);
                        enc.addFrame(frame);
                        frame.recycle();
                    }
                    enc.finish();
                }
                finishGalleryItem(uri, "image/gif");
                runOnUiThread(() -> status("Grid GIF saved to Gallery."));
            } catch (Exception e) {
                abortGalleryItem(uri);
                runOnUiThread(() -> status("Grid GIF failed: " + e.getMessage()));
            }
        });
    }

    private void exportBatchGifs() {
        int count = editor.getPhotoCount();
        if (count == 0) return;
        status("Batch exporting " + count + " GIFs…");
        worker.execute(() -> {
            int made = 0;
            for (int index = 0; index < count; index++) {
                Uri uri = null;
                try {
                    Bitmap first = editor.renderSingle(index, 0f, 820);
                    int width = first.getWidth();
                    int height = first.getHeight();
                    uri = beginGalleryItem(String.format(Locale.US, "JelliForge_%02d_%d.gif", index + 1, System.currentTimeMillis()), "image/gif", width, height);
                    try (OutputStream raw = getContentResolver().openOutputStream(uri, "w"); BufferedOutputStream out = new BufferedOutputStream(raw)) {
                        GifEncoder332 enc = new GifEncoder332(out, width, height, 56);
                        enc.start();
                        enc.addFrame(first);
                        first.recycle();
                        int frames = 37;
                        for (int f = 1; f < frames; f++) {
                            float phase = (float) (Math.PI * 2.0 * f / frames);
                            Bitmap frame = editor.renderSingle(index, phase, 820);
                            enc.addFrame(frame);
                            frame.recycle();
                        }
                        enc.finish();
                    }
                    finishGalleryItem(uri, "image/gif");
                    made++;
                } catch (Exception e) {
                    abortGalleryItem(uri);
                }
            }
            int finalMade = made;
            runOnUiThread(() -> status("Batch complete: " + finalMade + "/" + count + " GIFs saved."));
        });
    }

    private void openLastGalleryItem() {
        Uri uri = lastGalleryUri;
        if (uri == null) {
            Toast.makeText(this, "Save something first.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, lastGalleryMime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "Open saved JelliForge file"));
        } catch (Exception e) {
            status("The file is saved, but no installed viewer accepted it.");
        }
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
        requestAutosave();
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

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private final class EditorView extends View {
        static final int TOOL_PAINT = 0;
        static final int TOOL_ERASE = 1;
        static final int TOOL_OVERRIDE = 2;
        static final int TOOL_TOUCH = 3;

        static final int EFFECT_WOBBLE = 0;
        static final int EFFECT_PULSE = 1;
        static final int EFFECT_TWIST = 2;
        static final int EFFECT_WAVE = 3;
        static final int EFFECT_ELASTIC = 4;
        static final int EFFECT_JELLIFY = 5;

        static final int MATERIAL_HARD = 0;
        static final int MATERIAL_SOFT = 1;
        static final int MATERIAL_SLIME = 2;

        private static final int MW = 40;
        private static final int MH = 40;
        private static final int HISTORY_LIMIT = 36;

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
        private boolean linkAll;
        private boolean heatmap = true;
        private boolean focusSelected;
        private long startMs = SystemClock.uptimeMillis();
        private long lastFrameMs = startMs;
        private volatile float tiltX;
        private volatile float tiltY;
        private int tool = TOOL_PAINT;
        private int currentEffect = EFFECT_JELLIFY;
        private int material = MATERIAL_HARD;
        private float brushRadius = 0.124f;
        private float intensity = 0.78f;
        private float feather = 0.82f;
        private float bounce = 0.68f;
        private float speed = 1.0f;
        private float lastStampX = -10f;
        private float lastStampY = -10f;
        private float cursorX;
        private float cursorY;
        private boolean cursorVisible;
        private boolean strokeActive;
        private int nextGroupId = 1;
        private int activeGroupId = 1;
        private float touchPrevX;
        private float touchPrevY;
        private long touchPrevMs;
        private float touchTravel;

        EditorView() {
            super(MainActivityV13.this);
            setBackgroundColor(Color.rgb(20, 20, 25));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(3));
            cursorPaint.setStyle(Paint.Style.STROKE);
            cursorPaint.setStrokeWidth(dp(2));
            cursorPaint.setColor(Color.WHITE);
        }

        int getPhotoCount() { synchronized (photos) { return photos.size(); } }
        int getSelectedIndex() { synchronized (photos) { return selected < 0 && !photos.isEmpty() ? 0 : selected; } }
        boolean isPlaying() { return playing; }
        boolean isLinkAll() { return linkAll; }
        boolean isHeatmap() { return heatmap; }
        boolean isFocusSelected() { return focusSelected; }
        void setLinkAll(boolean value) { linkAll = value; invalidate(); }
        void setHeatmap(boolean value) { heatmap = value; invalidate(); }
        void setFocusSelected(boolean value) {
            if (photos.isEmpty()) value = false;
            focusSelected = value;
            invalidate();
        }

        void setBrushProgress(int value) {
            float t = clamp(value / 100f, 0.01f, 1f);
            brushRadius = 0.025f + t * 0.275f;
            invalidate();
        }

        void setIntensityProgress(int value) { intensity = clamp(value / 100f, 0.03f, 1f); invalidate(); }
        void setFeatherProgress(int value) { feather = clamp(0.35f + value / 100f * 0.65f, 0.35f, 1f); invalidate(); }
        void setBounceProgress(int value) { bounce = clamp(value / 100f, 0.05f, 1f); }
        void setSpeedProgress(int value) { speed = 0.45f + clamp(value / 100f, 0f, 1f) * 1.55f; }

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
                focusSelected = loaded.focusSelected && !photos.isEmpty();
                tool = clampInt(loaded.tool, TOOL_PAINT, TOOL_TOUCH);
                currentEffect = clampInt(loaded.effect, EFFECT_WOBBLE, EFFECT_JELLIFY);
                material = clampInt(loaded.material, MATERIAL_HARD, MATERIAL_SLIME);
                brushRadius = clamp(loaded.brushRadius, 0.025f, 0.30f);
                intensity = clamp(loaded.intensity, 0.03f, 1f);
                feather = clamp(loaded.feather, 0.35f, 1f);
                bounce = clamp(loaded.bounce, 0.05f, 1f);
                speed = clamp(loaded.speed, 0.45f, 2.0f);
                nextGroupId = Math.max(loaded.nextGroup, 1);
                for (PhotoItem p : photos) p.rebuildBodies();
            }
            undoStack.clear();
            redoStack.clear();
            settleMotion();
            invalidate();
        }

        void saveProject(File dir) throws Exception {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create project folder");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (f.getName().startsWith("photo_") && f.getName().endsWith(".png")) f.delete();

            JSONObject root = new JSONObject();
            JSONArray photoArray = new JSONArray();
            synchronized (photos) {
                root.put("version", 4);
                root.put("selected", selected);
                root.put("linkAll", linkAll);
                root.put("heatmap", heatmap);
                root.put("focusSelected", focusSelected);
                root.put("tool", tool);
                root.put("effect", currentEffect);
                root.put("material", material);
                root.put("brushRadius", brushRadius);
                root.put("intensity", intensity);
                root.put("feather", feather);
                root.put("bounce", bounce);
                root.put("speed", speed);
                root.put("nextGroup", nextGroupId);

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
                        mj.put("group", mark.group);
                        marks.put(mj);
                    }
                    pj.put("marks", marks);
                    photoArray.put(pj);
                }
            }
            root.put("photos", photoArray);
            File temp = new File(dir, "project.json.tmp");
            try (OutputStream out = new FileOutputStream(temp)) { out.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            File dest = new File(dir, "project.json");
            if (dest.exists() && !dest.delete()) throw new IOException("Could not replace old project");
            if (!temp.renameTo(dest)) throw new IOException("Could not finalize project");
        }

        void togglePlaying() {
            playing = !playing;
            if (playing) {
                startMs = SystemClock.uptimeMillis();
                lastFrameMs = startMs;
                postInvalidateOnAnimation();
            } else invalidate();
        }

        float currentPhase() { return playing ? (SystemClock.uptimeMillis() - startMs) / 430f * speed : 0f; }

        void setTilt(float x, float y) {
            float nx = clamp(-x / 9.81f, -1f, 1f);
            float ny = clamp(y / 9.81f, -1f, 1f);
            float dx = nx - tiltX;
            float dy = ny - tiltY;
            tiltX = nx;
            tiltY = ny;
            if (Math.abs(dx) + Math.abs(dy) > 0.025f) {
                synchronized (photos) {
                    for (PhotoItem p : photos) {
                        for (JellyBody b : p.bodies.values()) {
                            b.vx += dx * (0.16f + bounce * 0.20f);
                            b.vy += dy * (0.16f + bounce * 0.20f);
                        }
                    }
                }
            }
            if (playing) postInvalidateOnAnimation();
        }

        String cycleTool() { tool = (tool + 1) % 4; invalidate(); return toolName(); }
        String cycleEffect() { currentEffect = (currentEffect + 1) % 6; invalidate(); return effectName(); }
        String cycleMaterial() { material = (material + 1) % 3; settleMotion(); invalidate(); return materialName(); }

        String toolName() {
            switch (tool) {
                case TOOL_ERASE: return "Erase";
                case TOOL_OVERRIDE: return "Override";
                case TOOL_TOUCH: return "Touch";
                default: return "Paint";
            }
        }

        String effectName() { return MainActivityV13.effectName(currentEffect); }
        String materialName() { return MainActivityV13.materialName(material); }

        void clearCurrent() {
            synchronized (photos) {
                if (photos.isEmpty()) return;
                pushUndoLocked();
                if (linkAll) {
                    for (PhotoItem p : photos) { p.marks.clear(); p.bodies.clear(); }
                } else if (selected >= 0 && selected < photos.size()) {
                    photos.get(selected).marks.clear();
                    photos.get(selected).bodies.clear();
                }
            }
            invalidate();
        }

        void settleMotion() {
            synchronized (photos) {
                for (PhotoItem p : photos) {
                    for (JellyBody b : p.bodies.values()) b.reset();
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
                } else if (selected >= 0 && selected < photos.size()) seedSmartMarks(photos.get(selected));
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
                    if (score > bestScore[slot]) { bestScore[slot] = score; bestX[slot] = nx; bestY[slot] = ny; }
                }
            }
            for (int i = 0; i < 3; i++) {
                int group = nextGroupId++;
                int effect = currentEffect;
                item.marks.add(new BrushMark(bestX[i], bestY[i], Math.max(brushRadius, 0.18f), Math.max(intensity * (0.82f + i * 0.05f), 0.52f), feather, effect, group));
                if (effect == EFFECT_JELLIFY) item.bodyFor(group);
            }
        }

        private float luma(int c) { return Color.red(c) * 0.2126f + Color.green(c) * 0.7152f + Color.blue(c) * 0.0722f; }

        boolean undo() {
            synchronized (photos) {
                if (undoStack.isEmpty()) return false;
                redoStack.add(snapshotLocked());
                restoreLocked(undoStack.remove(undoStack.size() - 1));
            }
            invalidate();
            requestAutosave();
            return true;
        }

        boolean redo() {
            synchronized (photos) {
                if (redoStack.isEmpty()) return false;
                undoStack.add(snapshotLocked());
                restoreLocked(redoStack.remove(redoStack.size() - 1));
            }
            invalidate();
            requestAutosave();
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
                PhotoItem p = photos.get(i);
                p.marks.clear();
                if (i < state.marks.size()) for (BrushMark m : state.marks.get(i)) p.marks.add(m.copy());
                p.rebuildBodies();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = SystemClock.uptimeMillis();
            float dt = clamp((now - lastFrameMs) / 1000f, 0f, 0.040f);
            lastFrameMs = now;
            if (playing && dt > 0f) advancePhysics(dt);
            drawScene(canvas, getWidth(), getHeight(), currentPhase(), true, heatmap, focusSelected);
            if (playing) postInvalidateOnAnimation();
        }

        private void advancePhysics(float dt) {
            float k;
            float damping;
            float maxDisp;
            if (material == MATERIAL_SOFT) {
                k = 18f; damping = 4.5f - bounce * 1.3f; maxDisp = 0.18f;
            } else if (material == MATERIAL_SLIME) {
                k = 8.5f; damping = 2.8f - bounce * 0.7f; maxDisp = 0.24f;
            } else {
                k = 34f; damping = 7.8f - bounce * 2.0f; maxDisp = 0.125f;
            }
            k *= 0.75f + speed * 0.25f;
            damping = Math.max(1.1f, damping);
            synchronized (photos) {
                for (PhotoItem p : photos) {
                    for (JellyBody b : p.bodies.values()) {
                        float ax = -k * b.dx - damping * b.vx + tiltX * 0.018f;
                        float ay = -k * b.dy - damping * b.vy + tiltY * 0.018f;
                        b.vx += ax * dt;
                        b.vy += ay * dt;
                        b.dx += b.vx * dt;
                        b.dy += b.vy * dt;
                        b.dx = clamp(b.dx, -maxDisp, maxDisp);
                        b.dy = clamp(b.dy, -maxDisp, maxDisp);
                        b.vx = clamp(b.vx, -2.5f, 2.5f);
                        b.vy = clamp(b.vy, -2.5f, 2.5f);
                    }
                }
            }
        }

        private void drawScene(Canvas canvas, int w, int h, float phase, boolean showSelection, boolean showHeat, boolean respectFocus) {
            canvas.drawColor(Color.rgb(20, 20, 25));
            synchronized (photos) {
                int n = photos.size();
                if (n == 0) {
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Color.rgb(175, 175, 190));
                    p.setTextSize(Math.max(25f, w * 0.036f));
                    p.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("Add photos → Paint Jellify → Touch and flick.", w / 2f, h / 2f, p);
                    return;
                }
                if (respectFocus && focusSelected && selected >= 0 && selected < n) {
                    RectF rect = fitRect(new RectF(dp(6), dp(6), w - dp(6), h - dp(6)), photos.get(selected).bitmap);
                    drawItem(canvas, photos.get(selected), rect, phase, showSelection, showHeat);
                } else {
                    for (int i = 0; i < n; i++) {
                        RectF rect = photoRect(i, n, w, h, photos.get(i).bitmap);
                        drawItem(canvas, photos.get(i), rect, phase, showSelection && i == selected, showHeat && i == selected);
                    }
                }
            }
            if (cursorVisible && activeRect != null && tool != TOOL_TOUCH) {
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

        private int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }

        private float[] buildVerts(PhotoItem item, RectF rect, float phase) {
            float[] verts = new float[(MW + 1) * (MH + 1) * 2];
            int k = 0;
            float globalPhase = phase + tiltX * 0.52f - tiltY * 0.31f;
            float minSide = Math.min(rect.width(), rect.height());
            float[] jTmp = new float[2];

            for (int y = 0; y <= MH; y++) {
                float ny = y / (float) MH;
                for (int x = 0; x <= MW; x++) {
                    float nx = x / (float) MW;
                    float px = rect.left + nx * rect.width();
                    float py = rect.top + ny * rect.height();
                    float ox = tiltX * rect.width() * 0.0018f;
                    float oy = tiltY * rect.height() * 0.0018f;

                    int pendingGroup = -1;
                    float pendingLocal = 0f;
                    float pendingRadial = 1f;
                    BrushMark pendingMark = null;

                    for (int mi = 0; mi <= item.marks.size(); mi++) {
                        BrushMark mark = mi < item.marks.size() ? item.marks.get(mi) : null;
                        boolean isJelly = mark != null && mark.effect == EFFECT_JELLIFY;

                        if (!isJelly || (pendingGroup >= 0 && mark.group != pendingGroup)) {
                            if (pendingGroup >= 0 && pendingMark != null && pendingLocal > 0f) {
                                computeJellyOffset(item, pendingMark, pendingLocal, pendingRadial, nx, ny, rect, minSide, globalPhase, jTmp);
                                ox += jTmp[0];
                                oy += jTmp[1];
                            }
                            pendingGroup = -1;
                            pendingLocal = 0f;
                            pendingMark = null;
                        }

                        if (mark == null) break;

                        float dxPx = (nx - mark.x) * rect.width();
                        float dyPx = (ny - mark.y) * rect.height();
                        float distance = (float) Math.sqrt(dxPx * dxPx + dyPx * dyPx);
                        float radiusPx = Math.max(2f, mark.radius * minSide);
                        if (distance >= radiusPx) continue;
                        float fall = softFalloff(distance / radiusPx, mark.feather);
                        float local = mark.intensity * fall;
                        if (local <= 0.001f) continue;
                        float radial = clamp(distance / radiusPx, 0f, 1f);

                        if (mark.effect == EFFECT_JELLIFY) {
                            if (pendingGroup < 0) pendingGroup = mark.group;
                            if (local > pendingLocal) {
                                pendingLocal = local;
                                pendingRadial = radial;
                                pendingMark = mark;
                            }
                            continue;
                        }

                        float d = Math.max(0.001f, distance);
                        float ux = dxPx / d;
                        float uy = dyPx / d;
                        float seed = mark.x * 9.3f + mark.y * 13.7f;
                        float amp = minSide * 0.050f * local;
                        switch (mark.effect) {
                            case EFFECT_PULSE: {
                                float s = (float) Math.sin(globalPhase + seed + radial * 4.2f);
                                ox += ux * amp * s; oy += uy * amp * s; break;
                            }
                            case EFFECT_TWIST: {
                                float s = (float) Math.sin(globalPhase + seed + radial * 3.4f);
                                ox += -uy * amp * s * 1.15f; oy += ux * amp * s * 1.15f; break;
                            }
                            case EFFECT_WAVE:
                                ox += amp * 0.64f * (float) Math.sin(globalPhase * 1.08f + ny * 15f + seed);
                                oy += amp * 0.64f * (float) Math.sin(globalPhase * 1.23f + nx * 14f + seed * 0.7f);
                                break;
                            case EFFECT_ELASTIC: {
                                float s = (float) Math.sin(globalPhase * 1.15f + seed + radial * 4.6f);
                                ox += ux * amp * s * 0.55f + amp * 0.32f * (float) Math.sin(globalPhase * 1.31f + seed);
                                oy += uy * amp * s * 0.55f + amp * 0.32f * (float) Math.cos(globalPhase * 1.17f + seed);
                                break;
                            }
                            default:
                                ox += amp * 0.72f * (float) Math.sin(globalPhase * 1.05f + seed + ny * 4f);
                                oy += amp * 0.72f * (float) Math.cos(globalPhase * 1.16f + seed + nx * 4f);
                                break;
                        }
                    }

                    ox = clamp(ox, -rect.width() * 0.24f, rect.width() * 0.24f);
                    oy = clamp(oy, -rect.height() * 0.24f, rect.height() * 0.24f);
                    verts[k++] = px + ox;
                    verts[k++] = py + oy;
                }
            }
            return verts;
        }

        private void computeJellyOffset(PhotoItem item, BrushMark mark, float local, float radial,
                                        float nx, float ny, RectF rect, float minSide, float phase, float[] out) {
            JellyBody body = item.bodyFor(mark.group);
            float dxPx = (nx - mark.x) * rect.width();
            float dyPx = (ny - mark.y) * rect.height();
            float d = Math.max(0.001f, (float) Math.sqrt(dxPx * dxPx + dyPx * dyPx));
            float ux = dxPx / d;
            float uy = dyPx / d;
            float groupSeed = mark.group * 0.713f + mark.x * 2.1f + mark.y * 3.7f;

            float materialIdle;
            float shear;
            float lag;
            if (material == MATERIAL_SOFT) {
                materialIdle = 0.010f; shear = 0.34f; lag = radial * 0.38f;
            } else if (material == MATERIAL_SLIME) {
                materialIdle = 0.014f; shear = 0.56f; lag = radial * 0.62f;
            } else {
                materialIdle = 0.0055f; shear = 0.16f; lag = radial * 0.20f;
            }

            float idleX = (float) Math.sin(phase * 0.78f + groupSeed - lag) * materialIdle * (0.55f + bounce * 0.45f);
            float idleY = (float) Math.sin(phase * 0.84f + groupSeed + 1.37f - lag) * materialIdle * (0.55f + bounce * 0.45f);
            float velocityPhase = (float) Math.sin(phase * 1.42f + groupSeed + 0.8f - lag * 1.3f);
            float liveX = body.dx + body.vx * 0.018f * velocityPhase;
            float liveY = body.dy + body.vy * 0.018f * (float) Math.cos(phase * 1.36f + groupSeed - lag);
            float coherence = 1f - radial * (material == MATERIAL_HARD ? 0.055f : 0.12f);
            float ampX = (liveX + idleX) * minSide * local * coherence;
            float ampY = (liveY + idleY) * minSide * local * coherence;

            float velocityAlongRadial = body.vx * ux + body.vy * uy;
            float squash = clamp(-velocityAlongRadial * 0.018f, -0.055f, 0.055f) * (1f - radial) * local;
            float secondary = (float) Math.sin(phase * 1.82f + groupSeed + radial * 2.2f) * minSide * materialIdle * local * shear;

            out[0] = ampX + ux * minSide * squash - uy * secondary;
            out[1] = ampY + uy * minSide * squash + ux * secondary;
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
            RectF cell = new RectF(col * cellW + gap, row * cellH + gap, (col + 1) * cellW - gap, (row + 1) * cellH - gap);
            return fitRect(cell, bitmap);
        }

        private RectF fitRect(RectF cell, Bitmap bitmap) {
            float imageAspect = bitmap.getWidth() / (float) bitmap.getHeight();
            float cellAspect = cell.width() / Math.max(1f, cell.height());
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
                    if (focusSelected) activeRect = fitRect(new RectF(dp(6), dp(6), getWidth() - dp(6), getHeight() - dp(6)), photos.get(activeTile).bitmap);
                    else activeRect = photoRect(activeTile, photos.size(), getWidth(), getHeight(), photos.get(activeTile).bitmap);
                }
                cursorX = x; cursorY = y; cursorVisible = true;
                touchPrevX = x; touchPrevY = y; touchPrevMs = event.getEventTime(); touchTravel = 0f;

                if (tool == TOOL_TOUCH) {
                    pokeAtScreen(x, y, 0.055f);
                } else {
                    synchronized (photos) { pushUndoLocked(); }
                    activeGroupId = nextGroupId++;
                    strokeActive = true;
                    lastStampX = -10f;
                    lastStampY = -10f;
                    stampScreenPoint(x, y, true);
                }
                invalidate();
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE && activeTile >= 0 && activeRect != null) {
                cursorX = x; cursorY = y; cursorVisible = true;
                if (tool == TOOL_TOUCH) {
                    long now = event.getEventTime();
                    float dt = Math.max(0.008f, (now - touchPrevMs) / 1000f);
                    float minSide = Math.max(1f, Math.min(activeRect.width(), activeRect.height()));
                    float dx = (x - touchPrevX) / minSide;
                    float dy = (y - touchPrevY) / minSide;
                    touchTravel += Math.abs(dx) + Math.abs(dy);
                    dragJellyAtScreen(x, y, dx, dy, dt);
                    touchPrevX = x; touchPrevY = y; touchPrevMs = now;
                } else if (strokeActive) {
                    stampScreenPoint(x, y, false);
                }
                invalidate();
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (tool == TOOL_TOUCH && touchTravel < 0.018f && activeRect != null) pokeAtScreen(x, y, 0.15f + bounce * 0.12f);
                boolean edited = strokeActive && tool != TOOL_TOUCH;
                cursorVisible = false;
                strokeActive = false;
                activeTile = -1;
                activeRect = null;
                lastStampX = -10f;
                lastStampY = -10f;
                if (edited) requestAutosave();
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
                float spacing = Math.max(0.004f, brushRadius * 0.20f);
                if (dist < spacing) return;
                int steps = Math.min(28, Math.max(1, (int) Math.ceil(dist / spacing)));
                for (int i = 1; i <= steps; i++) {
                    float t = i / (float) steps;
                    stampNormalized(lastStampX + dx * t, lastStampY + dy * t);
                }
            } else stampNormalized(nx, ny);
            lastStampX = nx;
            lastStampY = ny;
        }

        private void stampNormalized(float nx, float ny) {
            synchronized (photos) {
                if (linkAll) {
                    for (PhotoItem item : photos) applyTool(item, nx, ny);
                } else if (activeTile >= 0 && activeTile < photos.size()) applyTool(photos.get(activeTile), nx, ny);
            }
        }

        private void applyTool(PhotoItem item, float nx, float ny) {
            if (tool == TOOL_ERASE) {
                eraseAt(item, nx, ny);
                return;
            }
            if (tool == TOOL_OVERRIDE) eraseOverlap(item, nx, ny, true);
            BrushMark mark = new BrushMark(nx, ny, brushRadius, intensity, feather, currentEffect, activeGroupId);
            item.marks.add(mark);
            if (currentEffect == EFFECT_JELLIFY) item.bodyFor(activeGroupId);
            if (item.marks.size() > 3600) item.marks.remove(0);
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
            item.pruneBodies();
        }

        private void eraseOverlap(PhotoItem item, float nx, float ny, boolean broad) {
            float scale = broad ? 0.82f : 0.62f;
            for (int i = item.marks.size() - 1; i >= 0; i--) {
                BrushMark m = item.marks.get(i);
                float dx = m.x - nx;
                float dy = m.y - ny;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d <= brushRadius * scale + m.radius * 0.45f) item.marks.remove(i);
            }
            item.pruneBodies();
        }

        private void dragJellyAtScreen(float sx, float sy, float dxNorm, float dyNorm, float dt) {
            if (activeRect == null || !activeRect.contains(sx, sy)) return;
            float nx = clamp((sx - activeRect.left) / activeRect.width(), 0f, 1f);
            float ny = clamp((sy - activeRect.top) / activeRect.height(), 0f, 1f);
            synchronized (photos) {
                if (linkAll) {
                    for (PhotoItem p : photos) dragJellyAt(p, nx, ny, dxNorm, dyNorm, dt);
                } else if (activeTile >= 0 && activeTile < photos.size()) dragJellyAt(photos.get(activeTile), nx, ny, dxNorm, dyNorm, dt);
            }
        }

        private void dragJellyAt(PhotoItem item, float nx, float ny, float dxNorm, float dyNorm, float dt) {
            HashMap<Integer, Float> weights = groupWeightsAt(item, nx, ny);
            for (Map.Entry<Integer, Float> e : weights.entrySet()) {
                float w = e.getValue();
                JellyBody body = item.bodyFor(e.getKey());
                float materialGrab = material == MATERIAL_HARD ? 0.78f : (material == MATERIAL_SOFT ? 0.92f : 1.08f);
                body.dx += dxNorm * w * materialGrab;
                body.dy += dyNorm * w * materialGrab;
                float impulse = 0.09f + bounce * 0.20f;
                body.vx += dxNorm / dt * w * impulse;
                body.vy += dyNorm / dt * w * impulse;
            }
        }

        private void pokeAtScreen(float sx, float sy, float power) {
            if (activeRect == null || !activeRect.contains(sx, sy)) return;
            float nx = clamp((sx - activeRect.left) / activeRect.width(), 0f, 1f);
            float ny = clamp((sy - activeRect.top) / activeRect.height(), 0f, 1f);
            synchronized (photos) {
                if (linkAll) {
                    for (PhotoItem p : photos) pokeAt(p, nx, ny, power);
                } else if (activeTile >= 0 && activeTile < photos.size()) pokeAt(photos.get(activeTile), nx, ny, power);
            }
        }

        private void pokeAt(PhotoItem item, float nx, float ny, float power) {
            HashMap<Integer, Float> weights = groupWeightsAt(item, nx, ny);
            for (Map.Entry<Integer, Float> e : weights.entrySet()) {
                JellyBody b = item.bodyFor(e.getKey());
                float seed = e.getKey() * 0.73f;
                b.vx += (float) Math.sin(seed) * power * e.getValue();
                b.vy += (float) Math.cos(seed * 1.27f) * power * e.getValue();
            }
        }

        private HashMap<Integer, Float> groupWeightsAt(PhotoItem item, float nx, float ny) {
            HashMap<Integer, Float> weights = new HashMap<>();
            for (BrushMark m : item.marks) {
                if (m.effect != EFFECT_JELLIFY) continue;
                float dx = m.x - nx;
                float dy = m.y - ny;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float reach = Math.max(0.025f, m.radius * 1.12f);
                if (dist > reach) continue;
                float w = 1f - dist / reach;
                w = w * w * m.intensity;
                Float old = weights.get(m.group);
                if (old == null || w > old) weights.put(m.group, w);
            }
            return weights;
        }

        private int findTile(float x, float y) {
            synchronized (photos) {
                if (focusSelected && selected >= 0 && selected < photos.size()) {
                    RectF r = fitRect(new RectF(dp(6), dp(6), getWidth() - dp(6), getHeight() - dp(6)), photos.get(selected).bitmap);
                    return r.contains(x, y) ? selected : -1;
                }
                for (int i = 0; i < photos.size(); i++) {
                    RectF r = photoRect(i, photos.size(), getWidth(), getHeight(), photos.get(i).bitmap);
                    if (r.contains(x, y)) return i;
                }
            }
            return -1;
        }

        Bitmap renderGrid(float phase, int maxWidth) {
            int vw = Math.max(1, getWidth());
            int vh = Math.max(1, getHeight());
            if (vw <= 1 || vh <= 1) { vw = 1080; vh = 1080; }
            int outW = Math.min(maxWidth, vw);
            int outH = Math.max(1, Math.round(outW * (vh / (float) vw)));
            if (outH > 2200) {
                float scale = 2200f / outH;
                outH = 2200;
                outW = Math.max(1, Math.round(outW * scale));
            }
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            drawScene(canvas, outW, outH, phase, false, false, false);
            return out;
        }

        Bitmap renderSelected(float phase, int maxSide) {
            return renderSingle(getSelectedIndex(), phase, maxSide);
        }

        Bitmap renderSingle(int index, float phase, int maxSide) {
            PhotoItem item;
            synchronized (photos) {
                if (photos.isEmpty()) throw new IllegalStateException("No photos");
                if (index < 0 || index >= photos.size()) index = 0;
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
            case 0: return "Wobble";
            case 1: return "Pulse";
            case 2: return "Twist";
            case 3: return "Wave";
            case 4: return "Elastic";
            case 5: return "Jellify";
            default: return "Jellify";
        }
    }

    private static String materialName(int material) {
        switch (material) {
            case 1: return "Soft Jello";
            case 2: return "Slime";
            default: return "Hard Jello";
        }
    }

    private static final class PhotoItem {
        final Bitmap bitmap;
        final ArrayList<BrushMark> marks = new ArrayList<>();
        final HashMap<Integer, JellyBody> bodies = new HashMap<>();

        PhotoItem(Bitmap bitmap) { this.bitmap = bitmap; }

        JellyBody bodyFor(int group) {
            JellyBody b = bodies.get(group);
            if (b == null) { b = new JellyBody(); bodies.put(group, b); }
            return b;
        }

        void rebuildBodies() {
            bodies.clear();
            for (BrushMark m : marks) if (m.effect == EditorView.EFFECT_JELLIFY) bodyFor(m.group);
        }

        void pruneBodies() {
            HashSet<Integer> used = new HashSet<>();
            for (BrushMark m : marks) if (m.effect == EditorView.EFFECT_JELLIFY) used.add(m.group);
            bodies.keySet().retainAll(used);
        }
    }

    private static final class JellyBody {
        float dx, dy, vx, vy;
        void reset() { dx = dy = vx = vy = 0f; }
    }

    private static final class BrushMark {
        final float x, y, radius, intensity, feather;
        final int effect;
        final int group;

        BrushMark(float x, float y, float radius, float intensity, float feather, int effect, int group) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.intensity = intensity;
            this.feather = feather;
            this.effect = effect;
            this.group = group;
        }

        BrushMark copy() { return new BrushMark(x, y, radius, intensity, feather, effect, group); }
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
        boolean focusSelected;
        int tool;
        int effect = EditorView.EFFECT_JELLIFY;
        int material = EditorView.MATERIAL_HARD;
        int nextGroup = 1;
        float brushRadius = 0.124f;
        float intensity = 0.78f;
        float feather = 0.82f;
        float bounce = 0.68f;
        float speed = 1.0f;
    }
}
