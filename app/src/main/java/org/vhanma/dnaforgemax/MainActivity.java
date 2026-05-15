package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 777;

    TextView log;
    Button pickButton;

    static final int SAMPLE_RATE = 44100;
    static final double F0 = 6400.0;
    static final double F1 = 7000.0;
    static final double BIT_MS = 3.0;
    static final double HE_NE_HZ = 6328.0;
    static final double AURA_HZ = 528.0;
    static final double SCHUMANN_HZ = 7.83;
    static final int MAX_DIM = 32;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xff070714);

        TextView title = new TextView(this);
        title.setText("DNA Forge Max\nNative Android Wavecode Encoder");
        title.setTextColor(0xff00e5ff);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        pickButton = new Button(this);
        pickButton.setText("PICK IMAGE → MAKE WAVECODE WAV");
        root.addView(pickButton);

        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(0xffb8ffb8);
        log.setTextSize(13);
        log.setText("Ready.\nTap the button and choose an image.\n\n");
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        pickButton.setOnClickListener(v -> openPicker());
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
                    runOnUiThread(() -> pickButton.setEnabled(false));
                    say("Reading image...");

                    InputStream in = getContentResolver().openInputStream(uri);
                    Bitmap bmp = BitmapFactory.decodeStream(in);

                    if (bmp == null) {
                        say("Could not decode image.");
                        return;
                    }

                    File outDir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DNA_FORGE_MAX");
                    outDir.mkdirs();

                    String base = "DNA_Forge_Max_" + System.currentTimeMillis();
                    File wav = new File(outDir, base + ".wav");
                    File manifest = new File(outDir, base + "_manifest.json");

                    encodeBitmap(bmp, wav, manifest);

                    say("");
                    say("DONE.");
                    say("WAV:");
                    say(wav.getAbsolutePath());
                    say("");
                    say("Manifest:");
                    say(manifest.getAbsolutePath());
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

    static Bitmap shrink(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();

        double scale = Math.min((double) MAX_DIM / w, (double) MAX_DIM / h);
        if (scale >= 1.0) return src.copy(Bitmap.Config.ARGB_8888, false);

        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    void encodeBitmap(Bitmap original, File wavFile, File manifestFile) throws Exception {
        say("Shrinking image...");
        Bitmap bmp = shrink(original);

        byte[] rgba = bitmapToRgba(bmp);
        byte[] compressed = deflate(rgba);

        CRC32 crc = new CRC32();
        crc.update(compressed);

        String metadata = buildMetadata(bmp.getWidth(), bmp.getHeight(), rgba, compressed);
        byte[] metaBytes = metadata.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream packetOut = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(packetOut);

        dos.writeBytes("DFMAXN1");
        dos.writeInt(bmp.getWidth());
        dos.writeInt(bmp.getHeight());
        dos.writeByte(4);
        dos.writeInt(compressed.length);
        dos.writeInt((int) crc.getValue());
        dos.writeShort(metaBytes.length);
        dos.write(metaBytes);
        dos.write(compressed);
        dos.flush();

        byte[] packet = packetOut.toByteArray();

        say("Packet bytes: " + packet.length);
        say("Generating wavecode audio...");

        double[] samples = makeWavecode(packet);
        writeWav(wavFile, samples);

        FileOutputStream mf = new FileOutputStream(manifestFile);
        mf.write(metadata.getBytes(StandardCharsets.UTF_8));
        mf.close();

        say("Seconds: " + Math.round(samples.length * 10.0 / SAMPLE_RATE) / 10.0);
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

    String buildMetadata(int w, int h, byte[] raw, byte[] compressed) {
        return "{\n" +
                "  \"name\":\"DNA Forge Max Native Android\",\n" +
                "  \"safety\":\"No medical claims. Consent-based, non-invasive, low-power only.\",\n" +
                "  \"image\":{\"width\":" + w + ",\"height\":" + h + "},\n" +
                "  \"dna_payload\":\"RGBA pixels compressed into binary packet\",\n" +
                "  \"gariaev_layer\":{\"zero_hz\":6400,\"one_hz\":7000,\"he_ne_preamble_hz\":6328,\"original_carrier_hz\":[640000,700000]},\n" +
                "  \"levin_layer\":\"bioelectric morphogenesis map metadata\",\n" +
                "  \"rife_layer\":{\"sweep_hz\":[20,20000]},\n" +
                "  \"tesla_layer\":{\"schumann_gate_hz\":7.83,\"pulse_rhythm\":\"3-6-9\"},\n" +
                "  \"bearden_scalar_layer\":{\"coil_marker_hz\":384000,\"phase_conjugate_reverse_hash\":true},\n" +
                "  \"infrared_layer\":{\"wavelengths_nm\":[632.8,660,850,940],\"note\":\"metadata only\"},\n" +
                "  \"expanded_modules\":[\"donor-template chamber\",\"polarized He-Ne optical correlator\",\"MBER carrier\",\"scalar plasma longitudinal stage\",\"sound-language encoder\",\"water liquid-crystal memory\",\"biofield detector\",\"feedback loop\",\"target resonance lock\",\"control placebo channel\"],\n" +
                "  \"meaning_phrase\":\"restore coherence copy this pattern stabilize this geometry resonate with this template\"\n" +
                "}\n";
    }

    static double[] makeWavecode(byte[] packet) {
        int bitSamples = Math.max(80, (int) (SAMPLE_RATE * BIT_MS / 1000.0));
        java.util.ArrayList<Double> samples = new java.util.ArrayList<>();

        addSilence(samples, 300);
        addTone(samples, HE_NE_HZ, 750, 0.45);
        addSilence(samples, 50);

        String phrase = "restore coherence copy this pattern stabilize this geometry resonate with this template";
        byte[] phraseBytes = phrase.getBytes(StandardCharsets.UTF_8);

        for (byte b : phraseBytes) {
            for (int i = 7; i >= 0; i--) {
                int bit = (b >> i) & 1;
                addTone(samples, bit == 1 ? 528.0 : 396.0, bit == 1 ? 28.0 : 14.0, 0.28);
                addSilence(samples, 6);
            }
        }

        addSilence(samples, 50);

        double phase = 0.0;

        for (byte b : packet) {
            for (int i = 7; i >= 0; i--) {
                int bit = (b >> i) & 1;
                double freq = bit == 1 ? F1 : F0;
                double step = 2.0 * Math.PI * freq / SAMPLE_RATE;

                for (int s = 0; s < bitSamples; s++) {
                    samples.add(Math.sin(phase) * 0.70);
                    phase = (phase + step) % (2.0 * Math.PI);
                }
            }
        }

        addSilence(samples, 300);

        double[] out = new double[samples.size()];

        for (int i = 0; i < samples.size(); i++) {
            double t = i / (double) SAMPLE_RATE;
            double base = samples.get(i);
            double aura = Math.sin(2.0 * Math.PI * AURA_HZ * t) * 0.07;

            double gateWave = (1.0 + Math.sin(2.0 * Math.PI * SCHUMANN_HZ * t)) / 2.0;
            double gateEnv = 0.65 + 0.35 * gateWave;

            double pulse = 1.0;
            int beat = ((int) (t * 9.0)) % 9;
            if (beat == 2 || beat == 5 || beat == 8) pulse = 1.08;

            out[i] = clamp(base * gateEnv * pulse + aura);
        }

        return out;
    }

    static void addSilence(java.util.ArrayList<Double> samples, double ms) {
        int n = (int) (SAMPLE_RATE * ms / 1000.0);
        for (int i = 0; i < n; i++) samples.add(0.0);
    }

    static void addTone(java.util.ArrayList<Double> samples, double freq, double ms, double amp) {
        int n = (int) (SAMPLE_RATE * ms / 1000.0);
        int fade = Math.max(1, (int) (SAMPLE_RATE * 0.005));

        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < fade) env = i / (double) fade;
            else if (i > n - fade) env = Math.max(0.0, (n - i) / (double) fade);

            samples.add(Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE) * amp * env);
        }
    }

    static double clamp(double x) {
        if (x > 1.0) return 1.0;
        if (x < -1.0) return -1.0;
        return x;
    }

    static void writeWav(File file, double[] samples) throws Exception {
        int dataSize = samples.length * 2;
        int totalSize = 36 + dataSize;

        FileOutputStream out = new FileOutputStream(file);

        writeAscii(out, "RIFF");
        writeLE32(out, totalSize);
        writeAscii(out, "WAVE");

        writeAscii(out, "fmt ");
        writeLE32(out, 16);
        writeLE16(out, 1);
        writeLE16(out, 1);
        writeLE32(out, SAMPLE_RATE);
        writeLE32(out, SAMPLE_RATE * 2);
        writeLE16(out, 2);
        writeLE16(out, 16);

        writeAscii(out, "data");
        writeLE32(out, dataSize);

        for (double s : samples) {
            int v = (int) (clamp(s) * 32767.0);
            writeLE16(out, v);
        }

        out.flush();
        out.close();
    }

    static void writeAscii(FileOutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    static void writeLE16(FileOutputStream out, int v) throws Exception {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    static void writeLE32(FileOutputStream out, int v) throws Exception {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }
}
