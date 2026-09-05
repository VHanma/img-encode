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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** OsciVision Ultra v4: closed-loop full-field XY image / GIF / video instrument. */
public class UltraV4Activity extends Activity {
    private static final int REQ_OPEN = 4101;
    private static final int REQ_SAVE = 4102;
    private static final int KIND_NONE = 0;
    private static final int KIND_IMAGE = 1;
    private static final int KIND_VIDEO = 2;
    private static final int KIND_GIF = 3;

    private V4ScopeView scope;
    private ImageView sourcePreview;
    private TextView status;
    private TextView qualityLabel, harmonyLabel, auraLabel, gammaLabel, residualLabel;
    private TextView persistenceLabel, intensityLabel, bloomLabel, lowLiftLabel, smoothLabel;
    private TextView xGainLabel, yGainLabel, rotationLabel;
    private Spinner sampleSpinner, fpsSpinner, profileSpinner, residualSpinner, bankSpinner, exportSpinner;
    private SeekBar qualityBar, harmonyBar, auraBar, gammaBar, persistenceBar, intensityBar, bloomBar;
    private SeekBar lowLiftBar, smoothBar, xGainBar, yGainBar, rotationBar;
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
    private volatile V4Engine.Result lastResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        status.setText("Load media. v4 reconstructs, simulates phosphor density, measures missing detail, then spends later passes on the residual.");
    }

    private View buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(2, 5, 8));

        TextView title = new TextView(this);
        title.setText("OSCIVISION ULTRA v4");
        title.setTextColor(Color.rgb(185, 255, 212));
        title.setTextSize(25f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = new TextView(this);
        sub.setText("CLOSED-LOOP FULL-FIELD XY INSTRUMENT");
        sub.setTextColor(Color.rgb(120, 215, 165));
        sub.setTextSize(12f);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(3), 0, dp(7));
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        sourcePreview = new ImageView(this);
        sourcePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        sourcePreview.setBackgroundColor(Color.BLACK);
        sourcePreview.setVisibility(View.GONE);
        root.addView(sourcePreview, new LinearLayout.LayoutParams(-1, dp(150)));

        scope = new V4ScopeView(this);
        LinearLayout.LayoutParams scopeParams = new LinearLayout.LayoutParams(-1, dp(430));
        scopeParams.setMargins(0, dp(5), 0, dp(8));
        root.addView(scope, scopeParams);

        LinearLayout actions = row();
        Button load = button("LOAD");
        playButton = button("PLAY XY");
        Button auto = button("AUTO TUNE");
        Button save = button("EXPORT WAV");
        actions.addView(load, weight());
        actions.addView(playButton, weight());
        actions.addView(auto, weight());
        actions.addView(save, weight());
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        load.setOnClickListener(v -> openMedia());
        playButton.setOnClickListener(v -> togglePlayback());
        auto.setOnClickListener(v -> autoTune());
        save.setOnClickListener(v -> chooseSave());

        sourceBox = new CheckBox(this);
        sourceBox.setText("Show source reference above scope");
        sourceBox.setTextColor(Color.rgb(200, 225, 214));
        sourceBox.setOnCheckedChangeListener((b, checked) -> sourcePreview.setVisibility(checked ? View.VISIBLE : View.GONE));
        root.addView(sourceBox, new LinearLayout.LayoutParams(-1, -2));

        root.addView(section("SIGNAL / DETAIL"));
        root.addView(label("OUTPUT SAMPLE RATE"));
        sampleSpinner = spinner(new String[]{"192000 Hz · max density", "96000 Hz", "48000 Hz"}, 0, true);
        root.addView(sampleSpinner, new LinearLayout.LayoutParams(-1, -2));

        root.addView(label("PERSISTENCE FRAME RATE"));
        fpsSpinner = spinner(new String[]{
                "8 fps · maximum still detail",
                "12 fps · extreme detail",
                "15 fps · high detail",
                "24 fps · cinematic balance",
                "30 fps · video balance",
                "60 fps · motion priority"
        }, 1, true);
        root.addView(fpsSpinner, new LinearLayout.LayoutParams(-1, -2));

        root.addView(label("RECONSTRUCTION PROFILE"));
        profileSpinner = spinner(new String[]{
                "ULTRA PHOTO · CLOSED LOOP",
                "PORTRAIT / FINE DETAIL",
                "VIDEO STABLE",
                "EDGE / LINE ART"
        }, 0, true);
        root.addView(profileSpinner, new LinearLayout.LayoutParams(-1, -2));

        qualityLabel = label("DETAIL / PATH QUALITY: 97%");
        root.addView(qualityLabel);
        qualityBar = slider(97, v -> {
            qualityLabel.setText("DETAIL / PATH QUALITY: " + v + "%");
            queuePreview();
        });
        root.addView(qualityBar, new LinearLayout.LayoutParams(-1, -2));

        residualLabel = label("CLOSED-LOOP RESIDUAL PASSES: 3");
        root.addView(residualLabel);
        residualSpinner = spinner(new String[]{"1 pass · fast", "2 passes", "3 passes · strong", "4 passes · maximum"}, 2, true);
        root.addView(residualSpinner, new LinearLayout.LayoutParams(-1, -2));

        root.addView(section("HARMONIC FIELD"));
        root.addView(label("HARMONIC BANK"));
        bankSpinner = spinner(new String[]{
                "81 LATTICE · 81 / 121.5 / 162 / 243 / 324 / 486 / 729",
                "BAGUA 64 · 64 / 80 / 96 / 128 / 160 / 192 / 256 / 320 / 384 / 512",
                "SEVENFOLD 49 · 49 / 73.5 / 98 / 122.5 / 147 / 196 / 245 / 343 / 490 / 686",
                "RAW XY · harmonic shaping off"
        }, 0, true);
        root.addView(bankSpinner, new LinearLayout.LayoutParams(-1, -2));

        harmonyLabel = label("HARMONIC TIMING: 34%");
        root.addView(harmonyLabel);
        harmonyBar = slider(34, v -> {
            harmonyLabel.setText("HARMONIC TIMING: " + v + "%");
            queuePreview();
        });
        root.addView(harmonyBar, new LinearLayout.LayoutParams(-1, -2));

        auraLabel = label("HARMONIC MICRO-AURA: 16%");
        root.addView(auraLabel);
        auraBar = slider(16, v -> {
            auraLabel.setText("HARMONIC MICRO-AURA: " + v + "%");
            queuePreview();
        });
        root.addView(auraBar, new LinearLayout.LayoutParams(-1, -2));

        root.addView(section("IMAGE / CRT RESPONSE"));
        gammaLabel = label("LUMA GAMMA: 0.95");
        root.addView(gammaLabel);
        gammaBar = new SeekBar(this);
        gammaBar.setMax(180);
        gammaBar.setProgress(55);
        gammaBar.setOnSeekBarChangeListener(change((b, v) -> {
            gammaLabel.setText(String.format(Locale.US, "LUMA GAMMA: %.2f", gamma()));
            queuePreview();
        }));
        root.addView(gammaBar, new LinearLayout.LayoutParams(-1, -2));

        smoothLabel = label("LOCAL CURVE SMOOTHING: 28%");
        root.addView(smoothLabel);
        smoothBar = slider(28, v -> {
            smoothLabel.setText("LOCAL CURVE SMOOTHING: " + v + "%");
            queuePreview();
        });
        root.addView(smoothBar, new LinearLayout.LayoutParams(-1, -2));

        lowLiftLabel = label("AC-LINK LOW-FREQUENCY LIFT: 12%");
        root.addView(lowLiftLabel);
        lowLiftBar = slider(12, v -> {
            lowLiftLabel.setText("AC-LINK LOW-FREQUENCY LIFT: " + v + "%");
            queuePreview();
        });
        root.addView(lowLiftBar, new LinearLayout.LayoutParams(-1, -2));

        temporalBox = new CheckBox(this);
        temporalBox.setText("Temporal anchor for GIF / video");
        temporalBox.setTextColor(Color.rgb(205, 226, 215));
        temporalBox.setChecked(true);
        temporalBox.setOnCheckedChangeListener((b, v) -> queuePreview());
        root.addView(temporalBox, new LinearLayout.LayoutParams(-1, -2));

        invertBox = new CheckBox(this);
        invertBox.setText("Invert luminance polarity");
        invertBox.setTextColor(Color.rgb(205, 226, 215));
        invertBox.setOnCheckedChangeListener((b, v) -> queuePreview());
        root.addView(invertBox, new LinearLayout.LayoutParams(-1, -2));

        persistenceLabel = label("SOFTWARE PHOSPHOR PERSISTENCE: 78%");
        root.addView(persistenceLabel);
        persistenceBar = slider(78, v -> {
            persistenceLabel.setText("SOFTWARE PHOSPHOR PERSISTENCE: " + v + "%");
            scope.setPersistence(v);
        });
        root.addView(persistenceBar, new LinearLayout.LayoutParams(-1, -2));

        intensityLabel = label("BEAM INTENSITY: 72%");
        root.addView(intensityLabel);
        intensityBar = slider(72, v -> {
            intensityLabel.setText("BEAM INTENSITY: " + v + "%");
            scope.setIntensity(v);
        });
        root.addView(intensityBar, new LinearLayout.LayoutParams(-1, -2));

        bloomLabel = label("CRT BLOOM: 58%");
        root.addView(bloomLabel);
        bloomBar = slider(58, v -> {
            bloomLabel.setText("CRT BLOOM: " + v + "%");
            scope.setBloom(v);
        });
        root.addView(bloomBar, new LinearLayout.LayoutParams(-1, -2));

        root.addView(section("REAL-SCOPE CALIBRATION"));
        xGainLabel = label("X GAIN: 100%");
        root.addView(xGainLabel);
        xGainBar = gainBar(xGainLabel, "X GAIN");
        root.addView(xGainBar, new LinearLayout.LayoutParams(-1, -2));

        yGainLabel = label("Y GAIN: 100%");
        root.addView(yGainLabel);
        yGainBar = gainBar(yGainLabel, "Y GAIN");
        root.addView(yGainBar, new LinearLayout.LayoutParams(-1, -2));

        rotationLabel = label("ROTATION: 0.0°");
        root.addView(rotationLabel);
        rotationBar = new SeekBar(this);
        rotationBar.setMax(240);
        rotationBar.setProgress(120);
        rotationBar.setOnSeekBarChangeListener(change((b, v) -> {
            rotationLabel.setText(String.format(Locale.US, "ROTATION: %.1f°", rotation()));
            queuePreview();
        }));
        root.addView(rotationBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tests = row();
        Button grid = button("CAL GRID");
        Button circle = button("CAL CIRCLE");
        tests.addView(grid, weight());
        tests.addView(circle, weight());
        root.addView(tests, new LinearLayout.LayoutParams(-1, -2));
        grid.setOnClickListener(v -> showTest(false));
        circle.setOnClickListener(v -> showTest(true));

        root.addView(section("EXPORT"));
        exportSpinner = spinner(new String[]{"24-bit PCM · wide compatibility", "32-bit float · maximum waveform precision"}, 1, false);
        root.addView(exportSpinner, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("LEFT = X  •  RIGHT = Y  •  full-field / non-raster\nMatch % is the engine's internal density-fit estimate, not a claim that every external oscilloscope or audio route will reproduce that percentage. Physical results still depend on bandwidth, coupling, gain, and scope persistence.");
        note.setTextColor(Color.rgb(132, 160, 150));
        note.setTextSize(11.5f);
        note.setPadding(0, dp(7), 0, dp(7));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(190, 255, 212));
        status.setTextSize(13f);
        status.setPadding(0, dp(5), 0, dp(20));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView section(String text) {
        TextView t = label(text);
        t.setTextColor(Color.rgb(155, 245, 185));
        t.setTextSize(13f);
        t.setPadding(0, dp(11), 0, dp(3));
        return t;
    }

    private interface SliderAction { void changed(int value); }
    private SeekBar slider(int value, SliderAction action) {
        SeekBar b = new SeekBar(this);
        b.setMax(100);
        b.setProgress(value);
        b.setOnSeekBarChangeListener(change((bar, v) -> action.changed(v)));
        return b;
    }

    private interface ChangeAction { void changed(SeekBar bar, int value); }
    private SeekBar.OnSeekBarChangeListener change(ChangeAction action) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) action.changed(seekBar, progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar gainBar(TextView target, String prefix) {
        SeekBar b = new SeekBar(this);
        b.setMax(100); // 50..150
        b.setProgress(50);
        b.setOnSeekBarChangeListener(change((bar, v) -> {
            target.setText(prefix + ": " + (50 + v) + "%");
            queuePreview();
        }));
        return b;
    }

    private Spinner spinner(String[] values, int selected, boolean previewOnChange) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
        if (previewOnChange) {
            s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                private boolean first = true;
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (first) { first = false; return; }
                    if (s == residualSpinner && residualLabel != null) residualLabel.setText("CLOSED-LOOP RESIDUAL PASSES: " + (position + 1));
                    queuePreview();
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        return s;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, 0, 0, dp(7));
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10.5f);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(190, 215, 205));
        t.setTextSize(12f);
        t.setPadding(0, dp(5), 0, dp(2));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void openMedia() {
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        startActivityForResult(i, REQ_OPEN);
    }

    private void chooseSave() {
        if (sourceKind == KIND_NONE) {
            toast("Load media first.");
            return;
        }
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        String depth = exportSpinner.getSelectedItemPosition() == 1 ? "32f" : "24bit";
        i.putExtra(Intent.EXTRA_TITLE, "oscivision_v4_" + depth + "_xy_" + System.currentTimeMillis() + ".wav");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) {}
            loadSource(uri);
        } else if (requestCode == REQ_SAVE) {
            export(uri);
        }
    }

    private void loadSource(Uri uri) {
        sourceUri = uri;
        status.setText("Decoding media…");
        previewGeneration.incrementAndGet();
        worker.submit(() -> {
            try {
                releaseSource();
                ContentResolver cr = getContentResolver();
                String mime = cr.getType(uri);
                String lower = uri.toString().toLowerCase(Locale.US);
                Bitmap first;
                if (mime != null && mime.startsWith("video/")) {
                    sourceKind = KIND_VIDEO;
                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(this, uri);
                    durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 1000L);
                    sourceWidth = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 640);
                    sourceHeight = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 480);
                    first = getVideoFrame(0L, 720);
                    if (first == null) throw new IllegalStateException("Video decoder returned no frame");
                } else if ((mime != null && mime.equals("image/gif")) || lower.endsWith(".gif")) {
                    sourceKind = KIND_GIF;
                    gifBytes = readAll(cr.openInputStream(uri));
                    gifMovie = Movie.decodeByteArray(gifBytes, 0, gifBytes.length);
                    if (gifMovie == null) {
                        sourceKind = KIND_IMAGE;
                        stillBitmap = decodeBitmap(uri);
                        if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                        sourceWidth = stillBitmap.getWidth();
                        sourceHeight = stillBitmap.getHeight();
                        durationMs = 6000L;
                        first = stillBitmap;
                    } else {
                        durationMs = gifMovie.duration() > 0 ? gifMovie.duration() : 1000L;
                        sourceWidth = gifMovie.width();
                        sourceHeight = gifMovie.height();
                        first = getGifFrame(0L, 720);
                    }
                } else {
                    sourceKind = KIND_IMAGE;
                    stillBitmap = decodeBitmap(uri);
                    if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                    sourceWidth = stillBitmap.getWidth();
                    sourceHeight = stillBitmap.getHeight();
                    durationMs = 6000L;
                    first = stillBitmap;
                }
                Bitmap preview = first;
                runOnUiThread(() -> {
                    sourcePreview.setImageBitmap(preview);
                    autoTune();
                    status.setText(mediaName() + " · " + sourceWidth + "×" + sourceHeight + " · v4 closed-loop ready");
                });
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Load error: " + safe(t)));
            }
        });
    }

    private Bitmap decodeBitmap(Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        }
        try (InputStream in = new BufferedInputStream(getContentResolver().openInputStream(uri))) {
            return BitmapFactory.decodeStream(in);
        }
    }

    private void autoTune() {
        if (sourceKind == KIND_NONE) {
            sampleSpinner.setSelection(0);
            fpsSpinner.setSelection(1);
            profileSpinner.setSelection(0);
            residualSpinner.setSelection(2);
            qualityBar.setProgress(97);
            return;
        }
        sampleSpinner.setSelection(0);
        temporalBox.setChecked(true);
        bankSpinner.setSelection(0);
        xGainBar.setProgress(50);
        yGainBar.setProgress(50);
        rotationBar.setProgress(120);
        lowLiftBar.setProgress(10);
        persistenceBar.setProgress(82);
        intensityBar.setProgress(78);
        bloomBar.setProgress(54);

        if (sourceKind == KIND_IMAGE) {
            fpsSpinner.setSelection(0);           // 8 fps = 24k XY samples @ 192k
            profileSpinner.setSelection(0);
            residualSpinner.setSelection(3);
            qualityBar.setProgress(100);
            harmonyBar.setProgress(28);
            auraBar.setProgress(12);
            gammaBar.setProgress(50);             // 0.90
            smoothBar.setProgress(18);
        } else {
            fpsSpinner.setSelection(sourceKind == KIND_GIF ? 3 : 4);
            profileSpinner.setSelection(2);
            residualSpinner.setSelection(1);
            qualityBar.setProgress(sourceKind == KIND_GIF ? 93 : 89);
            harmonyBar.setProgress(25);
            auraBar.setProgress(10);
            gammaBar.setProgress(55);
            smoothBar.setProgress(38);
            persistenceBar.setProgress(72);
        }
        updateLabels();
        queuePreview();
    }

    private void updateLabels() {
        qualityLabel.setText("DETAIL / PATH QUALITY: " + qualityBar.getProgress() + "%");
        harmonyLabel.setText("HARMONIC TIMING: " + harmonyBar.getProgress() + "%");
        auraLabel.setText("HARMONIC MICRO-AURA: " + auraBar.getProgress() + "%");
        gammaLabel.setText(String.format(Locale.US, "LUMA GAMMA: %.2f", gamma()));
        residualLabel.setText("CLOSED-LOOP RESIDUAL PASSES: " + (residualSpinner.getSelectedItemPosition() + 1));
        persistenceLabel.setText("SOFTWARE PHOSPHOR PERSISTENCE: " + persistenceBar.getProgress() + "%");
        intensityLabel.setText("BEAM INTENSITY: " + intensityBar.getProgress() + "%");
        bloomLabel.setText("CRT BLOOM: " + bloomBar.getProgress() + "%");
        lowLiftLabel.setText("AC-LINK LOW-FREQUENCY LIFT: " + lowLiftBar.getProgress() + "%");
        smoothLabel.setText("LOCAL CURVE SMOOTHING: " + smoothBar.getProgress() + "%");
        xGainLabel.setText("X GAIN: " + (50 + xGainBar.getProgress()) + "%");
        yGainLabel.setText("Y GAIN: " + (50 + yGainBar.getProgress()) + "%");
        rotationLabel.setText(String.format(Locale.US, "ROTATION: %.1f°", rotation()));
        scope.setPersistence(persistenceBar.getProgress());
        scope.setIntensity(intensityBar.getProgress());
        scope.setBloom(bloomBar.getProgress());
    }

    private V4Engine.Settings settings() {
        V4Engine.Settings s = new V4Engine.Settings();
        s.sampleRate = sampleRate();
        s.fps = fps();
        s.profile = profileSpinner == null ? 0 : profileSpinner.getSelectedItemPosition();
        s.residualPasses = residualSpinner == null ? 3 : residualSpinner.getSelectedItemPosition() + 1;
        s.quality = qualityBar == null ? 97 : qualityBar.getProgress();
        s.bank = bankSpinner == null ? 0 : bankSpinner.getSelectedItemPosition();
        s.harmony = harmonyBar == null ? 0.34f : harmonyBar.getProgress() / 100f;
        s.aura = auraBar == null ? 0.16f : auraBar.getProgress() / 100f;
        if (s.bank == 3) { s.harmony = 0f; s.aura = 0f; }
        s.gamma = gamma();
        s.temporal = temporalBox == null || temporalBox.isChecked();
        s.invert = invertBox != null && invertBox.isChecked();
        s.lowFrequencyLift = lowLiftBar == null ? 0.12f : lowLiftBar.getProgress() / 100f;
        s.smoothness = smoothBar == null ? 0.28f : smoothBar.getProgress() / 100f;
        s.xGain = xGainBar == null ? 1f : (50f + xGainBar.getProgress()) / 100f;
        s.yGain = yGainBar == null ? 1f : (50f + yGainBar.getProgress()) / 100f;
        s.rotationDeg = rotation();
        return s;
    }

    private int sampleRate() {
        if (sampleSpinner == null) return 192000;
        int p = sampleSpinner.getSelectedItemPosition();
        return p == 1 ? 96000 : (p == 2 ? 48000 : 192000);
    }

    private int fps() {
        if (fpsSpinner == null) return 12;
        switch (fpsSpinner.getSelectedItemPosition()) {
            case 0: return 8;
            case 1: return 12;
            case 2: return 15;
            case 3: return 24;
            case 4: return 30;
            default: return 60;
        }
    }

    private float gamma() {
        return gammaBar == null ? 0.95f : 0.40f + gammaBar.getProgress() / 100f;
    }

    private float rotation() {
        return rotationBar == null ? 0f : (rotationBar.getProgress() - 120) / 10f;
    }

    private void queuePreview() {
        if (sourceKind != KIND_IMAGE || stillBitmap == null || playing.get()) return;
        final int generation = previewGeneration.incrementAndGet();
        if (!previewQueued.compareAndSet(false, true)) return;
        worker.submit(() -> {
            try {
                SystemClock.sleep(45L);
                V4Engine.Settings s = settings();
                V4Engine.Result r = V4Engine.compile(stillBitmap, s, 0L);
                if (generation != previewGeneration.get()) return;
                lastResult = r;
                runOnUiThread(() -> displayResult(r, false));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Compile error: " + safe(t)));
            } finally {
                previewQueued.set(false);
                if (generation != previewGeneration.get() && sourceKind == KIND_IMAGE && !playing.get()) queuePreview();
            }
        });
    }

    private void compilePreview(Bitmap bitmap, long frameIndex) {
        if (bitmap == null) return;
        V4Engine.Settings s = settings();
        worker.submit(() -> {
            try {
                V4Engine.Result r = V4Engine.compile(bitmap, s, frameIndex);
                lastResult = r;
                runOnUiThread(() -> displayResult(r, false));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Compile error: " + safe(t)));
            }
        });
    }

    private void displayResult(V4Engine.Result r, boolean live) {
        if (r == null) return;
        scope.setTrace(r.xy);
        String d = String.format(Locale.US, "FIT %.1f%%  PATH %.1f%%  FLY %d  PTS %,d",
                r.matchScore, r.pathScore, r.flybacks, r.points);
        scope.setDiagnostics(d);
        if (!live) status.setText(d + " · grid " + r.grid + "²");
    }

    private void togglePlayback() {
        if (sourceKind == KIND_NONE) {
            toast("Load media first.");
            return;
        }
        if (playing.get()) stopPlayback();
        else startPlayback();
    }

    private void startPlayback() {
        stopPlayback();
        V4Engine.Settings requested = settings();
        int activeRate;
        try {
            activeRate = audio.start(requested.sampleRate);
        } catch (Throwable t) {
            status.setText("Audio start error: " + safe(t));
            return;
        }
        V4Engine.Settings s = requested.copy();
        s.sampleRate = activeRate;
        playing.set(true);
        playButton.setText("STOP");
        status.setText("LIVE · " + activeRate + " Hz · " + s.fps + " fps · " + bankName(s.bank));

        if (sourceKind == KIND_IMAGE) {
            worker.submit(() -> {
                try {
                    V4Engine.Result shown = V4Engine.compile(stillBitmap, s, 0L);
                    V4Engine.Settings rawSettings = s.copy();
                    rawSettings.harmony = 0f;
                    rawSettings.aura = 0f;
                    V4Engine.Result raw = V4Engine.compile(stillBitmap, rawSettings, 0L);
                    float[] superLoop = V4Engine.makeStillSuperLoop(raw, s, 2);
                    if (!playing.get()) return;
                    audio.setFrame(superLoop);
                    lastResult = shown;
                    runOnUiThread(() -> {
                        displayResult(shown, true);
                        status.setText(liveStatus(shown, s));
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> status.setText("Compile error: " + safe(t)));
                }
            });
            return;
        }

        animationThread = new Thread(() -> {
            long start = SystemClock.elapsedRealtime();
            long frameIndex = 0L;
            long period = Math.max(1L, Math.round(1000.0 / s.fps));
            while (playing.get()) {
                long target = frameIndex * period;
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (elapsed < target) {
                    SystemClock.sleep(Math.min(5L, target - elapsed));
                    continue;
                }
                if (elapsed - target > period * 2L) frameIndex = Math.max(frameIndex, elapsed / period);
                long mediaTime = durationMs > 0 ? (frameIndex * period) % durationMs : frameIndex * period;
                try {
                    int maxDecode = s.fps >= 60 ? 480 : (s.fps >= 30 ? 600 : 720);
                    Bitmap frame = sourceKind == KIND_VIDEO ? getVideoFrame(mediaTime, maxDecode) : getGifFrame(mediaTime, maxDecode);
                    if (frame != null) {
                        V4Engine.Result r = V4Engine.compile(frame, s, frameIndex);
                        audio.setFrame(r.xy);
                        lastResult = r;
                        if ((frameIndex % Math.max(1, s.fps / 3)) == 0) {
                            runOnUiThread(() -> {
                                displayResult(r, true);
                                status.setText(liveStatus(r, s));
                            });
                        } else {
                            runOnUiThread(() -> scope.setTrace(r.xy));
                        }
                    }
                } catch (Throwable t) {
                    runOnUiThread(() -> status.setText("Frame error: " + safe(t)));
                }
                frameIndex++;
                if (frameIndex > 100000000L) {
                    frameIndex = 0L;
                    start = SystemClock.elapsedRealtime();
                }
            }
        }, "OsciVisionV4Video");
        animationThread.setPriority(Thread.NORM_PRIORITY + 1);
        animationThread.start();
    }

    private String liveStatus(V4Engine.Result r, V4Engine.Settings s) {
        return String.format(Locale.US,
                "LIVE %d Hz · %d fps · FIT %.1f%% · PATH %.1f%% · FLY %d · underruns %d · %s",
                audio.sampleRate(), s.fps, r.matchScore, r.pathScore, r.flybacks,
                audio.underruns(), audio.routeName());
    }

    private void stopPlayback() {
        playing.set(false);
        if (animationThread != null) {
            animationThread.interrupt();
            animationThread = null;
        }
        audio.stop();
        if (playButton != null) playButton.setText("PLAY XY");
    }

    private Bitmap getVideoFrame(long timeMs, int maxDimension) {
        if (retriever == null) return null;
        long us = Math.max(0L, timeMs) * 1000L;
        int option = fps() >= 60 ? MediaMetadataRetriever.OPTION_CLOSEST_SYNC : MediaMetadataRetriever.OPTION_CLOSEST;
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                int w = sourceWidth <= 0 ? maxDimension : sourceWidth;
                int h = sourceHeight <= 0 ? maxDimension : sourceHeight;
                float scale = Math.min(1f, maxDimension / (float) Math.max(w, h));
                int tw = Math.max(2, Math.round(w * scale));
                int th = Math.max(2, Math.round(h * scale));
                Bitmap b = retriever.getScaledFrameAtTime(us, option, tw, th);
                if (b != null) return b;
            }
        } catch (Throwable ignored) {}
        return retriever.getFrameAtTime(us, option);
    }

    private Bitmap getGifFrame(long timeMs, int maxDimension) {
        if (gifMovie == null) return null;
        int w = Math.max(1, gifMovie.width());
        int h = Math.max(1, gifMovie.height());
        float scale = Math.min(1f, maxDimension / (float) Math.max(w, h));
        int tw = Math.max(2, Math.round(w * scale));
        int th = Math.max(2, Math.round(h * scale));
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        c.scale(scale, scale);
        synchronized (this) {
            gifMovie.setTime((int) (timeMs % Math.max(1L, durationMs)));
            gifMovie.draw(c, 0f, 0f);
        }
        return out;
    }

    private void export(Uri destination) {
        if (sourceKind == KIND_NONE) return;
        status.setText("Rendering v4 WAV…");
        V4Engine.Settings s = settings();
        boolean float32 = exportSpinner.getSelectedItemPosition() == 1;
        worker.submit(() -> {
            File temp = new File(getCacheDir(), "oscivision_v4_" + System.currentTimeMillis() + ".wav");
            try {
                try (V4Audio.WavWriter writer = V4Audio.createWriter(temp, s.sampleRate, float32)) {
                    if (sourceKind == KIND_IMAGE) {
                        V4Engine.Settings rawSettings = s.copy();
                        rawSettings.harmony = 0f;
                        rawSettings.aura = 0f;
                        V4Engine.Result raw = V4Engine.compile(stillBitmap, rawSettings, 0L);
                        writer.write(V4Engine.makeStillSuperLoop(raw, s, 4));
                    } else {
                        long dur = Math.max(1L, durationMs);
                        int frames = Math.max(1, (int) Math.ceil(dur * s.fps / 1000.0));
                        for (int i = 0; i < frames; i++) {
                            long t = Math.min(dur - 1, Math.round(i * 1000.0 / s.fps));
                            int maxDecode = s.fps >= 60 ? 480 : (s.fps >= 30 ? 600 : 720);
                            Bitmap frame = sourceKind == KIND_VIDEO ? getVideoFrame(t, maxDecode) : getGifFrame(t, maxDecode);
                            if (frame == null) continue;
                            V4Engine.Result r = V4Engine.compile(frame, s, i);
                            writer.write(r.xy);
                            if (i % Math.max(1, s.fps / 2) == 0) {
                                final int pct = Math.min(99, Math.round(100f * i / frames));
                                runOnUiThread(() -> status.setText("Rendering v4 WAV… " + pct + "%"));
                            }
                        }
                    }
                }
                try (InputStream in = new BufferedInputStream(new FileInputStream(temp));
                     OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(destination, "w"))) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                String depth = float32 ? "32-bit float" : "24-bit PCM";
                runOnUiThread(() -> status.setText("Saved · " + depth + " stereo XY · " + s.sampleRate + " Hz · " + bankName(s.bank)));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Export error: " + safe(t)));
            } finally {
                try { if (temp.exists()) temp.delete(); } catch (Throwable ignored) {}
            }
        });
    }

    private void showTest(boolean circle) {
        V4Engine.Settings s = settings();
        worker.submit(() -> {
            float[] xy = circle ? V4Engine.circlePattern(s) : V4Engine.gridPattern(s);
            runOnUiThread(() -> {
                scope.setTrace(xy);
                scope.setDiagnostics(circle ? "CAL: make this circle perfectly round" : "CAL: make grid square and level");
                status.setText(circle ? "CAL CIRCLE · adjust X/Y gain until perfectly round" : "CAL GRID · adjust X/Y gain and rotation until square");
            });
            if (playing.get()) audio.setFrame(xy);
        });
    }

    private String mediaName() {
        if (sourceKind == KIND_VIDEO) return "VIDEO";
        if (sourceKind == KIND_GIF) return "GIF";
        if (sourceKind == KIND_IMAGE) return "IMAGE";
        return "MEDIA";
    }

    private static String bankName(int bank) {
        return bank == 1 ? "Bagua 64" : (bank == 2 ? "Sevenfold 49" : (bank == 3 ? "Raw XY" : "81 Lattice"));
    }

    private void releaseSource() {
        stillBitmap = null;
        gifMovie = null;
        gifBytes = null;
        durationMs = 0L;
        sourceWidth = 0;
        sourceHeight = 0;
        lastResult = null;
        if (retriever != null) {
            try { retriever.release(); } catch (Throwable ignored) {}
            retriever = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        releaseSource();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = input.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s); } catch (Throwable t) { return fallback; }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return fallback; }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
