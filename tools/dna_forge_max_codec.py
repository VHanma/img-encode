#!/usr/bin/env python3
import argparse, hashlib, json, math, struct, sys, wave, zlib
from pathlib import Path

MAGIC=b"DFMAX01"
HEADER=">7sIIBIII"
HS=struct.calcsize(HEADER)

def pil():
    try:
        from PIL import Image
        return Image
    except Exception:
        print("Install Pillow: python -m pip install pillow", file=sys.stderr)
        raise

def sha(b): return hashlib.sha256(b).hexdigest()
def crc(b): return zlib.crc32(b)&0xffffffff
def clamp(x): return max(-.95,min(.95,x))

def b2bits(data):
    for b in data:
        for i in range(7,-1,-1):
            yield (b>>i)&1

def bits2b(bits):
    out=bytearray()
    n=len(bits)-len(bits)%8
    for i in range(0,n,8):
        v=0
        for bit in bits[i:i+8]: v=(v<<1)|bit
        out.append(v)
    return bytes(out)

def wav_write(path,samples,sr):
    Path(path).parent.mkdir(parents=True,exist_ok=True)
    with wave.open(str(path),"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes(b"".join(struct.pack("<h",int(clamp(x)*32767)) for x in samples))

def wav_read(path):
    with wave.open(str(path),"rb") as w:
        ch=w.getnchannels(); sw=w.getsampwidth(); sr=w.getframerate(); raw=w.readframes(w.getnframes())
    if sw!=2: raise SystemExit("Only 16-bit WAV supported.")
    vals=struct.unpack("<"+"h"*(len(raw)//2),raw)
    if ch>1: vals=vals[::ch]
    return [v/32767 for v in vals],sr

def tone(freq,ms,sr,amp):
    n=int(sr*ms/1000); fade=max(1,int(sr*.005)); out=[]
    for i in range(n):
        env=1
        if i<fade: env=i/fade
        elif i>n-fade: env=max(0,(n-i)/fade)
        out.append(math.sin(2*math.pi*freq*i/sr)*amp*env)
    return out

def metrics(img):
    w,h=img.size; px=list(img.getdata())
    bright=[(r+g+b)//3 for r,g,b,a in px]
    avg=sum(bright)/max(1,len(bright))
    edges=0; total=0
    for y in range(h):
        for x in range(w):
            v=bright[y*w+x]
            if x+1<w:
                d=abs(v-bright[y*w+x+1]); total+=d; edges+=1 if d>30 else 0
            if y+1<h:
                d=abs(v-bright[(y+1)*w+x]); total+=d; edges+=1 if d>30 else 0
    dif=[]
    for y in range(h):
        for x in range(w//2):
            dif.append(abs(bright[y*w+x]-bright[y*w+(w-1-x)]))
    sym=max(0,min(1,1-(sum(dif)/max(1,len(dif)))/255))
    sig=bytes(int(sum(bright)/max(1,len(bright))) for _ in range(1))
    return {
      "levin_bioelectric_map":{
        "brightness_voltage_average":round(avg,4),
        "polarity_edge_count":edges,
        "polarity_edge_total":total,
        "left_right_symmetry":round(sym,6)
      },
      "morphogenetic_field":{
        "width":w,"height":h,"aspect":round(w/max(1,h),6),
        "shape_blueprint_hash":sha(sig+struct.pack(">II",w,h))
      }
    }

def packet(image,max_dim,args):
    Image=pil(); img=Image.open(image).convert("RGBA")
    if max_dim>0: img.thumbnail((max_dim,max_dim),Image.Resampling.LANCZOS)
    w,h=img.size; raw=img.tobytes(); comp=zlib.compress(raw,9)
    meta={
      "mode":"DNA Forge Max",
      "layers":{
        "dna_payload":"RGBA pixels compressed with zlib and CRC32",
        "gariaev_carrier":{"zero_hz":args.f0,"one_hz":args.f1,"he_ne_preamble_hz":args.preamble,"original_carrier_hz":[640000,700000],"scale":100},
        "rife_sweep":{"start_hz":args.rife_start,"end_hz":args.rife_end},
        "tesla_standing_wave":{"schumann_gate_hz":args.gate,"pulse_rhythm":[3,6,9]},
        "bearden_scalar":{"forward_hash":sha(comp),"reverse_hash":sha(comp[::-1]),"mirror_hash":sha(bytes(b^255 for b in comp))},
        "infrared":{"he_ne_nm":632.8,"red_nm":660,"near_ir_nm":850,"ir_led_nm":940,"note":"speaker cannot emit IR; metadata for hardware/screen layer"},
        "hardware":{"coil_marker_hz":384000,"plasma_or_coil_export":"future hardware profile"}
      },
      "hashes":{"raw_pixels_sha256":sha(raw),"payload_sha256":sha(comp)}
    }
    meta.update(metrics(img))
    mb=json.dumps(meta,separators=(",",":"),sort_keys=True).encode()
    head=struct.pack(HEADER,MAGIC,w,h,4,len(mb),len(comp),crc(comp))
    return head+mb+comp,meta,w,h

def apply_layers(samples,sr,args):
    dur=max(.001,len(samples)/sr); out=[]
    for i,s in enumerate(samples):
        t=i/sr
        gate_env=1-args.gate_depth+args.gate_depth*((1+math.sin(2*math.pi*args.gate*t))/2)
        cycle=t%18; pulse=0
        for m in (3,6,9): pulse+=math.exp(-((cycle-m)**2)/.045)
        tesla=1+args.tesla_depth*min(1,pulse)
        sweep=math.sin(2*math.pi*(args.rife_start*t+0.5*((args.rife_end-args.rife_start)/dur)*t*t))*args.rife_amp
        aura=math.sin(2*math.pi*args.aura*t)*args.aura_amp
        out.append(clamp(s*gate_env*tesla+sweep+aura))
    return out

def encode(args):
    data,meta,w,h=packet(args.image,args.max_dim,args)
    bits=list(b2bits(data)); sr=args.sr; spb=int(sr*args.bit_ms/1000)
    if spb<80: raise SystemExit("Use --bit-ms 3 or higher.")
    samples=[0.0]*int(sr*args.lead/1000)
    samples+=tone(args.preamble,args.preamble_ms,sr,.43)
    samples+=[0.0]*int(sr*.05)
    phase=0
    for bit in bits:
        f=args.f1 if bit else args.f0; step=2*math.pi*f/sr
        for _ in range(spb):
            samples.append(math.sin(phase)*.72); phase=(phase+step)%(2*math.pi)
    samples+=[0.0]*int(sr*args.lead/1000)
    if not args.carrier_only: samples=apply_layers(samples,sr,args)
    wav_write(args.wav,samples,sr)
    if args.meta: Path(args.meta).write_text(json.dumps(meta,indent=2))
    print("\n✅ ENCODED DNA FORGE MAX")
    print("WAV:",args.wav)
    print("Image size:",f"{w}x{h}")
    print("Bits:",len(bits))
    print("Layers: Levin + Gariaev + Rife + Morphogenetic + Bearden + Tesla + Scalar + Infrared\n")

def power(seg,f,sr):
    a=b=0
    for i,x in enumerate(seg):
        ang=2*math.pi*f*i/sr
        a+=x*math.sin(ang); b+=x*math.cos(ang)
    return a*a+b*b

def decode(args):
    samples,sr=wav_read(args.wav); spb=int(sr*args.bit_ms/1000)
    start=int(sr*(args.lead+args.preamble_ms+50)/1000)
    usable=samples[start:]; bits=[]
    for i in range(len(usable)//spb):
        seg=usable[i*spb:(i+1)*spb]
        bits.append(1 if power(seg,args.f1,sr)>power(seg,args.f0,sr) else 0)
    data=bits2b(bits); idx=data.find(MAGIC)
    if idx<0: raise SystemExit("Magic not found. Use same --f0 --f1 --bit-ms.")
    data=data[idx:]
    magic,w,h,ch,mlen,plen,exp=struct.unpack(HEADER,data[:HS])
    meta=json.loads(data[HS:HS+mlen].decode())
    comp=data[HS+mlen:HS+mlen+plen]
    if crc(comp)!=exp: raise SystemExit("CRC failed. Use WAV/FLAC and exact settings.")
    raw=zlib.decompress(comp); Image=pil()
    Image.frombytes("RGBA",(w,h),raw).save(args.image)
    if args.meta: Path(args.meta).write_text(json.dumps(meta,indent=2))
    print("\n✅ DECODED:",args.image,"\n")

def auto(args):
    out=Path(args.out); out.mkdir(parents=True,exist_ok=True)
    stem=Path(args.image).stem
    class X: pass
    e=X(); e.__dict__.update(args.__dict__)
    e.wav=str(out/(stem+"_dna_forge_max.wav")); e.meta=str(out/(stem+"_metadata.json"))
    encode(e)
    d=X(); d.wav=e.wav; d.image=str(out/(stem+"_recovered.png")); d.meta=str(out/(stem+"_decoded_metadata.json"))
    d.f0=args.f0; d.f1=args.f1; d.bit_ms=args.bit_ms; d.lead=args.lead; d.preamble_ms=args.preamble_ms
    decode(d)

def add_common(p):
    p.add_argument("--sr",type=int,default=44100)
    p.add_argument("--f0",type=float,default=6400)
    p.add_argument("--f1",type=float,default=7000)
    p.add_argument("--bit-ms",type=float,default=4)
    p.add_argument("--lead",type=float,default=300)
    p.add_argument("--preamble",type=float,default=6328)
    p.add_argument("--preamble-ms",type=float,default=750)
    p.add_argument("--aura",type=float,default=528)
    p.add_argument("--aura-amp",type=float,default=.055)
    p.add_argument("--gate",type=float,default=7.83)
    p.add_argument("--gate-depth",type=float,default=.30)
    p.add_argument("--rife-start",type=float,default=20)
    p.add_argument("--rife-end",type=float,default=20000)
    p.add_argument("--rife-amp",type=float,default=.025)
    p.add_argument("--tesla-depth",type=float,default=.11)
    p.add_argument("--max-dim",type=int,default=256)
    p.add_argument("--carrier-only",action="store_true")

ap=argparse.ArgumentParser()
sub=ap.add_subparsers(dest="cmd",required=True)
e=sub.add_parser("encode"); e.add_argument("image"); e.add_argument("wav"); e.add_argument("--meta"); add_common(e)
d=sub.add_parser("decode"); d.add_argument("wav"); d.add_argument("image"); d.add_argument("--meta"); d.add_argument("--f0",type=float,default=6400); d.add_argument("--f1",type=float,default=7000); d.add_argument("--bit-ms",type=float,default=4); d.add_argument("--lead",type=float,default=300); d.add_argument("--preamble-ms",type=float,default=750)
a=sub.add_parser("auto"); a.add_argument("image"); a.add_argument("--out",default="dna_forge_max_out"); add_common(a)
args=ap.parse_args()
if args.cmd=="encode": encode(args)
elif args.cmd=="decode": decode(args)
else: auto(args)
