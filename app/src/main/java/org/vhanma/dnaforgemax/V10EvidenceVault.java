package org.vhanma.dnaforgemax;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Rolling pre-trigger frame/sensor buffer + event package writer. */
final class V10EvidenceVault {
    static final class BufferedFrame {
        final long timeMs; final byte[]jpeg; final V10SensorFusion.Snapshot sensor;
        BufferedFrame(long t,byte[]j,V10SensorFusion.Snapshot s){timeMs=t;jpeg=j;sensor=s;}
    }
    static final class Event {
        final File dir; final long startedMs; int tier; float score; String filter;
        Event(File d,long t,int tier,float score,String filter){dir=d;startedMs=t;this.tier=tier;this.score=score;this.filter=filter;}
    }

    private final Context context;
    private final Deque<BufferedFrame> ring=new ArrayDeque<>();
    private long keepMs=8000,lastPush=0;
    private Event active;

    V10EvidenceVault(Context c){context=c.getApplicationContext();}
    synchronized void setPrebufferSeconds(int seconds){keepMs=Math.max(2,Math.min(20,seconds))*1000L;trim(System.currentTimeMillis());}

    synchronized void push(Bitmap frame,V10SensorFusion.Snapshot sensor){
        if(frame==null)return;long now=System.currentTimeMillis();if(now-lastPush<180)return;lastPush=now;
        Bitmap small=Bitmap.createScaledBitmap(frame,320,Math.max(180,Math.round(frame.getHeight()*(320f/Math.max(1,frame.getWidth())))),true);
        ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,72,out);ring.addLast(new BufferedFrame(now,out.toByteArray(),sensor));trim(now);
    }

    synchronized Event beginEvent(V10HunterEngine.Result r,Bitmap filtered,V10SensorFusion.Snapshot sensor){
        long now=System.currentTimeMillis();
        if(active!=null&&now-active.startedMs<12000){active.tier=Math.max(active.tier,r.tier);active.score=Math.max(active.score,r.score);return active;}
        File root=new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),"OsciVisionHunter/events");root.mkdirs();
        String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date(now));File dir=new File(root,"event_"+stamp);dir.mkdirs();
        Event e=new Event(dir,now,r.tier,r.score,r.filter.name());active=e;
        int n=0;for(BufferedFrame bf:ring){writeBytes(new File(dir,String.format(Locale.US,"pre_%03d_%d.jpg",n++,bf.timeMs)),bf.jpeg);}
        if(filtered!=null)writeBitmap(new File(dir,"trigger_filter.jpg"),filtered,92);
        writeManifest(e,r,sensor,ring.size());return e;
    }

    synchronized void savePostFrame(Bitmap frame){if(active==null||frame==null)return;long now=System.currentTimeMillis();writeBitmap(new File(active.dir,"post_"+now+".jpg"),frame,85);}
    synchronized void endEvent(){active=null;}
    synchronized Event active(){return active;}

    List<File> listEvents(){
        File root=new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),"OsciVisionHunter/events");File[]a=root.listFiles();List<File>out=new ArrayList<>();if(a!=null){java.util.Arrays.sort(a,(x,y)->Long.compare(y.lastModified(),x.lastModified()));for(File f:a)if(f.isDirectory())out.add(f);}return out;
    }

    private void trim(long now){while(!ring.isEmpty()&&now-ring.peekFirst().timeMs>keepMs)ring.removeFirst();while(ring.size()>120)ring.removeFirst();}
    private void writeManifest(Event e,V10HunterEngine.Result r,V10SensorFusion.Snapshot s,int pre){
        String json="{\n"+
                "  \"started_ms\": "+e.startedMs+",\n"+
                "  \"tier\": "+r.tier+",\n"+
                "  \"score\": "+fmt(r.score)+",\n"+
                "  \"filter\": \""+r.filter.name()+"\",\n"+
                "  \"motion\": "+fmt(r.motion)+",\n"+
                "  \"structure\": "+fmt(r.structure)+",\n"+
                "  \"code_score\": "+fmt(r.code)+",\n"+
                "  \"bent_score\": "+fmt(r.bent)+",\n"+
                "  \"sensor_mismatch\": "+fmt(r.sensorMismatch)+",\n"+
                "  \"rectangle_penalty\": "+fmt(r.rectanglePenalty)+",\n"+
                "  \"persistence\": "+r.persistence+",\n"+
                "  \"prebuffer_frames\": "+pre+",\n"+
                "  \"gyro\": "+fmt(s==null?0:s.gyro)+",\n"+
                "  \"accel\": "+fmt(s==null?0:s.accel)+",\n"+
                "  \"magnetic\": "+fmt(s==null?0:s.magnetic)+",\n"+
                "  \"light\": "+fmt(s==null?0:s.light)+"\n"+
                "}\n";
        writeBytes(new File(e.dir,"manifest.json"),json.getBytes(StandardCharsets.UTF_8));
    }
    private static String fmt(float v){return String.format(Locale.US,"%.5f",v);}
    private static void writeBitmap(File f,Bitmap b,int q){try(FileOutputStream o=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,q,o);}catch(Throwable ignored){}}
    private static void writeBytes(File f,byte[]b){try(FileOutputStream o=new FileOutputStream(f)){o.write(b);}catch(Throwable ignored){}}
}
