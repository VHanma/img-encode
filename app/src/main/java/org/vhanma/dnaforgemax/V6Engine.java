package org.vhanma.dnaforgemax;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * OsciVision Ultra v6 trajectory engine.
 *
 * Core idea: turn pixels into smooth sub-pixel vector geometry first, then allocate audio
 * samples according to geometry/curvature and finally apply a geometry-preserving harmonic
 * time warp. Photo tone is reconstructed with residual micro-loops instead of an unordered
 * point cloud. LEFT=X, RIGHT=Y.
 */
final class V6Engine {
    private V6Engine() {}

    static final class Settings {
        int sampleRate = 192000;
        int fps = 8;
        int quality = 100;
        int profile = 0;       // 0 photo max, 1 portrait, 2 video, 3 line/logo
        int bands = 12;
        int bank = 0;          // 0=81 family, 1=Bagua64, 2=Sevenfold49, 3=raw
        boolean temporal = true;
        boolean invert = false;
        float gamma = 0.90f;
        float tone = 0.56f;
        float edge = 0.78f;
        float harmony = 0.18f;
        float trajectorySmooth = 0.42f;
        float xGain = 1f;
        float yGain = 1f;
        float rotationDeg = 0f;

        Settings copy() {
            Settings s = new Settings();
            s.sampleRate=sampleRate; s.fps=fps; s.quality=quality; s.profile=profile;
            s.bands=bands; s.bank=bank; s.temporal=temporal; s.invert=invert;
            s.gamma=gamma; s.tone=tone; s.edge=edge; s.harmony=harmony;
            s.trajectorySmooth=trajectorySmooth; s.xGain=xGain; s.yGain=yGain;
            s.rotationDeg=rotationDeg; return s;
        }
    }

    static final class Result {
        final float[] xy;
        final int grid;
        final int paths;
        final int residualLoops;
        final int flybacks;
        final float continuity;
        final float fit;
        final float peakStep;
        final float rmsStep;
        final double latticeHz;
        final double primaryHz;
        final int samples;
        final String mode;

        Result(float[] xy,int grid,int paths,int residualLoops,int flybacks,float continuity,
               float fit,float peakStep,float rmsStep,double latticeHz,double primaryHz,
               int samples,String mode) {
            this.xy=xy; this.grid=grid; this.paths=paths; this.residualLoops=residualLoops;
            this.flybacks=flybacks; this.continuity=continuity; this.fit=fit;
            this.peakStep=peakStep; this.rmsStep=rmsStep; this.latticeHz=latticeHz;
            this.primaryHz=primaryHz; this.samples=samples; this.mode=mode;
        }
    }

    private static final double[][] BANKS = {
            {81,121.5,162,243,324,486,729},
            {64,80,96,128,160,192,256,320,384,512},
            {49,73.5,98,122.5,147,196,245,343,490,686}
    };
    private static final double[] LATTICE = {13.5,16.0,24.5};
    private static final double[] PRIMARY = {81.0,64.0,49.0};
    private static final double GOLD = 0.6180339887498948482;

    private static final class P {
        float x,y;
        P(float x,float y){this.x=x;this.y=y;}
        P copy(){return new P(x,y);}
    }

    private static final class Seg {
        P a,b; boolean used;
        Seg(P a,P b){this.a=a;this.b=b;}
    }

    private static final class Path {
        final ArrayList<P> p = new ArrayList<>();
        boolean closed;
        float importance;
        float length;
        float curvature;
        int kind; // 0 isophote, 1 structural edge
        void finish(){
            length=0f; curvature=0f;
            for(int i=1;i<p.size();i++) length += dist(p.get(i-1),p.get(i));
            if(closed && p.size()>2) length += dist(p.get(p.size()-1),p.get(0));
            for(int i=1;i+1<p.size();i++){
                P a=p.get(i-1),b=p.get(i),c=p.get(i+1);
                float ax=b.x-a.x, ay=b.y-a.y, bx=c.x-b.x, by=c.y-b.y;
                float al=(float)Math.sqrt(ax*ax+ay*ay)+1e-5f;
                float bl=(float)Math.sqrt(bx*bx+by*by)+1e-5f;
                float dot=clamp((ax*bx+ay*by)/(al*bl),-1f,1f);
                curvature += (float)Math.acos(dot);
            }
        }
        void reverse(){Collections.reverse(p);}
    }

