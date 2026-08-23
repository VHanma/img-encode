#!/usr/bin/env python3
import pathlib, sys

if len(sys.argv)!=2: raise SystemExit('usage: apply_v4.py <SpectraDNA source root>')
root=pathlib.Path(sys.argv[1])
main_path=root/'app/src/main/java/com/vaan/spectradna/MainActivity.java'
wav_path=root/'app/src/main/java/com/vaan/spectradna/WavVault.java'
main=main_path.read_text(); wav=wav_path.read_text()

def rep(text,old,new,label):
    if old not in text: raise SystemExit('missing v4 patch anchor: '+label)
    return text.replace(old,new,1)

main=rep(main,'SPECTRA DNA VAULT MAX v3.2 COMPACT EXACT','SPECTRADNA FORGE v4  •  ALPHA + GAMMA EXACT','v4 title')
main=rep(main,'Image → short visible spectrogram/BioWave → exact embedded source → SHA/Merkle verified pixels','Image → high-detail picture-in-spectrogram audio → exact recovery → Alpha/Gamma-dominant genome signal','v4 subtitle')
main=rep(main,'GENERATE ARCHIVE MASTER 96 kHz','OPTIONAL HI-RES MASTER 96 kHz','96 label')
main=rep(main,'GENERATE LAB MASTER 192 kHz','OPTIONAL EXTENDED MASTER 192 kHz','192 label')

anchor='shareBtn=button("SHARE LAST AUDIO",v->shareLastWav());shareBtn.setEnabled(false);root.addView(shareBtn);'
insert=anchor+'\n        root.addView(button("▥  VIEW BUILT-IN SPECTROGRAM • FORENSIC FULL-BAND",v->showLastSpectrogram()));\n        root.addView(button("01  BINARY + ALPHA/GAMMA GENOME",v->showBinaryAndTriStrand()));\n        root.addView(button("🧬  EXPORT FULL ALPHA/GAMMA GENOME CODE",v->exportTriStrand()));'
main=rep(main,anchor,insert,'v4 buttons')

foot_old='COMPACT EXACT stores the source bytes once inside the WAV sDNA chunk and verifies source SHA-256, decoded-pixel SHA-256 and tile Merkle root. AIR FORTRESS remains available when a full acoustic modem path is wanted. Firefly, Levin-timing and wave-genetics layers are experimental sonification controls; optical wavelength references are metadata, not claims that a phone speaker emits those wavelengths.'
foot_new='PHONE MODE is fully standalone. COMPACT EXACT stores the source once in the WAV sDNA chunk while the waveform carries a high-detail picture spectrogram. Alpha and Gamma are the dominant automatic genome-sonification lanes; Theta and Delta are subordinate parity/sync support. Full genome text is generated deterministically on export instead of being stored as a second giant copy. 96/192 kHz files and external rigs remain optional upgrades. Experimental biological labels are kept separate from exact byte recovery.'
main=rep(main,foot_old,foot_new,'footer')

art_old='Bitmap small=Bitmap.createScaledBitmap(src,224,128,true);int[] px=new int[224*128];small.getPixels(px,0,224,0,0,224,128);short[] art=SpectrogramArt.renderMax(px,224,128);'
art_new='double artScale=Math.min(1.0,Math.min(896.0/src.getWidth(),512.0/src.getHeight()));int artW=Math.max(1,(int)Math.round(src.getWidth()*artScale)),artH=Math.max(1,(int)Math.round(src.getHeight()*artScale));Bitmap small=(artW==src.getWidth()&&artH==src.getHeight())?src:Bitmap.createScaledBitmap(src,artW,artH,true);int[] px=new int[artW*artH];small.getPixels(px,0,artW,0,0,artW,artH);short[] art=SpectrogramArt.renderMax(px,artW,artH);'
main=rep(main,art_old,art_new,'real high-detail waveform source grid')

main=rep(main,
    'VISIBLE LAYER: deterministic 3-pass spectrogram compressed into the short master',
    'VISIBLE LAYER: deterministic picture spectrogram from the real source, capped at 896 × 512 without artificial upscaling',
    'detail description')
main=rep(main,
    'COMPACT EXACT READY ✓  12 s AUDIO + EXACT SOURCE',
    'COMPACT EXACT READY ✓  12 s AUDIO + EXACT SOURCE + ALPHA/GAMMA GENOME',
    'compact ready status')
main=rep(main,
    '96/192 kHz and DNA text remain optional, so storage is not duplicated automatically.',
    'Alpha/Gamma genome sonification is already automatic. Full text export and 96/192 kHz masters stay optional so storage is not duplicated.',
    'compact completion details')

