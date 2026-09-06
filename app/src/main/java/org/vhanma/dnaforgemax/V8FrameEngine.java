package org.vhanma.dnaforgemax;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Arrays;

/**
 * FRAMEFORGE Ω v8.
 * Every source frame is compiled into one exact-duration, self-contained stereo XY block.
 * The compiler oversamples a perceptual field, orders the candidate beam visits, performs
 * residual correction, decimates to the exact sample budget, then solves the frame seam.
 * Color is retained in the software preview and encoded into tiny geometry-safe chroma
 * micro-offsets. LEFT=X, RIGHT=Y.
 */
final class V8FrameEngine {
    private V8FrameEngine() {}

    static final class Settings {
        int sampleRate = 192000;
        int fps = 30;
        int quality = 100;
        int profile = 0; // 0 photo, 1 portrait, 2 line/ink, 3 video
        boolean suppressBorders = true;
        boolean invert = false;
        boolean offline = false;
        float structure = .92f;
        float tone = .72f;
        float color = .36f;
        float gamma = .90f;
        float xGain = 1f, yGain = 1f, rotationDeg = 0f;

        Settings copy() {
            Settings s = new Settings();
            s.sampleRate=sampleRate; s.fps=fps; s.quality=quality; s.profile=profile;
            s.suppressBorders=suppressBorders; s.invert=invert; s.offline=offline;
            s.structure=structure; s.tone=tone; s.color=color; s.gamma=gamma;
            s.xGain=xGain; s.yGain=yGain; s.rotationDeg=rotationDeg; return s;
        }
    }

    static final class Result {
        final float[] xy;
        final int[] rgb;
        final int samples, fps, flybacks;
        final float structureScore, lumaScore, colorScore, frameError, rmsStep, peakStep;
        final long renderMs;
        final boolean missedDeadline;
        final String mode;

        Result(float[] xy,int[] rgb,int samples,int fps,int flybacks,float structureScore,
               float lumaScore,float colorScore,float frameError,float rmsStep,float peakStep,
               long renderMs,boolean missedDeadline,String mode) {
            this.xy=xy; this.rgb=rgb; this.samples=samples; this.fps=fps; this.flybacks=flybacks;
            this.structureScore=structureScore; this.lumaScore=lumaScore; this.colorScore=colorScore;
            this.frameError=frameError; this.rmsStep=rmsStep; this.peakStep=peakStep;
            this.renderMs=renderMs; this.missedDeadline=missedDeadline; this.mode=mode;
        }
    }

    private static final class Field {
        final int n;
        final int[] rgb;
        final float[] lum,hue,sat,grad,contrast,colorContrast,corner,importance;
        Field(int n,int[]rgb,float[]lum,float[]hue,float[]sat,float[]grad,float[]contrast,
              float[]colorContrast,float[]corner,float[]importance){
            this.n=n;this.rgb=rgb;this.lum=lum;this.hue=hue;this.sat=sat;this.grad=grad;
            this.contrast=contrast;this.colorContrast=colorContrast;this.corner=corner;
            this.importance=importance;
        }
    }

    private static final class Cloud {
        final float[] x,y,importance;
        final int[] rgb;
        int size;
        Cloud(int cap){x=new float[cap];y=new float[cap];importance=new float[cap];rgb=new int[cap];}
        void add(float xx,float yy,float imp,int c){if(size>=x.length)return;x[size]=xx;y[size]=yy;importance[size]=imp;rgb[size]=c;size++;}
    }

    private static final double GOLD=.6180339887498948482;

