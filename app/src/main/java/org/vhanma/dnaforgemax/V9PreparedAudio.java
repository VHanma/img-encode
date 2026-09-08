package org.vhanma.dnaforgemax;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

/** Direct blocking AudioTrack writer for prepared v9 frame blocks. */
final class V9PreparedAudio {
    private AudioTrack track;private int rate=48000,pairs=800;private volatile int underruns=0;
    int start(int requestedRate,int fps){stop();int[]rates=requestedRate>=192000?new int[]{192000,96000,48000}:requestedRate>=96000?new int[]{96000,48000}:new int[]{48000};RuntimeException fail=null;for(int r:rates){try{pairs=Math.max(256,Math.round(r/(float)Math.max(1,fps)));int min=AudioTrack.getMinBufferSize(r,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);if(min<=0)min=pairs*2*4;int bytes=Math.max(min,pairs*2*4*2);track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(r).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes).build();if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new RuntimeException("AudioTrack failed at "+r+" Hz");rate=r;underruns=0;track.play();return rate;}catch(RuntimeException e){fail=e;release();}}if(fail!=null)throw fail;return rate;}
    void writeFrame(float[]xy){AudioTrack t=track;if(t==null||xy==null)return;int need=pairs*2;if(xy.length!=need){float[]f=new float[need];System.arraycopy(xy,0,f,0,Math.min(xy.length,need));xy=f;}int off=0;while(track!=null&&off<need){int n=t.write(xy,off,need-off,AudioTrack.WRITE_BLOCKING);if(n>0)off+=n;else if(n<0)break;}if(Build.VERSION.SDK_INT>=24&&track!=null)try{underruns=track.getUnderrunCount();}catch(Throwable ignored){}}
    int rate(){return rate;}int pairs(){return pairs;}int underruns(){return underruns;}
    String route(){AudioTrack t=track;if(t==null||Build.VERSION.SDK_INT<23)return "route unknown";try{AudioDeviceInfo d=t.getRoutedDevice();if(d==null)return "default route";String p=d.getProductName()==null?"":d.getProductName().toString();return type(d.getType())+(p.isEmpty()?"":" · "+p);}catch(Throwable e){return "default route";}}
    void stop(){release();}
    private void release(){AudioTrack t=track;track=null;if(t!=null){try{t.pause();}catch(Throwable ignored){}try{t.flush();}catch(Throwable ignored){}try{t.stop();}catch(Throwable ignored){}try{t.release();}catch(Throwable ignored){}}}
    private static String type(int t){switch(t){case AudioDeviceInfo.TYPE_USB_DEVICE:return "USB audio";case AudioDeviceInfo.TYPE_USB_HEADSET:return "USB headset";case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return "wired headphones";case AudioDeviceInfo.TYPE_WIRED_HEADSET:return "wired headset";case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:return "Bluetooth";case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return "speaker";default:return "audio device "+t;}}
}