    private static final class Field {
        final int n;
        final float[] lum, grad, gx, gy, local;
        Field(int n,float[] lum,float[] grad,float[] gx,float[] gy,float[] local){
            this.n=n;this.lum=lum;this.grad=grad;this.gx=gx;this.gy=gy;this.local=local;
        }
    }

    static Result compile(Bitmap input, Settings s, long frameIndex) {
        if(input==null) return empty();
        int grid = chooseGrid(s);
        Field f = field(input,s,grid);
        ArrayList<Path> paths = new ArrayList<>();

        if(s.profile!=3) {
            float[] levels = quantileLevels(f.lum, clamp(s.bands,4,18));
            for(int i=0;i<levels.length;i++) {
                float imp = 0.42f + 0.36f * i / Math.max(1f,levels.length-1f);
                addMarchingContours(f.lum,grid,levels[i],0,imp,paths,
                        s.profile==2?10:7);
            }
        }

        float edgeBase = s.profile==3 ? 0.12f : (s.profile==1 ? 0.18f : 0.23f);
        addMarchingContours(f.grad,grid,edgeBase,1,0.92f,paths,s.profile==2?9:6);
        addMarchingContours(f.grad,grid,Math.min(.72f,edgeBase*1.75f),1,1.0f,paths,s.profile==2?7:5);
        if(s.profile==3) addMarchingContours(f.grad,grid,Math.min(.86f,edgeBase*2.7f),1,1.08f,paths,4);

        if(paths.isEmpty()) {
            float[] c=circle(s,Math.max(2048,s.sampleRate/Math.max(1,s.fps)));
            return new Result(c,grid,0,0,0,1f,0f,0f,0f,0,0,c.length/2,"fallback");
        }

        // Smooth/simplify before ordering. Marching squares gives sub-pixel geometry; these
        // passes remove cell-scale stair stepping without erasing strong corners.
        for(Path p:paths) {
            simplify(p, s.profile==3 ? 0.20f : 0.32f);
            if(s.profile!=3) chaikin(p, s.quality>=90?2:1);
            p.finish();
        }
        paths.removeIf(p -> p.p.size()<3 || p.length<1.0f);
        paths.sort((a,b)->Float.compare(score(b,s),score(a,s)));
        int maxPaths = s.profile==2 ? 260 : (s.profile==3?420:520);
        maxPaths += Math.round(s.quality*1.1f);
        if(paths.size()>maxPaths) paths = new ArrayList<>(paths.subList(0,maxPaths));

        orderPaths(paths,s);

        double latticeHz=0,primaryHz=0;
        int totalSamples;
        if(s.bank>=0 && s.bank<3) {
            latticeHz=LATTICE[s.bank]; primaryHz=PRIMARY[s.bank];
            totalSamples=clamp((int)Math.round(s.sampleRate/latticeHz),4096,s.profile==2?20000:32000);
        } else {
            totalSamples=clamp(Math.round(s.sampleRate/(float)Math.max(1,s.fps)),2400,s.profile==2?16000:32000);
        }

        float toneShare = s.profile==3 ? 0f : clamp(s.tone,0f,.88f) * (s.profile==2?.25f:.38f);
        int residualN = Math.round(totalSamples*toneShare);
        int vectorN = Math.max(1024,totalSamples-residualN);

        float[] vector = renderPaths(paths,vectorN,grid,s);
        float[] density = densityFromXY(vector,grid);
        Residual residual = residualLoops(f,density,residualN,frameIndex,s);

        float[] xy = new float[totalSamples*2];
        int pos=0;
        int vcopy=Math.min(vector.length,xy.length); System.arraycopy(vector,0,xy,0,vcopy); pos=vcopy;
        if(pos<xy.length && residual.xy.length>0) {
            int n=Math.min(residual.xy.length,xy.length-pos); System.arraycopy(residual.xy,0,xy,pos,n); pos+=n;
        }
        while(pos<xy.length){xy[pos]=pos>=2?xy[pos-2]:0; pos++;}

        // Put the single worst travel event at the loop seam so disconnected geometry does
        // not smear through the middle of the visible image.
        rotateLargestGapToSeam(xy);
        normalizeFromGrid(xy,grid,s);
        minimumJerkRelax(xy,clamp(s.trajectorySmooth,0f,.85f));
        if(s.bank>=0 && s.bank<3 && s.harmony>0.002f)
            xy = harmonicTimeWarp(xy,s,frameIndex,LATTICE[s.bank],BANKS[s.bank]);

        Metrics m=metrics(xy);
        float fit=estimateFit(f,xy,grid);
        String mode = s.profile==0?"PHOTO MAX":s.profile==1?"PORTRAIT":s.profile==2?"VIDEO":"LINE";
        return new Result(xy,grid,paths.size(),residual.loops,m.flybacks,m.continuity,
                fit,m.peak,m.rms,latticeHz,primaryHz,xy.length/2,mode);
    }

