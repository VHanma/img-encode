package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

/** Dual-decay CRT preview for the exact v7 stereo X/Y tile. */
final class V7ScopeView extends View {
    private final Paint grid=new Paint(Paint.ANTI_ALIAS_FLAG),beam=new Paint(Paint.ANTI_ALIAS_FLAG),glow=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG),fadeFast=new Paint(),fadeSlow=new Paint(),composite=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private Bitmap fastLayer,slowLayer;private Canvas fastCanvas,slowCanvas;private volatile float[]trace;private volatile String diagnostics="";private float persistence=.88f,intensity=.86f,bloom=.34f,beamWidth=.72f;
    V7ScopeView(Activity c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);setBackgroundColor(Color.BLACK);grid.setStyle(Paint.Style.STROKE);grid.setStrokeWidth(1);grid.setColor(Color.argb(34,70,165,110));beam.setStyle(Paint.Style.STROKE);beam.setStrokeCap(Paint.Cap.ROUND);beam.setStrokeJoin(Paint.Join.ROUND);beam.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));glow.setStyle(Paint.Style.STROKE);glow.setStrokeCap(Paint.Cap.ROUND);glow.setStrokeJoin(Paint.Join.ROUND);glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));text.setColor(Color.argb(225,190,255,216));text.setTextSize(10.5f*getResources().getDisplayMetrics().scaledDensity);}
    void setResult(V7SceneEngine.Result r){trace=r==null?null:r.xy;if(r!=null)diagnostics=String.format(java.util.Locale.US,"OBJ %d  HARM %d  FLY %d  RMS %.4f",r.objects,r.harmonics,r.flybacks,r.rmsStep);invalidate();}
    void setTrace(float[]xy,String d){trace=xy;diagnostics=d==null?"":d;invalidate();}void setPersistence(int v){persistence=clamp(v/100f,.10f,.998f);}void setIntensity(int v){intensity=clamp(v/100f,.08f,1);}void setBloom(int v){bloom=clamp(v/100f,0,1);}void setBeamWidth(int v){beamWidth=.36f+1.20f*clamp(v/100f,0,1);}void clearPhosphor(){if(fastCanvas!=null)fastCanvas.drawColor(Color.BLACK,PorterDuff.Mode.SRC);if(slowCanvas!=null)slowCanvas.drawColor(Color.BLACK,PorterDuff.Mode.SRC);invalidate();}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){if(w<=0||h<=0)return;fastLayer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);slowLayer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);fastCanvas=new Canvas(fastLayer);slowCanvas=new Canvas(slowLayer);fastCanvas.drawColor(Color.BLACK);slowCanvas.drawColor(Color.BLACK);}
    @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();float cx=w*.5f,cy=h*.5f,size=Math.min(w,h)*.46f;drawGrid(c,cx,cy,size);if(fastCanvas==null||slowCanvas==null)return;int fa=clamp(Math.round((1-persistence)*170),2,112),sa=clamp(Math.round((1-Math.min(.999f,persistence+.08f))*76),1,34);fadeFast.setColor(Color.argb(fa,0,0,0));fadeSlow.setColor(Color.argb(sa,0,0,0));fastCanvas.drawRect(0,0,w,h,fadeFast);slowCanvas.drawRect(0,0,w,h,fadeSlow);float[]xy=trace;if(xy!=null&&xy.length>=4)drawTrace(xy,cx,cy,size);c.drawBitmap(slowLayer,0,0,composite);c.drawBitmap(fastLayer,0,0,composite);if(!diagnostics.isEmpty())c.drawText(diagnostics,9,h-11,text);}
    private void drawGrid(Canvas c,float cx,float cy,float s){for(int i=-4;i<=4;i++){float x=cx+s*i/4f,y=cy+s*i/4f;c.drawLine(x,cy-s,x,cy+s,grid);c.drawLine(cx-s,y,cx+s,y,grid);}c.drawCircle(cx,cy,s,grid);c.drawCircle(cx,cy,s*.5f,grid);}
    private void drawTrace(float[]xy,float cx,float cy,float size){int n=xy.length/2,step=Math.max(1,n/38000);float fly2=.08f*.08f;beam.setShadowLayer(1.5f+bloom*5.5f,0,0,Color.rgb(55,255,135));glow.setShadowLayer(3f+bloom*10f,0,0,Color.rgb(18,215,92));for(int i=step;i<n;i+=step){int a=(i-step)*2,b=i*2;float x0n=xy[a],y0n=xy[a+1],x1n=xy[b],y1n=xy[b+1],dx=x1n-x0n,dy=y1n-y0n,d2=dx*dx+dy*dy,x0=cx+x0n*size,y0=cy+y0n*size,x1=cx+x1n*size,y1=cy+y1n*size;if(d2>fly2){beam.setColor(Color.argb(Math.max(1,Math.round(6*intensity)),100,255,155));beam.setStrokeWidth(.4f);fastCanvas.drawPoint(x1,y1,beam);continue;}float speed=(float)Math.sqrt(Math.max(1e-9,d2)),dwell=clamp(.0042f/speed,.14f,2.2f);int ba=clamp(Math.round((32+104*dwell)*intensity),5,230),ga=clamp(Math.round((6+34*dwell)*intensity),2,80);float bw=clamp(beamWidth+.14f*dwell,.45f,2.05f);beam.setColor(Color.argb(ba,132,255,182));glow.setColor(Color.argb(ga,52,242,118));beam.setStrokeWidth(bw);glow.setStrokeWidth(bw+.68f+bloom*1.30f);fastCanvas.drawLine(x0,y0,x1,y1,beam);slowCanvas.drawLine(x0,y0,x1,y1,glow);}}
    private static float clamp(float v,float a,float b){return v<a?a:(v>b?b:v);}private static int clamp(int v,int a,int b){return v<a?a:(v>b?b:v);}
}
