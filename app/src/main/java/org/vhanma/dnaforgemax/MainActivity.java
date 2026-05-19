package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public class MainActivity extends Activity {

    // ── constants ────────────────────────────────────────────────────────────
    static final int SAMPLE_RATE   = 44100;
    static final int MAX_DIM       = 512;
    static final double HE_NE_HZ   = 6328.0;
    static final double HE_NE_MS   = 632.8;
    static final double AURA_HZ    = 528.0;
    static final double SCHUMANN_HZ = 7.83;
    static final double F0         = 6400.0;
    static final double F1         = 7000.0;
    static final double BIT_MS     = 1.5;
    static final double GATE_DEPTH = 0.22;

    // reference wave amplitudes
    static final double REFERENCE_528_AMP   = 0.055;
    static final double REFERENCE_HE_NE_AMP = 0.035;
    static final double REFERENCE_40_AMP    = 0.020;

    // v13 additions
    static final double LASER_NM          = 632.8;
    static final double PIXEL_SIZE_UM     = 5.0;
    static final double PHI               = 1.6180339887498948;

    // full solfeggio stack
    static final double[] SOLFEGGIO = {396.0, 417.0, 528.0, 639.0, 741.0, 852.0, 963.0};

    // Tesla 3-6-9 harmonic ratios
    static final double[] TESLA_369 = {3.0, 6.0, 9.0, 18.0, 27.0, 36.0, 54.0, 81.0, 108.0};

    // ── request codes ────────────────────────────────────────────────────────
    static final int REQ_PICK_IMAGE = 1;
    static final int REQ_MIC_PERM   = 2;
    static final int REQ_AUDIO_PERM = 3;

    // ── UI ───────────────────────────────────────────────────────────────────
    TextView tvTitle, tvStatus, tvResult;
    Button btnPickImage, btnScanMic, btnScanCorrelator, btnForge, btnReset;
    ProgressBar progressBar;
    TextView tvProgress;
    LinearLayout rootLayout;
    ScrollView scrollView;

    // ── state ─────────────────────────────────────────────────────────────────
    Uri        imageUri;
    Bitmap     imageBitmap;
    String     sessionName = "";

    VisualImprint   visual;
    AcousticImprint acoustic;
    SpeckleCorrelator correlator;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    Handler         uiHandler = new Handler(Looper.getMainLooper());

    // ── data classes ─────────────────────────────────────────────────────────

    static class VisualImprint {
        int width, height;
        double brightness, red, green, blue;
        double edgeDensity, symmetry, fractalCompressionProxy;
        int[] histR = new int[8], histG = new int[8], histB = new int[8];
        long rawCrc, compressedCrc;
        // v13: spatial frequency centroid (Jiang cross-species donor)
        double spatialCentroidX, spatialCentroidY;
        // v13: gariaev speckle correlation coefficient
        double speckleCorrelation;
    }

    static class AcousticImprint {
        double rms, zeroCrossRate;
        double[] peaks  = new double[8];
        double[] powers = new double[8];
        // v13: dominant resonant frequency (for Jiang carrier)
        double dominantHz;
    }

    static class SpeckleCorrelator {
        // v13: proper Gariaev speckle interferometry — cross-correlation of
        // reference field vs object field brightness patches
        double[] correlationCoeffs = new double[16]; // 16 quadrant pairs
        double mberIndex;
        double[] feedbackMatrix = new double[16];
        // pass means
        double darkMean, redMean, dimRedMean, schumannMean, pulse369Mean;
    }

    static class Capsule {
        VisualImprint    visual;
        AcousticImprint  acoustic;
        SpeckleCorrelator correlator;
        double[]         phaseMatrix;
        String           meaningDna;
        String           phrase;
        byte[]           capsuleBytes;
        String           sessionName;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
    }

    void buildUI() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(24, 48, 24, 24);
        rootLayout.setBackgroundColor(0xFF0A0A0F);

        scrollView = new ScrollView(this);
        scrollView.addView(rootLayout);
        setContentView(scrollView);

        tvTitle = makeText("⬡ DNA FORGE MAX v13", 0xFF00FFAA, 22, true);
        rootLayout.addView(tvTitle);

        makeText2("Gariaev Speckle · Jiang Kanzhen · Tesla 3-6-9 · Solfeggio · Scalar Mid/Side", 0xFF558877, 13);

        rootLayout.addView(makeDivider());

        btnPickImage = makeButton("[ 1 ] LOAD DONOR IMAGE", 0xFF003322, 0xFF00FF88);
        btnPickImage.setOnClickListener(v -> pickImage());

        btnScanMic = makeButton("[ 2 ] SCAN MIC RESONANCE", 0xFF003322, 0xFF00FF88);
        btnScanMic.setOnClickListener(v -> startMicScan());

        btnScanCorrelator = makeButton("[ 3 ] SPECKLE CORRELATOR SCAN", 0xFF003322, 0xFF00FF88);
        btnScanCorrelator.setOnClickListener(v -> startCorrelatorScan());

        btnForge = makeButton("[ 4 ] FORGE SESSION →", 0xFF001A33, 0xFF00CCFF);
        btnForge.setOnClickListener(v -> showNameDialog());

        rootLayout.addView(makeDivider());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        rootLayout.addView(progressBar);

        tvProgress = makeText2("", 0xFF888888, 12);

        tvStatus = makeText2("Ready — load donor image to begin", 0xFF667788, 13);
        tvResult = makeText2("", 0xFF00FFAA, 12);

        btnReset = makeButton("[ RESET ]", 0xFF1A0011, 0xFFFF6688);
        btnReset.setOnClickListener(v -> resetState());
        btnReset.setVisibility(View.GONE);
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    TextView makeText(String s, int color, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, 8, 0, 8);
        rootLayout.addView(tv);
        return tv;
    }

    TextView makeText2(String s, int color, int sp) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setPadding(0, 4, 0, 4);
        rootLayout.addView(tv);
        return tv;
    }

    Button makeButton(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setBackgroundColor(bg);
        b.setTextColor(fg);
        b.setPadding(16, 20, 16, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 10, 0, 10);
        b.setLayoutParams(lp);
        rootLayout.addView(b);
        return b;
    }

    View makeDivider() {
        View v = new View(this);
        v.setBackgroundColor(0xFF1A2A1A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2);
        lp.setMargins(0, 16, 0, 16);
        v.setLayoutParams(lp);
        return v;
    }

    void say(String msg) {
        uiHandler.post(() -> tvStatus.setText(msg));
    }

    void setProgress(int pct, String label) {
        uiHandler.post(() -> {
            progressBar.setProgress(pct);
            tvProgress.setText(label + " — " + pct + "%");
        });
    }

    // ── step 1: pick image ────────────────────────────────────────────────────

    void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_IMAGE && res == RESULT_OK && data != null) {
            imageUri = data.getData();
            try {
                InputStream is = getContentResolver().openInputStream(imageUri);
                imageBitmap = BitmapFactory.decodeStream(is);
                is.close();
                visual = null;
                say("Donor image loaded: " + imageBitmap.getWidth() + "×" + imageBitmap.getHeight());
                btnPickImage.setTextColor(0xFF00FF88);
                btnPickImage.setText("✓ DONOR IMAGE LOADED");
            } catch (Exception e) {
                say("Image load error: " + e.getMessage());
            }
        }
    }

    // ── step 2: mic resonance scan ────────────────────────────────────────────

    void startMicScan() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERM);
            return;
        }
        say("Scanning mic resonance — 5 seconds...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        executor.submit(() -> {
            acoustic = recordAcousticImprint();
            uiHandler.post(() -> {
                btnScanMic.setTextColor(0xFF00FF88);
                btnScanMic.setText("✓ MIC RESONANCE SCANNED (" +
                        String.format(Locale.US, "%.1f", acoustic.dominantHz) + " Hz dominant)");
                progressBar.setVisibility(View.GONE);
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_AUDIO_PERM &&
                grants.length > 0 &&
                grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMicScan();
        }
    }

    AcousticImprint recordAcousticImprint() {
        AcousticImprint a = new AcousticImprint();
        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        bufSize = Math.max(bufSize, SAMPLE_RATE * 2);

        AudioRecord ar = null;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            ar.startRecording();

            int totalSamples = SAMPLE_RATE * 5;
            short[] pcm = new short[totalSamples];
            int read = 0;
            int prog = 0;
            while (read < totalSamples) {
                int chunk = Math.min(4096, totalSamples - read);
                int got = ar.read(pcm, read, chunk);
                if (got <= 0) break;
                read += got;
                int newProg = (read * 100) / totalSamples;
                if (newProg != prog) {
                    prog = newProg;
                    setProgress(prog, "Scanning mic");
                }
            }
            ar.stop();

            a = analyzeAudio(pcm, read);
        } catch (Exception e) {
            a.dominantHz = 528.0;
        } finally {
            if (ar != null) try { ar.release(); } catch (Exception ignored) {}
        }
        return a;
    }

    AcousticImprint analyzeAudio(short[] pcm, int n) {
        AcousticImprint a = new AcousticImprint();
        if (n <= 0) { a.dominantHz = 528.0; return a; }

        double sum2 = 0;
        int zc = 0;
        for (int i = 0; i < n; i++) {
            double s = pcm[i] / 32768.0;
            sum2 += s * s;
            if (i > 0 && ((pcm[i] >= 0) != (pcm[i - 1] >= 0))) zc++;
        }
        a.rms = Math.sqrt(sum2 / n);
        a.zeroCrossRate = zc / (double) n;

        // Goertzel for 8 detection targets
        double[] targets = {40, 100, 256, 440, 528, 741, 963, 1111};
        for (int k = 0; k < 8; k++) {
            a.peaks[k]  = targets[k];
            a.powers[k] = goertzelPower(pcm, n, targets[k], SAMPLE_RATE);
        }

        // find dominant
        int best = 0;
        for (int k = 1; k < 8; k++) if (a.powers[k] > a.powers[best]) best = k;
        a.dominantHz = a.peaks[best];

        // normalize powers
        double maxP = a.powers[best];
        if (maxP > 0) for (int k = 0; k < 8; k++) a.powers[k] /= maxP;

        return a;
    }

    static double goertzelPower(short[] pcm, int n, double freq, int sr) {
        double k = freq * n / sr;
        double w = 2.0 * Math.PI * k / n;
        double coeff = 2.0 * Math.cos(w);
        double s0 = 0, s1 = 0, s2;
        for (int i = 0; i < n; i++) {
            s2 = s1; s1 = s0;
            s0 = pcm[i] / 32768.0 + coeff * s1 - s2;
        }
        return s0 * s0 + s1 * s1 - coeff * s0 * s1;
    }

    // ── step 3: speckle correlator scan ──────────────────────────────────────

    void startCorrelatorScan() {
        if (imageBitmap == null) {
            Toast.makeText(this, "Load donor image first", Toast.LENGTH_SHORT).show();
            return;
        }
        say("Running Gariaev speckle interferometry scan...");
        progressBar.setVisibility(View.VISIBLE);
        executor.submit(() -> {
            correlator = computeSpeckleCorrelator(imageBitmap);
            uiHandler.post(() -> {
                btnScanCorrelator.setTextColor(0xFF00FF88);
                btnScanCorrelator.setText("✓ SPECKLE CORRELATOR DONE (MBER=" +
                        String.format(Locale.US, "%.3f", correlator.mberIndex) + ")");
                progressBar.setVisibility(View.GONE);
                say("Speckle correlation complete — " + 16 + " quadrant pairs computed");
            });
        });
    }

    SpeckleCorrelator computeSpeckleCorrelator(Bitmap src) {
        SpeckleCorrelator sc = new SpeckleCorrelator();
        Bitmap bmp = shrink(src);
        int w = bmp.getWidth(), h = bmp.getHeight();

        // 5-pass pixel sampling (Gariaev emulation: dark/red/dim-red/Schumann/369)
        // Each pass uses a different spectral weighting on RGB channels
        double darkSum = 0, redSum = 0, dimSum = 0, schuSum = 0, p369Sum = 0;
        int count = w * h;

        // Reference field: left half brightness
        // Object field: right half brightness
        // Compute cross-correlation coefficient per quadrant (4×4 grid = 16 cells)
        int qw = Math.max(1, w / 4), qh = Math.max(1, h / 4);

        for (int qi = 0; qi < 4; qi++) {
            for (int qj = 0; qj < 4; qj++) {
                int x0 = qi * qw, y0 = qj * qh;
                int x1 = Math.min(x0 + qw, w), y1 = Math.min(y0 + qh, h);
                int n = (x1 - x0) * (y1 - y0);
                if (n <= 0) continue;

                double sumA = 0, sumB = 0, sumA2 = 0, sumB2 = 0, sumAB = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int c = bmp.getPixel(x, y);
                        int r = (c >> 16) & 0xff;
                        int g = (c >> 8) & 0xff;
                        int bl = c & 0xff;

                        // reference: luminance weighted toward red (He-Ne 632.8nm)
                        double ref = (r * 0.60 + g * 0.30 + bl * 0.11) / 255.0;
                        // object: mirror pixel across vertical axis
                        int xm = w - 1 - x;
                        int cm = bmp.getPixel(xm, y);
                        int rm = (cm >> 16) & 0xff;
                        int gm = (cm >> 8) & 0xff;
                        int bm = cm & 0xff;
                        double obj = (rm * 0.60 + gm * 0.30 + bm * 0.11) / 255.0;

                        sumA += ref; sumB += obj;
                        sumA2 += ref * ref; sumB2 += obj * obj;
                        sumAB += ref * obj;
                    }
                }
                double meanA = sumA / n, meanB = sumB / n;
                double stdA = Math.sqrt(Math.max(0, sumA2 / n - meanA * meanA));
                double stdB = Math.sqrt(Math.max(0, sumB2 / n - meanB * meanB));
                double denom = stdA * stdB * n;
                double corr = denom > 1e-12 ? (sumAB - n * meanA * meanB) / denom : 0.0;
                sc.correlationCoeffs[qi * 4 + qj] = corr;

                setProgress((qi * 4 + qj + 1) * 5, "Speckle correlator");
            }
        }

        // pass means via progressive scan rows
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int bl = c & 0xff;
                double lum = (r + g + bl) / 3.0 / 255.0;
                darkSum  += lum * 0.05;
                redSum   += (r / 255.0) * 0.85 + lum * 0.15;
                dimSum   += (r / 255.0) * 0.45 + lum * 0.55;
                // Schumann: edge-modulated brightness
                if (x + 1 < w) {
                    int c2 = bmp.getPixel(x + 1, y);
                    double lum2 = (((c2 >> 16) & 0xff) + ((c2 >> 8) & 0xff) + (c2 & 0xff)) / 3.0 / 255.0;
                    schuSum += Math.abs(lum - lum2);
                }
                // 369: modulate by golden ratio position
                double phi_mod = Math.sin(PHI * 2 * Math.PI * (x + y * w) / (double) count);
                p369Sum += lum * (0.5 + 0.5 * phi_mod);
            }
        }

        sc.darkMean        = darkSum / count;
        sc.redMean         = redSum  / count;
        sc.dimRedMean      = dimSum  / count;
        sc.schumannMean    = schuSum / Math.max(1, count - h);
        sc.pulse369Mean    = p369Sum / count;

        // MBER modulation index: std of correlationCoeffs
        double mean = 0;
        for (double cc : sc.correlationCoeffs) mean += cc;
        mean /= sc.correlationCoeffs.length;
        double var = 0;
        for (double cc : sc.correlationCoeffs) var += (cc - mean) * (cc - mean);
        sc.mberIndex = Math.sqrt(var / sc.correlationCoeffs.length);

        // Build feedback matrix from correlation coefficients (v13: actual cross-corr values)
        for (int i = 0; i < 16; i++) {
            double base = sc.correlationCoeffs[i];
            // Golden ratio spiral phase offset
            double spiral = Math.atan2(
                    Math.sin(PHI * Math.PI * i),
                    Math.cos(PHI * Math.PI * i)
            );
            sc.feedbackMatrix[i] = base * Math.PI + spiral * sc.mberIndex;
        }

        return sc;
    }

    // ── step 4: session name dialog ───────────────────────────────────────────

    void showNameDialog() {
        if (imageBitmap == null) {
            Toast.makeText(this, "Load a donor image first", Toast.LENGTH_SHORT).show();
            return;
        }

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String defaultName = "dna_forge_v13_" + ts;

        AlertDialog.Builder dlg = new AlertDialog.Builder(this);
        dlg.setTitle("Session Name");

        EditText et = new EditText(this);
        et.setText(defaultName);
        et.setSelectAllOnFocus(true);
        dlg.setView(et);

        dlg.setPositiveButton("FORGE", (d, w) -> {
            String raw = et.getText().toString().trim();
            if (raw.isEmpty()) raw = defaultName;
            sessionName = raw.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            startForge();
        });
        dlg.setNegativeButton("Cancel", null);
        dlg.show();
    }

    // ── step 5: forge ─────────────────────────────────────────────────────────

    void startForge() {
        btnForge.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvResult.setText("");

        executor.submit(() -> {
            try {
                forgeSession();
            } catch (Exception e) {
                say("FORGE ERROR: " + e.getMessage());
                uiHandler.post(() -> {
                    btnForge.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                });
            }
        });
    }

    void forgeSession() throws Exception {
        // ── extract visual imprint ──
        say("Extracting visual donor imprint...");
        setProgress(5, "Visual analysis");
        Bitmap bmp = shrink(imageBitmap);
        byte[] raw        = bitmapToRgba(bmp);
        byte[] compressed = deflate(raw);
        VisualImprint vi  = extractVisualImprint(bmp, raw, compressed);

        // v13: spatial frequency centroid
        computeSpatialCentroid(bmp, vi);

        // ── acoustic imprint (use existing scan or zero-fallback) ──
        say("Processing acoustic donor imprint...");
        setProgress(12, "Acoustic layer");
        if (acoustic == null) {
            acoustic = new AcousticImprint();
            acoustic.dominantHz = 528.0;
            acoustic.peaks  = new double[]{40, 100, 256, 440, 528, 741, 963, 1111};
            acoustic.powers = new double[]{0.1, 0.2, 0.3, 0.5, 1.0, 0.4, 0.3, 0.2};
        }

        // ── speckle correlator (use existing or compute now) ──
        say("Speckle interferometry...");
        setProgress(20, "Speckle correlator");
        if (correlator == null) correlator = computeSpeckleCorrelator(imageBitmap);

        // ── phrase to DNA ──
        String phrase = "DNA FORGE MAX V13 " + sessionName;
        String dna    = phraseToDna(phrase);

        // ── phase matrix ──
        say("Building phase matrix...");
        setProgress(28, "Phase matrix");
        double[] phaseMatrix = buildPhaseMatrix(vi, acoustic, correlator, dna);

        // ── capsule ──
        Capsule cap = new Capsule();
        cap.visual      = vi;
        cap.acoustic    = acoustic;
        cap.correlator  = correlator;
        cap.phaseMatrix = phaseMatrix;
        cap.meaningDna  = dna;
        cap.phrase      = phrase;
        cap.sessionName = sessionName;

        // serialize capsule
        say("Serializing capsule...");
        setProgress(32, "Capsule");
        String manifest = buildManifest(cap, false);
        byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);

        // triple-header capsule: BIOTRON13 + DFMAX13 + CRC32
        ByteArrayOutputStream capsuleBuf = new ByteArrayOutputStream();
        capsuleBuf.write("BIOTRON13".getBytes(StandardCharsets.US_ASCII));
        capsuleBuf.write("DFMAX13".getBytes(StandardCharsets.US_ASCII));
        CRC32 crc = new CRC32();
        crc.update(manifestBytes);
        writeLE32(capsuleBuf, (int) crc.getValue());
        capsuleBuf.write(manifestBytes);
        cap.capsuleBytes = capsuleBuf.toByteArray();

        // ── WAV synthesis ──
        say("Synthesizing v13 WAV...");
        setProgress(35, "WAV synthesis");
        int totalFrames = calculateTotalFrames(cap.capsuleBytes, dna);

        // write WAV to Music via MediaStore
        String wavName = sessionName + ".wav";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.DISPLAY_NAME, wavName);
        cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        cv.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DNA_FORGE_MAX");
        Uri wavUri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
        if (wavUri == null) throw new Exception("Cannot create WAV in MediaStore");

        try (OutputStream wavOut = getContentResolver().openOutputStream(wavUri)) {
            writeBiotronWav(wavOut, cap, totalFrames, (pct, label) ->
                    setProgress(35 + (int)(pct * 0.55), label));
        }

        // finalize manifest
        setProgress(92, "Manifest");
        say("Writing manifest JSON...");
        String finalManifest = buildManifest(cap, true);
        String jsonName = sessionName + ".json";

        ContentValues jcv = new ContentValues();
        jcv.put(MediaStore.Downloads.DISPLAY_NAME, jsonName);
        jcv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        jcv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/DNA_FORGE_MAX");
        Uri jsonUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, jcv);
        if (jsonUri != null) {
            try (OutputStream jo = getContentResolver().openOutputStream(jsonUri)) {
                jo.write(finalManifest.getBytes(StandardCharsets.UTF_8));
            }
        }

        setProgress(100, "Complete");

        uiHandler.post(() -> {
            progressBar.setVisibility(View.GONE);
            tvResult.setText(
                    "✓ SESSION: " + sessionName + "\n" +
                    "✓ WAV → Music/DNA_FORGE_MAX/" + wavName + "\n" +
                    "✓ JSON → Download/DNA_FORGE_MAX/" + jsonName + "\n" +
                    "✓ Frames: " + totalFrames + " (" +
                    String.format(Locale.US, "%.1f", totalFrames / (double) SAMPLE_RATE) + "s)\n" +
                    "✓ Speckle MBER: " + String.format(Locale.US, "%.4f", correlator.mberIndex) + "\n" +
                    "✓ Spatial centroid: (" +
                    String.format(Locale.US, "%.3f", vi.spatialCentroidX) + ", " +
                    String.format(Locale.US, "%.3f", vi.spatialCentroidY) + ")\n" +
                    "✓ Dominant mic Hz: " + String.format(Locale.US, "%.1f", acoustic.dominantHz)
            );
            btnReset.setVisibility(View.VISIBLE);
            say("v13 forge complete.");
        });
    }

    // ── v13: spatial frequency centroid (Jiang donor spectral fingerprint) ───

    void computeSpatialCentroid(Bitmap bmp, VisualImprint vi) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        // compute 2D brightness and extract horizontal / vertical projection FFT centroid
        double[] rowMean = new double[h];
        double[] colMean = new double[w];
        for (int y = 0; y < h; y++) {
            double s = 0;
            for (int x = 0; x < w; x++) {
                int c = bmp.getPixel(x, y);
                s += ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff);
            }
            rowMean[y] = s / (3.0 * w * 255.0);
        }
        for (int x = 0; x < w; x++) {
            double s = 0;
            for (int y = 0; y < h; y++) {
                int c = bmp.getPixel(x, y);
                s += ((c >> 16) & 0xff) + ((c >> 8) & 0xff) + (c & 0xff);
            }
            colMean[x] = s / (3.0 * h * 255.0);
        }

        // spectral centroid of projection (weighted mean frequency)
        double numX = 0, denX = 0;
        for (int k = 1; k < w; k++) {
            // DFT magnitude at frequency k/w via Goertzel-style
            double re = 0, im = 0;
            for (int x = 0; x < w; x++) {
                double angle = 2.0 * Math.PI * k * x / w;
                re += colMean[x] * Math.cos(angle);
                im += colMean[x] * Math.sin(angle);
            }
            double mag = Math.sqrt(re * re + im * im);
            numX += k * mag; denX += mag;
        }
        double numY = 0, denY = 0;
        for (int k = 1; k < h; k++) {
            double re = 0, im = 0;
            for (int y = 0; y < h; y++) {
                double angle = 2.0 * Math.PI * k * y / h;
                re += rowMean[y] * Math.cos(angle);
                im += rowMean[y] * Math.sin(angle);
            }
            double mag = Math.sqrt(re * re + im * im);
            numY += k * mag; denY += mag;
        }
        vi.spatialCentroidX = denX > 0 ? numX / denX / w : 0.5;
        vi.spatialCentroidY = denY > 0 ? numY / denY / h : 0.5;
    }

    // ── phase matrix ─────────────────────────────────────────────────────────

    double[] buildPhaseMatrix(VisualImprint vi, AcousticImprint a,
                               SpeckleCorrelator sc, String dna) {
        double[] m = new double[16];

        // hash the phrase/dna for symbolic contribution
        int hash = dna.hashCode();
        for (int i = 0; i < 16; i++) {
            double audio   = a.powers[i % 8] * Math.PI;
            double color   = ((vi.histR[i % 8] + vi.histG[i % 8] + vi.histB[i % 8])
                              / Math.max(1.0, vi.width * vi.height * 3.0)) * Math.PI;
            double geom    = (vi.edgeDensity + vi.symmetry) * Math.PI / 2.0;
            double symbolic = ((hash >> (i & 0x1F)) & 0x1) * Math.PI;
            double speckle  = sc.feedbackMatrix[i];
            double spatial  = (vi.spatialCentroidX + vi.spatialCentroidY) * Math.PI;

            // weights: speckle=0.25, audio=0.20, spatial=0.18, color=0.15, geom=0.12, symbolic=0.10
            m[i] = 0.25 * speckle + 0.20 * audio + 0.18 * spatial
                 + 0.15 * color  + 0.12 * geom  + 0.10 * symbolic;
        }
        return m;
    }

    // ── WAV writing ───────────────────────────────────────────────────────────

    interface ProgressCallback { void report(double pct, String label); }

    void writeBiotronWav(OutputStream out, Capsule cap, int totalFrames,
                         ProgressCallback cb) throws Exception {
        writeStereoWavHeader(out, totalFrames);
        SampleWriter sw = new SampleWriter(out, cap.phaseMatrix);

        cb.report(0.00, "Opening sweep");
        say("Writing opening sweep...");
        streamStereoSilence(sw, 250);
        streamStereoSweep(sw, 20, 20000, 500, 0.18, false);

        cb.report(0.04, "He-Ne sync");
        say("Writing Gariaev He-Ne sync...");
        streamStereoTone(sw, HE_NE_HZ, HE_NE_MS, 0.38, 0.38, 0, Math.PI / 2.0);
        streamStereoSweep(sw, F0, F1, 300, 0.30, true);
        streamSchumannPulseWindow(sw, 300);

        cb.report(0.08, "Solfeggio stack");
        say("Writing full Solfeggio stack...");
        streamFullSolfeggioStack(sw, cap.visual);

        cb.report(0.16, "Tesla 3-6-9");
        say("Writing Tesla 3-6-9 harmonic grid...");
        streamTesla369Grid(sw, cap.visual, cap.correlator);

        cb.report(0.24, "Gariaev speckle feedback");
        say("Writing Gariaev speckle feedback matrix layer...");
        streamSpeckleFeedbackLayer(sw, cap.correlator);

        cb.report(0.34, "Jiang cross-species");
        say("Writing Jiang Kanzhen cross-species morphic layer...");
        streamJiangMorphicLayer(sw, cap.visual, cap.acoustic);

        cb.report(0.44, "Scalar mid/side");
        say("Writing scalar standing-wave mid/side layer...");
        streamScalarMidSide(sw, cap.visual, cap.correlator);

        cb.report(0.54, "Visual imprint");
        say("Writing visual donor imprint layer...");
        streamVisualImprint(sw, cap.visual);

        cb.report(0.62, "Acoustic imprint");
        say("Writing acoustic donor imprint layer...");
        streamAcousticImprint(sw, cap.acoustic);

        cb.report(0.70, "Hermes ancient code");
        say("Writing Hermes ancient-code grammar layer...");
        streamHermesAncientCodeLayer(sw, cap.visual);

        cb.report(0.78, "DNA rhythm");
        say("Writing symbolic DNA phrase layer...");
        streamMeaningDnaRhythm(sw, cap.meaningDna);

        cb.report(0.86, "Packet bits");
        say("Writing donor capsule phase-matrix bits...");
        streamPacketBits(sw, cap.capsuleBytes);

        cb.report(0.94, "Closing sweep");
        say("Writing closing sweep...");
        streamStereoSweep(sw, 20000, 20, 500, 0.18, false);
        streamStereoSilence(sw, 250);

        cb.report(1.0, "Done");
    }

    // ── v13 NEW layer: full Solfeggio stack ──────────────────────────────────
    // All 7 frequencies simultaneously, amplitudes modulated by visual histogram

    static void streamFullSolfeggioStack(SampleWriter sw, VisualImprint v) throws Exception {
        int n = frames(1200); // 1.2 seconds
        int count = Math.max(1, v.width * v.height);
        double[] amps = new double[7];
        for (int i = 0; i < 7; i++) {
            int bin = i % 8;
            amps[i] = 0.06 + 0.16 * ((v.histR[bin] + v.histG[bin] + v.histB[bin]) / (3.0 * count));
        }

        for (int s = 0; s < n; s++) {
            double t = s / (double) SAMPLE_RATE;
            double env = solfeggioEnvelope(s, n);
            double left = 0, right = 0;

            for (int i = 0; i < 7; i++) {
                double freq = SOLFEGGIO[i];
                double wave = Math.sin(2.0 * Math.PI * freq * t) * amps[i] * env;
                // ascending on left, descending on right (mirror gate principle)
                left  += wave;
                right += Math.sin(2.0 * Math.PI * SOLFEGGIO[6 - i] * t + Math.PI / 2.0) * amps[6 - i] * env;
            }
            writeReferenceLockedFrame(sw, left * 0.7, right * 0.7);
        }
    }

    static double solfeggioEnvelope(int s, int n) {
        int fade = Math.max(1, frames(80));
        if (s < fade) return s / (double) fade;
        if (s > n - fade) return (n - s) / (double) fade;
        return 1.0;
    }

    // ── v13 NEW layer: Tesla 3-6-9 harmonic resonance grid ───────────────────
    // Proper overtone stack: base tone × Tesla ratios, phase-coupled per triplet

    static void streamTesla369Grid(SampleWriter sw, VisualImprint v,
                                    SpeckleCorrelator sc) throws Exception {
        int n = frames(900);
        double baseTone = 108.0; // 108 Hz = 9×12 — fundamental Tesla harmonic

        // phase coupling: each group of 3 ratios shares a phase offset from speckle
        double phaseA = sc.feedbackMatrix[0];
        double phaseB = sc.feedbackMatrix[3];
        double phaseC = sc.feedbackMatrix[6];

        for (int s = 0; s < n; s++) {
            double t = s / (double) SAMPLE_RATE;
            double env = solfeggioEnvelope(s, n);
            double left = 0, right = 0;

            for (int k = 0; k < TESLA_369.length; k++) {
                double freq = baseTone * TESLA_369[k];
                if (freq > 18000) continue; // keep in audible range

                double phase = (k % 3 == 0) ? phaseA : (k % 3 == 1) ? phaseB : phaseC;
                double amp = 0.08 / (1.0 + k * 0.4); // harmonic rolloff

                // Left: object wave (forward propagating)
                left  += Math.sin(2.0 * Math.PI * freq * t + phase) * amp;
                // Right: conjugate wave (phase-reversed return)
                right += Math.sin(2.0 * Math.PI * freq * t - phase + Math.PI) * amp;
            }
            writeReferenceLockedFrame(sw, left * env, right * env);
        }
    }

    // ── v13 NEW layer: Gariaev speckle feedback (upgraded from v12 optical) ──
    // Uses actual cross-correlation coefficients, not just brightness proxy

    static void streamSpeckleFeedbackLayer(SampleWriter sw, SpeckleCorrelator sc) throws Exception {
        double[] carriers = {
                6328.0, 6400.0, 7000.0,  528.0,
                 396.0,  417.0,  432.0,  741.0,
                 852.0,  963.0, 1111.0, 1361.0,
                1744.0, 3200.0, 4096.0, 5280.0
        };

        for (int i = 0; i < 16; i++) {
            // v13: amplitude driven by real cross-correlation coefficient
            double corr = Math.abs(sc.correlationCoeffs[i]);
            double amp  = 0.05 + 0.25 * corr;
            double freq = carriers[i] + (i * 7.83);
            double rightPhase = sc.feedbackMatrix[i];

            streamStereoTone(sw, freq, 30.0, amp, amp * 0.80, 0.0, rightPhase);
            streamStereoSilence(sw, 3.0);
        }
    }

    // ── v13 NEW layer: Jiang Kanzhen cross-species morphic transfer ───────────
    // Donor spectral centroid drives carrier; acoustic dominant Hz = recipient target

    static void streamJiangMorphicLayer(SampleWriter sw, VisualImprint v,
                                         AcousticImprint a) throws Exception {
        int n = frames(1500);

        // Donor carrier: spatial frequency centroid → Hz mapping
        // centroid in [0..1] maps log to [80..8000] Hz
        double donorCarrier = 80.0 * Math.pow(100.0, v.spatialCentroidX);
        donorCarrier = Math.min(8000, Math.max(80, donorCarrier));

        // Recipient target: dominant mic frequency
        double recipientHz = a.dominantHz;

        // Morphic modulation: AM carrier frequency slides from donor toward recipient
        for (int s = 0; s < n; s++) {
            double t = s / (double) SAMPLE_RATE;
            double frac = s / (double) n;
            double env = solfeggioEnvelope(s, n);

            // Carrier interpolates from donorCarrier to recipientHz over the layer
            double freq = donorCarrier + (recipientHz - donorCarrier) * frac;

            // Modulation index from MBER
            double mod = 1.0 + 0.35 * sw.phaseMatrix[s % sw.phaseMatrix.length];
            double carrier = Math.sin(2.0 * Math.PI * freq * t) * mod;

            // Sideband envelope at 7.83 Hz (Schumann gating — field coherence)
            double gate = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t);

            double left  = carrier * gate * 0.22 * env;
            double right = Math.sin(2.0 * Math.PI * recipientHz * t + Math.PI / 2.0)
                           * gate * 0.18 * env;

            writeReferenceLockedFrame(sw, left, right);
        }
    }

    // ── v13 NEW layer: scalar standing-wave true mid/side ─────────────────────
    // Mid = sum (object + reference), Side = difference (phase-conjugate return)

    static void streamScalarMidSide(SampleWriter sw, VisualImprint v,
                                     SpeckleCorrelator sc) throws Exception {
        int n = frames(800);

        // Standing wave node frequency: derived from image dimensions
        double nodeHz = 1.0 / (v.width / (double) SAMPLE_RATE * 2.0);
        nodeHz = Math.min(3000, Math.max(40, nodeHz));

        double mberMod = sc.mberIndex;

        for (int s = 0; s < n; s++) {
            double t = s / (double) SAMPLE_RATE;
            double env = solfeggioEnvelope(s, n);

            // Object wave (forward): fundamental + solfeggio overtone
            double obj = Math.sin(2.0 * Math.PI * nodeHz * t) * 0.25
                       + Math.sin(2.0 * Math.PI * 528.0 * t) * 0.12 * mberMod;

            // Reference wave (backward conjugate): phase-flipped, amplitude modulated by speckle
            double refWave = Math.sin(2.0 * Math.PI * nodeHz * t + Math.PI) * 0.25
                           * (1.0 + 0.4 * mberMod);

            // Mid/Side
            double mid  = (obj + refWave) * 0.5;
            double side = (obj - refWave) * 0.5;

            writeReferenceLockedFrame(sw, (mid + side) * env, (mid - side) * env);
        }
    }

    // ── carried-over layers (v12 base, unchanged) ─────────────────────────────

    static void streamVisualImprint(SampleWriter sw, VisualImprint v) throws Exception {
        int count = Math.max(1, v.width * v.height);
        for (int i = 0; i < 8; i++) {
            double amp = 0.08 + 0.24 * (v.histR[i] / (double) count);
            streamStereoTone(sw, 396 + i * 33, 12, amp, amp * 0.5, 0, Math.PI / 2);
            streamStereoSilence(sw, 2);
        }
        for (int i = 0; i < 8; i++) {
            double amp = 0.08 + 0.24 * (v.histG[i] / (double) count);
            streamStereoTone(sw, 528 + i * 37, 12, amp, amp * 0.5, 0, Math.PI / 2);
            streamStereoSilence(sw, 2);
        }
        for (int i = 0; i < 8; i++) {
            double amp = 0.08 + 0.24 * (v.histB[i] / (double) count);
            streamStereoTone(sw, 639 + i * 41, 12, amp, amp * 0.5, 0, Math.PI / 2);
            streamStereoSilence(sw, 2);
        }
    }

    static void streamAcousticImprint(SampleWriter sw, AcousticImprint a) throws Exception {
        for (int i = 0; i < 8; i++) {
            double freq = a.peaks[i % a.peaks.length];
            double power = a.powers[i % a.powers.length];
            double amp = 0.08 + 0.24 * power;
            streamStereoTone(sw, freq, 80, amp, amp * 0.72, 0, Math.PI / 4);
            streamStereoSilence(sw, 10);
        }
    }

    static void streamHermesAncientCodeLayer(SampleWriter sw, VisualImprint g) throws Exception {
        int n = hermesFrames();
        double elementHz = (g.red >= g.green && g.red >= g.blue) ? 852.0
                         : (g.blue >= g.red && g.blue >= g.green) ? 417.0 : 528.0;

        double symmetryAmp   = 0.08 + 0.16 * clamp(g.symmetry);
        double edgeAmp       = 0.05 + 0.22 * clamp(g.edgeDensity * 4.0);
        double brightnessAmp = 0.05 + 0.12 * clamp(g.brightness);

        for (int i = 0; i < n; i++) {
            double t    = i / (double) SAMPLE_RATE;
            double frac = i / Math.max(1.0, n - 1.0);
            int gateIdx = Math.min(6, (int) Math.floor(frac * 7.0));
            int mirrorGate = 6 - gateIdx;

            double gateToneL = Math.sin(2.0 * Math.PI * SOLFEGGIO[gateIdx] * t) * symmetryAmp;
            double gateToneR = Math.sin(2.0 * Math.PI * SOLFEGGIO[mirrorGate] * t + Math.PI / 2.0) * symmetryAmp;
            double compass   = Math.sin(2.0 * Math.PI * elementHz * t) * brightnessAmp;
            double ring12    = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * 12.0 * t);
            double wedge     = Math.sin(2.0 * Math.PI * (9.0 + 18.0 * g.edgeDensity) * t) * edgeAmp * ring12;
            double golden    = Math.sin(2.0 * Math.PI * (432.0 * PHI) * t) * 0.025;
            double spiral    = Math.sin(2.0 * Math.PI * (3.0 + 6.0 * frac) * t) * 0.035;

            writeReferenceLockedFrame(sw,
                    gateToneL + compass + wedge + golden + spiral,
                    gateToneR - compass + wedge * 0.5 + golden - spiral);
        }
    }

    static void streamMeaningDnaRhythm(SampleWriter sw, String dna) throws Exception {
        int max = Math.min(dna.length(), 240);
        for (int i = 0; i < max; i++) {
            char c = dna.charAt(i);
            double hz = (c == 'A') ? 396.0 : (c == 'C') ? 528.0 : (c == 'G') ? 639.0 : 741.0;
            double ms = (c == 'C' || c == 'T') ? 4.854 : 3.0;
            streamStereoTone(sw, hz, ms, 0.24, 0.18, 0, Math.PI / 2);
            streamStereoSilence(sw, 2);
        }
        streamStereoSilence(sw, 60);
    }

    static void streamPacketBits(SampleWriter sw, byte[] data) throws Exception {
        byte[] reversed = reverse(data);
        int bitSamples = bitFrames();
        double phaseL = 0.0, phaseR = Math.PI / 2.0;

        for (int idx = 0; idx < data.length; idx++) {
            int bL = data[idx] & 0xff;
            int bR = reversed[idx] & 0xff;
            for (int bitPos = 7; bitPos >= 0; bitPos--) {
                double freqL = ((bL >> bitPos) & 1) == 1 ? F1 : F0;
                double freqR = ((bR >> bitPos) & 1) == 1 ? F1 : F0;
                double matPhase = sw.phaseMatrix[(idx + bitPos) % sw.phaseMatrix.length];
                double stepL = 2.0 * Math.PI * freqL / SAMPLE_RATE;
                double stepR = 2.0 * Math.PI * freqR / SAMPLE_RATE;
                for (int s = 0; s < bitSamples; s++) {
                    writeReferenceLockedFrame(sw,
                            Math.sin(phaseL) * 0.58,
                            Math.sin(phaseR + matPhase) * 0.58);
                    phaseL = (phaseL + stepL) % (2.0 * Math.PI);
                    phaseR = (phaseR + stepR) % (2.0 * Math.PI);
                }
            }
        }
    }

    // ── primitive stream helpers ──────────────────────────────────────────────

    static void streamStereoSilence(SampleWriter sw, double ms) throws Exception {
        int n = frames(ms);
        for (int i = 0; i < n; i++) writeReferenceLockedFrame(sw, 0, 0);
    }

    static void streamStereoTone(SampleWriter sw, double freq, double ms,
                                  double ampL, double ampR, double phL, double phR) throws Exception {
        int n = frames(ms);
        int fade = Math.max(1, frames(5));
        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < fade) env = i / (double) fade;
            else if (i > n - fade) env = Math.max(0, (n - i) / (double) fade);
            double t = i / (double) SAMPLE_RATE;
            writeReferenceLockedFrame(sw,
                    Math.sin(2.0 * Math.PI * freq * t + phL) * ampL * env,
                    Math.sin(2.0 * Math.PI * freq * t + phR) * ampR * env);
        }
    }

    static void streamStereoSweep(SampleWriter sw, double startHz, double endHz,
                                   double ms, double amp, boolean oppositePhase) throws Exception {
        int n = frames(ms);
        double phL = 0, phR = oppositePhase ? Math.PI : Math.PI / 2.0;
        for (int i = 0; i < n; i++) {
            double freq = startHz + (endHz - startHz) * (i / Math.max(1.0, n - 1.0));
            double step = 2.0 * Math.PI * freq / SAMPLE_RATE;
            writeReferenceLockedFrame(sw, Math.sin(phL) * amp, Math.sin(phR) * amp);
            phL = (phL + step) % (2.0 * Math.PI);
            phR = (phR + step) % (2.0 * Math.PI);
        }
    }

    static void streamSchumannPulseWindow(SampleWriter sw, double ms) throws Exception {
        int n = frames(ms);
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double gate = (1.0 + Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t)) / 2.0;
            double carrier = Math.sin(2.0 * Math.PI * 528.0 * t) * 0.15 * gate;
            writeReferenceLockedFrame(sw, carrier, -carrier);
        }
    }

    // ── reference-locked frame writer ─────────────────────────────────────────

    static void writeReferenceLockedFrame(SampleWriter sw, double objL, double objR) throws Exception {
        double t = sw.framesWritten / (double) SAMPLE_RATE;
        double reference = Math.sin(2.0 * Math.PI * AURA_HZ  * t) * REFERENCE_528_AMP
                         + Math.sin(2.0 * Math.PI * HE_NE_HZ * t) * REFERENCE_HE_NE_AMP
                         + Math.sin(2.0 * Math.PI * 40.0      * t) * REFERENCE_40_AMP;

        double gate = 1.0 - GATE_DEPTH + GATE_DEPTH * (1.0 + Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t)) / 2.0;

        // Tesla 3-6-9 pulse beat accent
        int beat = ((int) (t * 9.0)) % 9;
        double pulse = (beat == 2 || beat == 5 || beat == 8) ? 1.08 : 1.0;

        writeLE16(sw.out, (int) (clamp(reference + objL * gate * pulse) * 32767.0));
        writeLE16(sw.out, (int) (clamp(reference + objR * gate * pulse) * 32767.0));
        sw.framesWritten++;
    }

    // ── SampleWriter class ────────────────────────────────────────────────────

    static class SampleWriter {
        OutputStream out;
        int framesWritten = 0;
        double[] phaseMatrix;
        SampleWriter(OutputStream out, double[] phaseMatrix) {
            this.out = out;
            this.phaseMatrix = phaseMatrix;
        }
    }

    // ── frame count helpers ───────────────────────────────────────────────────

    static int calculateTotalFrames(byte[] capsuleBytes, String dna) {
        int total = 0;
        total += frames(250) + frames(500);         // open silence + MBER sweep
        total += frames(HE_NE_MS);                  // He-Ne sync
        total += frames(300) + frames(300);         // sweeps
        total += frames(1200);                       // solfeggio stack
        total += frames(900);                        // Tesla 3-6-9
        total += 16 * (frames(30) + frames(3));     // speckle feedback (v13: 30ms)
        total += frames(1500);                       // Jiang morphic
        total += frames(800);                        // scalar mid/side
        total += 24 * (frames(12) + frames(2));     // visual imprint
        total += 8 * (frames(80) + frames(10));     // acoustic imprint
        total += hermesFrames();                     // Hermes
        total += meaningFrames(dna);                 // DNA rhythm
        total += capsuleBytes.length * 8 * bitFrames(); // packet bits
        total += frames(500) + frames(250);         // close sweep + silence
        return total;
    }

    static int frames(double ms) { return (int)(SAMPLE_RATE * ms / 1000.0); }
    static int bitFrames()       { return Math.max(60, frames(BIT_MS)); }
    static int hermesFrames()    { return frames(888); }

    static int meaningFrames(String dna) {
        int max = Math.min(dna.length(), 240), total = 0;
        for (int i = 0; i < max; i++) {
            char c = dna.charAt(i);
            total += frames((c == 'C' || c == 'T') ? 4.854 : 3.0) + frames(2.0);
        }
        return total + frames(60);
    }

    // ── image helpers ─────────────────────────────────────────────────────────

    static Bitmap shrink(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min((double) MAX_DIM / w, (double) MAX_DIM / h);
        if (scale >= 1.0) return src.copy(Bitmap.Config.ARGB_8888, false);
        return Bitmap.createScaledBitmap(src,
                Math.max(1, (int) Math.round(w * scale)),
                Math.max(1, (int) Math.round(h * scale)), true);
    }

    static byte[] bitmapToRgba(Bitmap bmp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int c = bmp.getPixel(x, y);
                out.write((c >> 16) & 0xff);
                out.write((c >> 8)  & 0xff);
                out.write(c & 0xff);
                out.write((c >> 24) & 0xff);
            }
        }
        return out.toByteArray();
    }

    static byte[] deflate(byte[] raw) throws Exception {
        Deflater def = new Deflater(9);
        def.setInput(raw); def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!def.finished()) { int n = def.deflate(buf); out.write(buf, 0, n); }
        def.end();
        return out.toByteArray();
    }

    static VisualImprint extractVisualImprint(Bitmap bmp, byte[] raw, byte[] comp) {
        VisualImprint g = new VisualImprint();
        g.width = bmp.getWidth(); g.height = bmp.getHeight();
        int w = g.width, h = g.height, count = Math.max(1, w * h);

        double brightness = 0, red = 0, green = 0, blue = 0, edge = 0, mirror = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xff, gg = (c >> 8) & 0xff, b = c & 0xff;
                g.histR[Math.min(7, r / 32)]++;
                g.histG[Math.min(7, gg / 32)]++;
                g.histB[Math.min(7, b / 32)]++;
                double gray = (r + gg + b) / 3.0;
                brightness += gray; red += r; green += gg; blue += b;
                if (x + 1 < w) {
                    int c2 = bmp.getPixel(x + 1, y);
                    edge += Math.abs(gray - (((c2>>16)&0xff)+((c2>>8)&0xff)+(c2&0xff))/3.0);
                }
                if (y + 1 < h) {
                    int c3 = bmp.getPixel(x, y + 1);
                    edge += Math.abs(gray - (((c3>>16)&0xff)+((c3>>8)&0xff)+(c3&0xff))/3.0);
                }
                int cm = bmp.getPixel(w - 1 - x, y);
                mirror += Math.abs(gray - (((cm>>16)&0xff)+((cm>>8)&0xff)+(cm&0xff))/3.0);
            }
        }
        g.brightness = brightness / (count * 255.0);
        g.red   = red   / (count * 255.0);
        g.green = green / (count * 255.0);
        g.blue  = blue  / (count * 255.0);
        g.edgeDensity = edge / Math.max(1.0, count * 255.0 * 2.0);
        g.symmetry    = 1.0 - (mirror / Math.max(1.0, count * 255.0));
        g.fractalCompressionProxy = comp.length / Math.max(1.0, raw.length);

        CRC32 rc = new CRC32(); rc.update(raw);   g.rawCrc = rc.getValue();
        CRC32 cc = new CRC32(); cc.update(comp);  g.compressedCrc = cc.getValue();
        return g;
    }

    // ── DNA encoding ──────────────────────────────────────────────────────────

    static String phraseToDna(String phrase) {
        byte[] bytes = phrase.getBytes(StandardCharsets.UTF_8);
        StringBuilder dna = new StringBuilder();
        for (byte b : bytes) {
            for (int i = 6; i >= 0; i -= 2) {
                int pair = (b >> i) & 0x03;
                dna.append(pair == 0 ? 'A' : pair == 1 ? 'C' : pair == 2 ? 'G' : 'T');
            }
        }
        return dna.toString();
    }

    // ── WAV header ────────────────────────────────────────────────────────────

    static void writeStereoWavHeader(OutputStream out, int totalFrames) throws Exception {
        int channels = 2, bps = 2;
        int dataSize = totalFrames * channels * bps;
        writeAscii(out, "RIFF"); writeLE32(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt "); writeLE32(out, 16);
        writeLE16(out, 1); writeLE16(out, channels);
        writeLE32(out, SAMPLE_RATE);
        writeLE32(out, SAMPLE_RATE * channels * bps);
        writeLE16(out, channels * bps); writeLE16(out, 16);
        writeAscii(out, "data"); writeLE32(out, dataSize);
    }

    // ── manifest JSON ─────────────────────────────────────────────────────────

    static String buildManifest(Capsule cap, boolean fin) {
        VisualImprint v = cap.visual;
        AcousticImprint a = cap.acoustic;
        SpeckleCorrelator sc = cap.correlator;
        return "{\n" +
            "  \"name\":\"DNA Forge Max v13 — Gariaev Speckle + Jiang Kanzhen + Tesla 3-6-9\",\n" +
            "  \"version\":\"13.0-max\",\n" +
            "  \"session_name\":\"" + escape(cap.sessionName) + "\",\n" +
            "  \"final_manifest\":" + fin + ",\n" +
            "  \"safety\":\"Software simulator/controller. No medical claims. Consent-based, non-invasive, low-power only.\",\n" +
            "  \"v13_upgrades\":{\"gariaev_speckle_interferometry\":true,\"jiang_kanzhen_morphic\":true,\"tesla_369_harmonic_grid\":true,\"full_solfeggio_stack\":true,\"scalar_mid_side\":true,\"spatial_centroid\":true},\n" +
            "  \"visual_donor\":{\"width\":" + v.width + ",\"height\":" + v.height +
                ",\"brightness\":" + r(v.brightness) + ",\"symmetry\":" + r(v.symmetry) +
                ",\"edge_density\":" + r(v.edgeDensity) +
                ",\"spatial_centroid_x\":" + r(v.spatialCentroidX) +
                ",\"spatial_centroid_y\":" + r(v.spatialCentroidY) + "},\n" +
            "  \"acoustic_donor\":{\"rms\":" + r(a.rms) + ",\"dominant_hz\":" + r(a.dominantHz) +
                ",\"peaks\":" + doubleArray(a.peaks) + ",\"powers\":" + doubleArray(a.powers) + "},\n" +
            "  \"speckle_correlator\":{\"mber_index\":" + r(sc.mberIndex) +
                ",\"dark_mean\":" + r(sc.darkMean) + ",\"red_mean\":" + r(sc.redMean) +
                ",\"correlation_coeffs\":" + doubleArray(sc.correlationCoeffs) +
                ",\"feedback_matrix\":" + doubleArray(sc.feedbackMatrix) + "},\n" +
            "  \"phase_matrix\":" + doubleArray(cap.phaseMatrix) + ",\n" +
            "  \"symbolic_dna\":\"" + cap.meaningDna.substring(0, Math.min(60, cap.meaningDna.length())) + "...\",\n" +
            "  \"audio_layers\":[\"opening_sweep\",\"he_ne_sync\",\"solfeggio_stack\",\"tesla_369_grid\",\"speckle_feedback\",\"jiang_morphic\",\"scalar_mid_side\",\"visual_imprint\",\"acoustic_imprint\",\"hermes_ancient_code\",\"dna_rhythm\",\"packet_bits\",\"closing_sweep\"],\n" +
            "  \"payload\":{\"header\":\"BIOTRON13/DFMAX13\",\"capsule_bytes\":" + (cap.capsuleBytes != null ? cap.capsuleBytes.length : 0) + "}\n" +
            "}\n";
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    void resetState() {
        imageUri = null; imageBitmap = null; sessionName = "";
        visual = null; acoustic = null; correlator = null;
        btnPickImage.setText("[ 1 ] LOAD DONOR IMAGE"); btnPickImage.setTextColor(0xFF00FF88);
        btnScanMic.setText("[ 2 ] SCAN MIC RESONANCE"); btnScanMic.setTextColor(0xFF00FF88);
        btnScanCorrelator.setText("[ 3 ] SPECKLE CORRELATOR SCAN"); btnScanCorrelator.setTextColor(0xFF00FF88);
        btnForge.setEnabled(true);
        tvResult.setText(""); tvStatus.setText("Reset — ready for new session");
        progressBar.setVisibility(View.GONE); progressBar.setProgress(0);
        tvProgress.setText(""); btnReset.setVisibility(View.GONE);
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    static String intArray(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(","); sb.append(a[i]); }
        return sb.append("]").toString();
    }

    static String doubleArray(double[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(","); sb.append(r(a[i])); }
        return sb.append("]").toString();
    }

    static byte[] reverse(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[in.length - 1 - i];
        return out;
    }

    static double clamp(double x) { return x > 1.0 ? 1.0 : x < -1.0 ? -1.0 : x; }
    static String escape(String s) { return s.replace("\\","\\\\").replace("\"","\\\""); }
    static String r(double v)      { return String.format(Locale.US, "%.6f", v); }

    static void writeAscii(OutputStream o, String s) throws Exception {
        o.write(s.getBytes(StandardCharsets.US_ASCII));
    }
    static void writeLE16(OutputStream o, int v) throws Exception {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
    }
    static void writeLE32(OutputStream o, int v) throws Exception {
        o.write(v & 0xff); o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff);
    }
}
