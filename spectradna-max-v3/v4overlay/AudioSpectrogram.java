package com.vaan.spectradna;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.*;
import java.util.*;

/** Lightweight built-in analyzer for generated PCM WAV files. */
public final class AudioSpectrogram {
    private AudioSpectrogram(){}

    public static final class Result {
        public final Bitmap bitmap;
        public final int sampleRate;
        public final int channels;
        public final double secondsAnalyzed;
        Result(Bitmap bitmap,int sampleRate,int channels,double secondsAnalyzed){this.bitmap=bitmap;this.sampleRate=sampleRate;this.channels=channels;this.secondsAnalyzed=secondsAnalyzed;}
    }

    private static final int FFT=1024;
    private static final int WIDTH=512;
    private static final int HEIGHT=300;
    private static final int MAX_PCM_BYTES=48*1024*1024;

    public static Result render(InputStream raw)throws IOException{
        if(raw==null)throw new IOException("Missing WAV input");
        BufferedInputStream in=new BufferedInputStream(raw,256*1024);
        byte[] head=new byte[12];readFully(in,head,0,12);
        if(!ascii(head,0,"RIFF")||!ascii(head,8,"WAVE"))throw new IOException("Expected RIFF/WAVE audio");
        int sampleRate=0,channels=0,bits=0;byte[] pcm=null;long dataDeclared=0;
        byte[] ch=new byte[8];
        while(true){
            int first=in.read();if(first<0)break;ch[0]=(byte)first;readFully(in,ch,1,7);
            String id=new String(ch,0,4,"US-ASCII");long size=u32le(ch,4);
            if(size>0x7fffffffL&&"data".equals(id))throw new IOException("WAV data too large for in-app preview");
            if("fmt ".equals(id)){
                byte[] f=new byte[(int)Math.min(size,64)];readFully(in,f,0,f.length);long remain=size-f.length;if(remain>0)skipFully(in,remain);if((size&1)!=0)skipFully(in,1);
                if(f.length<16)throw new IOException("WAV fmt chunk is truncated");
                int format=u16le(f,0);channels=u16le(f,2);sampleRate=(int)u32le(f,4);bits=u16le(f,14);
                if(format!=1)throw new IOException("Built-in spectrogram currently expects PCM WAV");
            }else if("data".equals(id)){
                dataDeclared=size;int keep=(int)Math.min(size,MAX_PCM_BYTES);pcm=new byte[keep];readFully(in,pcm,0,keep);if(size>keep)skipFully(in,size-keep);if((size&1)!=0)skipFully(in,1);break;
            }else{skipFully(in,size);if((size&1)!=0)skipFully(in,1);}
        }
        if(pcm==null||sampleRate<=0||channels<=0||bits!=16)throw new IOException("Unsupported or incomplete WAV");
        int frameBytes=channels*2;int frames=pcm.length/frameBytes;if(frames<FFT)throw new IOException("Audio is too short for spectrogram");
        double[] db=new double[WIDTH*HEIGHT];Arrays.fill(db,-120.0);
        double maxDb=-120.0,minDb=-120.0;
        double[] re=new double[FFT],im=new double[FFT];
        double minHz=80.0,maxHz=Math.min(sampleRate*0.48,12000.0);
        for(int x=0;x<WIDTH;x++){
            int start=(int)Math.round((frames-FFT)*(x/(double)Math.max(1,WIDTH-1)));
            for(int i=0;i<FFT;i++){
                int p=(start+i)*frameBytes;double v=0;
                for(int c=0;c<channels;c++){int q=p+c*2;short s=(short)((pcm[q]&255)|(pcm[q+1]<<8));v+=s/32768.0;}
                v/=channels;double w=0.5-0.5*Math.cos(2*Math.PI*i/(FFT-1));re[i]=v*w;im[i]=0;
            }
            fft(re,im);
            for(int y=0;y<HEIGHT;y++){
                double frac=1.0-y/(double)(HEIGHT-1);double hz=minHz+frac*(maxHz-minHz);int bin=(int)Math.round(hz*FFT/sampleRate);bin=Math.max(1,Math.min(FFT/2-1,bin));
                double mag=Math.hypot(re[bin],im[bin]);double d=20.0*Math.log10(mag+1e-9);db[y*WIDTH+x]=d;if(d>maxDb)maxDb=d;
            }
        }
        minDb=maxDb-58.0;
        int[] px=new int[WIDTH*HEIGHT];
        for(int i=0;i<px.length;i++){
            double n=(db[i]-minDb)/(maxDb-minDb);n=Math.max(0,Math.min(1,n));n=Math.pow(n,0.72);
            int r=(int)(255*Math.max(0,(n-0.55)/0.45));
            int g=(int)(255*Math.min(1,n*1.35));
            int b=(int)(255*Math.min(1,0.18+n*0.92));
            px[i]=Color.rgb(r,g,b);
        }
        Bitmap bm=Bitmap.createBitmap(px,WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
        double seconds=frames/(double)sampleRate;
        return new Result(bm,sampleRate,channels,seconds);
    }

    private static void fft(double[] re,double[] im){
        int n=re.length;for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;j>=bit;bit>>=1)j-=bit;j+=bit;if(i<j){double tr=re[i];re[i]=re[j];re[j]=tr;double ti=im[i];im[i]=im[j];im[j]=ti;}}
        for(int len=2;len<=n;len<<=1){double ang=-2*Math.PI/len;double wlr=Math.cos(ang),wli=Math.sin(ang);for(int i=0;i<n;i+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int u=i+j,v=i+j+len/2;double vr=re[v]*wr-im[v]*wi,vi=re[v]*wi+im[v]*wr;re[v]=re[u]-vr;im[v]=im[u]-vi;re[u]+=vr;im[u]+=vi;double nwr=wr*wlr-wi*wli;wi=wr*wli+wi*wlr;wr=nwr;}}}
    }
    private static boolean ascii(byte[]a,int o,String s){for(int i=0;i<s.length();i++)if((char)a[o+i]!=s.charAt(i))return false;return true;}
    private static int u16le(byte[]a,int o){return(a[o]&255)|((a[o+1]&255)<<8);}private static long u32le(byte[]a,int o){return((long)a[o]&255)|(((long)a[o+1]&255)<<8)|(((long)a[o+2]&255)<<16)|(((long)a[o+3]&255)<<24);}
    private static void readFully(InputStream in,byte[]b,int off,int len)throws IOException{int p=0,n;while(p<len&&(n=in.read(b,off+p,len-p))>=0){if(n>0)p+=n;}if(p<len)throw new EOFException();}
    private static void skipFully(InputStream in,long n)throws IOException{long left=n;byte[]buf=new byte[65536];while(left>0){long s=in.skip(left);if(s>0){left-=s;continue;}int r=in.read(buf,0,(int)Math.min(buf.length,left));if(r<0)throw new EOFException();left-=r;}}
}
