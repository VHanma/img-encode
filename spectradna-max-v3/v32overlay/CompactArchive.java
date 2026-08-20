package com.vaan.spectradna;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Compact exact archive used by SpectraDNA v3.2.
 *
 * The original source file is stored exactly once. Pixel truth is represented
 * by SHA-256 + tile hashes + Merkle root instead of storing a second PNG and a
 * full raw-RGBA duplicate. This keeps exact original recovery while removing
 * the two largest redundant payloads from v3.1.
 */
public final class CompactArchive {
    private static final byte[] MAGIC = new byte[]{'S','D','N','A','C','M','P','4'};
    private static final int VERSION = 4;
    private static final int MAX_BLOB = 512 * 1024 * 1024;

    public String fileName = "image";
    public String mime = "application/octet-stream";
    public String colorSpace = "unknown";
    public int width;
    public int height;
    public int tileSize = 64;

    public byte[] originalBytes = new byte[0];
    public byte[] originalSha = new byte[32];
    public byte[] canonicalSha = new byte[32]; // compatibility alias: exact stored source SHA
    public byte[] pixelSha = new byte[32];
    public byte[][] tileHashes = new byte[0][];
    public byte[] merkleRoot = new byte[32];

    // Kept only for source compatibility with old UI paths. v3.2 does not
    // serialize these large duplicates.
    public byte[] canonicalPng = new byte[0];
    public byte[] rawRgba = new byte[0];

    public byte[] serialize() throws IOException {
        ByteArrayOutputStream bodyBuf = new ByteArrayOutputStream(Math.max(1024, originalBytes.length + 512));
        DataOutputStream body = new DataOutputStream(bodyBuf);
        body.writeInt(VERSION);
        writeString(body, fileName);
        writeString(body, mime);
        writeString(body, colorSpace);
        body.writeInt(width);
        body.writeInt(height);
        body.writeInt(tileSize);
        writeFixed32(body, originalSha);
        writeFixed32(body, pixelSha);
        writeFixed32(body, merkleRoot);
        body.writeInt(tileHashes == null ? 0 : tileHashes.length);
        if (tileHashes != null) {
            for (byte[] h : tileHashes) writeFixed32(body, h);
        }
        if (originalBytes == null) originalBytes = new byte[0];
        body.writeInt(originalBytes.length);
        body.write(originalBytes);
        body.flush();

        byte[] payload = bodyBuf.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream(payload.length + 24);
        DataOutputStream out = new DataOutputStream(outBuf);
        out.write(MAGIC);
        out.writeInt(payload.length);
        out.writeInt((int) crc.getValue());
        out.write(payload);
        out.flush();
        return outBuf.toByteArray();
    }

    public static CompactArchive parse(byte[] raw) throws IOException {
        if (raw == null || raw.length < MAGIC.length + 12) throw new IOException("Compact archive is truncated");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("Not a SpectraDNA compact archive");
        int bodyLen = in.readInt();
        long storedCrc = in.readInt() & 0xffffffffL;
        if (bodyLen < 0 || bodyLen > MAX_BLOB || bodyLen != raw.length - MAGIC.length - 8) throw new IOException("Compact archive length is invalid");
        byte[] payload = new byte[bodyLen];
        in.readFully(payload);
        CRC32 crc = new CRC32();
        crc.update(payload);
        if (crc.getValue() != storedCrc) throw new IOException("Compact archive CRC mismatch");

        DataInputStream b = new DataInputStream(new ByteArrayInputStream(payload));
        int version = b.readInt();
        if (version != VERSION) throw new IOException("Unsupported compact archive version " + version);
        CompactArchive a = new CompactArchive();
        a.fileName = readString(b, 16 * 1024);
        a.mime = readString(b, 4 * 1024);
        a.colorSpace = readString(b, 4 * 1024);
        a.width = b.readInt();
        a.height = b.readInt();
        a.tileSize = b.readInt();
        if (a.width <= 0 || a.height <= 0 || a.tileSize <= 0 || a.tileSize > 4096) throw new IOException("Compact archive image geometry is invalid");
        a.originalSha = readFixed32(b);
        a.canonicalSha = a.originalSha.clone();
        a.pixelSha = readFixed32(b);
        a.merkleRoot = readFixed32(b);
        int tiles = b.readInt();
        if (tiles < 0 || tiles > 1_000_000) throw new IOException("Compact archive tile count is invalid");
        a.tileHashes = new byte[tiles][];
        for (int i = 0; i < tiles; i++) a.tileHashes[i] = readFixed32(b);
        int originalLen = b.readInt();
        if (originalLen < 0 || originalLen > MAX_BLOB || originalLen > b.available()) throw new IOException("Compact archive source length is invalid");
        a.originalBytes = new byte[originalLen];
        b.readFully(a.originalBytes);
        if (b.available() != 0) throw new IOException("Unexpected bytes at end of compact archive");
        return a;
    }

    /** Fast adaptive compression. Already-compressed photos are kept raw if zlib does not win. */
    public static byte[] compress(byte[] raw) throws IOException {
        if (raw == null) throw new IOException("Missing compact archive bytes");
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream dout = new DeflaterOutputStream(z, deflater, 64 * 1024)) {
            dout.write(raw);
        }
        byte[] packed = z.toByteArray();
        boolean useZ = packed.length + 9 < raw.length;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream((useZ ? packed.length : raw.length) + 9);
        DataOutputStream out = new DataOutputStream(outBuf);
        out.writeInt(0x53444334); // SDC4
        out.writeByte(useZ ? 1 : 0);
        out.writeInt(raw.length);
        out.write(useZ ? packed : raw);
        out.flush();
        return outBuf.toByteArray();
    }

    public static byte[] inflate(byte[] packed) throws IOException {
        if (packed == null || packed.length < 9) throw new IOException("Compressed compact archive is truncated");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(packed));
        if (in.readInt() != 0x53444334) throw new IOException("Unknown compact compression wrapper");
        int mode = in.readUnsignedByte();
        int rawLen = in.readInt();
        if (rawLen < 0 || rawLen > MAX_BLOB) throw new IOException("Compact archive expansion length is invalid");
        byte[] result;
        if (mode == 0) {
            result = new byte[in.available()];
            in.readFully(result);
        } else if (mode == 1) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(rawLen);
            try (InflaterInputStream zin = new InflaterInputStream(in)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = zin.read(buf)) >= 0) {
                    if (n == 0) continue;
                    out.write(buf, 0, n);
                    if (out.size() > rawLen) throw new IOException("Compact archive expands beyond declared length");
                }
            }
            result = out.toByteArray();
        } else {
            throw new IOException("Unknown compact compression mode " + mode);
        }
        if (result.length != rawLen) throw new IOException("Compact archive expansion length mismatch");
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max || n > in.available()) throw new IOException("Compact archive text field is invalid");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeFixed32(DataOutputStream out, byte[] h) throws IOException {
        if (h == null || h.length != 32) throw new IOException("Expected 32-byte hash");
        out.write(h);
    }

    private static byte[] readFixed32(DataInputStream in) throws IOException {
        byte[] h = new byte[32];
        in.readFully(h);
        return h;
    }
}
