package com.vhanma.jelliforge;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

final class GifEncoder332 {
    private final OutputStream out;
    private final int width;
    private final int height;
    private final int delayCs;
    private boolean started;

    GifEncoder332(OutputStream out, int width, int height, int delayMs) {
        this.out = out;
        this.width = width;
        this.height = height;
        this.delayCs = Math.max(2, Math.round(delayMs / 10f));
    }

    void start() throws IOException {
        if (started) return;
        out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
        writeShort(width);
        writeShort(height);
        out.write(0xF7);
        out.write(0);
        out.write(0);
        writePalette332();
        writeLoopExtension();
        started = true;
    }

    void addFrame(Bitmap bitmap) throws IOException {
        if (!started) start();
        Bitmap frame = bitmap;
        Bitmap scaled = null;
        if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
            scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
            frame = scaled;
        }
        int[] pixels = new int[width * height];
        frame.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] indexed = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int a = Color.alpha(c);
            int r = Color.red(c);
            int g = Color.green(c);
            int b = Color.blue(c);
            if (a < 255) {
                r = (r * a + 20 * (255 - a)) / 255;
                g = (g * a + 20 * (255 - a)) / 255;
                b = (b * a + 25 * (255 - a)) / 255;
            }
            indexed[i] = (byte) (((r >> 5) << 5) | ((g >> 5) << 2) | (b >> 6));
        }
        writeGraphicControl();
        writeImageDescriptor();
        writeLzw(indexed);
        if (scaled != null) scaled.recycle();
    }

    void finish() throws IOException {
        if (!started) return;
        out.write(0x3B);
        out.flush();
        started = false;
    }

    private void writePalette332() throws IOException {
        for (int i = 0; i < 256; i++) {
            int r = ((i >> 5) & 7) * 255 / 7;
            int g = ((i >> 2) & 7) * 255 / 7;
            int b = (i & 3) * 255 / 3;
            out.write(r);
            out.write(g);
            out.write(b);
        }
    }

    private void writeLoopExtension() throws IOException {
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.write("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
        out.write(3);
        out.write(1);
        writeShort(0);
        out.write(0);
    }

    private void writeGraphicControl() throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(0);
        writeShort(delayCs);
        out.write(0);
        out.write(0);
    }

    private void writeImageDescriptor() throws IOException {
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0);
    }

    private void writeShort(int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private void writeLzw(byte[] data) throws IOException {
        out.write(8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.max(1024, data.length / 2));
        BitPacker bits = new BitPacker(compressed);
        final int clear = 256;
        final int end = 257;
        int codeSize = 9;
        int nextCode = 258;
        HashMap<Integer, Integer> dict = new HashMap<>(4096);

        bits.write(clear, codeSize);
        if (data.length == 0) {
            bits.write(end, codeSize);
        } else {
            int prefix = data[0] & 0xFF;
            for (int i = 1; i < data.length; i++) {
                int value = data[i] & 0xFF;
                int key = (prefix << 8) | value;
                Integer found = dict.get(key);
                if (found != null) {
                    prefix = found;
                } else {
                    bits.write(prefix, codeSize);
                    if (nextCode < 4096) {
                        dict.put(key, nextCode++);
                        if (nextCode == (1 << codeSize) && codeSize < 12) codeSize++;
                    } else {
                        bits.write(clear, codeSize);
                        dict.clear();
                        codeSize = 9;
                        nextCode = 258;
                    }
                    prefix = value;
                }
            }
            bits.write(prefix, codeSize);
            bits.write(end, codeSize);
        }
        bits.flush();
        byte[] bytes = compressed.toByteArray();
        int offset = 0;
        while (offset < bytes.length) {
            int len = Math.min(255, bytes.length - offset);
            out.write(len);
            out.write(bytes, offset, len);
            offset += len;
        }
        out.write(0);
    }

    private static final class BitPacker {
        private final ByteArrayOutputStream out;
        private int buffer;
        private int bitCount;

        BitPacker(ByteArrayOutputStream out) {
            this.out = out;
        }

        void write(int code, int bits) {
            buffer |= (code << bitCount);
            bitCount += bits;
            while (bitCount >= 8) {
                out.write(buffer & 0xFF);
                buffer >>>= 8;
                bitCount -= 8;
            }
        }

        void flush() {
            if (bitCount > 0) {
                out.write(buffer & 0xFF);
                buffer = 0;
                bitCount = 0;
            }
        }
    }
}
