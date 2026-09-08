package org.vhanma.dnaforgemax;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

/**
 * Hunter Ω v10 analysis core.
 * Fast, deterministic, low-resolution analysis for anomaly triggering and filter selection.
 * DMT Code and Bent Light are experimental visualization/anomaly heuristics; their scores
 * describe image-pattern behavior, not a claim about cause.
 */
final class V10HunterEngine {
    enum Filter { NORMAL, HIGH_CONTRAST, EDGE, MOTION, LOW_LIGHT, CHROMA, DMT_CODE, BENT_LIGHT }

    static final class State {
        float[] prevLum,prevGrad;
        float prevAvgLum;
        RectF lastRegion;
        int persistence;
        long frameIndex;
        final V10AdaptiveAI ai=new V10AdaptiveAI();
        void reset(){prevLum=null;prevGrad=null;prevAvgLum=0;lastRegion=null;persistence=0;frameIndex=0;ai.reset();}
    }

    static final class Result {
        final long frame;
        final Filter filter;
        final Bitmap filtered;
        final RectF region;
        final int tier;
        final float score,motion,structure,lumaSpike,color,code,bent,sensorMismatch,rectanglePenalty;
        final float[] filterScores,tileScores;
        final int tileCols,tileRows,persistence;
        Result(long frame,Filter filter,Bitmap filtered,RectF region,int tier,float score,float motion,
               float structure,float lumaSpike,float color,float code,float bent,float sensorMismatch,
               float rectanglePenalty,float[]filterScores,float[]tileScores,int tc,int tr,int persistence){
            this.frame=frame;this.filter=filter;this.filtered=filtered;this.region=region;this.tier=tier;
            this.score=score;this.motion=motion;this.structure=structure;this.lumaSpike=lumaSpike;
            this.color=color;this.code=code;this.bent=bent;this.sensorMismatch=sensorMismatch;
            this.rectanglePenalty=rectanglePenalty;this.filterScores=filterScores;this.tileScores=tileScores;
            this.tileCols=tc;this.tileRows=tr;this.persistence=persistence;
        }
    }

    private static final int W=192,H=144,TC=8,TR=6;

