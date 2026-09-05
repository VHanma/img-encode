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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OsciVision Ultra v2
 * Full-field XY image / GIF / video synthesizer.
 * LEFT audio = X, RIGHT audio = Y.
 *
 * There is deliberately no horizontal or vertical raster scan. Each vector frame is a
 * weighted cloud of XY samples distributed over the entire source frame, then ordered by
 * a locality-preserving path so the single beam paints the complete image inside the
 * phosphor persistence window.
 */
public class UltraActivity extends Activity {
    private static final int REQ_OPEN = 2101;
    private static final int REQ_SAVE = 2102;
    private static final int KIND_NONE = 0;
    private static final int KIND_IMAGE = 1;
    private static final int KIND_VIDEO = 2;
    private static final int KIND_GIF = 3;

    private ScopeView scopeView;
    private TextView status;
    private TextView detailLabel;
    private TextView gammaLabel;
    private TextView xGainLabel;
    private TextView yGainLabel;
    private TextView rotationLabel;
    private TextView persistenceLabel;
    private Spinner sampleSpinner;
    private Spinner fpsSpinner;
    private Spinner modeSpinner;
    private SeekBar detailBar;
    private SeekBar gammaBar;
    private SeekBar xGainBar;
    private SeekBar yGainBar;
    private SeekBar rotationBar;
    private SeekBar persistenceBar;
    private CheckBox invertBox;
    private CheckBox stabilizeBox;
    private Button playButton;

    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean previewQueued = new AtomicBoolean(false);
    private Thread animationThread;
    private AudioEngine audio;

