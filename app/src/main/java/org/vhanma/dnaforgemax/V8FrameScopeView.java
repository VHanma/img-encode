package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Zero-persistence truth preview. Every setFrame replaces the previous frame completely. */
final class V8FrameScopeView extends View {
    private final Paint grid=new Paint(Paint.ANTI_ALIAS_FLAG),beam=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile V8FrameEngine.Result result; private boolean colorPreview=true; private float intensity=.92f,beamWidth=.78f;

    V8FrameScopeView(Activity c){super(c);setBackgroundColor(Color.BLACK);grid.setStyle(Paint.Style.STROKE);grid.setStrokeWidth(1f);grid.setColor(Color.argb(36,75,175,115));beam.setStyle(Paint.Style.STROKE);beam.setStrokeCap(Paint.Cap.ROUND);beam.setStrokeJoin(Paint.Join.ROUND);text.setColor(Color.argb(225,195,255,220));text.setTextSize(10.5f*getResources().getDisplayMetrics().scaledDensity);}
    void setFrame(V8FrameEngine.Result r){result=r;invalidate();}
    void setColorPreview(boolean v){colorPreview=v;invalidate();}
    void setIntensity(int v){intensity=clamp(v/100f,.10f,1f);invalidate();}
    void setBeamWidth(int v){beamWidth=.42f+1.35f*clamp(v/100f,0,1);invalidate();}
    void clearFrame(){result=null;invalidate();}

    @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();float cx=w*.5f,cy=h*.5f,size=Math.min(w,h)*.465f;drawGrid(c,cx,cy,size);V8FrameEngine.Result r=result;if(r==null||r.xy.length<4)return;draw(r,c,cx,cy,size);String d=String.format(java.util.Locale.US,"%s  %d FPS  %d XY  ERR %.1f%%  FLY %d  RMS %.4f",r.mode,r.fps,r.samples,r.frameError*100f,r.flybacks,r.rmsStep);c.drawText(d,8,h-10,text);}
    private void drawGrid(Canvas c,float cx,float cy,float s){for(int i=-4;i<=4;i++){float x=cx+s*i/4f,y=cy+s*i/4f;c.drawLine(x,cy-s,x,cy+s,grid);c.drawLine(cx-s,y,cx+s,y,grid);}c.drawCircle(cx,cy,s,grid);}
    private void draw(V8FrameEngine.Result r,Canvas c,float cx,float cy,float size){float[]xy=r.xy;int[]rgb=r.rgb;int n=rgb.length,step=Math.max(1,n/36000);float jump2=.085f*.085f;beam.setStrokeWidth(beamWidth);for(int i=step;i<n;i+=step){int a=(i-step)*2,b=i*2;float x0n=xy[a],y0n=xy[a+1],x1n=xy[b],y1n=xy[b+1],dx=x1n-x0n,dy=y1n-y0n,d2=dx*dx+dy*dy;if(d2>jump2)continue;int col=colorPreview?rgb[i]:Color.rgb(135,255,182);int alpha=clamp(Math.round(235*intensity),15,255);beam.setColor(Color.argb(alpha,Color.red(col),Color.green(col),Color.blue(col)));c.drawLine(cx+x0n*size,cy+y0n*size,cx+x1n*size,cy+y1n*size,beam);}}
    private static float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);}private static int clamp(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}
}
