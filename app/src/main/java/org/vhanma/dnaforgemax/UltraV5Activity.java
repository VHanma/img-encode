package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** OsciVision Ultra v5: contour-vector + harmonic-lattice XY instrument. */
public class UltraV5Activity extends Activity {
    private static final int REQ_OPEN = 5101;
    private static final int REQ_SAVE = 5102;
    private static final int KIND_NONE = 0, KIND_IMAGE = 1, KIND_VIDEO = 2, KIND_GIF = 3;

    private V5ScopeView scope;
    private ImageView sourcePreview;
    private TextView status;
    private TextView qualityLabel, contourLabel, fillLabel, resonanceLabel, smoothLabel, gammaLabel;
    private TextView persistenceLabel, intensityLabel, bloomLabel, beamLabel;
    private TextView xGainLabel, yGainLabel, rotationLabel;
    private Spinner sampleSpinner, fpsSpinner, profileSpinner, bandsSpinner, bankSpinner, exportSpinner;
    private SeekBar qualityBar, contourBar, fillBar, resonanceBar, smoothBar, gammaBar;
    private SeekBar persistenceBar, intensityBar, bloomBar, beamBar, xGainBar, yGainBar, rotationBar;
    private CheckBox temporalBox, invertBox, sourceBox;
    private Button playButton;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean previewQueued = new AtomicBoolean(false);
    private final AtomicInteger previewGeneration = new AtomicInteger(0);
    private final V4Audio audio = new V4Audio();
    private Thread animationThread;

