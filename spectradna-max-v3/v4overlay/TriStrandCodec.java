package com.vaan.spectradna;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic experimental three-strand DNA-style representation.
 * Alpha stores the 2-bit A/C/G/T projection of the exact compressed archive.
 * Beta stores its Watson-Crick complement as an antiparallel redundant view.
 * Gamma stores one 2-bit parity base per Alpha triplet plus periodic sync bases.
 */
public final class TriStrandCodec {
    private TriStrandCodec() {}
    private static final char[] BASES = {'A','C','G','T'};

    public static final class Result {
        public final long sourceBytes,binaryBits;
        public final String binaryPreview,alpha,beta,gamma,fasta;
        Result(long sourceBytes,long binaryBits,String binaryPreview,String alpha,String beta,String gamma,String fasta){this.sourceBytes=sourceBytes;this.binaryBits=binaryBits;this.binaryPreview=binaryPreview;this.alpha=alpha;this.beta=beta;this.gamma=gamma;this.fasta=fasta;}
    }

    public static String previewText(byte[] data){
        if(data==null)data=new byte[0];
        int showBytes=Math.min(data.length,256);
        StringBuilder bits=new StringBuilder(showBytes*9+256),bases=new StringBuilder(showBytes*4+64),beta=new StringBuilder(showBytes*4+64),gamma=new StringBuilder(showBytes*2+64);
        for(int i=0;i<showBytes;i++){
            int v=data[i]&255;
            for(int sh=7;sh>=0;sh--)bits.append(((v>>>sh)&1)==0?'0':'1');
            bits.append(' ');
            for(int sh=6;sh>=0;sh-=2){char b=BASES[(v>>>sh)&3];bases.append(b);beta.append(complement(b));}
        }
        for(int i=0;i<bases.length();i+=3){int p=0;for(int j=i;j<Math.min(bases.length(),i+3);j++)p^=baseValue(bases.charAt(j));gamma.append(BASES[p&3]);}
        return "EXACT ARCHIVE BINARY\n"+bits+"\n\nTRI-STRAND SIGNAL CODE\nAlpha/Data:  "+bases+"\nBeta/Complement:  "+beta.reverse()+"\nGamma/Parity:  "+gamma+"\n\nFull archive: "+data.length+" bytes = "+(data.length*8L)+" bits\nAlpha full length: "+(data.length*4L)+" bases\nBeta full length: "+(data.length*4L)+" bases\nGamma is parity/sync redundancy.\n\nA=00  C=01  G=10  T=11\nThe three lanes are also sonified in the generated COMPACT EXACT audio.\n[Preview shows first "+showBytes+" bytes.]";
    }

    public static Result encode(byte[] data) {
        if(data==null)data=new byte[0];
        StringBuilder a=new StringBuilder(data.length*4),b=new StringBuilder(data.length*4),preview=new StringBuilder(Math.min(4096,data.length*8)+128);
        int previewBits=0;
        for(byte raw:data){int v=raw&255;for(int shift=6;shift>=0;shift-=2){int q=(v>>>shift)&3;char base=BASES[q];a.append(base);b.append(complement(base));}if(previewBits<4096){for(int shift=7;shift>=0&&previewBits<4096;shift--){preview.append(((v>>>shift)&1)==0?'0':'1');previewBits++;if((previewBits&7)==0)preview.append(' ');}}}
        String alpha=a.toString(),beta=b.reverse().toString();
        StringBuilder g=new StringBuilder(alpha.length()/3+alpha.length()/96+32);int syncCount=0;
        for(int i=0;i<alpha.length();i+=3){int p=0,end=Math.min(alpha.length(),i+3);for(int j=i;j<end;j++)p^=baseValue(alpha.charAt(j));g.append(BASES[p&3]);syncCount++;if(syncCount==32){g.append("ACGT");syncCount=0;}}
        String gamma=g.toString();
        StringBuilder fasta=new StringBuilder(alpha.length()+beta.length()+gamma.length()+512);
        fasta.append(">SpectraDNA-Forge-v4|ALPHA_DATA|bases=").append(alpha.length()).append('\n');wrap(fasta,alpha,120);
        fasta.append(">SpectraDNA-Forge-v4|BETA_COMPLEMENT_ANTIPARALLEL|bases=").append(beta.length()).append('\n');wrap(fasta,beta,120);
        fasta.append(">SpectraDNA-Forge-v4|GAMMA_PARITY_SYNC|bases=").append(gamma.length()).append('\n');wrap(fasta,gamma,120);
        if(data.length*8L>4096)preview.append("… [preview first 4096 of ").append(data.length*8L).append(" bits]");
        return new Result(data.length,data.length*8L,preview.toString(),alpha,beta,gamma,fasta.toString());
    }

    public static void writeFasta(byte[] data,OutputStream out)throws IOException{if(out==null)throw new IOException("Missing output stream");Result r=encode(data);out.write(r.fasta.getBytes(StandardCharsets.US_ASCII));}
    public static String summary(Result r){return "Binary: "+r.binaryBits+" bits\nAlpha data: "+r.alpha.length()+" bases\nBeta complement: "+r.beta.length()+" bases\nGamma parity/sync: "+r.gamma.length()+" bases";}
    private static char complement(char c){switch(c){case 'A':return 'T';case 'C':return 'G';case 'G':return 'C';default:return 'A';}}
    private static int baseValue(char c){switch(c){case 'A':return 0;case 'C':return 1;case 'G':return 2;default:return 3;}}
    private static void wrap(StringBuilder out,String s,int width){for(int i=0;i<s.length();i+=width)out.append(s,i,Math.min(s.length(),i+width)).append('\n');}
}
