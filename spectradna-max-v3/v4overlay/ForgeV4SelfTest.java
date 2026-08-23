package com.vaan.spectradna;

import java.io.*;
import java.util.*;

public final class ForgeV4SelfTest {
    public static void main(String[] args)throws Exception{
        byte[] data=new byte[4096];new Random(0x53444634L).nextBytes(data);
        TriStrandCodec.Result r=TriStrandCodec.encode(data);
        require(r.binaryBits==data.length*8L,"binary length");
        require(r.alpha.length()==data.length*4,"alpha length");
        require(r.beta.length()==r.alpha.length(),"beta length");
        require(r.gamma.length()>r.alpha.length()/3,"gamma parity/sync length");
        require(r.fasta.contains("ALPHA_DATA")&&r.fasta.contains("BETA_COMPLEMENT")&&r.fasta.contains("GAMMA_PARITY"),"FASTA headers");
        require(TriStrandCodec.previewText(data).contains("A=00  C=01  G=10  T=11"),"preview mapping");
        ByteArrayOutputStream out=new ByteArrayOutputStream();TriStrandCodec.writeFasta(data,out);require(out.size()>data.length,"fasta export");
        TriStrandSignal sig=new TriStrandSignal(data,48000);double energy=0;for(int i=0;i<48000;i+=37){double v=sig.sample(i,false);require(Double.isFinite(v),"finite signal");energy+=v*v;}require(energy>0.01,"tri-strand signal energy");
        System.out.println("SpectraDNA Forge v4 tri-strand self-test: PASS");
        System.out.println("Alpha="+r.alpha.length()+" Beta="+r.beta.length()+" Gamma="+r.gamma.length()+" FASTA="+out.size());
    }
    private static void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
}