    private static Result empty(){return new Result(new float[]{0,0,0,0},1,0,0,0,1f,0,0,0,0,0,2,"empty");}

    private static int chooseGrid(Settings s){
        if(s.profile==2) return clamp(180+Math.round(s.quality*1.1f),180,292);
        if(s.profile==3) return clamp(220+Math.round(s.quality*1.55f),220,384);
        return clamp(250+Math.round(s.quality*1.75f),250,440);
    }

    private static Field field(Bitmap input,Settings s,int n){
        Bitmap b=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(b); c.drawColor(Color.BLACK);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float sc=Math.min(n/(float)Math.max(1,input.getWidth()),n/(float)Math.max(1,input.getHeight()));
        float w=input.getWidth()*sc,h=input.getHeight()*sc,l=(n-w)/2f,t=(n-h)/2f;
        c.drawBitmap(input,null,new RectF(l,t,l+w,t+h),paint);
        int[] px=new int[n*n]; b.getPixels(px,0,n,0,0,n,n);
        float[] lum=new float[px.length];
        for(int i=0;i<px.length;i++){
            int q=px[i]; float v=(.2126f*Color.red(q)+.7152f*Color.green(q)+.0722f*Color.blue(q))/255f;
            if(s.invert)v=1-v; lum[i]=(float)Math.pow(clamp01(v),clamp(s.gamma,.3f,2.3f));
        }
        // Local contrast normalization is deliberately mild. It raises facial/texture detail
        // without turning noise into thousands of contour fragments.
        float[] blur=boxBlur(lum,n,s.profile==2?2:3);
        float[] local=new float[lum.length];
        for(int i=0;i<lum.length;i++) local[i]=clamp01(lum[i]+(lum[i]-blur[i])*(s.profile==1?.72f:.46f));
        float[] gx=new float[lum.length],gy=new float[lum.length],mag=new float[lum.length];
        float max=1e-6f;
        for(int y=1;y<n-1;y++)for(int x=1;x<n-1;x++){
            int i=y*n+x;
            float dx=-local[i-n-1]+local[i-n+1]-2*local[i-1]+2*local[i+1]-local[i+n-1]+local[i+n+1];
            float dy=-local[i-n-1]-2*local[i-n]-local[i-n+1]+local[i+n-1]+2*local[i+n]+local[i+n+1];
            float m=(float)Math.sqrt(dx*dx+dy*dy); gx[i]=dx;gy[i]=dy;mag[i]=m;if(m>max)max=m;
        }
        float inv=1/max;
        for(int i=0;i<mag.length;i++){gx[i]*=inv;gy[i]*=inv;mag[i]=clamp01(mag[i]*inv);}
        return new Field(n,local,mag,gx,gy,blur);
    }

    private static float[] quantileLevels(float[] a,int count){
        int bins=256; int[] hist=new int[bins];
        for(float v:a)hist[clamp(Math.round(clamp01(v)*255),0,255)]++;
        int total=a.length; float[] out=new float[count];
        for(int k=0;k<count;k++){
            float q=(k+1f)/(count+1f); int target=Math.round(q*total),sum=0,bin=0;
            for(;bin<bins;bin++){sum+=hist[bin];if(sum>=target)break;}
            out[k]=clamp(bin/255f,.035f,.965f);
        }
        // Remove near-duplicates caused by large flat regions.
        ArrayList<Float> u=new ArrayList<>();
        for(float v:out){if(u.isEmpty()||Math.abs(v-u.get(u.size()-1))>.018f)u.add(v);}
        float[] r=new float[u.size()];for(int i=0;i<r.length;i++)r[i]=u.get(i);return r;
    }