    private Uri sourceUri;
    private int sourceKind = KIND_NONE;
    private Bitmap stillBitmap;
    private Movie gifMovie;
    private byte[] gifBytes;
    private MediaMetadataRetriever videoRetriever;
    private long sourceDurationMs;
    private int sourceWidth;
    private int sourceHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = new AudioEngine();
        setContentView(buildUi());
        status.setText("Load an image, GIF, or video. Every displayed point is generated from the same XY sample field sent to audio.");
    }

    private View buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(4, 7, 10));

        TextView title = new TextView(this);
        title.setText("OSCIVISION ULTRA v2");
        title.setTextColor(Color.rgb(170, 255, 205));
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = new TextView(this);
        sub.setText("FULL-FIELD XY PHOSPHOR SYNTHESIS");
        sub.setTextColor(Color.rgb(120, 190, 155));
        sub.setTextSize(12f);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(3), 0, dp(8));
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        scopeView = new ScopeView(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(410));
        sp.setMargins(0, dp(7), 0, dp(9));
        root.addView(scopeView, sp);

        LinearLayout actions = row();
        Button load = button("LOAD MEDIA");
        playButton = button("PLAY XY");
        Button save = button("SAVE 24-BIT WAV");
        actions.addView(load, weight());
        actions.addView(playButton, weight());
        actions.addView(save, weight());
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        load.setOnClickListener(v -> openMedia());
        playButton.setOnClickListener(v -> togglePlayback());
        save.setOnClickListener(v -> chooseSave());

        root.addView(label("OUTPUT SAMPLE RATE"));
        sampleSpinner = spinner(new String[]{"192000 Hz · maximum XY density", "96000 Hz", "48000 Hz"}, 0);
        root.addView(sampleSpinner, new LinearLayout.LayoutParams(-1, -2));

        root.addView(label("VECTOR FRAME / PERSISTENCE RATE"));
        fpsSpinner = spinner(new String[]{"12 fps · extreme detail", "15 fps · high detail", "24 fps", "30 fps · video balance", "60 fps · motion priority"}, 1);
        root.addView(fpsSpinner, new LinearLayout.LayoutParams(-1, -2));

        root.addView(label("FULL-FIELD COMPILER"));
        modeSpinner = spinner(new String[]{"PERCEPTUAL PHOTO + MICRODETAIL", "PHOTO DENSITY", "EDGE / LINE ART"}, 0);
        root.addView(modeSpinner, new LinearLayout.LayoutParams(-1, -2));

        detailLabel = label("DETAIL / OPTIMIZATION: 96%");
        root.addView(detailLabel);
        detailBar = new SeekBar(this);
        detailBar.setMax(100);
        detailBar.setProgress(96);
        detailBar.setOnSeekBarChangeListener(simpleChange((bar, value) -> {
            detailLabel.setText("DETAIL / OPTIMIZATION: " + value + "%");
            queuePreview();
        }));
        root.addView(detailBar, new LinearLayout.LayoutParams(-1, -2));

        gammaLabel = label("PHOSPHOR / LUMA GAMMA: 1.00");
        root.addView(gammaLabel);
        gammaBar = new SeekBar(this);
        gammaBar.setMax(180);
        gammaBar.setProgress(60); // 0.40 + 0.60 = 1.00
        gammaBar.setOnSeekBarChangeListener(simpleChange((bar, value) -> {
            gammaLabel.setText(String.format(Locale.US, "PHOSPHOR / LUMA GAMMA: %.2f", gammaValue()));
            queuePreview();
        }));
        root.addView(gammaBar, new LinearLayout.LayoutParams(-1, -2));

        stabilizeBox = new CheckBox(this);
        stabilizeBox.setText("Temporal lock for GIF / video (reduces point shimmer)");
        stabilizeBox.setTextColor(Color.rgb(205, 225, 215));
        stabilizeBox.setChecked(true);
        root.addView(stabilizeBox, new LinearLayout.LayoutParams(-1, -2));

        invertBox = new CheckBox(this);
        invertBox.setText("Invert luminance polarity");
        invertBox.setTextColor(Color.rgb(205, 225, 215));
        invertBox.setOnCheckedChangeListener((b, checked) -> queuePreview());
        root.addView(invertBox, new LinearLayout.LayoutParams(-1, -2));

        persistenceLabel = label("SOFTWARE PHOSPHOR PERSISTENCE: 70%");
        root.addView(persistenceLabel);
        persistenceBar = new SeekBar(this);
        persistenceBar.setMax(100);
        persistenceBar.setProgress(70);
        persistenceBar.setOnSeekBarChangeListener(simpleChange((bar, value) -> {
            persistenceLabel.setText("SOFTWARE PHOSPHOR PERSISTENCE: " + value + "%");
            scopeView.setPersistence(value);
        }));
        root.addView(persistenceBar, new LinearLayout.LayoutParams(-1, -2));

        TextView calibration = label("OUTPUT GEOMETRY CALIBRATION");
        calibration.setTextColor(Color.rgb(150, 240, 180));
        root.addView(calibration);

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
        rotationBar.setMax(200);
        rotationBar.setProgress(100);
        rotationBar.setOnSeekBarChangeListener(simpleChange((bar, value) -> {
            rotationLabel.setText(String.format(Locale.US, "ROTATION: %.1f°", rotationDegrees()));
            queuePreview();
        }));
        root.addView(rotationBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tests = row();
        Button gridTest = button("CAL GRID");
        Button circleTest = button("CAL CIRCLE");
        tests.addView(gridTest, weight());
        tests.addView(circleTest, weight());
        root.addView(tests, new LinearLayout.LayoutParams(-1, -2));
        gridTest.setOnClickListener(v -> showTestPattern(false));
        circleTest.setOnClickListener(v -> showTestPattern(true));

        TextView note = new TextView(this);
        note.setText("LEFT = X   •   RIGHT = Y   •   24-bit WAV export\nNo raster wipe: brightness is encoded as full-field sample density and micro-dwell. Long beam jumps are kept as single-sample flybacks instead of being interpolated into bright connector lines.");
        note.setTextColor(Color.rgb(135, 158, 150));
        note.setTextSize(12f);
        note.setPadding(0, dp(7), 0, dp(7));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(185, 255, 205));
        status.setTextSize(13f);
        status.setPadding(0, dp(5), 0, dp(18));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private interface ChangeAction { void changed(SeekBar bar, int value); }

    private SeekBar.OnSeekBarChangeListener simpleChange(ChangeAction a) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { if (fromUser) a.changed(seekBar, progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private SeekBar gainBar(TextView target, String prefix) {
        SeekBar b = new SeekBar(this);
        b.setMax(80); // 60..140
        b.setProgress(40);
        b.setOnSeekBarChangeListener(simpleChange((bar, value) -> {
            target.setText(prefix + ": " + (60 + value) + "%");
            queuePreview();
        }));
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, 0, 0, dp(7));
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11f);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(190, 212, 205));
        t.setTextSize(12f);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private Spinner spinner(String[] values, int selected) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(selected);
        return s;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void openMedia() {
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        startActivityForResult(i, REQ_OPEN);
    }

    private void chooseSave() {
        if (sourceKind == KIND_NONE) { toast("Load media first."); return; }
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, "oscivision_ultra_xy_24bit_" + System.currentTimeMillis() + ".wav");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
            loadSource(uri);
        } else if (requestCode == REQ_SAVE) {
            exportSource(uri);
        }
    }

    private void loadSource(Uri uri) {
        sourceUri = uri;
        status.setText("Decoding media…");
        work.submit(() -> {
            try {
                releaseSource();
                ContentResolver cr = getContentResolver();
                String mime = cr.getType(uri);
                String lower = uri.toString().toLowerCase(Locale.US);
                if (mime != null && mime.startsWith("video/")) {
                    sourceKind = KIND_VIDEO;
                    videoRetriever = new MediaMetadataRetriever();
                    videoRetriever.setDataSource(this, uri);
                    sourceDurationMs = parseLong(videoRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 1000L);
                    sourceWidth = parseInt(videoRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 640);
                    sourceHeight = parseInt(videoRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 480);
                    Bitmap first = getVideoFrame(0L);
                    if (first == null) throw new IllegalStateException("Video decoder returned no frame");
                    runOnUiThread(() -> {
                        compilePreview(first, 0L);
                        status.setText(String.format(Locale.US, "VIDEO · %.2fs · %dx%d · full-field XY ready", sourceDurationMs / 1000.0, sourceWidth, sourceHeight));
                    });
                } else if ((mime != null && mime.equals("image/gif")) || lower.endsWith(".gif")) {
                    sourceKind = KIND_GIF;
                    gifBytes = readAll(cr.openInputStream(uri));
                    gifMovie = Movie.decodeByteArray(gifBytes, 0, gifBytes.length);
                    if (gifMovie == null) {
                        sourceKind = KIND_IMAGE;
                        stillBitmap = decodeBitmap(uri);
                        if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                        sourceWidth = stillBitmap.getWidth(); sourceHeight = stillBitmap.getHeight(); sourceDurationMs = 5000L;
                        runOnUiThread(() -> { compilePreview(stillBitmap, 0L); status.setText("GIF fallback loaded as still image"); });
                    } else {
                        sourceDurationMs = gifMovie.duration() > 0 ? gifMovie.duration() : 1000L;
                        sourceWidth = gifMovie.width(); sourceHeight = gifMovie.height();
                        Bitmap first = getGifFrame(0L);
                        runOnUiThread(() -> { compilePreview(first, 0L); status.setText(String.format(Locale.US, "GIF · %.2fs · %dx%d · full-field XY ready", sourceDurationMs / 1000.0, sourceWidth, sourceHeight)); });
                    }
                } else {
                    sourceKind = KIND_IMAGE;
                    stillBitmap = decodeBitmap(uri);
                    if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                    sourceWidth = stillBitmap.getWidth(); sourceHeight = stillBitmap.getHeight(); sourceDurationMs = 5000L;
                    runOnUiThread(() -> { compilePreview(stillBitmap, 0L); status.setText("IMAGE · " + sourceWidth + "×" + sourceHeight + " · full-field XY ready"); });
                }
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Load error: " + safeMessage(t)));
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

    private void queuePreview() {
        if (sourceKind != KIND_IMAGE || stillBitmap == null || playing.get()) return;
        if (!previewQueued.compareAndSet(false, true)) return;
        work.submit(() -> {
            try {
                SystemClock.sleep(45L);
                Settings s = settings();
                float[] trace = VectorEngine.compile(stillBitmap, s, 0L);
                runOnUiThread(() -> scopeView.setTrace(trace));
            } catch (Throwable ignored) {
            } finally {
                previewQueued.set(false);
            }
        });
    }

    private void compilePreview(Bitmap b, long frameIndex) {
        if (b == null) return;
        Settings s = settings();
        work.submit(() -> {
            try {
                float[] trace = VectorEngine.compile(b, s, frameIndex);
                runOnUiThread(() -> scopeView.setTrace(trace));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Compile error: " + safeMessage(t)));
            }
        });
    }

    private Settings settings() {
        Settings s = new Settings();
        s.sampleRate = selectedSampleRate();
        s.fps = selectedFps();
        s.quality = detailBar == null ? 96 : detailBar.getProgress();
        s.mode = modeSpinner == null ? 0 : modeSpinner.getSelectedItemPosition();
        s.invert = invertBox != null && invertBox.isChecked();
        s.temporalLock = stabilizeBox == null || stabilizeBox.isChecked();
        s.gamma = gammaValue();
        s.xGain = xGainBar == null ? 1f : (60f + xGainBar.getProgress()) / 100f;
        s.yGain = yGainBar == null ? 1f : (60f + yGainBar.getProgress()) / 100f;
        s.rotationDeg = rotationDegrees();
        return s;
    }

    private float gammaValue() { return gammaBar == null ? 1f : 0.40f + gammaBar.getProgress() / 100f; }
    private float rotationDegrees() { return rotationBar == null ? 0f : (rotationBar.getProgress() - 100) / 10f; }

    private int selectedSampleRate() {
        if (sampleSpinner == null) return 192000;
        switch (sampleSpinner.getSelectedItemPosition()) {
            case 1: return 96000;
            case 2: return 48000;
            default: return 192000;
        }
    }

    private int selectedFps() {
        if (fpsSpinner == null) return 15;
        switch (fpsSpinner.getSelectedItemPosition()) {
            case 0: return 12;
            case 1: return 15;
            case 2: return 24;
            case 3: return 30;
            default: return 60;
        }
    }

    private void togglePlayback() {
        if (sourceKind == KIND_NONE) { toast("Load media first."); return; }
        if (playing.get()) stopPlayback(); else startPlayback();
    }

    private void startPlayback() {
        stopPlayback();
        final Settings requested = settings();
        int activeRate;
        try { activeRate = audio.start(requested.sampleRate); }
        catch (Throwable t) { status.setText("Audio start error: " + safeMessage(t)); return; }
        final Settings s = requested.copy();
        s.sampleRate = activeRate;
        playing.set(true);
        playButton.setText("STOP");
        status.setText("LIVE XY · " + activeRate + " Hz · " + s.fps + " fps · " + (activeRate / s.fps) + " coordinates/frame");

        if (sourceKind == KIND_IMAGE) {
            work.submit(() -> {
                try {
                    float[] trace = VectorEngine.compile(stillBitmap, s, 0L);
                    if (!playing.get()) return;
                    audio.setFrame(trace);
                    runOnUiThread(() -> scopeView.setTrace(trace));
                } catch (Throwable t) { runOnUiThread(() -> status.setText("Compile error: " + safeMessage(t))); }
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
                if (elapsed < target) { SystemClock.sleep(Math.min(5L, target - elapsed)); continue; }
                if (elapsed - target > period * 2L) frameIndex = Math.max(frameIndex, elapsed / period);
                long mediaTime = sourceDurationMs > 0 ? (frameIndex * period) % sourceDurationMs : frameIndex * period;
                try {
                    Bitmap frame = sourceKind == KIND_VIDEO ? getVideoFrame(mediaTime) : getGifFrame(mediaTime);
                    if (frame != null) {
                        float[] trace = VectorEngine.compile(frame, s, frameIndex);
                        audio.setFrame(trace);
                        runOnUiThread(() -> scopeView.setTrace(trace));
                    }
                } catch (Throwable t) {
                    runOnUiThread(() -> status.setText("Playback frame error: " + safeMessage(t)));
                }
                frameIndex++;
                if (frameIndex > 100000000L) { frameIndex = 0; start = SystemClock.elapsedRealtime(); }
            }
        }, "OsciVisionVideo");
        animationThread.setPriority(Thread.NORM_PRIORITY + 1);
        animationThread.start();
    }

    private void stopPlayback() {
        playing.set(false);
        if (animationThread != null) { animationThread.interrupt(); animationThread = null; }
        if (audio != null) audio.stop();
        if (playButton != null) playButton.setText("PLAY XY");
    }

    private Bitmap getVideoFrame(long timeMs) {
        if (videoRetriever == null) return null;
        long us = Math.max(0L, timeMs) * 1000L;
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                int max = 720;
                int w = sourceWidth <= 0 ? max : sourceWidth;
                int h = sourceHeight <= 0 ? max : sourceHeight;
                float scale = Math.min(1f, max / (float) Math.max(w, h));
                int tw = Math.max(2, Math.round(w * scale));
                int th = Math.max(2, Math.round(h * scale));
                Bitmap b = videoRetriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, tw, th);
                if (b != null) return b;
            }
        } catch (Throwable ignored) {}
        return videoRetriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
    }

    private Bitmap getGifFrame(long timeMs) {
        if (gifMovie == null) return null;
        int max = 720;
        int w = Math.max(1, gifMovie.width());
        int h = Math.max(1, gifMovie.height());
        float scale = Math.min(1f, max / (float) Math.max(w, h));
        int tw = Math.max(2, Math.round(w * scale));
        int th = Math.max(2, Math.round(h * scale));
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        c.scale(scale, scale);
        synchronized (this) {
            gifMovie.setTime((int) (timeMs % Math.max(1L, sourceDurationMs)));
            gifMovie.draw(c, 0f, 0f);
        }
        return out;
    }

    private void exportSource(Uri destination) {
        if (sourceKind == KIND_NONE) return;
        status.setText("Rendering 24-bit XY WAV…");
        final Settings s = settings();
        work.submit(() -> {
            File temp = new File(getCacheDir(), "oscivision_v2_" + System.currentTimeMillis() + ".wav");
            try {
                long duration = sourceKind == KIND_IMAGE ? 5000L : Math.max(1L, sourceDurationMs);
                int frames = Math.max(1, (int) Math.ceil(duration * s.fps / 1000.0));
                try (Wav24Writer writer = new Wav24Writer(temp, s.sampleRate)) {
                    for (int i = 0; i < frames; i++) {
                        long t = Math.min(duration - 1, Math.round(i * 1000.0 / s.fps));
                        Bitmap frame = sourceKind == KIND_IMAGE ? stillBitmap : (sourceKind == KIND_VIDEO ? getVideoFrame(t) : getGifFrame(t));
                        if (frame == null) continue;
                        float[] xy = VectorEngine.compile(frame, s, i);
                        writer.write(xy);
                        if (i % Math.max(1, s.fps / 2) == 0) {
                            final int pct = Math.min(99, Math.round(100f * i / frames));
                            runOnUiThread(() -> status.setText("Rendering 24-bit XY WAV… " + pct + "%"));
                        }
                    }
                }
                try (InputStream in = new BufferedInputStream(new FileInputStream(temp));
                     OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(destination, "w"))) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                runOnUiThread(() -> status.setText("SAVED · 24-bit stereo XY · " + s.sampleRate + " Hz"));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Export error: " + safeMessage(t)));
            } finally {
                try { if (temp.exists()) temp.delete(); } catch (Throwable ignored) {}
            }
        });
    }

    private void showTestPattern(boolean circle) {
        Settings s = settings();
        work.submit(() -> {
            float[] trace = circle ? VectorEngine.circlePattern(s) : VectorEngine.gridPattern(s);
            runOnUiThread(() -> {
                scopeView.setTrace(trace);
                status.setText(circle ? "CAL CIRCLE · adjust X/Y gain until perfectly round" : "CAL GRID · adjust gain/rotation until square and centered");
            });
            if (playing.get()) audio.setFrame(trace);
        });
    }

    private void releaseSource() {
        stillBitmap = null; gifMovie = null; gifBytes = null; sourceDurationMs = 0L; sourceWidth = 0; sourceHeight = 0;
        if (videoRetriever != null) { try { videoRetriever.release(); } catch (Throwable ignored) {} videoRetriever = null; }
    }

    @Override protected void onDestroy() {
        stopPlayback();
        releaseSource();
        work.shutdownNow();
        super.onDestroy();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536]; int n;
            while ((n = input.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
    private static long parseLong(String s, long f) { try { return Long.parseLong(s); } catch (Throwable t) { return f; } }
    private static int parseInt(String s, int f) { try { return Integer.parseInt(s); } catch (Throwable t) { return f; } }
    private static String safeMessage(Throwable t) { if (t == null) return "unknown"; String m = t.getMessage(); return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static final class Settings {
        int sampleRate = 192000;
        int fps = 15;
        int quality = 96;
        int mode = 0;
        boolean invert;
        boolean temporalLock = true;
        float gamma = 1f;
        float xGain = 1f;
        float yGain = 1f;
        float rotationDeg;
        Settings copy() {
            Settings s = new Settings(); s.sampleRate = sampleRate; s.fps = fps; s.quality = quality; s.mode = mode; s.invert = invert; s.temporalLock = temporalLock; s.gamma = gamma; s.xGain = xGain; s.yGain = yGain; s.rotationDeg = rotationDeg; return s;
        }
    }

    /** Full-field weighted vector compiler. */
    private static final class VectorEngine {
        private static final double GOLDEN_CONJ = 0.6180339887498948482;
        private static final double GOLDEN_ANGLE = 2.39996322972865332;

        private static final class Point {
            float x, y;
            int h;
            Point(float x, float y, int h) { this.x = x; this.y = y; this.h = h; }
        }

        static float[] compile(Bitmap input, Settings s, long frameIndex) {
            if (input == null) return new float[]{0f,0f,0f,0f};
            int rate = clamp(s.sampleRate, 8000, 192000);
            int fps = clamp(s.fps, 5, 120);
            int budget = clamp(Math.round(rate / (float) fps), 800, 24000);
            int grid = clamp(112 + Math.round(s.quality * 2.08f), 112, 320);
            int side = nextPow2(grid);
            int bits = Integer.numberOfTrailingZeros(side);

            Bitmap square = Bitmap.createBitmap(grid, grid, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(square);
            c.drawColor(Color.BLACK);
            Paint filter = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float scale = Math.min(grid / (float) Math.max(1, input.getWidth()), grid / (float) Math.max(1, input.getHeight()));
            float dw = input.getWidth() * scale, dh = input.getHeight() * scale;
            float left = (grid - dw) * 0.5f, top = (grid - dh) * 0.5f;
            c.drawBitmap(input, null, new android.graphics.RectF(left, top, left + dw, top + dh), filter);

            int[] px = new int[grid * grid];
            square.getPixels(px, 0, grid, 0, 0, grid, grid);
            float[] lum = new float[px.length];
            for (int i = 0; i < px.length; i++) {
                int color = px[i];
                float l = (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f;
                if (s.invert) l = 1f - l;
                lum[i] = (float) Math.pow(clamp01(l), Math.max(0.25f, s.gamma));
            }

            Gradient g = gradients(lum, grid, grid);
            float[] detail = localDetail(lum, grid, grid);
            float[] photoW = new float[lum.length];
            float[] edgeW = new float[lum.length];
            float[] hybridW = new float[lum.length];
            for (int i = 0; i < lum.length; i++) {
                float l = lum[i];
                float e = g.mag[i];
                float d = detail[i];
                photoW[i] = l;
                edgeW[i] = (float) Math.pow(e, 0.72);
                hybridW[i] = clamp01(l * 0.80f + edgeW[i] * 0.38f + d * 0.16f);
            }

            List<Point> pts = new ArrayList<>(budget);
            if (s.mode == 2) {
                sampleField(edgeW, g, grid, budget, bits, pts, true, s.temporalLock ? 0L : frameIndex);
            } else if (s.mode == 1) {
                int edgeQuota = Math.round(budget * (0.08f + s.quality / 1000f));
                sampleField(photoW, g, grid, budget - edgeQuota, bits, pts, false, s.temporalLock ? 0L : frameIndex);
                sampleField(edgeW, g, grid, edgeQuota, bits, pts, true, s.temporalLock ? 0L : frameIndex + 31);
            } else {
                int edgeQuota = Math.round(budget * (0.19f + 0.10f * s.quality / 100f));
                sampleField(hybridW, g, grid, budget - edgeQuota, bits, pts, false, s.temporalLock ? 0L : frameIndex);
                sampleField(edgeW, g, grid, edgeQuota, bits, pts, true, s.temporalLock ? 0L : frameIndex + 31);
            }
            if (pts.size() < 2) return new float[]{0f,0f,0f,0f};

            Collections.sort(pts, Comparator.comparingInt(p -> p.h));
            int window = s.quality >= 90 ? 18 : (s.quality >= 65 ? 12 : 7);
            localNearest(pts, window);
            rotateLargestGapToSeam(pts);

            float[] out = new float[pts.size() * 2];
            double rad = Math.toRadians(s.rotationDeg);
            float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
            float norm = 1.84f / Math.max(1f, grid - 1f);
            int o = 0;
            for (Point pnt : pts) {
                float x = (pnt.x - (grid - 1) * 0.5f) * norm;
                float y = -((pnt.y - (grid - 1) * 0.5f) * norm);
                x *= s.xGain; y *= s.yGain;
                float xr = x * cs - y * sn;
                float yr = x * sn + y * cs;
                out[o++] = clamp(xr, -0.985f, 0.985f);
                out[o++] = clamp(yr, -0.985f, 0.985f);
            }
            return out;
        }

        private static void sampleField(float[] w, Gradient g, int grid, int count, int bits, List<Point> out, boolean edgeMode, long seed) {
            if (count <= 0) return;
            double[] cdf = new double[w.length];
            double total = 0.0;
            for (int i = 0; i < w.length; i++) { total += Math.max(0.0, w[i]); cdf[i] = total; }
            if (total <= 1e-12) return;
            double rotation = fract((seed * 0.7548776662466927) + 0.1732050807568877);
            for (int k = 0; k < count; k++) {
                double u = fract((k + 0.5) * GOLDEN_CONJ + rotation) * total;
                int idx = lowerBound(cdf, u);
                int x = idx % grid, y = idx / grid;
                double hx = halton(k + 1 + (int) (seed & 127), 2) - 0.5;
                double hy = halton(k + 1 + (int) ((seed * 3) & 127), 3) - 0.5;
                float jx, jy;
                if (edgeMode && g.mag[idx] > 0.05f) {
                    float gx = g.gx[idx], gy = g.gy[idx];
                    float inv = 1f / Math.max(1e-6f, (float) Math.sqrt(gx * gx + gy * gy));
                    float tx = -gy * inv, ty = gx * inv;
                    float along = (float) hx * 0.86f;
                    float across = (float) hy * 0.16f;
                    jx = tx * along + gx * inv * across;
                    jy = ty * along + gy * inv * across;
                } else {
                    jx = (float) hx * 0.88f;
                    jy = (float) hy * 0.88f;
                }
                float px = clamp(x + jx, 0f, grid - 1f);
                float py = clamp(y + jy, 0f, grid - 1f);
                int hi = hilbertIndex(clamp(Math.round(px), 0, grid - 1), clamp(Math.round(py), 0, grid - 1), bits);
                out.add(new Point(px, py, hi));
            }
        }

        private static void localNearest(List<Point> p, int window) {
            int n = p.size();
            for (int i = 0; i < n - 2; i++) {
                Point cur = p.get(i);
                int best = i + 1;
                float bestD = dist2(cur, p.get(best));
                int end = Math.min(n, i + 1 + window);
                for (int j = i + 2; j < end; j++) {
                    float d = dist2(cur, p.get(j));
                    if (d < bestD) { bestD = d; best = j; }
                }
                if (best != i + 1) Collections.swap(p, i + 1, best);
            }
        }

        private static void rotateLargestGapToSeam(List<Point> p) {
            int n = p.size();
            if (n < 4) return;
            int gapAt = n - 1;
            float max = dist2(p.get(n - 1), p.get(0));
            for (int i = 0; i < n - 1; i++) {
                float d = dist2(p.get(i), p.get(i + 1));
                if (d > max) { max = d; gapAt = i; }
            }
            if (gapAt == n - 1) return;
            Collections.rotate(p, -(gapAt + 1));
        }

        private static float dist2(Point a, Point b) { float dx = a.x - b.x, dy = a.y - b.y; return dx * dx + dy * dy; }

        private static int lowerBound(double[] a, double v) {
            int lo = 0, hi = a.length - 1;
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] < v) lo = mid + 1; else hi = mid; }
            return lo;
        }

        private static double halton(int index, int base) {
            double f = 1.0, r = 0.0; int i = Math.max(1, index);
            while (i > 0) { f /= base; r += f * (i % base); i /= base; }
            return r;
        }

        private static double fract(double x) { return x - Math.floor(x); }

        private static final class Gradient {
            final float[] gx, gy, mag;
            Gradient(float[] gx, float[] gy, float[] mag) { this.gx = gx; this.gy = gy; this.mag = mag; }
        }

        private static Gradient gradients(float[] a, int w, int h) {
            float[] gx = new float[a.length], gy = new float[a.length], m = new float[a.length];
            float max = 1e-7f;
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    float dx = -a[i-w-1] + a[i-w+1] - 2f*a[i-1] + 2f*a[i+1] - a[i+w-1] + a[i+w+1];
                    float dy = -a[i-w-1] - 2f*a[i-w] - a[i-w+1] + a[i+w-1] + 2f*a[i+w] + a[i+w+1];
                    float mm = (float) Math.sqrt(dx*dx + dy*dy);
                    gx[i] = dx; gy[i] = dy; m[i] = mm; if (mm > max) max = mm;
                }
            }
            float inv = 1f / max;
            for (int i = 0; i < m.length; i++) { gx[i] *= inv; gy[i] *= inv; m[i] = clamp01(m[i] * inv); }
            return new Gradient(gx, gy, m);
        }

        private static float[] localDetail(float[] a, int w, int h) {
            float[] d = new float[a.length]; float max = 1e-7f;
            for (int y = 1; y < h - 1; y++) for (int x = 1; x < w - 1; x++) {
                int i = y*w+x;
                float v = Math.abs(a[i]*4f - a[i-1] - a[i+1] - a[i-w] - a[i+w]);
                d[i] = v; if (v > max) max = v;
            }
            float inv = 1f/max;
            for (int i=0;i<d.length;i++) d[i]=clamp01(d[i]*inv);
            return d;
        }

        static float[] circlePattern(Settings s) {
            int n = clamp(s.sampleRate / s.fps, 800, 24000);
            float[] out = new float[n * 2];
            double r = Math.toRadians(s.rotationDeg); float cs=(float)Math.cos(r), sn=(float)Math.sin(r);
            for (int i=0;i<n;i++) {
                double a = 2.0*Math.PI*i/n;
                float x=(float)Math.cos(a)*0.82f*s.xGain, y=(float)Math.sin(a)*0.82f*s.yGain;
                out[i*2]=clamp(x*cs-y*sn,-0.985f,0.985f); out[i*2+1]=clamp(x*sn+y*cs,-0.985f,0.985f);
            }
            return out;
        }

        static float[] gridPattern(Settings s) {
            int n = clamp(s.sampleRate / s.fps, 800, 24000);
            float[] out = new float[n*2];
            List<Point> path = new ArrayList<>();
            int lines=9, per=Math.max(20,n/(lines*2));
            for(int l=0;l<lines;l++){
                float v=-0.82f+1.64f*l/(lines-1f);
                for(int i=0;i<per;i++){ float t=-0.82f+1.64f*i/(per-1f); path.add(new Point(t,v,0)); }
                for(int i=0;i<per;i++){ float t=-0.82f+1.64f*i/(per-1f); path.add(new Point(v,t,0)); }
            }
            double rad=Math.toRadians(s.rotationDeg); float cs=(float)Math.cos(rad),sn=(float)Math.sin(rad);
            for(int i=0;i<n;i++){
                Point p=path.get(i%path.size()); float x=p.x*s.xGain,y=p.y*s.yGain;
                out[i*2]=clamp(x*cs-y*sn,-0.985f,0.985f); out[i*2+1]=clamp(x*sn+y*cs,-0.985f,0.985f);
            }
            return out;
        }

        private static int nextPow2(int n) { int p=1; while(p<n)p<<=1; return p; }
        private static int hilbertIndex(int x,int y,int bits){ int index=0,n=1<<bits,xx=x,yy=y; for(int ss=n>>1;ss>0;ss>>=1){ int rx=(xx&ss)>0?1:0,ry=(yy&ss)>0?1:0; index+=ss*ss*((3*rx)^ry); if(ry==0){ if(rx==1){xx=n-1-xx;yy=n-1-yy;} int t=xx;xx=yy;yy=t; } } return index; }
        private static float clamp01(float v){return v<0f?0f:(v>1f?1f:v);} private static float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);} private static int clamp(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}
    }

    private static final class AudioEngine {
        private final AtomicReference<float[]> frame = new AtomicReference<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private AudioTrack track;
        private Thread thread;
        private int activeRate = 48000;

        int start(int requested) {
            stop();
            int[] rates = requested >= 192000 ? new int[]{192000,96000,48000} : requested >= 96000 ? new int[]{96000,48000} : new int[]{48000};
            RuntimeException last = null;
            for (int rate : rates) {
                try {
                    int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
                    if (min <= 0) min = rate / 10 * 8;
                    int buffer = Math.max(min, rate / 12 * 8);
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(buffer).build();
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new RuntimeException("AudioTrack initialization failed");
                    activeRate = rate; track.play(); running.set(true);
                    thread = new Thread(() -> {
                        float[] lastFrame = null;
                        while (running.get()) {
                            float[] f = frame.get();
                            if (f == null || f.length < 4) f = lastFrame;
                            if (f == null) { SystemClock.sleep(3L); continue; }
                            lastFrame = f;
                            int wrote = track.write(f, 0, f.length, AudioTrack.WRITE_BLOCKING);
                            if (wrote < 0) SystemClock.sleep(2L);
                        }
                    }, "OsciVisionAudio");
                    thread.setPriority(Thread.MAX_PRIORITY); thread.start(); return activeRate;
                } catch (RuntimeException e) {
                    last = e; if (track != null) { try { track.release(); } catch (Throwable ignored) {} track = null; }
                }
            }
            if (last != null) throw last; return activeRate;
        }
        void setFrame(float[] f){ frame.set(f); }
        void stop(){ running.set(false); if(thread!=null){thread.interrupt();thread=null;} frame.set(null); if(track!=null){try{track.pause();}catch(Throwable ignored){} try{track.flush();}catch(Throwable ignored){} try{track.stop();}catch(Throwable ignored){} try{track.release();}catch(Throwable ignored){} track=null;} }
    }

    private static final class Wav24Writer implements AutoCloseable {
        private final RandomAccessFile raf; private final int sampleRate; private long dataBytes;
        Wav24Writer(File f,int sr)throws Exception{sampleRate=sr;raf=new RandomAccessFile(f,"rw");raf.setLength(0);writeHeader(0);}
        void write(float[] xy)throws Exception{
            byte[] buf=new byte[Math.min(131072,Math.max(12288,xy.length*3))];int bp=0;
            for(float v:xy){int s=Math.round(Math.max(-1f,Math.min(1f,v))*8388607f); if(bp+3>buf.length){raf.write(buf,0,bp);dataBytes+=bp;bp=0;} buf[bp++]=(byte)(s&255);buf[bp++]=(byte)((s>>8)&255);buf[bp++]=(byte)((s>>16)&255);} if(bp>0){raf.write(buf,0,bp);dataBytes+=bp;}
        }
        private void writeHeader(long data)throws Exception{raf.seek(0);raf.writeBytes("RIFF");le32(36+data);raf.writeBytes("WAVE");raf.writeBytes("fmt ");le32(16);le16(1);le16(2);le32(sampleRate);le32((long)sampleRate*6L);le16(6);le16(24);raf.writeBytes("data");le32(data);}
        private void le16(int v)throws Exception{raf.write(v&255);raf.write((v>>>8)&255);} private void le32(long v)throws Exception{raf.write((int)(v&255));raf.write((int)((v>>>8)&255));raf.write((int)((v>>>16)&255));raf.write((int)((v>>>24)&255));}
        @Override public void close()throws Exception{writeHeader(dataBytes);raf.close();}
    }

    /** Density-aware software phosphor. Repeated XY visits become brighter instead of one opaque polyline. */
    private static final class ScopeView extends View {
        private final Paint gridPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<float[]> history=new ArrayList<>();
        private volatile int persistence=70;

        ScopeView(Activity a){super(a);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setBackgroundColor(Color.BLACK);gridPaint.setStyle(Paint.Style.STROKE);gridPaint.setStrokeWidth(1f);gridPaint.setColor(Color.argb(45,80,180,115));pointPaint.setStyle(Paint.Style.FILL);pointPaint.setStrokeCap(Paint.Cap.ROUND);linePaint.setStyle(Paint.Style.STROKE);linePaint.setStrokeWidth(0.9f);linePaint.setStrokeCap(Paint.Cap.ROUND);glowPaint.setStyle(Paint.Style.STROKE);glowPaint.setStrokeWidth(3.2f);glowPaint.setColor(Color.argb(28,60,255,125));glowPaint.setShadowLayer(8f,0,0,Color.rgb(30,255,105));}
        synchronized void setTrace(float[] t){if(t==null)return;history.add(0,t);int keep=1+Math.round(persistence/25f);while(history.size()>keep)history.remove(history.size()-1);invalidate();}
        void setPersistence(int p){persistence=Math.max(0,Math.min(100,p)); synchronized(this){int keep=1+Math.round(persistence/25f);while(history.size()>keep)history.remove(history.size()-1);}invalidate();}
        @Override protected synchronized void onDraw(Canvas canvas){super.onDraw(canvas);int w=getWidth(),h=getHeight();float cx=w*.5f,cy=h*.5f,size=Math.min(w,h)*.47f;for(int i=-4;i<=4;i++){float x=cx+size*i/4f,y=cy+size*i/4f;canvas.drawLine(x,cy-size,x,cy+size,gridPaint);canvas.drawLine(cx-size,y,cx+size,y,gridPaint);}canvas.drawCircle(cx,cy,size,gridPaint);for(int hi=history.size()-1;hi>=0;hi--){float[] xy=history.get(hi);if(xy==null||xy.length<4)continue;float age=(history.size()-hi)/(float)Math.max(1,history.size());int alpha=Math.max(3,Math.round(11f*age));pointPaint.setColor(Color.argb(alpha,175,255,205));pointPaint.setStrokeWidth(1.5f+age);float[] pts=new float[xy.length];for(int i=0;i+1<xy.length;i+=2){pts[i]=cx+xy[i]*size;pts[i+1]=cy+xy[i+1]*size;}canvas.drawPoints(pts,pointPaint);Path close=new Path();boolean drawing=false;float lastX=0,lastY=0;for(int i=0;i+1<pts.length;i+=2){float x=pts[i],y=pts[i+1];if(!drawing){close.moveTo(x,y);drawing=true;}else{float dx=x-lastX,dy=y-lastY;float d2=dx*dx+dy*dy;if(d2<Math.max(9f,size*size*.0014f)){close.lineTo(x,y);}else{close.moveTo(x,y);}}lastX=x;lastY=y;}linePaint.setColor(Color.argb(Math.max(2,alpha/2),160,255,195));canvas.drawPath(close,linePaint);if(hi==0)canvas.drawPath(close,glowPaint);}}
    }
}
