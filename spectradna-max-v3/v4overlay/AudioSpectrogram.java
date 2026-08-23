package com.vaan.spectradna;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.*;
import java.util.*;

/**
 * Full-band spectrogram analyzer for the actual generated PCM WAV.
 * Resolution is derived from the PCM itself; the renderer does not invent
 * image detail that is absent from the waveform.
 */
public final class AudioSpectrogram {
    private AudioSpectrogram(){}

    public static final class Result {
        public final Bitmap bitmap;
        public final int sampleRate;
        public final int channels;
        public final double secondsAnalyzed;
        public final int fftSize;
        public final double maxFrequencyHz;
        public final double frequencyResolutionHz;
        public final double dynamicRangeDb;
        public final boolean truncated;
        Result(Bitmap bitmap,int sampleRate,int channels,double secondsAnalyzed,int fftSize,double maxFrequencyHz,double frequencyResolutionHz,double dynamicRangeDb,boolean truncated){
            this.bitmap=bitmap;this.sampleRate=sampleRate;this.channels=channels;this.secondsAnalyzed=secondsAnalyzed;
            this.fftSize=fftSize;this.maxFrequencyHz=maxFrequencyHz;this.frequencyResolutionHz=frequencyResolutionHz;
            this.dynamicRangeDb=dynamicRangeDb;this.truncated=truncated;
        }
    }

    private static final int WIDTH=1280;
    private static final int HEIGHT=720;
    private static final int MAX_PCM_BYTES=128*1024*1024;
    private static final double DYNAMIC_RANGE_DB=84.0;

