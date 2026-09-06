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
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** FRAMEFORGE Ω v8: independent full biosonified frame blocks at 15-60 fps. */
public class UltraV8Activity extends Activity {
    private static final int REQ_OPEN=8101,REQ_SAVE=8102;
    private static final int NONE=0,IMAGE=1,VIDEO=2,GIF=3;
    private V8FrameScopeView scope; private ImageView sourcePreview; private TextView status;
    private Spinner sampleSpinner,modeSpinner,profileSpinner,exportSpinner;
    private SeekBar qualityBar,structureBar,toneBar,colorBar,gammaBar,intensityBar,beamBar,xGainBar,yGainBar,rotationBar;
    private TextView qualityLabel,structureLabel,toneLabel,colorLabel,gammaLabel,intensityLabel,beamLabel,xGainLabel,yGainLabel,rotationLabel;
    private CheckBox borderBox,invertBox,sourceBox,colorPreviewBox; private Button playButton;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing=new AtomicBoolean(false),previewQueued=new AtomicBoolean(false);
    private final V8FrameAudio audio=new V8FrameAudio(); private Thread frameThread; private boolean uiChanging=false;
    private Uri sourceUri; private int sourceKind=NONE; private Bitmap stillBitmap; private Movie gifMovie; private byte[]gifBytes;
    private MediaMetadataRetriever retriever; private long durationMs; private int sourceWidth,sourceHeight;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setContentView(buildUi());status.setText("FRAMEFORGE Ω ready. Every source frame becomes one complete XY audio block, then the preview hard-clears for the next frame.");}

    private View buildUi(){int pad=dp(10);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);root.setBackgroundColor(Color.rgb(1,3,6));
        TextView title=label("FRAMEFORGE Ω v8");title.setTextSize(27);title.setGravity(Gravity.CENTER_HORIZONTAL);title.setTextColor(Color.rgb(205,255,224));root.addView(title);
        TextView sub=label("FULL-FRAME BIOSONIFICATION • HARD FRAME-LOCK • COLOR PHASE SPACE");sub.setTextSize(10.8f);sub.setGravity(Gravity.CENTER_HORIZONTAL);sub.setTextColor(Color.rgb(112,225,166));root.addView(sub);
        sourcePreview=new ImageView(this);sourcePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);sourcePreview.setBackgroundColor(Color.BLACK);sourcePreview.setVisibility(View.GONE);root.addView(sourcePreview,new LinearLayout.LayoutParams(-1,dp(145)));
        scope=new V8FrameScopeView(this);root.addView(scope,new LinearLayout.LayoutParams(-1,dp(455)));
        LinearLayout actions=row();Button load=button("LOAD");playButton=button("PLAY FRAME-LOCK");Button omega=button("Ω RENDER WAV");Button cal=button("CAL CIRCLE");actions.addView(load,weight());actions.addView(playButton,weight());actions.addView(omega,weight());actions.addView(cal,weight());root.addView(actions);
        load.setOnClickListener(v->openMedia());playButton.setOnClickListener(v->togglePlayback());omega.setOnClickListener(v->chooseSave());cal.setOnClickListener(v->showCal());
        sourceBox=check("Show source frame",true);sourceBox.setOnCheckedChangeListener((b,v)->sourcePreview.setVisibility(v?View.VISIBLE:View.GONE));root.addView(sourceBox);sourcePreview.setVisibility(View.VISIBLE);
        colorPreviewBox=check("Decode source colors in FRAMEFORGE preview",true);colorPreviewBox.setOnCheckedChangeListener((b,v)->scope.setColorPreview(v));root.addView(colorPreviewBox);

        root.addView(section("FRAME CLOCK"));
        sampleSpinner=spinner(new String[]{"192000 Hz · 6400 XY @30 / 3200 @60","96000 Hz","48000 Hz"},0,true);root.addView(sampleSpinner);
        modeSpinner=spinner(new String[]{"LIVE 30 · 6400 XY @192k","LIVE 60 · 3200 XY @192k","DETAIL 24 · 8000 XY @192k","DETAIL 20 · 9600 XY @192k","DETAIL 15 · 12800 XY @192k"},0,true);root.addView(modeSpinner);
        profileSpinner=spinner(new String[]{"FULL PHOTO","PORTRAIT / CHARACTER","LINE / INK","VIDEO FULL-FRAME"},0,true);root.addView(profileSpinner);

        root.addView(section("Ω INFORMATION BUDGET"));
        qualityLabel=label("SUPERSAMPLE QUALITY: 100%");root.addView(qualityLabel);qualityBar=slider(100,v->{qualityLabel.setText("SUPERSAMPLE QUALITY: "+v+"%");queuePreview();});root.addView(qualityBar);
        structureLabel=label("STRUCTURE / ANCHORS: 94%");root.addView(structureLabel);structureBar=slider(94,v->{structureLabel.setText("STRUCTURE / ANCHORS: "+v+"%");queuePreview();});root.addView(structureBar);
        toneLabel=label("LUMINANCE / TONE: 74%");root.addView(toneLabel);toneBar=slider(74,v->{toneLabel.setText("LUMINANCE / TONE: "+v+"%");queuePreview();});root.addView(toneBar);
        colorLabel=label("CHROMATIC PHASE SPACE: 38%");root.addView(colorLabel);colorBar=slider(38,v->{colorLabel.setText("CHROMATIC PHASE SPACE: "+v+"%");queuePreview();});root.addView(colorBar);
        borderBox=check("Crush frame / panel / letterbox priority",true);borderBox.setOnCheckedChangeListener((b,v)->queuePreview());root.addView(borderBox);
        invertBox=check("Invert luminance",false);invertBox.setOnCheckedChangeListener((b,v)->queuePreview());root.addView(invertBox);
        gammaLabel=label("IMAGE GAMMA: 0.90");root.addView(gammaLabel);gammaBar=new SeekBar(this);gammaBar.setMax(180);gammaBar.setProgress(50);gammaBar.setOnSeekBarChangeListener(change((b,v)->{gammaLabel.setText(String.format(Locale.US,"IMAGE GAMMA: %.2f",gamma()));queuePreview();}));root.addView(gammaBar);

        root.addView(section("TRUTH PREVIEW / REAL SCOPE"));
        intensityLabel=label("FRAME INTENSITY: 92%");root.addView(intensityLabel);intensityBar=slider(92,v->{intensityLabel.setText("FRAME INTENSITY: "+v+"%");scope.setIntensity(v);});root.addView(intensityBar);
        beamLabel=label("BEAM WIDTH: 26%");root.addView(beamLabel);beamBar=slider(26,v->{beamLabel.setText("BEAM WIDTH: "+v+"%");scope.setBeamWidth(v);});root.addView(beamBar);
        xGainLabel=label("X GAIN: 100%");root.addView(xGainLabel);xGainBar=gain(xGainLabel,"X GAIN");root.addView(xGainBar);yGainLabel=label("Y GAIN: 100%");root.addView(yGainLabel);yGainBar=gain(yGainLabel,"Y GAIN");root.addView(yGainBar);
        rotationLabel=label("ROTATION: 0.0°");root.addView(rotationLabel);rotationBar=new SeekBar(this);rotationBar.setMax(240);rotationBar.setProgress(120);rotationBar.setOnSeekBarChangeListener(change((b,v)->{rotationLabel.setText(String.format(Locale.US,"ROTATION: %.1f°",rotation()));queuePreview();}));root.addView(rotationBar);

        root.addView(section("Ω OFFLINE EXPORT"));exportSpinner=spinner(new String[]{"32-bit float WAV · best","24-bit PCM WAV"},0,false);root.addView(exportSpinner);
        TextView note=label("LIVE = exact one-frame audio blocks under a real-time deadline. Ω RENDER = 4× candidate oversampling with no real-time deadline. The software scope has ZERO persistence: old frames are not faded, they are replaced. LEFT=X, RIGHT=Y. Source color is encoded in tiny chromatic microtrajectories and shown in the FRAMEFORGE preview; a normal monochrome 2-channel scope still displays XY geometry only.");note.setTextSize(11);note.setTextColor(Color.rgb(132,168,152));root.addView(note);
        status=new TextView(this);status.setTextColor(Color.rgb(198,255,222));status.setTextSize(12.3f);status.setPadding(0,dp(7),0,dp(24));root.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);return scroll;
    }

    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.rgb(192,221,208));t.setTextSize(12);t.setPadding(0,dp(4),0,dp(2));return t;}
    private TextView section(String s){TextView t=label(s);t.setTextColor(Color.rgb(158,248,190));t.setTextSize(13);t.setPadding(0,dp(10),0,dp(2));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(9.2f);return b;}
    private CheckBox check(String s,boolean v){CheckBox b=new CheckBox(this);b.setText(s);b.setTextColor(Color.rgb(205,231,218));b.setChecked(v);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setPadding(0,dp(3),0,dp(5));return r;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.setMargins(dp(2),0,dp(2),0);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private interface S{void run(int v);}private SeekBar slider(int v,S a){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(v);b.setOnSeekBarChangeListener(change((x,n)->a.run(n)));return b;}
    private interface C{void run(SeekBar b,int v);}private SeekBar.OnSeekBarChangeListener change(C c){return new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean u){if(u&&!uiChanging)c.run(b,v);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};}
    private Spinner spinner(String[]v,int pos,boolean preview){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,v);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setSelection(pos);if(preview)s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int x,long id){if(!uiChanging)queuePreview();}public void onNothingSelected(AdapterView<?>p){}});return s;}
    private SeekBar gain(TextView t,String prefix){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(50);b.setOnSeekBarChangeListener(change((x,v)->{t.setText(prefix+": "+(50+v)+"%");queuePreview();}));return b;}

    private void openMedia(){stopPlayback();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});startActivityForResult(i,REQ_OPEN);}
    private void chooseSave(){if(sourceKind==NONE){toast("Load media first.");return;}stopPlayback();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/wav");i.putExtra(Intent.EXTRA_TITLE,"FRAMEFORGE_OMEGA_v8_"+fps()+"fps_"+System.currentTimeMillis()+".wav");startActivityForResult(i,REQ_SAVE);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(req==REQ_OPEN){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){}loadSource(u);}else if(req==REQ_SAVE)omegaRender(u);}

    private void loadSource(Uri u){sourceUri=u;status.setText("Decoding source…");worker.submit(()->{try{releaseSource();ContentResolver cr=getContentResolver();String mime=cr.getType(u),lower=u.toString().toLowerCase(Locale.US);
        if(mime!=null&&mime.startsWith("video/")){sourceKind=VIDEO;retriever=new MediaMetadataRetriever();retriever.setDataSource(this,u);durationMs=parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),1000);sourceWidth=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),640);sourceHeight=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),480);Bitmap first=getVideoFrame(0);if(first==null)throw new IllegalStateException("Video decoder returned no frame");runOnUiThread(()->{sourcePreview.setImageBitmap(first);presetVideo();compilePreview(first);});}
        else if((mime!=null&&mime.equals("image/gif"))||lower.endsWith(".gif")){sourceKind=GIF;gifBytes=readAll(cr.openInputStream(u));gifMovie=Movie.decodeByteArray(gifBytes,0,gifBytes.length);if(gifMovie==null){sourceKind=IMAGE;stillBitmap=decodeBitmap(u);durationMs=6000;runOnUiThread(()->{sourcePreview.setImageBitmap(stillBitmap);compilePreview(stillBitmap);});}else{durationMs=gifMovie.duration()>0?gifMovie.duration():1000;sourceWidth=gifMovie.width();sourceHeight=gifMovie.height();Bitmap first=getGifFrame(0);runOnUiThread(()->{sourcePreview.setImageBitmap(first);presetVideo();compilePreview(first);});}}
        else{sourceKind=IMAGE;stillBitmap=decodeBitmap(u);if(stillBitmap==null)throw new IllegalStateException("Image decode failed");durationMs=6000;sourceWidth=stillBitmap.getWidth();sourceHeight=stillBitmap.getHeight();runOnUiThread(()->{sourcePreview.setImageBitmap(stillBitmap);compilePreview(stillBitmap);});}}
        catch(Throwable t){runOnUiThread(()->status.setText("Load error: "+safe(t)));}});}

    private Bitmap decodeBitmap(Uri u)throws Exception{if(Build.VERSION.SDK_INT>=28){ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),u);return ImageDecoder.decodeBitmap(src,(d,info,s)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));}try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(u))){return BitmapFactory.decodeStream(in);}}
    private V8FrameEngine.Settings settings(boolean offline){V8FrameEngine.Settings s=new V8FrameEngine.Settings();s.sampleRate=sampleRate();s.fps=fps();s.profile=profileSpinner.getSelectedItemPosition();s.quality=qualityBar.getProgress();s.structure=structureBar.getProgress()/100f;s.tone=toneBar.getProgress()/100f;s.color=colorBar.getProgress()/100f;s.gamma=gamma();s.suppressBorders=borderBox.isChecked();s.invert=invertBox.isChecked();s.offline=offline;s.xGain=(50+xGainBar.getProgress())/100f;s.yGain=(50+yGainBar.getProgress())/100f;s.rotationDeg=rotation();return s;}
    private int sampleRate(){int p=sampleSpinner.getSelectedItemPosition();return p==1?96000:p==2?48000:192000;}
    private int fps(){switch(modeSpinner.getSelectedItemPosition()){case 1:return 60;case 2:return 24;case 3:return 20;case 4:return 15;default:return 30;}}
    private float gamma(){return .40f+gammaBar.getProgress()/100f;}private float rotation(){return (rotationBar.getProgress()-120)/10f;}

    private void queuePreview(){if(playing.get()||sourceKind==NONE||previewQueued.getAndSet(true))return;worker.submit(()->{try{Bitmap b=currentFrame(0);if(b!=null){V8FrameEngine.Result r=V8FrameEngine.compile(b,settings(false),0,Float.NaN,Float.NaN);runOnUiThread(()->showResult(r,0,false));}}catch(Throwable t){runOnUiThread(()->status.setText("Preview error: "+safe(t)));}finally{previewQueued.set(false);}});}
    private void compilePreview(Bitmap b){if(b==null)return;worker.submit(()->{try{V8FrameEngine.Result r=V8FrameEngine.compile(b,settings(false),0,Float.NaN,Float.NaN);runOnUiThread(()->showResult(r,0,false));}catch(Throwable t){runOnUiThread(()->status.setText("Compile error: "+safe(t)));}});}
    private void showResult(V8FrameEngine.Result r,long frame,boolean live){scope.setFrame(r);String liveInfo=live?String.format(Locale.US,"\nAUDIO %d Hz · %s · queue %d · dropped %d · repeated %d · underruns %d",audio.rate(),audio.route(),audio.queued(),audio.dropped(),audio.repeated(),audio.underruns()):"";status.setText(String.format(Locale.US,"FRAME %d · %s\n%d XY · STRUCT %.0f%% · LUMA %.0f%% · COLOR %.0f%% · ERROR %.1f%%\nFLY %d · RMS %.4f · PEAK %.4f · RENDER %d ms%s%s",frame,r.mode,r.samples,r.structureScore*100,r.lumaScore*100,r.colorScore*100,r.frameError*100,r.flybacks,r.rmsStep,r.peakStep,r.renderMs,r.missedDeadline?" · DEADLINE MISS":"",liveInfo));}

    private void togglePlayback(){if(sourceKind==NONE){toast("Load media first.");return;}if(playing.get())stopPlayback();else startPlayback();}
    private void startPlayback(){stopPlayback();V8FrameEngine.Settings req=settings(false);int rate;try{rate=audio.start(req.sampleRate,req.fps);}catch(Throwable t){status.setText("Audio start error: "+safe(t));return;}V8FrameEngine.Settings s=req.copy();s.sampleRate=rate;playing.set(true);playButton.setText("STOP");frameThread=new Thread(()->frameLoop(s),"FRAMEFORGE-FrameClock");frameThread.setPriority(Thread.NORM_PRIORITY+2);frameThread.start();}
    private void frameLoop(V8FrameEngine.Settings s){long periodNs=Math.round(1_000_000_000.0/s.fps),start=System.nanoTime(),fi=0;float px=Float.NaN,py=Float.NaN;V8FrameEngine.Result still=null;
        while(playing.get()){
            long due=start+fi*periodNs,now=System.nanoTime();if(now<due){long ms=(due-now)/1_000_000;if(ms>0)SystemClock.sleep(Math.min(4,ms));continue;}if(now-due>periodNs*2&&sourceKind!=IMAGE)fi=Math.max(fi,(now-start)/periodNs);
            long tms=durationMs>0?Math.round((fi*1000.0/s.fps)%durationMs):Math.round(fi*1000.0/s.fps);
            try{Bitmap frame=sourceKind==IMAGE?stillBitmap:currentFrame(tms);V8FrameEngine.Result r;if(sourceKind==IMAGE&&still!=null)r=still;else{r=V8FrameEngine.compile(frame,s,fi,px,py);if(sourceKind==IMAGE)still=r;}if(r.xy.length>=2){px=r.xy[r.xy.length-2];py=r.xy[r.xy.length-1];}audio.offer(r.xy);Bitmap shown=frame;long shownFi=fi;runOnUiThread(()->{if(sourceBox.isChecked()&&shown!=null)sourcePreview.setImageBitmap(shown);showResult(r,shownFi,true);});}catch(Throwable t){runOnUiThread(()->status.setText("Frame error: "+safe(t)));}
            fi++;if(fi>100000000L){fi=0;start=System.nanoTime();}
        }}
    private void stopPlayback(){playing.set(false);if(frameThread!=null){frameThread.interrupt();frameThread=null;}audio.stop();if(playButton!=null)playButton.setText("PLAY FRAME-LOCK");}

    private void omegaRender(Uri dest){V8FrameEngine.Settings s=settings(true);boolean f32=exportSpinner.getSelectedItemPosition()==0;status.setText("Ω RENDER: optimizing complete independent frames…");worker.submit(()->{File tmp=new File(getCacheDir(),"frameforge_omega_"+System.currentTimeMillis()+".wav");try(V4Audio.WavWriter w=V4Audio.createWriter(tmp,s.sampleRate,f32)){long dur=sourceKind==IMAGE?6000:Math.max(1,durationMs);int frames=Math.max(1,(int)Math.ceil(dur*s.fps/1000.0));float px=Float.NaN,py=Float.NaN;V8FrameEngine.Result still=null;for(int i=0;i<frames;i++){Bitmap b=sourceKind==IMAGE?stillBitmap:currentFrame(Math.min(dur-1,Math.round(i*1000.0/s.fps)));V8FrameEngine.Result r;if(sourceKind==IMAGE&&still!=null)r=still;else{r=V8FrameEngine.compile(b,s,i,px,py);if(sourceKind==IMAGE)still=r;}px=r.xy[r.xy.length-2];py=r.xy[r.xy.length-1];w.write(r.xy);if(i%Math.max(1,s.fps/3)==0){final int pct=Math.min(99,Math.round(100f*i/frames));runOnUiThread(()->status.setText("Ω RENDER · "+pct+"% · 4× oversample → exact frame blocks"));}}
            try(InputStream in=new BufferedInputStream(new FileInputStream(tmp));OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(dest,"w"))){byte[]buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}runOnUiThread(()->status.setText("Ω RENDER saved · "+s.fps+" fps · "+s.sampleRate+" Hz · "+(f32?"32-bit float":"24-bit PCM")+" · every frame independent"));}catch(Throwable t){runOnUiThread(()->status.setText("Ω RENDER error: "+safe(t)));}finally{try{tmp.delete();}catch(Throwable ignored){}}});}

    private Bitmap currentFrame(long ms){if(sourceKind==VIDEO)return getVideoFrame(ms);if(sourceKind==GIF)return getGifFrame(ms);return stillBitmap;}
    private Bitmap getVideoFrame(long ms){if(retriever==null)return null;long us=Math.max(0,ms)*1000L;try{if(Build.VERSION.SDK_INT>=27){int max=720,w=sourceWidth<=0?max:sourceWidth,h=sourceHeight<=0?max:sourceHeight;float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap b=retriever.getScaledFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST,Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)));if(b!=null)return b;}}catch(Throwable ignored){}return retriever.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST);}
    private Bitmap getGifFrame(long ms){if(gifMovie==null)return null;int max=720,w=Math.max(1,gifMovie.width()),h=Math.max(1,gifMovie.height());float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap out=Bitmap.createBitmap(Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.BLACK);c.scale(sc,sc);synchronized(this){gifMovie.setTime((int)(ms%Math.max(1,durationMs)));gifMovie.draw(c,0,0);}return out;}
    private void showCal(){V8FrameEngine.Settings s=settings(false);float[]xy=V8FrameEngine.calCircle(s);int[]rgb=new int[xy.length/2];java.util.Arrays.fill(rgb,Color.rgb(140,255,185));V8FrameEngine.Result r=new V8FrameEngine.Result(xy,rgb,rgb.length,s.fps,0,1,1,1,0,0,0,0,false,"CAL CIRCLE");scope.setFrame(r);if(playing.get())audio.offer(xy);}

    private void presetVideo(){uiChanging=true;profileSpinner.setSelection(3);modeSpinner.setSelection(0);qualityBar.setProgress(86);structureBar.setProgress(92);toneBar.setProgress(68);colorBar.setProgress(34);uiChanging=false;syncLabels();}
    private void syncLabels(){qualityLabel.setText("SUPERSAMPLE QUALITY: "+qualityBar.getProgress()+"%");structureLabel.setText("STRUCTURE / ANCHORS: "+structureBar.getProgress()+"%");toneLabel.setText("LUMINANCE / TONE: "+toneBar.getProgress()+"%");colorLabel.setText("CHROMATIC PHASE SPACE: "+colorBar.getProgress()+"%");}
    private void releaseSource(){stillBitmap=null;gifMovie=null;gifBytes=null;durationMs=0;sourceWidth=sourceHeight=0;if(retriever!=null){try{retriever.release();}catch(Throwable ignored){}retriever=null;}}
    @Override protected void onDestroy(){stopPlayback();releaseSource();worker.shutdownNow();super.onDestroy();}
    private static byte[]readAll(InputStream in)throws Exception{if(in==null)return new byte[0];try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[65536];int n;while((n=x.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private static long parseLong(String s,long f){try{return Long.parseLong(s);}catch(Throwable t){return f;}}private static int parseInt(String s,int f){try{return Integer.parseInt(s);}catch(Throwable t){return f;}}private static String safe(Throwable t){if(t==null)return "unknown";String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
