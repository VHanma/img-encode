package org.vhanma.dnaforgemax;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Low-allocation streaming audio engine plus 24-bit PCM / 32-bit float WAV export. */
final class V4Audio {
    private final AtomicReference<float[]> frame = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private AudioTrack track;
    private Thread thread;
    private int rate = 48000;
    private volatile int lastUnderruns = 0;

    int start(int requestedRate) {
        stop();
        int[] rates = requestedRate >= 192000 ? new int[]{192000, 96000, 48000}
                : requestedRate >= 96000 ? new int[]{96000, 48000}
                : new int[]{48000};
        RuntimeException last = null;
        for (int r : rates) {
            try {
                int min = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_FLOAT);
                if (min <= 0) min = Math.max(32768, r / 10 * 8);
                int buffer = Math.max(min * 2, r / 8 * 8);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(r)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(buffer)
                        .build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new RuntimeException("AudioTrack initialization failed at " + r + " Hz");
                }
                rate = r;
                lastUnderruns = 0;
                track.play();
                running.set(true);
                thread = new Thread(this::audioLoop, "OsciVisionV4Audio");
                thread.setPriority(Thread.MAX_PRIORITY);
                thread.start();
                return rate;
            } catch (RuntimeException e) {
                last = e;
                releaseTrack();
            }
        }
        if (last != null) throw last;
        return rate;
    }

    private void audioLoop() {
        while (running.get()) {
            float[] f = frame.get();
            AudioTrack t = track;
            if (f == null || f.length < 4 || t == null) {
                SystemClock.sleep(2L);
                continue;
            }
            int offset = 0;
            while (running.get() && offset < f.length) {
                int wrote = t.write(f, offset, f.length - offset, AudioTrack.WRITE_BLOCKING);
                if (wrote > 0) offset += wrote;
                else if (wrote < 0) {
                    SystemClock.sleep(2L);
                    break;
                } else {
                    SystemClock.sleep(1L);
                }
            }
            if (Build.VERSION.SDK_INT >= 24 && track != null) {
                try { lastUnderruns = track.getUnderrunCount(); } catch (Throwable ignored) {}
            }
        }
    }

    void setFrame(float[] xy) {
        frame.set(xy);
    }

    int sampleRate() {
        return rate;
    }

    int underruns() {
        return lastUnderruns;
    }

    String routeName() {
        AudioTrack t = track;
        if (t == null || Build.VERSION.SDK_INT < 23) return "audio route unknown";
        try {
            AudioDeviceInfo d = t.getRoutedDevice();
            if (d == null) return "default route";
            String product = d.getProductName() == null ? "" : d.getProductName().toString();
            return deviceTypeName(d.getType()) + (product.isEmpty() ? "" : " · " + product);
        } catch (Throwable ignored) {
            return "default route";
        }
    }

    void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        frame.set(null);
        releaseTrack();
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) {}
            try { t.flush(); } catch (Throwable ignored) {}
            try { t.stop(); } catch (Throwable ignored) {}
            try { t.release(); } catch (Throwable ignored) {}
        }
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "BLE headset";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "BLE speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "built-in speaker";
            default: return "audio device " + type;
        }
    }

    interface WavWriter extends AutoCloseable {
        void write(float[] xy) throws Exception;
    }

    static WavWriter createWriter(File file, int sampleRate, boolean float32) throws Exception {
        return float32 ? new Wav32Float(file, sampleRate) : new Wav24Pcm(file, sampleRate);
    }

    private abstract static class BaseWav implements WavWriter {
        final RandomAccessFile raf;
        final int sampleRate;
        final int bits;
        final int formatCode;
        final int bytesPerSample;
        long dataBytes = 0L;

        BaseWav(File file, int sampleRate, int bits, int formatCode) throws Exception {
            this.sampleRate = sampleRate;
            this.bits = bits;
            this.formatCode = formatCode;
            this.bytesPerSample = bits / 8;
            raf = new RandomAccessFile(file, "rw");
            raf.setLength(0L);
            header(0L);
        }

        void header(long data) throws Exception {
            raf.seek(0L);
            raf.writeBytes("RIFF");
            le32(36L + data);
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            le32(16);
            le16(formatCode);
            le16(2);
            le32(sampleRate);
            le32((long) sampleRate * 2L * bytesPerSample);
            le16(2 * bytesPerSample);
            le16(bits);
            raf.writeBytes("data");
            le32(data);
        }

        void le16(int v) throws Exception {
            raf.write(v & 0xff);
            raf.write((v >>> 8) & 0xff);
        }

        void le32(long v) throws Exception {
            raf.write((int) (v & 0xff));
            raf.write((int) ((v >>> 8) & 0xff));
            raf.write((int) ((v >>> 16) & 0xff));
            raf.write((int) ((v >>> 24) & 0xff));
        }

        @Override
        public void close() throws Exception {
            header(dataBytes);
            raf.close();
        }
    }

    private static final class Wav24Pcm extends BaseWav {
        Wav24Pcm(File file, int sampleRate) throws Exception {
            super(file, sampleRate, 24, 1);
        }

        @Override
        public void write(float[] xy) throws Exception {
            if (xy == null) return;
            byte[] buf = new byte[Math.min(131072, Math.max(6144, xy.length * 3))];
            int p = 0;
            for (float v : xy) {
                int s = Math.round(clamp(v, -1f, 1f) * 8388607f);
                if (p + 3 > buf.length) {
                    raf.write(buf, 0, p);
                    dataBytes += p;
                    p = 0;
                }
                buf[p++] = (byte) (s & 0xff);
                buf[p++] = (byte) ((s >>> 8) & 0xff);
                buf[p++] = (byte) ((s >>> 16) & 0xff);
            }
            if (p > 0) {
                raf.write(buf, 0, p);
                dataBytes += p;
            }
        }
    }

    private static final class Wav32Float extends BaseWav {
        Wav32Float(File file, int sampleRate) throws Exception {
            super(file, sampleRate, 32, 3);
        }

        @Override
        public void write(float[] xy) throws Exception {
            if (xy == null) return;
            byte[] buf = new byte[Math.min(131072, Math.max(8192, xy.length * 4))];
            int p = 0;
            for (float v : xy) {
                int bits = Float.floatToIntBits(clamp(v, -1f, 1f));
                if (p + 4 > buf.length) {
                    raf.write(buf, 0, p);
                    dataBytes += p;
                    p = 0;
                }
                buf[p++] = (byte) (bits & 0xff);
                buf[p++] = (byte) ((bits >>> 8) & 0xff);
                buf[p++] = (byte) ((bits >>> 16) & 0xff);
                buf[p++] = (byte) ((bits >>> 24) & 0xff);
            }
            if (p > 0) {
                raf.write(buf, 0, p);
                dataBytes += p;
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
