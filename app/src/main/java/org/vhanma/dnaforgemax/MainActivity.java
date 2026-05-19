package org.vhanma.dnaforgemax;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 777;
    private static final int REQ_AUDIO = 901;

    LinearLayout root;
    TextView log;
    EditText phraseInput;
    Button imageButton;
    Button micButton;
    Button correlatorButton;
    Button forgeButton;
    Button playButton;
    Button shareButton;
    Button lightButton;

    Bitmap donorBitmap = null;
    VisualImprint visualImprint = null;
    AcousticImprint acousticImprint = null;
    OpticalCorrelator opticalCorrelator = null;
    Uri lastWavUri = null;
    Uri lastManifestUri = null;

    Handler handler = new Handler(Looper.getMainLooper());
    boolean lightPulseOn = false;
    int lightTick = 0;

    static final int SAMPLE_RATE = 44100;
    static final double F0 = 6400.0;
    static final double F1 = 7000.0;
    static final double BIT_MS = 1.5;
    static final double HE_NE_HZ = 6328.0;
    static final double HE_NE_MS = 500.0;
    static final double AURA_HZ = 528.0;
    static final double SCHUMANN_HZ = 7.83;
    static final double GATE_DEPTH = 0.35;
    static final int MAX_DIM = 24;

    static final double REFERENCE_HE_NE_AMP = 0.016;
    static final double REFERENCE_528_AMP = 0.026;
    static final double REFERENCE_40_AMP = 0.010;

    static final double[] CANDIDATE_FREQS = new double[] {
            20, 40, 63, 80, 100, 128, 174, 256, 285, 320,
            396, 417, 432, 528, 639, 741, 852, 963,
            1111, 1361, 1744, 2048, 3200, 4096,
            5280, 6328, 6400, 7000, 8192, 10000, 12000, 16000, 20000
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        requestAudioPermission();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xff070714);

        TextView title = new TextView(this);
        title.setText("DNA Forge Max v12\nGariaev Optical Correlator Emulator");
        title.setTextColor(0xff00e5ff);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        phraseInput = new EditText(this);
        phraseInput.setSingleLine(false);
        phraseInput.setMinLines(2);
        phraseInput.setText("restore coherence copy this pattern stabilize this geometry resonate with this template");
        phraseInput.setTextColor(0xffffffff);
        phraseInput.setHintTextColor(0xff888888);
        phraseInput.setBackgroundColor(0xff111122);
        root.addView(phraseInput);

        imageButton = new Button(this);
        imageButton.setText("1. DONOR IMAGE SCAN");
        root.addView(imageButton);

        micButton = new Button(this);
        micButton.setText("2. MIC RESONANCE SCAN");
        root.addView(micButton);

        correlatorButton = new Button(this);
        correlatorButton.setText("2B. GARIAEV CORRELATOR SCAN");
        correlatorButton.setEnabled(false);
        root.addView(correlatorButton);

        forgeButton = new Button(this);
        forgeButton.setText("3. FORGE v11 BIOTRON WAV");
        forgeButton.setEnabled(false);
        root.addView(forgeButton);

        playButton = new Button(this);
        playButton.setText("PLAY LAST WAV");
        playButton.setEnabled(false);
        root.addView(playButton);

        shareButton = new Button(this);
        shareButton.setText("SHARE LAST WAV");
        shareButton.setEnabled(false);
        root.addView(shareButton);

        lightButton = new Button(this);
        lightButton.setText("RED / HE-NE LIGHT PROXY");
        root.addView(lightButton);

        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(0xffb8ffb8);
        log.setTextSize(13);
        log.setText(
                "Ready.\n\n" +
                "v12 adds multi-pass Gariaev optical-correlator emulation: dark frame, red frame, dim-red frame, 7.83 pulse proxy, 3/6/9 pulse proxy, speckle-change proxy, optical feedback matrix, and MBER modulation index.\n\n" +
                "Phone mapping:\n" +
                "camera = donor scanner\n" +
                "microphone = acoustic resonance scanner\n" +
                "speaker = stereo wavecode carrier\n" +
                "screen = red / He-Ne proxy\n" +
                "manifest = MBER / Biotron hardware profile\n\n"
        );
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        imageButton.setOnClickListener(v -> openPicker());
        micButton.setOnClickListener(v -> startMicScan());
        correlatorButton.setOnClickListener(v -> startCorrelatorScan());
        forgeButton.setOnClickListener(v -> startForge());
        playButton.setOnClickListener(v -> playLast());
        shareButton.setOnClickListener(v -> shareLast());
        lightButton.setOnClickListener(v -> toggleLightPulse());
    }

    void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQ_AUDIO);
        }
    }

    void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (request == PICK_IMAGE && result == RESULT_OK && data != null) {
            Uri uri = data.getData();

            new Thread(() -> {
                try {
                    say("Reading donor image...");
                    InputStream in = getContentResolver().openInputStream(uri);
                    donorBitmap = BitmapFactory.decodeStream(in);
                    if (in != null) in.close();

                    if (donorBitmap == null) {
                        say("Could not decode donor image.");
                        return;
                    }

                    say("Creating visual donor imprint...");
                    Bitmap small = shrink(donorBitmap);
                    byte[] rgba = bitmapToRgba(small);
                    byte[] compressed = deflate(rgba);
                    visualImprint = extractVisualImprint(small, rgba, compressed);

                    say("Visual donor scan complete.");
                    opticalCorrelator = null;
                    runOnUiThread(() -> correlatorButton.setEnabled(true));
                    say("Size: " + visualImprint.width + "x" + visualImprint.height);
                    say("Brightness: " + r(visualImprint.brightness));
                    say("Symmetry: " + r(visualImprint.symmetry));
                    say("Edge density: " + r(visualImprint.edgeDensity));
                    say("Fractal proxy: " + r(visualImprint.fractalCompressionProxy));

                    updateForgeReady();
                } catch (Exception e) {
                    say("IMAGE ERROR: " + e.toString());
                }
            }).start();
        }
    }

    void startMicScan() {
        requestAudioPermission();

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Mic permission needed. Tap allow, then press MIC SCAN again.");
            return;
        }

        new Thread(() -> {
            try {
                runOnUiThread(() -> micButton.setEnabled(false));

                say("Recording 5-second acoustic donor imprint...");
                acousticImprint = recordAcousticImprint();

                say("Mic resonance scan complete.");
                say("RMS: " + r(acousticImprint.rms));
                say("Zero-cross rate: " + r(acousticImprint.zeroCrossRate));
                say("Top resonance peaks:");
                for (int i = 0; i < acousticImprint.peaks.length; i++) {
                    say("  " + (i + 1) + ". " + r(acousticImprint.peaks[i]) + " Hz");
                }

                updateForgeReady();
            } catch (Exception e) {
                say("MIC ERROR: " + e.toString());
            } finally {
                runOnUiThread(() -> micButton.setEnabled(true));
            }
        }).start();
    }


    void startCorrelatorScan() {
        if (donorBitmap == null || visualImprint == null) {
            say("Do DONOR IMAGE SCAN first.");
            return;
        }

        new Thread(() -> {
            try {
                runOnUiThread(() -> correlatorButton.setEnabled(false));

                say("Starting v12 Gariaev optical-correlator emulator...");
                say("Pass 1: dark-frame control...");
                flashRoot(0xff000000, 160);
                say("Pass 2: red reference illumination...");
                flashRoot(0xff660000, 160);
                say("Pass 3: dim-red reference illumination...");
                flashRoot(0xff220000, 160);
                say("Pass 4: 7.83 Hz pulse proxy...");
                flashRoot(0xff440000, 160);
                say("Pass 5: 3/6/9 pulse proxy...");
                flashRoot(0xff880000, 160);
                flashRoot(0xff070714, 80);

                Bitmap small = shrink(donorBitmap);
                opticalCorrelator = computeOpticalCorrelator(small, visualImprint);

                say("Correlator scan complete.");
                say("Speckle-change proxy: " + r(opticalCorrelator.speckleChange));
                say("Brightness fluctuation: " + r(opticalCorrelator.brightnessFluctuation));
                say("Edge fluctuation: " + r(opticalCorrelator.edgeFluctuation));
                say("Color shift: " + r(opticalCorrelator.colorShift));
                say("MBER modulation index: " + r(opticalCorrelator.mberModulationIndex));

                updateForgeReady();
            } catch (Exception e) {
                say("CORRELATOR ERROR: " + e.toString());
            } finally {
                runOnUiThread(() -> correlatorButton.setEnabled(true));
            }
        }).start();
    }

    void flashRoot(int color, long ms) {
        runOnUiThread(() -> root.setBackgroundColor(color));
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    void updateForgeReady() {
        runOnUiThread(() -> {
            boolean ready = donorBitmap != null && visualImprint != null;
            forgeButton.setEnabled(ready);
            correlatorButton.setEnabled(ready);
        });
    }

    void startForge() {
        new Thread(() -> {
            try {
                runOnUiThread(() -> {
                    forgeButton.setEnabled(false);
                    playButton.setEnabled(false);
                    shareButton.setEnabled(false);
                });

                if (donorBitmap == null || visualImprint == null) {
                    say("Pick a donor image first.");
                    return;
                }

                if (acousticImprint == null) {
                    say("No mic scan found. Using default acoustic imprint.");
                    acousticImprint = AcousticImprint.defaults();
                }

                String phrase = phraseInput.getText().toString().trim();
                if (phrase.length() == 0) {
                    phrase = "restore coherence copy this pattern stabilize this geometry resonate with this template";
                }

                say("Forging Gariaev-Jiang donor imprint profile...");
                if (opticalCorrelator == null) {
                    say("No correlator scan found. Auto-building optical correlator from donor image.");
                    opticalCorrelator = computeOpticalCorrelator(shrink(donorBitmap), visualImprint);
                }

                Capsule capsule = buildCapsule(donorBitmap, visualImprint, acousticImprint, opticalCorrelator, phrase);

                int totalFrames = calculateTotalFrames(capsule.capsuleBytes.length, capsule.meaningDna);

                say("Estimated seconds: " + r(totalFrames / (double) SAMPLE_RATE));
                say("Streaming WAV directly to Music folder...");

                String base = "DNA_Forge_Max_v11_" + System.currentTimeMillis();

                lastWavUri = saveWavToMusic(
                        base + ".wav",
                        capsule,
                        totalFrames
                );

                lastManifestUri = saveTextToDownloads(
                        base + "_biotron_imprint.json",
                        capsule.manifest
                );

                say("");
                say("DONE.");
                say("");
                say("WAV:");
                say("Music/DNA_FORGE_MAX/" + base + ".wav");
                say("");
                say("Biotron imprint manifest:");
                say("Downloads/DNA_FORGE_MAX/" + base + "_biotron_imprint.json");

                runOnUiThread(() -> {
                    playButton.setEnabled(true);
                    shareButton.setEnabled(true);
                });
            } catch (Exception e) {
                say("FORGE ERROR: " + e.toString());
            } finally {
                runOnUiThread(() -> forgeButton.setEnabled(true));
            }
        }).start();
    }

    void say(String s) {
        runOnUiThread(() -> log.append(s + "\n"));
    }

    void playLast() {
        if (lastWavUri == null) {
            say("No WAV yet.");
            return;
        }

        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastWavUri, "audio/wav");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            say("Could not open audio player. Use Files → Music → DNA_FORGE_MAX.");
        }
    }

    void shareLast() {
        if (lastWavUri == null) {
            say("No WAV yet.");
            return;
        }

        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("audio/wav");
            send.putExtra(Intent.EXTRA_STREAM, lastWavUri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share DNA Forge v11 WAV"));
        } catch (Exception e) {
            say("Could not share WAV.");
        }
    }

    void toggleLightPulse() {
        lightPulseOn = !lightPulseOn;

        if (lightPulseOn) {
            say("Light proxy ON. Visible red / He-Ne proxy only; phone screen is not true infrared.");
            runLightPulse();
        } else {
            root.setBackgroundColor(0xff070714);
            say("Light proxy OFF.");
        }
    }

    void runLightPulse() {
        if (!lightPulseOn) return;

        lightTick++;
        int phase = lightTick % 9;

        if (phase == 2 || phase == 5 || phase == 8) {
            root.setBackgroundColor(0xffff0000);
        } else if (phase % 2 == 0) {
            root.setBackgroundColor(0xff330000);
        } else {
            root.setBackgroundColor(0xff070714);
        }

        handler.postDelayed(this::runLightPulse, 111);
    }

    static class VisualImprint {
        int width;
        int height;
        double brightness;
        double red;
        double green;
        double blue;
        double edgeDensity;
        double symmetry;
        double fractalCompressionProxy;
        long rawCrc;
        long compressedCrc;
        int[] histR;
        int[] histG;
        int[] histB;
    }

    static class AcousticImprint {
        double rms;
        double zeroCrossRate;
        double[] peaks;
        double[] powers;

        static AcousticImprint defaults() {
            AcousticImprint a = new AcousticImprint();
            a.rms = 0.0;
            a.zeroCrossRate = 0.0;
            a.peaks = new double[] { 528, 6328, 6400, 7000, 7.83, 396, 852, 963 };
            a.powers = new double[] { 1, 0.9, 0.8, 0.8, 0.7, 0.6, 0.5, 0.5 };
            return a;
        }
    }


    static class OpticalCorrelator {
        double darkMean;
        double redMean;
        double dimRedMean;
        double schumannPulseMean;
        double pulse369Mean;
        double speckleChange;
        double brightnessFluctuation;
        double edgeFluctuation;
        double colorShift;
        double mberModulationIndex;
        double[] feedbackMatrix;
    }

    static class Capsule {
        byte[] capsuleBytes;
        String manifest;
        String meaningDna;
        VisualImprint visual;
        AcousticImprint acoustic;
        OpticalCorrelator optical;
        double[] phaseMatrix;

        Capsule(byte[] capsuleBytes, String manifest, String meaningDna, VisualImprint visual, AcousticImprint acoustic, OpticalCorrelator optical, double[] phaseMatrix) {
            this.capsuleBytes = capsuleBytes;
            this.manifest = manifest;
            this.meaningDna = meaningDna;
            this.visual = visual;
            this.acoustic = acoustic;
            this.optical = optical;
            this.phaseMatrix = phaseMatrix;
        }
    }

    AcousticImprint recordAcousticImprint() throws Exception {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        if (minBuf < 4096) minBuf = 4096;

        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
        );

        int totalSamples = SAMPLE_RATE * 5;
        short[] data = new short[totalSamples];
        short[] buffer = new short[minBuf];

        recorder.startRecording();

        int offset = 0;
        while (offset < totalSamples) {
            int want = Math.min(buffer.length, totalSamples - offset);
            int n = recorder.read(buffer, 0, want);

            if (n > 0) {
                System.arraycopy(buffer, 0, data, offset, n);
                offset += n;
            }
        }

        recorder.stop();
        recorder.release();

        return analyzeAudio(data, offset);
    }

    static AcousticImprint analyzeAudio(short[] data, int n) {
        AcousticImprint out = new AcousticImprint();

        double sumSq = 0;
        int crossings = 0;

        for (int i = 0; i < n; i++) {
            double x = data[i] / 32768.0;
            sumSq += x * x;

            if (i > 0) {
                if ((data[i - 1] < 0 && data[i] >= 0) || (data[i - 1] >= 0 && data[i] < 0)) {
                    crossings++;
                }
            }
        }

        out.rms = Math.sqrt(sumSq / Math.max(1, n));
        out.zeroCrossRate = crossings / Math.max(1.0, n);

        double[] powers = new double[CANDIDATE_FREQS.length];

        for (int i = 0; i < CANDIDATE_FREQS.length; i++) {
            powers[i] = goertzelPower(data, n, CANDIDATE_FREQS[i], SAMPLE_RATE);
        }

        int peakCount = 8;
        double[] peaks = new double[peakCount];
        double[] peakPowers = new double[peakCount];
        boolean[] used = new boolean[CANDIDATE_FREQS.length];

        for (int k = 0; k < peakCount; k++) {
            int best = 0;
            double bestVal = -1;

            for (int i = 0; i < powers.length; i++) {
                if (!used[i] && powers[i] > bestVal) {
                    best = i;
                    bestVal = powers[i];
                }
            }

            used[best] = true;
            peaks[k] = CANDIDATE_FREQS[best];
            peakPowers[k] = bestVal;
        }

        out.peaks = peaks;
        out.powers = normalizePowers(peakPowers);

        return out;
    }

    static double[] normalizePowers(double[] p) {
        double max = 0;
        for (double v : p) if (v > max) max = v;
        if (max <= 0) max = 1;

        double[] out = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = p[i] / max;
        }

        return out;
    }

    static double goertzelPower(short[] data, int n, double freq, int sr) {
        double sPrev = 0;
        double sPrev2 = 0;
        double normalized = freq / sr;
        double coeff = 2.0 * Math.cos(2.0 * Math.PI * normalized);

        for (int i = 0; i < n; i++) {
            double sample = data[i] / 32768.0;
            double s = sample + coeff * sPrev - sPrev2;
            sPrev2 = sPrev;
            sPrev = s;
        }

        return sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2;
    }

    Capsule buildCapsule(Bitmap original, VisualImprint visual, AcousticImprint acoustic, OpticalCorrelator optical, String phrase) throws Exception {
        Bitmap bmp = shrink(original);
        byte[] rgba = bitmapToRgba(bmp);
        byte[] compressed = deflate(rgba);

        String meaningDna = phraseToDna(phrase);
        double[] phaseMatrix = buildPhaseMatrix(visual, acoustic, optical, phrase);

        String embeddedManifest = buildManifest(visual, acoustic, optical, phaseMatrix, compressed.length, 0, meaningDna, phrase, false);
        byte[] metaBytes = embeddedManifest.getBytes(StandardCharsets.UTF_8);

        CRC32 payloadCrc = new CRC32();
        payloadCrc.update(compressed);

        ByteArrayOutputStream coreOut = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(coreOut);

        dos.writeBytes("DFMAX11");
        dos.writeInt(visual.width);
        dos.writeInt(visual.height);
        dos.writeByte(4);
        dos.writeInt(compressed.length);
        dos.writeInt((int) payloadCrc.getValue());
        dos.writeShort(metaBytes.length);
        dos.write(metaBytes);
        dos.write(compressed);
        dos.flush();

        byte[] corePacket = coreOut.toByteArray();

        CRC32 reverseCrc = new CRC32();
        reverseCrc.update(reverse(corePacket));

        ByteArrayOutputStream capsuleOut = new ByteArrayOutputStream();
        DataOutputStream cds = new DataOutputStream(capsuleOut);

        cds.writeBytes("BIOTRON11");
        cds.writeInt(corePacket.length);
        cds.writeInt((int) payloadCrc.getValue());
        cds.writeInt((int) reverseCrc.getValue());

        int headerChunk = Math.min(128, corePacket.length);

        cds.write(corePacket, 0, headerChunk);
        cds.write(corePacket, 0, headerChunk);
        cds.write(corePacket, 0, headerChunk);
        cds.write(corePacket);
        cds.flush();

        byte[] capsuleBytes = capsuleOut.toByteArray();

        String finalManifest = buildManifest(
                visual,
                acoustic,
                optical,
                phaseMatrix,
                compressed.length,
                capsuleBytes.length,
                meaningDna,
                phrase,
                true
        );

        return new Capsule(capsuleBytes, finalManifest, meaningDna, visual, acoustic, optical, phaseMatrix);
    }


    static OpticalCorrelator computeOpticalCorrelator(Bitmap bmp, VisualImprint v) {
        OpticalCorrelator o = new OpticalCorrelator();

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int count = Math.max(1, w * h);

        double dark = 0;
        double red = 0;
        double dimRed = 0;
        double schumann = 0;
        double pulse369 = 0;
        double speckle = 0;
        double colorShift = 0;
        double edgeBase = 0;
        double edgeRed = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;

                double gray = (r + g + b) / 3.0;

                double darkFrame = gray * 0.035;
                double redFrame = r;
                double dimRedFrame = r * 0.45;

                double schGate = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * ((x + y + 1) / Math.max(1.0, w + h)));
                double schFrame = gray * schGate;

                int pulsePhase = (x + y) % 9;
                double pulseBoost = (pulsePhase == 2 || pulsePhase == 5 || pulsePhase == 8) ? 1.0 : 0.35;
                double pulseFrame = gray * pulseBoost;

                dark += darkFrame;
                red += redFrame;
                dimRed += dimRedFrame;
                schumann += schFrame;
                pulse369 += pulseFrame;

                speckle += Math.abs(redFrame - darkFrame) + Math.abs(schFrame - dimRedFrame) + Math.abs(pulseFrame - schFrame);
                colorShift += (Math.abs(r - g) + Math.abs(r - b)) / 2.0;

                if (x + 1 < w) {
                    int c2 = bmp.getPixel(x + 1, y);
                    int r2 = (c2 >> 16) & 0xff;
                    int g2 = (c2 >> 8) & 0xff;
                    int b2 = c2 & 0xff;
                    double gray2 = (r2 + g2 + b2) / 3.0;
                    edgeBase += Math.abs(gray - gray2);
                    edgeRed += Math.abs(r - r2);
                }
            }
        }

        o.darkMean = dark / (count * 255.0);
        o.redMean = red / (count * 255.0);
        o.dimRedMean = dimRed / (count * 255.0);
        o.schumannPulseMean = schumann / (count * 255.0);
        o.pulse369Mean = pulse369 / (count * 255.0);

        o.speckleChange = clamp(speckle / (count * 255.0 * 3.0));
        o.colorShift = clamp(colorShift / (count * 255.0));
        o.edgeFluctuation = clamp(Math.abs(edgeRed - edgeBase) / Math.max(1.0, count * 255.0));
        o.brightnessFluctuation = clamp(
                standardDeviation(new double[] { o.darkMean, o.redMean, o.dimRedMean, o.schumannPulseMean, o.pulse369Mean })
        );

        o.mberModulationIndex = clamp(
                o.speckleChange * 0.34 +
                o.brightnessFluctuation * 0.22 +
                o.edgeFluctuation * 0.18 +
                o.colorShift * 0.16 +
                v.fractalCompressionProxy * 0.10
        );

        o.feedbackMatrix = new double[16];

        for (int i = 0; i < o.feedbackMatrix.length; i++) {
            double spiral = 0.5 + 0.5 * Math.sin((i + 1) * 1.61803398875);
            double pulse = ((i % 9) == 2 || (i % 9) == 5 || (i % 9) == 8) ? 1.0 : 0.45;

            double value =
                    o.mberModulationIndex * 0.35 +
                    o.speckleChange * 0.22 +
                    o.colorShift * 0.15 +
                    v.symmetry * 0.12 +
                    spiral * 0.10 +
                    pulse * 0.06;

            o.feedbackMatrix[i] = clamp(value) * Math.PI;
        }

        return o;
    }

    static double standardDeviation(double[] x) {
        double mean = 0;
        for (double v : x) mean += v;
        mean /= Math.max(1, x.length);

        double sum = 0;
        for (double v : x) {
            double d = v - mean;
            sum += d * d;
        }

        return Math.sqrt(sum / Math.max(1, x.length));
    }

    static double[] buildPhaseMatrix(VisualImprint v, AcousticImprint a, OpticalCorrelator o, String phrase) {
        double[] m = new double[16];

        CRC32 crc = new CRC32();
        crc.update(phrase.getBytes(StandardCharsets.UTF_8));
        long phraseCrc = crc.getValue();

        for (int i = 0; i < m.length; i++) {
            double audio = a.peaks[i % a.peaks.length] / 20000.0;
            double color = (i % 3 == 0) ? v.red : (i % 3 == 1) ? v.green : v.blue;
            double geom = (v.symmetry + v.edgeDensity + v.brightness) / 3.0;
            double code = ((phraseCrc >> (i % 24)) & 0xff) / 255.0;
            double optical = o.feedbackMatrix[i % o.feedbackMatrix.length] / Math.PI;

            m[i] = (audio * 0.25 + color * 0.18 + geom * 0.18 + code * 0.12 + optical * 0.27) * Math.PI;
        }

        return m;
    }

    Uri saveWavToMusic(String displayName, Capsule capsule, int totalFrames) throws Exception {
        ContentResolver resolver = getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/DNA_FORGE_MAX");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        }

        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create WAV in Music.");

        OutputStream out = resolver.openOutputStream(uri);
        if (out == null) throw new Exception("Could not open WAV output stream.");

        writeBiotronWav(out, capsule, totalFrames);

        out.flush();
        out.close();

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        }

        return uri;
    }

    Uri saveTextToDownloads(String displayName, String text) throws Exception {
        if (Build.VERSION.SDK_INT < 29) return null;

        ContentResolver resolver = getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DNA_FORGE_MAX");
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create manifest.");

        OutputStream out = resolver.openOutputStream(uri);
        if (out == null) throw new Exception("Could not open manifest stream.");

        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();

        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, done, null, null);

        return uri;
    }

    static Bitmap shrink(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();

        double scale = Math.min((double) MAX_DIM / w, (double) MAX_DIM / h);
        if (scale >= 1.0) return src.copy(Bitmap.Config.ARGB_8888, false);

        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    static byte[] bitmapToRgba(Bitmap bmp) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int c = bmp.getPixel(x, y);
                out.write((c >> 16) & 0xff);
                out.write((c >> 8) & 0xff);
                out.write(c & 0xff);
                out.write((c >> 24) & 0xff);
            }
        }

        return out.toByteArray();
    }

    static byte[] deflate(byte[] raw) throws Exception {
        Deflater def = new Deflater(9);
        def.setInput(raw);
        def.finish();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];

        while (!def.finished()) {
            int n = def.deflate(buf);
            out.write(buf, 0, n);
        }

        def.end();
        return out.toByteArray();
    }

    static VisualImprint extractVisualImprint(Bitmap bmp, byte[] raw, byte[] compressed) {
        VisualImprint g = new VisualImprint();
        g.width = bmp.getWidth();
        g.height = bmp.getHeight();

        g.histR = new int[8];
        g.histG = new int[8];
        g.histB = new int[8];

        int w = g.width;
        int h = g.height;
        int count = Math.max(1, w * h);

        double brightness = 0;
        double red = 0;
        double green = 0;
        double blue = 0;
        double edge = 0;
        double mirrorError = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int gg = (c >> 8) & 0xff;
                int b = c & 0xff;

                g.histR[Math.min(7, r / 32)]++;
                g.histG[Math.min(7, gg / 32)]++;
                g.histB[Math.min(7, b / 32)]++;

                double gray = (r + gg + b) / 3.0;

                brightness += gray;
                red += r;
                green += gg;
                blue += b;

                if (x + 1 < w) {
                    int c2 = bmp.getPixel(x + 1, y);
                    int r2 = (c2 >> 16) & 0xff;
                    int g2 = (c2 >> 8) & 0xff;
                    int b2 = c2 & 0xff;
                    edge += Math.abs(gray - ((r2 + g2 + b2) / 3.0));
                }

                if (y + 1 < h) {
                    int c3 = bmp.getPixel(x, y + 1);
                    int r3 = (c3 >> 16) & 0xff;
                    int g3 = (c3 >> 8) & 0xff;
                    int b3 = c3 & 0xff;
                    edge += Math.abs(gray - ((r3 + g3 + b3) / 3.0));
                }

                int cm = bmp.getPixel(w - 1 - x, y);
                int rm = (cm >> 16) & 0xff;
                int gm = (cm >> 8) & 0xff;
                int bm = cm & 0xff;
                double grayMirror = (rm + gm + bm) / 3.0;

                mirrorError += Math.abs(gray - grayMirror);
            }
        }

        g.brightness = brightness / (count * 255.0);
        g.red = red / (count * 255.0);
        g.green = green / (count * 255.0);
        g.blue = blue / (count * 255.0);
        g.edgeDensity = edge / Math.max(1.0, count * 255.0 * 2.0);
        g.symmetry = 1.0 - (mirrorError / Math.max(1.0, count * 255.0));
        g.fractalCompressionProxy = compressed.length / Math.max(1.0, raw.length);

        CRC32 rawCrc = new CRC32();
        rawCrc.update(raw);
        g.rawCrc = rawCrc.getValue();

        CRC32 compCrc = new CRC32();
        compCrc.update(compressed);
        g.compressedCrc = compCrc.getValue();

        return g;
    }

    static String phraseToDna(String phrase) {
        byte[] bytes = phrase.getBytes(StandardCharsets.UTF_8);
        StringBuilder dna = new StringBuilder();

        for (byte b : bytes) {
            for (int i = 6; i >= 0; i -= 2) {
                int pair = (b >> i) & 0x03;
                if (pair == 0) dna.append('A');
                else if (pair == 1) dna.append('C');
                else if (pair == 2) dna.append('G');
                else dna.append('T');
            }
        }

        return dna.toString();
    }

    static int calculateTotalFrames(int capsuleBytes, String dna) {
        int total = 0;

        total += frames(250);
        total += frames(500);
        total += frames(HE_NE_MS);
        total += frames(300);
        total += frames(300);
        total += opticalCorrelatorFrames();
        total += visualImprintFrames();
        total += acousticImprintFrames();
        total += hermesFrames();
        total += meaningFrames(dna);
        total += capsuleBytes * 8 * bitFrames();
        total += frames(500);
        total += frames(250);

        return total;
    }

    static int frames(double ms) {
        return (int) (SAMPLE_RATE * ms / 1000.0);
    }

    static int bitFrames() {
        return Math.max(60, frames(BIT_MS));
    }

    static int opticalCorrelatorFrames() {
        return 16 * (frames(24) + frames(3));
    }

    static int visualImprintFrames() {
        return 24 * (frames(12) + frames(2));
    }

    static int acousticImprintFrames() {
        return 8 * (frames(80) + frames(10));
    }

    static int hermesFrames() {
        return frames(888);
    }

    static int meaningFrames(String dna) {
        int max = Math.min(dna.length(), 240);
        int total = 0;

        for (int i = 0; i < max; i++) {
            char c = dna.charAt(i);
            double ms = (c == 'C' || c == 'T') ? 4.854 : 3.0;
            total += frames(ms);
            total += frames(2.0);
        }

        total += frames(60);
        return total;
    }

    void writeBiotronWav(OutputStream out, Capsule capsule, int totalFrames) throws Exception {
        writeStereoWavHeader(out, totalFrames);

        SampleWriter sw = new SampleWriter(out, capsule.phaseMatrix);

        say("Writing MBER/Rife open sweep...");
        streamStereoSilence(sw, 250);
        streamStereoSweep(sw, 20, 20000, 500, 0.18, false);

        say("Writing Gariaev optical-correlator sync...");
        streamStereoTone(sw, HE_NE_HZ, HE_NE_MS, 0.38, 0.38, 0, Math.PI / 2.0);
        streamStereoSweep(sw, F0, F1, 300, 0.30, true);
        streamSchumannPulseWindow(sw, 300);

        say("Writing v12 optical feedback matrix layer...");
        streamOpticalFeedbackLayer(sw, capsule.optical);

        say("Writing visual donor imprint layer...");
        streamVisualImprint(sw, capsule.visual);

        say("Writing acoustic donor imprint layer...");
        streamAcousticImprint(sw, capsule.acoustic);

        say("Writing Hermes ancient-code grammar layer...");
        streamHermesAncientCodeLayer(sw, capsule.visual);

        say("Writing symbolic DNA phrase layer...");
        streamMeaningDnaRhythm(sw, capsule.meaningDna);

        say("Writing donor capsule object wave + phase-shift matrix...");
        streamPacketBits(sw, capsule.capsuleBytes);

        say("Writing closing sweep...");
        streamStereoSweep(sw, 20000, 20, 500, 0.18, false);
        streamStereoSilence(sw, 250);

        if (sw.framesWritten != totalFrames) {
            say("Frame note: expected " + totalFrames + " wrote " + sw.framesWritten);
        }
    }

    static class SampleWriter {
        OutputStream out;
        int framesWritten = 0;
        double[] phaseMatrix;

        SampleWriter(OutputStream out, double[] phaseMatrix) {
            this.out = out;
            this.phaseMatrix = phaseMatrix;
        }
    }

    static void writeReferenceLockedFrame(SampleWriter sw, double objectLeft, double objectRight) throws Exception {
        double t = sw.framesWritten / (double) SAMPLE_RATE;

        double reference =
                Math.sin(2.0 * Math.PI * AURA_HZ * t) * REFERENCE_528_AMP +
                Math.sin(2.0 * Math.PI * HE_NE_HZ * t) * REFERENCE_HE_NE_AMP +
                Math.sin(2.0 * Math.PI * 40.0 * t) * REFERENCE_40_AMP;

        double gateWave = (1.0 + Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t)) / 2.0;
        double gateEnv = 1.0 - GATE_DEPTH + GATE_DEPTH * gateWave;

        double pulse = 1.0;
        int beat = ((int) (t * 9.0)) % 9;
        if (beat == 2 || beat == 5 || beat == 8) pulse = 1.08;

        double left = clamp(reference + objectLeft * gateEnv * pulse);
        double right = clamp(reference + objectRight * gateEnv * pulse);

        writeLE16(sw.out, (int) (left * 32767.0));
        writeLE16(sw.out, (int) (right * 32767.0));

        sw.framesWritten++;
    }

    static void streamStereoSilence(SampleWriter sw, double ms) throws Exception {
        int n = frames(ms);
        for (int i = 0; i < n; i++) writeReferenceLockedFrame(sw, 0, 0);
    }

    static void streamStereoTone(
            SampleWriter sw,
            double freq,
            double ms,
            double ampL,
            double ampR,
            double phaseL,
            double phaseR
    ) throws Exception {
        int n = frames(ms);
        int fade = Math.max(1, frames(5));

        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < fade) env = i / (double) fade;
            else if (i > n - fade) env = Math.max(0.0, (n - i) / (double) fade);

            double t = i / (double) SAMPLE_RATE;
            double l = Math.sin(2.0 * Math.PI * freq * t + phaseL) * ampL * env;
            double r = Math.sin(2.0 * Math.PI * freq * t + phaseR) * ampR * env;

            writeReferenceLockedFrame(sw, l, r);
        }
    }

    static void streamStereoSweep(SampleWriter sw, double startHz, double endHz, double ms, double amp, boolean oppositePhase) throws Exception {
        int n = frames(ms);
        double phaseL = 0.0;
        double phaseR = oppositePhase ? Math.PI : Math.PI / 2.0;

        for (int i = 0; i < n; i++) {
            double frac = i / Math.max(1.0, n - 1.0);
            double freq = startHz + (endHz - startHz) * frac;
            double step = 2.0 * Math.PI * freq / SAMPLE_RATE;

            writeReferenceLockedFrame(sw, Math.sin(phaseL) * amp, Math.sin(phaseR) * amp);

            phaseL = (phaseL + step) % (2.0 * Math.PI);
            phaseR = (phaseR + step) % (2.0 * Math.PI);
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


    static void streamOpticalFeedbackLayer(SampleWriter sw, OpticalCorrelator o) throws Exception {
        double[] carriers = new double[] {
                6328.0, 6400.0, 7000.0, 528.0,
                396.0, 417.0, 432.0, 741.0,
                852.0, 963.0, 1111.0, 1361.0,
                1744.0, 3200.0, 4096.0, 5280.0
        };

        for (int i = 0; i < o.feedbackMatrix.length; i++) {
            double matrix = o.feedbackMatrix[i] / Math.PI;
            double freq = carriers[i % carriers.length] + (i * 7.83);
            double amp = 0.06 + 0.22 * clamp(matrix);
            double rightPhase = o.feedbackMatrix[i];

            streamStereoTone(sw, freq, 24.0, amp, amp * 0.78, 0.0, rightPhase);
            streamStereoSilence(sw, 3.0);
        }
    }

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

        double elementHz;
        if (g.red >= g.green && g.red >= g.blue) {
            elementHz = 852.0;
        } else if (g.blue >= g.red && g.blue >= g.green) {
            elementHz = 417.0;
        } else {
            elementHz = 528.0;
        }

        double[] sevenGates = new double[] {396.0, 417.0, 528.0, 639.0, 741.0, 852.0, 963.0};

        double symmetryAmp = 0.08 + 0.16 * clamp(g.symmetry);
        double edgeAmp = 0.05 + 0.22 * clamp(g.edgeDensity * 4.0);
        double brightnessAmp = 0.05 + 0.12 * clamp(g.brightness);

        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double frac = i / Math.max(1.0, n - 1.0);

            int gateIndex = (int) Math.floor(frac * 7.0);
            if (gateIndex < 0) gateIndex = 0;
            if (gateIndex > 6) gateIndex = 6;

            int mirrorGate = 6 - gateIndex;

            double gateToneL = Math.sin(2.0 * Math.PI * sevenGates[gateIndex] * t) * symmetryAmp;
            double gateToneR = Math.sin(2.0 * Math.PI * sevenGates[mirrorGate] * t + Math.PI / 2.0) * symmetryAmp;

            double compass = Math.sin(2.0 * Math.PI * elementHz * t) * brightnessAmp;
            double ring12 = Math.sin(2.0 * Math.PI * 12.0 * t);
            double ringEnv = 0.5 + 0.5 * ring12;
            double wedgePulse = Math.sin(2.0 * Math.PI * (9.0 + 18.0 * g.edgeDensity) * t);
            double wedge = wedgePulse * edgeAmp * ringEnv;
            double phi = 1.61803398875;
            double golden = Math.sin(2.0 * Math.PI * (432.0 * phi) * t) * 0.025;
            double spiralPhase = 2.0 * Math.PI * (3.0 + 6.0 * frac) * t;
            double spiral = Math.sin(spiralPhase) * 0.035;

            double left = gateToneL + compass + wedge + golden + spiral;
            double right = gateToneR - compass + wedge * 0.5 + golden - spiral;

            writeReferenceLockedFrame(sw, left, right);
        }
    }

    static void streamMeaningDnaRhythm(SampleWriter sw, String dna) throws Exception {
        double shortMs = 3.0;
        double longMs = 4.854;

        int max = Math.min(dna.length(), 240);

        for (int i = 0; i < max; i++) {
            char c = dna.charAt(i);

            double hz;
            double ms;

            if (c == 'A') {
                hz = 396.0;
                ms = shortMs;
            } else if (c == 'C') {
                hz = 528.0;
                ms = longMs;
            } else if (c == 'G') {
                hz = 639.0;
                ms = shortMs;
            } else {
                hz = 741.0;
                ms = longMs;
            }

            streamStereoTone(sw, hz, ms, 0.24, 0.18, 0, Math.PI / 2);
            streamStereoSilence(sw, 2);
        }

        streamStereoSilence(sw, 60);
    }

    static void streamPacketBits(SampleWriter sw, byte[] data) throws Exception {
        byte[] reversed = reverse(data);

        int bitSamples = bitFrames();

        double phaseL = 0.0;
        double phaseR = Math.PI / 2.0;

        for (int idx = 0; idx < data.length; idx++) {
            int bL = data[idx] & 0xff;
            int bR = reversed[idx] & 0xff;

            for (int bitPos = 7; bitPos >= 0; bitPos--) {
                int bitL = (bL >> bitPos) & 1;
                int bitR = (bR >> bitPos) & 1;

                double freqL = bitL == 1 ? F1 : F0;
                double freqR = bitR == 1 ? F1 : F0;

                double matrixPhase = sw.phaseMatrix[(idx + bitPos) % sw.phaseMatrix.length];

                double stepL = 2.0 * Math.PI * freqL / SAMPLE_RATE;
                double stepR = 2.0 * Math.PI * freqR / SAMPLE_RATE;

                for (int s = 0; s < bitSamples; s++) {
                    double l = Math.sin(phaseL) * 0.58;
                    double r = Math.sin(phaseR + matrixPhase) * 0.58;

                    writeReferenceLockedFrame(sw, l, r);

                    phaseL = (phaseL + stepL) % (2.0 * Math.PI);
                    phaseR = (phaseR + stepR) % (2.0 * Math.PI);
                }
            }
        }
    }

    static void writeStereoWavHeader(OutputStream out, int totalFrames) throws Exception {
        int channels = 2;
        int bytesPerSample = 2;
        int dataSize = totalFrames * channels * bytesPerSample;
        int totalSize = 36 + dataSize;

        writeAscii(out, "RIFF");
        writeLE32(out, totalSize);
        writeAscii(out, "WAVE");

        writeAscii(out, "fmt ");
        writeLE32(out, 16);
        writeLE16(out, 1);
        writeLE16(out, channels);
        writeLE32(out, SAMPLE_RATE);
        writeLE32(out, SAMPLE_RATE * channels * bytesPerSample);
        writeLE16(out, channels * bytesPerSample);
        writeLE16(out, 16);

        writeAscii(out, "data");
        writeLE32(out, dataSize);
    }

    static String buildManifest(
            VisualImprint v,
            AcousticImprint a,
            OpticalCorrelator o,
            double[] phaseMatrix,
            int compressedBytes,
            int capsuleBytes,
            String meaningDna,
            String phrase,
            boolean finalManifest
    ) {
        return "{\n" +
                "  \"name\":\"DNA Forge Max v12 - Gariaev Optical Correlator Emulator\",\n" +
                "  \"final_manifest\":" + finalManifest + ",\n" +
                "  \"safety\":\"Software simulator/controller. No medical claims. Consent-based, non-invasive, low-power only. Phone screen is not true infrared and phone is not a physical He-Ne/MBER lab apparatus.\",\n" +
                "  \"phone_mapping\":{\"camera\":\"visual donor scanner\",\"microphone\":\"acoustic resonance scanner\",\"speaker\":\"stereo wavecode carrier\",\"screen\":\"red/He-Ne proxy\",\"manifest\":\"MBER/Biotron hardware export memory\"},\n" +
                "  \"session_profile\":{\"visual_donor_pattern\":true,\"acoustic_donor_pattern\":true,\"symbolic_meaning_phrase\":true,\"dynamic_phase_shift_matrix\":true,\"optical_correlator_emulator\":true},\n" +
                "  \"gariaev_optical_correlator_emulator\":{\"passes\":[\"dark-frame control\",\"red reference illumination\",\"dim-red reference illumination\",\"7.83 Hz pulse proxy\",\"3/6/9 pulse proxy\"],\"dark_mean\":" + r(o.darkMean) + ",\"red_mean\":" + r(o.redMean) + ",\"dim_red_mean\":" + r(o.dimRedMean) + ",\"schumann_pulse_mean\":" + r(o.schumannPulseMean) + ",\"pulse369_mean\":" + r(o.pulse369Mean) + ",\"speckle_change_proxy\":" + r(o.speckleChange) + ",\"brightness_fluctuation\":" + r(o.brightnessFluctuation) + ",\"edge_fluctuation\":" + r(o.edgeFluctuation) + ",\"color_shift\":" + r(o.colorShift) + ",\"mber_modulation_index\":" + r(o.mberModulationIndex) + ",\"optical_feedback_matrix\":" + doubleArray(o.feedbackMatrix) + "},\n" +
                "  \"visual_donor_pattern\":{\"width\":" + v.width + ",\"height\":" + v.height + ",\"brightness\":" + r(v.brightness) + ",\"red\":" + r(v.red) + ",\"green\":" + r(v.green) + ",\"blue\":" + r(v.blue) + ",\"edge_density\":" + r(v.edgeDensity) + ",\"symmetry\":" + r(v.symmetry) + ",\"fractal_compression_proxy\":" + r(v.fractalCompressionProxy) + ",\"rgb_histogram_bins\":{\"r\":" + intArray(v.histR) + ",\"g\":" + intArray(v.histG) + ",\"b\":" + intArray(v.histB) + "}},\n" +
                "  \"acoustic_donor_pattern\":{\"rms\":" + r(a.rms) + ",\"zero_cross_rate\":" + r(a.zeroCrossRate) + ",\"peak_frequencies_hz\":" + doubleArray(a.peaks) + ",\"relative_powers\":" + doubleArray(a.powers) + "},\n" +
                "  \"symbolic_meaning_phrase\":{\"phrase\":\"" + escape(phrase) + "\",\"symbolic_dna\":\"" + meaningDna + "\"},\n" +
                "  \"dynamic_phase_shift_matrix\":{\"description\":\"right-channel optical-feedback surrogate controlled by visual imprint, acoustic peaks, and phrase hash\",\"radians\":" + doubleArray(phaseMatrix) + "},\n" +
                "  \"gariaev_optical_correlator_profile\":{\"he_ne_nm\":632.8,\"he_ne_power_mw\":2,\"audio_signature_hz\":6328,\"orthogonal_polarization_modes\":true,\"glass_slide_sample_chamber\":\"hardware profile\",\"partial_resonator_feedback\":\"simulated by right-channel phase matrix\",\"mber\":\"modulated broadband electromagnetic radiation metadata layer\"},\n" +
                "  \"jiang_biotron_profile\":{\"researcher\":\"Jiang Kanzhen\",\"themes\":[\"biological information transmission metadata\",\"bio-ultra-high-frequency profile\",\"Biotron Tszyan-2 style donor-to-recipient information profile\"],\"uhf_rf_note\":\"phone cannot generate UHF/RF directly; export profile is for external hardware\"},\n" +
                "  \"reference_wave_lock\":{\"active\":true,\"continuous_reference_lattice\":[528,6328,40,7.83],\"mid_side_holography\":true},\n" +
                "  \"audio_layers\":{\"format\":\"stereo 16-bit PCM WAV\",\"zero_hz\":6400,\"one_hz\":7000,\"he_ne_preamble_hz\":6328,\"rife_open_sweep_hz\":[20,20000],\"rife_close_sweep_hz\":[20000,20],\"schumann_gate_hz\":7.83,\"bit_ms\":1.5},\n" +
                "  \"hermes_layer\":{\"active\":true,\"rule\":\"not random; selected by image color, symmetry, edge density, and meaning\"},\n" +
                "  \"hardware_export_profile\":{\"he_ne_laser_driver\":\"632.8 nm timing/sync metadata\",\"coil_pad\":\"384 kHz marker metadata plus audio-derived pulse profile\",\"sdr_transmitter\":\"MBER/RF export metadata only\",\"photodiode\":\"feedback sensor profile\",\"sample_chamber\":\"glass-slide donor chamber profile\"},\n" +
                "  \"payload\":{\"compressed_bytes\":" + compressedBytes + ",\"capsule_bytes\":" + capsuleBytes + ",\"stream_to_file\":true,\"triple_header\":true,\"crc32\":true,\"phase_conjugate_mirror\":true}\n" +
                "}\n";
    }

    static String intArray(int[] a) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    static String doubleArray(double[] a) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(r(a[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    static byte[] reverse(byte[] input) {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) out[i] = input[input.length - 1 - i];
        return out;
    }

    static double clamp(double x) {
        if (x > 1.0) return 1.0;
        if (x < -1.0) return -1.0;
        return x;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String r(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    static void writeAscii(OutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    static void writeLE16(OutputStream out, int v) throws Exception {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    static void writeLE32(OutputStream out, int v) throws Exception {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }
}
