package com.vaan.spectradna;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic experimental genome-style representation with the forbidden lane omitted.
 *
 * Alpha is the primary exact 2-bit A/C/G/T projection.
 * Gamma is a second full-length independently reversible projection.
 * Theta is smaller parity support.
 * Delta is sparse sync support.
 *
 * The naming describes digital/sonification lanes, not established biological effects.
 */
public final class TriStrandCodec {
    private TriStrandCodec() {}
    private static final char[] BASES = {'A','C','G','T'};
    private static final int WRAP = 120;

    public static final class Result {
        public final long sourceBytes,binaryBits;
        public final String binaryPreview,alpha,gamma,theta,delta,fasta;
        Result(long sourceBytes,long binaryBits,String binaryPreview,String alpha,String gamma,String theta,String delta,String fasta){
            this.sourceBytes=sourceBytes;this.binaryBits=binaryBits;this.binaryPreview=binaryPreview;
            this.alpha=alpha;this.gamma=gamma;this.theta=theta;this.delta=delta;this.fasta=fasta;
        }
    }

    public static String previewText(byte[] data){
        if(data==null)data=new byte[0];
        int showBytes=Math.min(data.length,256);
        StringBuilder bits=new StringBuilder(showBytes*9+256);
        StringBuilder alpha=new StringBuilder(showBytes*4+64);
        StringBuilder gamma=new StringBuilder(showBytes*4+64);
        StringBuilder theta=new StringBuilder(showBytes+64);
        StringBuilder delta=new StringBuilder(showBytes/2+64);

        int alphaSymbols=0;
        for(int i=0;i<showBytes;i++){
            int v=data[i]&255;
            int parity=0;
            for(int sh=7;sh>=0;sh--)bits.append(((v>>>sh)&1)==0?'0':'1');
            bits.append(' ');
            for(int sh=6;sh>=0;sh-=2){
                int q=(v>>>sh)&3;
                alpha.append(BASES[q]);
                gamma.append(BASES[gammaValue(q)]);
                parity^=q;
                alphaSymbols++;
                if((alphaSymbols&31)==0)delta.append("ACGT");
            }
            theta.append(BASES[parity&3]);
        }

        return "EXACT ARCHIVE BINARY\n"+bits+
            "\n\nALPHA + GAMMA DOMINANT GENOME CODE\n"+
            "Alpha/Primary exact:  "+alpha+
            "\nGamma/Full redundant:  "+gamma+
            "\nTheta/Parity support:  "+theta+
            "\nDelta/Sync support:  "+delta+
            "\n\nFull archive: "+data.length+" bytes = "+(data.length*8L)+" bits"+
            "\nAlpha full length: "+(data.length*4L)+" bases"+
            "\nGamma full length: "+(data.length*4L)+" bases"+
            "\nTheta support length: "+data.length+" bases"+
            "\nDelta is sparse sync support."+
            "\n\nA=00  C=01  G=10  T=11"+
            "\nAlpha and Gamma are the dominant sonified lanes."+
            "\nTheta and Delta are subordinate parity/sync support only."+
            "\nFull code is generated deterministically from the exact archive; storing a duplicate is optional."+
            "\n[Preview shows first "+showBytes+" bytes.]";
    }

