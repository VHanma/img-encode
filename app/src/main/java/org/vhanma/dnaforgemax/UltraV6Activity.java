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
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** OsciVision Ultra v6: subpixel vector + minimum-jerk XY instrument. */
public class UltraV6Activity extends Activity {
    private static final int REQ_OPEN=6101,REQ_SAVE=6102;
    private static final int NONE=0,IMAGE=1,VIDEO=2,GIF=3;

    private V6ScopeView scope; private ImageView sourcePreview; private TextView status;
    private Spinner profileSpinner,sampleSpinner,fpsSpinner,bandsSpinner,bankSpinner,exportSpinner;
    private SeekBar qualityBar,toneBar,edgeBar,harmonyBar,smoothBar,gammaBar;
    private SeekBar persistenceBar,intensityBar,bloomBar,beamBar,xGainBar,yGainBar,rotationBar;
    private TextView qualityLabel,toneLabel,edgeLabel,harmonyLabel,smoothLabel,gammaLabel;
    private TextView persistenceLabel,intensityLabel,bloomLabel,beamLabel,xGainLabel,yGainLabel,rotationLabel;
    private CheckBox temporalBox,invertBox,sourceBox; private Button playButton;

    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing=new AtomicBoolean(false),previewQueued=new AtomicBoolean(false);
    private final AtomicInteger generation=new AtomicInteger(0); private final V4Audio audio=new V4Audio();
    private Thread animationThread; private boolean uiChanging=false;
    private Uri sourceUri; private int sourceKind=NONE; private Bitmap stillBitmap; private Movie gifMovie; private byte[]gifBytes;
    private MediaMetadataRetriever retriever; private long durationMs; private int sourceWidth,sourceHeight;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setContentView(buildUi());status.setText("v6 ready. Load media and hit MAX MODE. The engine now optimizes the physical beam trajectory, not just the preview.");}

    private View buildUi(){int pad=dp(11);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);root.setBackgroundColor(Color.rgb(1,3,6));
        TextView title=label("OSCIVISION ULTRA v6");title.setTextSize(26);title.setGravity(Gravity.CENTER_HORIZONTAL);title.setTextColor(Color.rgb(198,255,221));root.addView(title);
        TextView sub=label("SUBPIXEL VECTOR • MINIMUM-JERK BEAM • RESIDUAL TONE");sub.setTextSize(11);sub.setGravity(Gravity.CENTER_HORIZONTAL);sub.setTextColor(Color.rgb(112,222,163));root.addView(sub);
        sourcePreview=new ImageView(this);sourcePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);sourcePreview.setBackgroundColor(Color.BLACK);sourcePreview.setVisibility(View.GONE);root.addView(sourcePreview,new LinearLayout.LayoutParams(-1,dp(150)));
        scope=new V6ScopeView(this);root.addView(scope,new LinearLayout.LayoutParams(-1,dp(455)));

        LinearLayout actions=row();Button load=button("LOAD");playButton=button("PLAY XY");Button max=button("MAX MODE");Button save=button("EXPORT");actions.addView(load,weight());actions.addView(playButton,weight());actions.addView(max,weight());actions.addView(save,weight());root.addView(actions);
        load.setOnClickListener(v->openMedia());playButton.setOnClickListener(v->togglePlayback());max.setOnClickListener(v->maxMode());save.setOnClickListener(v->chooseSave());
        sourceBox=check("Show source reference",false);sourceBox.setOnCheckedChangeListener((b,v)->sourcePreview.setVisibility(v?View.VISIBLE:View.GONE));root.addView(sourceBox);

        root.addView(section("RECONSTRUCTION"));
        profileSpinner=spinner(new String[]{"PHOTO MAX","PORTRAIT / FACE","VIDEO STABLE","LINE / LOGO"},0,true);root.addView(profileSpinner);
        sampleSpinner=spinner(new String[]{"192000 Hz · maximum coordinate density","96000 Hz","48000 Hz"},0,true);root.addView(sampleSpinner);
        fpsSpinner=spinner(new String[]{"8 fps · maximum still detail","12 fps","15 fps","24 fps","30 fps","60 fps"},0,true);root.addView(fpsSpinner);
        bandsSpinner=spinner(new String[]{"6 iso-contours","8 iso-contours","10 iso-contours","12 iso-contours","14 iso-contours","16 iso-contours"},3,true);root.addView(bandsSpinner);

        qualityLabel=label("SUBPIXEL DETAIL: 100%");root.addView(qualityLabel);qualityBar=slider(100,v->{qualityLabel.setText("SUBPIXEL DETAIL: "+v+"%");queuePreview();});root.addView(qualityBar);
        edgeLabel=label("STRUCTURE / EDGE: 90%");root.addView(edgeLabel);edgeBar=slider(90,v->{edgeLabel.setText("STRUCTURE / EDGE: "+v+"%");queuePreview();});root.addView(edgeBar);
        toneLabel=label("RESIDUAL GRAYSCALE: 62%");root.addView(toneLabel);toneBar=slider(62,v->{toneLabel.setText("RESIDUAL GRAYSCALE: "+v+"%");queuePreview();});root.addView(toneBar);
        smoothLabel=label("TRAJECTORY SMOOTHING: 52%");root.addView(smoothLabel);smoothBar=slider(52,v->{smoothLabel.setText("TRAJECTORY SMOOTHING: "+v+"%");queuePreview();});root.addView(smoothBar);

        root.addView(section("HARMONIC TIMING"));
        bankSpinner=spinner(new String[]{"81 FAMILY · 13.5 Hz closing grid","BAGUA 64 · 16 Hz closing grid","SEVENFOLD 49 · 24.5 Hz closing grid","RAW XY · image priority"},0,true);root.addView(bankSpinner);
        harmonyLabel=label("GEOMETRY-SAFE HARMONY: 12%");root.addView(harmonyLabel);harmonyBar=slider(12,v->{harmonyLabel.setText("GEOMETRY-SAFE HARMONY: "+v+"%");queuePreview();});root.addView(harmonyBar);
        gammaLabel=label("IMAGE GAMMA: 0.88");root.addView(gammaLabel);gammaBar=new SeekBar(this);gammaBar.setMax(180);gammaBar.setProgress(48);gammaBar.setOnSeekBarChangeListener(change((b,v)->{gammaLabel.setText(String.format(Locale.US,"IMAGE GAMMA: %.2f",gamma()));queuePreview();}));root.addView(gammaBar);
        temporalBox=check("Temporal lock for GIF / video",true);temporalBox.setOnCheckedChangeListener((b,v)->queuePreview());root.addView(temporalBox);
        invertBox=check("Invert luminance",false);invertBox.setOnCheckedChangeListener((b,v)->queuePreview());root.addView(invertBox);

        root.addView(section("CRT / SCOPE"));
        persistenceLabel=label("PERSISTENCE: 92%");root.addView(persistenceLabel);persistenceBar=slider(92,v->{persistenceLabel.setText("PERSISTENCE: "+v+"%");scope.setPersistence(v);});root.addView(persistenceBar);
        intensityLabel=label("BEAM INTENSITY: 90%");root.addView(intensityLabel);intensityBar=slider(90,v->{intensityLabel.setText("BEAM INTENSITY: "+v+"%");scope.setIntensity(v);});root.addView(intensityBar);
        bloomLabel=label("BLOOM: 38%");root.addView(bloomLabel);bloomBar=slider(38,v->{bloomLabel.setText("BLOOM: "+v+"%");scope.setBloom(v);});root.addView(bloomBar);
        beamLabel=label("BEAM WIDTH: 26%");root.addView(beamLabel);beamBar=slider(26,v->{beamLabel.setText("BEAM WIDTH: "+v+"%");scope.setBeamWidth(v);});root.addView(beamBar);

        root.addView(section("REAL SCOPE CALIBRATION"));xGainLabel=label("X GAIN: 100%");root.addView(xGainLabel);xGainBar=gain(xGainLabel,"X GAIN");root.addView(xGainBar);yGainLabel=label("Y GAIN: 100%");root.addView(yGainLabel);yGainBar=gain(yGainLabel,"Y GAIN");root.addView(yGainBar);
        rotationLabel=label("ROTATION: 0.0°");root.addView(rotationLabel);rotationBar=new SeekBar(this);rotationBar.setMax(240);rotationBar.setProgress(120);rotationBar.setOnSeekBarChangeListener(change((b,v)->{rotationLabel.setText(String.format(Locale.US,"ROTATION: %.1f°",rotation()));queuePreview();}));root.addView(rotationBar);
        LinearLayout tests=row();Button circle=button("CIRCLE");Button grid=button("GRID");Button clear=button("CLEAR CRT");tests.addView(circle,weight());tests.addView(grid,weight());tests.addView(clear,weight());root.addView(tests);circle.setOnClickListener(v->showTest(true));grid.setOnClickListener(v->showTest(false));clear.setOnClickListener(v->scope.clearPhosphor());

        root.addView(section("EXPORT"));exportSpinner=spinner(new String[]{"32-bit float WAV · maximum XY precision","24-bit PCM WAV"},0,false);root.addView(exportSpinner);
        TextView note=label("LEFT = X   •   RIGHT = Y   •   non-raster XY\nFIT measures the software reconstruction. RMS STEP measures beam motion. Long disconnected moves are kept as one-sample flybacks so they spend minimum visible time on a 2-channel scope.");note.setTextColor(Color.rgb(130,164,150));note.setTextSize(11);root.addView(note);
        status=new TextView(this);status.setTextColor(Color.rgb(195,255,220));status.setTextSize(12.5f);status.setPadding(0,dp(7),0,dp(24));root.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);return scroll;
    }

    private TextView section(String s){TextView t=label(s);t.setTextColor(Color.rgb(154,248,187));t.setTextSize(13);t.setPadding(0,dp(10),0,dp(2));return t;}
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.rgb(190,220,207));t.setTextSize(12);t.setPadding(0,dp(4),0,dp(2));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(10.5f);return b;}
    private CheckBox check(String s,boolean v){CheckBox b=new CheckBox(this);b.setText(s);b.setTextColor(Color.rgb(205,230,218));b.setChecked(v);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setPadding(0,dp(3),0,dp(6));return r;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(2),0,dp(2),0);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private interface S{void run(int v);}private SeekBar slider(int v,S a){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(v);b.setOnSeekBarChangeListener(change((x,n)->a.run(n)));return b;}
    private interface C{void run(SeekBar b,int v);}private SeekBar.OnSeekBarChangeListener change(C c){return new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean u){if(u&&!uiChanging)c.run(b,v);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};}
    private Spinner spinner(String[]v,int pos,boolean preview){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,v);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setSelection(pos);if(preview)s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int x,long id){if(!uiChanging)queuePreview();}public void onNothingSelected(AdapterView<?>p){}});return s;}
    private SeekBar gain(TextView t,String prefix){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(50);b.setOnSeekBarChangeListener(change((x,v)->{t.setText(prefix+": "+(50+v)+"%");queuePreview();}));return b;}

    private void openMedia(){stopPlayback();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});startActivityForResult(i,REQ_OPEN);}
    private void chooseSave(){if(sourceKind==NONE){toast("Load media first.");return;}stopPlayback();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/wav");i.putExtra(Intent.EXTRA_TITLE,"oscivision_v6_xy_"+System.currentTimeMillis()+".wav");startActivityForResult(i,REQ_SAVE);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(req==REQ_OPEN){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){}loadSource(u);}else if(req==REQ_SAVE)export(u);}

    private void loadSource(Uri u){sourceUri=u;status.setText("Decoding…");worker.submit(()->{try{releaseSource();ContentResolver cr=getContentResolver();String mime=cr.getType(u),lower=u.toString().toLowerCase(Locale.US);
        if(mime!=null&&mime.startsWith("video/")){sourceKind=VIDEO;retriever=new MediaMetadataRetriever();retriever.setDataSource(this,u);durationMs=parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),1000);sourceWidth=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),640);sourceHeight=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),480);Bitmap first=getVideoFrame(0);if(first==null)throw new IllegalStateException("Video decoder returned no frame");runOnUiThread(()->{sourcePreview.setImageBitmap(first);preset(2);compilePreview(first,0);});}
        else if((mime!=null&&mime.equals("image/gif"))||lower.endsWith(".gif")){sourceKind=GIF;gifBytes=readAll(cr.openInputStream(u));gifMovie=Movie.decodeByteArray(gifBytes,0,gifBytes.length);if(gifMovie==null){sourceKind=IMAGE;stillBitmap=decodeBitmap(u);durationMs=6000;sourceWidth=stillBitmap.getWidth();sourceHeight=stillBitmap.getHeight();runOnUiThread(()->{sourcePreview.setImageBitmap(stillBitmap);preset(0);compilePreview(stillBitmap,0);});}else{durationMs=gifMovie.duration()>0?gifMovie.duration():1000;sourceWidth=gifMovie.width();sourceHeight=gifMovie.height();Bitmap first=getGifFrame(0);runOnUiThread(()->{sourcePreview.setImageBitmap(first);preset(2);compilePreview(first,0);});}}
        else{sourceKind=IMAGE;stillBitmap=decodeBitmap(u);if(stillBitmap==null)throw new IllegalStateException("Image decode failed");durationMs=6000;sourceWidth=stillBitmap.getWidth();sourceHeight=stillBitmap.getHeight();runOnUiThread(()->{sourcePreview.setImageBitmap(stillBitmap);preset(0);compilePreview(stillBitmap,0);});}}
        catch(Throwable t){runOnUiThread(()->status.setText("Load error: "+safe(t)));}});}

    private Bitmap decodeBitmap(Uri u)throws Exception{if(Build.VERSION.SDK_INT>=28){ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),u);return ImageDecoder.decodeBitmap(src,(d,info,s)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));}try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(u))){return BitmapFactory.decodeStream(in);}}

    private V6Engine.Settings settings(){V6Engine.Settings s=new V6Engine.Settings();s.sampleRate=sampleRate();s.fps=fps();s.profile=profileSpinner.getSelectedItemPosition();s.bands=bands();s.bank=bankSpinner.getSelectedItemPosition();s.quality=qualityBar.getProgress();s.edge=edgeBar.getProgress()/100f;s.tone=toneBar.getProgress()/100f;s.harmony=s.bank==3?0:harmonyBar.getProgress()/100f;s.trajectorySmooth=smoothBar.getProgress()/100f;s.gamma=gamma();s.temporal=temporalBox.isChecked();s.invert=invertBox.isChecked();s.xGain=(50+xGainBar.getProgress())/100f;s.yGain=(50+yGainBar.getProgress())/100f;s.rotationDeg=rotation();return s;}
    private int sampleRate(){int p=sampleSpinner.getSelectedItemPosition();return p==1?96000:(p==2?48000:192000);}private int fps(){switch(fpsSpinner.getSelectedItemPosition()){case 0:return 8;case 1:return 12;case 2:return 15;case 3:return 24;case 4:return 30;default:return 60;}}private int bands(){switch(bandsSpinner.getSelectedItemPosition()){case 0:return 6;case 1:return 8;case 2:return 10;case 3:return 12;case 4:return 14;default:return 16;}}private float gamma(){return .40f+gammaBar.getProgress()/100f;}private float rotation(){return(rotationBar.getProgress()-120)/10f;}

    private void queuePreview(){if(uiChanging||sourceKind!=IMAGE||stillBitmap==null||playing.get())return;if(!previewQueued.compareAndSet(false,true))return;int gen=generation.incrementAndGet();V6Engine.Settings s=settings();Bitmap b=stillBitmap;worker.submit(()->{try{SystemClock.sleep(30);V6Engine.Result r=V6Engine.compile(b,s,0);if(gen==generation.get())runOnUiThread(()->show(r,false));}catch(Throwable t){runOnUiThread(()->status.setText("Compile error: "+safe(t)));}finally{previewQueued.set(false);}});}
    private void compilePreview(Bitmap b,long frame){if(b==null)return;int gen=generation.incrementAndGet();V6Engine.Settings s=settings();worker.submit(()->{try{V6Engine.Result r=V6Engine.compile(b,s,frame);if(gen==generation.get())runOnUiThread(()->show(r,false));}catch(Throwable t){runOnUiThread(()->status.setText("Compile error: "+safe(t)));}});}
    private void show(V6Engine.Result r,boolean live){scope.setResult(r);String lattice=r.primaryHz>0?String.format(Locale.US,"%.1f Hz primary / %.1f Hz closing grid",r.primaryHz,r.latticeHz):"RAW IMAGE-PRIORITY XY";String route=live?"\n"+audio.sampleRate()+" Hz · "+audio.routeName()+" · underruns "+audio.underruns():"";String warn="";if(live){String q=audio.routeName().toLowerCase(Locale.US);if(q.contains("bluetooth")||q.contains("ble")||q.contains("built-in speaker"))warn="\n⚠ For an external XY scope, wired/USB audio is far more reliable than this route.";}status.setText(r.mode+" · "+lattice+"\nFIT "+Math.round(r.fit*100)+"% · "+r.samples+" samples · "+r.paths+" paths · "+r.residualLoops+" residual loops · FLY "+r.flybacks+" · RMS STEP "+String.format(Locale.US,"%.4f",r.rmsStep)+route+warn);}

    private void togglePlayback(){if(sourceKind==NONE){toast("Load media first.");return;}if(playing.get())stopPlayback();else startPlayback();}
    private void startPlayback(){stopPlayback();V6Engine.Settings req=settings();int rate;try{rate=audio.start(req.sampleRate);}catch(Throwable t){status.setText("Audio start error: "+safe(t));return;}V6Engine.Settings s=req.copy();s.sampleRate=rate;playing.set(true);playButton.setText("STOP");
        if(sourceKind==IMAGE){worker.submit(()->{try{V6Engine.Result r=V6Engine.compile(stillBitmap,s,0);if(!playing.get())return;audio.setFrame(r.xy);runOnUiThread(()->show(r,true));}catch(Throwable t){runOnUiThread(()->status.setText("Playback error: "+safe(t)));}});return;}
        animationThread=new Thread(()->{long start=SystemClock.elapsedRealtime(),fi=0,period=Math.max(1,Math.round(1000.0/s.fps));while(playing.get()){long target=fi*period,elapsed=SystemClock.elapsedRealtime()-start;if(elapsed<target){SystemClock.sleep(Math.min(5,target-elapsed));continue;}if(elapsed-target>period*2)fi=Math.max(fi,elapsed/period);long mt=durationMs>0?(fi*period)%durationMs:fi*period;try{Bitmap f=sourceKind==VIDEO?getVideoFrame(mt):getGifFrame(mt);if(f!=null){V6Engine.Result r=V6Engine.compile(f,s,fi);audio.setFrame(r.xy);Bitmap shown=f;runOnUiThread(()->{if(sourceBox.isChecked())sourcePreview.setImageBitmap(shown);show(r,true);});}}catch(Throwable t){runOnUiThread(()->status.setText("Frame error: "+safe(t)));}fi++;}},"OsciVisionV6Video");animationThread.setPriority(Thread.NORM_PRIORITY+1);animationThread.start();}
    private void stopPlayback(){playing.set(false);if(animationThread!=null){animationThread.interrupt();animationThread=null;}audio.stop();if(playButton!=null)playButton.setText("PLAY XY");}

    private Bitmap getVideoFrame(long ms){if(retriever==null)return null;long us=Math.max(0,ms)*1000;try{if(Build.VERSION.SDK_INT>=27){int max=640,w=sourceWidth<=0?max:sourceWidth,h=sourceHeight<=0?max:sourceHeight;float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap b=retriever.getScaledFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST,Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)));if(b!=null)return b;}}catch(Throwable ignored){}return retriever.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST);}
    private Bitmap getGifFrame(long ms){if(gifMovie==null)return null;int max=640,w=Math.max(1,gifMovie.width()),h=Math.max(1,gifMovie.height());float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap out=Bitmap.createBitmap(Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.BLACK);c.scale(sc,sc);synchronized(this){gifMovie.setTime((int)(ms%Math.max(1,durationMs)));gifMovie.draw(c,0,0);}return out;}

    private void export(Uri dest){V6Engine.Settings s=settings();boolean float32=exportSpinner.getSelectedItemPosition()==0;status.setText("Rendering v6 WAV…");worker.submit(()->{File tmp=new File(getCacheDir(),"oscivision_v6_"+System.currentTimeMillis()+".wav");try(V4Audio.WavWriter writer=V4Audio.createWriter(tmp,s.sampleRate,float32)){long dur=sourceKind==IMAGE?6000:Math.max(1,durationMs);if(sourceKind==IMAGE){V6Engine.Result r=V6Engine.compile(stillBitmap,s,0);writeRepeated(writer,r.xy,Math.round(s.sampleRate*dur/1000f));}else{int frames=Math.max(1,(int)Math.ceil(dur*s.fps/1000.0)),pairs=Math.max(1,Math.round(s.sampleRate/(float)s.fps));for(int i=0;i<frames;i++){long t=Math.min(dur-1,Math.round(i*1000.0/s.fps));Bitmap f=sourceKind==VIDEO?getVideoFrame(t):getGifFrame(t);if(f==null)continue;V6Engine.Result r=V6Engine.compile(f,s,i);writeRepeated(writer,r.xy,pairs);if(i%Math.max(1,s.fps/2)==0){int pct=Math.min(99,Math.round(100f*i/frames));runOnUiThread(()->status.setText("Rendering v6 WAV… "+pct+"%"));}}}try(InputStream in=new BufferedInputStream(new FileInputStream(tmp));OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(dest,"w"))){byte[]buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}runOnUiThread(()->status.setText("Saved · "+(float32?"32-bit float":"24-bit PCM")+" · stereo XY · "+s.sampleRate+" Hz"));}catch(Throwable t){runOnUiThread(()->status.setText("Export error: "+safe(t)));}finally{try{if(tmp.exists())tmp.delete();}catch(Throwable ignored){}}});}
    private static void writeRepeated(V4Audio.WavWriter w,float[]tile,int pairs)throws Exception{if(tile==null||tile.length<2)return;int tp=tile.length/2;while(pairs>=tp){w.write(tile);pairs-=tp;}if(pairs>0)w.write(Arrays.copyOf(tile,pairs*2));}

    private void showTest(boolean circle){V6Engine.Settings s=settings();int n=s.bank<3?Math.max(2048,(int)Math.round(s.sampleRate/(s.bank==0?13.5:(s.bank==1?16:24.5)))):Math.max(2048,s.sampleRate/Math.max(1,s.fps));float[]xy=circle?V6Engine.circle(s,n):V6Engine.grid(s,n);scope.setTrace(xy,circle?"CAL CIRCLE":"CAL GRID");if(playing.get())audio.setFrame(xy);}
    private void maxMode(){preset(sourceKind==VIDEO||sourceKind==GIF?2:profileSpinner.getSelectedItemPosition());queuePreview();}
    private void preset(int p){uiChanging=true;try{p=Math.max(0,Math.min(3,p));profileSpinner.setSelection(p);sampleSpinner.setSelection(0);bankSpinner.setSelection(0);if(p==0){fpsSpinner.setSelection(0);bandsSpinner.setSelection(4);qualityBar.setProgress(100);edgeBar.setProgress(92);toneBar.setProgress(64);harmonyBar.setProgress(10);smoothBar.setProgress(54);gammaBar.setProgress(48);persistenceBar.setProgress(94);intensityBar.setProgress(92);bloomBar.setProgress(34);beamBar.setProgress(22);}else if(p==1){fpsSpinner.setSelection(0);bandsSpinner.setSelection(5);qualityBar.setProgress(100);edgeBar.setProgress(98);toneBar.setProgress(50);harmonyBar.setProgress(8);smoothBar.setProgress(48);gammaBar.setProgress(44);persistenceBar.setProgress(94);intensityBar.setProgress(94);bloomBar.setProgress(30);beamBar.setProgress(18);}else if(p==2){fpsSpinner.setSelection(2);bandsSpinner.setSelection(0);qualityBar.setProgress(70);edgeBar.setProgress(84);toneBar.setProgress(30);harmonyBar.setProgress(6);smoothBar.setProgress(34);gammaBar.setProgress(50);persistenceBar.setProgress(88);intensityBar.setProgress(86);bloomBar.setProgress(34);beamBar.setProgress(26);bankSpinner.setSelection(3);}else{fpsSpinner.setSelection(1);bandsSpinner.setSelection(2);qualityBar.setProgress(100);edgeBar.setProgress(100);toneBar.setProgress(0);harmonyBar.setProgress(8);smoothBar.setProgress(18);gammaBar.setProgress(56);persistenceBar.setProgress(90);intensityBar.setProgress(94);bloomBar.setProgress(26);beamBar.setProgress(14);}scope.setPersistence(persistenceBar.getProgress());scope.setIntensity(intensityBar.getProgress());scope.setBloom(bloomBar.getProgress());scope.setBeamWidth(beamBar.getProgress());labels();}finally{uiChanging=false;}}
    private void labels(){qualityLabel.setText("SUBPIXEL DETAIL: "+qualityBar.getProgress()+"%");edgeLabel.setText("STRUCTURE / EDGE: "+edgeBar.getProgress()+"%");toneLabel.setText("RESIDUAL GRAYSCALE: "+toneBar.getProgress()+"%");harmonyLabel.setText("GEOMETRY-SAFE HARMONY: "+harmonyBar.getProgress()+"%");smoothLabel.setText("TRAJECTORY SMOOTHING: "+smoothBar.getProgress()+"%");gammaLabel.setText(String.format(Locale.US,"IMAGE GAMMA: %.2f",gamma()));persistenceLabel.setText("PERSISTENCE: "+persistenceBar.getProgress()+"%");intensityLabel.setText("BEAM INTENSITY: "+intensityBar.getProgress()+"%");bloomLabel.setText("BLOOM: "+bloomBar.getProgress()+"%");beamLabel.setText("BEAM WIDTH: "+beamBar.getProgress()+"%");}

    private void releaseSource(){stillBitmap=null;gifMovie=null;gifBytes=null;durationMs=0;sourceWidth=sourceHeight=0;if(retriever!=null){try{retriever.release();}catch(Throwable ignored){}retriever=null;}}
    @Override protected void onDestroy(){stopPlayback();releaseSource();worker.shutdownNow();super.onDestroy();}
    private static byte[]readAll(InputStream in)throws Exception{if(in==null)return new byte[0];try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[65536];int n;while((n=x.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private static long parseLong(String s,long f){try{return Long.parseLong(s);}catch(Throwable t){return f;}}private static int parseInt(String s,int f){try{return Integer.parseInt(s);}catch(Throwable t){return f;}}private static String safe(Throwable t){if(t==null)return"unknown";String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
