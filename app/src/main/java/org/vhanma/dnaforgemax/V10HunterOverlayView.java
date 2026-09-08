package org.vhanma.dnaforgemax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/** Camera overlay for filtered preview, anomaly heatmap, candidate box and HUD. */
final class V10HunterOverlayView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG),heat=new Paint();
    private volatile V10HunterEngine.Result result;
    private volatile boolean showFiltered=true,showHeatmap=true;
    private volatile float alpha=.82f;

    V10HunterOverlayView(Context c){super(c);setWillNotDraw(false);text.setTextSize(12*c.getResources().getDisplayMetrics().scaledDensity);text.setColor(Color.WHITE);text.setShadowLayer(3,0,0,Color.BLACK);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3*c.getResources().getDisplayMetrics().density);}
    void setResult(V10HunterEngine.Result r){result=r;postInvalidateOnAnimation();}
    void setShowFiltered(boolean v){showFiltered=v;invalidate();}
    void setShowHeatmap(boolean v){showHeatmap=v;invalidate();}
    void setFilterAlpha(float v){alpha=Math.max(0,Math.min(1,v));invalidate();}

    @Override protected void onDraw(Canvas c){super.onDraw(c);V10HunterEngine.Result r=result;if(r==null)return;int w=getWidth(),h=getHeight();
        if(showFiltered&&r.filter!=V10HunterEngine.Filter.NORMAL&&r.filtered!=null){Paint bp=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);bp.setAlpha(Math.round(alpha*255));c.drawBitmap(r.filtered,null,new RectF(0,0,w,h),bp);}
        if(showHeatmap&&r.tileScores!=null){float tw=w/(float)r.tileCols,th=h/(float)r.tileRows;for(int y=0;y<r.tileRows;y++)for(int x=0;x<r.tileCols;x++){float s=r.tileScores[y*r.tileCols+x];if(s<.22f)continue;int a=Math.min(120,Math.round(130*s));heat.setColor(Color.argb(a,255,70+Math.round(130*(1-s)),25));c.drawRect(x*tw,y*th,(x+1)*tw,(y+1)*th,heat);}}
        if(r.region!=null){int col=r.tier>=3?Color.rgb(255,65,45):r.tier>=2?Color.rgb(255,195,45):Color.rgb(95,255,155);p.setColor(col);RectF q=new RectF(r.region.left*w,r.region.top*h,r.region.right*w,r.region.bottom*h);c.drawRect(q,p);}
        String a=String.format(Locale.US,"HUNTER Ω  SCORE %.0f%%  TIER %d  %s  PERSIST %d",r.score*100,r.tier,r.filter.name(),r.persistence);c.drawText(a,10,24,text);
        String b=String.format(Locale.US,"MOT %.0f  STRUCT %.0f  CODE %.0f  BENT %.0f  SENSOR %.0f",r.motion*100,r.structure*100,r.code*100,r.bent*100,r.sensorMismatch*100);c.drawText(b,10,44,text);
        if(r.rectanglePenalty>.08f)c.drawText(String.format(Locale.US,"RECTANGLE REJECT %.0f%%",r.rectanglePenalty*100),10,64,text);
    }
}