    private Uri sourceUri;
    private int sourceKind = KIND_NONE;
    private Bitmap stillBitmap;
    private Movie gifMovie;
    private byte[] gifBytes;
    private MediaMetadataRetriever retriever;
    private long durationMs;
    private int sourceWidth, sourceHeight;
    private volatile V5Engine.Result lastResult;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        status.setText("Load an image, GIF, or video. v5 converts structure into real vector contours before it ever becomes audio.");
    }

    private View buildUi() {
        int pad = dp(11);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(1, 4, 7));

        TextView title = new TextView(this);
        title.setText("OSCIVISION ULTRA v5");
        title.setTextColor(Color.rgb(192, 255, 218));
        title.setTextSize(25f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = new TextView(this);
        sub.setText("CONTOUR-VECTOR • PHASE-CLOSING HARMONIC LATTICE");
        sub.setTextColor(Color.rgb(115, 220, 165));
        sub.setTextSize(11.5f);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(2), 0, dp(6));
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        sourcePreview = new ImageView(this);
        sourcePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sourcePreview.setBackgroundColor(Color.BLACK);
        sourcePreview.setVisibility(View.GONE);
        root.addView(sourcePreview, new LinearLayout.LayoutParams(-1, dp(150)));

        scope = new V5ScopeView(this);
        root.addView(scope, new LinearLayout.LayoutParams(-1, dp(440)));

        LinearLayout actions = row();
        Button load = button("LOAD");
        playButton = button("PLAY XY");
        Button max = button("MAX MODE");
        Button save = button("EXPORT");
        actions.addView(load, weight()); actions.addView(playButton, weight());
        actions.addView(max, weight()); actions.addView(save, weight());
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        load.setOnClickListener(v -> openMedia());
        playButton.setOnClickListener(v -> togglePlayback());
        max.setOnClickListener(v -> autoTune());
        save.setOnClickListener(v -> chooseSave());

        sourceBox = new CheckBox(this);
        sourceBox.setText("Show source reference");
        sourceBox.setTextColor(Color.rgb(205, 228, 216));
        sourceBox.setOnCheckedChangeListener((b, checked) -> sourcePreview.setVisibility(checked ? View.VISIBLE : View.GONE));
        root.addView(sourceBox);

        root.addView(section("CORE RENDER"));
        profileSpinner = spinner(new String[]{
                "MAX PHOTO · contours + tonal field",
                "PORTRAIT / FINE DETAIL",
                "VIDEO STABLE",
                "LINE / LOGO / DRAWING"
        }, 0, true);
        root.addView(profileSpinner);

        sampleSpinner = spinner(new String[]{"192000 Hz · strongest", "96000 Hz", "48000 Hz"}, 0, true);
        root.addView(sampleSpinner);

        fpsSpinner = spinner(new String[]{
                "8 fps · max still detail", "12 fps", "15 fps", "24 fps", "30 fps · video", "60 fps · motion"
        }, 0, true);
        root.addView(fpsSpinner);

        bandsSpinner = spinner(new String[]{"4 tone contours", "6 tone contours", "8 tone contours", "10 tone contours", "12 tone contours"}, 2, true);
        root.addView(bandsSpinner);

        qualityLabel = label("VECTOR DETAIL: 98%"); root.addView(qualityLabel);
        qualityBar = slider(98, v -> { qualityLabel.setText("VECTOR DETAIL: " + v + "%"); queuePreview(); }); root.addView(qualityBar);
        contourLabel = label("CONTOUR PRIORITY: 78%"); root.addView(contourLabel);
        contourBar = slider(78, v -> { contourLabel.setText("CONTOUR PRIORITY: " + v + "%"); queuePreview(); }); root.addView(contourBar);
        fillLabel = label("TONAL / MICRO-STIPPLE: 52%"); root.addView(fillLabel);
        fillBar = slider(52, v -> { fillLabel.setText("TONAL / MICRO-STIPPLE: " + v + "%"); queuePreview(); }); root.addView(fillBar);

        root.addView(section("RESONANT LATTICE"));
        bankSpinner = spinner(new String[]{
                "81 FAMILY · 13.5 Hz phase grid · primary 81 Hz",
                "BAGUA 64 · 16 Hz phase grid · primary 64 Hz",
                "SEVENFOLD 49 · 24.5 Hz phase grid · primary 49 Hz",
                "RAW VECTOR XY · no frequency lattice"
        }, 0, true);
        root.addView(bankSpinner);
        resonanceLabel = label("BANK RESONANCE: 32%"); root.addView(resonanceLabel);
        resonanceBar = slider(32, v -> { resonanceLabel.setText("BANK RESONANCE: " + v + "%"); queuePreview(); }); root.addView(resonanceBar);
        smoothLabel = label("BAND-LIMIT / SMOOTHING: 20%"); root.addView(smoothLabel);
        smoothBar = slider(20, v -> { smoothLabel.setText("BAND-LIMIT / SMOOTHING: " + v + "%"); queuePreview(); }); root.addView(smoothBar);

        gammaLabel = label("IMAGE GAMMA: 0.90"); root.addView(gammaLabel);
        gammaBar = new SeekBar(this); gammaBar.setMax(180); gammaBar.setProgress(50);
        gammaBar.setOnSeekBarChangeListener(change((b, v) -> { gammaLabel.setText(String.format(Locale.US, "IMAGE GAMMA: %.2f", gamma())); queuePreview(); }));
        root.addView(gammaBar);

        temporalBox = new CheckBox(this);
        temporalBox.setText("Temporal lock for GIF / video"); temporalBox.setTextColor(Color.rgb(205, 228, 216)); temporalBox.setChecked(true);
        temporalBox.setOnCheckedChangeListener((b, v) -> queuePreview()); root.addView(temporalBox);
        invertBox = new CheckBox(this);
        invertBox.setText("Invert luminance"); invertBox.setTextColor(Color.rgb(205, 228, 216));
        invertBox.setOnCheckedChangeListener((b, v) -> queuePreview()); root.addView(invertBox);

        root.addView(section("VIRTUAL CRT"));
        persistenceLabel = label("PERSISTENCE: 86%"); root.addView(persistenceLabel);
        persistenceBar = slider(86, v -> { persistenceLabel.setText("PERSISTENCE: " + v + "%"); scope.setPersistence(v); }); root.addView(persistenceBar);
        intensityLabel = label("BEAM INTENSITY: 84%"); root.addView(intensityLabel);
        intensityBar = slider(84, v -> { intensityLabel.setText("BEAM INTENSITY: " + v + "%"); scope.setIntensity(v); }); root.addView(intensityBar);
        bloomLabel = label("BLOOM: 46%"); root.addView(bloomLabel);
        bloomBar = slider(46, v -> { bloomLabel.setText("BLOOM: " + v + "%"); scope.setBloom(v); }); root.addView(bloomBar);
        beamLabel = label("BEAM WIDTH: 36%"); root.addView(beamLabel);
        beamBar = slider(36, v -> { beamLabel.setText("BEAM WIDTH: " + v + "%"); scope.setBeamWidth(v); }); root.addView(beamBar);

        root.addView(section("REAL SCOPE CALIBRATION"));
        xGainLabel = label("X GAIN: 100%"); root.addView(xGainLabel);
        xGainBar = gainBar(xGainLabel, "X GAIN"); root.addView(xGainBar);
        yGainLabel = label("Y GAIN: 100%"); root.addView(yGainLabel);
        yGainBar = gainBar(yGainLabel, "Y GAIN"); root.addView(yGainBar);
        rotationLabel = label("ROTATION: 0.0°"); root.addView(rotationLabel);
        rotationBar = new SeekBar(this); rotationBar.setMax(240); rotationBar.setProgress(120);
        rotationBar.setOnSeekBarChangeListener(change((b, v) -> { rotationLabel.setText(String.format(Locale.US, "ROTATION: %.1f°", rotation())); queuePreview(); }));
        root.addView(rotationBar);

        LinearLayout tests = row();
        Button circle = button("CAL CIRCLE"); Button grid = button("CAL GRID"); Button clear = button("CLEAR CRT");
        tests.addView(circle, weight()); tests.addView(grid, weight()); tests.addView(clear, weight()); root.addView(tests);
        circle.setOnClickListener(v -> showTest(true)); grid.setOnClickListener(v -> showTest(false)); clear.setOnClickListener(v -> scope.clearPhosphor());

        root.addView(section("EXPORT"));
        exportSpinner = spinner(new String[]{"32-bit float WAV · best XY precision", "24-bit PCM WAV · compatibility"}, 0, false);
        root.addView(exportSpinner);

        TextView note = label("LEFT = X   •   RIGHT = Y   •   no raster sweep\n81 mode repeats the structural skeleton 6× inside a 13.5 Hz phase-closing supercycle. Bagua repeats 4× on 16 Hz. Sevenfold repeats 2× on 24.5 Hz. Tonal detail rotates inside those repeated skeleton cells.");
        note.setTextColor(Color.rgb(132, 166, 151)); note.setTextSize(11.5f); root.addView(note);

        status = new TextView(this);
        status.setTextColor(Color.rgb(193, 255, 218)); status.setTextSize(12.5f); status.setPadding(0, dp(7), 0, dp(22));
        root.addView(status);

        ScrollView scroll = new ScrollView(this); scroll.addView(root); return scroll;
    }

    private TextView section(String s) { TextView t = label(s); t.setTextColor(Color.rgb(155, 248, 187)); t.setTextSize(13f); t.setPadding(0, dp(10), 0, dp(3)); return t; }
    private TextView label(String s) { TextView t = new TextView(this); t.setText(s); t.setTextColor(Color.rgb(190, 218, 205)); t.setTextSize(12f); t.setPadding(0, dp(4), 0, dp(2)); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextSize(10.5f); return b; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(3), 0, dp(6)); return r; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f); p.setMargins(dp(2), 0, dp(2), 0); return p; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private interface Slide { void go(int value); }
    private SeekBar slider(int value, Slide action) { SeekBar b = new SeekBar(this); b.setMax(100); b.setProgress(value); b.setOnSeekBarChangeListener(change((bar, v) -> action.go(v))); return b; }
    private interface Changed { void go(SeekBar b, int value); }
    private SeekBar.OnSeekBarChangeListener change(Changed c) { return new SeekBar.OnSeekBarChangeListener() {
        @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) c.go(b, p); }
        @Override public void onStartTrackingTouch(SeekBar b) {}
        @Override public void onStopTrackingTouch(SeekBar b) {}
    }; }

    private Spinner spinner(String[] values, int selected, boolean preview) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(a); s.setSelection(selected);
        if (preview) s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { queuePreview(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        return s;
    }

    private SeekBar gainBar(TextView target, String prefix) {
        SeekBar b = new SeekBar(this); b.setMax(100); b.setProgress(50);
        b.setOnSeekBarChangeListener(change((bar, v) -> { target.setText(prefix + ": " + (50 + v) + "%"); queuePreview(); })); return b;
    }

    private void openMedia() {
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"}); startActivityForResult(i, REQ_OPEN);
    }

    private void chooseSave() {
        if (sourceKind == KIND_NONE) { toast("Load media first."); return; }
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, "oscivision_v5_vector_lattice_" + System.currentTimeMillis() + ".wav"); startActivityForResult(i, REQ_SAVE);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_OPEN) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
            loadSource(u);
        } else if (req == REQ_SAVE) export(u);
    }

    private void loadSource(Uri u) {
        sourceUri = u; status.setText("Decoding media…");
        worker.submit(() -> {
            try {
                releaseSource(); ContentResolver cr = getContentResolver(); String mime = cr.getType(u); String lower = u.toString().toLowerCase(Locale.US);
                if (mime != null && mime.startsWith("video/")) {
                    sourceKind = KIND_VIDEO; retriever = new MediaMetadataRetriever(); retriever.setDataSource(this, u);
                    durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 1000L);
                    sourceWidth = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 640);
                    sourceHeight = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 480);
                    Bitmap first = getVideoFrame(0); if (first == null) throw new IllegalStateException("Video decoder returned no frame");
                    runOnUiThread(() -> { sourcePreview.setImageBitmap(first); applyPreset(2); compilePreview(first, 0); });
                } else if ((mime != null && mime.equals("image/gif")) || lower.endsWith(".gif")) {
                    sourceKind = KIND_GIF; gifBytes = readAll(cr.openInputStream(u)); gifMovie = Movie.decodeByteArray(gifBytes, 0, gifBytes.length);
                    if (gifMovie == null) {
                        sourceKind = KIND_IMAGE; stillBitmap = decodeBitmap(u); if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                        sourceWidth = stillBitmap.getWidth(); sourceHeight = stillBitmap.getHeight(); durationMs = 6000L;
                        runOnUiThread(() -> { sourcePreview.setImageBitmap(stillBitmap); applyPreset(0); compilePreview(stillBitmap, 0); });
                    } else {
                        durationMs = gifMovie.duration() > 0 ? gifMovie.duration() : 1000L; sourceWidth = gifMovie.width(); sourceHeight = gifMovie.height();
                        Bitmap first = getGifFrame(0); runOnUiThread(() -> { sourcePreview.setImageBitmap(first); applyPreset(2); fpsSpinner.setSelection(3); compilePreview(first, 0); });
                    }
                } else {
                    sourceKind = KIND_IMAGE; stillBitmap = decodeBitmap(u); if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                    sourceWidth = stillBitmap.getWidth(); sourceHeight = stillBitmap.getHeight(); durationMs = 6000L;
                    runOnUiThread(() -> { sourcePreview.setImageBitmap(stillBitmap); applyPreset(0); compilePreview(stillBitmap, 0); });
                }
            } catch (Throwable t) { runOnUiThread(() -> status.setText("Load error: " + safe(t))); }
        });
    }

    private Bitmap decodeBitmap(Uri u) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), u);
            return ImageDecoder.decodeBitmap(src, (d, info, source) -> d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        }
        try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(u))) { return BitmapFactory.decodeStream(in); }
    }

    private V5Engine.Settings settings() {
        V5Engine.Settings s = new V5Engine.Settings();
        s.sampleRate = sampleRate(); s.fps = fps(); s.profile = profileSpinner == null ? 0 : profileSpinner.getSelectedItemPosition();
        s.toneBands = toneBands(); s.bank = bankSpinner == null ? 0 : bankSpinner.getSelectedItemPosition();
        s.quality = qualityBar == null ? 98 : qualityBar.getProgress();
        s.contourStrength = contourBar == null ? .78f : contourBar.getProgress() / 100f;
        s.fillStrength = fillBar == null ? .52f : fillBar.getProgress() / 100f;
        s.resonance = resonanceBar == null ? .32f : resonanceBar.getProgress() / 100f;
        s.smoothness = smoothBar == null ? .20f : smoothBar.getProgress() / 100f;
        s.gamma = gamma(); s.temporal = temporalBox == null || temporalBox.isChecked(); s.invert = invertBox != null && invertBox.isChecked();
        s.xGain = xGainBar == null ? 1f : (50f + xGainBar.getProgress()) / 100f;
        s.yGain = yGainBar == null ? 1f : (50f + yGainBar.getProgress()) / 100f; s.rotationDeg = rotation();
        return s;
    }

    private int sampleRate() { int p = sampleSpinner == null ? 0 : sampleSpinner.getSelectedItemPosition(); return p == 1 ? 96000 : (p == 2 ? 48000 : 192000); }
    private int fps() { if (fpsSpinner == null) return 8; switch (fpsSpinner.getSelectedItemPosition()) { case 0:return 8; case 1:return 12; case 2:return 15; case 3:return 24; case 4:return 30; default:return 60; } }
    private int toneBands() { if (bandsSpinner == null) return 8; switch (bandsSpinner.getSelectedItemPosition()) { case 0:return 4; case 1:return 6; case 2:return 8; case 3:return 10; default:return 12; } }
    private float gamma() { return gammaBar == null ? .90f : .40f + gammaBar.getProgress() / 100f; }
    private float rotation() { return rotationBar == null ? 0f : (rotationBar.getProgress() - 120) / 10f; }

    private void queuePreview() {
        if (sourceKind != KIND_IMAGE || stillBitmap == null || playing.get()) return;
        if (!previewQueued.compareAndSet(false, true)) return;
        int gen = previewGeneration.incrementAndGet();
        V5Engine.Settings s = settings(); Bitmap b = stillBitmap;
        worker.submit(() -> {
            try { SystemClock.sleep(35); V5Engine.Result r = V5Engine.compile(b, s, 0); if (gen == previewGeneration.get()) runOnUiThread(() -> showResult(r, false)); }
            catch (Throwable t) { runOnUiThread(() -> status.setText("Compile error: " + safe(t))); }
            finally { previewQueued.set(false); }
        });
    }

    private void compilePreview(Bitmap b, long frame) {
        if (b == null) return; V5Engine.Settings s = settings(); int gen = previewGeneration.incrementAndGet();
        worker.submit(() -> {
            try { V5Engine.Result r = V5Engine.compile(b, s, frame); if (gen == previewGeneration.get()) runOnUiThread(() -> showResult(r, false)); }
            catch (Throwable t) { runOnUiThread(() -> status.setText("Compile error: " + safe(t))); }
        });
    }

    private void showResult(V5Engine.Result r, boolean live) {
        lastResult = r; scope.setResult(r);
        String lattice = r.primaryHz > 0 ? String.format(Locale.US, "PRIMARY %.1f Hz · GRID %.1f Hz", r.primaryHz, r.latticeHz) : "RAW VECTOR XY";
        String audioInfo = live ? " · " + audio.sampleRate() + " Hz · " + audio.routeName() + " · underruns " + audio.underruns() : "";
        status.setText(lattice + "\n" + r.samplesPerLoop + " samples/loop · " + r.contourPaths + " vector paths · " + r.skeletonPaths + " repeated skeletons · " + r.fillCenters + " tonal centers · flybacks " + r.flybacks + audioInfo);
    }

    private void togglePlayback() { if (sourceKind == KIND_NONE) { toast("Load media first."); return; } if (playing.get()) stopPlayback(); else startPlayback(); }

    private void startPlayback() {
        stopPlayback(); V5Engine.Settings request = settings(); int rate;
        try { rate = audio.start(request.sampleRate); } catch (Throwable t) { status.setText("Audio start error: " + safe(t)); return; }
        V5Engine.Settings s = request.copy(); s.sampleRate = rate; playing.set(true); playButton.setText("STOP");
        if (sourceKind == KIND_IMAGE) {
            worker.submit(() -> {
                try { V5Engine.Result r = V5Engine.compile(stillBitmap, s, 0); if (!playing.get()) return; audio.setFrame(r.xy); runOnUiThread(() -> showResult(r, true)); }
                catch (Throwable t) { runOnUiThread(() -> status.setText("Playback compile error: " + safe(t))); }
            });
            return;
        }
        animationThread = new Thread(() -> {
            long start = SystemClock.elapsedRealtime(); long fi = 0; long period = Math.max(1, Math.round(1000.0 / s.fps));
            while (playing.get()) {
                long target = fi * period, elapsed = SystemClock.elapsedRealtime() - start;
                if (elapsed < target) { SystemClock.sleep(Math.min(5, target - elapsed)); continue; }
                if (elapsed - target > period * 2) fi = Math.max(fi, elapsed / period);
                long mt = durationMs > 0 ? (fi * period) % durationMs : fi * period;
                try {
                    Bitmap f = sourceKind == KIND_VIDEO ? getVideoFrame(mt) : getGifFrame(mt);
                    if (f != null) {
                        V5Engine.Result r = V5Engine.compile(f, s, fi); audio.setFrame(r.xy);
                        Bitmap shown = f; runOnUiThread(() -> { if (sourceBox.isChecked()) sourcePreview.setImageBitmap(shown); showResult(r, true); });
                    }
                } catch (Throwable t) { runOnUiThread(() -> status.setText("Frame error: " + safe(t))); }
                fi++;
                if (fi > 100000000L) { fi = 0; start = SystemClock.elapsedRealtime(); }
            }
        }, "OsciVisionV5Video");
        animationThread.setPriority(Thread.NORM_PRIORITY + 1); animationThread.start();
    }

    private void stopPlayback() {
        playing.set(false); if (animationThread != null) { animationThread.interrupt(); animationThread = null; }
        audio.stop(); if (playButton != null) playButton.setText("PLAY XY");
    }

    private Bitmap getVideoFrame(long ms) {
        if (retriever == null) return null; long us = Math.max(0, ms) * 1000L;
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                int max = 640, w = sourceWidth <= 0 ? max : sourceWidth, h = sourceHeight <= 0 ? max : sourceHeight;
                float sc = Math.min(1f, max / (float) Math.max(w, h));
                Bitmap b = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, Math.max(2, Math.round(w * sc)), Math.max(2, Math.round(h * sc)));
                if (b != null) return b;
            }
        } catch (Throwable ignored) {}
        return retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
    }

    private Bitmap getGifFrame(long ms) {
        if (gifMovie == null) return null; int max = 640, w = Math.max(1, gifMovie.width()), h = Math.max(1, gifMovie.height());
        float sc = Math.min(1f, max / (float) Math.max(w, h));
        Bitmap out = Bitmap.createBitmap(Math.max(2, Math.round(w * sc)), Math.max(2, Math.round(h * sc)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out); c.drawColor(Color.BLACK); c.scale(sc, sc);
        synchronized (this) { gifMovie.setTime((int) (ms % Math.max(1L, durationMs))); gifMovie.draw(c, 0, 0); }
        return out;
    }

    private void export(Uri dest) {
        if (sourceKind == KIND_NONE) return; V5Engine.Settings s = settings(); boolean float32 = exportSpinner.getSelectedItemPosition() == 0;
        status.setText("Rendering v5 vector-lattice WAV…");
        worker.submit(() -> {
            File tmp = new File(getCacheDir(), "oscivision_v5_" + System.currentTimeMillis() + ".wav");
            try (V4Audio.WavWriter writer = V4Audio.createWriter(tmp, s.sampleRate, float32)) {
                long dur = sourceKind == KIND_IMAGE ? 6000L : Math.max(1L, durationMs);
                if (sourceKind == KIND_IMAGE) {
                    V5Engine.Result r = V5Engine.compile(stillBitmap, s, 0); writeRepeated(writer, r.xy, Math.round(s.sampleRate * dur / 1000f));
                } else {
                    int frames = Math.max(1, (int) Math.ceil(dur * s.fps / 1000.0));
                    int pairsPerFrame = Math.max(1, Math.round(s.sampleRate / (float) s.fps));
                    for (int i = 0; i < frames; i++) {
                        long t = Math.min(dur - 1, Math.round(i * 1000.0 / s.fps));
                        Bitmap f = sourceKind == KIND_VIDEO ? getVideoFrame(t) : getGifFrame(t); if (f == null) continue;
                        V5Engine.Result r = V5Engine.compile(f, s, i); writeRepeated(writer, r.xy, pairsPerFrame);
                        if (i % Math.max(1, s.fps / 2) == 0) { final int pct = Math.min(99, Math.round(100f * i / frames)); runOnUiThread(() -> status.setText("Rendering v5 WAV… " + pct + "%")); }
                    }
                }
                try (InputStream in = new BufferedInputStream(new FileInputStream(tmp)); OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(dest, "w"))) {
                    byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                runOnUiThread(() -> status.setText("Saved · " + (float32 ? "32-bit float" : "24-bit PCM") + " · stereo XY · " + s.sampleRate + " Hz"));
            } catch (Throwable t) { runOnUiThread(() -> status.setText("Export error: " + safe(t))); }
            finally { try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) {} }
        });
    }

    private static void writeRepeated(V4Audio.WavWriter writer, float[] tile, int pairs) throws Exception {
        if (tile == null || tile.length < 2 || pairs <= 0) return; int tilePairs = tile.length / 2;
        while (pairs >= tilePairs) { writer.write(tile); pairs -= tilePairs; }
        if (pairs > 0) writer.write(Arrays.copyOf(tile, pairs * 2));
    }

    private void showTest(boolean circle) {
        V5Engine.Settings s = settings(); int n = s.bank < 3 ? Math.max(2048, (int) Math.round(s.sampleRate / (s.bank == 0 ? 13.5 : (s.bank == 1 ? 16.0 : 24.5)))) : Math.max(2048, s.sampleRate / Math.max(1, s.fps));
        float[] xy = circle ? V5Engine.circle(s, n) : V5Engine.grid(s, n); scope.setTrace(xy, circle ? "CAL CIRCLE" : "CAL GRID"); if (playing.get()) audio.setFrame(xy);
    }

    private void autoTune() { applyPreset(sourceKind == KIND_VIDEO || sourceKind == KIND_GIF ? 2 : profileSpinner.getSelectedItemPosition()); queuePreview(); }

    private void applyPreset(int profile) {
        profile = Math.max(0, Math.min(3, profile)); profileSpinner.setSelection(profile); sampleSpinner.setSelection(0);
        if (profile == 0) {
            fpsSpinner.setSelection(0); bandsSpinner.setSelection(3); qualityBar.setProgress(100); contourBar.setProgress(84); fillBar.setProgress(56); resonanceBar.setProgress(32); smoothBar.setProgress(18); gammaBar.setProgress(50); bankSpinner.setSelection(0); persistenceBar.setProgress(90); intensityBar.setProgress(86); bloomBar.setProgress(48); beamBar.setProgress(30);
        } else if (profile == 1) {
            fpsSpinner.setSelection(0); bandsSpinner.setSelection(4); qualityBar.setProgress(100); contourBar.setProgress(92); fillBar.setProgress(46); resonanceBar.setProgress(28); smoothBar.setProgress(14); gammaBar.setProgress(45); bankSpinner.setSelection(0); persistenceBar.setProgress(91); intensityBar.setProgress(88); bloomBar.setProgress(42); beamBar.setProgress(26);
        } else if (profile == 2) {
            fpsSpinner.setSelection(4); bandsSpinner.setSelection(1); qualityBar.setProgress(76); contourBar.setProgress(70); fillBar.setProgress(34); resonanceBar.setProgress(24); smoothBar.setProgress(24); gammaBar.setProgress(50); bankSpinner.setSelection(2); persistenceBar.setProgress(84); intensityBar.setProgress(82); bloomBar.setProgress(42); beamBar.setProgress(34);
        } else {
            fpsSpinner.setSelection(1); bandsSpinner.setSelection(2); qualityBar.setProgress(100); contourBar.setProgress(100); fillBar.setProgress(0); resonanceBar.setProgress(24); smoothBar.setProgress(12); gammaBar.setProgress(60); bankSpinner.setSelection(1); persistenceBar.setProgress(88); intensityBar.setProgress(90); bloomBar.setProgress(36); beamBar.setProgress(20);
        }
        scope.setPersistence(persistenceBar.getProgress()); scope.setIntensity(intensityBar.getProgress()); scope.setBloom(bloomBar.getProgress()); scope.setBeamWidth(beamBar.getProgress());
        qualityLabel.setText("VECTOR DETAIL: " + qualityBar.getProgress() + "%"); contourLabel.setText("CONTOUR PRIORITY: " + contourBar.getProgress() + "%"); fillLabel.setText("TONAL / MICRO-STIPPLE: " + fillBar.getProgress() + "%"); resonanceLabel.setText("BANK RESONANCE: " + resonanceBar.getProgress() + "%"); smoothLabel.setText("BAND-LIMIT / SMOOTHING: " + smoothBar.getProgress() + "%"); gammaLabel.setText(String.format(Locale.US, "IMAGE GAMMA: %.2f", gamma()));
    }

    private void releaseSource() {
        stillBitmap = null; gifMovie = null; gifBytes = null; durationMs = 0; sourceWidth = sourceHeight = 0;
        if (retriever != null) { try { retriever.release(); } catch (Throwable ignored) {} retriever = null; }
    }

    @Override protected void onDestroy() { stopPlayback(); releaseSource(); worker.shutdownNow(); super.onDestroy(); }

    private static byte[] readAll(InputStream in) throws Exception { if (in == null) return new byte[0]; try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[65536]; int n; while ((n = x.read(b)) > 0) out.write(b, 0, n); return out.toByteArray(); } }
    private static long parseLong(String s, long f) { try { return Long.parseLong(s); } catch (Throwable t) { return f; } }
    private static int parseInt(String s, int f) { try { return Integer.parseInt(s); } catch (Throwable t) { return f; } }
    private static String safe(Throwable t) { if (t == null) return "unknown"; String m = t.getMessage(); return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