    private static void addMarchingContours(float[] f,int n,float level,int kind,float importance,
                                             ArrayList<Path> out,int minPoints){
        ArrayList<Seg> segs=new ArrayList<>();
        for(int y=0;y<n-1;y++)for(int x=0;x<n-1;x++){
            int i=y*n+x;
            float v0=f[i],v1=f[i+1],v2=f[i+n+1],v3=f[i+n];
            int mask=(v0>=level?1:0)|(v1>=level?2:0)|(v2>=level?4:0)|(v3>=level?8:0);
            if(mask==0||mask==15)continue;
            P e0=interp(x,y,v0,x+1,y,v1,level);
            P e1=interp(x+1,y,v1,x+1,y+1,v2,level);
            P e2=interp(x+1,y+1,v2,x,y+1,v3,level);
            P e3=interp(x,y+1,v3,x,y,v0,level);
            switch(mask){
                case 1:case 14:add(segs,e3,e0);break;
                case 2:case 13:add(segs,e0,e1);break;
                case 3:case 12:add(segs,e3,e1);break;
                case 4:case 11:add(segs,e1,e2);break;
                case 6:case 9:add(segs,e0,e2);break;
                case 7:case 8:add(segs,e3,e2);break;
                case 5:{float center=(v0+v1+v2+v3)*.25f;if(center>=level){add(segs,e0,e1);add(segs,e2,e3);}else{add(segs,e3,e0);add(segs,e1,e2);}break;}
                case 10:{float center=(v0+v1+v2+v3)*.25f;if(center>=level){add(segs,e3,e0);add(segs,e1,e2);}else{add(segs,e0,e1);add(segs,e2,e3);}break;}
            }
        }
        if(segs.isEmpty())return;
        HashMap<Long,ArrayList<Integer>> map=new HashMap<>(segs.size()*2);
        for(int i=0;i<segs.size();i++){
            map.computeIfAbsent(key(segs.get(i).a),k->new ArrayList<>()).add(i);
            map.computeIfAbsent(key(segs.get(i).b),k->new ArrayList<>()).add(i);
        }
        for(int si=0;si<segs.size();si++){
            if(segs.get(si).used)continue;
            Path p=new Path(); p.kind=kind;p.importance=importance;
            Seg s=segs.get(si);s.used=true;p.p.add(s.a.copy());p.p.add(s.b.copy());
            extend(p,segs,map,true); extend(p,segs,map,false);
            p.closed=p.p.size()>3 && dist(p.p.get(0),p.p.get(p.p.size()-1))<.9f;
            if(p.closed)p.p.remove(p.p.size()-1);
            if(p.p.size()>=minPoints){p.finish();out.add(p);}
        }
    }

    private static void add(ArrayList<Seg>s,P a,P b){if(dist(a,b)>.01f)s.add(new Seg(a,b));}
    private static P interp(float x0,float y0,float v0,float x1,float y1,float v1,float level){
        float d=v1-v0;float t=Math.abs(d)<1e-7f?.5f:clamp((level-v0)/d,0f,1f);return new P(x0+(x1-x0)*t,y0+(y1-y0)*t);
    }
    private static long key(P p){int x=Math.round(p.x*8f),y=Math.round(p.y*8f);return (((long)x)<<32)^(y&0xffffffffL);}

    private static void extend(Path p,ArrayList<Seg>segs,HashMap<Long,ArrayList<Integer>>map,boolean tail){
        for(int guard=0;guard<100000;guard++){
            P end=tail?p.p.get(p.p.size()-1):p.p.get(0);ArrayList<Integer> ids=map.get(key(end));if(ids==null)break;
            Seg hit=null;P next=null;
            for(int id:ids){Seg s=segs.get(id);if(s.used)continue;
                if(dist(end,s.a)<.24f){hit=s;next=s.b;break;}if(dist(end,s.b)<.24f){hit=s;next=s.a;break;}}
            if(hit==null)break;hit.used=true;if(tail)p.p.add(next.copy());else p.p.add(0,next.copy());
            if(p.p.size()>4 && dist(p.p.get(0),p.p.get(p.p.size()-1))<.12f)break;
        }
    }

    private static void simplify(Path p,float eps){
        if(p.p.size()<5)return;boolean[] keep=new boolean[p.p.size()];keep[0]=keep[keep.length-1]=true;rdp(p.p,0,p.p.size()-1,eps*eps,keep);
        ArrayList<P> q=new ArrayList<>();for(int i=0;i<p.p.size();i++)if(keep[i])q.add(p.p.get(i));p.p.clear();p.p.addAll(q);
    }
    private static void rdp(List<P>a,int lo,int hi,float eps2,boolean[]keep){
        if(hi<=lo+1)return;P A=a.get(lo),B=a.get(hi);float best=-1;int bi=-1;
        for(int i=lo+1;i<hi;i++){float d=pointLine2(a.get(i),A,B);if(d>best){best=d;bi=i;}}
        if(best>eps2){keep[bi]=true;rdp(a,lo,bi,eps2,keep);rdp(a,bi,hi,eps2,keep);}
    }
    private static float pointLine2(P p,P a,P b){float vx=b.x-a.x,vy=b.y-a.y,wx=p.x-a.x,wy=p.y-a.y;float vv=vx*vx+vy*vy;if(vv<1e-8f)return wx*wx+wy*wy;float t=clamp((wx*vx+wy*vy)/vv,0f,1f);float dx=p.x-(a.x+t*vx),dy=p.y-(a.y+t*vy);return dx*dx+dy*dy;}

