package org.vhanma.dnaforgemax;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * OsciVision Ultra v7: master-phasor parametric scene renderer.
 *
 * A source image is vectorized once into a small scene graph. Each retained contour is
 * converted to a band-limited Fourier curve. A single scene phasor then traverses those
 * periodic objects. Open strokes are made periodic by ping-ponging the stroke, so there is
 * no artificial closing line through the picture. Optional 3-D transforms happen after
 * curve evaluation and before stereo X/Y projection.
 *
 * LEFT = X, RIGHT = Y.
 */
final class V7Engine {
    private V7Engine() {}

    static final class Settings {
        int sampleRate = 192000;
        int sceneHz = 30;
        int profile = 0;         // 0 photo sculpture, 1 portrait, 2 line/ink, 3 video
        int toneShells = 5;
        int detail = 86;         // Fourier order + retained objects
        float structure = 0.88f;
        float depth = 0.12f;
        float perspective = 0.16f;
        float motion = 0.18f;
        float gamma = 0.90f;
        boolean invert = false;
        boolean suppressBorders = true;
        boolean temporal = true;
        int motionBank = 0;      // 0 natural, 1=81, 2=64, 3=49
        float xGain = 1f;
        float yGain = 1f;
        float rotationDeg = 0f;

        Settings copy() {
            Settings s = new Settings();
            s.sampleRate=sampleRate; s.sceneHz=sceneHz; s.profile=profile; s.toneShells=toneShells;
            s.detail=detail; s.structure=structure; s.depth=depth; s.perspective=perspective;
            s.motion=motion; s.gamma=gamma; s.invert=invert; s.suppressBorders=suppressBorders;
            s.temporal=temporal; s.motionBank=motionBank; s.xGain=xGain; s.yGain=yGain;
            s.rotationDeg=rotationDeg; return s;
        }
    }

    static final class Scene {
        final ArrayList<Obj> objects;
        final int grid;
        final int rejectedBorders;
        final int sourcePaths;
        final float sourceAspect;
        Scene(ArrayList<Obj> objects,int grid,int rejectedBorders,int sourcePaths,float sourceAspect){
            this.objects=objects; this.grid=grid; this.rejectedBorders=rejectedBorders;
            this.sourcePaths=sourcePaths; this.sourceAspect=sourceAspect;
        }
    }

    static final class Result {
        final float[] xy;
        final int objects;
        final int flybacks;
        final float peakStep;
        final float rmsStep;
        final int harmonics;
        final int samples;
        final float sceneHz;
        final String mode;
        final int rejectedBorders;
        Result(float[]xy,int objects,int flybacks,float peakStep,float rmsStep,int harmonics,
               int samples,float sceneHz,String mode,int rejectedBorders){
            this.xy=xy;this.objects=objects;this.flybacks=flybacks;this.peakStep=peakStep;
            this.rmsStep=rmsStep;this.harmonics=harmonics;this.samples=samples;
            this.sceneHz=sceneHz;this.mode=mode;this.rejectedBorders=rejectedBorders;
        }
    }

