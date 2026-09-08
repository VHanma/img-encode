package org.vhanma.dnaforgemax;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OsciVision Hunter Ω v10.
 * Camera anomaly watcher + smart filters + DMT Code/Bent Light experimental modes + evidence
 * capture, while preserving the prepared v9 oscilloscope engine as a dedicated subsystem.
 */
public class UltraV10Activity extends ComponentActivity {
    private static final int REQ_CAMERA=10010;
    private final ExecutorService cameraExecutor=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());

    private PreviewView previewView;
    private V10HunterOverlayView overlay;
    private TextView hunterStatus,filterScoresText,evidenceText,settingsText;
    private LinearLayout hunterPanel,filtersPanel,specialPanel,osciPanel,evidencePanel,settingsPanel;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private CameraSelector selector=CameraSelector.DEFAULT_BACK_CAMERA;

    private V10SensorFusion sensors;
    private V10EvidenceVault vault;
    private final V10HunterEngine.State hunterState=new V10HunterEngine.State();
    private volatile V10HunterEngine.Result lastResult;
    private volatile Bitmap lastOriginal;
    private volatile boolean autoCapture=true,autoRecord=true,autoFilter=true,showHeatmap=true,showFiltered=true;
    private volatile float threshold=.58f;
    private volatile V10HunterEngine.Filter manualFilter=V10HunterEngine.Filter.NORMAL;
    private volatile long lastAnalyzedNs=0,lastCaptureMs=0,recordUntilMs=0;
    private int prebufferSeconds=8;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sensors=new V10SensorFusion(this);vault=new V10EvidenceVault(this);vault.setPrebufferSeconds(prebufferSeconds);
        setContentView(buildUi());showPanel(hunterPanel);ensureCamera();
    }

    @Override protected void onResume(){super.onResume();sensors.start();}
    @Override protected void onPause(){sensors.stop();super.onPause();}
    @Override protected void onDestroy(){stopRecording();if(cameraProvider!=null)cameraProvider.unbindAll();cameraExecutor.shutdownNow();super.onDestroy();}

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(2,5,8));root.setPadding(dp(8),dp(8),dp(8),dp(8));
        TextView title=t("OSCIVISION HUNTER Ω v10",27,Color.rgb(196,255,222));title.setGravity(Gravity.CENTER);root.addView(title);
        TextView sub=t("AI ANOMALY WATCH • SMART FILTER HUNT • DMT CODE • BENT LIGHT • REFERENCE TRACE",10.5f,Color.rgb(110,220,160));sub.setGravity(Gravity.CENTER);root.addView(sub);
        LinearLayout nav=row();String[]names={"HUNTER","FILTERS","SPECIAL","OSCI","EVIDENCE","SETTINGS"};for(String n:names){Button x=button(n);nav.addView(x,weight());x.setOnClickListener(v->nav(n));}root.addView(nav);
        FrameLayout host=new FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));
        hunterPanel=buildHunter();filtersPanel=buildFilters();specialPanel=buildSpecial();osciPanel=buildOsci();evidencePanel=buildEvidence();settingsPanel=buildSettings();
        for(View p:new View[]{hunterPanel,filtersPanel,specialPanel,osciPanel,evidencePanel,settingsPanel})host.addView(p,new FrameLayout.LayoutParams(-1,-1));
        return root;
    }

    private LinearLayout buildHunter(){
        LinearLayout p=panel();FrameLayout cameraBox=new FrameLayout(this);previewView=new PreviewView(this);previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);overlay=new V10HunterOverlayView(this);cameraBox.addView(previewView,new FrameLayout.LayoutParams(-1,-1));cameraBox.addView(overlay,new FrameLayout.LayoutParams(-1,-1));p.addView(cameraBox,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=row();Button hunt=button("AUTO HUNT");Button mark=button("MARK EVENT");Button rec=button("RECORD NOW");Button flip=button("FLIP");actions.addView(hunt,weight());actions.addView(mark,weight());actions.addView(rec,weight());actions.addView(flip,weight());p.addView(actions);
        hunt.setOnClickListener(v->{autoFilter=true;autoCapture=true;autoRecord=true;toast("AUTO HUNT armed");});mark.setOnClickListener(v->manualMark());rec.setOnClickListener(v->{if(recording==null)startRecording(15000);else stopRecording();});flip.setOnClickListener(v->flipCamera());
        hunterStatus=t("Camera starting…",12.2f,Color.rgb(198,255,220));p.addView(hunterStatus);return p;
    }

    private LinearLayout buildFilters(){
        LinearLayout p=scrollPanel();p.addView(section("SMART FILTER HUNT"));CheckBox auto=check("AUTO choose filter from anomaly/sensor scores",true);auto.setOnCheckedChangeListener((b,v)->autoFilter=v);p.addView(auto);
        p.addView(t("AUTO uses hysteresis-like score separation: it stays with ordinary views until Motion, Edge, DMT Code, Bent Light, Low-Light, or Chroma has a meaningful advantage.",11,Color.rgb(145,180,165)));
        LinearLayout r1=row(),r2=row();addFilterButton(r1,"NORMAL",V10HunterEngine.Filter.NORMAL);addFilterButton(r1,"EDGE",V10HunterEngine.Filter.EDGE);addFilterButton(r1,"MOTION",V10HunterEngine.Filter.MOTION);addFilterButton(r1,"HIGH CONTRAST",V10HunterEngine.Filter.HIGH_CONTRAST);addFilterButton(r2,"LOW LIGHT",V10HunterEngine.Filter.LOW_LIGHT);addFilterButton(r2,"CHROMA",V10HunterEngine.Filter.CHROMA);addFilterButton(r2,"DMT CODE",V10HunterEngine.Filter.DMT_CODE);addFilterButton(r2,"BENT LIGHT",V10HunterEngine.Filter.BENT_LIGHT);p.addView(r1);p.addView(r2);
        CheckBox filt=check("Show processed filter over camera",true);filt.setOnCheckedChangeListener((b,v)->{showFiltered=v;overlay.setShowFiltered(v);});p.addView(filt);CheckBox heat=check("Show anomaly heatmap",true);heat.setOnCheckedChangeListener((b,v)->{showHeatmap=v;overlay.setShowHeatmap(v);});p.addView(heat);
        filterScoresText=t("Waiting for camera analysis…",12,Color.rgb(200,245,218));p.addView(filterScoresText);return p;
    }

    private LinearLayout buildSpecial(){
        LinearLayout p=scrollPanel();p.addView(section("DMT CODE MODE"));p.addView(t("Experimental code-like pattern visualizer. It boosts micro-contrast, alternating fine structure, chromatic phase and high-frequency edges. Its CODE score measures image-pattern behavior only.",11.3f,Color.rgb(170,205,190)));Button d=button("LOCK DMT CODE + FEED HUNTER");d.setOnClickListener(v->{autoFilter=false;manualFilter=V10HunterEngine.Filter.DMT_CODE;toast("DMT CODE locked");});p.addView(d);
        p.addView(section("BENT LIGHT MODE"));p.addView(t("Experimental refractive/warp heuristic. It emphasizes changing edge geometry, temporal displacement and disagreement between visual motion and phone motion sensors.",11.3f,Color.rgb(170,205,190)));Button bl=button("LOCK BENT LIGHT + FEED HUNTER");bl.setOnClickListener(v->{autoFilter=false;manualFilter=V10HunterEngine.Filter.BENT_LIGHT;toast("BENT LIGHT locked");});p.addView(bl);
        p.addView(section("OMEGA COMBINED"));Button omega=button("RETURN TO AUTO Ω HUNT");omega.setOnClickListener(v->{autoFilter=true;toast("Omega auto-filter hunt restored");});p.addView(omega);return p;
    }

    private LinearLayout buildOsci(){
        LinearLayout p=scrollPanel();p.addView(section("REFERENCE TRACE / BIOSONIFICATION"));p.addView(t("The v9 prepared-trace system stays separate so heavy image/vector conversion never stalls Hunter camera analysis or oscilloscope playback.",11.5f,Color.rgb(170,205,190)));Button open=button("OPEN PREPARED 60 FPS TRACE ENGINE");open.setOnClickListener(v->startActivity(new Intent(this,UltraV9Activity.class)));p.addView(open);p.addView(t("Rectangle suppression remains mandatory on the Hunter side and the trace branch keeps its large-frame/panel rejection. This v10 launcher does not reintroduce the old rectangle-heavy point-cloud renderer.",11,Color.rgb(140,180,160)));return p;
    }

    private LinearLayout buildEvidence(){
        LinearLayout p=scrollPanel();p.addView(section("EVIDENCE VAULT"));LinearLayout r=row();Button refresh=button("REFRESH");Button snap=button("CAPTURE NOW");Button rec=button("RECORD NOW");r.addView(refresh,weight());r.addView(snap,weight());r.addView(rec,weight());p.addView(r);refresh.setOnClickListener(v->refreshEvidence());snap.setOnClickListener(v->captureHighRes("manual"));rec.setOnClickListener(v->{if(recording==null)startRecording(15000);else stopRecording();});evidenceText=t("No scan yet.",11.8f,Color.rgb(205,240,220));p.addView(evidenceText);return p;
    }

    private LinearLayout buildSettings(){
        LinearLayout p=scrollPanel();p.addView(section("AUTO TRIGGER"));CheckBox ac=check("Auto-capture on Tier 2+",true);ac.setOnCheckedChangeListener((b,v)->autoCapture=v);p.addView(ac);CheckBox ar=check("Auto-record on Tier 3+",true);ar.setOnCheckedChangeListener((b,v)->autoRecord=v);p.addView(ar);
        TextView th=t("ANOMALY THRESHOLD: 58%",12,Color.WHITE);p.addView(th);SeekBar tb=new SeekBar(this);tb.setMax(65);tb.setProgress(38);tb.setOnSeekBarChangeListener(listener(v->{threshold=(20+v)/100f;th.setText("ANOMALY THRESHOLD: "+(20+v)+"%");}));p.addView(tb);
        TextView pre=t("PRE-TRIGGER BUFFER: 8 sec",12,Color.WHITE);p.addView(pre);SeekBar pb=new SeekBar(this);pb.setMax(18);pb.setProgress(6);pb.setOnSeekBarChangeListener(listener(v->{prebufferSeconds=2+v;vault.setPrebufferSeconds(prebufferSeconds);pre.setText("PRE-TRIGGER BUFFER: "+prebufferSeconds+" sec");}));p.addView(pb);
        p.addView(section("HARD ANTI-RECTANGLE RULE"));p.addView(t("Always enabled. Outer tiles are heavily downweighted, full-frame/panel candidates receive an additional penalty, and the Hunter does not offer a switch to turn this protection off.",11.2f,Color.rgb(255,205,160)));
        settingsText=t("Sensor fusion: gyro + accelerometer + magnetic field + ambient light when hardware exposes them.",11.5f,Color.rgb(180,220,200));p.addView(settingsText);return p;
    }

    private void nav(String n){if("HUNTER".equals(n))showPanel(hunterPanel);else if("FILTERS".equals(n))showPanel(filtersPanel);else if("SPECIAL".equals(n))showPanel(specialPanel);else if("OSCI".equals(n))showPanel(osciPanel);else if("EVIDENCE".equals(n)){showPanel(evidencePanel);refreshEvidence();}else showPanel(settingsPanel);}
    private void showPanel(View target){for(View v:new View[]{hunterPanel,filtersPanel,specialPanel,osciPanel,evidencePanel,settingsPanel})if(v!=null)v.setVisibility(v==target?View.VISIBLE:View.GONE);}

    private void ensureCamera(){if(Build.VERSION.SDK_INT<23||checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA);}
    @Override public void onRequestPermissionsResult(int req,@NonNull String[]permissions,@NonNull int[]grants){super.onRequestPermissionsResult(req,permissions,grants);if(req==REQ_CAMERA&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startCamera();else hunterStatus.setText("Camera permission is required for Hunter mode.");}

    private void startCamera(){
        ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);future.addListener(()->{try{cameraProvider=future.get();bindCamera();}catch(Throwable t){hunterStatus.setText("Camera start error: "+safe(t));}},ContextCompat.getMainExecutor(this));
    }
    private void bindCamera(){
        if(cameraProvider==null)return;cameraProvider.unbindAll();
        Preview preview=new Preview.Builder().build();preview.setSurfaceProvider(previewView.getSurfaceProvider());
        imageCapture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
        ImageAnalysis analysis=new ImageAnalysis.Builder().setTargetResolution(new Size(640,480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();analysis.setAnalyzer(cameraExecutor,this::analyzeFrame);
        Recorder recorder=new Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD,FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build();videoCapture=VideoCapture.withOutput(recorder);
        try{cameraProvider.bindToLifecycle(this,selector,preview,imageCapture,analysis,videoCapture);hunterStatus.setText("HUNTER Ω armed · live anomaly analysis running");}catch(Throwable t){hunterStatus.setText("Camera bind error: "+safe(t));}
    }

    private void analyzeFrame(ImageProxy image){
        try{
            long nowNs=System.nanoTime();if(nowNs-lastAnalyzedNs<50_000_000L)return;lastAnalyzedNs=nowNs;
            Bitmap full=yuvToBitmap(image);if(full==null)return;int rot=image.getImageInfo().getRotationDegrees();if(rot!=0)full=rotate(full,rot);
            Bitmap small=Bitmap.createScaledBitmap(full,384,Math.max(216,Math.round(full.getHeight()*(384f/Math.max(1,full.getWidth())))),true);lastOriginal=small;
            V10SensorFusion.Snapshot snap=sensors.snapshot();vault.push(small,snap);
            V10HunterEngine.Result r=V10HunterEngine.analyze(small,hunterState,snap,manualFilter,autoFilter,threshold);lastResult=r;overlay.setResult(r);
            updateHud(r,snap);
            if(r.tier>=1)handleTrigger(r,small,snap);
        }catch(Throwable t){main.post(()->hunterStatus.setText("Analyzer error: "+safe(t)));}finally{image.close();}
    }

    private void handleTrigger(V10HunterEngine.Result r,Bitmap frame,V10SensorFusion.Snapshot snap){
        long now=System.currentTimeMillis();
        if(r.tier>=2&&now-lastCaptureMs>2200){lastCaptureMs=now;vault.beginEvent(r,r.filtered,snap);vault.savePostFrame(frame);if(autoCapture)main.post(()->captureHighRes("tier"+r.tier));}
        if(r.tier>=3&&autoRecord){recordUntilMs=Math.max(recordUntilMs,now+(r.tier>=4?18000:10000));main.post(()->{if(recording==null)startRecording(recordUntilMs-System.currentTimeMillis());scheduleRecordStop();});}
    }

    private void manualMark(){V10HunterEngine.Result r=lastResult;Bitmap b=lastOriginal;if(r==null||b==null){toast("Waiting for first analyzed frame");return;}vault.beginEvent(r,r.filtered,sensors.snapshot());vault.savePostFrame(b);captureHighRes("manual_event");toast("Event marked");}

    private void captureHighRes(String reason){
        if(imageCapture==null)return;ContentValues cv=new ContentValues();String name="Hunter_"+stamp()+"_"+reason+".jpg";cv.put(MediaStore.Images.Media.DISPLAY_NAME,name);cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)cv.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/OsciVisionHunter");
        ImageCapture.OutputFileOptions opt=new ImageCapture.OutputFileOptions.Builder(getContentResolver(),MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv).build();imageCapture.takePicture(opt,ContextCompat.getMainExecutor(this),new ImageCapture.OnImageSavedCallback(){@Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults out){hunterStatus.setText("Evidence image saved · "+reason);}@Override public void onError(@NonNull ImageCaptureException e){hunterStatus.setText("Capture error: "+e.getMessage());}});
    }

    private void startRecording(long durationMs){
        if(videoCapture==null||recording!=null)return;ContentValues cv=new ContentValues();cv.put(MediaStore.Video.Media.DISPLAY_NAME,"Hunter_"+stamp()+".mp4");cv.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");if(Build.VERSION.SDK_INT>=29)cv.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/OsciVisionHunter");
        MediaStoreOutputOptions out=new MediaStoreOutputOptions.Builder(getContentResolver(),MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build();PendingRecording pending=videoCapture.getOutput().prepareRecording(this,out);recordUntilMs=Math.max(recordUntilMs,System.currentTimeMillis()+Math.max(4000,durationMs));recording=pending.start(ContextCompat.getMainExecutor(this),e->{if(e instanceof VideoRecordEvent.Start)hunterStatus.setText("AUTO RECORDING evidence…");if(e instanceof VideoRecordEvent.Finalize){VideoRecordEvent.Finalize f=(VideoRecordEvent.Finalize)e;recording=null;vault.endEvent();hunterStatus.setText(f.hasError()?"Recording finalize error "+f.getError():"Evidence video saved");}});scheduleRecordStop();
    }
    private void scheduleRecordStop(){main.removeCallbacks(recordStopRunnable);main.postDelayed(recordStopRunnable,700);}
    private final Runnable recordStopRunnable=new Runnable(){@Override public void run(){if(recording==null)return;if(System.currentTimeMillis()>=recordUntilMs)stopRecording();else main.postDelayed(this,700);}};
    private void stopRecording(){main.removeCallbacks(recordStopRunnable);Recording r=recording;recording=null;if(r!=null)try{r.stop();}catch(Throwable ignored){}vault.endEvent();}

    private void updateHud(V10HunterEngine.Result r,V10SensorFusion.Snapshot s){
        main.post(()->{
            hunterStatus.setText(String.format(Locale.US,"SCORE %.0f%% · TIER %d · %s · persistence %d\nMotion %.0f · Structure %.0f · Code %.0f · Bent %.0f · Sensor mismatch %.0f",r.score*100,r.tier,r.filter.name(),r.persistence,r.motion*100,r.structure*100,r.code*100,r.bent*100,r.sensorMismatch*100));
            if(filterScoresText!=null){StringBuilder z=new StringBuilder("FILTER LEADERBOARD\n");V10HunterEngine.Filter[]fs=V10HunterEngine.Filter.values();for(int i=0;i<fs.length;i++)z.append(String.format(Locale.US,"%-14s %3.0f%%\n",fs[i].name(),r.filterScores[i]*100));filterScoresText.setText(z.toString());}
            if(settingsText!=null&&s!=null)settingsText.setText(String.format(Locale.US,"SENSORS · gyro %.3f · accel %.3f · magnetic %.1f µT · light %.1f lx",s.gyro,s.accel,s.magnetic,s.light));
        });
    }

    private void refreshEvidence(){List<File> events=vault.listEvents();StringBuilder b=new StringBuilder();b.append("EVENT PACKAGES: ").append(events.size()).append("\n\n");for(int i=0;i<Math.min(30,events.size());i++){File f=events.get(i);File[]inside=f.listFiles();b.append(i+1).append(". ").append(f.getName()).append("  ·  ").append(inside==null?0:inside.length).append(" files\n");}b.append("\nHigh-resolution captures: Pictures/OsciVisionHunter\nRecordings: Movies/OsciVisionHunter\nPre-trigger low-res frames + manifest stay inside each app evidence package.");evidenceText.setText(b.toString());}

    private void flipCamera(){selector=selector==CameraSelector.DEFAULT_BACK_CAMERA?CameraSelector.DEFAULT_FRONT_CAMERA:CameraSelector.DEFAULT_BACK_CAMERA;hunterState.reset();bindCamera();}
    private void addFilterButton(LinearLayout row,String label,V10HunterEngine.Filter f){Button b=button(label);b.setOnClickListener(v->{autoFilter=false;manualFilter=f;toast(label+" locked");});row.addView(b,weight());}

    private Bitmap yuvToBitmap(ImageProxy image){
        ImageProxy.PlaneProxy[]p=image.getPlanes();if(p.length<3)return null;int w=image.getWidth(),h=image.getHeight();int[]out=new int[w*h];ByteBuffer yb=p[0].getBuffer().duplicate(),ub=p[1].getBuffer().duplicate(),vb=p[2].getBuffer().duplicate();int yrs=p[0].getRowStride(),yps=p[0].getPixelStride(),urs=p[1].getRowStride(),ups=p[1].getPixelStride(),vrs=p[2].getRowStride(),vps=p[2].getPixelStride();
        for(int y=0;y<h;y++){int yy=Math.min(y,h-1);for(int x=0;x<w;x++){int yi=yy*yrs+x*yps;int uvY=yy/2,uvX=x/2;int ui=Math.min(ub.limit()-1,uvY*urs+uvX*ups),vi=Math.min(vb.limit()-1,uvY*vrs+uvX*vps);int Y=yb.get(Math.min(yb.limit()-1,yi))&255,U=(ub.get(ui)&255)-128,V=(vb.get(vi)&255)-128;int r=Math.round(Y+1.402f*V),g=Math.round(Y-.344136f*U-.714136f*V),bl=Math.round(Y+1.772f*U);out[y*w+x]=Color.rgb(clamp255(r),clamp255(g),clamp255(bl));}}
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.setPixels(out,0,w,0,0,w,h);return b;
    }
    private static Bitmap rotate(Bitmap b,int deg){Matrix m=new Matrix();m.postRotate(deg);return Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);}

    private LinearLayout panel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setBackgroundColor(Color.rgb(1,3,5));return p;}
    private LinearLayout scrollPanel(){LinearLayout inner=panel();inner.setPadding(dp(5),dp(5),dp(5),dp(20));ScrollView s=new ScrollView(this);s.addView(inner);LinearLayout wrapper=panel();wrapper.addView(s,new LinearLayout.LayoutParams(-1,0,1));return inner;}
    private TextView section(String x){TextView t=t(x,14,Color.rgb(155,255,190));t.setPadding(0,dp(12),0,dp(5));return t;}
    private TextView t(String s,float size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(3),dp(4),dp(3),dp(4));return t;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(8.8f);b.setAllCaps(false);return b;}
    private CheckBox check(String s,boolean c){CheckBox b=new CheckBox(this);b.setText(s);b.setTextColor(Color.rgb(205,235,220));b.setChecked(c);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(1),dp(1),dp(1),dp(1));return p;}
    private interface Val{void set(int v);}private SeekBar.OnSeekBarChangeListener listener(Val v){return new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int x,boolean user){if(user)v.set(x);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static int clamp255(int v){return v<0?0:v>255?255:v;}
    private static String stamp(){return new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date());}
    private static String safe(Throwable t){if(t==null)return "unknown";String m=t.getMessage();return m==null||m.isEmpty()?t.getClass().getSimpleName():m;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