    private static void chaikin(Path path,int passes){
        for(int pass=0;pass<passes;pass++){
            if(path.p.size()<3)return;ArrayList<P> q=new ArrayList<>(path.p.size()*2);
            if(!path.closed)q.add(path.p.get(0).copy());
            int lim=path.closed?path.p.size():path.p.size()-1;
            for(int i=0;i<lim;i++){P a=path.p.get(i),b=path.p.get((i+1)%path.p.size());
                q.add(new P(.75f*a.x+.25f*b.x,.75f*a.y+.25f*b.y));
                q.add(new P(.25f*a.x+.75f*b.x,.25f*a.y+.75f*b.y));}
            if(!path.closed)q.add(path.p.get(path.p.size()-1).copy());path.p.clear();path.p.addAll(q);
        }
    }

    private static float score(Path p,Settings s){
        float edgeBoost=p.kind==1?(1.25f+.55f*s.edge):1f;
        return edgeBoost*p.importance*(float)Math.sqrt(Math.max(.1f,p.length))*(1f+.05f*p.curvature);
    }

    private static void orderPaths(ArrayList<Path> p,Settings s){
        if(p.size()<2)return;
        ArrayList<Path> ordered=new ArrayList<>(p.size());boolean[] used=new boolean[p.size()];
        int start=0;used[start]=true;ordered.add(p.get(start));
        for(int k=1;k<p.size();k++){
            Path cur=ordered.get(ordered.size()-1);P ce=end(cur);float[] ct=endTangent(cur);int best=-1;boolean rev=false;float bc=Float.MAX_VALUE;
            for(int j=0;j<p.size();j++)if(!used[j]){
                Path q=p.get(j);float c1=joinCost(ce,ct,start(q),startTangent(q),q);
                float c2=joinCost(ce,ct,end(q),neg(endTangent(q)),q);
                if(c1<bc){bc=c1;best=j;rev=false;}if(c2<bc){bc=c2;best=j;rev=true;}
            }
            if(best<0)break;Path q=p.get(best);if(rev)q.reverse();used[best]=true;ordered.add(q);
        }
        p.clear();p.addAll(ordered);
        // Small 2-opt pass on path-level travel cost. This is cheap and dramatically reduces
        // random connector lines in photos with many disconnected contours.
        int n=p.size();for(int pass=0;pass<2;pass++)for(int i=0;i<n-3;i++){
            int max=Math.min(n-2,i+22);for(int j=i+2;j<=max;j++){
                float old=dist(end(p.get(i)),start(p.get(i+1)))+dist(end(p.get(j)),start(p.get(j+1)));
                float neu=dist(end(p.get(i)),end(p.get(j)))+dist(start(p.get(i+1)),start(p.get(j+1)));
                if(neu+0.15f<old){Collections.reverse(p.subList(i+1,j+1));for(int z=i+1;z<=j;z++)p.get(z).reverse();break;}
            }
        }
    }

    private static float joinCost(P a,float[]at,P b,float[]bt,Path q){
        float d=dist(a,b);float align=1f-(at[0]*bt[0]+at[1]*bt[1]);return d*(1f+.22f*align)+Math.min(7f,align*1.4f)-.02f*q.importance;
    }
    private static P start(Path p){return p.p.get(0);}private static P end(Path p){return p.p.get(p.p.size()-1);}
    private static float[] startTangent(Path p){P a=p.p.get(0),b=p.p.get(Math.min(1,p.p.size()-1));return unit(b.x-a.x,b.y-a.y);}
    private static float[] endTangent(Path p){int n=p.p.size();P a=p.p.get(Math.max(0,n-2)),b=p.p.get(n-1);return unit(b.x-a.x,b.y-a.y);}
    private static float[] neg(float[]v){return new float[]{-v[0],-v[1]};}
    private static float[] unit(float x,float y){float d=(float)Math.sqrt(x*x+y*y)+1e-7f;return new float[]{x/d,y/d};}