    private static final class P { float x,y; P(float x,float y){this.x=x;this.y=y;} P copy(){return new P(x,y);} }
    private static final class Seg { P a,b; boolean used; Seg(P a,P b){this.a=a;this.b=b;} }
    private static final class RawPath {
        final ArrayList<P> p=new ArrayList<>();
        boolean closed; int kind; float importance,length,curvature,meanTone,borderFraction,rectness;
        float minX,minY,maxX,maxY;
        void finish(Field f){
            if(p.isEmpty())return;
            minX=maxX=p.get(0).x;minY=maxY=p.get(0).y;length=0;curvature=0;double tone=0;int border=0;
            for(int i=0;i<p.size();i++){
                P q=p.get(i);minX=Math.min(minX,q.x);maxX=Math.max(maxX,q.x);minY=Math.min(minY,q.y);maxY=Math.max(maxY,q.y);
                int xi=clamp(Math.round(q.x),0,f.n-1),yi=clamp(Math.round(q.y),0,f.n-1);tone+=f.lum[yi*f.n+xi];
                if(xi<4||yi<4||xi>f.n-5||yi>f.n-5)border++;
                if(i>0)length+=dist(p.get(i-1),q);
            }
            if(closed&&p.size()>2)length+=dist(p.get(p.size()-1),p.get(0));
            for(int i=1;i+1<p.size();i++){
                P a=p.get(i-1),b=p.get(i),c=p.get(i+1);float ax=b.x-a.x,ay=b.y-a.y,bx=c.x-b.x,by=c.y-b.y;
                float al=(float)Math.sqrt(ax*ax+ay*ay)+1e-6f,bl=(float)Math.sqrt(bx*bx+by*by)+1e-6f;
                curvature+=(float)Math.acos(clamp((ax*bx+ay*by)/(al*bl),-1f,1f));
            }
            meanTone=(float)(tone/Math.max(1,p.size()));borderFraction=border/(float)Math.max(1,p.size());
            float bw=Math.max(1e-3f,maxX-minX),bh=Math.max(1e-3f,maxY-minY),near=0;
            float tol=Math.max(1.2f,Math.min(bw,bh)*.035f);
            for(P q:p)if(Math.min(Math.min(Math.abs(q.x-minX),Math.abs(q.x-maxX)),Math.min(Math.abs(q.y-minY),Math.abs(q.y-maxY)))<tol)near++;
            rectness=near/Math.max(1f,p.size());
        }
        void reverse(){Collections.reverse(p);}
    }

    /** Fourier descriptor for one periodic scene object. */
    private static final class Obj {
        float a0x,a0y;
        float[] acx,asx,acy,asy;
        float importance,z,phaseOffset,span;
        int kind,harmonics;
        float minX,minY,maxX,maxY;
        float evalX(double phase){double a=a0x;for(int k=1;k<=harmonics;k++){double q=2*Math.PI*k*phase;a+=acx[k]*(float)Math.cos(q)+asx[k]*(float)Math.sin(q);}return (float)a;}
        float evalY(double phase){double a=a0y;for(int k=1;k<=harmonics;k++){double q=2*Math.PI*k*phase;a+=acy[k]*(float)Math.cos(q)+asy[k]*(float)Math.sin(q);}return (float)a;}
    }

    private static final class Field {
        final int n; final float[] lum,grad,gx,gy,saliency;
        Field(int n,float[]lum,float[]grad,float[]gx,float[]gy,float[]saliency){this.n=n;this.lum=lum;this.grad=grad;this.gx=gx;this.gy=gy;this.saliency=saliency;}
    }

    static Scene buildScene(Bitmap input,Settings s){
        if(input==null)return new Scene(new ArrayList<>(),1,0,0,1f);
        int grid=chooseGrid(s);Field f=buildField(input,s,grid);ArrayList<RawPath> paths=new ArrayList<>();

        // Tonal isophotes create nested sculptural shells instead of noisy fill points.
        if(s.profile!=2){
            float[] levels=quantiles(f.lum,clamp(s.toneShells,2,9));
            for(int i=0;i<levels.length;i++)addMarching(f.lum,grid,levels[i],0,.56f+.16f*i/Math.max(1f,levels.length-1),paths,s.profile==3?8:6);
        }
        // Structural contours carry recognition. Two thresholds keep strong and mid-scale detail.
        float e0=s.profile==2?.12f:(s.profile==1?.15f:.18f);
        addMarching(f.grad,grid,e0,1,1.00f,paths,s.profile==3?7:5);
        addMarching(f.grad,grid,Math.min(.72f,e0*1.9f),1,1.12f,paths,s.profile==3?5:4);

        int sourcePaths=paths.size(),rejected=0;ArrayList<RawPath> clean=new ArrayList<>();
        for(RawPath p:paths){
            simplify(p,s.profile==2?.25f:.38f);if(s.profile!=2)chaikin(p,s.detail>80?2:1);p.finish(f);
            if(p.p.size()<4||p.length<1.4f)continue;
            boolean huge=p.maxX-p.minX>grid*.86f&&p.maxY-p.minY>grid*.86f;
            boolean rect=(p.maxX-p.minX)*(p.maxY-p.minY)>grid*grid*.10f&&p.rectness>.80f;
            if(s.suppressBorders&&(p.borderFraction>.48f||huge||rect)){rejected++;continue;}
            clean.add(p);
        }
        clean.sort((a,b)->Float.compare(pathScore(b,f,s),pathScore(a,f,s)));
        int maxObjects=s.profile==3?clamp(20+s.detail/2,24,72):clamp(18+s.detail*2/3,24,82);
        if(clean.size()>maxObjects)clean=new ArrayList<>(clean.subList(0,maxObjects));

        ArrayList<Obj> objs=new ArrayList<>();int baseH=clamp(8+s.detail*24/100,8,32);
        for(RawPath p:clean){int h=clamp(baseH+(p.kind==1?4:0),8,36);Obj o=fitFourier(p,f,grid,h,s);if(o!=null)objs.add(o);}
        if(objs.isEmpty())objs.add(circleObject());
        orderObjects(objs);
        float aspect=input.getWidth()/(float)Math.max(1,input.getHeight());
        return new Scene(objs,grid,rejected,sourcePaths,aspect);
    }

