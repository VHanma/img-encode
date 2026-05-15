package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Intent;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;
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

    LinearLayout root;
    TextView log;
    Button pickButton;
    Button playButton;
    Button shareButton;
    Button lightButton;

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
    static final double HE_NE_MS = 750.0;

    static final double AURA_HZ = 528.0;
    static final double AURA_AMP = 0.055;

    static final double SCHUMANN_HZ = 7.83;
    static final double GATE_DEPTH = 0.35;

    static final int MAX_DIM = 16;

    static final String MEANING_PHRASE =
            "restore coherence copy this pattern stabilize this geometry resonate with this template";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xff070714);

        TextView title = new TextView(this);
        title.setText("DNA Forge Max v8 Safe\nLiving Wavecode Capsule");
        title.setTextColor(0xff00e5ff);
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        pickButton = new Button(this);
        pickButton.setText("PICK IMAGE → MAKE LIVING WAVECODE");
        root.addView(pickButton);

        playButton = new Button(this);
        playButton.setText("PLAY LAST WAV");
        playButton.setEnabled(false);
        root.addView(playButton);

        shareButton = new Button(this);
        shareButton.setText("SHARE LAST WAV");
        shareButton.setEnabled(false);
        root.addView(shareButton);

        lightButton = new Button(this);
        lightButton.setText("RED / HE-NE LIGHT PULSE PREVIEW");
        root.addView(lightButton);

        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(0xffb8ffb8);
        log.setTextSize(13);
        log.setText(
                "Ready.\n" +
                "v8 SAFE MODE: all layers kept, but audio is shorter so Android will not kill the app.\n\n"
        );
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        pickButton.setOnClickListener(v -> openPicker());
        playButton.setOnClickListener(v -> playLast());
        shareButton.setOnClickListener(v -> shareLast());
        lightButton.setOnClickListener(v -> toggleLightPulse());
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
                    runOnUiThread(() -> {
                        pickButton.setEnabled(false);
                        playButton.setEnabled(false);
                        shareButton.setEnabled(false);
                    });

                    say("Reading image...");

                    InputStream in = getContentResolver().openInputStream(uri);
                    Bitmap bmp = BitmapFactory.decodeStream(in);

                    if (bmp == null) {
                        say("Could not decode image.");
                        return;
                    }

                    String base = "DNA_Forge_Max_v8_" + System.currentTimeMillis();

                    EncodeResult resultObj = encodeBitmap(bmp);

                    say("Saving stereo WAV to public Music folder...");
                    lastWavUri = saveWavToMusic(base + ".wav", resultObj.left, resultObj.right);

                    say("Saving capsule manifest to public Downloads folder...");
                    lastManifestUri = saveTextToDownloads(base + "_capsule_manifest.json", resultObj.manifest);

                    say("");
                    say("DONE.");
                    say("");
                    say("WAV:");
                    say("Music/DNA_FORGE_MAX/" + base + ".wav");
                    say("");
                    say("Manifest:");
                    say("Downloads/DNA_FORGE_MAX/" + base + "_capsule_manifest.json");
                    say("");
                    say("Tap PLAY LAST WAV to hear it.");
                    say("Tap SHARE LAST WAV to export it.");

                    runOnUiThread(() -> {
                        playButton.setEnabled(true);
                        shareButton.setEnabled(true);
                    });
                } catch (Exception e) {
                    say("ERROR: " + e.toString());
                } finally {
                    runOnUiThread(() -> pickButton.setEnabled(true));
                }
            }).start();
        }
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
            say("Could not open audio player.");
            say("Use Files → Music → DNA_FORGE_MAX.");
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
            startActivity(Intent.createChooser(send, "Share DNA Forge v8 WAV"));
        } catch (Exception e) {
            say("Could not share WAV.");
        }
    }

    void toggleLightPulse() {
        lightPulseOn = !lightPulseOn;

        if (lightPulseOn) {
            say("Light pulse preview ON.");
            say("This is visible red / He-Ne proxy only. Phone screen is not true infrared.");
            runLightPulse();
        } else {
            root.setBackgroundColor(0xff070714);
            say("Light pulse preview OFF.");
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

    static class EncodeResult {
        double[] left;
        double[] right;
        String manifest;

        EncodeResult(double[] left, double[] right, String manifest) {
            this.left = left;
            this.right = right;
            this.manifest = manifest;
        }
    }

    static class Geometry {
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
    }

    EncodeResult encodeBitmap(Bitmap original) throws Exception {
        say("Shrinking image...");
        Bitmap bmp = shrink(original);

        say("Extracting geometry soul...");
        byte[] rgba = bitmapToRgba(bmp);
        byte[] compressed = deflate(rgba);

        Geometry geo = extractGeometry(bmp, rgba, compressed);

        say("Converting meaning phrase to symbolic DNA...");
        String meaningDna = phraseToDna(MEANING_PHRASE);

        String preManifest = buildManifest(geo, compressed.length, meaningDna, false);

        byte[] metaBytes = preManifest.getBytes(StandardCharsets.UTF_8);

        CRC32 crc = new CRC32();
        crc.update(compressed);
        long payloadCrc = crc.getValue();

        say("Building living capsule packet...");

        ByteArrayOutputStream packetOut = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(packetOut);

        // Main capsule header.
        dos.writeBytes("DFMAXV8");
        dos.writeInt(geo.width);
        dos.writeInt(geo.height);
        dos.writeByte(4);
        dos.writeInt(compressed.length);
        dos.writeInt((int) payloadCrc);
        dos.writeShort(metaBytes.length);
        dos.write(metaBytes);
        dos.write(compressed);

        byte[] corePacket = packetOut.toByteArray();

        // Redundancy / error survival:
        // Repeat critical header and add reverse mirror hash.
        CRC32 reverseCrc = new CRC32();
        byte[] reversedCore = reverse(corePacket);
        reverseCrc.update(reversedCore);

        ByteArrayOutputStream capsuleOut = new ByteArrayOutputStream();
        DataOutputStream cds = new DataOutputStream(capsuleOut);

        cds.writeBytes("CAPSULE8");
        cds.writeInt(corePacket.length);
        cds.writeInt((int) payloadCrc);
        cds.writeInt((int) reverseCrc.getValue());

        // Header triple.
        cds.write(corePacket, 0, Math.min(128, corePacket.length));
        cds.write(corePacket, 0, Math.min(128, corePacket.length));
        cds.write(corePacket, 0, Math.min(128, corePacket.length));

        // Main payload.
        cds.write(corePacket);
        cds.flush();

        byte[] capsule = capsuleOut.toByteArray();

        String manifest = buildManifest(geo, capsule.length, meaningDna, true);

        say("Capsule bytes: " + capsule.length);
        say("Generating stereo living wavecode...");
        say("Left = forward packet.");
        say("Right = reverse / phase-conjugate mirror packet.");

        StereoWave stereo = makeLivingWavecode(capsule, meaningDna);

        say("Seconds: " + Math.round(stereo.left.length * 10.0 / SAMPLE_RATE) / 10.0);

        return new EncodeResult(stereo.left, stereo.right, manifest);
    }

    static class StereoWave {
        double[] left;
        double[] right;

        StereoWave(double[] left, double[] right) {
            this.left = left;
            this.right = right;
        }
    }

    Uri saveWavToMusic(String displayName, double[] left, double[] right) throws Exception {
        byte[] wavBytes = buildStereoWavBytes(left, right);

        ContentResolver resolver = getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");

        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/DNA_FORGE_MAX");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        }

        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

        if (uri == null) {
            throw new Exception("Could not create WAV in Music.");
        }

        OutputStream out = resolver.openOutputStream(uri);
        out.write(wavBytes);
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
        ContentResolver resolver = getContentResolver();

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DNA_FORGE_MAX");
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

            if (uri == null) {
                throw new Exception("Could not create manifest in Downloads.");
            }

            OutputStream out = resolver.openOutputStream(uri);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);

            return uri;
        }

        return null;
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

    static Geometry extractGeometry(Bitmap bmp, byte[] raw, byte[] compressed) {
        Geometry g = new Geometry();
        g.width = bmp.getWidth();
        g.height = bmp.getHeight();

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

    static String buildManifest(Geometry g, int packetBytes, String meaningDna, boolean finalManifest) {
        return "{\n" +
                "  \"name\":\"DNA Forge Max v8 - Living Wavecode Capsule\",\n" +
                "  \"final_manifest\":" + finalManifest + ",\n" +
                "  \"safety\":\"No medical claims. Consent-based, non-invasive, low-power only. Phone screen is not true infrared.\",\n" +
                "  \"image\":{\"width\":" + g.width + ",\"height\":" + g.height + "},\n" +
                "  \"packet_bytes\":" + packetBytes + ",\n" +
                "  \"sync_key\":{\"he_ne_hz\":6328,\"chirp_hz\":[6400,7000],\"gate_hz\":7.83},\n" +
                "  \"audio_format\":{\"wav\":\"stereo 16-bit PCM\",\"left\":\"forward packet\",\"right\":\"reverse phase-conjugate mirror packet\"},\n" +
                "  \"error_survival\":{\"crc32\":true,\"header_repetition\":3,\"reverse_packet_crc\":true,\"packet_redundancy\":\"basic\"},\n" +
                "  \"meaning_layer\":{\"phrase\":\"" + escape(MEANING_PHRASE) + "\",\"symbolic_dna\":\"" + meaningDna + "\"},\n" +
                "  \"geometry_soul\":{\"brightness\":" + r(g.brightness) + ",\"red\":" + r(g.red) + ",\"green\":" + r(g.green) + ",\"blue\":" + r(g.blue) + ",\"edge_density\":" + r(g.edgeDensity) + ",\"symmetry\":" + r(g.symmetry) + ",\"fractal_compression_proxy\":" + r(g.fractalCompressionProxy) + "},\n" +
                "  \"gariaev_layer\":{\"zero_hz\":6400,\"one_hz\":7000,\"he_ne_preamble_hz\":6328,\"original_carrier_hz\":[640000,700000]},\n" +
                "  \"levin_layer\":\"bioelectric morphogenesis map metadata from brightness, edges, symmetry, and color balance\",\n" +
                "  \"rife_layer\":{\"open_sweep_hz\":[20,20000],\"close_sweep_hz\":[20000,20]},\n" +
                "  \"tesla_layer\":{\"schumann_gate_hz\":7.83,\"pulse_rhythm\":\"3-6-9\"},\n" +
                "  \"bearden_scalar_layer\":{\"infolded_potential_metadata\":true,\"phase_conjugate_mirror_channel\":true,\"coil_marker_hz\":384000},\n" +
                "  \"infrared_layer\":{\"wavelengths_nm\":[632.8,660,850,940],\"mode\":\"metadata plus red-screen He-Ne proxy\"},\n" +
                "  \"expanded_modules\":[\"donor-template chamber\",\"polarized He-Ne optical correlator\",\"MBER carrier\",\"scalar plasma longitudinal stage\",\"sound-language encoder\",\"water liquid-crystal memory\",\"biofield detector\",\"feedback loop\",\"target resonance lock\",\"control placebo channel\"],\n" +
                "  \"capsule\":\"image plus holographic geometry plus meaning plus binary plus phase mirror plus Rife sweep plus Tesla gate plus IR profile\"\n" +
                "}\n";
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String r(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    static StereoWave makeLivingWavecode(byte[] capsule, String meaningDna) {
        java.util.ArrayList<Double> left = new java.util.ArrayList<>();
        java.util.ArrayList<Double> right = new java.util.ArrayList<>();

        // Opening silence.
        addStereoSilence(left, right, 250);

        // Rife open sweep 20 Hz → 20 kHz.
        addStereoSweep(left, right, 20.0, 20000.0, 500.0, 0.18, false);

        // Triple sync key:
        // 6328 Hz He-Ne gate.
        addStereoTone(left, right, HE_NE_HZ, HE_NE_MS, 0.42, 0.42, 0.0, Math.PI / 2.0);

        // 6400→7000 Hz carrier lock chirp.
        addStereoSweep(left, right, F0, F1, 350.0, 0.30, true);

        // 7.83 Hz pulse window.
        addSchumannPulseWindow(left, right, 350.0);

        // Meaning phrase to DNA rhythm.
        addMeaningDnaRhythm(left, right, meaningDna);

        // Main stereo packet:
        // left = forward capsule.
        // right = reversed capsule.
        byte[] reversed = reverse(capsule);
        addPacketBits(left, capsule, false);
        addPacketBits(right, reversed, true);

        // Close field with reverse Rife sweep.
        addStereoSweep(left, right, 20000.0, 20.0, 500.0, 0.18, false);

        addStereoSilence(left, right, 250);

        double[] l = toArray(left);
        double[] r = toArray(right);

        // Apply global 528 bed, 7.83 gate, 3/6/9 pulse.
        applyGlobalLayers(l, r);

        return new StereoWave(l, r);
    }

    static void addMeaningDnaRhythm(java.util.ArrayList<Double> left, java.util.ArrayList<Double> right, String dna) {
        // Golden-ish living timing for the meaning layer.
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

            addStereoTone(left, right, hz, ms, 0.30, 0.22, 0.0, Math.PI / 2.0);
            addStereoSilence(left, right, 2.0);
        }

        addStereoSilence(left, right, 60.0);
    }

    static void addPacketBits(java.util.ArrayList<Double> target, byte[] data, boolean phaseMirror) {
        int bitSamples = Math.max(80, (int) (SAMPLE_RATE * BIT_MS / 1000.0));
        double phase = phaseMirror ? Math.PI / 2.0 : 0.0;

        for (byte b : data) {
            for (int i = 7; i >= 0; i--) {
                int bit = (b >> i) & 1;
                double freq = bit == 1 ? F1 : F0;
                double step = 2.0 * Math.PI * freq / SAMPLE_RATE;

                for (int s = 0; s < bitSamples; s++) {
                    target.add(Math.sin(phase) * 0.64);
                    phase = (phase + step) % (2.0 * Math.PI);
                }
            }
        }
    }

    static void addStereoSilence(java.util.ArrayList<Double> l, java.util.ArrayList<Double> r, double ms) {
        int n = (int) (SAMPLE_RATE * ms / 1000.0);
        for (int i = 0; i < n; i++) {
            l.add(0.0);
            r.add(0.0);
        }
    }

    static void addStereoTone(
            java.util.ArrayList<Double> l,
            java.util.ArrayList<Double> r,
            double freq,
            double ms,
            double ampL,
            double ampR,
            double phaseL,
            double phaseR
    ) {
        int n = (int) (SAMPLE_RATE * ms / 1000.0);
        int fade = Math.max(1, (int) (SAMPLE_RATE * 0.005));

        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < fade) env = i / (double) fade;
            else if (i > n - fade) env = Math.max(0.0, (n - i) / (double) fade);

            double t = i / (double) SAMPLE_RATE;
            l.add(Math.sin(2.0 * Math.PI * freq * t + phaseL) * ampL * env);
            r.add(Math.sin(2.0 * Math.PI * freq * t + phaseR) * ampR * env);
        }
    }

    static void addStereoSweep(
            java.util.ArrayList<Double> l,
            java.util.ArrayList<Double> r,
            double startHz,
            double endHz,
            double ms,
            double amp,
            boolean oppositePhase
    ) {
        int n = (int) (SAMPLE_RATE * ms / 1000.0);
        double phaseL = 0.0;
        double phaseR = oppositePhase ? Math.PI : Math.PI / 2.0;

        for (int i = 0; i < n; i++) {
            double frac = i / Math.max(1.0, n - 1.0);
            double freq = startHz + (endHz - startHz) * frac;
            double step = 2.0 * Math.PI * freq / SAMPLE_RATE;

            l.add(Math.sin(phaseL) * amp);
            r.add(Math.sin(phaseR) * amp);

            phaseL = (phaseL + step) % (2.0 * Math.PI);
            phaseR = (phaseR + step) % (2.0 * Math.PI);
        }
    }

    static void addSchumannPulseWindow(java.util.ArrayList<Double> l, java.util.ArrayList<Double> r, double ms) {
        int n = (int) (SAMPLE_RATE * ms / 1000.0);

        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double gate = (1.0 + Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t)) / 2.0;
            double carrier = Math.sin(2.0 * Math.PI * 528.0 * t) * 0.18 * gate;
            l.add(carrier);
            r.add(-carrier);
        }
    }

    static void applyGlobalLayers(double[] l, double[] r) {
        int n = Math.min(l.length, r.length);

        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;

            double aura = Math.sin(2.0 * Math.PI * AURA_HZ * t) * AURA_AMP;

            double gateWave = (1.0 + Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t)) / 2.0;
            double gateEnv = 1.0 - GATE_DEPTH + GATE_DEPTH * gateWave;

            double pulse = 1.0;
            int beat = ((int) (t * 9.0)) % 9;
            if (beat == 2 || beat == 5 || beat == 8) pulse = 1.08;

            l[i] = clamp((l[i] * gateEnv * pulse) + aura);
            r[i] = clamp((r[i] * gateEnv * pulse) - aura);
        }
    }

    static double[] toArray(java.util.ArrayList<Double> list) {
        double[] out = new double[list.size()];

        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }

        return out;
    }

    static byte[] reverse(byte[] input) {
        byte[] out = new byte[input.length];

        for (int i = 0; i < input.length; i++) {
            out[i] = input[input.length - 1 - i];
        }

        return out;
    }

    static double clamp(double x) {
        if (x > 1.0) return 1.0;
        if (x < -1.0) return -1.0;
        return x;
    }

    static byte[] buildStereoWavBytes(double[] left, double[] right) throws Exception {
        int frames = Math.min(left.length, right.length);
        int channels = 2;
        int bytesPerSample = 2;
        int dataSize = frames * channels * bytesPerSample;
        int totalSize = 36 + dataSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream();

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

        for (int i = 0; i < frames; i++) {
            writeLE16(out, (int) (clamp(left[i]) * 32767.0));
            writeLE16(out, (int) (clamp(right[i]) * 32767.0));
        }

        return out.toByteArray();
    }

    static void writeAscii(ByteArrayOutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    static void writeLE16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    static void writeLE32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }
}