    static Result analyze(Bitmap source, State state, V10SensorFusion.Snapshot sensor,
                          Filter requested, boolean autoFilter, float threshold){
        if(source==null) return empty(state);
        Bitmap b=Bitmap.createScaledBitmap(source,W,H,true);
        int[]px=new int[W*H]; b.getPixels(px,0,W,0,0,W,H);
        float[]lum=new float[px.length],sat=new float[px.length],hue=new float[px.length],grad=new float[px.length];
        float[]hsv=new float[3]; float avg=0,avgSat=0;
        for(int i=0;i<px.length;i++){
            int c=px[i];float l=(.2126f*Color.red(c)+.7152f*Color.green(c)+.0722f*Color.blue(c))/255f;
            lum[i]=l;avg+=l;Color.colorToHSV(c,hsv);sat[i]=hsv[1];hue[i]=hsv[0]/360f;avgSat+=sat[i];
        }
        avg/=px.length;avgSat/=px.length;
        float edgeSum=0,microSum=0,alternate=0;
        for(int y=1;y<H-1;y++)for(int x=1;x<W-1;x++){
            int i=y*W+x;float gx=(lum[i+1]-lum[i-1])*.5f,gy=(lum[i+W]-lum[i-W])*.5f;
            float g=(float)Math.sqrt(gx*gx+gy*gy);grad[i]=g;edgeSum+=g;
            float lap=Math.abs(lum[i-1]+lum[i+1]+lum[i-W]+lum[i+W]-4*lum[i]);microSum+=lap;
            float a=(lum[i]-lum[i-1])*(lum[i+1]-lum[i]);float d=(lum[i]-lum[i-W])*(lum[i+W]-lum[i]);
            if(a<-.0018f||d<-.0018f)alternate++;
        }
        int inner=(W-2)*(H-2);float edge=clamp01(edgeSum/(inner*.11f));float micro=clamp01(microSum/(inner*.19f));
        float pattern=clamp01(alternate/(inner*.18f));

        float motionSum=0,gradChange=0;
        if(state.prevLum!=null&&state.prevLum.length==lum.length){
            for(int i=0;i<lum.length;i++){motionSum+=Math.abs(lum[i]-state.prevLum[i]);if(state.prevGrad!=null)gradChange+=Math.abs(grad[i]-state.prevGrad[i]);}
        }
        float motion=state.prevLum==null?0:clamp01(motionSum/(lum.length*.12f));
        float bendVisual=state.prevLum==null?0:clamp01(gradChange/(lum.length*.055f));
        float lumaSpike=state.prevLum==null?0:clamp01(Math.abs(avg-state.prevAvgLum)*6f);

        float colorVar=0,colorEdge=0;int colorN=0;
        for(int y=1;y<H-1;y+=2)for(int x=1;x<W-1;x+=2){int i=y*W+x;colorVar+=Math.abs(sat[i]-avgSat);colorEdge+=hueDistance(hue[i],hue[i+1])*sat[i];colorN++;}
        float colorScore=clamp01(colorVar/(Math.max(1,colorN)*.31f)+colorEdge/(Math.max(1,colorN)*.65f));

        float gyro=sensor==null?0:clamp01(sensor.gyro/1.8f);float accel=sensor==null?0:clamp01(sensor.accel/2.2f);
        float sensorMismatch=clamp01(Math.abs(motion-gyro)*.72f + (motion>.45f&&gyro<.12f?.28f:0) + (motion<.10f&&gyro>.45f?.18f:0) + accel*.06f);
        float code=clamp01(micro*.40f+pattern*.31f+edge*.18f+colorScore*.11f);
        float bent=clamp01(bendVisual*.52f+sensorMismatch*.28f+motion*.12f+edge*.08f);

        float[]tiles=new float[TC*TR];RectF region=scoreTiles(lum,grad,sat,state.prevLum,tiles);
        float tileMax=0;for(float v:tiles)if(v>tileMax)tileMax=v;
        float rectPenalty=rectanglePenalty(region);
        float base=tileMax*.30f+motion*.20f+edge*.15f+lumaSpike*.08f+colorScore*.07f+code*.08f+bent*.08f+sensorMismatch*.04f;
        base*=1f-.70f*rectPenalty;

        boolean same=state.lastRegion!=null&&region!=null&&centerDistance(state.lastRegion,region)<.20f;
        if(base>=threshold*.78f){state.persistence=same?Math.min(30,state.persistence+1):1;state.lastRegion=new RectF(region);}else state.persistence=Math.max(0,state.persistence-1);
        float persistenceBoost=clamp01(state.persistence/8f)*.13f;
        float score=clamp01(base+persistenceBoost);
        int tier=tier(score,threshold,state.persistence);

        float lowLight=clamp01((.58f-avg)*1.8f);
        float contrast=clamp01(edge*.55f+micro*.25f+lumaSpike*.20f);
        float[]fs=new float[Filter.values().length];
        fs[Filter.NORMAL.ordinal()]=.20f+edge*.18f;
        fs[Filter.HIGH_CONTRAST.ordinal()]=contrast;
        fs[Filter.EDGE.ordinal()]=clamp01(edge*.82f+micro*.18f);
        fs[Filter.MOTION.ordinal()]=clamp01(motion*.84f+sensorMismatch*.16f);
        fs[Filter.LOW_LIGHT.ordinal()]=lowLight;
        fs[Filter.CHROMA.ordinal()]=clamp01(colorScore*.76f+avgSat*.24f);
        fs[Filter.DMT_CODE.ordinal()]=code;
        fs[Filter.BENT_LIGHT.ordinal()]=bent;
        Filter chosen=requested==null?Filter.NORMAL:requested;
        if(autoFilter){int bi=0;for(int i=1;i<fs.length;i++)if(fs[i]>fs[bi]+.035f)bi=i;chosen=Filter.values()[bi];}
        Bitmap filtered=render(chosen,px,lum,grad,sat,hue,state.prevLum);

        state.prevLum=lum;state.prevGrad=grad;state.prevAvgLum=avg;long fi=state.frameIndex++;
        Result raw=new Result(fi,chosen,filtered,region,tier,score,motion,edge,lumaSpike,colorScore,code,bent,sensorMismatch,rectPenalty,fs,tiles,TC,TR,state.persistence);
        V10AdaptiveAI.Decision ai=state.ai.evaluate(raw,threshold);
        if(ai.score>raw.score+.001f||ai.tier>raw.tier){
            return new Result(fi,chosen,filtered,region,ai.tier,ai.score,motion,edge,lumaSpike,colorScore,code,bent,sensorMismatch,rectPenalty,fs,tiles,TC,TR,state.persistence);
        }
        return raw;
    }

