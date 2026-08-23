package com.vaan.spectradna;

import java.io.*;
import java.util.*;

public final class ForgeV4SelfTest {
    public static void main(String[] args)throws Exception{
        byte[] data=new byte[4096];new Random(0x53444634L).nextBytes(data);
        TriStrandCodec.Result r=TriStrandCodec.encode(data);
        require(r.binaryBits==data.length*8L,"binary length");
        require(r.alpha.length()==data.length*4,"alpha length");
        require(r.gamma.length()==r.alpha.length(),"gamma full redundant length");
        require(r.theta.length()==data.length,"theta support length");
        require(r.delta.length()>0&&r.delta.length()<r.theta.length(),"delta sparse support");
        require(r.theta.length()<r.alpha.length()&&r.delta.length()<r.alpha.length(),"support lanes subordinate");
        require(r.fasta.contains("ALPHA_PRIMARY_EXACT")&&r.fasta.contains("GAMMA_REDUNDANT_FULL")&&r.fasta.contains("THETA_PARITY_SUPPORT")&&r.fasta.contains("DELTA_SYNC_SUPPORT"),"FASTA headers");
        require(!r.fasta.toUpperCase(Locale.ROOT).contains("BE"+"TA"),"forbidden lane absent from FASTA");
        String preview=TriStrandCodec.previewText(data);
        require(preview.contains("A=00  C=01  G=10  T=11"),"preview mapping");
        require(!preview.toUpperCase(Locale.ROOT).contains("BE"+"TA"),"forbidden lane absent from preview");

        ByteArrayOutputStream out=new ByteArrayOutputStream();
        TriStrandCodec.writeFasta(data,out);
        String streamed=out.toString("US-ASCII");
        require(streamed.contains("ALPHA_PRIMARY_EXACT")&&streamed.contains("GAMMA_REDUNDANT_FULL"),"streamed export");
        require(!streamed.toUpperCase(Locale.ROOT).contains("BE"+"TA"),"forbidden lane absent from streamed export");

        TriStrandSignal sig=new TriStrandSignal(data,48000);
        require(!TriStrandSignal.mixDescription().toUpperCase(Locale.ROOT).contains("BE"+"TA"),"forbidden lane absent from signal description");
        double energy=0;for(int i=0;i<48000;i+=37){double v=sig.sample(i,false);require(Double.isFinite(v),"finite signal");energy+=v*v;}
        require(energy>0.01,"genome signal energy");

        System.out.println("SpectraDNA Forge v4 Alpha/Gamma genome self-test: PASS");
        System.out.println("Alpha="+r.alpha.length()+" Gamma="+r.gamma.length()+" Theta="+r.theta.length()+" Delta="+r.delta.length()+" FASTA="+out.size());
    }
    private static void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
}
