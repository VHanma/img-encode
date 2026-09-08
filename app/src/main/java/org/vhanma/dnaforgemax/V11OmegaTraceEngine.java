package org.vhanma.dnaforgemax;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Omega v11 post-compiler.
 *
 * Keeps v9's connected geometry, then improves prepared animation by:
 *  - whole-frame cyclic phase registration against the previous prepared frame,
 *  - curvature/chroma aware timing density without adding unrelated X/Y tones,
 *  - hard perimeter-run purge so frame rectangles cannot survive as long traces,
 *  - light intra-path smoothing that never crosses detected flybacks.
 *
 * Playback remains fully prepared. No image analysis happens inside the audio clock.
 */
final class V11OmegaTraceEngine {
    private V11OmegaTraceEngine() {}

    static final class Options {
        float temporal=.88f;      // frame-to-frame phase registration strength
        float chroma=.42f;        // color/saturation influence on dwell density
        float curvature=.72f;     // preserve corners/feature bends with more samples
        float smoothing=.22f;     // gentle local smoothing inside connected runs
        boolean purgePerimeter=true;
        Options copy(){Options o=new Options();o.temporal=temporal;o.chroma=chroma;o.curvature=curvature;o.smoothing=smoothing;o.purgePerimeter=purgePerimeter;return o;}
    }

    static final class State {
        float[] prevXY;
        int[] prevRGB;
        float coherence=1f;
        int perimeterPurged=0;
        void reset(){prevXY=null;prevRGB=null;coherence=1f;perimeterPurged=0;}
    }

    static V9TraceEngine.Result compile(Bitmap input,
                                        V9TraceEngine.Settings base,
                                        Options omega,
                                        State state,
                                        long frameIndex,
                                        float prevX,
                                        float prevY){
        if(base==null)base=new V9TraceEngine.Settings();
        if(omega==null)omega=new Options();
        if(state==null)state=new State();

        // The anti-rectangle rule is mandatory in Omega mode.
        V9TraceEngine.Settings s=base.copy();
        s.suppressBorders=true;
        V9TraceEngine.Result raw=V9TraceEngine.compile(input,s,frameIndex,prevX,prevY);
        float[]xy=raw.xy.clone();
        int[]rgb=raw.rgb.clone();

        if(omega.purgePerimeter)state.perimeterPurged=purgePerimeterRuns(xy,rgb);
        else state.perimeterPurged=0;

        retimeConnectedRuns(xy,rgb,omega.chroma,omega.curvature);
        smoothConnectedRuns(xy,omega.smoothing);

        if(state.prevXY!=null&&state.prevXY.length==xy.length&&omega.temporal>.001f){
            int shift=findTemporalShift(xy,state.prevXY,prevX,prevY,omega.temporal);
            rotate(xy,rgb,shift);
            state.coherence=coherence(xy,state.prevXY);
        }else state.coherence=1f;

        // Final seam micro-solve is limited to a very small neighborhood so temporal
        // phase identity is not destroyed by a completely different frame start.
        microSeam(xy,rgb,prevX,prevY);

        Metrics m=metrics(xy);
        state.prevXY=xy.clone();
        state.prevRGB=rgb.clone();

        float temporalBonus=.035f*state.coherence;
        float err=clamp01(raw.frameError-temporalBonus);
        return new V9TraceEngine.Result(
                xy,rgb,raw.samples,raw.paths,m.fly,
                raw.structureScore,raw.lumaScore,err,m.rms,m.peak,raw.renderMs);
    }

    private static int purgePerimeterRuns(float[]xy,int[]rgb){
        int n=xy.length/2;if(n<16)return 0;
        boolean[]edge=new boolean[n];
        for(int i=0;i<n;i++){
            float x=Math.abs(xy[i*2]),y=Math.abs(xy[i*2+1]);
            edge[i]=(x>.935f||y>.935f);
        }
        int minRun=Math.max(10,n/180),purged=0;
        for(int i=0;i<n;){
            if(!edge[i]){i++;continue;}
            int a=i;while(i<n&&edge[i])i++;int b=i-1,len=b-a+1;
            if(len<minRun)continue;
            float minX=9,maxX=-9,minY=9,maxY=-9;
            for(int k=a;k<=b;k++){float x=xy[k*2],y=xy[k*2+1];minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}
            boolean frameLike=(maxX-minX>.50f)||(maxY-minY>.50f);
            if(!frameLike)continue;
            int src=a>0?a-1:(b+1<n?b+1:-1);if(src<0)continue;
            float sx=xy[src*2],sy=xy[src*2+1];int sc=rgb[src];
            for(int k=a;k<=b;k++){xy[k*2]=sx;xy[k*2+1]=sy;rgb[k]=sc;purged++;}
        }
        return purged;
    }

