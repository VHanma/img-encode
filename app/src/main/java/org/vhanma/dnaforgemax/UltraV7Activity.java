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

/** OsciVision Ultra v7: master-phasor Fourier scene synthesizer. */
public class UltraV7Activity extends Activity {
    private static final int REQ_OPEN=7101,REQ_SAVE=7102;
    private static final int NONE=0,IMAGE=1,VIDEO=2,GIF=3,DEMO=4;

    private V7ScopeView scope; private ImageView sourcePreview; private TextView status;
    private Spinner profileSpinner,sampleSpinner,sceneSpinner,shellSpinner,motionBankSpinner,exportSpinner;
    private SeekBar detailBar,structureBar,depthBar,perspectiveBar,motionBar,gammaBar;
    private SeekBar persistenceBar,intensityBar,bloomBar,beamBar,xGainBar,yGainBar,rotationBar;
    private TextView detailLabel,structureLabel,depthLabel,perspectiveLabel,motionLabel,gammaLabel;
    private TextView persistenceLabel,intensityLabel,bloomLabel,beamLabel,xGainLabel,yGainLabel,rotationLabel;
    private CheckBox borderBox,invertBox,sourceBox; private Button playButton;

    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean playing=new AtomicBoolean(false),rebuildQueued=new AtomicBoolean(false);
    private final AtomicInteger generation=new AtomicInteger(0); private final V4Audio audio=new V4Audio();
    private Thread animationThread; private boolean uiChanging=false;
    private Uri sourceUri; private int sourceKind=NONE; private Bitmap stillBitmap; private Movie gifMovie; private byte[]gifBytes;
    private MediaMetadataRetriever retriever; private long durationMs; private int sourceWidth,sourceHeight;
    private volatile V7SceneEngine.Scene currentScene;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setContentView(buildUi());status.setText("v7 ready. Load media, then the source is compiled into periodic Fourier objects driven by one master scene phasor.");}

    private View buildUi(){int pad=dp(11);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);root.setBackgroundColor(Color.rgb(1,3,6));
        TextView title=label("OSCIVISION ULTRA v7");title.setTextSize(26);title.setGravity(Gravity.CENTER_HORIZONTAL);title.setTextColor(Color.rgb(198,255,221));root.addView(title);
        TextView sub=label("MASTER PHASOR • FOURIER SCENE • PARAMETRIC 3D XY");sub.setTextSize(11);sub.setGravity(Gravity.CENTER_HORIZONTAL);sub.setTextColor(Color.rgb(112,222,163));root.addView(sub);
        sourcePreview=new ImageView(this);sourcePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);sourcePreview.setBackgroundColor(Color.BLACK);sourcePreview.setVisibility(View.GONE);root.addView(sourcePreview,new LinearLayout.LayoutParams(-1,dp(150)));
        scope=new V7ScopeView(this);root.addView(scope,new LinearLayout.LayoutParams(-1,dp(455)));

        LinearLayout actions=row();Button load=button("LOAD");playButton=button("PLAY XY");Button demo=button("PARAMETRIC DEMO");Button save=button("EXPORT");actions.addView(load,weight());actions.addView(playButton,weight());actions.addView(demo,weight());actions.addView(save,weight());root.addView(actions);
        load.setOnClickListener(v->openMedia());playButton.setOnClickListener(v->togglePlayback());demo.setOnClickListener(v->showDemo());save.setOnClickListener(v->chooseSave());
        LinearLayout tune=row();Button max=button("MAX PHOTO");Button clear=button("CLEAR CRT");Button circle=button("CAL CIRCLE");Button grid=button("CAL GRID");tune.addView(max,weight());tune.addView(clear,weight());tune.addView(circle,weight());tune.addView(grid,weight());root.addView(tune);max.setOnClickListener(v->maxMode());clear.setOnClickListener(v->scope.clearPhosphor());circle.setOnClickListener(v->showTest(true));grid.setOnClickListener(v->showTest(false));
        sourceBox=check("Show source reference",false);sourceBox.setOnCheckedChangeListener((b,v)->sourcePreview.setVisibility(v?View.VISIBLE:View.GONE));root.addView(sourceBox);

        root.addView(section("SCENE COMPILER"));
        profileSpinner=spinner(new String[]{"PHOTO SCULPTURE","PORTRAIT SCULPTURE","LINE / INK","VIDEO SCENE"},0,true);root.addView(profileSpinner);
        sampleSpinner=spinner(new String[]{"192000 Hz · maximum coordinate density","96000 Hz","48000 Hz"},0,true);root.addView(sampleSpinner);
        sceneSpinner=spinner(new String[]{"12 Hz scene redraw · extreme detail","20 Hz scene redraw · max photo","30 Hz scene redraw · balanced","40 Hz scene redraw · motion"},1,true);root.addView(sceneSpinner);
        shellSpinner=spinner(new String[]{"2 tone shells","3 tone shells","4 tone shells","5 tone shells","6 tone shells","7 tone shells","8 tone shells"},3,true);root.addView(shellSpinner);
        detailLabel=label("FOURIER / OBJECT DETAIL: 92%");root.addView(detailLabel);detailBar=slider(92,v->{detailLabel.setText("FOURIER / OBJECT DETAIL: "+v+"%");queueRebuild();});root.addView(detailBar);
        structureLabel=label("STRUCTURE PRIORITY: 92%");root.addView(structureLabel);structureBar=slider(92,v->{structureLabel.setText("STRUCTURE PRIORITY: "+v+"%");queueRebuild();});root.addView(structureBar);
        borderBox=check("Suppress frame / panel rectangles",true);borderBox.setOnCheckedChangeListener((b,v)->queueRebuild());root.addView(borderBox);

        root.addView(section("PARAMETRIC 3D MOTION"));
        depthLabel=label("DEPTH: 10%");root.addView(depthLabel);depthBar=slider(10,v->{depthLabel.setText("DEPTH: "+v+"%");queueRenderOnly();});root.addView(depthBar);
        perspectiveLabel=label("PERSPECTIVE: 12%");root.addView(perspectiveLabel);perspectiveBar=slider(12,v->{perspectiveLabel.setText("PERSPECTIVE: "+v+"%");queueRenderOnly();});root.addView(perspectiveBar);
        motionLabel=label("SCENE MOTION: 12%");root.addView(motionLabel);motionBar=slider(12,v->{motionLabel.setText("SCENE MOTION: "+v+"%");queueRenderOnly();});root.addView(motionBar);
        motionBankSpinner=spinner(new String[]{"NATURAL GEOMETRY","81 motion clock","64 motion clock","49 motion clock"},0,false);root.addView(motionBankSpinner);
        gammaLabel=label("IMAGE GAMMA: 0.90");root.addView(gammaLabel);gammaBar=new SeekBar(this);gammaBar.setMax(180);gammaBar.setProgress(50);gammaBar.setOnSeekBarChangeListener(change((b,v)->{gammaLabel.setText(String.format(Locale.US,"IMAGE GAMMA: %.2f",gamma()));queueRebuild();}));root.addView(gammaBar);
        invertBox=check("Invert luminance",false);invertBox.setOnCheckedChangeListener((b,v)->queueRebuild());root.addView(invertBox);

        root.addView(section("CRT / REAL SCOPE"));
        persistenceLabel=label("PERSISTENCE: 90%");root.addView(persistenceLabel);persistenceBar=slider(90,v->{persistenceLabel.setText("PERSISTENCE: "+v+"%");scope.setPersistence(v);});root.addView(persistenceBar);
        intensityLabel=label("BEAM INTENSITY: 88%");root.addView(intensityLabel);intensityBar=slider(88,v->{intensityLabel.setText("BEAM INTENSITY: "+v+"%");scope.setIntensity(v);});root.addView(intensityBar);
        bloomLabel=label("BLOOM: 30%");root.addView(bloomLabel);bloomBar=slider(30,v->{bloomLabel.setText("BLOOM: "+v+"%");scope.setBloom(v);});root.addView(bloomBar);
        beamLabel=label("BEAM WIDTH: 24%");root.addView(beamLabel);beamBar=slider(24,v->{beamLabel.setText("BEAM WIDTH: "+v+"%");scope.setBeamWidth(v);});root.addView(beamBar);
        xGainLabel=label("X GAIN: 100%");root.addView(xGainLabel);xGainBar=gain(xGainLabel,"X GAIN");root.addView(xGainBar);yGainLabel=label("Y GAIN: 100%");root.addView(yGainLabel);yGainBar=gain(yGainLabel,"Y GAIN");root.addView(yGainBar);
        rotationLabel=label("ROTATION: 0.0°");root.addView(rotationLabel);rotationBar=new SeekBar(this);rotationBar.setMax(240);rotationBar.setProgress(120);rotationBar.setOnSeekBarChangeListener(change((b,v)->{rotationLabel.setText(String.format(Locale.US,"ROTATION: %.1f°",rotation()));queueRenderOnly();}));root.addView(rotationBar);

        root.addView(section("EXPORT"));exportSpinner=spinner(new String[]{"32-bit float WAV · maximum XY precision","24-bit PCM WAV"},0,false);root.addView(exportSpinner);
        TextView note=label("LEFT = X   •   RIGHT = Y\nThe source is compiled into a small set of periodic Fourier curves. Open strokes retrace rather than drawing fake closing lines. Natural geometry is the default sound source; 81/64/49 are optional slow motion clocks, not tones mixed into X/Y.");note.setTextColor(Color.rgb(130,164,150));note.setTextSize(11);root.addView(note);
        status=new TextView(this);status.setTextColor(Color.rgb(195,255,220));status.setTextSize(12.5f);status.setPadding(0,dp(7),0,dp(24));root.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);return scroll;
    }

    private TextView section(String s){TextView t=label(s);t.setTextColor(Color.rgb(154,248,187));t.setTextSize(13);t.setPadding(0,dp(10),0,dp(2));return t;}
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.rgb(190,220,207));t.setTextSize(12);t.setPadding(0,dp(4),0,dp(2));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(9.8f);return b;}
    private CheckBox check(String s,boolean v){CheckBox b=new CheckBox(this);b.setText(s);b.setTextColor(Color.rgb(205,230,218));b.setChecked(v);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setPadding(0,dp(3),0,dp(5));return r;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(2),0,dp(2),0);return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private interface S{void run(int v);}private SeekBar slider(int v,S a){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(v);b.setOnSeekBarChangeListener(change((x,n)->a.run(n)));return b;}
    private interface C{void run(SeekBar b,int v);}private SeekBar.OnSeekBarChangeListener change(C c){return new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean u){if(u&&!uiChanging)c.run(b,v);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};}
    private Spinner spinner(String[]v,int pos,boolean rebuild){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,v);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setSelection(pos);s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?>p,View v,int x,long id){if(!uiChanging){if(rebuild)queueRebuild();else queueRenderOnly();}}public void onNothingSelected(AdapterView<?>p){}});return s;}
    private SeekBar gain(TextView t,String prefix){SeekBar b=new SeekBar(this);b.setMax(100);b.setProgress(50);b.setOnSeekBarChangeListener(change((x,v)->{t.setText(prefix+": "+(50+v)+"%");queueRenderOnly();}));return b;}

    private void openMedia(){stopPlayback();Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});startActivityForResult(i,REQ_OPEN);}
    private void chooseSave(){if(sourceKind==NONE){toast("Load media or open demo first.");return;}stopPlayback();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/wav");i.putExtra(Intent.EXTRA_TITLE,"oscivision_v7_phase_scene_"+System.currentTimeMillis()+".wav");startActivityForResult(i,REQ_SAVE);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri u=data.getData();if(req==REQ_OPEN){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Throwable ignored){}loadSource(u);}else if(req==REQ_SAVE)export(u);}

    private void loadSource(Uri u){sourceUri=u;currentScene=null;status.setText("Decoding source…");worker.submit(()->{try{releaseSource();ContentResolver cr=getContentResolver();String mime=cr.getType(u),lower=u.toString().toLowerCase(Locale.US);if(mime!=null&&mime.startsWith("video/")){sourceKind=VIDEO;retriever=new MediaMetadataRetriever();retriever.setDataSource(this,u);durationMs=parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),1000);sourceWidth=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),640);sourceHeight=parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),480);Bitmap first=getVideoFrame(0);if(first==null)throw new IllegalStateException("Video decoder returned no frame");runOnUiThread(()->{sourcePreview.setImageBitmap(first);preset(3);buildScene(first);});}
        else if((mime!=null&&mime.equals("image/gif"))||lower.endsWith(".gif")){sourceKind=GIF;gifBytes=readAll(cr.openInputStream(u));gifMovie=Movie.decodeByteArray(gifBytes,0,gifBytes.length);if(gifMovie==null){sourceKind=IMAGE;stillBitmap=decodeBitmap(u);durationMs=6000;runOnUiThread(()->{sourcePreview.setImageBitmap(stillBitmap);preset(0);buildScene(stillBitmap);});}else{durationMs=gifMovie.duration()>0?gifMovie.duration():1000;sourceWidth=gifMovie.width();sourceHeight=gifMovie.height();Bitmap first=getGifFrame(0);runOnUiThread(()->{sourcePreview.setImageBitmap(first);preset(3);buildScene(first);});}}
        else{sourceKind=IMAGE;stillBitmap=decodeBitmap(u);if(stillBitmap==null)throw new IllegalStateException("Image decode failed");durationMs=6000;sourceWidth=stillBitmap.getWidth();sourceHeight=stillBitmap.getHeight();runOnUiThread(()->{sourcePreview.setImageBitmap(stillBitmap);preset(0);buildScene(stillBitmap);});}}
        catch(Throwable t){runOnUiThread(()->status.setText("Load error: "+safe(t)));}});}

    private Bitmap decodeBitmap(Uri u)throws Exception{if(Build.VERSION.SDK_INT>=28){ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),u);return ImageDecoder.decodeBitmap(src,(d,info,s)->d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));}try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(u))){return BitmapFactory.decodeStream(in);}}

    private V7SceneEngine.Settings settings(){V7SceneEngine.Settings s=new V7SceneEngine.Settings();s.sampleRate=sampleRate();s.sceneHz=sceneHz();s.profile=profileSpinner.getSelectedItemPosition();s.toneShells=2+shellSpinner.getSelectedItemPosition();s.detail=detailBar.getProgress();s.structure=structureBar.getProgress()/100f;s.depth=depthBar.getProgress()/100f*.55f;s.perspective=perspectiveBar.getProgress()/100f*.55f;s.motion=motionBar.getProgress()/100f;s.gamma=gamma();s.invert=invertBox.isChecked();s.suppressBorders=borderBox.isChecked();s.motionBank=motionBankSpinner.getSelectedItemPosition();s.xGain=(50+xGainBar.getProgress())/100f;s.yGain=(50+yGainBar.getProgress())/100f;s.rotationDeg=rotation();return s;}
    private int sampleRate(){int p=sampleSpinner.getSelectedItemPosition();return p==1?96000:p==2?48000:192000;}private int sceneHz(){switch(sceneSpinner.getSelectedItemPosition()){case 0:return 12;case 1:return 20;case 2:return 30;default:return 40;}}private float gamma(){return .40f+gammaBar.getProgress()/100f;}private float rotation(){return(rotationBar.getProgress()-120)/10f;}

    private void buildScene(Bitmap b){if(b==null)return;V7SceneEngine.Settings s=settings();int gen=generation.incrementAndGet();status.setText("Compiling source into Fourier scene…");worker.submit(()->{try{V7SceneEngine.Scene sc=V7SceneEngine.buildScene(b,s);V7SceneEngine.Result r=V7SceneEngine.render(sc,s,0);if(gen==generation.get()){currentScene=sc;runOnUiThread(()->show(r,false));}}catch(Throwable t){runOnUiThread(()->status.setText("Scene compile error: "+safe(t)));}});}
    private void queueRebuild(){if(sourceKind==NONE||playing.get())return;if(sourceKind==DEMO){showDemo();return;}if(sourceKind!=IMAGE||stillBitmap==null)return;if(!rebuildQueued.compareAndSet(false,true))return;V7SceneEngine.Settings s=settings();Bitmap b=stillBitmap;int gen=generation.incrementAndGet();worker.submit(()->{try{SystemClock.sleep(40);V7SceneEngine.Scene sc=V7SceneEngine.buildScene(b,s);V7SceneEngine.Result r=V7SceneEngine.render(sc,s,0);if(gen==generation.get()){currentScene=sc;runOnUiThread(()->show(r,false));}}catch(Throwable t){runOnUiThread(()->status.setText("Scene compile error: "+safe(t)));}finally{rebuildQueued.set(false);}});}
    private void queueRenderOnly(){if(currentScene==null||playing.get())return;V7SceneEngine.Settings s=settings();V7SceneEngine.Scene sc=currentScene;worker.submit(()->{try{V7SceneEngine.Result r=V7SceneEngine.render(sc,s,0);runOnUiThread(()->show(r,false));}catch(Throwable ignored){}});}

    private void show(V7SceneEngine.Result r,boolean live){scope.setResult(r);String route=live?" · "+audio.sampleRate()+" Hz · "+audio.routeName()+" · underruns "+audio.underruns():"";status.setText(r.mode+" · "+r.sceneHz+" Hz scene\n"+r.objects+" periodic objects · avg Fourier order "+r.harmonics+" · rejected borders "+r.rejectedBorders+" · flybacks "+r.flybacks+" · RMS "+String.format(Locale.US,"%.4f",r.rmsStep)+route);}

    private void showDemo(){stopPlayback();sourceKind=DEMO;V7SceneEngine.Settings s=settings();currentScene=V7SceneEngine.demoScene(s);sourcePreview.setVisibility(View.GONE);V7SceneEngine.Result r=V7SceneEngine.render(currentScene,s,0);show(r,false);status.setText("PARAMETRIC DEMO\n"+r.objects+" Fourier objects · this bypasses image tracing and tests the phase-scene renderer itself.");}

    private void togglePlayback(){if(sourceKind==NONE){toast("Load media or open demo first.");return;}if(playing.get())stopPlayback();else startPlayback();}
    private void startPlayback(){stopPlayback();V7SceneEngine.Settings req=settings();int rate;try{rate=audio.start(req.sampleRate);}catch(Throwable t){status.setText("Audio start error: "+safe(t));return;}V7SceneEngine.Settings s=req.copy();s.sampleRate=rate;playing.set(true);playButton.setText("STOP");animationThread=new Thread(()->{long start=SystemClock.elapsedRealtime(),lastSource=-100000;int ui=0;V7SceneEngine.Scene scene=currentScene;while(playing.get()){long now=SystemClock.elapsedRealtime(),elapsed=now-start;double sec=elapsed/1000.0;try{if((sourceKind==VIDEO||sourceKind==GIF)&&(elapsed-lastSource>=83||scene==null)){long mt=durationMs>0?elapsed%durationMs:elapsed;Bitmap f=sourceKind==VIDEO?getVideoFrame(mt):getGifFrame(mt);if(f!=null){scene=V7SceneEngine.buildScene(f,s);currentScene=scene;Bitmap shown=f;if(sourceBox.isChecked())runOnUiThread(()->sourcePreview.setImageBitmap(shown));lastSource=elapsed;}}if(scene==null){SystemClock.sleep(5);continue;}V7SceneEngine.Result r=V7SceneEngine.render(scene,s,sec);audio.setFrame(r.xy);if((ui++&1)==0)runOnUiThread(()->show(r,true));long frameMs=Math.max(16,1000/Math.max(20,s.sceneHz));SystemClock.sleep(frameMs);}catch(Throwable t){runOnUiThread(()->status.setText("Playback error: "+safe(t)));SystemClock.sleep(10);}}},"OsciVisionV7PhaseScene");animationThread.setPriority(Thread.NORM_PRIORITY+1);animationThread.start();}
    private void stopPlayback(){playing.set(false);if(animationThread!=null){animationThread.interrupt();animationThread=null;}audio.stop();if(playButton!=null)playButton.setText("PLAY XY");}

    private void export(Uri dest){if(sourceKind==NONE)return;V7SceneEngine.Settings s=settings();boolean f32=exportSpinner.getSelectedItemPosition()==0;status.setText("Rendering parametric scene WAV…");worker.submit(()->{File tmp=new File(getCacheDir(),"oscivision_v7_"+System.currentTimeMillis()+".wav");try(V4Audio.WavWriter writer=V4Audio.createWriter(tmp,s.sampleRate,f32)){long dur=sourceKind==IMAGE||sourceKind==DEMO?6000:Math.max(1,durationMs);int tiles=Math.max(1,(int)Math.ceil(dur*s.sceneHz/1000.0));V7SceneEngine.Scene scene=currentScene;long lastScene=-1;for(int i=0;i<tiles;i++){long t=Math.min(dur-1,Math.round(i*1000.0/s.sceneHz));if(sourceKind==VIDEO||sourceKind==GIF){if(scene==null||t-lastScene>=83){Bitmap b=sourceKind==VIDEO?getVideoFrame(t):getGifFrame(t);if(b!=null){scene=V7SceneEngine.buildScene(b,s);lastScene=t;}}}if(scene==null)continue;V7SceneEngine.Result r=V7SceneEngine.render(scene,s,t/1000.0);writer.write(r.xy);if(i%Math.max(1,s.sceneHz)==0){final int pct=Math.min(99,Math.round(i*100f/tiles));runOnUiThread(()->status.setText("Rendering v7 WAV… "+pct+"%"));}}
        try(InputStream in=new BufferedInputStream(new FileInputStream(tmp));OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(dest,"w"))){byte[]buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}runOnUiThread(()->status.setText("Saved · "+(f32?"32-bit float":"24-bit PCM")+" · stereo master-phasor XY · "+s.sampleRate+" Hz"));}catch(Throwable t){runOnUiThread(()->status.setText("Export error: "+safe(t)));}finally{try{if(tmp.exists())tmp.delete();}catch(Throwable ignored){}}});}

    private Bitmap getVideoFrame(long ms){if(retriever==null)return null;long us=Math.max(0,ms)*1000L;try{if(Build.VERSION.SDK_INT>=27){int max=720,w=sourceWidth<=0?max:sourceWidth,h=sourceHeight<=0?max:sourceHeight;float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap b=retriever.getScaledFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST,Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)));if(b!=null)return b;}}catch(Throwable ignored){}return retriever.getFrameAtTime(us,MediaMetadataRetriever.OPTION_CLOSEST);}
    private Bitmap getGifFrame(long ms){if(gifMovie==null)return null;int max=720,w=Math.max(1,gifMovie.width()),h=Math.max(1,gifMovie.height());float sc=Math.min(1f,max/(float)Math.max(w,h));Bitmap out=Bitmap.createBitmap(Math.max(2,Math.round(w*sc)),Math.max(2,Math.round(h*sc)),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.drawColor(Color.BLACK);c.scale(sc,sc);synchronized(this){gifMovie.setTime((int)(ms%Math.max(1,durationMs)));gifMovie.draw(c,0,0);}return out;}

    private void maxMode(){preset(sourceKind==VIDEO||sourceKind==GIF?3:0);queueRebuild();}
    private void preset(int p){uiChanging=true;profileSpinner.setSelection(p);sampleSpinner.setSelection(0);if(p==0){sceneSpinner.setSelection(1);shellSpinner.setSelection(3);detailBar.setProgress(96);structureBar.setProgress(94);depthBar.setProgress(8);perspectiveBar.setProgress(10);motionBar.setProgress(8);motionBankSpinner.setSelection(0);gammaBar.setProgress(50);borderBox.setChecked(true);}else if(p==1){sceneSpinner.setSelection(1);shellSpinner.setSelection(4);detailBar.setProgress(100);structureBar.setProgress(100);depthBar.setProgress(6);perspectiveBar.setProgress(8);motionBar.setProgress(6);motionBankSpinner.setSelection(0);gammaBar.setProgress(46);borderBox.setChecked(true);}else if(p==2){sceneSpinner.setSelection(2);shellSpinner.setSelection(0);detailBar.setProgress(100);structureBar.setProgress(100);depthBar.setProgress(12);perspectiveBar.setProgress(14);motionBar.setProgress(12);motionBankSpinner.setSelection(0);gammaBar.setProgress(55);borderBox.setChecked(true);}else{sceneSpinner.setSelection(2);shellSpinner.setSelection(2);detailBar.setProgress(72);structureBar.setProgress(92);depthBar.setProgress(8);perspectiveBar.setProgress(10);motionBar.setProgress(5);motionBankSpinner.setSelection(0);gammaBar.setProgress(50);borderBox.setChecked(true);}uiChanging=false;refreshLabels();scope.setPersistence(persistenceBar.getProgress());scope.setIntensity(intensityBar.getProgress());scope.setBloom(bloomBar.getProgress());scope.setBeamWidth(beamBar.getProgress());}
    private void refreshLabels(){detailLabel.setText("FOURIER / OBJECT DETAIL: "+detailBar.getProgress()+"%");structureLabel.setText("STRUCTURE PRIORITY: "+structureBar.getProgress()+"%");depthLabel.setText("DEPTH: "+depthBar.getProgress()+"%");perspectiveLabel.setText("PERSPECTIVE: "+perspectiveBar.getProgress()+"%");motionLabel.setText("SCENE MOTION: "+motionBar.getProgress()+"%");gammaLabel.setText(String.format(Locale.US,"IMAGE GAMMA: %.2f",gamma()));}
    private void showTest(boolean circle){V7SceneEngine.Settings s=settings();int n=Math.max(2048,s.sampleRate/Math.max(8,s.sceneHz));float[]xy=circle?V7SceneEngine.circle(s,n):V7SceneEngine.grid(s,n);scope.setTrace(xy,circle?"CAL CIRCLE":"CAL GRID");if(playing.get())audio.setFrame(xy);}

    private void releaseSource(){stillBitmap=null;gifMovie=null;gifBytes=null;durationMs=0;sourceWidth=sourceHeight=0;currentScene=null;if(retriever!=null){try{retriever.release();}catch(Throwable ignored){}retriever=null;}}
    @Override protected void onDestroy(){stopPlayback();releaseSource();worker.shutdownNow();super.onDestroy();}
    private static byte[]readAll(InputStream in)throws Exception{if(in==null)return new byte[0];try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[65536];int n;while((n=x.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private static long parseLong(String s,long f){try{return Long.parseLong(s);}catch(Throwable t){return f;}}private static int parseInt(String s,int f){try{return Integer.parseInt(s);}catch(Throwable t){return f;}}private static String safe(Throwable t){if(t==null)return"unknown";String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
