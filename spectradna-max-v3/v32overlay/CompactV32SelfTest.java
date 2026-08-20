package com.vaan.spectradna;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Pure-Java regression test for the v3.2 compact path. */
public final class CompactV32SelfTest {
    public static void main(String[] args) throws Exception {
        CompactArchive a = new CompactArchive();
        a.fileName = "exact-test.png";
        a.mime = "image/png";
        a.colorSpace = "sRGB";
        a.width = 2;
        a.height = 2;
        a.tileSize = 2;
        a.originalBytes = "EXACT-SOURCE-BYTES-0123456789".getBytes(StandardCharsets.US_ASCII);
        a.originalSha = ByteUtil.sha256(a.originalBytes);
        a.canonicalSha = a.originalSha.clone();
        a.pixelSha = ByteUtil.sha256(new byte[]{1,2,3,4,5,6,7,8});
        a.tileHashes = new byte[][]{ByteUtil.sha256(new byte[]{9,8,7,6})};
        a.merkleRoot = a.tileHashes[0].clone();

        byte[] raw = a.serialize();
        byte[] packed = CompactArchive.compress(raw);
        CompactArchive round = CompactArchive.parse(CompactArchive.inflate(packed));
        require(Arrays.equals(a.originalBytes, round.originalBytes), "compact archive source mismatch");
        require(Arrays.equals(a.originalSha, round.originalSha), "compact archive SHA mismatch");

        short[] art = new short[4096];
        for (int i=0;i<art.length;i++) art[i]=(short)Math.round(Math.sin(i*0.071)*7000.0);
        ByteArrayOutputStream wav = new ByteArrayOutputStream(4*1024*1024);
        WavVault.writeCompactProfile(wav, art, packed, WavProfile.PHONE_48, 0, null);
        byte[] wavBytes = wav.toByteArray();

        long expectedPcm = Math.round(18.0 * 48000.0) * 4L;
        require(wavBytes.length >= 44L + expectedPcm + packed.length, "compact WAV unexpectedly small/truncated");
        require(wavBytes.length < 4_000_000, "compact WAV exceeded 4 MB regression ceiling for tiny payload: "+wavBytes.length);

        byte[] recovered = WavVault.readEmbeddedArchive(new ByteArrayInputStream(wavBytes));
        require(Arrays.equals(packed, recovered), "embedded sDNA archive mismatch");
        CompactArchive finalArchive = CompactArchive.parse(CompactArchive.inflate(recovered));
        require(Arrays.equals(a.originalBytes, finalArchive.originalBytes), "WAV exact source round-trip failed");

        // Corrupt a byte inside the sDNA archive. SHA/CRC validation must reject it.
        byte[] damaged = wavBytes.clone();
        int pos = indexOf(damaged, new byte[]{'s','D','N','A'});
        require(pos > 44, "sDNA RIFF chunk not found");
        int payloadStart = pos + 8;
        int archiveStart = payloadStart + 48;
        require(archiveStart + 4 < damaged.length, "sDNA archive missing");
        damaged[archiveStart + 3] ^= 0x55;
        boolean rejected = false;
        try { WavVault.readEmbeddedArchive(new ByteArrayInputStream(damaged)); }
        catch (IOException expected) { rejected = true; }
        require(rejected, "corrupted sDNA payload was not rejected");

        System.out.println("SpectraDNA v3.2 COMPACT EXACT self-test: PASS");
        System.out.println("18 s 48 kHz compact test WAV bytes: "+wavBytes.length);
        System.out.println("Exact embedded archive bytes: "+packed.length);
    }

    private static int indexOf(byte[] hay, byte[] needle){
        outer: for(int i=0;i<=hay.length-needle.length;i++){
            for(int j=0;j<needle.length;j++)if(hay[i+j]!=needle[j])continue outer;
            return i;
        }
        return -1;
    }

    private static void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
}