    static Result compile(Bitmap input, Settings s, long frameIndex, float prevEndX, float prevEndY) {
        long started=System.nanoTime();
        if(input==null) return empty(s,started);
        int pairs=Math.max(256,Math.round(s.sampleRate/(float)Math.max(1,s.fps)));
        int grid=chooseGrid(s);
        Field f=buildField(input,s,grid);
        int over=s.offline?4:2;
        int candidates=Math.min(52000,Math.max(pairs,pairs*over));
        Cloud c=sampleField(f,s,candidates,frameIndex);
        orderByMorton(c,grid);
        localRouteImprove(c,s.offline?18:10);
        Cloud out=decimate(c,pairs);
        residualCorrect(out,f,s,frameIndex);
        orderByMorton(out,grid);
        localRouteImprove(out,s.offline?14:8);

        float[]xy=new float[pairs*2]; int[]colors=new int[pairs];
        normalizeAndColor(out,f,s,frameIndex,xy,colors);
        solveSeam(xy,colors,prevEndX,prevEndY);
        Metrics m=metrics(xy);
        Scores q=scores(f,out,colors);
        long renderMs=(System.nanoTime()-started)/1_000_000L;
        long deadline=Math.max(1,Math.round(1000.0/Math.max(1,s.fps)));
        String mode=s.offline?"Ω RENDER":(s.fps==60?"LIVE 60":s.fps==30?"LIVE 30":"DETAIL "+s.fps);
        return new Result(xy,colors,pairs,s.fps,m.flybacks,q.structure,q.luma,q.color,
                q.error,m.rms,m.peak,renderMs,renderMs>deadline,mode);
    }

    private static Result empty(Settings s,long started){
        int n=Math.max(256,Math.round(s.sampleRate/(float)Math.max(1,s.fps)));
        return new Result(new float[n*2],new int[n],n,s.fps,0,0,0,0,1,0,0,
                (System.nanoTime()-started)/1_000_000L,false,"EMPTY");
    }

    private static int chooseGrid(Settings s){
        int base=s.profile==3?188:(s.profile==2?230:246);
        int add=Math.round(s.quality*(s.offline?1.65f:1.18f));
        return clamp(base+add,192,s.offline?440:360);
    }

