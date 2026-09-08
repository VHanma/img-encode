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

/**
 * v9 reference-trace compiler.
 *
 * Unlike v8's perceptual point cloud, this compiler keeps explicit connected geometry.
 * The ordering is deterministic by region/contour family so neighboring animation frames
 * tend to keep the same traversal structure instead of solving a brand new TSP every frame.
 * Tone is represented by sparse hatching, not random stipple.
 */
final class V9TraceEngine {
    private V9TraceEngine() {}

    static final class Settings {
        int sampleRate=192000;
        int fps=60;
        int profile=0;       // 0 reference/anime, 1 portrait, 2 photo hatch, 3 line/ink
        int quality=92;
        int isoBands=5;
        float structure=.95f;
        float tone=.42f;
        float gamma=.90f;
        boolean suppressBorders=true;
        boolean invert=false;
        float xGain=1f,yGain=1f,rotationDeg=0f;
        Settings copy(){Settings s=new Settings();s.sampleRate=sampleRate;s.fps=fps;s.profile=profile;s.quality=quality;s.isoBands=isoBands;s.structure=structure;s.tone=tone;s.gamma=gamma;s.suppressBorders=suppressBorders;s.invert=invert;s.xGain=xGain;s.yGain=yGain;s.rotationDeg=rotationDeg;return s;}
    }

    static final class Result {
        final float[]xy;final int[]rgb;final int samples,paths,flybacks;final float structureScore,lumaScore,frameError,rmsStep,peakStep;final long renderMs;
        Result(float[]xy,int[]rgb,int samples,int paths,int flybacks,float structureScore,float lumaScore,float frameError,float rmsStep,float peakStep,long renderMs){this.xy=xy;this.rgb=rgb;this.samples=samples;this.paths=paths;this.flybacks=flybacks;this.structureScore=structureScore;this.lumaScore=lumaScore;this.frameError=frameError;this.rmsStep=rmsStep;this.peakStep=peakStep;this.renderMs=renderMs;}
    }

    private static final class P{float x,y;P(float x,float y){this.x=x;this.y=y;}P copy(){return new P(x,y);}}
    private static final class Seg{P a,b;boolean used;Seg(P a,P b){this.a=a;this.b=b;}}
    private static final class Path{
        final ArrayList<P>p=new ArrayList<>();boolean closed;int kind,level,key;float importance,length,cx,cy;
        void finish(){length=0;cx=cy=0;for(P q:p){cx+=q.x;cy+=q.y;}if(!p.isEmpty()){cx/=p.size();cy/=p.size();}for(int i=1;i<p.size();i++)length+=dist(p.get(i-1),p.get(i));if(closed&&p.size()>2)length+=dist(p.get(p.size()-1),p.get(0));}
        void reverse(){Collections.reverse(p);}
    }
    private static final class Field{final int n;final int[]rgb;final float[]lum,grad;Field(int n,int[]rgb,float[]lum,float[]grad){this.n=n;this.rgb=rgb;this.lum=lum;this.grad=grad;}}
    private static final class Metrics{int fly;float rms,peak;}

