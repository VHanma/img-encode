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

    // Gariaev audio-scaled carrier
    static final double F0 = 6400.0;
    static final double F1 = 7000.0;

    // Safe streaming bit time
    static final double BIT_MS = 1.5;

    // Reference wave identity markers
    static final double HE_NE_HZ = 6328.0;
    static final double HE_NE_MS = 500.0;

    // Reference lattice
    static final double AURA_HZ = 528.0;
    static final double SCHUMANN_HZ = 7.83;
    static final double REFERENCE_HE_NE_AMP = 0.018;
    static final double REFERENCE_528_AMP = 0.028;
    static final double REFERENCE_40_AMP = 0.010;

    static final double GATE_DEPTH = 0.35;

    // Safe size. Streaming prevents crash, but this keeps files manageable.
    static final int MAX_DIM = 24;

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
        title.setText("DNA Forge Max v9\nLiving Capsule + Reference Wave Lock");
        title.setTextColor(0xff00e5ff);
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        pickButton = new Button(this);
        pickButton.setText("PICK IMAGE → MAKE v9 REFERENCE-LOCK WAV");
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
                "Ready.\n\n" +
                "v9 combines v8 Living Wavecode Capsule with Reference Wave Lock.\n" +
                "This version streams audio directly to file so Android does not kill the app.\n\n" +
                "Core secret: the image is not just data. It is a controlled deformation of a stable reference wave.\n\n"
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
                    if (in != null) in.close();

                    if (bmp == null) {
                        say("Could not decode image.");
                        return;
                    }

                    say("Building v9 living capsule...");
                    Capsule capsule = buildCapsule(bmp);

                    int totalFrames = calculateTotalFrames(capsule.capsuleBytes.length, capsule.meaningDna);

                    say("Estimated seconds: " + r(totalFrames / (double) SAMPLE_RATE));
                    say("Streaming stereo WAV directly to Music folder...");
                    say("No giant RAM buffer. No cutoff.");

                    String base = "DNA_Forge_Max_v9_" + System.currentTimeMillis();

                    lastWavUri = saveWavToMusic(
                            base + ".wav",
                            capsule.capsuleBytes,
                            capsule.meaningDna,
                            totalFrames
                    );

                    say("Saving capsule manifest...");
                    lastManifestUri = saveTextToDownloads(
                            base + "_reference_wave_capsule_manifest.json",
                            capsule.manifest
                    );

                    say("");
                    say("DONE.");
                    say("");
                    say("WAV:");
                    say("Music/DNA_FORGE_MAX/" + base + ".wav");
                    say("");
                    say("Manifest:");
                    say("Downloads/DNA_FORGE_MAX/" + base + "_reference_wave_capsule_manifest.json");
                    say("");
                    say("Tap PLAY LAST WAV or SHARE LAST WAV.");

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
            startActivity(Intent.createChooser(send, "Share DNA Forge v9 WAV"));
        } catch (Exception e) {
            say("Could not share WAV.");
        }
    }

    void toggleLightPulse() {
        lightPulseOn = !lightPulseOn;

        if (lightPulseOn) {
            say("Light pulse preview ON.");
            say("Visible red / He-Ne proxy only. Phone screen is not true infrared.");
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

    static class Capsule {
        byte[] capsuleBytes;
        String manifest;
        String meaningDna;
        Geometry geometry;

        Capsule(byte[] capsuleBytes, String manifest, String meaningDna, Geometry geometry) {
            this.capsuleBytes = capsuleBytes;
            this.manifest = manifest;
            this.meaningDna = meaningDna;
            this.geometry = geometry;
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

    Capsule buildCapsule(Bitmap original) throws Exception {
        Bitmap bmp = shrink(original);

        say("Extracting geometry soul...");
        byte[] rgba = bitmapToRgba(bmp);
        byte[] compressed = deflate(rgba);

        Geometry geo = extractGeometry(bmp, rgba, compressed);

        say("Encoding meaning phrase as symbolic DNA...");
        String meaningDna = phraseToDna(MEANING_PHRASE);

        String embeddedManifest = buildManifest(geo, compressed.length, meaningDna, 0, false);
        byte[] metaBytes = embeddedManifest.getBytes(StandardCharsets.UTF_8);

        CRC32 payloadCrcObj = new CRC32();
        payloadCrcObj.update(compressed);
        long payloadCrc = payloadCrcObj.getValue();

        ByteArrayOutputStream coreOut = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(coreOut);

        dos.writeBytes("DFMAXV9");
        dos.writeInt(geo.width);
        dos.writeInt(geo.height);
        dos.writeByte(4);
        dos.writeInt(compressed.length);
        dos.writeInt((int) payloadCrc);
        dos.writeShort(metaBytes.length);
        dos.write(metaBytes);
        dos.write(compressed);
        dos.flush();

        byte[] corePacket = coreOut.toByteArray();

        CRC32 reverseCrcObj = new CRC32();
        reverseCrcObj.update(reverse(corePacket));
        long reverseCrc = reverseCrcObj.getValue();

        ByteArrayOutputStream capsuleOut = new ByteArrayOutputStream();
        DataOutputStream cds = new DataOutputStream(capsuleOut);

        cds.writeBytes("CAPSULE9");
        cds.writeInt(corePacket.length);
        cds.writeInt((int) payloadCrc);
        cds.writeInt((int) reverseCrc);

        int headerChunk = Math.min(128, corePacket.length);

        // Triple header repetition for survival.
        cds.write(corePacket, 0, headerChunk);
        cds.write(corePacket, 0, headerChunk);
        cds.write(corePacket, 0, headerChunk);

        // Main object packet.
        cds.write(corePacket);
        cds.flush();

        byte[] capsuleBytes = capsuleOut.toByteArray();

        String finalManifest = buildManifest(
                geo,
                compressed.length,
                meaningDna,
                capsuleBytes.length,
                true
        );

        say("Capsule bytes: " + capsuleBytes.length);
        say("Reference wave lock ready.");

        return new Capsule(capsuleBytes, finalManifest, meaningDna, geo);
    }

    Uri saveWavToMusic(String displayName, byte[] capsuleBytes, String meaningDna, int totalFrames) throws Exception {
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
        if (out == null) {
            throw new Exception("Could not open WAV output stream.");
        }

        writeLivingReferenceLockedWav(out, capsuleBytes, meaningDna, totalFrames);

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
        if (Build.VERSION.SDK_INT < 29) {
            return null;
        }

        ContentResolver resolver = getContentResolver();

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
        if (out == null) {
            throw new Exception("Could not open manifest output stream.");
        }

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

    static String buildManifest(
            Geometry g,
            int compressedBytes,
            String meaningDna,
            int capsuleBytes,
            boolean finalManifest
    ) {
        return "{\n" +
                "  \"name\":\"DNA Forge Max v9 - Living Wavecode Capsule + Reference Wave Lock\",\n" +
                "  \"final_manifest\":" + finalManifest + ",\n" +
                "  \"safety\":\"No medical claims. Consent-based, non-invasive, low-power only. Phone screen is not true infrared.\",\n" +
                "  \"image\":{\"width\":" + g.width + ",\"height\":" + g.height + "},\n" +
                "  \"compressed_payload_bytes\":" + compressedBytes + ",\n" +
                "  \"capsule_bytes\":" + capsuleBytes + ",\n" +
                "  \"core_insight\":\"The image is a controlled deformation of a stable reference wave. The receiver reconstructs meaning from reference, object, and mirror return.\",\n" +
                "  \"reference_wave_lock\":{\"active\":true,\"continuous_reference_lattice\":[528,6328,40,7.83],\"mid_side_holography\":true},\n" +
                "  \"audio_format\":{\"wav\":\"stereo 16-bit PCM\",\"left\":\"object wave / forward image packet\",\"right\":\"reference-locked mirror / phase-conjugate return\"},\n" +
                "  \"sync_key\":{\"he_ne_hz\":6328,\"chirp_hz\":[6400,7000],\"gate_hz\":7.83,\"golden_mean_timing\":true},\n" +
                "  \"error_survival\":{\"crc32\":true,\"triple_header\":true,\"reverse_crc\":true,\"stream_to_file\":true},\n" +
                "  \"meaning_layer\":{\"phrase\":\"" + escape(MEANING_PHRASE) + "\",\"symbolic_dna\":\"" + meaningDna + "\"},\n" +
                "  \"geometry_soul\":{\"brightness\":" + r(g.brightness) + ",\"red\":" + r(g.red) + ",\"green\":" + r(g.green) + ",\"blue\":" + r(g.blue) + ",\"edge_density\":" + r(g.edgeDensity) + ",\"symmetry\":" + r(g.symmetry) + ",\"fractal_compression_proxy\":" + r(g.fractalCompressionProxy) + "},\n" +
                "  \"gariaev_layer\":{\"zero_hz\":6400,\"one_hz\":7000,\"he_ne_preamble_hz\":6328,\"original_carrier_hz\":[640000,700000]},\n" +
                "  \"levin_layer\":\"morphogenetic bioelectric map from brightness, edges, color balance, and symmetry\",\n" +
                "  \"rife_layer\":{\"open_sweep_hz\":[20,20000],\"close_sweep_hz\":[20000,20]},\n" +
                "  \"tesla_layer\":{\"schumann_gate_hz\":7.83,\"pulse_rhythm\":\"3-6-9\",\"standing_wave_reference\":true},\n" +
                "  \"bearden_scalar_layer\":{\"infolded_potential_metadata\":true,\"phase_conjugate_mirror_channel\":true,\"coil_marker_hz\":384000},\n" +
                "  \"infrared_layer\":{\"wavelengths_nm\":[632.8,660,850,940],\"mode\":\"metadata plus red-screen He-Ne proxy\"},\n" +
                "  \"expanded_modules\":[\"donor-template chamber\",\"polarized He-Ne optical correlator\",\"MBER carrier\",\"scalar plasma longitudinal stage\",\"sound-language encoder\",\"water liquid-crystal memory\",\"biofield detector\",\"feedback loop\",\"target resonance lock\",\"control placebo channel\"],\n" +
                "  \"capsule\":\"reference wave plus object modulation plus mirror return plus meaning DNA plus geometry soul plus manifest\"\n" +
                "}\n";
    }

    static int calculateTotalFrames(int capsuleBytes, String dna) {
        int total = 0;

        total += frames(250);
        total += frames(500);
        total += frames(HE_NE_MS);
        total += frames(300);
        total += frames(300);
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

    void writeLivingReferenceLockedWav(
            OutputStream out,
            byte[] capsule,
            String meaningDna,
            int totalFrames
    ) throws Exception {
        writeStereoWavHeader(out, totalFrames);

        SampleWriter sw = new SampleWriter(out);

        say("Writing Rife open sweep...");
        streamStereoSilence(sw, 250);
        streamStereoSweep(sw, 20.0, 20000.0, 500.0, 0.18, false);

        say("Writing triple sync key...");
        streamStereoTone(sw, HE_NE_HZ, HE_NE_MS, 0.38, 0.38, 0.0, Math.PI / 2.0);
        streamStereoSweep(sw, F0, F1, 300.0, 0.30, true);
        streamSchumannPulseWindow(sw, 300.0);

        say("Writing symbolic DNA meaning rhythm...");
        streamMeaningDnaRhythm(sw, meaningDna);

        say("Writing object wave + mirror return...");
        streamPacketBits(sw, capsule);

        say("Writing Rife close sweep...");
        streamStereoSweep(sw, 20000.0, 20.0, 500.0, 0.18, false);
        streamStereoSilence(sw, 250);

        if (sw.framesWritten != totalFrames) {
            say("Frame note: expected " + totalFrames + " wrote " + sw.framesWritten);
        }
    }

    static class SampleWriter {
        OutputStream out;
        int framesWritten = 0;

        SampleWriter(OutputStream out) {
            this.out = out;
        }
    }

    static void writeReferenceLockedFrame(SampleWriter sw, double objectLeft, double objectRight) throws Exception {
        double t = sw.framesWritten / (double) SAMPLE_RATE;

        // Stable reference lattice. This exists through the whole file.
        double reference =
                Math.sin(2.0 * Math.PI * AURA_HZ * t) * REFERENCE_528_AMP +
                Math.sin(2.0 * Math.PI * HE_NE_HZ * t) * REFERENCE_HE_NE_AMP +
                Math.sin(2.0 * Math.PI * 40.0 * t) * REFERENCE_40_AMP;

        double gateWave = (1.0 + Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t)) / 2.0;
        double gateEnv = 1.0 - GATE_DEPTH + GATE_DEPTH * gateWave;

        double pulse = 1.0;
        int beat = ((int) (t * 9.0)) % 9;
        if (beat == 2 || beat == 5 || beat == 8) {
            pulse = 1.08;
        }

        // Mid/side holographic stereo:
        // reference is common mid field, object/mirror are side deformations.
        double left = clamp(reference + objectLeft * gateEnv * pulse);
        double right = clamp(reference + objectRight * gateEnv * pulse);

        writeLE16(sw.out, (int) (left * 32767.0));
        writeLE16(sw.out, (int) (right * 32767.0));

        sw.framesWritten++;
    }

    static void streamStereoSilence(SampleWriter sw, double ms) throws Exception {
        int n = frames(ms);

        for (int i = 0; i < n; i++) {
            writeReferenceLockedFrame(sw, 0.0, 0.0);
        }
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
        int fade = Math.max(1, frames(5.0));

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

    static void streamStereoSweep(
            SampleWriter sw,
            double startHz,
            double endHz,
            double ms,
            double amp,
            boolean oppositePhase
    ) throws Exception {
        int n = frames(ms);

        double phaseL = 0.0;
        double phaseR = oppositePhase ? Math.PI : Math.PI / 2.0;

        for (int i = 0; i < n; i++) {
            double frac = i / Math.max(1.0, n - 1.0);
            double freq = startHz + (endHz - startHz) * frac;
            double step = 2.0 * Math.PI * freq / SAMPLE_RATE;

            double l = Math.sin(phaseL) * amp;
            double r = Math.sin(phaseR) * amp;

            writeReferenceLockedFrame(sw, l, r);

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

            streamStereoTone(sw, hz, ms, 0.26, 0.18, 0.0, Math.PI / 2.0);
            streamStereoSilence(sw, 2.0);
        }

        streamStereoSilence(sw, 60.0);
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

                double stepL = 2.0 * Math.PI * freqL / SAMPLE_RATE;
                double stepR = 2.0 * Math.PI * freqR / SAMPLE_RATE;

                for (int s = 0; s < bitSamples; s++) {
                    double l = Math.sin(phaseL) * 0.60;
                    double r = Math.sin(phaseR) * 0.60;

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
