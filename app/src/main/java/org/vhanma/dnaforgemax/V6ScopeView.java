package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

/** Dual-decay software CRT that draws the exact v6 XY trajectory. */
final class V6ScopeView extends View {
    private final Paint grid=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beam=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadeFast=new Paint(),fadeSlow=new Paint();
    private final Paint composite=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private Bitmap fastLayer,slowLayer; private Canvas fastCanvas,slowCanvas;
    private volatile float[] trace; private volatile String diagnostics="";
    private float persistence=.90f,intensity=.88f,bloom=.42f,beamWidth=.82f;

    V6ScopeView(Activity context){
        super(context);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setBackgroundColor(Color.BLACK);
        grid.setStyle(Paint.Style.STROKE);grid.setStrokeWidth(1f);grid.setColor(Color.argb(38,72,172,112));
        beam.setStyle(Paint.Style.STROKE);beam.setStrokeCap(Paint.Cap.ROUND);beam.setStrokeJoin(Paint.Join.ROUND);
        beam.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        glow.setStyle(Paint.Style.STROKE);glow.setStrokeCap(Paint.Cap.ROUND);glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        text.setColor(Color.argb(220,190,255,216));text.setTextSize(11f*getResources().getDisplayMetrics().scaledDensity);
    }

    void setResult(V6Engine.Result r){
        trace=r==null?null:r.xy;
        if(r!=null) diagnostics=String.format(java.util.Locale.US,"FIT %.0f%%  PATH %d  LOOP %d  FLY %d  RMS %.4f",r.fit*100f,r.paths,r.residualLoops,r.flybacks,r.rmsStep);
        invalidate();
    }
    void setTrace(float[]xy,String d){trace=xy;diagnostics=d==null?"":d;invalidate();}
    void setPersistence(int v){persistence=clamp(v/100f,.10f,.997f);}void setIntensity(int v){intensity=clamp(v/100f,.08f,1f);}void setBloom(int v){bloom=clamp(v/100f,0,1);}void setBeamWidth(int v){beamWidth=.38f+1.35f*clamp(v/100f,0,1);}
    void clearPhosphor(){if(fastCanvas!=null)fastCanvas.drawColor(Color.BLACK,PorterDuff.Mode.SRC);if(slowCanvas!=null)slowCanvas.drawColor(Color.BLACK,PorterDuff.Mode.SRC);invalidate();}

    @Override protected void onSizeChanged(int w,int h,int ow,int oh){if(w<=0||h<=0)return;fastLayer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);slowLayer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);fastCanvas=new Canvas(fastLayer);slowCanvas=new Canvas(slowLayer);fastCanvas.drawColor(Color.BLACK);slowCanvas.drawColor(Color.BLACK);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();float cx=w*.5f,cy=h*.5f,size=Math.min(w,h)*.46f;drawGrid(c,cx,cy,size);if(fastCanvas==null||slowCanvas==null)return;
        int fa=clamp(Math.round((1-persistence)*175),2,118),sa=clamp(Math.round((1-Math.min(.999f,persistence+.075f))*82),1,36);fadeFast.setColor(Color.argb(fa,0,0,0));fadeSlow.setColor(Color.argb(sa,0,0,0));fastCanvas.drawRect(0,0,w,h,fadeFast);slowCanvas.drawRect(0,0,w,h,fadeSlow);
        float[]xy=trace;if(xy!=null&&xy.length>=4)drawTrace(xy,cx,cy,size);c.drawBitmap(slowLayer,0,0,composite);c.drawBitmap(fastLayer,0,0,composite);if(!diagnostics.isEmpty())c.drawText(diagnostics,9,h-11,text);
    }
    private void drawGrid(Canvas c,float cx,float cy,float s){for(int i=-4;i<=4;i++){float x=cx+s*i/4f,y=cy+s*i/4f;c.drawLine(x,cy-s,x,cy+s,grid);c.drawLine(cx-s,y,cx+s,y,grid);}c.drawCircle(cx,cy,s,grid);c.drawCircle(cx,cy,s*.5f,grid);}
    private void drawTrace(float[]xy,float cx,float cy,float size){int n=xy.length/2,step=Math.max(1,n/42000);float fly2=.075f*.075f;beam.setShadowLayer(1.8f+bloom*6.5f,0,0,Color.rgb(55,255,135));glow.setShadowLayer(3.5f+bloom*12f,0,0,Color.rgb(20,220,95));
        for(int i=step;i<n;i+=step){int a=(i-step)*2,b=i*2;float x0n=xy[a],y0n=xy[a+1],x1n=xy[b],y1n=xy[b+1],dx=x1n-x0n,dy=y1n-y0n,d2=dx*dx+dy*dy;float x0=cx+x0n*size,y0=cy+y0n*size,x1=cx+x1n*size,y1=cy+y1n*size;
            if(d2>fly2){beam.setColor(Color.argb(Math.max(1,Math.round(7*intensity)),100,255,155));beam.setStrokeWidth(.45f);fastCanvas.drawPoint(x1,y1,beam);continue;}
            float speed=(float)Math.sqrt(Math.max(1e-9,d2));float dwell=clamp(.0044f/speed,.16f,2.35f);int ba=clamp(Math.round((34+108*dwell)*intensity),5,235),ga=clamp(Math.round((7+36*dwell)*intensity),2,84);float bw=clamp(beamWidth+.16f*dwell,.48f,2.25f);
            beam.setColor(Color.argb(ba,132,255,182));glow.setColor(Color.argb(ga,54,244,120));beam.setStrokeWidth(bw);glow.setStrokeWidth(bw+.75f+bloom*1.45f);fastCanvas.drawLine(x0,y0,x1,y1,beam);slowCanvas.drawLine(x0,y0,x1,y1,glow);
            if(d2<.000008f)fastCanvas.drawCircle(x1,y1,.45f+dwell*.28f,beam);
        }
    }
    private static float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);}private static int clamp(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}
}
