package com.vaan.spectradna;

/**
 * Three-lane experimental sonification of the compact archive.
 * Alpha=data bases, Beta=complement bases, Gamma=parity/sync bases.
 */
public final class TriStrandSignal {
    private final byte[] data;
    private final int sampleRate;
    private final long symbolSamples;
    private final double[] alphaHz={432.0,528.0,741.0,963.0};
    private final double[] betaHz ={648.0,792.0,1111.5,1444.5};
    private final double[] gammaHz={216.0,264.0,370.5,481.5};

    public TriStrandSignal(byte[] data,int sampleRate){this.data=data==null?new byte[0]:data;this.sampleRate=Math.max(8000,sampleRate);this.symbolSamples=Math.max(1,Math.round(this.sampleRate*0.040));}

    public double sample(long frame,boolean right){
        if(data.length==0)return 0;
        long symbol=frame/symbolSamples;
        int a=baseAt(symbol);
        int b=3-a;
        int p=baseAt(symbol*3)^baseAt(symbol*3+1)^baseAt(symbol*3+2);
        double t=frame/(double)sampleRate;
        double gate=0.5-0.5*Math.cos(2*Math.PI*((frame%symbolSamples)/(double)symbolSamples));
        double phase=right?Math.PI/3.0:0.0;
        double s=0.48*Math.sin(2*Math.PI*alphaHz[a]*t+phase)+0.32*Math.sin(2*Math.PI*betaHz[b]*t-phase)+0.20*Math.sin(2*Math.PI*gammaHz[p&3]*t+phase*0.5);
        return s*gate;
    }

    private int baseAt(long symbol){long total=(long)data.length*4L;if(total<=0)return 0;long q=Math.floorMod(symbol,total);int byteIndex=(int)(q/4);int pair=(int)(q%4);int shift=6-pair*2;return((data[byteIndex]&255)>>>shift)&3;}
}