    public static Result render(InputStream raw)throws IOException{
        if(raw==null)throw new IOException("Missing WAV input");
        BufferedInputStream in=new BufferedInputStream(raw,512*1024);
        byte[] head=new byte[12];readFully(in,head,0,12);
        if(!ascii(head,0,"RIFF")||!ascii(head,8,"WAVE"))throw new IOException("Expected RIFF/WAVE audio");

        int sampleRate=0,channels=0,bits=0;
        byte[] pcm=null;
        long dataDeclared=0;
        boolean truncated=false;
        byte[] ch=new byte[8];

        while(true){
            int first=in.read();if(first<0)break;
            ch[0]=(byte)first;readFully(in,ch,1,7);
            String id=new String(ch,0,4,"US-ASCII");
            long size=u32le(ch,4);

            if("fmt ".equals(id)){
                byte[] f=new byte[(int)Math.min(size,64)];
                readFully(in,f,0,f.length);
                long remain=size-f.length;if(remain>0)skipFully(in,remain);
                if((size&1)!=0)skipFully(in,1);
                if(f.length<16)throw new IOException("WAV fmt chunk is truncated");
                int format=u16le(f,0);channels=u16le(f,2);sampleRate=(int)u32le(f,4);bits=u16le(f,14);
                if(format!=1)throw new IOException("Built-in spectrogram expects PCM WAV");
            }else if("data".equals(id)){
                dataDeclared=size;
                int keep=(int)Math.min(size,MAX_PCM_BYTES);
                pcm=new byte[keep];
                readFully(in,pcm,0,keep);
                truncated=size>keep;
                break;
            }else{
                skipFully(in,size);
                if((size&1)!=0)skipFully(in,1);
            }
        }

        if(pcm==null||sampleRate<=0||channels<=0||bits!=16)throw new IOException("Unsupported or incomplete WAV");
        int frameBytes=channels*2;
        int frames=pcm.length/frameBytes;
        int fftSize=sampleRate>96000?8192:4096;
        if(frames<fftSize)throw new IOException("Audio is too short for high-resolution spectrogram");

        final double maxHz=sampleRate*0.495;
        final double binHz=sampleRate/(double)fftSize;
        final int usedChannels=Math.min(channels,2);

        double[] window=new double[fftSize];
        for(int i=0;i<fftSize;i++){
            double a=2.0*Math.PI*i/(fftSize-1.0);
            window[i]=0.35875-0.48829*Math.cos(a)+0.14128*Math.cos(2*a)-0.01168*Math.cos(3*a);
        }

        double[] db=new double[WIDTH*HEIGHT];
        Arrays.fill(db,-180.0);
        double maxDb=-180.0;

        double[] reL=new double[fftSize],imL=new double[fftSize];
        double[] reR=usedChannels>1?new double[fftSize]:null;
        double[] imR=usedChannels>1?new double[fftSize]:null;

        for(int x=0;x<WIDTH;x++){
            double pos=x/(double)Math.max(1,WIDTH-1);
            int center=(int)Math.round(pos*(frames-1));
            int start=center-fftSize/2;
            if(start<0)start=0;
            if(start>frames-fftSize)start=frames-fftSize;

            for(int i=0;i<fftSize;i++){
                int p=(start+i)*frameBytes;
                short sl=(short)((pcm[p]&255)|(pcm[p+1]<<8));
                reL[i]=(sl/32768.0)*window[i];imL[i]=0;
                if(usedChannels>1){
                    int q=p+2;
                    short sr=(short)((pcm[q]&255)|(pcm[q+1]<<8));
                    reR[i]=(sr/32768.0)*window[i];imR[i]=0;
                }
            }

            fft(reL,imL);
            if(usedChannels>1)fft(reR,imR);

            for(int y=0;y<HEIGHT;y++){
                double frac=1.0-y/(double)(HEIGHT-1);
                double hz=frac*maxHz;
                double bf=hz/binHz;
                int b0=(int)Math.floor(bf);
                double mix=bf-b0;
                b0=Math.max(0,Math.min(fftSize/2-1,b0));
                int b1=Math.min(fftSize/2-1,b0+1);

                double p0=powerAt(reL,imL,reR,imR,usedChannels,b0);
                double p1=powerAt(reL,imL,reR,imR,usedChannels,b1);
                double power=p0+(p1-p0)*mix;
                double d=10.0*Math.log10(power+1e-18);
                db[y*WIDTH+x]=d;
                if(d>maxDb)maxDb=d;
            }
        }

        double minDb=maxDb-DYNAMIC_RANGE_DB;
        int[] px=new int[WIDTH*HEIGHT];
        for(int i=0;i<px.length;i++){
            double n=(db[i]-minDb)/DYNAMIC_RANGE_DB;
            n=Math.max(0,Math.min(1,n));
            n=Math.pow(n,0.82);
            int r=clamp255(255.0*Math.max(0,(n-0.62)/0.38));
            int g=clamp255(255.0*Math.min(1,n*1.18));
            int b=clamp255(255.0*Math.min(1,0.10+n*0.90));
            px[i]=Color.rgb(r,g,b);
        }

        Bitmap bm=Bitmap.createBitmap(px,WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
        double seconds=frames/(double)sampleRate;
        return new Result(bm,sampleRate,channels,seconds,fftSize,maxHz,binHz,DYNAMIC_RANGE_DB,truncated||dataDeclared>pcm.length);
    }

    private static double powerAt(double[] reL,double[] imL,double[] reR,double[] imR,int channels,int bin){
        double lr=reL[bin],li=imL[bin];
        double p=lr*lr+li*li;
        if(channels>1){
            double rr=reR[bin],ri=imR[bin];
            p=(p+rr*rr+ri*ri)*0.5;
        }
        return p;
    }

    private static int clamp255(double v){return(int)Math.max(0,Math.min(255,Math.round(v)));}

    private static void fft(double[] re,double[] im){
        int n=re.length;
        for(int i=1,j=0;i<n;i++){
            int bit=n>>1;
            for(;j>=bit;bit>>=1)j-=bit;
            j+=bit;
            if(i<j){
                double tr=re[i];re[i]=re[j];re[j]=tr;
                double ti=im[i];im[i]=im[j];im[j]=ti;
            }
        }
        for(int len=2;len<=n;len<<=1){
            double ang=-2*Math.PI/len;
            double wlr=Math.cos(ang),wli=Math.sin(ang);
            for(int i=0;i<n;i+=len){
                double wr=1,wi=0;
                for(int j=0;j<len/2;j++){
                    int u=i+j,v=i+j+len/2;
                    double vr=re[v]*wr-im[v]*wi,vi=re[v]*wi+im[v]*wr;
                    re[v]=re[u]-vr;im[v]=im[u]-vi;
                    re[u]+=vr;im[u]+=vi;
                    double nwr=wr*wlr-wi*wli;
                    wi=wr*wli+wi*wlr;wr=nwr;
                }
            }
        }
    }

    private static boolean ascii(byte[]a,int o,String s){for(int i=0;i<s.length();i++)if((char)a[o+i]!=s.charAt(i))return false;return true;}
    private static int u16le(byte[]a,int o){return(a[o]&255)|((a[o+1]&255)<<8);}
    private static long u32le(byte[]a,int o){return((long)a[o]&255)|(((long)a[o+1]&255)<<8)|(((long)a[o+2]&255)<<16)|(((long)a[o+3]&255)<<24);}
    private static void readFully(InputStream in,byte[]b,int off,int len)throws IOException{int p=0,n;while(p<len&&(n=in.read(b,off+p,len-p))>=0){if(n>0)p+=n;}if(p<len)throw new EOFException();}
    private static void skipFully(InputStream in,long n)throws IOException{long left=n;byte[]buf=new byte[65536];while(left>0){long s=in.skip(left);if(s>0){left-=s;continue;}int r=in.read(buf,0,(int)Math.min(buf.length,left));if(r<0)throw new EOFException();left-=r;}}
}
