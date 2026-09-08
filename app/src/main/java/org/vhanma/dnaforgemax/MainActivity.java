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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
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

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1001;
    private static final int REQ_SAVE = 1002;
    private static final int KIND_NONE = 0;
    private static final int KIND_IMAGE = 1;
    private static final int KIND_VIDEO = 2;
    private static final int KIND_GIF = 3;

    private ScopeView scopeView;
    private TextView status;
    private TextView qualityLabel;
    private Spinner sampleSpinner;
    private Spinner fpsSpinner;
    private Spinner modeSpinner;
    private SeekBar qualityBar;
    private CheckBox invertBox;
    private Button playButton;
    private Button saveButton;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private Thread animationThread;
    private AudioEngine audioEngine;

    private Uri sourceUri;
    private int sourceKind = KIND_NONE;
    private Bitmap stillBitmap;
    private byte[] gifBytes;
    private Movie gifMovie;
    private MediaMetadataRetriever videoRetriever;
    private long sourceDurationMs = 0L;
    private int sourceWidth = 0;
    private int sourceHeight = 0;
    private float[] currentTrace;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audioEngine = new AudioEngine();
        setContentView(buildUi());
        status.setText("Load an image, GIF, or video. The live display is driven by the same XY samples sent to audio.");
    }

    private View buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(5, 8, 12));

        TextView title = new TextView(this);
        title.setText("OSCIVISION ULTRA");
        title.setTextColor(Color.rgb(170, 255, 205));
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = new TextView(this);
        sub.setText("Image / GIF / video  →  live XY vector sound");
        sub.setTextColor(Color.rgb(150, 175, 190));
        sub.setTextSize(13f);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        scopeView = new ScopeView(this);
        LinearLayout.LayoutParams scopeParams = new LinearLayout.LayoutParams(-1, dp(390));
        scopeParams.setMargins(0, dp(12), 0, dp(10));
        root.addView(scopeView, scopeParams);

        LinearLayout row1 = row();
        Button open = button("LOAD MEDIA");
        playButton = button("PLAY XY");
        saveButton = button("SAVE WAV");
        row1.addView(open, weight());
        row1.addView(playButton, weight());
        row1.addView(saveButton, weight());
        root.addView(row1, new LinearLayout.LayoutParams(-1, -2));

        open.setOnClickListener(v -> openMedia());
        playButton.setOnClickListener(v -> togglePlayback());
        saveButton.setOnClickListener(v -> chooseSaveDestination());

        root.addView(label("OUTPUT SAMPLE RATE"));
        sampleSpinner = spinner(new String[]{"192000 Hz", "96000 Hz", "48000 Hz"}, 0);
        root.addView(sampleSpinner, new LinearLayout.LayoutParams(-1, -2));

        root.addView(label("VECTOR FRAME RATE"));
        fpsSpinner = spinner(new String[]{"15 fps · maximum detail", "24 fps", "30 fps", "60 fps · maximum motion"}, 2);
        root.addView(fpsSpinner, new LinearLayout.LayoutParams(-1, -2));

        root.addView(label("IMAGE COMPILER"));
        modeSpinner = spinner(new String[]{"HYBRID PHOTO + EDGES", "PHOTO DENSITY", "EDGE MICRODETAIL"}, 0);
        root.addView(modeSpinner, new LinearLayout.LayoutParams(-1, -2));

        qualityLabel = label("DETAIL / RESIDUAL OPTIMIZATION: 88%");
        root.addView(qualityLabel);
        qualityBar = new SeekBar(this);
        qualityBar.setMax(100);
        qualityBar.setProgress(88);
        qualityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                qualityLabel.setText("DETAIL / RESIDUAL OPTIMIZATION: " + progress + "%");
                if (fromUser && sourceKind == KIND_IMAGE && stillBitmap != null && !playing.get()) compilePreview(stillBitmap, 0L);
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(qualityBar, new LinearLayout.LayoutParams(-1, -2));

        invertBox = new CheckBox(this);
        invertBox.setText("Invert luminance polarity");
        invertBox.setTextColor(Color.rgb(210, 225, 230));
        invertBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (sourceKind == KIND_IMAGE && stillBitmap != null && !playing.get()) compilePreview(stillBitmap, 0L);
        });
        root.addView(invertBox, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("Stereo output: LEFT = X, RIGHT = Y. There is no horizontal or vertical raster sweep. A physical single-beam XY scope still moves one spot continuously, so the complete picture is formed inside each persistence window.");
        note.setTextColor(Color.rgb(135, 155, 165));
        note.setTextSize(12f);
        note.setPadding(0, dp(6), 0, dp(8));
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(Color.rgb(185, 255, 205));
        status.setTextSize(13f);
        status.setPadding(0, dp(4), 0, dp(18));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, 0, 0, dp(8));
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
        b.setTextSize(12f);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(190, 210, 220));
        t.setTextSize(12f);
        t.setPadding(0, dp(7), 0, dp(2));
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openMedia() {
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        startActivityForResult(i, REQ_OPEN);
    }

    private void chooseSaveDestination() {
        if (sourceKind == KIND_NONE) {
            toast("Load media first.");
            return;
        }
        stopPlayback();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/wav");
        i.putExtra(Intent.EXTRA_TITLE, "oscivision_xy_" + System.currentTimeMillis() + ".wav");
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {}
            loadSource(uri);
        } else if (requestCode == REQ_SAVE) {
            exportSource(uri);
        }
    }

    private void loadSource(Uri uri) {
        status.setText("Decoding media…");
        sourceUri = uri;
        ioExecutor.submit(() -> {
            try {
                releaseSource();
                ContentResolver cr = getContentResolver();
                String mime = cr.getType(uri);
                String lower = uri.toString().toLowerCase(Locale.US);
                if ((mime != null && mime.startsWith("video/"))) {
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
                        status.setText(String.format(Locale.US, "VIDEO loaded · %.2fs · %dx%d", sourceDurationMs / 1000.0, sourceWidth, sourceHeight));
                    });
                } else if ((mime != null && mime.equals("image/gif")) || lower.endsWith(".gif")) {
                    sourceKind = KIND_GIF;
                    gifBytes = readAll(cr.openInputStream(uri));
                    gifMovie = Movie.decodeByteArray(gifBytes, 0, gifBytes.length);
                    if (gifMovie == null) {
                        sourceKind = KIND_IMAGE;
                        stillBitmap = decodeBitmap(uri);
                        if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                        runOnUiThread(() -> {
                            compilePreview(stillBitmap, 0L);
                            status.setText("GIF decoder fell back to still image.");
                        });
                    } else {
                        sourceDurationMs = gifMovie.duration() > 0 ? gifMovie.duration() : 1000L;
                        sourceWidth = gifMovie.width();
                        sourceHeight = gifMovie.height();
                        Bitmap first = getGifFrame(0L);
                        runOnUiThread(() -> {
                            compilePreview(first, 0L);
                            status.setText(String.format(Locale.US, "GIF loaded · %.2fs · %dx%d", sourceDurationMs / 1000.0, sourceWidth, sourceHeight));
                        });
                    }
                } else {
                    sourceKind = KIND_IMAGE;
                    stillBitmap = decodeBitmap(uri);
                    if (stillBitmap == null) throw new IllegalStateException("Could not decode image");
                    sourceWidth = stillBitmap.getWidth();
                    sourceHeight = stillBitmap.getHeight();
                    sourceDurationMs = 5000L;
                    runOnUiThread(() -> {
                        compilePreview(stillBitmap, 0L);
                        status.setText("IMAGE loaded · " + sourceWidth + "×" + sourceHeight + " · full-frame XY synthesis ready");
                    });
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

    private void togglePlayback() {
        if (sourceKind == KIND_NONE) {
            toast("Load media first.");
            return;
        }
        if (playing.get()) stopPlayback(); else startPlayback();
    }

    private void startPlayback() {
        stopPlayback();
        final int sampleRate = selectedSampleRate();
        final int fps = selectedFps();
        final int quality = qualityBar.getProgress();
        final int mode = modeSpinner.getSelectedItemPosition();
        final boolean invert = invertBox.isChecked();
        int actualRate = audioEngine.start(sampleRate);
        playing.set(true);
        playButton.setText("STOP");
        status.setText("LIVE XY · requested " + sampleRate + " Hz · active " + actualRate + " Hz · " + fps + " fps");

        if (sourceKind == KIND_IMAGE) {
            float[] trace = VectorEngine.compile(stillBitmap, actualRate, fps, quality, mode, invert, 0L);
            currentTrace = trace;
            scopeView.setTrace(trace);
            audioEngine.setFrame(trace);
            return;
        }

        animationThread = new Thread(() -> {
            long start = SystemClock.elapsedRealtime();
            long frameIndex = 0;
            long framePeriod = Math.max(1L, Math.round(1000.0 / fps));
            while (playing.get()) {
                long targetElapsed = frameIndex * framePeriod;
                long nowElapsed = SystemClock.elapsedRealtime() - start;
                if (nowElapsed < targetElapsed) {
                    SystemClock.sleep(Math.min(6L, targetElapsed - nowElapsed));
                    continue;
                }
                long mediaTime = sourceDurationMs > 0 ? targetElapsed % sourceDurationMs : targetElapsed;
                try {
                    Bitmap frame = sourceKind == KIND_VIDEO ? getVideoFrame(mediaTime) : getGifFrame(mediaTime);
                    if (frame != null) {
                        float[] trace = VectorEngine.compile(frame, audioEngine.sampleRate(), fps, quality, mode, invert, frameIndex);
                        currentTrace = trace;
                        audioEngine.setFrame(trace);
                        runOnUiThread(() -> scopeView.setTrace(trace));
                    }
                } catch (Throwable t) {
                    runOnUiThread(() -> status.setText("Playback decode error: " + safeMessage(t)));
                }
                frameIndex++;
                if (frameIndex > 100000000L) {
                    frameIndex = 0;
                    start = SystemClock.elapsedRealtime();
                }
            }
        }, "OscivisionAnimation");
        animationThread.start();
    }

    private void stopPlayback() {
        playing.set(false);
        if (animationThread != null) {
            animationThread.interrupt();
            animationThread = null;
        }
        if (audioEngine != null) audioEngine.stop();
        if (playButton != null) playButton.setText("PLAY XY");
    }

    private void compilePreview(Bitmap bitmap, long frameIndex) {
        if (bitmap == null) return;
        int sr = selectedSampleRate();
        int fps = selectedFps();
        int q = qualityBar.getProgress();
        int mode = modeSpinner.getSelectedItemPosition();
        boolean inv = invertBox.isChecked();
        ioExecutor.submit(() -> {
            try {
                float[] trace = VectorEngine.compile(bitmap, sr, fps, q, mode, inv, frameIndex);
                currentTrace = trace;
                runOnUiThread(() -> scopeView.setTrace(trace));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Compile error: " + safeMessage(t)));
            }
        });
    }

    private Bitmap getVideoFrame(long timeMs) {
        if (videoRetriever == null) return null;
        long us = Math.max(0L, timeMs) * 1000L;
        try {
            if (Build.VERSION.SDK_INT >= 27) {
                int max = 720;
                int w = sourceWidth <= 0 ? max : sourceWidth;
                int h = sourceHeight <= 0 ? max : sourceHeight;
                float s = Math.min(1f, max / (float) Math.max(w, h));
                int tw = Math.max(2, Math.round(w * s));
                int th = Math.max(2, Math.round(h * s));
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
        float s = Math.min(1f, max / (float) Math.max(w, h));
        int tw = Math.max(2, Math.round(w * s));
        int th = Math.max(2, Math.round(h * s));
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        c.scale(s, s);
        synchronized (this) {
            gifMovie.setTime((int) (timeMs % Math.max(1L, sourceDurationMs)));
            gifMovie.draw(c, 0f, 0f);
        }
        return out;
    }

    private void exportSource(Uri destination) {
        if (sourceKind == KIND_NONE) return;
        status.setText("Exporting XY WAV…");
        final int sr = selectedSampleRate();
        final int fps = selectedFps();
        final int quality = qualityBar.getProgress();
        final int mode = modeSpinner.getSelectedItemPosition();
        final boolean invert = invertBox.isChecked();

        ioExecutor.submit(() -> {
            File temp = new File(getCacheDir(), "oscivision_export_" + System.currentTimeMillis() + ".wav");
            try {
                int frameCount;
                long duration = sourceKind == KIND_IMAGE ? 5000L : Math.max(1L, sourceDurationMs);
                frameCount = Math.max(1, (int) Math.ceil(duration * fps / 1000.0));
                try (WavWriter writer = new WavWriter(temp, sr)) {
                    for (int i = 0; i < frameCount; i++) {
                        long t = Math.min(duration - 1, Math.round(i * 1000.0 / fps));
                        Bitmap frame;
                        if (sourceKind == KIND_IMAGE) frame = stillBitmap;
                        else if (sourceKind == KIND_VIDEO) frame = getVideoFrame(t);
                        else frame = getGifFrame(t);
                        if (frame == null) continue;
                        float[] xy = VectorEngine.compile(frame, sr, fps, quality, mode, invert, i);
                        writer.writeFloatStereo(xy);
                        if (i % Math.max(1, fps) == 0) {
                            final int pct = Math.min(99, Math.round(100f * i / frameCount));
                            runOnUiThread(() -> status.setText("Exporting XY WAV… " + pct + "%"));
                        }
                    }
                }
                try (InputStream in = new BufferedInputStream(new FileInputStream(temp));
                     OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(destination, "w"))) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                runOnUiThread(() -> status.setText("WAV saved · stereo XY · " + sr + " Hz"));
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Export error: " + safeMessage(t)));
            } finally {
                try { if (temp.exists()) temp.delete(); } catch (Throwable ignored) {}
            }
        });
    }

    private int selectedSampleRate() {
        switch (sampleSpinner.getSelectedItemPosition()) {
            case 1: return 96000;
            case 2: return 48000;
            default: return 192000;
        }
    }

    private int selectedFps() {
        switch (fpsSpinner.getSelectedItemPosition()) {
            case 0: return 15;
            case 1: return 24;
            case 3: return 60;
            default: return 30;
        }
    }

    private void releaseSource() {
        stillBitmap = null;
        gifMovie = null;
        gifBytes = null;
        sourceDurationMs = 0L;
        sourceWidth = 0;
        sourceHeight = 0;
        if (videoRetriever != null) {
            try { videoRetriever.release(); } catch (Throwable ignored) {}
            videoRetriever = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopPlayback();
        releaseSource();
        ioExecutor.shutdownNow();
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    public static final class VectorEngine {
        private static final class Node {
            int x, y, index;
            float target, edge, weight;
            int count;
            Node(int x, int y, int index, float target, float edge) {
                this.x = x; this.y = y; this.index = index; this.target = target; this.edge = edge;
            }
        }

        public static float[] compile(Bitmap input, int sampleRate, int fps, int quality, int mode, boolean invert, long frameSeed) {
            if (input == null) return new float[]{0f, 0f, 0f, 0f};
            sampleRate = clamp(sampleRate, 8000, 192000);
            fps = clamp(fps, 5, 120);
            quality = clamp(quality, 0, 100);
            int budget = clamp(sampleRate / fps, 900, 18000);
            int grid = clamp(80 + quality * 2, 80, 280);
            int side = nextPow2(grid);

            Bitmap square = Bitmap.createBitmap(grid, grid, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(square);
            canvas.drawColor(Color.BLACK);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float scale = Math.min(grid / (float) input.getWidth(), grid / (float) input.getHeight());
            float dw = input.getWidth() * scale;
            float dh = input.getHeight() * scale;
            float left = (grid - dw) * 0.5f;
            float top = (grid - dh) * 0.5f;
            canvas.drawBitmap(input, null, new android.graphics.RectF(left, top, left + dw, top + dh), p);

            int[] pix = new int[grid * grid];
            square.getPixels(pix, 0, grid, 0, 0, grid, grid);
            float[] lum = new float[pix.length];
            for (int i = 0; i < pix.length; i++) {
                int c = pix[i];
                float l = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
                if (invert) l = 1f - l;
                lum[i] = (float) Math.pow(clamp01(l), 1.05);
            }

            float[] edge = sobel(lum, grid, grid);
            List<Node> nodes = new ArrayList<>(grid * grid);
            int bits = Integer.numberOfTrailingZeros(side);
            for (int y = 0; y < grid; y++) {
                for (int x = 0; x < grid; x++) {
                    int i = y * grid + x;
                    float t;
                    if (mode == 1) t = lum[i];
                    else if (mode == 2) t = edge[i];
                    else t = clamp01(lum[i] * 0.76f + edge[i] * 0.34f);
                    if (t > 0.008f || edge[i] > 0.02f) {
                        nodes.add(new Node(x, y, hilbertIndex(x, y, bits), t, edge[i]));
                    }
                }
            }
            if (nodes.isEmpty()) return new float[]{0f, 0f, 0f, 0f};
            Collections.sort(nodes, Comparator.comparingInt(a -> a.index));

            float[] predicted = new float[grid * grid];
            float[] residual = new float[grid * grid];
            Arrays.fill(residual, 1f);
            int passes = 1 + quality / 35;
            for (int pass = 0; pass < passes; pass++) {
                double total = 0.0;
                float edgeMix = mode == 2 ? 0.70f : (mode == 1 ? 0.10f : 0.25f);
                for (Node n : nodes) {
                    int idx = n.y * grid + n.x;
                    float r = pass == 0 ? 1f : clamp(0.35f + 1.65f * residual[idx], 0.15f, 2.7f);
                    n.weight = Math.max(0.00001f, n.target * (1f - edgeMix) + n.edge * edgeMix) * r;
                    total += n.weight;
                }
                allocateCounts(nodes, budget, total);
                Arrays.fill(predicted, 0f);
                for (Node n : nodes) predicted[n.y * grid + n.x] += n.count;
                blur3(predicted, grid, grid);
                float maxPred = 0f;
                for (float v : predicted) if (v > maxPred) maxPred = v;
                float invMax = maxPred > 1e-6f ? 1f / maxPred : 1f;
                for (int i = 0; i < predicted.length; i++) {
                    float target = mode == 2 ? edge[i] : (mode == 1 ? lum[i] : clamp01(lum[i] * 0.80f + edge[i] * 0.25f));
                    residual[i] = clamp01(target - predicted[i] * invMax + 0.5f);
                }
            }

            int actual = 0;
            for (Node n : nodes) actual += n.count;
            if (actual <= 1) return new float[]{0f, 0f, 0f, 0f};
            float[] xy = new float[actual * 2];
            int out = 0;
            final double golden = 2.399963229728653;
            float half = (grid - 1) * 0.5f;
            float norm = half > 0 ? 0.92f / half : 1f;
            for (Node n : nodes) {
                for (int k = 0; k < n.count; k++) {
                    double a = golden * (k + 1 + (frameSeed % 97) * 0.03125);
                    float radius = n.count <= 1 ? 0f : Math.min(0.42f, 0.10f + 0.055f * (float) Math.sqrt(k));
                    float jx = (float) Math.cos(a) * radius;
                    float jy = (float) Math.sin(a) * radius;
                    float x = ((n.x + jx) - half) * norm;
                    float y = -((n.y + jy) - half) * norm;
                    xy[out++] = clamp(x, -0.96f, 0.96f);
                    xy[out++] = clamp(y, -0.96f, 0.96f);
                }
            }
            return xy;
        }

        private static void allocateCounts(List<Node> nodes, int budget, double total) {
            if (total <= 0.0) total = 1.0;
            double carry = 0.0;
            int used = 0;
            for (Node n : nodes) {
                double desired = n.weight / total * budget + carry;
                int c = (int) Math.floor(desired);
                carry = desired - c;
                n.count = c;
                used += c;
            }
            int missing = budget - used;
            if (missing > 0) {
                int step = Math.max(1, nodes.size() / missing);
                int pos = 0;
                for (int i = 0; i < missing; i++) {
                    nodes.get(Math.min(nodes.size() - 1, pos)).count++;
                    pos += step;
                    if (pos >= nodes.size()) pos = (pos % nodes.size()) + 1;
                }
            } else if (missing < 0) {
                int remove = -missing;
                for (int i = nodes.size() - 1; i >= 0 && remove > 0; i--) {
                    Node n = nodes.get(i);
                    int d = Math.min(remove, n.count);
                    n.count -= d;
                    remove -= d;
                }
            }
        }

        private static float[] sobel(float[] a, int w, int h) {
            float[] out = new float[a.length];
            float max = 1e-6f;
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    float gx = -a[i - w - 1] + a[i - w + 1] - 2f * a[i - 1] + 2f * a[i + 1] - a[i + w - 1] + a[i + w + 1];
                    float gy = -a[i - w - 1] - 2f * a[i - w] - a[i - w + 1] + a[i + w - 1] + 2f * a[i + w] + a[i + w + 1];
                    float g = (float) Math.sqrt(gx * gx + gy * gy);
                    out[i] = g;
                    if (g > max) max = g;
                }
            }
            float inv = 1f / max;
            for (int i = 0; i < out.length; i++) out[i] = clamp01(out[i] * inv);
            return out;
        }

        private static void blur3(float[] a, int w, int h) {
            float[] copy = a.clone();
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    float sum = copy[i] * 4f + copy[i - 1] * 2f + copy[i + 1] * 2f + copy[i - w] * 2f + copy[i + w] * 2f
                            + copy[i - w - 1] + copy[i - w + 1] + copy[i + w - 1] + copy[i + w + 1];
                    a[i] = sum / 16f;
                }
            }
        }

        private static int nextPow2(int n) {
            int p = 1;
            while (p < n) p <<= 1;
            return p;
        }

        private static int hilbertIndex(int x, int y, int bits) {
            int index = 0;
            int n = 1 << bits;
            int xx = x, yy = y;
            for (int s = n >> 1; s > 0; s >>= 1) {
                int rx = (xx & s) > 0 ? 1 : 0;
                int ry = (yy & s) > 0 ? 1 : 0;
                index += s * s * ((3 * rx) ^ ry);
                if (ry == 0) {
                    if (rx == 1) {
                        xx = n - 1 - xx;
                        yy = n - 1 - yy;
                    }
                    int t = xx; xx = yy; yy = t;
                }
            }
            return index;
        }

        private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
        private static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
        private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    }

    private static final class AudioEngine {
        private final AtomicReference<float[]> frame = new AtomicReference<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private AudioTrack track;
        private Thread thread;
        private int activeRate = 48000;

        int start(int requestedRate) {
            stop();
            int[] rates = requestedRate == 192000 ? new int[]{192000, 96000, 48000} : requestedRate == 96000 ? new int[]{96000, 48000} : new int[]{48000};
            RuntimeException last = null;
            for (int rate : rates) {
                try {
                    int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
                    if (min <= 0) min = rate / 10 * 2 * 4;
                    int buffer = Math.max(min, rate / 20 * 2 * 4);
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(buffer)
                            .build();
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new RuntimeException("AudioTrack did not initialize");
                    activeRate = rate;
                    track.play();
                    running.set(true);
                    thread = new Thread(() -> {
                        while (running.get()) {
                            float[] f = frame.get();
                            if (f == null || f.length < 4) {
                                SystemClock.sleep(4L);
                                continue;
                            }
                            int wrote = track.write(f, 0, f.length, AudioTrack.WRITE_BLOCKING);
                            if (wrote < 0) SystemClock.sleep(2L);
                        }
                    }, "OscivisionAudio");
                    thread.setPriority(Thread.MAX_PRIORITY);
                    thread.start();
                    return activeRate;
                } catch (RuntimeException e) {
                    last = e;
                    if (track != null) {
                        try { track.release(); } catch (Throwable ignored) {}
                        track = null;
                    }
                }
            }
            if (last != null) throw last;
            return activeRate;
        }

        void setFrame(float[] xy) { frame.set(xy); }
        int sampleRate() { return activeRate; }

        void stop() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
            frame.set(null);
            if (track != null) {
                try { track.pause(); } catch (Throwable ignored) {}
                try { track.flush(); } catch (Throwable ignored) {}
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
                track = null;
            }
        }
    }

    private static final class WavWriter implements AutoCloseable {
        private final RandomAccessFile raf;
        private final int sampleRate;
        private long dataBytes = 0L;

        WavWriter(File file, int sampleRate) throws Exception {
            this.sampleRate = sampleRate;
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(0L);
            writeHeader(0L);
        }

        void writeFloatStereo(float[] xy) throws Exception {
            byte[] buf = new byte[Math.min(65536, Math.max(4096, xy.length * 2))];
            int bp = 0;
            for (float v : xy) {
                int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
                if (bp + 2 > buf.length) {
                    raf.write(buf, 0, bp);
                    dataBytes += bp;
                    bp = 0;
                }
                buf[bp++] = (byte) (s & 0xff);
                buf[bp++] = (byte) ((s >> 8) & 0xff);
            }
            if (bp > 0) {
                raf.write(buf, 0, bp);
                dataBytes += bp;
            }
        }

        private void writeHeader(long data) throws Exception {
            raf.seek(0L);
            raf.writeBytes("RIFF");
            writeLE32(36L + data);
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            writeLE32(16);
            writeLE16(1);
            writeLE16(2);
            writeLE32(sampleRate);
            writeLE32((long) sampleRate * 4L);
            writeLE16(4);
            writeLE16(16);
            raf.writeBytes("data");
            writeLE32(data);
        }

        private void writeLE16(int v) throws Exception {
            raf.write(v & 0xff);
            raf.write((v >>> 8) & 0xff);
        }

        private void writeLE32(long v) throws Exception {
            raf.write((int) (v & 0xff));
            raf.write((int) ((v >>> 8) & 0xff));
            raf.write((int) ((v >>> 16) & 0xff));
            raf.write((int) ((v >>> 24) & 0xff));
        }

        @Override public void close() throws Exception {
            writeHeader(dataBytes);
            raf.close();
        }
    }

    private static final class ScopeView extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private volatile float[] trace;

        ScopeView(Activity context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(4.5f);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setStrokeJoin(Paint.Join.ROUND);
            glow.setColor(Color.argb(62, 60, 255, 135));
            glow.setShadowLayer(9f, 0f, 0f, Color.rgb(30, 255, 110));
            beam.setStyle(Paint.Style.STROKE);
            beam.setStrokeWidth(1.15f);
            beam.setStrokeCap(Paint.Cap.ROUND);
            beam.setStrokeJoin(Paint.Join.ROUND);
            beam.setColor(Color.rgb(190, 255, 212));
            grid.setStyle(Paint.Style.STROKE);
            grid.setStrokeWidth(1f);
            grid.setColor(Color.argb(48, 90, 190, 125));
            setBackgroundColor(Color.BLACK);
        }

        void setTrace(float[] xy) {
            this.trace = xy;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            float cx = w * 0.5f, cy = h * 0.5f;
            float size = Math.min(w, h) * 0.47f;
            for (int i = -4; i <= 4; i++) {
                float x = cx + size * i / 4f;
                float y = cy + size * i / 4f;
                canvas.drawLine(x, cy - size, x, cy + size, grid);
                canvas.drawLine(cx - size, y, cx + size, y, grid);
            }
            canvas.drawCircle(cx, cy, size, grid);
            float[] xy = trace;
            if (xy == null || xy.length < 4) return;
            Path path = new Path();
            float x0 = cx + xy[0] * size;
            float y0 = cy + xy[1] * size;
            path.moveTo(x0, y0);
            for (int i = 2; i + 1 < xy.length; i += 2) {
                path.lineTo(cx + xy[i] * size, cy + xy[i + 1] * size);
            }
            canvas.drawPath(path, glow);
            canvas.drawPath(path, beam);
        }
    }
}
