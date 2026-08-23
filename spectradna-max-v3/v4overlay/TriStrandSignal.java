package com.vaan.spectradna;

/**
 * Alpha/Gamma-dominant experimental sonification of the exact compact archive.
 * The forbidden lane is deliberately omitted.
 *
 * Alpha and Gamma are full-length lanes. Theta and Delta are low-level
 * parity/sync support. The nominal 10/40/6/2 Hz rates are amplitude-modulation
 * labels for this digital sonification, not claims of a biological effect.
 */
public final class TriStrandSignal {
    private final byte[] data;
    private final int sampleRate;
    private final long symbolSamples;

    private final double[] alphaHz={432.0,528.0,741.0,963.0};
    private final double[] gammaHz={864.0,1056.0,1482.0,1926.0};
    private final double[] thetaHz={216.0,264.0,370.5,481.5};
    private final double[] deltaHz={108.0,132.0,185.25,240.75};

    private static final double ALPHA_WEIGHT=0.52;
    private static final double GAMMA_WEIGHT=0.42;
    private static final double THETA_WEIGHT=0.04;
    private static final double DELTA_WEIGHT=0.02;

    public TriStrandSignal(byte[] data,int sampleRate){
        this.data=data==null?new byte[0]:data;
        this.sampleRate=Math.max(8000,sampleRate);
        this.symbolSamples=Math.max(1,Math.round(this.sampleRate*0.020));
    }

    public double sample(long frame,boolean right){
        if(data.length==0)return 0;
        long symbol=frame/symbolSamples;
        int a=baseAt(symbol);
        int g=(a+2)&3;

        long byteBase=(symbol/4L)*4L;
        int theta=baseAt(byteBase)^baseAt(byteBase+1)^baseAt(byteBase+2)^baseAt(byteBase+3);
        int delta=(int)(Math.floorMod(symbol/32L,4L));

        double t=frame/(double)sampleRate;
        double local=(frame%symbolSamples)/(double)symbolSamples;
        double gate=0.5-0.5*Math.cos(2*Math.PI*local);
        double stereo=right?Math.PI/3.0:0.0;

        double alphaEnv=0.82+0.18*Math.sin(2*Math.PI*10.0*t);
        double gammaEnv=0.82+0.18*Math.sin(2*Math.PI*40.0*t+Math.PI/7.0);
        double thetaEnv=0.35+0.15*Math.sin(2*Math.PI*6.0*t+Math.PI/5.0);
        double deltaEnv=0.30+0.10*Math.sin(2*Math.PI*2.0*t+Math.PI/9.0);

        double s=
            ALPHA_WEIGHT*alphaEnv*Math.sin(2*Math.PI*alphaHz[a]*t+stereo)+
            GAMMA_WEIGHT*gammaEnv*Math.sin(2*Math.PI*gammaHz[g]*t-stereo*0.70)+
            THETA_WEIGHT*thetaEnv*Math.sin(2*Math.PI*thetaHz[theta&3]*t+stereo*0.35)+
            DELTA_WEIGHT*deltaEnv*Math.sin(2*Math.PI*deltaHz[delta]*t-stereo*0.20);
        return s*gate;
    }

    public static String mixDescription(){
        return "Alpha 52% + Gamma 42% + Theta 4% + Delta 2%";
    }

    private int baseAt(long symbol){
        long total=(long)data.length*4L;
        if(total<=0)return 0;
        long q=Math.floorMod(symbol,total);
        int byteIndex=(int)(q/4);
        int pair=(int)(q%4);
        int shift=6-pair*2;
        return((data[byteIndex]&255)>>>shift)&3;
    }
}