    private static float[] renderPaths(ArrayList<Path> paths,int samples,int grid,Settings s){
        if(samples<=0||paths.isEmpty())return new float[0];float[] w=new float[paths.size()];float sum=0;
        for(int i=0;i<paths.size();i++){Path p=paths.get(i);float ww=p.length*(1f+.22f*Math.min(8f,p.curvature))*(p.kind==1?1.16f:1f);w[i]=Math.max(.01f,ww);sum+=w[i];}
        int[] alloc=new int[paths.size()];int used=0;int min=s.profile==2?4:6;
        for(int i=0;i<alloc.length;i++){alloc[i]=Math.max(min,Math.round(samples*w[i]/Math.max(.001f,sum)));used+=alloc[i];}
        while(used>samples){int bi=-1,bv=min;for(int i=0;i<alloc.length;i++)if(alloc[i]>bv){bv=alloc[i];bi=i;}if(bi<0)break;alloc[bi]--;used--;}
        while(used<samples){int bi=used%alloc.length;alloc[bi]++;used++;}
        float[] out=new float[samples*2];int pos=0;
        for(int i=0;i<paths.size()&&pos<out.length;i++){
            float[] r=resample(paths.get(i),alloc[i]);int n=Math.min(r.length,out.length-pos);System.arraycopy(r,0,out,pos,n);pos+=n;
        }
        while(pos<out.length){out[pos]=pos>=2?out[pos-2]:grid*.5f;pos++;}return out;
    }

    private static float[] resample(Path p,int n){
        if(n<=0)return new float[0];if(p.p.size()<2){float[]o=new float[n*2];return o;}
        int segs=p.closed?p.p.size():p.p.size()-1;float[] cum=new float[segs+1];
        for(int i=0;i<segs;i++)cum[i+1]=cum[i]+dist(p.p.get(i),p.p.get((i+1)%p.p.size()));
        float total=Math.max(1e-6f,cum[segs]);float[]o=new float[n*2];int si=0;
        for(int k=0;k<n;k++){float target=total*(p.closed?k/(float)n:k/(float)Math.max(1,n-1));while(si<segs-1&&cum[si+1]<target)si++;
            float den=Math.max(1e-6f,cum[si+1]-cum[si]),t=(target-cum[si])/den;P a=p.p.get(si),b=p.p.get((si+1)%p.p.size());
            o[k*2]=a.x+(b.x-a.x)*t;o[k*2+1]=a.y+(b.y-a.y)*t;}
        return o;
    }

    private static float[] densityFromXY(float[]xy,int grid){
        float[]d=new float[grid*grid];for(int i=0;i+1<xy.length;i+=2){int x=clamp(Math.round(xy[i]),0,grid-1),y=clamp(Math.round(xy[i+1]),0,grid-1);d[y*grid+x]+=1f;}
        d=boxBlur(d,grid,1);float max=1e-6f;for(float v:d)if(v>max)max=v;for(int i=0;i<d.length;i++)d[i]=clamp01(d[i]/max);return d;
    }

    private static final class Residual {final float[]xy;final int loops;Residual(float[]x,int l){xy=x;loops=l;}}
    private static Residual residualLoops(Field f,float[]density,int samples,long frame,Settings s){
        if(samples<8)return new Residual(new float[0],0);float[]w=new float[f.lum.length];double total=0;
        for(int i=0;i<w.length;i++){
            float target=clamp01(f.lum[i]*(.72f+.20f*s.tone)+f.grad[i]*(s.profile==1?.34f:.22f));
            float r=Math.max(0,target-density[i]*.88f);w[i]=(float)Math.pow(r,1.35);total+=w[i];
        }
        if(total<1e-8)return new Residual(new float[samples*2],0);double[]cdf=new double[w.length];double c=0;for(int i=0;i<w.length;i++){c+=w[i];cdf[i]=c;}
        int loopSize=s.profile==2?6:8;int loops=Math.max(1,samples/loopSize);float[]o=new float[samples*2];int pos=0;
        double rot=s.temporal?0:fract(frame*.754877666);for(int k=0;k<loops&&pos<o.length;k++){
            double u=fract((k+.5)*GOLD+rot)*total;int idx=lower(cdf,u);int x=idx%f.n,y=idx/f.n;
            float gx=f.gx[idx],gy=f.gy[idx],ang=(float)Math.atan2(gy,gx)+(float)Math.PI/2f;
            float radius=.22f+.58f*(float)Math.sqrt(clamp01(w[idx]));float ca=(float)Math.cos(ang),sa=(float)Math.sin(ang);
            int m=Math.min(loopSize,(o.length-pos)/2);for(int j=0;j<m;j++){
                float a=(float)(2*Math.PI*j/Math.max(1,m));float ex=(float)Math.cos(a)*radius,ey=(float)Math.sin(a)*radius*.58f;
                o[pos++]=x+ex*ca-ey*sa;o[pos++]=y+ex*sa+ey*ca;}
        }
        while(pos<o.length){o[pos]=pos>=2?o[pos-2]:f.n*.5f;pos++;}return new Residual(o,loops);
    }