    private static Result empty(State s){long f=s==null?0:s.frameIndex++;return new Result(f,Filter.NORMAL,null,new RectF(.25f,.25f,.75f,.75f),0,0,0,0,0,0,0,0,0,0,new float[Filter.values().length],new float[TC*TR],TC,TR,0);}

    private static RectF scoreTiles(float[]lum,float[]grad,float[]sat,float[]prev,float[]out){
        int tw=W/TC,th=H/TR;int best=0;float bestV=-1;
        for(int ty=0;ty<TR;ty++)for(int tx=0;tx<TC;tx++){
            float g=0,d=0,ch=0;int n=0;int x0=tx*tw,y0=ty*th,x1=tx==TC-1?W:(tx+1)*tw,y1=ty==TR-1?H:(ty+1)*th;
            for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){int i=y*W+x;g+=grad[i];ch+=sat[i];if(prev!=null)d+=Math.abs(lum[i]-prev[i]);n++;}
            float v=clamp01(g/(Math.max(1,n)*.10f))*.42f+clamp01(d/(Math.max(1,n)*.12f))*.44f+clamp01(ch/(Math.max(1,n)*.65f))*.14f;
            if(tx==0||ty==0||tx==TC-1||ty==TR-1)v*=.18f;
            int k=ty*TC+tx;out[k]=v;if(v>bestV){bestV=v;best=k;}
        }
        int bx=best%TC,by=best/TC;float cutoff=bestV*.62f;int minX=bx,maxX=bx,minY=by,maxY=by;
        for(int ty=Math.max(0,by-1);ty<=Math.min(TR-1,by+1);ty++)for(int tx=Math.max(0,bx-1);tx<=Math.min(TC-1,bx+1);tx++)if(out[ty*TC+tx]>=cutoff){minX=Math.min(minX,tx);maxX=Math.max(maxX,tx);minY=Math.min(minY,ty);maxY=Math.max(maxY,ty);}
        return new RectF(minX/(float)TC,minY/(float)TR,(maxX+1)/(float)TC,(maxY+1)/(float)TR);
    }

    private static float rectanglePenalty(RectF r){if(r==null)return 0;float w=r.width(),h=r.height();float edge=(r.left<=.001f||r.top<=.001f||r.right>=.999f||r.bottom>=.999f)?1f:0f;float huge=clamp01((w*h-.42f)/.45f);float wide=clamp01((Math.max(w/Math.max(.001f,h),h/Math.max(.001f,w))-2.6f)/3f);return clamp01(edge*.65f+huge*.65f+wide*.18f);}
    private static int tier(float score,float threshold,int persistence){float t=clamp(threshold,.20f,.92f);if(score<t)return 0;if(score>=Math.min(.98f,t+.24f)&&persistence>=3)return 4;if(score>=Math.min(.96f,t+.16f)&&persistence>=2)return 3;if(score>=Math.min(.94f,t+.08f))return 2;return 1;}

    private static Bitmap render(Filter f,int[]src,float[]lum,float[]grad,float[]sat,float[]hue,float[]prev){
        int[]o=new int[src.length];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int i=y*W+x,c=src[i];float l=lum[i],g=clamp01(grad[i]*5.2f),d=prev==null?0:clamp01(Math.abs(l-prev[i])*5f);int v;
            switch(f){
                case HIGH_CONTRAST:v=(int)(clamp01((l-.5f)*2.2f+.5f)*255);o[i]=Color.rgb(v,v,v);break;
                case EDGE:v=(int)(g*255);o[i]=Color.rgb(v,Math.min(255,v*2),v);break;
                case MOTION:v=(int)(d*255);o[i]=Color.rgb(v,(int)(v*.35f),Math.min(255,v*2));break;
                case LOW_LIGHT:{float q=(float)Math.pow(l,.42);float gain=.65f+.8f*q;o[i]=Color.rgb(clamp255((int)(Color.red(c)*gain+q*42)),clamp255((int)(Color.green(c)*gain+q*42)),clamp255((int)(Color.blue(c)*gain+q*42)));break;}
                case CHROMA:{float h=hue[i]*6f;int sector=(int)h;float frac=h-sector;float s=clamp01(sat[i]*1.6f);int rr=0,gg=0,bb=0;switch(sector%6){case 0:rr=255;gg=(int)(255*frac);break;case 1:rr=(int)(255*(1-frac));gg=255;break;case 2:gg=255;bb=(int)(255*frac);break;case 3:gg=(int)(255*(1-frac));bb=255;break;case 4:rr=(int)(255*frac);bb=255;break;default:rr=255;bb=(int)(255*(1-frac));}o[i]=Color.rgb((int)(rr*s),(int)(gg*s),(int)(bb*s));break;}
                case DMT_CODE:{float micro=0;if(x>0&&x<W-1&&y>0&&y<H-1)micro=clamp01(Math.abs(lum[i-1]+lum[i+1]+lum[i-W]+lum[i+W]-4*l)*4.8f);float phase=(hue[i]+g*.37f+micro*.73f)%1f;float q=clamp01(g*.5f+micro*.75f+d*.25f);int rr=(int)(255*q*clamp01(1.5f-Math.abs(phase-.05f)*3));int gg=(int)(255*q*clamp01(1.5f-Math.abs(phase-.40f)*3));int bb=(int)(255*q*clamp01(1.5f-Math.abs(phase-.75f)*3));o[i]=Color.rgb(rr,gg,bb);break;}
                case BENT_LIGHT:{float warp=clamp01(g*.35f+d*.85f);int rr=clamp255((int)(Color.red(c)*.55f+255*warp));int gg=clamp255((int)(Color.green(c)*.65f+120*warp));int bb=clamp255((int)(Color.blue(c)*.75f+255*(1-warp)*g*.45f));o[i]=Color.rgb(rr,gg,bb);break;}
                default:o[i]=c;
            }
        }
        Bitmap b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);b.setPixels(o,0,W,0,0,W,H);return b;
    }

    private static float centerDistance(RectF a,RectF b){float ax=(a.left+a.right)*.5f,ay=(a.top+a.bottom)*.5f,bx=(b.left+b.right)*.5f,by=(b.top+b.bottom)*.5f;float dx=ax-bx,dy=ay-by;return (float)Math.sqrt(dx*dx+dy*dy);}
    private static float hueDistance(float a,float b){float d=Math.abs(a-b);return Math.min(d,1-d);}
    private static float clamp01(float v){return v<0?0:v>1?1:v;}private static float clamp(float v,float lo,float hi){return v<lo?lo:v>hi?hi:v;}private static int clamp255(int v){return v<0?0:v>255?255:v;}
}