    static Result render(Scene scene,Settings s,double timeSeconds){
        if(scene==null||scene.objects.isEmpty()){float[]c=circle(s,Math.max(1024,s.sampleRate/Math.max(1,s.sceneHz)));return metricsResult(c,1,1,0,s,"fallback",0);}
        int samples=clamp((int)Math.round(s.sampleRate/(double)Math.max(8,s.sceneHz)),2400,24000);
        ArrayList<Obj> objs=scene.objects;float sum=0;for(Obj o:objs){o.span=Math.max(.02f,o.importance);sum+=o.span;}for(Obj o:objs)o.span/=Math.max(.001f,sum);
        int[] alloc=new int[objs.size()];int used=0;for(int i=0;i<objs.size();i++){alloc[i]=Math.max(48,Math.round(samples*objs.get(i).span));used+=alloc[i];}
        while(used>samples){int bi=-1,bv=48;for(int i=0;i<alloc.length;i++)if(alloc[i]>bv){bv=alloc[i];bi=i;}if(bi<0)break;alloc[bi]--;used--;}
        while(used<samples){alloc[used%alloc.length]++;used++;}

        float[]xy=new float[samples*2];int pos=0;double masterMotion=motionPhase(s,timeSeconds);double theta=s.motion*0.42*Math.sin(2*Math.PI*masterMotion);double cs=Math.cos(theta),sn=Math.sin(theta);
        for(int oi=0;oi<objs.size()&&pos<xy.length;oi++){
            Obj o=objs.get(oi);int n=alloc[oi];
            for(int j=0;j<n&&pos<xy.length;j++){
                double lp=o.phaseOffset+j/(double)Math.max(1,n);float x=o.evalX(lp),y=o.evalY(lp);
                float z=o.z;
                // Common 3-D scene transform. All objects share the same slow master motion.
                float xr=(float)(x*cs+z*sn),zr=(float)(-x*sn+z*cs);
                float wob=s.motion*.035f*(float)Math.sin(2*Math.PI*(masterMotion+oi*.071));
                float yr=y+wob*(.35f+Math.abs(z));
                float persp=1f/(1f+clamp(s.perspective,0f,.55f)*zr);
                xy[pos++]=xr*persp;xy[pos++]=yr*persp;
            }
        }
        while(pos<xy.length){xy[pos]=pos>=2?xy[pos-2]:0;pos++;}
        rotateLargestGapToSeam(xy);transform(xy,s);return metricsResult(xy,objs.size(),averageH(objs),scene.rejectedBorders,s,profileName(s.profile),scene.rejectedBorders);
    }

    static Result compile(Bitmap input,Settings s,long frameIndex){Scene sc=buildScene(input,s);return render(sc,s,frameIndex/30.0);}

