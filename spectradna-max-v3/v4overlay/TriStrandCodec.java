package com.vaan.spectradna;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic experimental three-strand DNA-style representation.
 *
 * Alpha stores the 2-bit A/C/G/T projection of the exact compressed archive.
 * Beta stores its Watson-Crick complement for a second redundant view.
 * Gamma stores one 2-bit parity base per Alpha triplet plus periodic sync bases.
 * This is a digital information code and does not claim a biological molecule
 * literally decodes phone audio.
 */
public final class TriStrandCodec {
    private TriStrandCodec() {}

    private static final char[] BASES = {'A','C','G','T'};

    public static final class Result {
        public final long sourceBytes;
        public final long binaryBits;
        public final String binaryPreview;
        public final String alpha;
        public final String beta;
        public final String gamma;
        public final String fasta;
        Result(long sourceBytes,long binaryBits,String binaryPreview,String alpha,String beta,String gamma,String fasta){
            this.sourceBytes=sourceBytes;this.binaryBits=binaryBits;this.binaryPreview=binaryPreview;this.alpha=alpha;this.beta=beta;this.gamma=gamma;this.fasta=fasta;
        }
    }

    public static Result encode(byte[] data) {
        if(data==null)data=new byte[0];
        StringBuilder a=new StringBuilder(data.length*4);
        StringBuilder b=new StringBuilder(data.length*4);
        StringBuilder preview=new StringBuilder(Math.min(4096,data.length*8)+128);
        int previewBits=0;
        for(byte raw:data){
            int v=raw&255;
            for(int shift=6;shift>=0;shift-=2){
                int q=(v>>>shift)&3;
                char base=BASES[q];
                a.append(base);
                b.append(complement(base));
            }
            if(previewBits<4096){
                for(int shift=7;shift>=0&&previewBits<4096;shift--){
                    preview.append(((v>>>shift)&1)==0?'0':'1');
                    previewBits++;
                    if((previewBits&7)==0)preview.append(' ');
                }
            }
        }

        String alpha=a.toString();
        String beta=b.reverse().toString(); // antiparallel display convention
        StringBuilder g=new StringBuilder(alpha.length()/3+alpha.length()/96+32);
        int syncCount=0;
        for(int i=0;i<alpha.length();i+=3){
            int p=0;
            int end=Math.min(alpha.length(),i+3);
            for(int j=i;j<end;j++)p^=baseValue(alpha.charAt(j));
            g.append(BASES[p&3]);
            syncCount++;
            if(syncCount==32){g.append("ACGT");syncCount=0;}
        }
        String gamma=g.toString();

        StringBuilder fasta=new StringBuilder(alpha.length()+beta.length()+gamma.length()+512);
        fasta.append(">SpectraDNA-Forge-v4|ALPHA_DATA|bases=").append(alpha.length()).append('\n');
        wrap(fasta,alpha,120);
        fasta.append(">SpectraDNA-Forge-v4|BETA_COMPLEMENT_ANTIPARALLEL|bases=").append(beta.length()).append('\n');
        wrap(fasta,beta,120);
        fasta.append(">SpectraDNA-Forge-v4|GAMMA_PARITY_SYNC|bases=").append(gamma.length()).append('\n');
        wrap(fasta,gamma,120);

        if(data.length*8L>4096)preview.append("… [preview first 4096 of ").append(data.length*8L).append(" bits]");
        return new Result(data.length,data.length*8L,preview.toString(),alpha,beta,gamma,fasta.toString());
    }

    public static void writeFasta(byte[] data,OutputStream out)throws IOException{
        if(out==null)throw new IOException("Missing output stream");
        Result r=encode(data);
        out.write(r.fasta.getBytes(StandardCharsets.US_ASCII));
    }

    public static String summary(Result r){
        return "Binary: "+r.binaryBits+" bits\n"+
               "Alpha data: "+r.alpha.length()+" bases\n"+
               "Beta complement: "+r.beta.length()+" bases\n"+
               "Gamma parity/sync: "+r.gamma.length()+" bases";
    }

    private static char complement(char c){switch(c){case 'A':return 'T';case 'C':return 'G';case 'G':return 'C';default:return 'A';}}
    private static int baseValue(char c){switch(c){case 'A':return 0;case 'C':return 1;case 'G':return 2;default:return 3;}}
    private static void wrap(StringBuilder out,String s,int width){for(int i=0;i<s.length();i+=width)out.append(s,i,Math.min(s.length(),i+width)).append('\n');}
}