methods=r'''
    private void showLastSpectrogram(){
        if(lastWavUri==null){status.setText("GENERATE AUDIO FIRST");return;}
        status.setText("Analyzing actual PCM at forensic full-band resolution…");
        worker.execute(()->{try{
            AudioSpectrogram.Result sr;try(InputStream in=getContentResolver().openInputStream(lastWavUri)){sr=AudioSpectrogram.render(in);}
            runOnUiThread(()->{
                ImageView iv=new ImageView(this);
                iv.setImageBitmap(sr.bitmap);
                iv.setAdjustViewBounds(false);
                iv.setScaleType(ImageView.ScaleType.CENTER);
                iv.setPadding(dp(4),dp(4),dp(4),dp(4));
                int viewW=sr.bitmap.getWidth();
                int viewH=sr.bitmap.getHeight();
                HorizontalScrollView hs=new HorizontalScrollView(this);
                hs.setFillViewport(false);
                hs.addView(iv,new HorizontalScrollView.LayoutParams(viewW,viewH));
                ScrollView vs=new ScrollView(this);
                vs.setFillViewport(false);
                vs.addView(hs,new ScrollView.LayoutParams(-2,-2));
                String trunc=sr.truncated?" • preview limited to first analyzed PCM block":"";
                String info=String.format(java.util.Locale.US,
                    "Actual WAV PCM • %.1f s%s\n%d × %d measured display • FFT %d • %.2f Hz/bin\nFull analyzed band: 0 → %.2f kHz • %.0f dB display range\nStereo channels are transformed separately before power is combined, so phase-opposed content is not erased.\nNo extra picture structure is painted into this spectrogram.",
                    sr.secondsAnalyzed,trunc,sr.bitmap.getWidth(),sr.bitmap.getHeight(),sr.fftSize,sr.frequencyResolutionHz,sr.maxFrequencyHz/1000.0,sr.dynamicRangeDb);
                new AlertDialog.Builder(this)
                    .setTitle("BUILT-IN SPECTROGRAM • FORENSIC FULL-BAND • "+sr.sampleRate/1000+" kHz")
                    .setMessage(info)
                    .setView(vs)
                    .setPositiveButton("Close",null)
                    .show();
                status.setText("FULL-BAND SPECTROGRAM READY ✓");
            });
        }catch(Exception e){fail(e);}});
    }

    private void showBinaryAndTriStrand(){
        if(compressedArchive==null){status.setText("SELECT AN IMAGE FIRST");return;}
        worker.execute(()->{try{
            String text=TriStrandCodec.previewText(compressedArchive);
            runOnUiThread(()->{
                TextView tv=text(text,12,Color.rgb(205,255,218));tv.setTextIsSelectable(true);tv.setPadding(dp(10),dp(10),dp(10),dp(10));
                ScrollView sc=new ScrollView(this);sc.addView(tv);
                new AlertDialog.Builder(this)
                    .setTitle("BINARY + ALPHA/GAMMA GENOME CODE")
                    .setView(sc)
                    .setNegativeButton("Close",null)
                    .setPositiveButton("Export full code",(d,w)->exportTriStrand())
                    .show();
            });
        }catch(Exception e){fail(e);}});
    }

    private void exportTriStrand(){
        if(compressedArchive==null){status.setText("SELECT AN IMAGE FIRST");return;}
        status.setText("Streaming full Alpha/Gamma genome code…");
        worker.execute(()->{Uri u=null;try{
            String name=safeBase(currentArchive==null?"image":currentArchive.fileName)+"-ALPHA-GAMMA-GENOME.fasta.txt";
            u=createTextUri(name,"text/plain");
            try(OutputStream out=getContentResolver().openOutputStream(u,"w")){TriStrandCodec.writeFasta(compressedArchive,out);}
            finishPending(u);
            runOnUiThread(()->status.setText("ALPHA/GAMMA GENOME EXPORTED ✓  Downloads/SpectraDNA"));
        }catch(Exception e){if(u!=null)try{getContentResolver().delete(u,null,null);}catch(Exception ignored){}fail(e);}});
    }
'''
anchor2='    private void createDnaDocument(){'
if anchor2 not in main: raise SystemExit('missing v4 method insertion anchor')
main=main.replace(anchor2,methods+'\n'+anchor2,1)

bio='BioWaveCarrier bio=new BioWaveCarrier(archive,art,profile.sampleRate,frames,bioOptions);'
if bio not in wav: raise SystemExit('missing BioWaveCarrier compact anchor')
wav=wav.replace(bio,bio+'\n        TriStrandSignal tri=new TriStrandSignal(archive,profile.sampleRate);',1)

candidates=[
    'short l=clip(artSample+bioL+key*0.35),r=clip(bioR+key);',
    'short l=clip(artSample+bioL+key*0.35),r=clip(bioR+key);',
    'short l=clip(artSample+bioL+key*0.35);short r=clip(bioR+key);'
]
patched=False
for old in candidates:
    if old in wav:
        if ';short r=' in old:
            new='double triL=tri.sample(f,false)*1250.0,triR=tri.sample(f,true)*1250.0;short l=clip(artSample+bioL+key*0.35+triL);short r=clip(bioR+key+triR);'
        else:
            new='double triL=tri.sample(f,false)*1250.0,triR=tri.sample(f,true)*1250.0;short l=clip(artSample+bioL+key*0.35+triL),r=clip(bioR+key+triR);'
        wav=wav.replace(old,new,1);patched=True;break
if not patched:
    old='double l=artSample+bioL+key*0.35,r=bioR+key;'
    if old in wav:
        wav=wav.replace(old,'double triL=tri.sample(f,false)*1250.0,triR=tri.sample(f,true)*1250.0;double l=artSample+bioL+key*0.35+triL,r=bioR+key+triR;',1);patched=True
if not patched: raise SystemExit('missing compact sample mix anchor')

main_path.write_text(main);wav_path.write_text(wav)
print('Applied SpectraDNA Forge v4 Alpha/Gamma + forensic spectrogram overlay')