    static Result compile(Bitmap input,Settings s,long frameIndex,float prevX,float prevY){
        long started=System.nanoTime();
        int pairs=Math.max(320,Math.round(s.sampleRate/(float)Math.max(1,s.fps)));
        if(input==null)return new Result(new float[pairs*2],new int[pairs],pairs,0,0,0,0,1,0,0,0);
        int grid=chooseGrid(s);Field f=field(input,s,grid);ArrayList<Path>paths=new ArrayList<>();

        // Strong structural families first. Gradient isophotes produce coherent connected curves.
        float[]edgeLevels=s.profile==3?new float[]{.10f,.18f,.30f,.46f}:new float[]{.13f,.22f,.36f};
        for(int k=0;k<edgeLevels.length;k++)addContours(f.grad,grid,edgeLevels[k],0,k,1.20f+.15f*k,paths,s.profile==3?4:6);

        // A few luminance shells preserve internal facial/clothing structure without contour soup.
        if(s.profile!=3){float[]levels=quantileLevels(f.lum,clamp(s.isoBands,2,8));for(int k=0;k<levels.length;k++)addContours(f.lum,grid,levels[k],1,k,.72f+.08f*k,paths,7);}

        // Sparse hatch runs carry large tone masses. They are deterministic and therefore stable.
        if(s.profile==2||s.tone>.18f)addHatching(f,s,paths);

        for(Path p:paths){simplify(p,s.profile==3?.20f:.31f);if(p.kind!=2&&s.quality>75)chaikin(p,s.quality>94?2:1);canonicalize(p);p.finish();p.key=stableKey(p,grid);}
        paths.removeIf(p->p.p.size()<2||p.length<1.0f||rejectBorder(p,grid,s));
        for(Path p:paths)p.importance=score(p,f,s);
        paths.sort((a,b)->{int q=Integer.compare(a.kind,b.kind);if(q!=0)return q;q=Integer.compare(a.level,b.level);if(q!=0)return q;q=Integer.compare(a.key,b.key);if(q!=0)return q;return Float.compare(b.importance,a.importance);});

        // Cull low-value geometry to the sample budget. This is path-aware, not point-cloud sampling.
        int maxPaths=s.profile==3?240:(s.profile==0?175:210);maxPaths+=Math.round(s.quality*.55f);if(paths.size()>maxPaths){paths.sort((a,b)->Float.compare(b.importance,a.importance));paths=new ArrayList<>(paths.subList(0,maxPaths));paths.sort((a,b)->{int q=Integer.compare(a.kind,b.kind);if(q!=0)return q;q=Integer.compare(a.level,b.level);if(q!=0)return q;return Integer.compare(a.key,b.key);});}

        int[]alloc=allocate(paths,pairs);float[]xy=new float[pairs*2];int[]colors=new int[pairs];int pos=0;
        for(int i=0;i<paths.size()&&pos<pairs;i++){
            Path p=paths.get(i);int n=alloc[i];if(n<=0)continue;float[]r=resample(p,n);for(int j=0;j<n&&pos<pairs;j++,pos++){
                float gx=r[j*2],gy=r[j*2+1];xy[pos*2]=gx;xy[pos*2+1]=gy;int ix=clamp(Math.round(gx),0,grid-1),iy=clamp(Math.round(gy),0,grid-1);colors[pos]=f.rgb[iy*grid+ix];
            }
        }
        while(pos<pairs){xy[pos*2]=pos>0?xy[(pos-1)*2]:grid*.5f;xy[pos*2+1]=pos>0?xy[(pos-1)*2+1]:grid*.5f;colors[pos]=pos>0?colors[pos-1]:Color.WHITE;pos++;}

        normalize(xy,grid,s);solveSeam(xy,colors,prevX,prevY);Metrics m=metrics(xy);float structure=structureScore(f,xy,grid),luma=lumaScore(f,xy,grid);float err=clamp01(1f-(.72f*structure+.28f*luma));
        long ms=(System.nanoTime()-started)/1_000_000L;return new Result(xy,colors,pairs,paths.size(),m.fly,structure,luma,err,m.rms,m.peak,ms);
    }

    private static int chooseGrid(Settings s){int base=s.profile==3?300:320;return clamp(base+Math.round(s.quality*.9f),300,430);}

