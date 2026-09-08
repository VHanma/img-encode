package org.vhanma.dnaforgemax;

/**
 * Tiny on-device unsupervised anomaly model.
 * Learns the clean baseline of Hunter feature vectors and scores multivariate novelty.
 * Suspect/trigger-adjacent frames are quarantined from learning to avoid baseline contamination.
 */
final class V10AdaptiveAI {
    static final class Decision {
        final float score,novelty; final int tier; final boolean learned;
        Decision(float score,float novelty,int tier,boolean learned){this.score=score;this.novelty=novelty;this.tier=tier;this.learned=learned;}
    }
    private final float[]mean=new float[7],var=new float[7];
    private int count=0,quarantine=0;

    synchronized Decision evaluate(V10HunterEngine.Result r,float threshold){
        float[]f={r.motion,r.structure,r.lumaSpike,r.color,r.code,r.bent,r.sensorMismatch};
        float novelty=0;
        if(count>=20){
            float[]z=new float[f.length];
            for(int i=0;i<f.length;i++){float sd=(float)Math.sqrt(Math.max(.0025f,var[i]));z[i]=clamp01(Math.abs(f[i]-mean[i])/(sd*3.2f));}
            java.util.Arrays.sort(z);novelty=clamp01(z[6]*.36f+z[5]*.25f+z[4]*.16f+(z[0]+z[1]+z[2]+z[3])*.0575f);
        }
        float score=clamp01(r.score+novelty*.22f);
        int tier=r.tier;float t=clamp(threshold,.20f,.92f);
        if(tier<1&&score>=t)tier=1;
        if(tier<2&&score>=Math.min(.94f,t+.08f))tier=2;
        if(tier<3&&score>=Math.min(.96f,t+.16f)&&r.persistence>=2)tier=3;
        if(tier<4&&score>=Math.min(.98f,t+.24f)&&r.persistence>=3)tier=4;

        if(tier>0||r.score>=t*.82f||novelty>.58f)quarantine=Math.max(quarantine,12);
        boolean learn=false;
        if(quarantine>0)quarantine--;
        else if(r.tier==0&&r.score<t*.72f){update(f);learn=true;}
        if(count<20&&r.score<t*.82f){update(f);learn=true;}
        return new Decision(score,novelty,tier,learn);
    }

    synchronized void reset(){java.util.Arrays.fill(mean,0);java.util.Arrays.fill(var,0);count=0;quarantine=0;}
    synchronized int baselineFrames(){return count;}

    private void update(float[]f){
        count++;float a=count<30?1f/count:.025f;
        for(int i=0;i<f.length;i++){float d=f[i]-mean[i];mean[i]+=a*d;var[i]=(1-a)*var[i]+a*d*d;}
    }
    private static float clamp01(float v){return v<0?0:v>1?1:v;}private static float clamp(float v,float lo,float hi){return v<lo?lo:v>hi?hi:v;}
}
