package org.vhanma.dnaforgemax;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Fixed-record prepared cache so playback never performs image analysis. */
final class V9PreparedCache {
    private static final int MAGIC=0x56395452; // V9TR
    static final class Meta {final int rate,fps,pairs,frames;Meta(int r,int f,int p,int n){rate=r;fps=f;pairs=p;frames=n;}}
    static final class Frame {final float[]xy;final int[]rgb;Frame(float[]x,int[]c){xy=x;rgb=c;}}

    static final class Writer implements AutoCloseable {
        private final DataOutputStream out;final Meta meta;int written=0;
        Writer(File file,Meta m)throws Exception{meta=m;out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file),1<<20));out.writeInt(MAGIC);out.writeInt(m.rate);out.writeInt(m.fps);out.writeInt(m.pairs);out.writeInt(m.frames);}
        void write(V9TraceEngine.Result r)throws Exception{for(int i=0;i<meta.pairs*2;i++)out.writeFloat(i<r.xy.length?r.xy[i]:0f);for(int i=0;i<meta.pairs;i++)out.writeInt(i<r.rgb.length?r.rgb[i]:0xffffffff);written++;}
        public void close()throws Exception{out.flush();out.close();}
    }

    static final class Reader implements AutoCloseable {
        private final DataInputStream in;final Meta meta;int index=0;
        Reader(File file)throws Exception{in=new DataInputStream(new BufferedInputStream(new FileInputStream(file),1<<20));int magic=in.readInt();if(magic!=MAGIC)throw new IllegalStateException("Bad v9 cache");meta=new Meta(in.readInt(),in.readInt(),in.readInt(),in.readInt());}
        Frame next()throws Exception{if(index>=meta.frames)return null;float[]xy=new float[meta.pairs*2];int[]rgb=new int[meta.pairs];for(int i=0;i<xy.length;i++)xy[i]=in.readFloat();for(int i=0;i<rgb.length;i++)rgb[i]=in.readInt();index++;return new Frame(xy,rgb);}
        public void close()throws Exception{in.close();}
    }
}