    private static Field field(Bitmap input,Settings s,int n){
        Bitmap b=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.BLACK);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float sc=Math.min(n/(float)Math.max(1,input.getWidth()),n/(float)Math.max(1,input.getHeight()));float w=input.getWidth()*sc,h=input.getHeight()*sc,l=(n-w)*.5f,t=(n-h)*.5f;c.drawBitmap(input,null,new RectF(l,t,l+w,t+h),p);
        int[]rgb=new int[n*n];b.getPixels(rgb,0,n,0,0,n,n);float[]lum=new float[rgb.length];for(int i=0;i<rgb.length;i++){int q=rgb[i];float v=(.2126f*Color.red(q)+.7152f*Color.green(q)+.0722f*Color.blue(q))/255f;if(s.invert)v=1-v;lum[i]=(float)Math.pow(clamp01(v),clamp(s.gamma,.35f,2.2f));}
        float[]blur=boxBlur(lum,n,s.profile==3?1:2),grad=new float[lum.length];float max=1e-6f;for(int y=1;y<n-1;y++)for(int x=1;x<n-1;x++){int i=y*n+x;float dx=-blur[i-n-1]+blur[i-n+1]-2*blur[i-1]+2*blur[i+1]-blur[i+n-1]+blur[i+n+1];float dy=-blur[i-n-1]-2*blur[i-n]-blur[i-n+1]+blur[i+n-1]+2*blur[i+n]+blur[i+n+1];float g=(float)Math.sqrt(dx*dx+dy*dy);grad[i]=g;if(g>max)max=g;}for(int i=0;i<grad.length;i++)grad[i]=clamp01(grad[i]/max);return new Field(n,rgb,lum,grad);
    }

    private static float[]quantileLevels(float[]a,int count){int[]h=new int[256];for(float v:a)h[clamp(Math.round(v*255),0,255)]++;float[]out=new float[count];for(int k=0;k<count;k++){int target=Math.round((k+1f)/(count+1f)*a.length),sum=0,b=0;for(;b<256;b++){sum+=h[b];if(sum>=target)break;}out[k]=clamp(b/255f,.07f,.93f);}return out;}

    private static void addContours(float[]f,int n,float level,int kind,int lev,float imp,ArrayList<Path>out,int minPoints){
        ArrayList<Seg>segs=new ArrayList<>();for(int y=0;y<n-1;y++)for(int x=0;x<n-1;x++){int i=y*n+x;float v0=f[i],v1=f[i+1],v2=f[i+n+1],v3=f[i+n];int mask=(v0>=level?1:0)|(v1>=level?2:0)|(v2>=level?4:0)|(v3>=level?8:0);if(mask==0||mask==15)continue;P e0=interp(x,y,v0,x+1,y,v1,level),e1=interp(x+1,y,v1,x+1,y+1,v2,level),e2=interp(x+1,y+1,v2,x,y+1,v3,level),e3=interp(x,y+1,v3,x,y,v0,level);switch(mask){case 1:case 14:add(segs,e3,e0);break;case 2:case 13:add(segs,e0,e1);break;case 3:case 12:add(segs,e3,e1);break;case 4:case 11:add(segs,e1,e2);break;case 6:case 9:add(segs,e0,e2);break;case 7:case 8:add(segs,e3,e2);break;case 5:{float m=(v0+v1+v2+v3)*.25f;if(m>=level){add(segs,e0,e1);add(segs,e2,e3);}else{add(segs,e3,e0);add(segs,e1,e2);}break;}case 10:{float m=(v0+v1+v2+v3)*.25f;if(m>=level){add(segs,e3,e0);add(segs,e1,e2);}else{add(segs,e0,e1);add(segs,e2,e3);}break;}}}
        if(segs.isEmpty())return;HashMap<Long,ArrayList<Integer>>map=new HashMap<>();for(int i=0;i<segs.size();i++){map.computeIfAbsent(key(segs.get(i).a),k->new ArrayList<>()).add(i);map.computeIfAbsent(key(segs.get(i).b),k->new ArrayList<>()).add(i);}for(int si=0;si<segs.size();si++){if(segs.get(si).used)continue;Path p=new Path();p.kind=kind;p.level=lev;p.importance=imp;Seg s=segs.get(si);s.used=true;p.p.add(s.a.copy());p.p.add(s.b.copy());extend(p,segs,map,true);extend(p,segs,map,false);p.closed=p.p.size()>3&&dist(p.p.get(0),p.p.get(p.p.size()-1))<.9f;if(p.closed)p.p.remove(p.p.size()-1);if(p.p.size()>=minPoints){p.finish();out.add(p);}}
    }

    private static void addHatching(Field f,Settings s,ArrayList<Path>out){int step=s.profile==2?12:18;float dark=.62f-.22f*s.tone;for(int y=step/2;y<f.n;y+=step){int x=0;while(x<f.n){while(x<f.n&&f.lum[y*f.n+x]>dark)x++;int a=x;while(x<f.n&&f.lum[y*f.n+x]<=dark)x++;int b=x-1;if(b-a>=10){Path p=new Path();p.kind=2;p.level=y/step;p.importance=.38f+.35f*s.tone;p.p.add(new P(a,y));p.p.add(new P(b,y));p.closed=false;p.finish();out.add(p);}}}
        if(s.profile==2){for(int d=-f.n;d<f.n;d+=step*2){Path p=null;for(int x=0;x<f.n;x++){int y=x+d;if(y<0||y>=f.n)continue;boolean on=f.lum[y*f.n+x]<dark*.78f;if(on){if(p==null){p=new Path();p.kind=2;p.level=1000+d;p.importance=.32f;p.p.add(new P(x,y));}else p.p.add(new P(x,y));}else if(p!=null){if(p.p.size()>=8){p.finish();out.add(p);}p=null;}}if(p!=null&&p.p.size()>=8){p.finish();out.add(p);}}}
    }

    private static void add(ArrayList<Seg>s,P a,P b){if(dist(a,b)>.01f)s.add(new Seg(a,b));}
    private static P interp(float x0,float y0,float v0,float x1,float y1,float v1,float l){float d=v1-v0,t=Math.abs(d)<1e-7f?.5f:clamp((l-v0)/d,0,1);return new P(x0+(x1-x0)*t,y0+(y1-y0)*t);}
    private static long key(P p){int x=Math.round(p.x*8),y=Math.round(p.y*8);return(((long)x)<<32)^(y&0xffffffffL);}
    private static void extend(Path p,ArrayList<Seg>s,HashMap<Long,ArrayList<Integer>>map,boolean tail){for(int g=0;g<100000;g++){P e=tail?p.p.get(p.p.size()-1):p.p.get(0);ArrayList<Integer>ids=map.get(key(e));if(ids==null)break;Seg hit=null;P next=null;for(int id:ids){Seg q=s.get(id);if(q.used)continue;if(dist(e,q.a)<.24f){hit=q;next=q.b;break;}if(dist(e,q.b)<.24f){hit=q;next=q.a;break;}}if(hit==null)break;hit.used=true;if(tail)p.p.add(next.copy());else p.p.add(0,next.copy());if(p.p.size()>4&&dist(p.p.get(0),p.p.get(p.p.size()-1))<.12f)break;}}

    private static void simplify(Path p,float eps){if(p.p.size()<5)return;boolean[]keep=new boolean[p.p.size()];keep[0]=keep[keep.length-1]=true;rdp(p.p,0,p.p.size()-1,eps*eps,keep);ArrayList<P>q=new ArrayList<>();for(int i=0;i<p.p.size();i++)if(keep[i])q.add(p.p.get(i));p.p.clear();p.p.addAll(q);}
    private static void rdp(ArrayList<P>a,int lo,int hi,float e2,boolean[]keep){if(hi<=lo+1)return;P A=a.get(lo),B=a.get(hi);float best=-1;int bi=-1;for(int i=lo+1;i<hi;i++){float d=pointLine2(a.get(i),A,B);if(d>best){best=d;bi=i;}}if(best>e2){keep[bi]=true;rdp(a,lo,bi,e2,keep);rdp(a,bi,hi,e2,keep);}}
    private static float pointLine2(P p,P a,P b){float vx=b.x-a.x,vy=b.y-a.y,wx=p.x-a.x,wy=p.y-a.y,vv=vx*vx+vy*vy;if(vv<1e-8f)return wx*wx+wy*wy;float t=clamp((wx*vx+wy*vy)/vv,0,1),dx=p.x-(a.x+t*vx),dy=p.y-(a.y+t*vy);return dx*dx+dy*dy;}
    private static void chaikin(Path p,int passes){for(int z=0;z<passes;z++){if(p.p.size()<3)return;ArrayList<P>q=new ArrayList<>();if(!p.closed)q.add(p.p.get(0).copy());int lim=p.closed?p.p.size():p.p.size()-1;for(int i=0;i<lim;i++){P a=p.p.get(i),b=p.p.get((i+1)%p.p.size());q.add(new P(.75f*a.x+.25f*b.x,.75f*a.y+.25f*b.y));q.add(new P(.25f*a.x+.75f*b.x,.25f*a.y+.75f*b.y));}if(!p.closed)q.add(p.p.get(p.p.size()-1).copy());p.p.clear();p.p.addAll(q);}}

    private static void canonicalize(Path p){if(p.p.size()<2)return;if(p.closed){int bi=0;for(int i=1;i<p.p.size();i++){P a=p.p.get(i),b=p.p.get(bi);if(a.x<b.x||(Math.abs(a.x-b.x)<.001f&&a.y<b.y))bi=i;}if(bi>0)Collections.rotate(p.p,-bi);if(p.p.size()>2){P a=p.p.get(1),b=p.p.get(p.p.size()-1);if(a.y>b.y)p.reverse();}}else{P a=p.p.get(0),b=p.p.get(p.p.size()-1);if(a.y>b.y||(Math.abs(a.y-b.y)<.01f&&a.x>b.x))p.reverse();}}
    private static int stableKey(Path p,int grid){int x=clamp(Math.round(p.cx*31/Math.max(1,grid-1)),0,31),y=clamp(Math.round(p.cy*31/Math.max(1,grid-1)),0,31);return morton5(x,y);}
    private static int morton5(int x,int y){int z=0;for(int i=0;i<5;i++){z|=((x>>i)&1)<<(2*i);z|=((y>>i)&1)<<(2*i+1);}return z;}
    private static boolean rejectBorder(Path p,int n,Settings s){if(!s.suppressBorders)return false;float minX=1e9f,minY=1e9f,maxX=-1,maxY=-1;for(P q:p.p){minX=Math.min(minX,q.x);minY=Math.min(minY,q.y);maxX=Math.max(maxX,q.x);maxY=Math.max(maxY,q.y);}float w=maxX-minX,h=maxY-minY;boolean near=minX<n*.035f||minY<n*.035f||maxX>n*.965f||maxY>n*.965f;return near&&w>n*.72f&&h>n*.45f;}
    private static float score(Path p,Field f,Settings s){float center=1f-.22f*clamp01((float)Math.sqrt(Math.pow((p.cx-f.n*.5f)/(f.n*.5f),2)+Math.pow((p.cy-f.n*.5f)/(f.n*.5f),2)));float kind=p.kind==0?1.35f*s.structure:p.kind==1?.92f:.48f*s.tone;return p.importance*kind*center*(float)Math.sqrt(Math.max(1,p.length));}

    private static int[]allocate(ArrayList<Path>p,int total){int n=p.size();int[]a=new int[n];if(n==0)return a;double sum=0;float[]w=new float[n];for(int i=0;i<n;i++){Path q=p.get(i);w[i]=Math.max(.01f,q.importance*q.length*(q.kind==0?1.18f:1f));sum+=w[i];}int used=0;for(int i=0;i<n;i++){int min=p.get(i).kind==2?2:4;a[i]=Math.max(min,(int)Math.round(total*w[i]/Math.max(1e-9,sum)));used+=a[i];}while(used>total){int bi=-1,bv=0;for(int i=0;i<n;i++){int min=p.get(i).kind==2?2:4;if(a[i]>min&&a[i]>bv){bv=a[i];bi=i;}}if(bi<0)break;a[bi]--;used--;}for(int i=0;used<total;i=(i+1)%n){a[i]++;used++;}return a;}
    private static float[]resample(Path p,int n){float[]o=new float[Math.max(0,n)*2];if(n<=0||p.p.size()<2)return o;int segs=p.closed?p.p.size():p.p.size()-1;float[]cum=new float[segs+1];for(int i=0;i<segs;i++)cum[i+1]=cum[i]+dist(p.p.get(i),p.p.get((i+1)%p.p.size()));float total=Math.max(1e-6f,cum[segs]);int si=0;for(int k=0;k<n;k++){float t=total*(p.closed?k/(float)n:k/(float)Math.max(1,n-1));while(si<segs-1&&cum[si+1]<t)si++;float d=Math.max(1e-6f,cum[si+1]-cum[si]),u=(t-cum[si])/d;P a=p.p.get(si),b=p.p.get((si+1)%p.p.size());o[k*2]=a.x+(b.x-a.x)*u;o[k*2+1]=a.y+(b.y-a.y)*u;}return o;}

    private static void normalize(float[]xy,int n,Settings s){float half=(n-1)*.5f,k=1.84f/Math.max(1,n-1);double r=Math.toRadians(s.rotationDeg);float cs=(float)Math.cos(r),sn=(float)Math.sin(r);for(int i=0;i+1<xy.length;i+=2){float x=(xy[i]-half)*k*s.xGain,y=-(xy[i+1]-half)*k*s.yGain;float xr=x*cs-y*sn,yr=x*sn+y*cs;xy[i]=clamp(xr,-.985f,.985f);xy[i+1]=clamp(yr,-.985f,.985f);}}
    private static void solveSeam(float[]xy,int[]rgb,float px,float py){int n=xy.length/2;if(n<4)return;int best=0;if(!Float.isNaN(px)&&!Float.isNaN(py)){float bd=Float.MAX_VALUE;for(int i=0;i<n;i++){float dx=xy[i*2]-px,dy=xy[i*2+1]-py,d=dx*dx+dy*dy;if(d<bd){bd=d;best=i;}}}if(best==0)return;float[]c=xy.clone();int[]cr=rgb.clone();for(int i=0;i<n;i++){int j=(best+i)%n;xy[i*2]=c[j*2];xy[i*2+1]=c[j*2+1];rgb[i]=cr[j];}}
    private static Metrics metrics(float[]xy){Metrics m=new Metrics();double ss=0;int n=xy.length/2;for(int i=1;i<n;i++){float dx=xy[i*2]-xy[(i-1)*2],dy=xy[i*2+1]-xy[(i-1)*2+1],d=(float)Math.sqrt(dx*dx+dy*dy);ss+=d*d;m.peak=Math.max(m.peak,d);if(d>.075f)m.fly++;}m.rms=(float)Math.sqrt(ss/Math.max(1,n-1));return m;}
    private static float structureScore(Field f,float[]xy,int grid){double s=0;float half=(grid-1)*.5f,inv=half/.92f;for(int i=0;i+1<xy.length;i+=2){int x=clamp(Math.round(half+xy[i]*inv),0,grid-1),y=clamp(Math.round(half-xy[i+1]*inv),0,grid-1);s+=f.grad[y*grid+x];}return clamp01((float)(s/Math.max(1,xy.length/2))*.95f+.20f);}
    private static float lumaScore(Field f,float[]xy,int grid){float[]d=new float[grid*grid];float half=(grid-1)*.5f,inv=half/.92f;for(int i=0;i+1<xy.length;i+=2){int x=clamp(Math.round(half+xy[i]*inv),0,grid-1),y=clamp(Math.round(half-xy[i+1]*inv),0,grid-1);d[y*grid+x]+=1;}d=boxBlur(d,grid,1);float mx=1e-6f;for(float v:d)mx=Math.max(mx,v);double e=0,den=0;for(int i=0;i<d.length;i++){float p=d[i]/mx,t=f.lum[i];e+=Math.abs(t-p);den+=t+.15;}return clamp01(1f-(float)(e/Math.max(1e-6,den)));}

    private static float[]boxBlur(float[]src,int n,int r){if(r<=0)return src.clone();float[]tmp=new float[src.length],out=new float[src.length];for(int y=0;y<n;y++){float sum=0;for(int x=-r;x<=r;x++)sum+=src[y*n+clamp(x,0,n-1)];for(int x=0;x<n;x++){tmp[y*n+x]=sum/(2*r+1);sum-=src[y*n+clamp(x-r,0,n-1)];sum+=src[y*n+clamp(x+r+1,0,n-1)];}}for(int x=0;x<n;x++){float sum=0;for(int y=-r;y<=r;y++)sum+=tmp[clamp(y,0,n-1)*n+x];for(int y=0;y<n;y++){out[y*n+x]=sum/(2*r+1);sum-=tmp[clamp(y-r,0,n-1)*n+x];sum+=tmp[clamp(y+r+1,0,n-1)*n+x];}}return out;}
    private static float dist(P a,P b){float x=a.x-b.x,y=a.y-b.y;return(float)Math.sqrt(x*x+y*y);}private static float clamp(float v,float a,float b){return v<a?a:(v>b?b:v);}private static int clamp(int v,int a,int b){return v<a?a:(v>b?b:v);}private static float clamp01(float v){return clamp(v,0,1);}
}