    public static Result encode(byte[] data) {
        if(data==null)data=new byte[0];
        StringBuilder a=new StringBuilder(data.length*4);
        StringBuilder g=new StringBuilder(data.length*4);
        StringBuilder t=new StringBuilder(data.length);
        StringBuilder d=new StringBuilder(Math.max(16,data.length/2));
        StringBuilder preview=new StringBuilder(Math.min(4096,data.length*8)+128);
        int previewBits=0,alphaSymbols=0;

        for(byte raw:data){
            int v=raw&255,parity=0;
            for(int shift=6;shift>=0;shift-=2){
                int q=(v>>>shift)&3;
                a.append(BASES[q]);
                g.append(BASES[gammaValue(q)]);
                parity^=q;
                alphaSymbols++;
                if((alphaSymbols&31)==0)d.append("ACGT");
            }
            t.append(BASES[parity&3]);

            if(previewBits<4096){
                for(int shift=7;shift>=0&&previewBits<4096;shift--){
                    preview.append(((v>>>shift)&1)==0?'0':'1');
                    previewBits++;
                    if((previewBits&7)==0)preview.append(' ');
                }
            }
        }

        String alpha=a.toString(),gamma=g.toString(),theta=t.toString(),delta=d.toString();
        StringBuilder fasta=new StringBuilder(alpha.length()+gamma.length()+theta.length()+delta.length()+768);
        fasta.append(">SpectraDNA-Forge-v4|ALPHA_PRIMARY_EXACT|bases=").append(alpha.length()).append('\n');wrap(fasta,alpha,WRAP);
        fasta.append(">SpectraDNA-Forge-v4|GAMMA_REDUNDANT_FULL|transform=plus2mod4|bases=").append(gamma.length()).append('\n');wrap(fasta,gamma,WRAP);
        fasta.append(">SpectraDNA-Forge-v4|THETA_PARITY_SUPPORT|xor_per_byte|bases=").append(theta.length()).append('\n');wrap(fasta,theta,WRAP);
        fasta.append(">SpectraDNA-Forge-v4|DELTA_SYNC_SUPPORT|ACGT_every_32_alpha_bases|bases=").append(delta.length()).append('\n');wrap(fasta,delta,WRAP);

        if(data.length*8L>4096)preview.append("… [preview first 4096 of ").append(data.length*8L).append(" bits]");
        return new Result(data.length,data.length*8L,preview.toString(),alpha,gamma,theta,delta,fasta.toString());
    }

    /**
     * Streaming export avoids building a multi-megabyte duplicate genome string in memory.
     * The exact archive remains the source of truth and every emitted base is deterministic.
     */
    public static void writeFasta(byte[] data,OutputStream out)throws IOException{
        if(out==null)throw new IOException("Missing output stream");
        if(data==null)data=new byte[0];
        Writer w=new BufferedWriter(new OutputStreamWriter(out,StandardCharsets.US_ASCII),64*1024);

        long alphaLen=data.length*4L;
        long gammaLen=alphaLen;
        long thetaLen=data.length;
        long deltaLen=(alphaLen/32L)*4L;

        w.write(">SpectraDNA-Forge-v4|ALPHA_PRIMARY_EXACT|bases="+alphaLen+"\n");
        LineWriter lw=new LineWriter(w,WRAP);
        for(byte raw:data){int v=raw&255;for(int sh=6;sh>=0;sh-=2)lw.put(BASES[(v>>>sh)&3]);}
        lw.finish();

        w.write(">SpectraDNA-Forge-v4|GAMMA_REDUNDANT_FULL|transform=plus2mod4|bases="+gammaLen+"\n");
        lw=new LineWriter(w,WRAP);
        for(byte raw:data){int v=raw&255;for(int sh=6;sh>=0;sh-=2)lw.put(BASES[gammaValue((v>>>sh)&3)]);}
        lw.finish();

        w.write(">SpectraDNA-Forge-v4|THETA_PARITY_SUPPORT|xor_per_byte|bases="+thetaLen+"\n");
        lw=new LineWriter(w,WRAP);
        for(byte raw:data){int v=raw&255,p=0;for(int sh=6;sh>=0;sh-=2)p^=(v>>>sh)&3;lw.put(BASES[p&3]);}
        lw.finish();

        w.write(">SpectraDNA-Forge-v4|DELTA_SYNC_SUPPORT|ACGT_every_32_alpha_bases|bases="+deltaLen+"\n");
        lw=new LineWriter(w,WRAP);
        long groups=alphaLen/32L;
        for(long i=0;i<groups;i++){lw.put('A');lw.put('C');lw.put('G');lw.put('T');}
        lw.finish();
        w.flush();
    }

    public static String summary(Result r){
        return "Binary: "+r.binaryBits+" bits\n"+
            "Alpha primary exact: "+r.alpha.length()+" bases\n"+
            "Gamma full redundant: "+r.gamma.length()+" bases\n"+
            "Theta parity support: "+r.theta.length()+" bases\n"+
            "Delta sync support: "+r.delta.length()+" bases";
    }

    private static int gammaValue(int alpha){return(alpha+2)&3;}
    private static void wrap(StringBuilder out,String s,int width){for(int i=0;i<s.length();i+=width)out.append(s,i,Math.min(s.length(),i+width)).append('\n');}

    private static final class LineWriter{
        final Writer out;final int width;int col=0;
        LineWriter(Writer out,int width){this.out=out;this.width=width;}
        void put(char c)throws IOException{out.write(c);if(++col>=width){out.write('\n');col=0;}}
        void finish()throws IOException{if(col!=0)out.write('\n');col=0;}
    }
}