    private static void retimeConnectedRuns(float[]xy,int[]rgb,float chroma,float curvature){
        chroma=clamp(chroma,0,1);curvature=clamp(curvature,0,1);
        int n=xy.length/2,start=0;
        while(start<n){
            int end=start;
            while(end+1<n&&step(xy,end,end+1)<=.075f)end++;
            if(end-start>=4)retimeRun(xy,rgb,start,end,chroma,curvature);
            start=end+1;
        }
    }

    private static void retimeRun(float[]xy,int[]rgb,int a,int b,float chroma,float curvature){
        int count=b-a+1;if(count<4)return;
        float[]ox=new float[count],oy=new float[count],mass=new float[count];int[]oc=new int[count];
        for(int i=0;i<count;i++){ox[i]=xy[(a+i)*2];oy[i]=xy[(a+i)*2+1];oc[i]=rgb[a+i];}
        mass[0]=0;
        for(int i=1;i<count;i++){
            float dx=ox[i]-ox[i-1],dy=oy[i]-oy[i-1],ds=(float)Math.sqrt(dx*dx+dy*dy);
            int c=oc[i];float mx=Math.max(Color.red(c),Math.max(Color.green(c),Color.blue(c)))/255f;
            float mn=Math.min(Color.red(c),Math.min(Color.green(c),Color.blue(c)))/255f;
            float sat=mx<=1e-5f?0:(mx-mn)/mx;
            float bend=0;
            if(i<count-1){
                float ax=ox[i]-ox[i-1],ay=oy[i]-oy[i-1],bx=ox[i+1]-ox[i],by=oy[i+1]-oy[i];
                float la=(float)Math.sqrt(ax*ax+ay*ay),lb=(float)Math.sqrt(bx*bx+by*by);
                if(la>1e-5f&&lb>1e-5f){float dot=clamp((ax*bx+ay*by)/(la*lb),-1,1);bend=(1-dot)*.5f;}
            }
            float w=1f+chroma*.75f*sat+curvature*1.45f*bend;
            mass[i]=mass[i-1]+Math.max(1e-6f,ds*w);
        }
        float total=mass[count-1];if(total<1e-6f)return;
        int j=0;
        for(int k=0;k<count;k++){
            float target=total*k/(float)(count-1);
            while(j<count-2&&mass[j+1]<target)j++;
            float d=Math.max(1e-6f,mass[j+1]-mass[j]),u=(target-mass[j])/d;
            xy[(a+k)*2]=ox[j]+(ox[j+1]-ox[j])*u;
            xy[(a+k)*2+1]=oy[j]+(oy[j+1]-oy[j])*u;
            rgb[a+k]=mix(oc[j],oc[j+1],u);
        }
    }

    private static void smoothConnectedRuns(float[]xy,float strength){
        strength=clamp(strength,0,.45f);if(strength<=.001f)return;
        int n=xy.length/2;float[]src=xy.clone();
        for(int i=1;i<n-1;i++){
            if(step(src,i-1,i)>.075f||step(src,i,i+1)>.075f)continue;
            float cx=src[i*2],cy=src[i*2+1];
            float ax=(src[(i-1)*2]+src[(i+1)*2])*.5f,ay=(src[(i-1)*2+1]+src[(i+1)*2+1])*.5f;
            xy[i*2]=cx+(ax-cx)*strength;
            xy[i*2+1]=cy+(ay-cy)*strength;
        }
    }