    private static void rotateLargestGapToSeam(float[]xy){int n=xy.length/2;if(n<4)return;int at=n-1;float best=step2(xy,n-1,0);
        for(int i=0;i<n-1;i++){float d=step2(xy,i,i+1);if(d>best){best=d;at=i;}}
        if(at==n-1)return;float[]c=xy.clone();int start=at+1;for(int i=0;i<n;i++){int src=(start+i)%n;xy[i*2]=c[src*2];xy[i*2+1]=c[src*2+1];}}

    private static void normalizeFromGrid(float[]xy,int grid,Settings s){float half=(grid-1)*.5f,norm=1.84f/Math.max(1f,grid-1f);double r=Math.toRadians(s.rotationDeg);float cs=(float)Math.cos(r),sn=(float)Math.sin(r);
        for(int i=0;i+1<xy.length;i+=2){float x=(xy[i]-half)*norm*s.xGain,y=-(xy[i+1]-half)*norm*s.yGain;float xr=x*cs-y*sn,yr=x*sn+y*cs;xy[i]=clamp(xr,-.985f,.985f);xy[i+1]=clamp(yr,-.985f,.985f);}}

    private static void minimumJerkRelax(float[]xy,float amount){
        if(amount<.01f||xy.length<14)return;int n=xy.length/2;float jump2=.035f*.035f;float[]c=xy.clone();int passes=amount>.55f?3:2;float a=.10f+.22f*amount;
        for(int pass=0;pass<passes;pass++){System.arraycopy(xy,0,c,0,xy.length);for(int i=2;i<n-2;i++){
            if(step2(c,i-2,i-1)>jump2||step2(c,i-1,i)>jump2||step2(c,i,i+1)>jump2||step2(c,i+1,i+2)>jump2)continue;
            float tx=(c[(i-2)*2]+4*c[(i-1)*2]+6*c[i*2]+4*c[(i+1)*2]+c[(i+2)*2])/16f;
            float ty=(c[(i-2)*2+1]+4*c[(i-1)*2+1]+6*c[i*2+1]+4*c[(i+1)*2+1]+c[(i+2)*2+1])/16f;
            xy[i*2]=c[i*2]+(tx-c[i*2])*a;xy[i*2+1]=c[i*2+1]+(ty-c[i*2+1])*a;}}
    }

    private static float[] harmonicTimeWarp(float[]xy,Settings s,long frame,double lattice,double[]bank){
        int n=xy.length/2;if(n<16)return xy;double duration=n/(double)Math.max(1,s.sampleRate);double strength=Math.min(.55,Math.max(0,s.harmony)*.48);
        double[]cum=new double[n];cum[0]=0;double sumW=0;for(int k=0;k<bank.length;k++)sumW+=1.0/(1+k*.55);
        for(int i=1;i<n;i++){double t=i/(double)s.sampleRate;double m=0;for(int k=0;k<bank.length;k++){double w=1.0/(1+k*.55);double phase=k*2.399963229728653 + (s.temporal?0:frame*.071);m+=w*Math.sin(2*Math.PI*bank[k]*t+phase);}m/=sumW;cum[i]=cum[i-1]+Math.exp(strength*m);}
        double total=cum[n-1];float[]o=new float[xy.length];int src=0;for(int j=0;j<n;j++){
            double target=total*j/Math.max(1,n-1.0);while(src<n-2&&cum[src+1]<target)src++;double den=Math.max(1e-12,cum[src+1]-cum[src]);float f=(float)((target-cum[src])/den);
            float x0=xy[src*2],y0=xy[src*2+1],x1=xy[(src+1)*2],y1=xy[(src+1)*2+1];float dx=x1-x0,dy=y1-y0;
            if(dx*dx+dy*dy>.012f)f=f<.5f?0:1;o[j*2]=x0+dx*f;o[j*2+1]=y0+dy*f;}
        return o;
    }

