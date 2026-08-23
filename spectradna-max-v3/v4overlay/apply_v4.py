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

main=rep(main,'SPECTRA DNA VAULT MAX v3.2 COMPACT EXACT','SPECTRADNA FORGE v4  •  IMAGE ⇄ SOUND ⇄ DNA','v4 title')
main=rep(main,'Image → short visible spectrogram/BioWave → exact embedded source → SHA/Merkle verified pixels','Image → picture-in-spectrogram audio → exact recovery → binary → experimental Tri-Strand DNA signal','v4 subtitle')
main=rep(main,'GENERATE ARCHIVE MASTER 96 kHz','OPTIONAL HI-RES MASTER 96 kHz','96 label')
main=rep(main,'GENERATE LAB MASTER 192 kHz','OPTIONAL EXTENDED MASTER 192 kHz','192 label')

anchor='shareBtn=button("SHARE LAST AUDIO",v->shareLastWav());shareBtn.setEnabled(false);root.addView(shareBtn);'
insert=anchor+'\n        root.addView(button("▥  VIEW BUILT-IN SPECTROGRAM",v->showLastSpectrogram()));\n        root.addView(button("01  BINARY + TRI-STRAND DNA",v->showBinaryAndTriStrand()));\n        root.addView(button("🧬  EXPORT TRI-STRAND DNA CODE",v->exportTriStrand()));'
main=rep(main,anchor,insert,'v4 buttons')

# Make the app explicit that phone-only is the baseline.
foot_old='COMPACT EXACT stores the source bytes once inside the WAV sDNA chunk and verifies source SHA-256, decoded-pixel SHA-256 and tile Merkle root. AIR FORTRESS remains available when a full acoustic modem path is wanted. Firefly, Levin-timing and wave-genetics layers are experimental sonification controls; optical wavelength references are metadata, not claims that a phone speaker emits those wavelengths.'
foot_new='PHONE MODE is fully standalone. COMPACT EXACT stores the source once in the WAV sDNA chunk while the waveform draws the image in the spectrogram. 96/192 kHz files and external rigs are optional upgrades only. Binary and Tri-Strand DNA are deterministic digital/sonification representations; experimental biological labels are kept separate from exact file recovery.'
main=rep(main,foot_old,foot_new,'footer')

methods=r'''
    private void showLastSpectrogram(){
        if(lastWavUri==null){status.setText("GENERATE AUDIO FIRST");return;}
        status.setText("Analyzing actual generated WAV spectrogram…");
        worker.execute(()->{try{
            AudioSpectrogram.Result sr;try(InputStream in=getContentResolver().openInputStream(lastWavUri)){sr=AudioSpectrogram.render(in);}
            runOnUiThread(()->{
                ImageView iv=new ImageView(this);iv.setImageBitmap(sr.bitmap);iv.setAdjustViewBounds(true);iv.setScaleType(ImageView.ScaleType.FIT_CENTER);iv.setPadding(dp(8),dp(8),dp(8),dp(8));
                ScrollView sc=new ScrollView(this);sc.addView(iv,new ScrollView.LayoutParams(-1,-2));
                new AlertDialog.Builder(this).setTitle("BUILT-IN SPECTROGRAM  •  "+sr.sampleRate/1000+" kHz").setMessage(String.format("Analyzed %.1f seconds of the actual WAV. High frequencies are at the top; time runs left → right.",sr.secondsAnalyzed)).setView(sc).setPositiveButton("Close",null).show();
                status.setText("SPECTROGRAM READY ✓");
            });
        }catch(Exception e){fail(e);}});
    }

    private void showBinaryAndTriStrand(){
        if(compressedArchive==null){status.setText("SELECT AN IMAGE FIRST");return;}
        worker.execute(()->{try{
            String text=TriStrandCodec.previewText(compressedArchive);
            runOnUiThread(()->{TextView tv=text(text,12,Color.rgb(205,255,218));tv.setTextIsSelectable(true);tv.setPadding(dp(10),dp(10),dp(10),dp(10));ScrollView sc=new ScrollView(this);sc.addView(tv);new AlertDialog.Builder(this).setTitle("BINARY + TRI-STRAND GENOME CODE").setView(sc).setNegativeButton("Close",null).setPositiveButton("Export full code",(d,w)->exportTriStrand()).show();});
        }catch(Exception e){fail(e);}});
    }

    private void exportTriStrand(){
        if(compressedArchive==null){status.setText("SELECT AN IMAGE FIRST");return;}
        status.setText("Writing Tri-Strand DNA code…");
        worker.execute(()->{Uri u=null;try{
            String name=safeBase(currentArchive==null?"image":currentArchive.fileName)+"-TRI-STRAND-DNA.fasta.txt";
            u=createTextUri(name,"text/plain");try(OutputStream out=getContentResolver().openOutputStream(u,"w")){TriStrandCodec.writeFasta(compressedArchive,out);}finishPending(u);
            runOnUiThread(()->status.setText("TRI-STRAND DNA EXPORTED ✓  Downloads/SpectraDNA"));
        }catch(Exception e){if(u!=null)try{getContentResolver().delete(u,null,null);}catch(Exception ignored){}fail(e);}});
    }
'''
anchor2='    private void createDnaDocument(){'
if anchor2 not in main: raise SystemExit('missing v4 method insertion anchor')
main=main.replace(anchor2,methods+'\n'+anchor2,1)

# Add tri-strand sonification to every COMPACT EXACT master. It is low-level so
# the picture-bearing spectrogram remains dominant and exact sDNA is unaffected.
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
    # alternate v3.2 compact implementation may assign doubles first
    old='double l=artSample+bioL+key*0.35,r=bioR+key;'
    if old in wav:
        wav=wav.replace(old,'double triL=tri.sample(f,false)*1250.0,triR=tri.sample(f,true)*1250.0;double l=artSample+bioL+key*0.35+triL,r=bioR+key+triR;',1);patched=True
if not patched: raise SystemExit('missing compact sample mix anchor')

main_path.write_text(main);wav_path.write_text(wav)
print('Applied SpectraDNA Forge v4 overlay')
