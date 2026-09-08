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
import java.util.concurrent.atomic.AtomicInteger;

/** OsciVision Omega v11: prepared stable-traversal + temporal/timing biosonification. */
public class UltraV11OmegaActivity extends Activity {
    private static final int OPEN=11101,SAVE=11102,NONE=0,IMAGE=1,VIDEO=2,GIF=3;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing=new AtomicBoolean(false),preparing=new AtomicBoolean(false),cancelPrep=new AtomicBoolean(false);
    private final AtomicInteger generation=new AtomicInteger(1);
    private final V9PreparedAudio audio=new V9PreparedAudio();
    private Thread playbackThread;

    private V8FrameScopeView scope;private ImageView sourceView;private TextView status;private Button play;
    private Spinner sampleSp,fpsSp,profileSp,bandsSp,exportSp;
    private SeekBar detailBar,structureBar,toneBar,gammaBar,temporalBar,chromaBar,curvatureBar,smoothBar,intensityBar,beamBar;
    private TextView detailTxt,structureTxt,toneTxt,gammaTxt,temporalTxt,chromaTxt,curvatureTxt,smoothTxt;
    private CheckBox invertBox,colorBox,sourceBox;

    private Uri sourceUri;private int kind=NONE;private Bitmap still;private Movie gif;private byte[]gifBytes;private MediaMetadataRetriever mmr;private long durationMs;private int sourceW,sourceH;
    private File cache;private boolean cacheReady=false;private int cacheGeneration=-1;private V9TraceEngine.Settings cacheSettings;private V11OmegaTraceEngine.Options cacheOmega;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setContentView(ui());status.setText("Ω v11 ready · prepare first, then fixed-clock playback. Anti-rectangle purge is permanently armed.");}

    private View ui(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(10),dp(10),dp(10),dp(10));root.setBackgroundColor(Color.rgb(1,3,6));
        TextView title=t("OSCIVISION Ω v11",28);title.setGravity(Gravity.CENTER);title.setTextColor(Color.rgb(220,255,230));root.addView(title);
        TextView sub=t("CARTOGRAPHER • CHOREOGRAPHER • COMPOSER",10);sub.setGravity(Gravity.CENTER);sub.setTextColor(Color.rgb(120,235,175));root.addView(sub);
        sourceView=new ImageView(this);sourceView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);sourceView.setBackgroundColor(Color.BLACK);root.addView(sourceView,new LinearLayout.LayoutParams(-1,dp(145)));
        scope=new V8FrameScopeView(this);root.addView(scope,new LinearLayout.LayoutParams(-1,dp(455)));

        LinearLayout actions=row();Button load=btn("LOAD");play=btn("PREPARE + PLAY");Button stop=btn("STOP");Button save=btn("EXPORT WAV");actions.addView(load,w());actions.addView(play,w());actions.addView(stop,w());actions.addView(save,w());root.addView(actions);
        load.setOnClickListener(v->open());play.setOnClickListener(v->startRequest());stop.setOnClickListener(v->stopAll());save.setOnClickListener(v->chooseSave());
        sourceBox=check("Show source reference",true);sourceBox.setOnCheckedChangeListener((b,v)->sourceView.setVisibility(v?View.VISIBLE:View.GONE));root.addView(sourceBox);
        colorBox=check("Source-color software trace",true);colorBox.setOnCheckedChangeListener((b,v)->scope.setColorPreview(v));root.addView(colorBox);

        root.addView(sec("Ω REFERENCE CLOCK"));sampleSp=spinner(new String[]{"192000 Hz","96000 Hz","48000 Hz"},0);root.addView(sampleSp);
        fpsSp=spinner(new String[]{"60 fps · motion priority","30 fps · detail priority","24 fps","20 fps","15 fps · maximum XY/frame"},0);root.addView(fpsSp);
        profileSp=spinner(new String[]{"REFERENCE VIDEO / ANIME","PORTRAIT / CHARACTER","PHOTO + HATCH","LINE / INK"},0);root.addView(profileSp);
        bandsSp=spinner(new String[]{"2 luminance shells","3 luminance shells","4 luminance shells","5 luminance shells","6 luminance shells","7 luminance shells","8 luminance shells"},2);root.addView(bandsSp);

        root.addView(sec("THE CARTOGRAPHER"));
        detailTxt=t("VECTOR DETAIL: 96%",12);root.addView(detailTxt);detailBar=slider(96,v->{detailTxt.setText("VECTOR DETAIL: "+v+"%");changed();});root.addView(detailBar);
        structureTxt=t("STRUCTURE PRIORITY: 100%",12);root.addView(structureTxt);structureBar=slider(100,v->{structureTxt.setText("STRUCTURE PRIORITY: "+v+"%");changed();});root.addView(structureBar);
        toneTxt=t("TONE / HATCH: 28%",12);root.addView(toneTxt);toneBar=slider(28,v->{toneTxt.setText("TONE / HATCH: "+v+"%");changed();});root.addView(toneBar);
        TextView anti=t("ANTI-RECTANGLE Ω LOCK: ON · frame/panel borders are rejected in the vectorizer AND purged again after normalization.",11);anti.setTextColor(Color.rgb(255,205,150));root.addView(anti);
        invertBox=check("Invert luminance",false);invertBox.setOnCheckedChangeListener((b,v)->changed());root.addView(invertBox);
        gammaTxt=t("IMAGE GAMMA: 0.90",12);root.addView(gammaTxt);gammaBar=new SeekBar(this);gammaBar.setMax(180);gammaBar.setProgress(50);gammaBar.setOnSeekBarChangeListener(listener((b,v)->{gammaTxt.setText(String.format(Locale.US,"IMAGE GAMMA: %.2f",gamma()));changed();}));root.addView(gammaBar);

        root.addView(sec("THE CHOREOGRAPHER"));
        temporalTxt=t("TEMPORAL PHASE LOCK: 88%",12);root.addView(temporalTxt);temporalBar=slider(88,v->{temporalTxt.setText("TEMPORAL PHASE LOCK: "+v+"%");changed();});root.addView(temporalBar);
        curvatureTxt=t("CURVATURE DENSITY: 72%",12);root.addView(curvatureTxt);curvatureBar=slider(72,v->{curvatureTxt.setText("CURVATURE DENSITY: "+v+"%");changed();});root.addView(curvatureBar);
        smoothTxt=t("INTRA-PATH SMOOTHNESS: 22%",12);root.addView(smoothTxt);smoothBar=slider(22,v->{smoothTxt.setText("INTRA-PATH SMOOTHNESS: "+v+"%");changed();});root.addView(smoothBar);

        root.addView(sec("THE COMPOSER"));
        chromaTxt=t("CHROMA TIMING: 42%",12);root.addView(chromaTxt);chromaBar=slider(42,v->{chromaTxt.setText("CHROMA TIMING: "+v+"%");changed();});root.addView(chromaBar);
        TextView comp=t("Color changes beam timing/density along existing geometry. It does not add arbitrary tones to LEFT=X / RIGHT=Y.",11);comp.setTextColor(Color.rgb(150,190,170));root.addView(comp);

        root.addView(sec("SCOPE"));root.addView(t("TRACE INTENSITY",12));intensityBar=sliderPlain(96,v->scope.setIntensity(v));root.addView(intensityBar);root.addView(t("BEAM WIDTH",12));beamBar=sliderPlain(20,v->scope.setBeamWidth(v));root.addView(beamBar);
        root.addView(sec("EXPORT"));exportSp=spinnerPlain(new String[]{"32-bit float WAV","24-bit PCM WAV"},0);root.addView(exportSp);
        TextView note=t("Ω playback is still prepared first. Once playback starts there is zero video decoding, zero contour extraction and zero temporal solving in the audio clock. LEFT = X, RIGHT = Y.",11);note.setTextColor(Color.rgb(132,168,152));root.addView(note);
        status=t("",12);status.setTextColor(Color.rgb(205,255,224));status.setPadding(0,dp(7),0,dp(24));root.addView(status);
        ScrollView sc=new ScrollView(this);sc.addView(root);return sc;
    }

    private TextView t(String s,float z){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(Color.rgb(192,221,208));v.setPadding(0,dp(4),0,dp(2));return v;}
    private TextView sec(String s){TextView v=t(s,13);v.setTextColor(Color.rgb(165,250,195));v.setPadding(0,dp(10),0,dp(2));return v;}
    private Button btn(String s){Button b=new Button(this);b.setText(s);b.setTextSize(9);return b;}
    private CheckBox check(String s,boolean c){CheckBox b=new CheckBox(this);b.setText(s);b.setChecked(c);b.setTextColor(Color.rgb(205,231,218));return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private LinearLayout.LayoutParams w(){return new LinearLayout.LayoutParams(0,dp(50),1);}private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private interface IV{void go(int v);}private SeekBar slider(int v,IV a){return sliderPlain(v,a);}private SeekBar sliderPlain(int v,IV a){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(v);b.setOnSeekBarChangeListener(listener((x,n)->a.go(n)));return b;}
    private interface SV{void go(SeekBar b,int v);}private SeekBar.OnSeekBarChangeListener listener(SV a){return new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean u){if(u)a.go(b,v);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};}
    private Spinner spinner(String[]a,int p){Spinner s=spinnerPlain(a,p);s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>x,View v,int i,long l){changed();}public void onNothingSelected(AdapterView<?>x){}});return s;}
    private Spinner spinnerPlain(String[]a,int p){Spinner s=new Spinner(this);ArrayAdapter<String>d=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,a);d.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(d);s.setSelection(p);return s;}

    private void changed(){invalidateCache();preview();}
    private void invalidateCache(){generation.incrementAndGet();cacheReady=false;cacheGeneration=-1;if(cache!=null){try{cache.delete();}catch(Throwable ignored){}cache=null;}}
    private int rate(){int p=sampleSp.getSelectedItemPosition();return p==1?96000:p==2?48000:192000;}
    private int fps(){switch(fpsSp.getSelectedItemPosition()){case 1:return 30;case 2:return 24;case 3:return 20;case 4:return 15;default:return 60;}}
    private float gamma(){return .40f+gammaBar.getProgress()/100f;}
    private V9TraceEngine.Settings snapshot(){V9TraceEngine.Settings s=new V9TraceEngine.Settings();s.sampleRate=rate();s.fps=fps();s.profile=profileSp.getSelectedItemPosition();s.isoBands=2+bandsSp.getSelectedItemPosition();s.quality=detailBar.getProgress();s.structure=structureBar.getProgress()/100f;s.tone=toneBar.getProgress()/100f;s.gamma=gamma();s.suppressBorders=true;s.invert=invertBox.isChecked();return s;}
    private V11OmegaTraceEngine.Options omegaSnapshot(){V11OmegaTraceEngine.Options o=new V11OmegaTraceEngine.Options();o.temporal=temporalBar.getProgress()/100f;o.chroma=chromaBar.getProgress()/100f;o.curvature=curvatureBar.getProgress()/100f;o.smoothing=smoothBar.getProgress()/100f;o.purgePerimeter=true;return o;}

    private void open(){stopAll();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});startActivityForResult(i,OPEN);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(req==OPEN){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){}load(u);}else if(req==SAVE)export(u);}

    private void load(Uri u){invalidateCache();sourceUri=u;status.setText("Decoding source…");worker.submit(()->{try{releaseSource();ContentResolver cr=getContentResolver();String mime=cr.getType(u),lower=u.toString().toLowerCase(Locale.US);Bitmap first;if(mime!=null&&mime.startsWith("video/")){kind=VIDEO;mmr=new MediaMetadataRetriever();mmr.setDataSource(this,u);durationMs=parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),1000);sourceW=parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),640);sourceH=parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),480);first=videoFrame(0);}else if((mime!=null&&mime.equals("image/gif"))||lower.endsWith(".gif")){gifBytes=readAll(cr.openInputStream(u));gif=Movie.decodeByteArray(gifBytes,0,gifBytes.length);if(gif!=null){kind=GIF;durationMs=Math.max(1000,gif.duration());sourceW=gif.width();sourceH=gif.height();first=gifFrame(0);}else{kind=IMAGE;still=decode(u);durationMs=6000;first=still;}}else{kind=IMAGE;still=decode(u);durationMs=6000;first=still;}if(first==null)throw new IllegalStateException("Decoder returned no frame");Bitmap f=first;runOnUiThread(()->{sourceView.setImageBitmap(f);status.setText("Source loaded · Omega preview compiling…");previewBitmap(f);});}catch(Throwable t){runOnUiThread(()->status.setText("Load error: "+safe(t)));}});}

    private void preview(){if(kind==NONE||playing.get()||preparing.get())return;V9TraceEngine.Settings s=snapshot();V11OmegaTraceEngine.Options o=omegaSnapshot();worker.submit(()->{try{V11OmegaTraceEngine.State st=new V11OmegaTraceEngine.State();Bitmap b=current(0);V9TraceEngine.Result r=V11OmegaTraceEngine.compile(b,s,o,st,0,Float.NaN,Float.NaN);runOnUiThread(()->show(r,s,st,0,"Ω PREVIEW"));}catch(Throwable ignored){}});}
    private void previewBitmap(Bitmap b){V9TraceEngine.Settings s=snapshot();V11OmegaTraceEngine.Options o=omegaSnapshot();worker.submit(()->{try{V11OmegaTraceEngine.State st=new V11OmegaTraceEngine.State();V9TraceEngine.Result r=V11OmegaTraceEngine.compile(b,s,o,st,0,Float.NaN,Float.NaN);runOnUiThread(()->show(r,s,st,0,"Ω PREVIEW"));}catch(Throwable t){runOnUiThread(()->status.setText("Preview error: "+safe(t)));}});}
    private void show(V9TraceEngine.Result r,V9TraceEngine.Settings s,V11OmegaTraceEngine.State st,long frame,String mode){V8FrameEngine.Result q=new V8FrameEngine.Result(r.xy,r.rgb,r.samples,s.fps,r.flybacks,r.structureScore,r.lumaScore,1,r.frameError,r.rmsStep,r.peakStep,r.renderMs,false,mode);scope.setFrame(q);status.setText(String.format(Locale.US,"FRAME %d · %s · %d FPS · %d XY\nPATHS %d · STRUCT %.0f%% · LUMA %.0f%% · ERR %.1f%%\nCOHERENCE %.0f%% · PERIM PURGED %d · FLY %d · RMS %.4f",frame,mode,s.fps,r.samples,r.paths,r.structureScore*100,r.lumaScore*100,r.frameError*100,st.coherence*100,st.perimeterPurged,r.flybacks,r.rmsStep));}

    private void startRequest(){if(kind==NONE){toast("Load media first.");return;}if(playing.get()||preparing.get()){stopAll();return;}if(cacheReady&&cacheGeneration==generation.get()&&cache!=null&&cache.exists())startPrepared();else prepareAndPlay();}
    private void prepareAndPlay(){if(preparing.getAndSet(true))return;cancelPrep.set(false);play.setText("CANCEL PREP");final int gen=generation.get();final V9TraceEngine.Settings requested=snapshot();final V11OmegaTraceEngine.Options oo=omegaSnapshot();worker.submit(()->{File outFile=null;try{int actual=audio.start(requested.sampleRate,requested.fps);audio.stop();V9TraceEngine.Settings s=requested.copy();s.sampleRate=actual;int pairs=Math.max(256,Math.round(actual/(float)s.fps));int frames=kind==IMAGE?1:Math.max(1,(int)Math.ceil(Math.max(1,durationMs)*s.fps/1000.0));outFile=new File(getCacheDir(),"omega11_"+System.currentTimeMillis()+".bin");float px=Float.NaN,py=Float.NaN;V11OmegaTraceEngine.State st=new V11OmegaTraceEngine.State();try(V9PreparedCache.Writer writer=new V9PreparedCache.Writer(outFile,new V9PreparedCache.Meta(actual,s.fps,pairs,frames))){for(int i=0;i<frames;i++){if(cancelPrep.get()||gen!=generation.get())throw new InterruptedException();long ms=kind==IMAGE?0:Math.min(Math.max(0,durationMs-1),Math.round(i*1000.0/s.fps));V9TraceEngine.Result r=V11OmegaTraceEngine.compile(current(ms),s,oo,st,i,px,py);px=r.xy[r.xy.length-2];py=r.xy[r.xy.length-1];writer.write(r);if(i%Math.max(1,s.fps/5)==0){final int pct=Math.min(99,Math.round(i*100f/Math.max(1,frames)));final int fi=i;final float coh=st.coherence;final int pur=st.perimeterPurged;runOnUiThread(()->status.setText("Ω PREPARING "+pct+"% · frame "+fi+"\nphase coherence "+Math.round(coh*100)+"% · perimeter purged "+pur));}}}if(gen!=generation.get())throw new InterruptedException();cache=outFile;cacheReady=true;cacheGeneration=gen;cacheSettings=s;cacheOmega=oo.copy();preparing.set(false);runOnUiThread(()->{play.setText("STOP");status.setText("Ω PREPARED · "+frames+" frames · "+s.fps+" fps · "+pairs+" XY/frame\nStarting fixed-clock playback…");startPrepared();});}catch(Throwable t){if(outFile!=null)try{outFile.delete();}catch(Throwable ignored){}preparing.set(false);runOnUiThread(()->{play.setText("PREPARE + PLAY");status.setText(t instanceof InterruptedException?"Preparation stopped.":"Prepare error: "+safe(t));});}});}

    private void startPrepared(){if(!cacheReady||cache==null||cacheSettings==null)return;stopPlayback();V9TraceEngine.Settings s=cacheSettings.copy();int actual;try{actual=audio.start(s.sampleRate,s.fps);}catch(Throwable t){status.setText("Audio error: "+safe(t));return;}if(actual!=s.sampleRate){audio.stop();invalidateCache();status.setText("Audio route changed. Reprepare at "+actual+" Hz.");return;}playing.set(true);play.setText("STOP");final File file=cache;final V9TraceEngine.Settings fs=s;playbackThread=new Thread(()->playLoop(file,fs),"Omega11-Prepared-Playback");playbackThread.setPriority(Thread.MAX_PRIORITY);playbackThread.start();}
    private void playLoop(File file,V9TraceEngine.Settings s){long global=0;final long period=Math.round(1_000_000_000.0/s.fps);final long clock=System.nanoTime();while(playing.get()){try(V9PreparedCache.Reader reader=new V9PreparedCache.Reader(file)){V9PreparedCache.Frame next;long local=0;while(playing.get()&&(next=reader.next())!=null){long due=clock+global*period;while(playing.get()){long d=due-System.nanoTime();if(d<=0)break;if(d>2_000_000)SystemClock.sleep(Math.min(3,d/1_000_000));else Thread.yield();}if(!playing.get())break;final V9PreparedCache.Frame shown=next;final long fi=local;final int fly=countFly(shown.xy);final float rr=rms(shown.xy);final V8FrameEngine.Result q=new V8FrameEngine.Result(shown.xy,shown.rgb,shown.rgb.length,s.fps,fly,0,0,1,0,rr,peak(shown.xy),0,false,"Ω PREPARED");runOnUiThread(()->{scope.setFrame(q);status.setText(String.format(Locale.US,"FRAME %d · Ω PREPARED · %d FPS · %d XY\nZERO DECODER / ZERO COMPILER IN PLAYBACK\nFLY %d · RMS %.4f · AUDIO %d Hz · %s · underruns %d",fi,s.fps,shown.rgb.length,fly,rr,audio.rate(),audio.route(),audio.underruns()));});audio.writeFrame(shown.xy);global++;local++;}}catch(Throwable t){runOnUiThread(()->status.setText("Playback error: "+safe(t)));break;}}}
    private void stopPlayback(){playing.set(false);if(playbackThread!=null){playbackThread.interrupt();playbackThread=null;}audio.stop();if(play!=null&&!preparing.get())play.setText(cacheReady?"PLAY PREPARED":"PREPARE + PLAY");}
    private void stopAll(){cancelPrep.set(true);stopPlayback();if(preparing.get())status.setText("Stopping preparation…");}

    private void chooseSave(){if(kind==NONE){toast("Load media first.");return;}stopPlayback();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/wav");i.putExtra(Intent.EXTRA_TITLE,"OsciVision_Omega_v11_"+fps()+"fps.wav");startActivityForResult(i,SAVE);}
    private void export(Uri dest){final V9TraceEngine.Settings s=snapshot();final V11OmegaTraceEngine.Options o=omegaSnapshot();final boolean f32=exportSp.getSelectedItemPosition()==0;status.setText("Ω rendering WAV…");worker.submit(()->{File tmp=new File(getCacheDir(),"omega11_export.wav");try(V4Audio.WavWriter w=V4Audio.createWriter(tmp,s.sampleRate,f32)){int frames=kind==IMAGE?s.fps*6:Math.max(1,(int)Math.ceil(durationMs*s.fps/1000.0));float px=Float.NaN,py=Float.NaN;V11OmegaTraceEngine.State st=new V11OmegaTraceEngine.State();V9TraceEngine.Result stillR=null;for(int i=0;i<frames;i++){V9TraceEngine.Result r;if(kind==IMAGE&&stillR!=null)r=stillR;else{long ms=kind==IMAGE?0:Math.min(Math.max(0,durationMs-1),Math.round(i*1000.0/s.fps));r=V11OmegaTraceEngine.compile(current(ms),s,o,st,i,px,py);if(kind==IMAGE)stillR=r;}px=r.xy[r.xy.length-2];py=r.xy[r.xy.length-1];w.write(r.xy);}try(InputStream in=new BufferedInputStream(new FileInputStream(tmp));OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(dest,"w"))){byte[]buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}runOnUiThread(()->status.setText("Saved Ω v11 biosonification WAV."));}catch(Throwable t){runOnUiThread(()->status.setText("Export error: "+safe(t)));}finally{try{tmp.delete();}catch(Throwable ignored){}}});}

    private Bitmap current(long ms){return kind==VIDEO?videoFrame(ms):kind==GIF?gifFrame(ms):still;}
    private Bitmap videoFrame(long ms){if(mmr==null)return null;long us=Math.max(0,ms)*1000L;try{if(Build.VERSION.SDK_INT>=27){int max=720,w0=sourceW<=0?max:sourceW,h0=sourceH<=0?max:sourceH;float sc=Math.min(1f,max/(float)Math.max(w0,h0));Bitmap b=mmr.getScaledFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST,Math.max(2,Math.round(w0*sc)),Math.max(2,Math.round(h0*sc)));if(b!=null)return b;}}catch(Throwable ignored){}return mmr.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST);}
    private Bitmap gifFrame(long ms){if(gif==null)return null;int max=720,w0=Math.max(1,gif.width()),h0=Math.max(1,gif.height());float sc=Math.min(1f,max/(float)Math.max(w0,h0));Bitmap b=Bitmap.createBitmap(Math.max(2,Math.round(w0*sc)),Math.max(2,Math.round(h0*sc)),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(Color.BLACK);c.scale(sc,sc);synchronized(this){gif.setTime((int)(ms%Math.max(1,durationMs)));gif.draw(c,0,0);}return b;}
    private Bitmap decode(Uri u)throws Exception{if(Build.VERSION.SDK_INT>=28){ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),u);return ImageDecoder.decodeBitmap(src,(d,info,s)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));}try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(u))){return BitmapFactory.decodeStream(in);}}
    private void releaseSource(){still=null;gif=null;gifBytes=null;durationMs=0;sourceW=sourceH=0;if(mmr!=null){try{mmr.release();}catch(Throwable ignored){}mmr=null;}}
    @Override protected void onDestroy(){stopAll();invalidateCache();releaseSource();worker.shutdownNow();super.onDestroy();}

    private static int countFly(float[]xy){int n=0;for(int i=2;i<xy.length;i+=2){float dx=xy[i]-xy[i-2],dy=xy[i+1]-xy[i-1];if(dx*dx+dy*dy>.005625f)n++;}return n;}
    private static float rms(float[]xy){double s=0;int n=0;for(int i=2;i<xy.length;i+=2){float dx=xy[i]-xy[i-2],dy=xy[i+1]-xy[i-1];s+=dx*dx+dy*dy;n++;}return(float)Math.sqrt(s/Math.max(1,n));}
    private static float peak(float[]xy){float p=0;for(int i=2;i<xy.length;i+=2){float dx=xy[i]-xy[i-2],dy=xy[i+1]-xy[i-1];p=Math.max(p,(float)Math.sqrt(dx*dx+dy*dy));}return p;}
    private static byte[]readAll(InputStream in)throws Exception{if(in==null)return new byte[0];try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[65536];int n;while((n=x.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private static long parseLong(String s,long f){try{return Long.parseLong(s);}catch(Throwable t){return f;}}
    private static int parseInt(String s,int f){try{return Integer.parseInt(s);}catch(Throwable t){return f;}}
    private static String safe(Throwable t){String m=t==null?null:t.getMessage();return m==null||m.trim().isEmpty()?(t==null?"unknown":t.getClass().getSimpleName()):m;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}