package org.vhanma.dnaforgemax;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Writes each FRAMEFORGE XY block exactly once. Queue starvation repeats the last block. */
final class V8FrameAudio {
    private final ArrayBlockingQueue<float[]> queue=new ArrayBlockingQueue<>(4);
    private final AtomicBoolean running=new AtomicBoolean(false);
    private AudioTrack track; private Thread thread; private int rate=48000,fps=30,pairs=1600;
    private volatile int underruns=0,dropped=0,repeated=0;
    private volatile float[] last;

    int start(int requestedRate,int fps){
        stop();this.fps=Math.max(1,fps);
        int[]rates=requestedRate>=192000?new int[]{192000,96000,48000}:requestedRate>=96000?new int[]{96000,48000}:new int[]{48000};
        RuntimeException fail=null;
        for(int r:rates){
            try{
                int min=AudioTrack.getMinBufferSize(r,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);
                if(min<=0)min=32768;int block=Math.max(256,Math.round(r/(float)this.fps));
                int bytes=Math.max(min*2,block*2*4*3);
                track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes).build();
                if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new RuntimeException("AudioTrack failed at "+r+" Hz");
                rate=r;pairs=block;queue.clear();last=null;underruns=dropped=repeated=0;track.play();running.set(true);
                thread=new Thread(this::loop,"FRAMEFORGE-Audio");thread.setPriority(Thread.MAX_PRIORITY);thread.start();return rate;
            }catch(RuntimeException e){fail=e;release();}
        }
        if(fail!=null)throw fail;return rate;
    }

    private void loop(){
        while(running.get()){
            try{
                float[]f=queue.poll(Math.max(2,Math.round(1000f/fps/2)),TimeUnit.MILLISECONDS);
                if(f==null){f=last;if(f==null)continue;repeated++;}
                last=f;writeExact(f);
                if(Build.VERSION.SDK_INT>=24&&track!=null)try{underruns=track.getUnderrunCount();}catch(Throwable ignored){}
            }catch(InterruptedException e){Thread.currentThread().interrupt();break;}catch(Throwable ignored){}
        }
    }

    private void writeExact(float[]src){AudioTrack t=track;if(t==null)return;int need=pairs*2,off=0;while(running.get()&&off<need){int remain=need-off;int n;
        if(src.length==need)n=t.write(src,off,remain,AudioTrack.WRITE_BLOCKING);
        else{float[]fixed=new float[need];System.arraycopy(src,0,fixed,0,Math.min(src.length,need));n=t.write(fixed,off,remain,AudioTrack.WRITE_BLOCKING);src=fixed;}
        if(n>0)off+=n;else if(n<0)break;
    }}

    void offer(float[]xy){if(xy==null)return;if(!queue.offer(xy)){queue.poll();if(!queue.offer(xy))return;dropped++;}}
    int rate(){return rate;}int pairs(){return pairs;}int dropped(){return dropped;}int repeated(){return repeated;}int underruns(){return underruns;}
    int queued(){return queue.size();}
    String route(){AudioTrack t=track;if(t==null||Build.VERSION.SDK_INT<23)return "route unknown";try{AudioDeviceInfo d=t.getRoutedDevice();if(d==null)return "default route";String p=d.getProductName()==null?"":d.getProductName().toString();return type(d.getType())+(p.isEmpty()?"":" · "+p);}catch(Throwable e){return "default route";}}
    void stop(){running.set(false);if(thread!=null){thread.interrupt();thread=null;}queue.clear();last=null;release();}
    private void release(){AudioTrack t=track;track=null;if(t!=null){try{t.pause();}catch(Throwable ignored){}try{t.flush();}catch(Throwable ignored){}try{t.stop();}catch(Throwable ignored){}try{t.release();}catch(Throwable ignored){}}}
    private static String type(int t){switch(t){case AudioDeviceInfo.TYPE_USB_DEVICE:return "USB audio";case AudioDeviceInfo.TYPE_USB_HEADSET:return "USB headset";case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return "wired headphones";case AudioDeviceInfo.TYPE_WIRED_HEADSET:return "wired headset";case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:return "Bluetooth";case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return "speaker";default:return "audio device "+t;}}
}