    static Scene demoScene(Settings s){
        ArrayList<Obj> o=new ArrayList<>();
        // Four mathematically generated mushroom-like wire sculptures. These are only a renderer
        // test scene, not a reproduction of any copyrighted artwork.
        for(int m=0;m<4;m++){
            float cx=(m%2==0?-.38f:.38f),cy=(m<2?-.30f:.34f);ArrayList<P> pts=new ArrayList<>();
            int n=256;for(int i=0;i<n;i++){
                double t=2*Math.PI*i/n;float y=(float)Math.sin(t);float cap=(float)Math.exp(-Math.pow((y-.30f)*2.0,2));
                float width=.08f+.24f*cap+.035f*(1-y*y);float x=(float)Math.cos(t)*width;
                float yy=y*.32f-.03f*cap;pts.add(new P((x+cx+1f)*160f,(yy+cy+1f)*160f));
            }
            RawPath rp=new RawPath();rp.p.addAll(pts);rp.closed=true;rp.kind=1;rp.importance=1f;Field dummy=dummyField(320);rp.finish(dummy);Obj ob=fitFourier(rp,dummy,320,28,s);ob.z=(m-1.5f)*.08f;o.add(ob);
            // Cap ring gives the shape an unmistakably parametric 3-D feel.
            ArrayList<P> ring=new ArrayList<>();for(int i=0;i<128;i++){double t=2*Math.PI*i/128;ring.add(new P((cx+.24f*(float)Math.cos(t)+1)*160f,(cy-.11f+.07f*(float)Math.sin(t)+1)*160f));}
            RawPath rr=new RawPath();rr.p.addAll(ring);rr.closed=true;rr.kind=0;rr.importance=.72f;rr.finish(dummy);Obj ro=fitFourier(rr,dummy,320,16,s);ro.z=ob.z+.05f;o.add(ro);
        }
        orderObjects(o);return new Scene(o,320,0,o.size(),1f);
    }

    private static double motionPhase(Settings s,double time){double rate=.085; if(s.motionBank==1)rate=.081;else if(s.motionBank==2)rate=.064;else if(s.motionBank==3)rate=.049;return fract(time*rate);}

    private static Obj fitFourier(RawPath p,Field f,int grid,int H,Settings s){
        int base=256;float[]r=resamplePeriodic(p,base);if(r.length<8)return null;int n=r.length/2;Obj o=new Obj();o.harmonics=Math.min(H,n/2-1);
        o.acx=new float[o.harmonics+1];o.asx=new float[o.harmonics+1];o.acy=new float[o.harmonics+1];o.asy=new float[o.harmonics+1];
        double sx=0,sy=0;for(int i=0;i<n;i++){sx+=normCoord(r[i*2],grid);sy+=-normCoord(r[i*2+1],grid);}o.a0x=(float)(sx/n);o.a0y=(float)(sy/n);
        for(int k=1;k<=o.harmonics;k++){
            double acx=0,asx=0,acy=0,asy=0;for(int i=0;i<n;i++){double q=2*Math.PI*k*i/n,c=Math.cos(q),sn=Math.sin(q);double x=normCoord(r[i*2],grid),y=-normCoord(r[i*2+1],grid);acx+=x*c;asx+=x*sn;acy+=y*c;asy+=y*sn;}
            float roll=(float)Math.exp(-Math.pow(k/(double)Math.max(3,o.harmonics),4)*1.55);o.acx[k]=(float)(2*acx/n)*roll;o.asx[k]=(float)(2*asx/n)*roll;o.acy[k]=(float)(2*acy/n)*roll;o.asy[k]=(float)(2*asy/n)*roll;
        }
        o.kind=p.kind;o.importance=Math.max(.05f,pathScore(p,f,s));o.z=(p.meanTone-.5f)*2f*clamp(s.depth,0f,.55f);
        o.minX=normCoord(p.minX,grid);o.maxX=normCoord(p.maxX,grid);o.minY=-normCoord(p.maxY,grid);o.maxY=-normCoord(p.minY,grid);return o;
    }

