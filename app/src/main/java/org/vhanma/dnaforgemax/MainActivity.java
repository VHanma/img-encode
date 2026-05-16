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
    static final int PICK_IMAGE = 777;
    static final int SR = 44100;
    static final int MAX_DIM = 24;
    static final double BIT_MS = 1.5;
    static final double F0 = 6400.0;
    static final double F1 = 7000.0;
    static final double HE_NE = 6328.0;
    static final double SCHUMANN = 7.83;
    static final String PHRASE = "restore coherence copy this pattern stabilize this geometry resonate with this template";

    LinearLayout root;
    TextView log;
    Button pick, play, share, light;
    Uri lastWav = null;
    Handler handler = new Handler(Looper.getMainLooper());
    boolean lightOn = false;
    int tick = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setBackgroundColor(0xff070714);

        TextView title = new TextView(this);
        title.setText("DNA Forge Max v10\nHermes + Reference Wave Lock");
        title.setTextColor(0xff00e5ff);
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        pick = new Button(this);
        pick.setText("PICK IMAGE → MAKE HERMES WAVECODE");
        root.addView(pick);

        play = new Button(this);
        play.setText("PLAY LAST WAV");
        play.setEnabled(false);
        root.addView(play);

        share = new Button(this);
        share.setText("SHARE LAST WAV");
        share.setEnabled(false);
        root.addView(share);

        light = new Button(this);
        light.setText("RED / HE-NE LIGHT PULSE");
        root.addView(light);

        ScrollView scroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(0xffb8ffb8);
        log.setTextSize(13);
        log.setText("Ready.\n\nv10 = v8 capsule + v9 reference wave lock + Hermes ancient-code grammar.\n\nThe image is translated through geometry, meaning, reference wave, mirror return, and ancient-code grammar.\n\n");
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        pick.setOnClickListener(v -> chooseImage());
        play.setOnClickListener(v -> playLast());
        share.setOnClickListener(v -> shareLast());
        light.setOnClickListener(v -> toggleLight());
    }

    void chooseImage() {
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
                        pick.setEnabled(false);
                        play.setEnabled(false);
                        share.setEnabled(false);
                    });

                    say("Reading image...");
                    InputStream in = getContentResolver().openInputStream(uri);
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (in != null) in.close();

                    if (bmp == null) {
                        say("Could not decode image.");
                        return;
                    }

                    say("Building capsule...");
                    Capsule cap = buildCapsule(bmp);
                    int totalFrames = calcFrames(cap.bytes.length, cap.dna);
                    say("Estimated seconds: " + fmt(totalFrames / (double) SR));
                    say("Streaming WAV straight to Music folder...");

                    String base = "DNA_Forge_Max_v10_" + System.currentTimeMillis();
                    lastWav = saveWav(base + ".wav", cap, totalFrames);
                    saveManifest(base + "_manifest.json", cap.manifest);

                    say("");
                    say("DONE.");
                    say("WAV saved in: Music/DNA_FORGE_MAX/" + base + ".wav");
                    say("Manifest saved in: Downloads/DNA_FORGE_MAX/" + base + "_manifest.json");
                    say("");
                    say("Tap PLAY LAST WAV.");

                    runOnUiThread(() -> {
                        play.setEnabled(true);
                        share.setEnabled(true);
                    });
                } catch (Exception e) {
                    say("ERROR: " + e.toString());
                } finally {
                    runOnUiThread(() -> pick.setEnabled(true));
                }
            }).start();
        }
    }

    void say(String s) {
        runOnUiThread(() -> log.append(s + "\n"));
    }

    void playLast() {
        if (lastWav == null) {
            say("No WAV yet.");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastWav, "audio/wav");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            say("Could not open player. Use Files → Music → DNA_FORGE_MAX.");
        }
    }

    void shareLast() {
        if (lastWav == null) {
            say("No WAV yet.");
            return;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("audio/wav");
            send.putExtra(Intent.EXTRA_STREAM, lastWav);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share v10 WAV"));
        } catch (Exception e) {
            say("Could not share.");
        }
    }

    void toggleLight() {
        lightOn = !lightOn;
        if (lightOn) {
            say("Light pulse ON. Visible red proxy only. Phone screen is not true infrared.");
            pulseLight();
        } else {
            root.setBackgroundColor(0xff070714);
            say("Light pulse OFF.");
        }
    }

    void pulseLight() {
        if (!lightOn) return;
        tick++;
        int phase = tick % 9;
        if (phase == 2 || phase == 5 || phase == 8) root.setBackgroundColor(0xffff0000);
        else if (phase % 2 == 0) root.setBackgroundColor(0xff330000);
        else root.setBackgroundColor(0xff070714);
        handler.postDelayed(this::pulseLight, 111);
    }

    static class Geo {
        int w, h;
        double bright, red, green, blue, edge, sym, fract;
        long rawCrc, compCrc;
    }

    static class Capsule {
        byte[] bytes;
        String dna;
        String manifest;
        Geo geo;
        Capsule(byte[] bytes, String dna, String manifest, Geo geo) {
            this.bytes = bytes;
            this.dna = dna;
            this.manifest = manifest;
            this.geo = geo;
        }
    }

    Capsule buildCapsule(Bitmap src) throws Exception {
        Bitmap bmp = shrink(src);
        byte[] rgba = rgba(bmp);
        byte[] comp = deflate(rgba);
        Geo g = geometry(bmp, rgba, comp);
        String dna = phraseToDna(PHRASE);

        String meta = manifest(g, comp.length, 0, dna, false);
        byte[] metaBytes = meta.getBytes(StandardCharsets.UTF_8);

        CRC32 crc = new CRC32();
        crc.update(comp);

        ByteArrayOutputStream core = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(core);
        d.writeBytes("DFMAX10");
        d.writeInt(g.w);
        d.writeInt(g.h);
        d.writeByte(4);
        d.writeInt(comp.length);
        d.writeInt((int) crc.getValue());
        d.writeShort(metaBytes.length);
        d.write(metaBytes);
        d.write(comp);
        d.flush();

        byte[] coreBytes = core.toByteArray();
        CRC32 rev = new CRC32();
        rev.update(reverse(coreBytes));

        ByteArrayOutputStream cap = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(cap);
        c.writeBytes("CAPSL10");
        c.writeInt(coreBytes.length);
        c.writeInt((int) crc.getValue());
        c.writeInt((int) rev.getValue());

        int header = Math.min(128, coreBytes.length);
        c.write(coreBytes, 0, header);
        c.write(coreBytes, 0, header);
        c.write(coreBytes, 0, header);
        c.write(coreBytes);
        c.flush();

        byte[] capsuleBytes = cap.toByteArray();
        String finalManifest = manifest(g, comp.length, capsuleBytes.length, dna, true);

        return new Capsule(capsuleBytes, dna, finalManifest, g);
    }

    static Bitmap shrink(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min((double) MAX_DIM / w, (double) MAX_DIM / h);
        if (scale >= 1.0) return src.copy(Bitmap.Config.ARGB_8888, false);
        return Bitmap.createScaledBitmap(src, Math.max(1, (int)Math.round(w * scale)), Math.max(1, (int)Math.round(h * scale)), true);
    }

    static byte[] rgba(Bitmap bmp) throws Exception {
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

    static Geo geometry(Bitmap bmp, byte[] raw, byte[] comp) {
        Geo g = new Geo();
        g.w = bmp.getWidth();
        g.h = bmp.getHeight();
        int count = Math.max(1, g.w * g.h);
        double bright = 0, red = 0, green = 0, blue = 0, edge = 0, mirror = 0;

        for (int y = 0; y < g.h; y++) {
            for (int x = 0; x < g.w; x++) {
                int c = bmp.getPixel(x, y);
                int r = (c >> 16) & 0xff, gr = (c >> 8) & 0xff, b = c & 0xff;
                double gray = (r + gr + b) / 3.0;
                bright += gray; red += r; green += gr; blue += b;

                if (x + 1 < g.w) {
                    int c2 = bmp.getPixel(x + 1, y);
                    edge += Math.abs(gray - ((((c2 >> 16) & 0xff) + ((c2 >> 8) & 0xff) + (c2 & 0xff)) / 3.0));
                }
                if (y + 1 < g.h) {
                    int c3 = bmp.getPixel(x, y + 1);
                    edge += Math.abs(gray - ((((c3 >> 16) & 0xff) + ((c3 >> 8) & 0xff) + (c3 & 0xff)) / 3.0));
                }

                int cm = bmp.getPixel(g.w - 1 - x, y);
                double gm = (((cm >> 16) & 0xff) + ((cm >> 8) & 0xff) + (cm & 0xff)) / 3.0;
                mirror += Math.abs(gray - gm);
            }
        }

        g.bright = bright / (count * 255.0);
        g.red = red / (count * 255.0);
        g.green = green / (count * 255.0);
        g.blue = blue / (count * 255.0);
        g.edge = edge / Math.max(1.0, count * 255.0 * 2.0);
        g.sym = 1.0 - mirror / Math.max(1.0, count * 255.0);
        g.fract = comp.length / Math.max(1.0, raw.length);

        CRC32 rawC = new CRC32(); rawC.update(raw); g.rawCrc = rawC.getValue();
        CRC32 compC = new CRC32(); compC.update(comp); g.compCrc = compC.getValue();

        return g;
    }

    static String phraseToDna(String phrase) {
        byte[] bytes = phrase.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            for (int i = 6; i >= 0; i -= 2) {
                int pair = (b >> i) & 3;
                sb.append(pair == 0 ? 'A' : pair == 1 ? 'C' : pair == 2 ? 'G' : 'T');
            }
        }
        return sb.toString();
    }

    static String manifest(Geo g, int compBytes, int capBytes, String dna, boolean fin) {
        return "{\n" +
                "  \"name\":\"DNA Forge Max v10 - Hermes Reference Wave Capsule\",\n" +
                "  \"final_manifest\":" + fin + ",\n" +
                "  \"safety\":\"No medical claims. Consent-based, non-invasive, low-power only. Phone screen is not true infrared.\",\n" +
                "  \"image\":{\"width\":" + g.w + ",\"height\":" + g.h + "},\n" +
                "  \"compressed_payload_bytes\":" + compBytes + ",\n" +
                "  \"capsule_bytes\":" + capBytes + ",\n" +
                "  \"core_insight\":\"The image is a controlled deformation of a stable reference wave, translated through geometry, meaning, and Hermes ancient-code grammar.\",\n" +
                "  \"reference_wave_lock\":{\"active\":true,\"continuous_reference_lattice\":[528,6328,40,7.83],\"mid_side_holography\":true},\n" +
                "  \"hermes_ancient_code_layer\":{\"active\":true,\"rule\":\"not random; selected by color, symmetry, edge density, and meaning\",\"modules\":[\"four elemental compass\",\"seven planetary gates\",\"twelvefold temple ring\",\"golden-ratio timing\",\"pyramid axis symmetry\",\"cuneiform wedge pulses\",\"labyrinth spiral return\",\"as-above-so-below mid-side mirror\"]},\n" +
                "  \"meaning_layer\":{\"phrase\":\"" + esc(PHRASE) + "\",\"symbolic_dna\":\"" + dna + "\"},\n" +
                "  \"geometry_soul\":{\"brightness\":" + fmt(g.bright) + ",\"red\":" + fmt(g.red) + ",\"green\":" + fmt(g.green) + ",\"blue\":" + fmt(g.blue) + ",\"edge_density\":" + fmt(g.edge) + ",\"symmetry\":" + fmt(g.sym) + ",\"fractal_compression_proxy\":" + fmt(g.fract) + "},\n" +
                "  \"gariaev_layer\":{\"zero_hz\":6400,\"one_hz\":7000,\"he_ne_preamble_hz\":6328,\"original_carrier_hz\":[640000,700000]},\n" +
                "  \"levin_layer\":\"morphogenetic map from brightness, edges, color balance, and symmetry\",\n" +
                "  \"rife_layer\":{\"open_sweep_hz\":[20,20000],\"close_sweep_hz\":[20000,20]},\n" +
                "  \"tesla_layer\":{\"schumann_gate_hz\":7.83,\"pulse_rhythm\":\"3-6-9\",\"standing_wave_reference\":true},\n" +
                "  \"bearden_scalar_layer\":{\"infolded_potential_metadata\":true,\"phase_conjugate_mirror_channel\":true,\"coil_marker_hz\":384000},\n" +
                "  \"infrared_layer\":{\"wavelengths_nm\":[632.8,660,850,940],\"mode\":\"metadata plus red-screen proxy\"}\n" +
                "}\n";
    }

    Uri saveWav(String name, Capsule cap, int totalFrames) throws Exception {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/DNA_FORGE_MAX");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        }
        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create WAV.");
        OutputStream out = resolver.openOutputStream(uri);
        if (out == null) throw new Exception("Could not open WAV.");
        writeWav(out, cap, totalFrames);
        out.flush(); out.close();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        }
        return uri;
    }

    Uri saveManifest(String name, String text) throws Exception {
        if (Build.VERSION.SDK_INT < 29) return null;
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DNA_FORGE_MAX");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create manifest.");
        OutputStream out = resolver.openOutputStream(uri);
        if (out == null) throw new Exception("Could not open manifest.");
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush(); out.close();
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    static int frames(double ms) { return (int)(SR * ms / 1000.0); }
    static int bitFrames() { return Math.max(60, frames(BIT_MS)); }

    static int calcFrames(int capBytes, String dna) {
        int total = 0;
        total += frames(250);
        total += frames(500);
        total += frames(500);
        total += frames(300);
        total += frames(300);
        total += meaningFrames(dna);
        total += frames(888);
        total += capBytes * 8 * bitFrames();
        total += frames(500);
        total += frames(250);
        return total;
    }

    static int meaningFrames(String dna) {
        int max = Math.min(dna.length(), 240);
        int total = 0;
        for (int i = 0; i < max; i++) {
            char c = dna.charAt(i);
            total += frames((c == 'C' || c == 'T') ? 4.854 : 3.0);
            total += frames(2.0);
        }
        return total + frames(60);
    }

    static class SW {
        OutputStream out;
        int frame = 0;
        SW(OutputStream out) { this.out = out; }
    }

    static void writeWav(OutputStream out, Capsule cap, int totalFrames) throws Exception {
        writeHeader(out, totalFrames);
        SW sw = new SW(out);

        streamSilence(sw, 250);
        streamSweep(sw, 20, 20000, 500, 0.18, false);
        streamTone(sw, HE_NE, 500, 0.38, 0.38, 0, Math.PI / 2);
        streamSweep(sw, F0, F1, 300, 0.30, true);
        streamSchumann(sw, 300);
        streamMeaning(sw, cap.dna);
        streamHermes(sw, cap.geo);
        streamPacket(sw, cap.bytes);
        streamSweep(sw, 20000, 20, 500, 0.18, false);
        streamSilence(sw, 250);
    }

    static void frame(SW sw, double objL, double objR) throws Exception {
        double t = sw.frame / (double) SR;
        double ref =
                Math.sin(2 * Math.PI * 528.0 * t) * 0.028 +
                Math.sin(2 * Math.PI * HE_NE * t) * 0.018 +
                Math.sin(2 * Math.PI * 40.0 * t) * 0.010;

        double gate = 0.65 + 0.35 * ((1 + Math.sin(2 * Math.PI * SCHUMANN * t)) / 2.0);
        double pulse = 1.0;
        int beat = ((int)(t * 9.0)) % 9;
        if (beat == 2 || beat == 5 || beat == 8) pulse = 1.08;

        double l = clamp(ref + objL * gate * pulse);
        double r = clamp(ref + objR * gate * pulse);

        le16(sw.out, (int)(l * 32767));
        le16(sw.out, (int)(r * 32767));
        sw.frame++;
    }

    static void streamSilence(SW sw, double ms) throws Exception {
        int n = frames(ms);
        for (int i = 0; i < n; i++) frame(sw, 0, 0);
    }

    static void streamTone(SW sw, double hz, double ms, double ampL, double ampR, double phaseL, double phaseR) throws Exception {
        int n = frames(ms);
        int fade = Math.max(1, frames(5));
        for (int i = 0; i < n; i++) {
            double env = 1.0;
            if (i < fade) env = i / (double)fade;
            else if (i > n - fade) env = Math.max(0, (n - i) / (double)fade);
            double t = i / (double)SR;
            frame(sw, Math.sin(2 * Math.PI * hz * t + phaseL) * ampL * env, Math.sin(2 * Math.PI * hz * t + phaseR) * ampR * env);
        }
    }

    static void streamSweep(SW sw, double start, double end, double ms, double amp, boolean opposite) throws Exception {
        int n = frames(ms);
        double pL = 0, pR = opposite ? Math.PI : Math.PI / 2;
        for (int i = 0; i < n; i++) {
            double frac = i / Math.max(1.0, n - 1.0);
            double hz = start + (end - start) * frac;
            double step = 2 * Math.PI * hz / SR;
            frame(sw, Math.sin(pL) * amp, Math.sin(pR) * amp);
            pL = (pL + step) % (2 * Math.PI);
            pR = (pR + step) % (2 * Math.PI);
        }
    }

    static void streamSchumann(SW sw, double ms) throws Exception {
        int n = frames(ms);
        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double gate = (1 + Math.sin(2 * Math.PI * SCHUMANN * t)) / 2.0;
            double car = Math.sin(2 * Math.PI * 528.0 * t) * 0.15 * gate;
            frame(sw, car, -car);
        }
    }

    static void streamMeaning(SW sw, String dna) throws Exception {
        int max = Math.min(dna.length(), 240);
        for (int i = 0; i < max; i++) {
            char c = dna.charAt(i);
            double hz = c == 'A' ? 396 : c == 'C' ? 528 : c == 'G' ? 639 : 741;
            double ms = (c == 'C' || c == 'T') ? 4.854 : 3.0;
            streamTone(sw, hz, ms, 0.26, 0.18, 0, Math.PI / 2);
            streamSilence(sw, 2.0);
        }
        streamSilence(sw, 60.0);
    }

    static void streamHermes(SW sw, Geo g) throws Exception {
        int n = frames(888);
        double elementHz = (g.red >= g.green && g.red >= g.blue) ? 852.0 : (g.blue >= g.red && g.blue >= g.green) ? 417.0 : 528.0;
        double[] gates = {396,417,528,639,741,852,963};
        double symAmp = 0.08 + 0.16 * clamp(g.sym);
        double edgeAmp = 0.05 + 0.22 * clamp(g.edge * 4.0);
        double brightAmp = 0.05 + 0.12 * clamp(g.bright);

        for (int i = 0; i < n; i++) {
            double t = i / (double)SR;
            double frac = i / Math.max(1.0, n - 1.0);
            int idx = Math.min(6, Math.max(0, (int)Math.floor(frac * 7.0)));
            int mir = 6 - idx;
            double gateL = Math.sin(2 * Math.PI * gates[idx] * t) * symAmp;
            double gateR = Math.sin(2 * Math.PI * gates[mir] * t + Math.PI / 2) * symAmp;
            double compass = Math.sin(2 * Math.PI * elementHz * t) * brightAmp;
            double ring = 0.5 + 0.5 * Math.sin(2 * Math.PI * 12.0 * t);
            double wedge = Math.sin(2 * Math.PI * (9.0 + 18.0 * g.edge) * t) * edgeAmp * ring;
            double golden = Math.sin(2 * Math.PI * (432.0 * 1.61803398875) * t) * 0.025;
            double spiral = Math.sin(2 * Math.PI * (3.0 + 6.0 * frac) * t) * 0.035;
            frame(sw, gateL + compass + wedge + golden + spiral, gateR - compass + wedge * 0.5 + golden - spiral);
        }
    }

    static void streamPacket(SW sw, byte[] data) throws Exception {
        byte[] rev = reverse(data);
        int bf = bitFrames();
        double pL = 0, pR = Math.PI / 2;

        for (int idx = 0; idx < data.length; idx++) {
            int bL = data[idx] & 0xff;
            int bR = rev[idx] & 0xff;
            for (int pos = 7; pos >= 0; pos--) {
                int bitL = (bL >> pos) & 1;
                int bitR = (bR >> pos) & 1;
                double hzL = bitL == 1 ? F1 : F0;
                double hzR = bitR == 1 ? F1 : F0;
                double stepL = 2 * Math.PI * hzL / SR;
                double stepR = 2 * Math.PI * hzR / SR;
                for (int s = 0; s < bf; s++) {
                    frame(sw, Math.sin(pL) * 0.60, Math.sin(pR) * 0.60);
                    pL = (pL + stepL) % (2 * Math.PI);
                    pR = (pR + stepR) % (2 * Math.PI);
                }
            }
        }
    }

    static void writeHeader(OutputStream out, int frames) throws Exception {
        int channels = 2, bps = 2;
        int data = frames * channels * bps;
        ascii(out, "RIFF");
        le32(out, 36 + data);
        ascii(out, "WAVE");
        ascii(out, "fmt ");
        le32(out, 16);
        le16(out, 1);
        le16(out, channels);
        le32(out, SR);
        le32(out, SR * channels * bps);
        le16(out, channels * bps);
        le16(out, 16);
        ascii(out, "data");
        le32(out, data);
    }

    static byte[] reverse(byte[] input) {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) out[i] = input[input.length - 1 - i];
        return out;
    }

    static double clamp(double x) { return x > 1 ? 1 : x < -1 ? -1 : x; }
    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
    static String fmt(double v) { return String.format(Locale.US, "%.6f", v); }
    static void ascii(OutputStream out, String s) throws Exception { out.write(s.getBytes(StandardCharsets.US_ASCII)); }
    static void le16(OutputStream out, int v) throws Exception { out.write(v & 255); out.write((v >> 8) & 255); }
    static void le32(OutputStream out, int v) throws Exception { out.write(v & 255); out.write((v >> 8) & 255); out.write((v >> 16) & 255); out.write((v >> 24) & 255); }
}