    private static Field buildField(Bitmap input,Settings s,int n){
        Bitmap b=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);
        Canvas cv=new Canvas(b); cv.drawColor(Color.BLACK);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float sc=Math.min(n/(float)Math.max(1,input.getWidth()),n/(float)Math.max(1,input.getHeight()));
        float w=input.getWidth()*sc,h=input.getHeight()*sc,l=(n-w)*.5f,t=(n-h)*.5f;
        cv.drawBitmap(input,null,new RectF(l,t,l+w,t+h),p);
        int[]rgb=new int[n*n];b.getPixels(rgb,0,n,0,0,n,n);
        float[]lum=new float[rgb.length],hue=new float[rgb.length],sat=new float[rgb.length];
        float[]hsv=new float[3];
        for(int i=0;i<rgb.length;i++){
            int q=rgb[i]; float v=(.2126f*Color.red(q)+.7152f*Color.green(q)+.0722f*Color.blue(q))/255f;
            if(s.invert)v=1-v; lum[i]=(float)Math.pow(clamp01(v),clamp(s.gamma,.35f,2.2f));
            Color.colorToHSV(q,hsv);hue[i]=hsv[0]/360f;sat[i]=hsv[1];
        }
        float[]blur=boxBlur(lum,n,s.profile==1?3:4);
        float[]gx=new float[lum.length],gy=new float[lum.length],grad=new float[lum.length];float gm=1e-6f;
        for(int y=1;y<n-1;y++)for(int x=1;x<n-1;x++){
            int i=y*n+x;float dx=-lum[i-n-1]+lum[i-n+1]-2*lum[i-1]+2*lum[i+1]-lum[i+n-1]+lum[i+n+1];
            float dy=-lum[i-n-1]-2*lum[i-n]-lum[i-n+1]+lum[i+n-1]+2*lum[i+n]+lum[i+n+1];
            float g=(float)Math.sqrt(dx*dx+dy*dy);gx[i]=dx;gy[i]=dy;grad[i]=g;if(g>gm)gm=g;
        }
        float inv=1/gm;float[]contrast=new float[lum.length],cc=new float[lum.length],corner=new float[lum.length],imp=new float[lum.length];
        for(int y=1;y<n-1;y++)for(int x=1;x<n-1;x++){
            int i=y*n+x;grad[i]=clamp01(grad[i]*inv);gx[i]*=inv;gy[i]*=inv;
            contrast[i]=clamp01(Math.abs(lum[i]-blur[i])*3.8f);
            int a=rgb[i],r=rgb[i+1],d=rgb[i+n];
            float cd=(Math.abs(Color.red(a)-Color.red(r))+Math.abs(Color.green(a)-Color.green(r))+Math.abs(Color.blue(a)-Color.blue(r))
                    +Math.abs(Color.red(a)-Color.red(d))+Math.abs(Color.green(a)-Color.green(d))+Math.abs(Color.blue(a)-Color.blue(d)))/(1530f);
            cc[i]=clamp01(cd*2.2f);
            corner[i]=clamp01(Math.min(Math.abs(gx[i]),Math.abs(gy[i]))*2.5f + contrast[i]*.32f);
            float structure=grad[i]*(.78f+.70f*corner[i])*s.structure;
            float tonal=(float)Math.sqrt(Math.max(0,lum[i]))*(.34f+.44f*s.tone)+contrast[i]*.52f;
            float chroma=(sat[i]*(.18f+.55f*cc[i]))*s.color;
            float cx=(x-(n-1)*.5f)/(n*.5f),cy=(y-(n-1)*.5f)/(n*.5f);
            float center=1f-.16f*clamp01((float)Math.sqrt(cx*cx+cy*cy));
            float val=Math.max(structure,Math.max(tonal*.72f,chroma*.65f))*center + .10f*tonal;
            if(s.profile==1)val*=1f+.28f*corner[i]+.18f*contrast[i];
            if(s.profile==2)val=structure*1.25f+contrast[i]*.18f;
            if(s.suppressBorders){int m=Math.max(3,n/30);if(x<m||y<m||x>=n-m||y>=n-m)val*=.055f;}
            imp[i]=Math.max(1e-6f,val);
        }
        return new Field(n,rgb,lum,hue,sat,grad,contrast,cc,corner,imp);
    }

    private static Cloud sampleField(Field f,Settings s,int count,long frame){
        Cloud c=new Cloud(count);int base=Math.max(64,Math.round(count*.08f));
        int side=Math.max(4,(int)Math.sqrt(base));
        for(int yy=0;yy<side&&c.size<count;yy++)for(int xx=0;xx<side&&c.size<count;xx++){
            int x=clamp(Math.round((xx+.5f)*f.n/side),0,f.n-1),y=clamp(Math.round((yy+.5f)*f.n/side),0,f.n-1),i=y*f.n+x;
            c.add(x,y,f.importance[i]*.65f,f.rgb[i]);
        }
        int left=count-c.size;int a=Math.round(left*.34f),b=Math.round(left*.30f),d=Math.round(left*.22f),e=left-a-b-d;
        weighted(c,f,s,0,a,frame*31+1); // structure
        weighted(c,f,s,1,b,frame*37+2); // saliency
        weighted(c,f,s,2,d,frame*41+3); // luminance
        weighted(c,f,s,3,e,frame*43+4); // color
        return c;
    }

    private static void weighted(Cloud c,Field f,Settings s,int layer,int count,long seed){
        if(count<=0)return;double[]cdf=new double[f.rgb.length];double total=0;
        for(int i=0;i<f.rgb.length;i++){
            float w;switch(layer){
                case 0:w=f.grad[i]*(.45f+.80f*f.corner[i])*s.structure+.02f;break;
                case 1:w=f.importance[i]+f.contrast[i]*.22f+.01f;break;
                case 2:w=(float)Math.sqrt(Math.max(0,f.lum[i]))*(.35f+.65f*s.tone)+f.contrast[i]*.18f+.008f;break;
                default:w=f.sat[i]*(.12f+.88f*f.colorContrast[i])*(.20f+.80f*s.color)+.006f;break;
            }
            total+=Math.max(1e-7,w);cdf[i]=total;
        }
        double rot=fract(seed*.7548776662466927);
        for(int k=0;k<count&&c.size<c.x.length;k++){
            double u=fract(rot+(k+.5)*GOLD)*total;int idx=lower(cdf,u);int x=idx%f.n,y=idx/f.n;
            float jx=(float)(fract((k+1)*.41421356237+rot)-.5)*.62f;
            float jy=(float)(fract((k+1)*.73205080757+rot*.7)-.5)*.62f;
            c.add(clamp(x+jx,0,f.n-1),clamp(y+jy,0,f.n-1),f.importance[idx],f.rgb[idx]);
        }
    }

    private static void orderByMorton(Cloud c,int grid){
        long[]pack=new long[c.size];
        for(int i=0;i<c.size;i++){int x=clamp(Math.round(c.x[i]*1023/Math.max(1,grid-1)),0,1023),y=clamp(Math.round(c.y[i]*1023/Math.max(1,grid-1)),0,1023);long key=morton(x,y);pack[i]=(key<<32)|(i&0xffffffffL);}
        Arrays.sort(pack);float[]x=c.x.clone(),y=c.y.clone(),im=c.importance.clone();int[]rgb=c.rgb.clone();
        for(int i=0;i<c.size;i++){int j=(int)pack[i];c.x[i]=x[j];c.y[i]=y[j];c.importance[i]=im[j];c.rgb[i]=rgb[j];}
    }

    private static void localRouteImprove(Cloud c,int window){
        for(int i=0;i<c.size-2;i++){
            int best=i+1;float bd=dist2(c.x[i],c.y[i],c.x[best],c.y[best]);int max=Math.min(c.size-1,i+window);
            for(int j=i+2;j<=max;j++){float d=dist2(c.x[i],c.y[i],c.x[j],c.y[j]);if(d<bd){bd=d;best=j;}}
            if(best!=i+1)swap(c,best,i+1);
        }
    }

    private static Cloud decimate(Cloud in,int target){
        Cloud out=new Cloud(target);if(in.size<=target){for(int i=0;i<in.size;i++)out.add(in.x[i],in.y[i],in.importance[i],in.rgb[i]);return out;}
        for(int k=0;k<target;k++){
            int lo=(int)((long)k*in.size/target),hi=Math.max(lo+1,(int)((long)(k+1)*in.size/target));hi=Math.min(hi,in.size);
            int best=lo;float bs=-1;
            for(int j=lo;j<hi;j++){
                float curv=0;if(j>0&&j+1<in.size){float ax=in.x[j]-in.x[j-1],ay=in.y[j]-in.y[j-1],bx=in.x[j+1]-in.x[j],by=in.y[j+1]-in.y[j];float al=(float)Math.sqrt(ax*ax+ay*ay)+1e-4f,bl=(float)Math.sqrt(bx*bx+by*by)+1e-4f;curv=1-clamp((ax*bx+ay*by)/(al*bl),-1,1);}
                float score=in.importance[j]*(1+.48f*curv);if(score>bs){bs=score;best=j;}
            }
            out.add(in.x[best],in.y[best],in.importance[best],in.rgb[best]);
        }
        return out;
    }

    private static void residualCorrect(Cloud c,Field f,Settings s,long frame){
        int n=f.n;float[]density=new float[n*n];for(int i=0;i<c.size;i++){int x=clamp(Math.round(c.x[i]),0,n-1),y=clamp(Math.round(c.y[i]),0,n-1);density[y*n+x]+=1;}
        density=boxBlur(density,n,1);float mx=1e-6f;for(float v:density)if(v>mx)mx=v;for(int i=0;i<density.length;i++)density[i]/=mx;
        float[]res=new float[n*n];double[]cdf=new double[n*n];double total=0;
        for(int i=0;i<res.length;i++){float target=clamp01(.54f*f.lum[i]+.34f*f.grad[i]+.12f*f.colorContrast[i]);res[i]=Math.max(0,target-density[i]*.92f);total+=Math.pow(res[i],1.5);cdf[i]=total;}
        if(total<1e-8)return;int replace=Math.max(1,Math.round(c.size*(s.offline?.10f:.055f)));int[]weak=new int[replace];Arrays.fill(weak,-1);
        for(int k=0;k<replace;k++){int wi=-1;float wv=Float.MAX_VALUE;for(int i=0;i<c.size;i++){boolean used=false;for(int j=0;j<k;j++)if(weak[j]==i){used=true;break;}if(!used&&c.importance[i]<wv){wv=c.importance[i];wi=i;}}weak[k]=wi;}
        double rot=fract(frame*.56984029+.17);
        for(int k=0;k<replace;k++){int p=weak[k];if(p<0)continue;double u=fract(rot+(k+.5)*GOLD)*total;int idx=lower(cdf,u);c.x[p]=idx%n;c.y[p]=idx/n;c.importance[p]=f.importance[idx]*(1+res[idx]);c.rgb[p]=f.rgb[idx];}
    }

    private static void normalizeAndColor(Cloud c,Field f,Settings s,long frame,float[]xy,int[]colors){
        float half=(f.n-1)*.5f,scale=1.84f/Math.max(1,f.n-1);double rr=Math.toRadians(s.rotationDeg);float cs=(float)Math.cos(rr),sn=(float)Math.sin(rr);
        for(int i=0;i<c.size&&i<colors.length;i++){
            float x=(c.x[i]-half)*scale,y=-(c.y[i]-half)*scale;int idx=clamp(Math.round(c.y[i]),0,f.n-1)*f.n+clamp(Math.round(c.x[i]),0,f.n-1);
            float sat=f.sat[idx],h=f.hue[idx];double phase=2*Math.PI*h + i*.31 + frame*.017;
            float radius=s.color*sat*(.0011f+.0019f*f.colorContrast[idx]);x+=(float)Math.cos(phase)*radius;y+=(float)Math.sin(phase)*radius*.82f;
            x*=s.xGain;y*=s.yGain;float xr=x*cs-y*sn,yr=x*sn+y*cs;xy[i*2]=clamp(xr,-.985f,.985f);xy[i*2+1]=clamp(yr,-.985f,.985f);colors[i]=c.rgb[i]|0xff000000;
        }
    }

    private static void solveSeam(float[]xy,int[]rgb,float px,float py){
        int n=rgb.length;if(n<4||Float.isNaN(px)||Float.isNaN(py))return;int best=0;float bc=Float.MAX_VALUE;
        for(int k=0;k<n;k+=Math.max(1,n/512)){
            int prev=(k-1+n)%n;float start=dist2(px,py,xy[k*2],xy[k*2+1]);float removed=dist2(xy[prev*2],xy[prev*2+1],xy[k*2],xy[k*2+1]);float cost=start-.72f*removed;if(cost<bc){bc=cost;best=k;}
        }
        if(best==0)return;float[]a=xy.clone();int[]c=rgb.clone();for(int i=0;i<n;i++){int j=(best+i)%n;xy[i*2]=a[j*2];xy[i*2+1]=a[j*2+1];rgb[i]=c[j];}
    }

    private static final class Metrics{int flybacks;float rms,peak;}
    private static Metrics metrics(float[]xy){Metrics m=new Metrics();double ss=0;int n=xy.length/2;for(int i=1;i<n;i++){float dx=xy[i*2]-xy[(i-1)*2],dy=xy[i*2+1]-xy[(i-1)*2+1],d=(float)Math.sqrt(dx*dx+dy*dy);ss+=d*d;if(d>m.peak)m.peak=d;if(d>.075f)m.flybacks++;}m.rms=(float)Math.sqrt(ss/Math.max(1,n-1));return m;}

    private static final class Scores{float structure,luma,color,error;}
    private static Scores scores(Field f,Cloud c,int[]colors){Scores s=new Scores();double sg=0,sl=0;float[]density=new float[f.n*f.n];float[]hTarget=new float[12],hSeen=new float[12];
        for(int i=0;i<f.rgb.length;i++){sg+=f.grad[i];sl+=f.lum[i];hTarget[clamp((int)(f.hue[i]*12),0,11)]+=f.sat[i]*f.lum[i];}
        double cg=0,cl=0;for(int i=0;i<c.size;i++){int x=clamp(Math.round(c.x[i]),0,f.n-1),y=clamp(Math.round(c.y[i]),0,f.n-1),idx=y*f.n+x;cg+=f.grad[idx];cl+=f.lum[idx];density[idx]+=1;hSeen[clamp((int)(f.hue[idx]*12),0,11)]+=f.sat[idx]*f.lum[idx];}
        s.structure=clamp01((float)(cg/Math.max(1,c.size))/.22f);s.luma=clamp01((float)(cl/Math.max(1,c.size))/.52f);
        float ht=0,hd=0;for(int i=0;i<12;i++){ht+=hTarget[i];}if(ht<1e-6f)s.color=1;else{float hs=0;for(float v:hSeen)hs+=v;for(int i=0;i<12;i++){float a=hTarget[i]/ht,b=hs<1e-6?0:hSeen[i]/hs;hd+=Math.abs(a-b);}s.color=clamp01(1-hd*.5f);}
        density=boxBlur(density,f.n,1);float mx=1e-6f;for(float v:density)if(v>mx)mx=v;double err=0,den=0;for(int i=0;i<density.length;i++){float pred=density[i]/mx,tar=clamp01(.58f*f.lum[i]+.32f*f.grad[i]+.10f*f.colorContrast[i]);err+=Math.abs(tar-pred);den+=tar+.12;}s.error=clamp01((float)(err/Math.max(1e-6,den)));return s;}

    static float[] calCircle(Settings s){int n=Math.max(512,Math.round(s.sampleRate/(float)Math.max(1,s.fps)));float[]o=new float[n*2];for(int i=0;i<n;i++){double a=2*Math.PI*i/n;o[i*2]=(float)Math.cos(a)*.82f;o[i*2+1]=(float)Math.sin(a)*.82f;}return o;}

    private static void swap(Cloud c,int a,int b){float x=c.x[a];c.x[a]=c.x[b];c.x[b]=x;x=c.y[a];c.y[a]=c.y[b];c.y[b]=x;x=c.importance[a];c.importance[a]=c.importance[b];c.importance[b]=x;int q=c.rgb[a];c.rgb[a]=c.rgb[b];c.rgb[b]=q;}
    private static long morton(int x,int y){return spread(x)|(spread(y)<<1);}private static long spread(long x){x&=0x3ff;x=(x|(x<<16))&0x30000ff;x=(x|(x<<8))&0x300f00f;x=(x|(x<<4))&0x30c30c3;x=(x|(x<<2))&0x9249249;return x;}
    private static int lower(double[]a,double v){int lo=0,hi=a.length-1;while(lo<hi){int m=(lo+hi)>>>1;if(a[m]<v)lo=m+1;else hi=m;}return lo;}
    private static float[] boxBlur(float[]src,int n,int r){float[]tmp=new float[src.length],out=new float[src.length];for(int y=0;y<n;y++){float sum=0;for(int x=-r;x<=r;x++)sum+=src[y*n+clamp(x,0,n-1)];for(int x=0;x<n;x++){tmp[y*n+x]=sum/(2*r+1f);sum-=src[y*n+clamp(x-r,0,n-1)];sum+=src[y*n+clamp(x+r+1,0,n-1)];}}for(int x=0;x<n;x++){float sum=0;for(int y=-r;y<=r;y++)sum+=tmp[clamp(y,0,n-1)*n+x];for(int y=0;y<n;y++){out[y*n+x]=sum/(2*r+1f);sum-=tmp[clamp(y-r,0,n-1)*n+x];sum+=tmp[clamp(y+r+1,0,n-1)*n+x];}}return out;}
    private static float dist2(float ax,float ay,float bx,float by){float dx=bx-ax,dy=by-ay;return dx*dx+dy*dy;}
    private static double fract(double x){return x-Math.floor(x);}private static float clamp01(float v){return clamp(v,0,1);}private static float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);}private static int clamp(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}
}