    private static float[] resamplePeriodic(RawPath p,int n){
        ArrayList<P> q=new ArrayList<>();q.addAll(p.p);if(!p.closed){for(int i=p.p.size()-2;i>0;i--)q.add(p.p.get(i));}
        if(q.size()<2)return new float[0];int segs=q.size();float[]cum=new float[segs+1];for(int i=0;i<segs;i++)cum[i+1]=cum[i]+dist(q.get(i),q.get((i+1)%q.size()));
        float total=Math.max(1e-5f,cum[segs]);float[]o=new float[n*2];int si=0;for(int k=0;k<n;k++){float target=total*k/n;while(si<segs-1&&cum[si+1]<target)si++;float d=Math.max(1e-6f,cum[si+1]-cum[si]),u=(target-cum[si])/d;P a=q.get(si),b=q.get((si+1)%q.size());o[k*2]=a.x+(b.x-a.x)*u;o[k*2+1]=a.y+(b.y-a.y)*u;}return o;
    }

    private static void orderObjects(ArrayList<Obj> objs){
        if(objs.size()<2)return;objs.sort((a,b)->Float.compare(b.importance,a.importance));ArrayList<Obj> out=new ArrayList<>();boolean[]used=new boolean[objs.size()];Obj cur=objs.get(0);used[0]=true;cur.phaseOffset=bestPhase(cur,0,0);out.add(cur);
        float ex=cur.evalX(cur.phaseOffset),ey=cur.evalY(cur.phaseOffset);
        for(int k=1;k<objs.size();k++){
            int best=-1;float bestP=0,bd=Float.MAX_VALUE;for(int i=0;i<objs.size();i++)if(!used[i]){Obj q=objs.get(i);float ph=bestPhase(q,ex,ey),x=q.evalX(ph),y=q.evalY(ph);float dx=x-ex,dy=y-ey;float d=dx*dx+dy*dy-.015f*q.importance;if(d<bd){bd=d;best=i;bestP=ph;}}
            if(best<0)break;Obj q=objs.get(best);q.phaseOffset=bestP;used[best]=true;out.add(q);ex=q.evalX(bestP);ey=q.evalY(bestP);
        }
        objs.clear();objs.addAll(out);
    }

    private static float bestPhase(Obj o,float tx,float ty){float best=0,bd=Float.MAX_VALUE;for(int i=0;i<64;i++){float p=i/64f,x=o.evalX(p),y=o.evalY(p),dx=x-tx,dy=y-ty,d=dx*dx+dy*dy;if(d<bd){bd=d;best=p;}}return best;}
    private static int averageH(ArrayList<Obj>o){int n=0;for(Obj q:o)n+=q.harmonics;return Math.round(n/(float)Math.max(1,o.size()));}

    private static Result metricsResult(float[]xy,int objects,int harmonics,int rejected,Settings s,String mode,int ignored){int n=xy.length/2,fly=0;double ss=0;float pk=0;for(int i=1;i<n;i++){float dx=xy[i*2]-xy[(i-1)*2],dy=xy[i*2+1]-xy[(i-1)*2+1],d=(float)Math.sqrt(dx*dx+dy*dy);ss+=d*d;pk=Math.max(pk,d);if(d>.08f)fly++;}float rms=(float)Math.sqrt(ss/Math.max(1,n-1));return new Result(xy,objects,fly,pk,rms,harmonics,n,s.sceneHz,mode,rejected);}

    private static int chooseGrid(Settings s){return s.profile==3?clamp(220+s.detail,240,340):clamp(260+s.detail,280,380);}

