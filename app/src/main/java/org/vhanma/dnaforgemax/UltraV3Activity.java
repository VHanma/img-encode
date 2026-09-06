package org.vhanma.dnaforgemax;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OsciVision Ultra v3 Harmonic Field.
 *
 * Stereo XY remains the image transport: LEFT = X, RIGHT = Y.
 * Harmonic mode does NOT mix extra tones into X/Y. Instead it time-warps beam velocity
 * along the already optimized geometry. The beam visits the same path, but dwell/velocity
 * is shaped by a phase-continuous symbolic harmonic lattice.
 */
public class UltraV3Activity extends Activity {
    private static final int REQ_OPEN = 3101;
    private static final int REQ_SAVE = 3102;
    private static final int KIND_NONE = 0, KIND_IMAGE = 1, KIND_VIDEO = 2, KIND_GIF = 3;

    private ScopeView scope;
    private TextView status, detailLabel, harmonyLabel, gammaLabel, persistenceLabel;
    private TextView xGainLabel, yGainLabel, rotationLabel;
    private Spinner sampleSpinner, fpsSpinner, modeSpinner, bankSpinner;
    private SeekBar detailBar, harmonyBar, gammaBar, persistenceBar, xGainBar, yGainBar, rotationBar;
    private CheckBox temporalBox, invertBox;
    private Button playButton;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean previewQueued = new AtomicBoolean(false);
    private final AudioEngine audio = new AudioEngine();
    private Thread animationThread;