    private static final class Metrics {int flybacks;float continuity,peak,rms;}
    private static Metrics metrics(float[]xy){Metrics m=new Metrics();int n=xy.length/2;double ss=0;float pk=0;for(int i=1;i<n;i++){float dx=xy[i*2]-xy[(i-1)*2],dy=xy[i*2+1]-xy[(i-1)*2+1];float d=(float)Math.sqrt(dx*dx+dy*dy);ss+=d*d;if(d>pk)pk=d;if(d>.075f)m.flybacks++;}m.peak=pk;m.rms=(float)Math.sqrt(ss/Math.max(1,n-1));m.continuity=clamp01(1-m.flybacks/(float)Math.max(1,n/120));return m;}

    private static float estimateFit(Field f,float[]xy,int grid){float[]d=new float[grid*grid];float half=(grid-1)*.5f,inv=half/.92f;
        for(int i=0;i+1<xy.length;i+=2){int x=clamp(Math.round(half+xy[i]*inv),0,grid-1),y=clamp(Math.round(half-xy[i+1]*inv),0,grid-1);d[y*grid+x]+=1;}
        d=boxBlur(d,grid,1);float mx=1e-6f;for(float v:d)if(v>mx)mx=v;double err=0,den=0;for(int i=0;i<d.length;i++){float pred=clamp01(d[i]/mx),tar=clamp01(f.lum[i]*.78f+f.grad[i]*.22f);err+=Math.abs(tar-pred);den+=tar+.15;}return clamp01(1f-(float)(err/Math.max(1e-6,den)));}

    static float[] circle(Settings s,int n){n=clamp(n,256,40000);float[]o=new float[n*2];double r=Math.toRadians(s.rotationDeg);float cs=(float)Math.cos(r),sn=(float)Math.sin(r);for(int i=0;i<n;i++){double a=2*Math.PI*i/n;float x=(float)Math.cos(a)*.82f*s.xGain,y=(float)Math.sin(a)*.82f*s.yGain;o[i*2]=clamp(x*cs-y*sn,-.985f,.985f);o[i*2+1]=clamp(x*sn+y*cs,-.985f,.985f);}return o;}
    static float[] grid(Settings s,int n){n=clamp(n,256,40000);float[]o=new float[n*2];int lines=10;double r=Math.toRadians(s.rotationDeg);float cs=(float)Math.cos(r),sn=(float)Math.sin(r);for(int i=0;i<n;i++){float t=i/(float)Math.max(1,n-1),q=t*lines*2;int seg=Math.min(lines*2-1,(int)q);float u=q-seg,x,y;if((seg&1)==0){y=-.8f+1.6f*(seg/2f)/(lines-1);x=-.8f+1.6f*u;}else{x=-.8f+1.6f*(seg/2f)/(lines-1);y=-.8f+1.6f*u;}x*=s.xGain;y*=s.yGain;o[i*2]=clamp(x*cs-y*sn,-.985f,.985f);o[i*2+1]=clamp(x*sn+y*cs,-.985f,.985f);}return o;}

    private static float[] boxBlur(float[]a,int n,int r){if(r<=0)return a.clone();float[]tmp=new float[a.length],out=new float[a.length];for(int y=0;y<n;y++){float sum=0;for(int x=-r;x<=r;x++)sum+=a[y*n+clamp(x,0,n-1)];for(int x=0;x<n;x++){tmp[y*n+x]=sum/(2*r+1);sum-=a[y*n+clamp(x-r,0,n-1)];sum+=a[y*n+clamp(x+r+1,0,n-1)];}}for(int x=0;x<n;x++){float sum=0;for(int y=-r;y<=r;y++)sum+=tmp[clamp(y,0,n-1)*n+x];for(int y=0;y<n;y++){out[y*n+x]=sum/(2*r+1);sum-=tmp[clamp(y-r,0,n-1)*n+x];sum+=tmp[clamp(y+r+1,0,n-1)*n+x];}}return out;}
    private static int lower(double[]a,double v){int lo=0,hi=a.length-1;while(lo<hi){int m=(lo+hi)>>>1;if(a[m]<v)lo=m+1;else hi=m;}return lo;}
    private static double fract(double x){return x-Math.floor(x);}
    private static float dist(P a,P b){float x=a.x-b.x,y=a.y-b.y;return(float)Math.sqrt(x*x+y*y);}
    private static float step2(float[]a,int i,int j){float x=a[i*2]-a[j*2],y=a[i*2+1]-a[j*2+1];return x*x+y*y;}
    private static float clamp01(float v){return v<0?0:(v>1?1:v);}private static float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);}private static int clamp(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}
}