    private static Field buildField(Bitmap input,Settings s,int n){
        Bitmap b=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.BLACK);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float sc=Math.min(n/(float)Math.max(1,input.getWidth()),n/(float)Math.max(1,input.getHeight()));float w=input.getWidth()*sc,h=input.getHeight()*sc,l=(n-w)/2f,t=(n-h)/2f;c.drawBitmap(input,null,new RectF(l,t,l+w,t+h),p);
        int[]px=new int[n*n];b.getPixels(px,0,n,0,0,n,n);float[]lum=new float[px.length];for(int i=0;i<px.length;i++){int q=px[i];float v=(.2126f*Color.red(q)+.7152f*Color.green(q)+.0722f*Color.blue(q))/255f;if(s.invert)v=1-v;lum[i]=(float)Math.pow(clamp01(v),clamp(s.gamma,.35f,2.2f));}
        float[]blur=boxBlur(lum,n,s.profile==3?2:3);float[]gx=new float[lum.length],gy=new float[lum.length],gr=new float[lum.length];float max=1e-6f;
        for(int y=1;y<n-1;y++)for(int x=1;x<n-1;x++){int i=y*n+x;float dx=-lum[i-n-1]+lum[i-n+1]-2*lum[i-1]+2*lum[i+1]-lum[i+n-1]+lum[i+n+1];float dy=-lum[i-n-1]-2*lum[i-n]-lum[i-n+1]+lum[i+n-1]+2*lum[i+n]+lum[i+n+1];float m=(float)Math.sqrt(dx*dx+dy*dy);gx[i]=dx;gy[i]=dy;gr[i]=m;max=Math.max(max,m);}
        float inv=1/max;float[]sal=new float[lum.length];for(int y=0;y<n;y++)for(int x=0;x<n;x++){int i=y*n+x;gx[i]*=inv;gy[i]*=inv;gr[i]=clamp01(gr[i]*inv);float nx=(x-(n-1)*.5f)/(n*.5f),ny=(y-(n-1)*.5f)/(n*.5f);float center=(float)Math.exp(-(nx*nx+ny*ny)*.62);float local=Math.abs(lum[i]-blur[i]);sal[i]=gr[i]*(.72f+.28f*center)+local*.55f;}
        return new Field(n,lum,gr,gx,gy,sal);
    }

    private static float pathScore(RawPath p,Field f,Settings s){
        double sal=0;for(P q:p.p){int x=clamp(Math.round(q.x),0,f.n-1),y=clamp(Math.round(q.y),0,f.n-1);sal+=f.saliency[y*f.n+x];}float mean=(float)(sal/Math.max(1,p.p.size()));float edge=p.kind==1?(1.25f+.5f*s.structure):1f;float size=(float)Math.sqrt(Math.max(.1f,p.length));return p.importance*edge*size*(.45f+.95f*mean)*(1+.025f*Math.min(12,p.curvature));}

    private static float[] quantiles(float[]a,int count){int[]h=new int[256];for(float v:a)h[clamp(Math.round(v*255),0,255)]++;float[]o=new float[count];for(int k=0;k<count;k++){int target=Math.round((k+1f)/(count+1f)*a.length),sum=0,b=0;for(;b<256;b++){sum+=h[b];if(sum>=target)break;}o[k]=clamp(b/255f,.04f,.96f);}return o;}

    private static void addMarching(float[]f,int n,float level,int kind,float importance,ArrayList<RawPath>out,int minPoints){
        ArrayList<Seg>segs=new ArrayList<>();for(int y=0;y<n-1;y++)for(int x=0;x<n-1;x++){int i=y*n+x;float v0=f[i],v1=f[i+1],v2=f[i+n+1],v3=f[i+n];int m=(v0>=level?1:0)|(v1>=level?2:0)|(v2>=level?4:0)|(v3>=level?8:0);if(m==0||m==15)continue;P e0=interp(x,y,v0,x+1,y,v1,level),e1=interp(x+1,y,v1,x+1,y+1,v2,level),e2=interp(x+1,y+1,v2,x,y+1,v3,level),e3=interp(x,y+1,v3,x,y,v0,level);switch(m){case 1:case 14:add(segs,e3,e0);break;case 2:case 13:add(segs,e0,e1);break;case 3:case 12:add(segs,e3,e1);break;case 4:case 11:add(segs,e1,e2);break;case 6:case 9:add(segs,e0,e2);break;case 7:case 8:add(segs,e3,e2);break;case 5:{float c=(v0+v1+v2+v3)*.25f;if(c>=level){add(segs,e0,e1);add(segs,e2,e3);}else{add(segs,e3,e0);add(segs,e1,e2);}break;}case 10:{float c=(v0+v1+v2+v3)*.25f;if(c>=level){add(segs,e3,e0);add(segs,e1,e2);}else{add(segs,e0,e1);add(segs,e2,e3);}break;}}}
        HashMap<Long,ArrayList<Integer>>map=new HashMap<>();for(int i=0;i<segs.size();i++){map.computeIfAbsent(key(segs.get(i).a),q->new ArrayList<>()).add(i);map.computeIfAbsent(key(segs.get(i).b),q->new ArrayList<>()).add(i);}for(int si=0;si<segs.size();si++){if(segs.get(si).used)continue;RawPath p=new RawPath();p.kind=kind;p.importance=importance;Seg s=segs.get(si);s.used=true;p.p.add(s.a.copy());p.p.add(s.b.copy());extend(p,segs,map,true);extend(p,segs,map,false);p.closed=p.p.size()>3&&dist(p.p.get(0),p.p.get(p.p.size()-1))<.9f;if(p.closed)p.p.remove(p.p.size()-1);if(p.p.size()>=minPoints)out.add(p);}
    }
    private static void add(ArrayList<Seg>s,P a,P b){if(dist(a,b)>.01f)s.add(new Seg(a,b));}
    private static P interp(float x0,float y0,float v0,float x1,float y1,float v1,float l){float d=v1-v0,t=Math.abs(d)<1e-7f?.5f:clamp((l-v0)/d,0,1);return new P(x0+(x1-x0)*t,y0+(y1-y0)*t);}
    private static long key(P p){int x=Math.round(p.x*8),y=Math.round(p.y*8);return(((long)x)<<32)^(y&0xffffffffL);}
    private static void extend(RawPath p,ArrayList<Seg>s,HashMap<Long,ArrayList<Integer>>m,boolean tail){for(int g=0;g<100000;g++){P e=tail?p.p.get(p.p.size()-1):p.p.get(0);ArrayList<Integer>ids=m.get(key(e));if(ids==null)break;Seg hit=null;P next=null;for(int id:ids){Seg q=s.get(id);if(q.used)continue;if(dist(e,q.a)<.24f){hit=q;next=q.b;break;}if(dist(e,q.b)<.24f){hit=q;next=q.a;break;}}if(hit==null)break;hit.used=true;if(tail)p.p.add(next.copy());else p.p.add(0,next.copy());if(p.p.size()>4&&dist(p.p.get(0),p.p.get(p.p.size()-1))<.12f)break;}}

    private static void simplify(RawPath p,float eps){if(p.p.size()<5)return;boolean[]k=new boolean[p.p.size()];k[0]=k[k.length-1]=true;rdp(p.p,0,p.p.size()-1,eps*eps,k);ArrayList<P>q=new ArrayList<>();for(int i=0;i<p.p.size();i++)if(k[i])q.add(p.p.get(i));p.p.clear();p.p.addAll(q);}
    private static void rdp(List<P>a,int lo,int hi,float e2,boolean[]k){if(hi<=lo+1)return;P A=a.get(lo),B=a.get(hi);float best=-1;int bi=-1;for(int i=lo+1;i<hi;i++){float d=line2(a.get(i),A,B);if(d>best){best=d;bi=i;}}if(best>e2){k[bi]=true;rdp(a,lo,bi,e2,k);rdp(a,bi,hi,e2,k);}}
    private static float line2(P p,P a,P b){float vx=b.x-a.x,vy=b.y-a.y,wx=p.x-a.x,wy=p.y-a.y,vv=vx*vx+vy*vy;if(vv<1e-8f)return wx*wx+wy*wy;float t=clamp((wx*vx+wy*vy)/vv,0,1),dx=p.x-(a.x+t*vx),dy=p.y-(a.y+t*vy);return dx*dx+dy*dy;}
    private static void chaikin(RawPath p,int passes){for(int pass=0;pass<passes;pass++){if(p.p.size()<3)return;ArrayList<P>q=new ArrayList<>();if(!p.closed)q.add(p.p.get(0).copy());int lim=p.closed?p.p.size():p.p.size()-1;for(int i=0;i<lim;i++){P a=p.p.get(i),b=p.p.get((i+1)%p.p.size());q.add(new P(.75f*a.x+.25f*b.x,.75f*a.y+.25f*b.y));q.add(new P(.25f*a.x+.75f*b.x,.25f*a.y+.75f*b.y));}if(!p.closed)q.add(p.p.get(p.p.size()-1).copy());p.p.clear();p.p.addAll(q);}}

    private static Obj circleObject(){Obj o=new Obj();o.harmonics=1;o.acx=new float[2];o.asx=new float[2];o.acy=new float[2];o.asy=new float[2];o.acx[1]=.7f;o.asy[1]=.7f;o.importance=1;o.z=0;return o;}
    private static Field dummyField(int n){float[]a=new float[n*n];Arrays.fill(a,.5f);return new Field(n,a,a,a,a,a);}
    private static float[] boxBlur(float[]src,int n,int r){if(r<=0)return src.clone();float[]tmp=new float[src.length],out=new float[src.length];for(int y=0;y<n;y++){float sum=0;for(int x=-r;x<=r;x++)sum+=src[y*n+clamp(x,0,n-1)];for(int x=0;x<n;x++){tmp[y*n+x]=sum/(2*r+1);sum-=src[y*n+clamp(x-r,0,n-1)];sum+=src[y*n+clamp(x+r+1,0,n-1)];}}for(int x=0;x<n;x++){float sum=0;for(int y=-r;y<=r;y++)sum+=tmp[clamp(y,0,n-1)*n+x];for(int y=0;y<n;y++){out[y*n+x]=sum/(2*r+1);sum-=tmp[clamp(y-r,0,n-1)*n+x];sum+=tmp[clamp(y+r+1,0,n-1)*n+x];}}return out;}
    private static void rotateLargestGapToSeam(float[]xy){int n=xy.length/2;if(n<4)return,at=n-1;}

    private static void transform(float[]xy,Settings s){double r=Math.toRadians(s.rotationDeg);float cs=(float)Math.cos(r),sn=(float)Math.sin(r);for(int i=0;i+1<xy.length;i+=2){float x=xy[i]*s.xGain,y=xy[i+1]*s.yGain;float xr=x*cs-y*sn,yr=x*sn+y*cs;xy[i]=clamp(xr,-.985f,.985f);xy[i+1]=clamp(yr,-.985f,.985f);}}
    private static float normCoord(float v,int n){return (v-(n-1)*.5f)*1.84f/Math.max(1,n-1);}
    private static float dist(P a,P b){float x=a.x-b.x,y=a.y-b.y;return(float)Math.sqrt(x*x+y*y);}
    private static float clamp(float v,float a,float b){return v<a?a:(v>b?b:v);}private static int clamp(int v,int a,int b){return v<a?a:(v>b?b:v);}private static float clamp01(float v){return clamp(v,0,1);}private static double fract(double v){return v-Math.floor(v);}

    static float[] circle(Settings s,int n){n=clamp(n,256,40000);float[]o=new float[n*2];for(int i=0;i<n;i++){double a=2*Math.PI*i/n;o[i*2]=(float)Math.cos(a)*.82f;o[i*2+1]=(float)Math.sin(a)*.82f;}transform(o,s);return o;}
    static float[] grid(Settings s,int n){n=clamp(n,256,40000);float[]o=new float[n*2];int lines=10;for(int i=0;i<n;i++){float t=i/(float)Math.max(1,n-1),q=t*lines*2;int seg=Math.min(lines*2-1,(int)q);float u=q-seg,x,y;if((seg&1)==0){y=-.8f+1.6f*(seg/2f)/(lines-1);x=-.8f+1.6f*u;}else{x=-.8f+1.6f*(seg/2f)/(lines-1);y=-.8f+1.6f*u;}o[i*2]=x;o[i*2+1]=y;}transform(o,s);return o;}
}