    private Uri sourceUri;
    private int sourceKind = KIND_NONE;
    private Bitmap stillBitmap;
    private Movie gifMovie;
    private byte[] gifBytes;
    private MediaMetadataRetriever retriever;
    private long durationMs;
    private int sourceWidth, sourceHeight;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        status.setText("Load image / GIF / video. Harmonic mode shapes beam timing without adding geometry-bending tones.");
    }

    private View buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad,pad,pad,pad);
        root.setBackgroundColor(Color.rgb(3,6,9));

        TextView title = new TextView(this);
        title.setText("OSCIVISION ULTRA v3");
        title.setTextColor(Color.rgb(180,255,210));
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));

        TextView sub = new TextView(this);
        sub.setText("HARMONIC FULL-FIELD XY SYNTHESIS");
        sub.setTextColor(Color.rgb(120,205,160));
        sub.setTextSize(12f);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0,dp(3),0,dp(8));
        root.addView(sub,new LinearLayout.LayoutParams(-1,-2));

        scope = new ScopeView(this);
        LinearLayout.LayoutParams scopeParams = new LinearLayout.LayoutParams(-1,dp(410));
        scopeParams.setMargins(0,dp(6),0,dp(8));
        root.addView(scope,scopeParams);

        LinearLayout actions = row();
        Button load = button("LOAD MEDIA");
        playButton = button("PLAY XY");
        Button save = button("SAVE 24-BIT WAV");
        actions.addView(load,weight()); actions.addView(playButton,weight()); actions.addView(save,weight());
        root.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        load.setOnClickListener(v -> openMedia());
        playButton.setOnClickListener(v -> togglePlayback());
        save.setOnClickListener(v -> chooseSave());

        root.addView(label("OUTPUT SAMPLE RATE"));
        sampleSpinner = spinner(new String[]{"192000 Hz · maximum density","96000 Hz","48000 Hz"},0);
        root.addView(sampleSpinner,new LinearLayout.LayoutParams(-1,-2));

        root.addView(label("FULL-FIELD FRAME RATE"));
        fpsSpinner = spinner(new String[]{"12 fps · extreme still detail","15 fps · high detail","24 fps","30 fps · video balance","60 fps · motion priority"},1);
        root.addView(fpsSpinner,new LinearLayout.LayoutParams(-1,-2));

        root.addView(label("IMAGE COMPILER"));
        modeSpinner = spinner(new String[]{"PERCEPTUAL PHOTO + MICRODETAIL","PHOTO DENSITY","EDGE / LINE ART"},0);
        root.addView(modeSpinner,new LinearLayout.LayoutParams(-1,-2));

        detailLabel = label("DETAIL / PATH OPTIMIZATION: 97%");
        root.addView(detailLabel);
        detailBar = new SeekBar(this); detailBar.setMax(100); detailBar.setProgress(97);
        detailBar.setOnSeekBarChangeListener(change((bar,v)->{ detailLabel.setText("DETAIL / PATH OPTIMIZATION: "+v+"%"); queuePreview(); }));
        root.addView(detailBar,new LinearLayout.LayoutParams(-1,-2));

        TextView harmonicHead = label("HARMONIC VELOCITY LATTICE");
        harmonicHead.setTextColor(Color.rgb(165,245,190));
        root.addView(harmonicHead);
        bankSpinner = spinner(new String[]{
                "81 LATTICE · 81 / 121.5 / 162 / 243 / 324 / 486 / 729",
                "BAGUA 64 · 64 / 80 / 96 / 128 / 160 / 192 / 256 / 320 / 384 / 512",
                "SEVENFOLD 49 · 49 / 73.5 / 98 / 122.5 / 147 / 196 / 245 / 343 / 490 / 686",
                "RAW XY · harmonic timing off"
        },0);
        root.addView(bankSpinner,new LinearLayout.LayoutParams(-1,-2));

        harmonyLabel = label("HARMONY ↔ FIDELITY: 42% harmonic timing");
        root.addView(harmonyLabel);
        harmonyBar = new SeekBar(this); harmonyBar.setMax(100); harmonyBar.setProgress(42);
        harmonyBar.setOnSeekBarChangeListener(change((bar,v)->{ harmonyLabel.setText("HARMONY ↔ FIDELITY: "+v+"% harmonic timing"); queuePreview(); }));
        root.addView(harmonyBar,new LinearLayout.LayoutParams(-1,-2));

        gammaLabel = label("PHOSPHOR / LUMA GAMMA: 1.00");
        root.addView(gammaLabel);
        gammaBar = new SeekBar(this); gammaBar.setMax(180); gammaBar.setProgress(60);
        gammaBar.setOnSeekBarChangeListener(change((bar,v)->{ gammaLabel.setText(String.format(Locale.US,"PHOSPHOR / LUMA GAMMA: %.2f",gamma())); queuePreview(); }));
        root.addView(gammaBar,new LinearLayout.LayoutParams(-1,-2));

        temporalBox = new CheckBox(this); temporalBox.setText("Temporal lock for GIF / video"); temporalBox.setTextColor(Color.rgb(205,225,215)); temporalBox.setChecked(true);
        root.addView(temporalBox,new LinearLayout.LayoutParams(-1,-2));
        invertBox = new CheckBox(this); invertBox.setText("Invert luminance polarity"); invertBox.setTextColor(Color.rgb(205,225,215)); invertBox.setOnCheckedChangeListener((b,v)->queuePreview());
        root.addView(invertBox,new LinearLayout.LayoutParams(-1,-2));

        persistenceLabel = label("SOFTWARE PHOSPHOR PERSISTENCE: 76%");
        root.addView(persistenceLabel);
        persistenceBar = new SeekBar(this); persistenceBar.setMax(100); persistenceBar.setProgress(76);
        persistenceBar.setOnSeekBarChangeListener(change((bar,v)->{ persistenceLabel.setText("SOFTWARE PHOSPHOR PERSISTENCE: "+v+"%"); scope.setPersistence(v); }));
        root.addView(persistenceBar,new LinearLayout.LayoutParams(-1,-2));

        TextView cal = label("REAL-SCOPE GEOMETRY CALIBRATION"); cal.setTextColor(Color.rgb(150,240,180)); root.addView(cal);
        xGainLabel = label("X GAIN: 100%"); root.addView(xGainLabel); xGainBar = gainBar(xGainLabel,"X GAIN"); root.addView(xGainBar,new LinearLayout.LayoutParams(-1,-2));
        yGainLabel = label("Y GAIN: 100%"); root.addView(yGainLabel); yGainBar = gainBar(yGainLabel,"Y GAIN"); root.addView(yGainBar,new LinearLayout.LayoutParams(-1,-2));
        rotationLabel = label("ROTATION: 0.0°"); root.addView(rotationLabel);
        rotationBar = new SeekBar(this); rotationBar.setMax(200); rotationBar.setProgress(100);
        rotationBar.setOnSeekBarChangeListener(change((bar,v)->{ rotationLabel.setText(String.format(Locale.US,"ROTATION: %.1f°",rotation())); queuePreview(); }));
        root.addView(rotationBar,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout tests = row();
        Button grid = button("CAL GRID"), circle = button("CAL CIRCLE"); tests.addView(grid,weight()); tests.addView(circle,weight()); root.addView(tests,new LinearLayout.LayoutParams(-1,-2));
        grid.setOnClickListener(v->showTest(false)); circle.setOnClickListener(v->showTest(true));

        TextView note = new TextView(this);
        note.setText("LEFT = X   •   RIGHT = Y   •   no raster sweep\nHarmonic mode changes traversal velocity and dwell along the existing path. It does not mix a separate musical tone into X/Y. Still images use a phase-closing 2-second super-loop.");
        note.setTextColor(Color.rgb(135,160,150)); note.setTextSize(12f); note.setPadding(0,dp(7),0,dp(7)); root.addView(note,new LinearLayout.LayoutParams(-1,-2));

        status = new TextView(this); status.setTextColor(Color.rgb(190,255,210)); status.setTextSize(13f); status.setPadding(0,dp(4),0,dp(18)); root.addView(status,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll = new ScrollView(this); scroll.addView(root); return scroll;
    }

    private interface Ch { void f(SeekBar b,int v); }
    private SeekBar.OnSeekBarChangeListener change(Ch c) { return new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar b,int v,boolean u){ if(u)c.f(b,v);} public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} }; }
    private SeekBar gainBar(TextView t,String prefix){ SeekBar b=new SeekBar(this); b.setMax(80); b.setProgress(40); b.setOnSeekBarChangeListener(change((bar,v)->{ t.setText(prefix+": "+(60+v)+"%"); queuePreview(); })); return b; }
    private LinearLayout row(){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setPadding(0,0,0,dp(7)); return l; }
    private LinearLayout.LayoutParams weight(){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1f); p.setMargins(dp(3),0,dp(3),0); return p; }
    private Button button(String s){ Button b=new Button(this); b.setText(s); b.setTextSize(11f); return b; }
    private TextView label(String s){ TextView t=new TextView(this); t.setText(s); t.setTextColor(Color.rgb(190,215,205)); t.setTextSize(12f); t.setPadding(0,dp(6),0,dp(2)); return t; }
    private Spinner spinner(String[] a,int selected){ Spinner s=new Spinner(this); ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,a); ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); s.setAdapter(ad); s.setSelection(selected); return s; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }

    private void openMedia(){ stopPlayback(); Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"}); startActivityForResult(i,REQ_OPEN); }
    private void chooseSave(){ if(sourceKind==KIND_NONE){ toast("Load media first."); return;} stopPlayback(); Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/wav"); i.putExtra(Intent.EXTRA_TITLE,"oscivision_v3_harmonic_xy_"+System.currentTimeMillis()+".wav"); startActivityForResult(i,REQ_SAVE); }

    @Override protected void onActivityResult(int req,int result,Intent data){ super.onActivityResult(req,result,data); if(result!=RESULT_OK||data==null||data.getData()==null)return; Uri u=data.getData(); if(req==REQ_OPEN){ try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){} loadSource(u);} else if(req==REQ_SAVE) export(u); }

    private void loadSource(Uri u){ sourceUri=u; status.setText("Decoding media…"); worker.submit(()->{ try{ releaseSource(); ContentResolver cr=getContentResolver(); String mime=cr.getType(u); String lower=u.toString().toLowerCase(Locale.US);
        if(mime!=null&&mime.startsWith("video/")){ sourceKind=KIND_VIDEO; retriever=new MediaMetadataRetriever(); retriever.setDataSource(this,u); durationMs=parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),1000); sourceWidth=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),640); sourceHeight=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),480); Bitmap f=getVideoFrame(0); if(f==null)throw new IllegalStateException("Video decoder returned no frame"); runOnUiThread(()->{ compilePreview(f,0); status.setText(String.format(Locale.US,"VIDEO · %.2fs · %dx%d · v3 harmonic field ready",durationMs/1000.0,sourceWidth,sourceHeight));}); }
        else if((mime!=null&&mime.equals("image/gif"))||lower.endsWith(".gif")){ sourceKind=KIND_GIF; gifBytes=readAll(cr.openInputStream(u)); gifMovie=Movie.decodeByteArray(gifBytes,0,gifBytes.length); if(gifMovie==null){ sourceKind=KIND_IMAGE; stillBitmap=decodeBitmap(u); sourceWidth=stillBitmap.getWidth(); sourceHeight=stillBitmap.getHeight(); durationMs=6000; runOnUiThread(()->{compilePreview(stillBitmap,0);status.setText("GIF fallback loaded as still image");}); } else { durationMs=gifMovie.duration()>0?gifMovie.duration():1000; sourceWidth=gifMovie.width(); sourceHeight=gifMovie.height(); Bitmap f=getGifFrame(0); runOnUiThread(()->{compilePreview(f,0); status.setText(String.format(Locale.US,"GIF · %.2fs · %dx%d · harmonic field ready",durationMs/1000.0,sourceWidth,sourceHeight));}); } }
        else { sourceKind=KIND_IMAGE; stillBitmap=decodeBitmap(u); if(stillBitmap==null)throw new IllegalStateException("Could not decode image"); sourceWidth=stillBitmap.getWidth(); sourceHeight=stillBitmap.getHeight(); durationMs=6000; runOnUiThread(()->{compilePreview(stillBitmap,0);status.setText("IMAGE · "+sourceWidth+"×"+sourceHeight+" · harmonic field ready");}); }
    }catch(Throwable t){ runOnUiThread(()->status.setText("Load error: "+safe(t))); }}); }

    private Bitmap decodeBitmap(Uri u)throws Exception{ if(Build.VERSION.SDK_INT>=28){ ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),u); return ImageDecoder.decodeBitmap(src,(d,info,s)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)); } try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(u))){ return BitmapFactory.decodeStream(in);} }

    private Settings settings(){ Settings s=new Settings(); s.sampleRate=sampleRate(); s.fps=fps(); s.mode=modeSpinner==null?0:modeSpinner.getSelectedItemPosition(); s.quality=detailBar==null?97:detailBar.getProgress(); s.bank=bankSpinner==null?0:bankSpinner.getSelectedItemPosition(); s.harmony=harmonyBar==null?0.42f:harmonyBar.getProgress()/100f; if(s.bank==3)s.harmony=0f; s.gamma=gamma(); s.temporal=temporalBox==null||temporalBox.isChecked(); s.invert=invertBox!=null&&invertBox.isChecked(); s.xGain=xGainBar==null?1f:(60f+xGainBar.getProgress())/100f; s.yGain=yGainBar==null?1f:(60f+yGainBar.getProgress())/100f; s.rotation=rotation(); return s; }
    private int sampleRate(){ if(sampleSpinner==null)return 192000; int p=sampleSpinner.getSelectedItemPosition(); return p==1?96000:(p==2?48000:192000); }
    private int fps(){ if(fpsSpinner==null)return 15; switch(fpsSpinner.getSelectedItemPosition()){case 0:return 12;case 1:return 15;case 2:return 24;case 3:return 30;default:return 60;} }
    private float gamma(){ return gammaBar==null?1f:0.40f+gammaBar.getProgress()/100f; }
    private float rotation(){ return rotationBar==null?0f:(rotationBar.getProgress()-100)/10f; }

    private void queuePreview(){ if(sourceKind!=KIND_IMAGE||stillBitmap==null||playing.get())return; if(!previewQueued.compareAndSet(false,true))return; worker.submit(()->{ try{SystemClock.sleep(40); Settings s=settings(); float[] raw=VectorEngine.compile(stillBitmap,s,0); float[] h=HarmonicEngine.apply(raw,s,0); runOnUiThread(()->scope.setTrace(h));}catch(Throwable ignored){}finally{previewQueued.set(false);} }); }
    private void compilePreview(Bitmap b,long frame){ if(b==null)return; Settings s=settings(); worker.submit(()->{ try{float[] raw=VectorEngine.compile(b,s,frame);float[] h=HarmonicEngine.apply(raw,s,frame);runOnUiThread(()->scope.setTrace(h));}catch(Throwable t){runOnUiThread(()->status.setText("Compile error: "+safe(t)));} }); }

    private void togglePlayback(){ if(sourceKind==KIND_NONE){toast("Load media first.");return;} if(playing.get())stopPlayback(); else startPlayback(); }
    private void startPlayback(){ stopPlayback(); Settings requested=settings(); int rate; try{rate=audio.start(requested.sampleRate);}catch(Throwable t){status.setText("Audio start error: "+safe(t));return;} Settings s=requested.copy(); s.sampleRate=rate; playing.set(true); playButton.setText("STOP"); status.setText("LIVE HARMONIC XY · "+rate+" Hz · "+s.fps+" fps · bank "+bankName(s.bank));
        if(sourceKind==KIND_IMAGE){ worker.submit(()->{ try{float[] raw=VectorEngine.compile(stillBitmap,s,0);float[] one=HarmonicEngine.apply(raw,s,0);float[] loop=HarmonicEngine.makeTwoSecondLoop(raw,s); if(!playing.get())return; audio.setFrame(loop); runOnUiThread(()->scope.setTrace(one));}catch(Throwable t){runOnUiThread(()->status.setText("Compile error: "+safe(t)));} }); return; }
        animationThread=new Thread(()->{ long start=SystemClock.elapsedRealtime(); long fi=0; long period=Math.max(1,Math.round(1000.0/s.fps)); while(playing.get()){ long target=fi*period, elapsed=SystemClock.elapsedRealtime()-start; if(elapsed<target){SystemClock.sleep(Math.min(5,target-elapsed));continue;} if(elapsed-target>period*2)fi=Math.max(fi,elapsed/period); long mt=durationMs>0?(fi*period)%durationMs:fi*period; try{Bitmap f=sourceKind==KIND_VIDEO?getVideoFrame(mt):getGifFrame(mt); if(f!=null){float[] raw=VectorEngine.compile(f,s,fi);float[] h=HarmonicEngine.apply(raw,s,fi);audio.setFrame(h);runOnUiThread(()->scope.setTrace(h));}}catch(Throwable t){runOnUiThread(()->status.setText("Frame error: "+safe(t)));} fi++; if(fi>100000000){fi=0;start=SystemClock.elapsedRealtime();} } },"OsciVisionV3Video"); animationThread.setPriority(Thread.NORM_PRIORITY+1); animationThread.start(); }
    private void stopPlayback(){ playing.set(false); if(animationThread!=null){animationThread.interrupt();animationThread=null;} audio.stop(); if(playButton!=null)playButton.setText("PLAY XY"); }

    private Bitmap getVideoFrame(long ms){ if(retriever==null)return null; long us=Math.max(0,ms)*1000; try{ if(Build.VERSION.SDK_INT>=27){int max=720,w=sourceWidth<=0?max:sourceWidth,h=sourceHeight<=0?max:sourceHeight;float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap b=retriever.getScaledFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST,Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)));if(b!=null)return b;}}catch(Throwable ignored){} return retriever.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST); }
    private Bitmap getGifFrame(long ms){ if(gifMovie==null)return null; int max=720,w=Math.max(1,gifMovie.width()),h=Math.max(1,gifMovie.height());float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap out=Bitmap.createBitmap(Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.BLACK);c.scale(sc,sc);synchronized(this){gifMovie.setTime((int)(ms%Math.max(1,durationMs)));gifMovie.draw(c,0,0);}return out; }

    private void export(Uri dest){ if(sourceKind==KIND_NONE)return; status.setText("Rendering harmonic 24-bit XY WAV…"); Settings s=settings(); worker.submit(()->{ File tmp=new File(getCacheDir(),"oscivision_v3_"+System.currentTimeMillis()+".wav"); try{long dur=sourceKind==KIND_IMAGE?6000:Math.max(1,durationMs);int frames=Math.max(1,(int)Math.ceil(dur*s.fps/1000.0));try(Wav24 w=new Wav24(tmp,s.sampleRate)){for(int i=0;i<frames;i++){long t=Math.min(dur-1,Math.round(i*1000.0/s.fps));Bitmap f=sourceKind==KIND_IMAGE?stillBitmap:(sourceKind==KIND_VIDEO?getVideoFrame(t):getGifFrame(t));if(f==null)continue;float[] raw=VectorEngine.compile(f,s,i);w.write(HarmonicEngine.apply(raw,s,i));if(i%Math.max(1,s.fps/2)==0){final int pct=Math.min(99,Math.round(100f*i/frames));runOnUiThread(()->status.setText("Rendering harmonic WAV… "+pct+"%"));}}}try(InputStream in=new BufferedInputStream(new FileInputStream(tmp));OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(dest,"w"))){byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}runOnUiThread(()->status.setText("Saved · 24-bit stereo XY · "+s.sampleRate+" Hz · "+bankName(s.bank)));}catch(Throwable t){runOnUiThread(()->status.setText("Export error: "+safe(t)));}finally{try{if(tmp.exists())tmp.delete();}catch(Throwable ignored){}} }); }

    private void showTest(boolean circle){ Settings s=settings(); worker.submit(()->{float[] raw=circle?VectorEngine.circle(s):VectorEngine.grid(s);float[] h=HarmonicEngine.apply(raw,s,0);runOnUiThread(()->{scope.setTrace(h);status.setText(circle?"CAL CIRCLE · tune X/Y gain until round":"CAL GRID · tune gain/rotation until square");});if(playing.get())audio.setFrame(HarmonicEngine.makeTwoSecondLoop(raw,s));}); }

    private void releaseSource(){stillBitmap=null;gifMovie=null;gifBytes=null;durationMs=0;sourceWidth=sourceHeight=0;if(retriever!=null){try{retriever.release();}catch(Throwable ignored){}retriever=null;}}
    @Override protected void onDestroy(){stopPlayback();releaseSource();worker.shutdownNow();super.onDestroy();}
    private static byte[] readAll(InputStream in)throws Exception{if(in==null)return new byte[0];try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];int n;while((n=x.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private static long parseLong(String s,long f){try{return Long.parseLong(s);}catch(Throwable t){return f;}}
    private static int parseInt(String s,int f){try{return Integer.parseInt(s);}catch(Throwable t){return f;}}
    private static String safe(Throwable t){if(t==null)return"unknown";String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private static String bankName(int b){return b==1?"Bagua 64":(b==2?"Sevenfold 49":(b==3?"Raw XY":"81 Lattice"));}

    private static final class Settings{
        int sampleRate=192000,fps=15,quality=97,mode=0,bank=0; boolean temporal=true,invert; float harmony=.42f,gamma=1f,xGain=1f,yGain=1f,rotation;
        Settings copy(){Settings s=new Settings();s.sampleRate=sampleRate;s.fps=fps;s.quality=quality;s.mode=mode;s.bank=bank;s.temporal=temporal;s.invert=invert;s.harmony=harmony;s.gamma=gamma;s.xGain=xGain;s.yGain=yGain;s.rotation=rotation;return s;}
    }

    private static final class VectorEngine{
        private static final double GOLD=0.6180339887498948;
        private static final class P{float x,y;int h;P(float x,float y,int h){this.x=x;this.y=y;this.h=h;}}
        private static final class G{final float[] gx,gy,m;G(float[]x,float[]y,float[]m){gx=x;gy=y;this.m=m;}}
        static float[] compile(Bitmap input,Settings s,long frame){if(input==null)return new float[]{0,0,0,0};int rate=clamp(s.sampleRate,8000,192000),fps=clamp(s.fps,5,120),budget=clamp(Math.round(rate/(float)fps),800,24000);int grid=clamp(120+Math.round(s.quality*2.0f),120,320),side=nextPow2(grid),bits=Integer.numberOfTrailingZeros(side);
            Bitmap sq=Bitmap.createBitmap(grid,grid,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(sq);c.drawColor(Color.BLACK);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);float scale=Math.min(grid/(float)Math.max(1,input.getWidth()),grid/(float)Math.max(1,input.getHeight()));float dw=input.getWidth()*scale,dh=input.getHeight()*scale,left=(grid-dw)/2f,top=(grid-dh)/2f;c.drawBitmap(input,null,new android.graphics.RectF(left,top,left+dw,top+dh),p);
            int[] px=new int[grid*grid];sq.getPixels(px,0,grid,0,0,grid,grid);float[] lum=new float[px.length];for(int i=0;i<px.length;i++){int col=px[i];float l=(.2126f*Color.red(col)+.7152f*Color.green(col)+.0722f*Color.blue(col))/255f;if(s.invert)l=1-l;lum[i]=(float)Math.pow(clamp01(l),Math.max(.25f,s.gamma));}
            G g=gradient(lum,grid,grid);float[] d=detail(lum,grid,grid),photo=new float[lum.length],edge=new float[lum.length],hybrid=new float[lum.length];for(int i=0;i<lum.length;i++){photo[i]=lum[i];edge[i]=(float)Math.pow(g.m[i],.70);hybrid[i]=clamp01(lum[i]*.78f+edge[i]*.39f+d[i]*.20f);}
            List<P> pts=new ArrayList<>(budget);long seed=s.temporal?0:frame;if(s.mode==2)sample(edge,g,grid,budget,bits,pts,true,seed);else if(s.mode==1){int eq=Math.round(budget*(.09f+s.quality/1100f));sample(photo,g,grid,budget-eq,bits,pts,false,seed);sample(edge,g,grid,eq,bits,pts,true,seed+31);}else{int eq=Math.round(budget*(.21f+.10f*s.quality/100f));sample(hybrid,g,grid,budget-eq,bits,pts,false,seed);sample(edge,g,grid,eq,bits,pts,true,seed+31);}if(pts.size()<2)return new float[]{0,0,0,0};Collections.sort(pts,Comparator.comparingInt(a->a.h));localNearest(pts,s.quality>=90?20:(s.quality>=65?13:8));rotateGap(pts);
            float[] out=new float[pts.size()*2];double rad=Math.toRadians(s.rotation);float cs=(float)Math.cos(rad),sn=(float)Math.sin(rad),norm=1.84f/Math.max(1f,grid-1f),half=(grid-1)*.5f;int o=0;for(P q:pts){float x=(q.x-half)*norm*s.xGain,y=-(q.y-half)*norm*s.yGain;float xr=x*cs-y*sn,yr=x*sn+y*cs;out[o++]=clamp(xr,-.985f,.985f);out[o++]=clamp(yr,-.985f,.985f);}return out;}
        private static void sample(float[] w,G g,int grid,int count,int bits,List<P> out,boolean edgeMode,long seed){if(count<=0)return;double[] cdf=new double[w.length];double total=0;for(int i=0;i<w.length;i++){total+=Math.max(0,w[i]);cdf[i]=total;}if(total<=1e-12)return;double rot=fract(seed*.7548776662466927+.1732050807568877);for(int k=0;k<count;k++){double u=fract((k+.5)*GOLD+rot)*total;int idx=lower(cdf,u),x=idx%grid,y=idx/grid;double hx=halton(k+1+(int)(seed&127),2)-.5,hy=halton(k+1+(int)((seed*3)&127),3)-.5;float jx,jy;if(edgeMode&&g.m[idx]>.05f){float gx=g.gx[idx],gy=g.gy[idx],inv=1f/Math.max(1e-6f,(float)Math.sqrt(gx*gx+gy*gy));float tx=-gy*inv,ty=gx*inv,along=(float)hx*.9f,across=(float)hy*.14f;jx=tx*along+gx*inv*across;jy=ty*along+gy*inv*across;}else{jx=(float)hx*.9f;jy=(float)hy*.9f;}float px=clamp(x+jx,0,grid-1),py=clamp(y+jy,0,grid-1);out.add(new P(px,py,hilbert(clamp(Math.round(px),0,grid-1),clamp(Math.round(py),0,grid-1),bits)));}}
        private static void localNearest(List<P> p,int window){for(int i=0;i<p.size()-2;i++){P cur=p.get(i);int best=i+1;float bd=dist(cur,p.get(best));int end=Math.min(p.size(),i+1+window);for(int j=i+2;j<end;j++){float d=dist(cur,p.get(j));if(d<bd){bd=d;best=j;}}if(best!=i+1)Collections.swap(p,i+1,best);}}
        private static void rotateGap(List<P> p){int n=p.size(),at=n-1;float max=dist(p.get(n-1),p.get(0));for(int i=0;i<n-1;i++){float d=dist(p.get(i),p.get(i+1));if(d>max){max=d;at=i;}}if(at!=n-1)Collections.rotate(p,-(at+1));}
        private static float dist(P a,P b){float x=a.x-b.x,y=a.y-b.y;return x*x+y*y;}
        private static G gradient(float[] a,int w,int h){float[] gx=new float[a.length],gy=new float[a.length],m=new float[a.length];float max=1e-7f;for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int i=y*w+x;float dx=-a[i-w-1]+a[i-w+1]-2*a[i-1]+2*a[i+1]-a[i+w-1]+a[i+w+1];float dy=-a[i-w-1]-2*a[i-w]-a[i-w+1]+a[i+w-1]+2*a[i+w]+a[i+w+1];float mm=(float)Math.sqrt(dx*dx+dy*dy);gx[i]=dx;gy[i]=dy;m[i]=mm;if(mm>max)max=mm;}float inv=1/max;for(int i=0;i<m.length;i++){gx[i]*=inv;gy[i]*=inv;m[i]=clamp01(m[i]*inv);}return new G(gx,gy,m);}
        private static float[] detail(float[] a,int w,int h){float[] d=new float[a.length];float max=1e-7f;for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int i=y*w+x;float v=Math.abs(a[i]*4-a[i-1]-a[i+1]-a[i-w]-a[i+w]);d[i]=v;if(v>max)max=v;}float inv=1/max;for(int i=0;i<d.length;i++)d[i]=clamp01(d[i]*inv);return d;}
        static float[] circle(Settings s){int n=clamp(s.sampleRate/s.fps,800,24000);float[] o=new float[n*2];double r=Math.toRadians(s.rotation);float cs=(float)Math.cos(r),sn=(float)Math.sin(r);for(int i=0;i<n;i++){double a=2*Math.PI*i/n;float x=(float)Math.cos(a)*.82f*s.xGain,y=(float)Math.sin(a)*.82f*s.yGain;o[i*2]=clamp(x*cs-y*sn,-.985f,.985f);o[i*2+1]=clamp(x*sn+y*cs,-.985f,.985f);}return o;}
        static float[] grid(Settings s){int n=clamp(s.sampleRate/s.fps,800,24000);float[] o=new float[n*2];int lines=10,idx=0;for(int i=0;i<n;i++){float t=i/(float)Math.max(1,n-1),x,y;int seg=(int)(t*lines*2);float u=(t*lines*2)-seg;if((seg&1)==0){float yy=-.8f+1.6f*((seg/2f)/(lines-1));x=-.8f+1.6f*u;y=yy;}else{float xx=-.8f+1.6f*((seg/2f)/(lines-1));x=xx;y=-.8f+1.6f*u;}double r=Math.toRadians(s.rotation);float cs=(float)Math.cos(r),sn=(float)Math.sin(r);x*=s.xGain;y*=s.yGain;o[idx++]=clamp(x*cs-y*sn,-.985f,.985f);o[idx++]=clamp(x*sn+y*cs,-.985f,.985f);}return o;}
        private static int lower(double[]a,double v){int lo=0,hi=a.length-1;while(lo<hi){int m=(lo+hi)>>>1;if(a[m]<v)lo=m+1;else hi=m;}return lo;}
        private static double halton(int index,int base){double f=1,r=0;int i=Math.max(1,index);while(i>0){f/=base;r+=f*(i%base);i/=base;}return r;}
        private static double fract(double x){return x-Math.floor(x);}private static int nextPow2(int n){int p=1;while(p<n)p<<=1;return p;}
        private static int hilbert(int x,int y,int bits){int index=0,n=1<<bits,xx=x,yy=y;for(int s=n>>1;s>0;s>>=1){int rx=(xx&s)>0?1:0,ry=(yy&s)>0?1:0;index+=s*s*((3*rx)^ry);if(ry==0){if(rx==1){xx=n-1-xx;yy=n-1-yy;}int t=xx;xx=yy;yy=t;}}return index;}
    }

    private static final class HarmonicEngine{
        private static final double[][] BANKS={
                {81,121.5,162,243,324,486,729},
                {64,80,96,128,160,192,256,320,384,512},
                {49,73.5,98,122.5,147,196,245,343,490,686}
        };
        static float[] apply(float[] xy,Settings s,long frameIndex){if(xy==null||xy.length<8||s.harmony<=.001f||s.bank<0||s.bank>=BANKS.length)return xy;int n=xy.length/2;if(n<4)return xy;double[] cum=new double[n];cum[0]=0;double t0=frameIndex*n/(double)Math.max(1,s.sampleRate);double strength=Math.min(.86,Math.max(0,s.harmony)*.72);double[] bank=BANKS[s.bank];double wsum=0;for(int k=0;k<bank.length;k++)wsum+=1.0/(1.0+k*.42);for(int i=1;i<n;i++){double t=t0+i/(double)s.sampleRate;double m=0;for(int k=0;k<bank.length;k++){double w=1.0/(1.0+k*.42);double phase=(k*2.399963229728653)+(frameIndex*.03125);m+=w*Math.sin(2*Math.PI*bank[k]*t+phase);}m/=Math.max(1e-9,wsum);double speed=Math.exp(strength*m);cum[i]=cum[i-1]+speed;}
            double total=cum[n-1];float[] out=new float[xy.length];int src=0;for(int j=0;j<n;j++){double target=total*j/(double)Math.max(1,n-1);while(src<n-2&&cum[src+1]<target)src++;double den=Math.max(1e-12,cum[src+1]-cum[src]);float f=(float)((target-cum[src])/den);float x0=xy[src*2],y0=xy[src*2+1],x1=xy[(src+1)*2],y1=xy[(src+1)*2+1];float dx=x1-x0,dy=y1-y0;if(dx*dx+dy*dy>.012f){f=f<.5f?0f:1f;}out[j*2]=x0+(x1-x0)*f;out[j*2+1]=y0+(y1-y0)*f;}return out;}
        static float[] makeTwoSecondLoop(float[] raw,Settings s){if(raw==null)return new float[]{0,0,0,0};int frames=Math.max(2,s.fps*2);int each=raw.length;long total=(long)each*frames;if(total>6_500_000L){frames=Math.max(2,6_500_000/Math.max(1,each));total=(long)each*frames;}float[] out=new float[(int)total];int pos=0;for(int i=0;i<frames;i++){float[] h=apply(raw,s,i);System.arraycopy(h,0,out,pos,h.length);pos+=h.length;}return pos==out.length?out:Arrays.copyOf(out,pos);}
    }

    private static final class AudioEngine{
        private final AtomicReference<float[]> frame=new AtomicReference<>();private final AtomicBoolean running=new AtomicBoolean(false);private AudioTrack track;private Thread thread;private int rate=48000;
        int start(int requested){stop();int[] rates=requested==192000?new int[]{192000,96000,48000}:requested==96000?new int[]{96000,48000}:new int[]{48000};RuntimeException last=null;for(int r:rates){try{int min=AudioTrack.getMinBufferSize(r,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);if(min<=0)min=r/10*8;track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min,r/16*8)).build();if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new RuntimeException("AudioTrack init failed");rate=r;track.play();running.set(true);thread=new Thread(()->{while(running.get()){float[]f=frame.get();if(f==null||f.length<4){SystemClock.sleep(3);continue;}int wrote=track.write(f,0,f.length,AudioTrack.WRITE_BLOCKING);if(wrote<0)SystemClock.sleep(2);}},"OsciVisionV3Audio");thread.setPriority(Thread.MAX_PRIORITY);thread.start();return rate;}catch(RuntimeException e){last=e;if(track!=null){try{track.release();}catch(Throwable ignored){}track=null;}}}if(last!=null)throw last;return rate;}
        void setFrame(float[]f){frame.set(f);}void stop(){running.set(false);if(thread!=null){thread.interrupt();thread=null;}frame.set(null);if(track!=null){try{track.pause();}catch(Throwable ignored){}try{track.flush();}catch(Throwable ignored){}try{track.stop();}catch(Throwable ignored){}try{track.release();}catch(Throwable ignored){}track=null;}}
    }

    private static final class Wav24 implements AutoCloseable{
        private final RandomAccessFile r;private final int sr;private long bytes=0;Wav24(File f,int sr)throws Exception{this.sr=sr;r=new RandomAccessFile(f,"rw");r.setLength(0);header(0);}void write(float[]xy)throws Exception{byte[]b=new byte[Math.min(98304,Math.max(6144,xy.length*3))];int p=0;for(float v:xy){int s=Math.round(Math.max(-1,Math.min(1,v))*8388607f);if(p+3>b.length){r.write(b,0,p);bytes+=p;p=0;}b[p++]=(byte)(s&255);b[p++]=(byte)((s>>8)&255);b[p++]=(byte)((s>>16)&255);}if(p>0){r.write(b,0,p);bytes+=p;}}private void header(long data)throws Exception{r.seek(0);r.writeBytes("RIFF");le32(36+data);r.writeBytes("WAVEfmt ");le32(16);le16(1);le16(2);le32(sr);le32((long)sr*6);le16(6);le16(24);r.writeBytes("data");le32(data);}private void le16(int v)throws Exception{r.write(v&255);r.write((v>>>8)&255);}private void le32(long v)throws Exception{r.write((int)(v&255));r.write((int)((v>>>8)&255));r.write((int)((v>>>16)&255));r.write((int)((v>>>24)&255));}@Override public void close()throws Exception{header(bytes);r.close();}}

    private static final class ScopeView extends View{
        private final Paint grid=new Paint(Paint.ANTI_ALIAS_FLAG),beam=new Paint(Paint.ANTI_ALIAS_FLAG);private Bitmap phosphor;private Canvas pc;private volatile float[] trace;private float persistence=.76f;
        ScopeView(Activity a){super(a);setLayerType(View.LAYER_TYPE_SOFTWARE,null);grid.setStyle(Paint.Style.STROKE);grid.setStrokeWidth(1);grid.setColor(Color.argb(45,90,190,125));beam.setStyle(Paint.Style.FILL);beam.setColor(Color.argb(34,130,255,175));beam.setShadowLayer(4,0,0,Color.rgb(50,255,120));setBackgroundColor(Color.BLACK);}
        void setPersistence(int p){persistence=p/100f;}void setTrace(float[]xy){trace=xy;invalidate();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){if(w>0&&h>0){phosphor=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);pc=new Canvas(phosphor);pc.drawColor(Color.BLACK);}}
        @Override protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();float cx=w*.5f,cy=h*.5f,size=Math.min(w,h)*.47f;for(int i=-4;i<=4;i++){float x=cx+size*i/4f,y=cy+size*i/4f;c.drawLine(x,cy-size,x,cy+size,grid);c.drawLine(cx-size,y,cx+size,y,grid);}c.drawCircle(cx,cy,size,grid);if(pc==null||phosphor==null)return;Paint fade=new Paint();int alpha=Math.max(4,Math.round((1f-persistence)*135f));fade.setColor(Color.argb(alpha,0,0,0));pc.drawRect(0,0,w,h,fade);float[]xy=trace;if(xy!=null){int n=xy.length/2;int step=Math.max(1,n/18000);for(int i=0;i<n;i+=step){float x=cx+xy[i*2]*size,y=cy+xy[i*2+1]*size;pc.drawCircle(x,y,1.15f,beam);}}c.drawBitmap(phosphor,0,0,null);}
    }

    private static float clamp(float v,float lo,float hi){return v<lo?lo:(v>hi?hi:v);}private static int clamp(int v,int lo,int hi){return v<lo?lo:(v>hi?hi:v);}private static float clamp01(float v){return v<0?0:(v>1?1:v);}
}