    private static int findTemporalShift(float[]cur,float[]prev,float px,float py,float strength){
        int n=cur.length/2;if(n<32)return 0;
        int radius=Math.max(8,n/14),coarse=Math.max(1,radius/16),best=0;double bd=Double.MAX_VALUE;
        for(int sh=-radius;sh<=radius;sh+=coarse){double d=phaseCost(cur,prev,sh,px,py,strength);if(d<bd){bd=d;best=sh;}}
        int lo=best-coarse,hi=best+coarse;
        for(int sh=lo;sh<=hi;sh++){double d=phaseCost(cur,prev,sh,px,py,strength);if(d<bd){bd=d;best=sh;}}
        return mod(best,n);
    }

    private static double phaseCost(float[]cur,float[]prev,int shift,float px,float py,float strength){
        int n=cur.length/2,probes=Math.min(96,n),stride=Math.max(1,n/probes);double sum=0;int used=0;
        for(int i=0;i<n;i+=stride){int j=mod(i+shift,n);float dx=cur[j*2]-prev[i*2],dy=cur[j*2+1]-prev[i*2+1];float d=dx*dx+dy*dy;if(d<.30f){sum+=d;used++;}}
        if(used==0)sum=99;else sum/=used;
        if(!Float.isNaN(px)&&!Float.isNaN(py)){int j=mod(shift,n);float dx=cur[j*2]-px,dy=cur[j*2+1]-py;sum+=(dx*dx+dy*dy)*(1.1-strength*.45f);}
        return sum;
    }

    private static float coherence(float[]a,float[]b){
        int n=a.length/2,probes=Math.min(128,n),stride=Math.max(1,n/probes);double d=0;int c=0;
        for(int i=0;i<n;i+=stride){float dx=a[i*2]-b[i*2],dy=a[i*2+1]-b[i*2+1];float q=(float)Math.sqrt(dx*dx+dy*dy);if(q<.65f){d+=q;c++;}}
        if(c==0)return 0;return clamp01(1f-(float)(d/c)/.22f);
    }

    private static void microSeam(float[]xy,int[]rgb,float px,float py){
        if(Float.isNaN(px)||Float.isNaN(py))return;int n=xy.length/2;if(n<8)return;
        int radius=Math.max(2,n/240),best=0;float bd=Float.MAX_VALUE;
        for(int s=-radius;s<=radius;s++){int i=mod(s,n);float dx=xy[i*2]-px,dy=xy[i*2+1]-py,d=dx*dx+dy*dy;if(d<bd){bd=d;best=i;}}
        rotate(xy,rgb,best);
    }

    private static void rotate(float[]xy,int[]rgb,int shift){
        int n=xy.length/2;if(n==0)return;shift=mod(shift,n);if(shift==0)return;
        float[]x=xy.clone();int[]c=rgb.clone();
        for(int i=0;i<n;i++){int j=(i+shift)%n;xy[i*2]=x[j*2];xy[i*2+1]=x[j*2+1];rgb[i]=c[j];}
    }

    private static final class Metrics{int fly;float rms,peak;}
    private static Metrics metrics(float[]xy){Metrics m=new Metrics();double ss=0;int n=xy.length/2;for(int i=1;i<n;i++){float d=step(xy,i-1,i);ss+=d*d;m.peak=Math.max(m.peak,d);if(d>.075f)m.fly++;}m.rms=(float)Math.sqrt(ss/Math.max(1,n-1));return m;}
    private static float step(float[]xy,int a,int b){float dx=xy[b*2]-xy[a*2],dy=xy[b*2+1]-xy[a*2+1];return(float)Math.sqrt(dx*dx+dy*dy);}
    private static int mix(int a,int b,float t){int r=Math.round(Color.red(a)+(Color.red(b)-Color.red(a))*t),g=Math.round(Color.green(a)+(Color.green(b)-Color.green(a))*t),bl=Math.round(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t);return Color.rgb(clampi(r,0,255),clampi(g,0,255),clampi(bl,0,255));}
    private static int mod(int x,int n){int r=x%n;return r<0?r+n:r;}
    private static int clampi(int v,int a,int b){return v<a?a:v>b?b:v;}
    private static float clamp(float v,float a,float b){return v<a?a:v>b?b:v;}
    private static float clamp01(float v){return clamp(v,0,1);}
}