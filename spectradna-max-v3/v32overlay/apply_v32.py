#!/usr/bin/env python3
import pathlib, sys

if len(sys.argv) != 2:
    raise SystemExit('usage: apply_v32.py <SpectraDNA source root>')
root = pathlib.Path(sys.argv[1])
main_path = root / 'app/src/main/java/com/vaan/spectradna/MainActivity.java'
wav_path = root / 'app/src/main/java/com/vaan/spectradna/WavVault.java'

main = main_path.read_text()
wav = wav_path.read_text()

def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f'missing patch anchor: {label}')
    return text.replace(old, new, 1)

def between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise SystemExit(f'missing start anchor: {label}')
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f'missing end anchor: {label}')
    return text[:a] + replacement + '\n\n    ' + text[b:]

# ---------- MainActivity v3.2 ----------
main = main.replace('ArchiveContainer', 'CompactArchive')
main = rep(main, 'private boolean maximumRecovery=true;', 'private boolean maximumRecovery=false;', 'compact default')
main = rep(main, 'SPECTRA DNA VAULT MAX v3.1 AUTO FAST', 'SPECTRA DNA VAULT MAX v3.2 COMPACT EXACT', 'title')
main = rep(main, 'Image → spectrogram body + exact acoustic genome → SHA/Merkle verified pixels → redundant DNA archive',
           'Image → short visible spectrogram/BioWave → exact embedded source → SHA/Merkle verified pixels', 'subtitle')
main = rep(main, 'SELECT IMAGE  •  AUTO BUILD EVERYTHING', 'SELECT IMAGE  •  AUTO BUILD COMPACT EXACT', 'select label')
main = rep(main, 'REBUILD EVERYTHING MAX', 'REBUILD COMPACT / FORTRESS', 'rebuild label')
main = rep(main,
    'Maximum Recovery: 3 acoustic passes + 6+4 erasure code + duplicate DNA branches',
    'AIR FORTRESS: full acoustic modem + 3 passes + duplicate DNA branches (much larger/longer)', 'fortress label')
main = rep(main, 'max.setChecked(true);', 'max.setChecked(false);', 'fortress default off')
main = rep(main,
    'Verification is byte/pixel based, not visual guesswork. The digital DNA layer is an information-storage codec: DENSE differential encoding + edit-tolerant SYNC codewords + GF(256) parity. Experimental carrier labels describe encoding choices, not established biological effects. The exact digital recovery path remains independent. It does not claim to be the official Gungnir, DNA-Aeon, or HEDGES implementation.',
    'COMPACT EXACT stores the source bytes once inside the WAV sDNA chunk and verifies source SHA-256, decoded-pixel SHA-256 and tile Merkle root. AIR FORTRESS remains available when a full acoustic modem path is wanted. Firefly, Levin-timing and wave-genetics layers are experimental sonification controls; optical wavelength references are metadata, not claims that a phone speaker emits those wavelengths.', 'footer')

base_start = 'private String baseDetails(){'
base_end = 'private Button button('
base_new = '''private String baseDetails(){return "COMPACT EXACT DEFAULT: 18 s playable master + verified RIFF sDNA archive chunk\\nSOURCE STORAGE: exact original bytes stored once; raw RGBA/duplicate PNG removed\\nPIXEL TRUTH: source SHA-256 + decoded RGBA SHA-256 + tile hashes + Merkle root\\nVISIBLE LAYER: deterministic 3-pass spectrogram compressed into the short master\\nBIOWAVE: Gariaev-inspired bank + phonon-ratio + soliton + phase structure\\nPHASE-LOCK TIMING: firefly-style 125 ms flashes / 0.55 s spacing / 8-pulse / 12 s cycle\\nLEVIN-RELATED TIMING PALETTE: 7, 15, 60, 100 Hz used as experimental modulation timings, not universal bioelectric frequencies\\nOPTICAL REFERENCE METADATA: 560, 600-650, 634, 660, 703, 810, 830, 1064, 1270 nm\\nAIR FORTRESS OPTIONAL: full QPSK-OFDM acoustic archive + rescue path + extra redundancy\\nDNA TEXT ARCHIVE: manual optional export instead of an automatic storage duplicate";}'''
main = between(main, base_start, base_end, base_new, 'base details')

# generateProfileWav: compact by default, old protected-grid writer only for AIR FORTRESS.
gen_start = 'private void generateProfileWav(WavProfile profile,boolean autoPlay){'
gen_end = 'private void buildEverything(){'
gen_new = '''private void generateProfileWav(WavProfile profile,boolean autoPlay){
        if(compressedArchive==null||spectrogramArt==null){status.setText("SELECT AN IMAGE FIRST");return;}
        if(buildInProgress){status.setText("A BUILD IS ALREADY RUNNING");return;}
        setBuildState(true,maximumRecovery?"AIR FORTRESS: preparing protected recovery grid…":"COMPACT EXACT: building short master…");
        worker.execute(()->{Uri outUri=null;try{
            outUri=createAudioUri(profile);
            if(maximumRecovery){
                try(BuildPlan plan=prepareBuildPlan()){writeMasterWav(outUri,profile,plan,20,98);}
            }else{
                writeCompactMasterWav(outUri,profile,5,98);
            }
            finishPending(outUri);wavUris.put(profile,outUri);lastWavUri=outUri;final Uri ready=outUri;
            runOnUiThread(()->{status.setText(profile.suffix+" AUDIO READY ✓");progress.setProgress(100);playBtn.setEnabled(true);shareBtn.setEnabled(true);details.append("\\n\\nWAV SAVED: Music/SpectraDNA/"+nameFor(profile));if(autoPlay)playUri(ready);setBuildStateUi(false);});
        }catch(Exception e){if(outUri!=null)try{getContentResolver().delete(outUri,null,null);}catch(Exception ignored){}setBuildStateUi(false);fail(e);}});
    }'''
main = between(main, gen_start, gen_end, gen_new, 'generate profile')

build_start = 'private void buildEverything(){'
build_end = 'private Uri createAudioUri('
build_new = '''private void buildEverything(){
        if(compressedArchive==null||spectrogramArt==null){status.setText("SELECT AN IMAGE FIRST");return;}
        if(buildInProgress){status.setText("A BUILD IS ALREADY RUNNING");return;}
        if(maximumRecovery) buildFortressEverything(); else buildCompactEverything();
    }

    private void buildCompactEverything(){
        setBuildState(true,"COMPACT EXACT: writing short spectrogram/BioWave master…");
        worker.execute(()->{ArrayList<Uri> made=new ArrayList<>();try{
            Uri phone=createAudioUri(WavProfile.PHONE_48);made.add(phone);
            writeCompactMasterWav(phone,WavProfile.PHONE_48,3,88);finishPending(phone);
            wavUris.put(WavProfile.PHONE_48,phone);lastWavUri=phone;
            setWeightedProgress(88,96,20,"Writing SHA/Merkle manifest");
            String manifest=buildManifest();Uri man=createTextUri(safeBase(currentArchive.fileName)+"-SpectraDNA-MANIFEST.txt","text/plain");made.add(man);
            try(OutputStream out=getContentResolver().openOutputStream(man,"w")){out.write(manifest.getBytes("UTF-8"));}finishPending(man);
            runOnUiThread(()->{progress.setProgress(100);playBtn.setEnabled(true);shareBtn.setEnabled(true);status.setText("COMPACT EXACT READY ✓  18 s AUDIO + EXACT SOURCE");details.append("\\n\\nCOMPACT BUILD COMPLETE\\n48 kHz master: short playable spectrogram/BioWave + exact verified sDNA chunk\\nManifest: Downloads/SpectraDNA\\n96/192 kHz and DNA text remain optional, so storage is not duplicated automatically.");playUri(phone);setBuildStateUi(false);});
        }catch(Exception e){for(Uri u:made)try{getContentResolver().delete(u,null,null);}catch(Exception ignored){}setBuildStateUi(false);fail(e);}});
    }

    private void buildFortressEverything(){
        setBuildState(true,"AIR FORTRESS: preparing one shared protected grid…");
        worker.execute(()->{ArrayList<Uri> made=new ArrayList<>();Future<?> aux=null;try(BuildPlan plan=prepareBuildPlan()){
            final String stats=String.format("Primary %s • secondary %s • %.1f min protected timeline",fmt(plan.primary.length),fmt(plan.secondary.length),plan.minutes);
            runOnUiThread(()->details.append("\\n\\nAIR FORTRESS BUILD\\n"+stats+"\\nFull acoustic recovery + 48/96/192 kHz masters."));
            aux=auxWorker.submit(()->{try{
                Uri dna=createTextUri("SpectraDNA-MAX-archive.fasta.txt","text/plain");synchronized(made){made.add(dna);}try(OutputStream out=getContentResolver().openOutputStream(dna,"w")){DnaAdvancedCodec.encodeFasta(compressedArchive,out,true);}finishPending(dna);
                String manifest=buildManifest();Uri man=createTextUri(safeBase(currentArchive.fileName)+"-SpectraDNA-MANIFEST.txt","text/plain");synchronized(made){made.add(man);}try(OutputStream out=getContentResolver().openOutputStream(man,"w")){out.write(manifest.getBytes("UTF-8"));}finishPending(man);
            }catch(Exception e){throw new CompletionException(e);}});
            Uri phone=createAudioUri(WavProfile.PHONE_48);made.add(phone);writeMasterWav(phone,WavProfile.PHONE_48,plan,20,47);finishPending(phone);wavUris.put(WavProfile.PHONE_48,phone);lastWavUri=phone;
            runOnUiThread(()->{playBtn.setEnabled(true);shareBtn.setEnabled(true);status.setText("FORTRESS PHONE AUDIO READY ▶  •  finishing archive masters");playUri(phone);});
            Uri archive=createAudioUri(WavProfile.ARCHIVE_96);made.add(archive);writeMasterWav(archive,WavProfile.ARCHIVE_96,plan,47,68);finishPending(archive);wavUris.put(WavProfile.ARCHIVE_96,archive);
            Uri lab=createAudioUri(WavProfile.LAB_192);made.add(lab);writeMasterWav(lab,WavProfile.LAB_192,plan,68,94);finishPending(lab);wavUris.put(WavProfile.LAB_192,lab);
            if(aux!=null)aux.get();
            runOnUiThread(()->{progress.setProgress(100);status.setText("AIR FORTRESS COMPLETE ✓");details.append("\\n\\nFORTRESS COMPLETE: 48/96/192 kHz + redundant DNA + manifest");setBuildStateUi(false);});
        }catch(Exception e){if(aux!=null)aux.cancel(true);setBuildStateUi(false);fail(e instanceof ExecutionException&&e.getCause() instanceof Exception?(Exception)e.getCause():e);}});
    }'''
main = between(main, build_start, build_end, build_new, 'build everything')

# Manifest and writer dispatcher.
man_start = 'private String buildManifest(){'
man_end = 'private void writeMasterWav(Uri outUri,WavProfile profile,BuildPlan plan'
man_new = '''private String buildManifest(){return "SpectraDNA MAX v3.2 COMPACT EXACT\\nOriginal SHA-256: "+ByteUtil.hex(currentArchive.originalSha)+"\\nDecoded RGBA SHA-256: "+ByteUtil.hex(currentArchive.pixelSha)+"\\nTile Merkle root: "+ByteUtil.hex(currentArchive.merkleRoot)+"\\nCompressed exact archive SHA-256: "+ByteUtil.hex(ByteUtil.sha256(compressedArchive))+"\\nStored source bytes: "+currentArchive.originalBytes.length+"\\nCompact archive bytes: "+compressedArchive.length+"\\nMode: "+(maximumRecovery?"AIR FORTRESS full acoustic redundancy":"COMPACT EXACT RIFF sDNA + short BioWave")+"\\nBio options bitmask: "+bioOptions+"\\nFirefly timing recipe: 125 ms pulse, 0.55 s spacing, 8 pulses, 12 s cycle\\nLevin-related experimental timing palette: 7 / 15 / 60 / 100 Hz modulation\\nOptical reference metadata (not speaker output): 560 / 600-650 / 634 / 660 / 703 / 810 / 830 / 1064 / 1270 nm\\n"+baseDetails()+"\\n";}'''
main = between(main, man_start, man_end, man_new, 'manifest')

old_single_writer = 'private void writeMasterWav(Uri outUri,WavProfile profile)throws Exception{try(BuildPlan plan=prepareBuildPlan()){writeMasterWav(outUri,profile,plan,20,98);}}'
new_single_writer = '''private void writeMasterWav(Uri outUri,WavProfile profile)throws Exception{if(maximumRecovery){try(BuildPlan plan=prepareBuildPlan()){writeMasterWav(outUri,profile,plan,20,98);}}else writeCompactMasterWav(outUri,profile,5,98);}
    private void writeCompactMasterWav(Uri outUri,WavProfile profile,int start,int end)throws Exception{
        runOnUiThread(()->details.append("\\n"+profile.suffix+": 18 s compact timeline • exact source in RIFF sDNA chunk"));
        try(OutputStream out=getContentResolver().openOutputStream(outUri,"w")){if(out==null)throw new IOException("Could not open WAV output");WavVault.writeCompactProfile(out,spectrogramArt,compressedArchive,profile,bioOptions,(p,phase)->setWeightedProgress(start,end,p,phase));}
    }'''
main = rep(main, old_single_writer, new_single_writer, 'writer dispatcher')

# prepareImage: never build duplicate PNG/raw pixel payloads.
prep_start = 'private void prepareImage(Uri uri){'
prep_end = 'private void buildWav(Uri outUri){'
prep_new = '''private void prepareImage(Uri uri){setBusy("Reading image + building compact exact archive…");worker.execute(()->{try{
        byte[] original;try(InputStream in=getContentResolver().openInputStream(uri)){original=ByteUtil.readAll(in);}Bitmap src=BitmapFactory.decodeByteArray(original,0,original.length);if(src==null)throw new IOException("Android could not decode this image");
        byte[] rgba=rawRgba(src);
        CompactArchive a=new CompactArchive();a.fileName=queryName(uri);a.mime=getContentResolver().getType(uri);if(a.mime==null)a.mime="application/octet-stream";a.width=src.getWidth();a.height=src.getHeight();a.colorSpace=src.getColorSpace()==null?"unknown":src.getColorSpace().getName();a.originalBytes=original;a.originalSha=ByteUtil.sha256(original);a.canonicalSha=a.originalSha.clone();a.pixelSha=ByteUtil.sha256(rgba);a.tileHashes=TileIntegrity.hashes(rgba,a.width,a.height,a.tileSize);a.merkleRoot=TileIntegrity.merkleRoot(a.tileHashes);
        byte[] raw=a.serialize(),comp=CompactArchive.compress(raw);Bitmap small=Bitmap.createScaledBitmap(src,224,128,true);int[] px=new int[224*128];small.getPixels(px,0,224,0,0,224,128);short[] art=SpectrogramArt.renderMax(px,224,128);
        currentArchive=a;compressedArchive=comp;spectrogramArt=art;recoveredArchive=null;lastWavUri=null;pendingWavName=safeBase(a.fileName)+"-SpectraDNA-MAX.wav";
        runOnUiThread(()->{preview.setImageBitmap(src);wavBtn.setEnabled(true);phoneBtn.setEnabled(true);archiveBtn.setEnabled(true);labWavBtn.setEnabled(true);dnaBtn.setEnabled(true);labBtn.setEnabled(true);playBtn.setEnabled(false);shareBtn.setEnabled(false);savePngBtn.setEnabled(false);saveOriginalBtn.setEnabled(false);status.setText("IMAGE LOCKED ✓  COMPACT BUILD STARTING…");details.setText("Image: "+a.width+" × "+a.height+"  •  "+a.colorSpace+"\\nOriginal SHA-256: "+ByteUtil.hex(a.originalSha)+"\\nDecoded RGBA SHA-256: "+ByteUtil.hex(a.pixelSha)+"\\nTile Merkle root: "+ByteUtil.hex(a.merkleRoot)+"\\nTiles: "+a.tileHashes.length+"  •  source "+fmt(original.length)+"  •  compact protected archive "+fmt(comp.length)+"\\nPayload duplicates removed: no stored raw RGBA and no second full PNG.\\n\\n"+baseDetails());buildEverything();});
    }catch(Exception e){fail(e);}});}'''
main = between(main, prep_start, prep_end, prep_new, 'prepare image')

# SAVE_PNG now regenerates a true PNG only when the user asks for it.
main = rep(main,
    'else if(request==SAVE_PNG)saveBytes(u,recoveredArchive.canonicalPng,"CANONICAL PNG SAVED ✓");',
    'else if(request==SAVE_PNG)saveCanonicalPng(u);', 'save png dispatch')

# Recovery: use exact RIFF chunk first; fall back to full acoustic modem for fortress/old files.
rec_start = 'private void recoverWav(Uri uri){'
rec_end = 'private void saveDna(Uri uri){'
rec_new = '''private void recoverWav(Uri uri){setBusy("Checking exact sDNA chunk…");worker.execute(()->{try{
        byte[] embedded;try(InputStream in=getContentResolver().openInputStream(uri)){embedded=WavVault.readEmbeddedArchive(in);}
        if(embedded!=null){
            CompactArchive a=parseRecoveredArchive(embedded);verifyBitmapAgainstArchive(a);recoveredArchive=a;currentArchive=a;compressedArchive=CompactArchive.compress(a.serialize());rebuildArtFromArchive(a);Bitmap canon=BitmapFactory.decodeByteArray(a.originalBytes,0,a.originalBytes.length);
            runOnUiThread(()->{preview.setImageBitmap(canon);enableRecoverySaves(a);wavBtn.setEnabled(true);phoneBtn.setEnabled(true);archiveBtn.setEnabled(true);labWavBtn.setEnabled(true);dnaBtn.setEnabled(true);status.setText("EXACT SOURCE + PIXELS + MERKLE VERIFIED ✓");details.setText("Recovered instantly from WAV sDNA chunk\\nOriginal SHA-256: "+ByteUtil.hex(a.originalSha)+"\\nDecoded RGBA SHA-256: "+ByteUtil.hex(a.pixelSha)+"\\nMerkle root: "+ByteUtil.hex(a.merkleRoot)+"\\nExact source bytes: "+fmt(a.originalBytes.length));});return;
        }
        setBusy("No sDNA chunk. Decoding AIR FORTRESS acoustic genome…");
        OfdmCodec.DecodeStats ds;try(InputStream in=getContentResolver().openInputStream(uri)){ds=WavVault.readAndDecode(in,(p,phase)->runOnUiThread(()->status.setText(phase+"  "+p+"%")));}OuterTransport.RecoverResult rr=null;SecondaryTransport.RecoverResult sr=null;byte[] recoveredComp=null;String route="";try{rr=OuterTransport.recover(ds.packetStream);if(rr.compressed!=null){recoveredComp=rr.compressed;route="PRIMARY";}}catch(Exception ignored){}if(ds.secondaryPacketStream!=null){try{sr=SecondaryTransport.recover(ds.secondaryPacketStream);if(recoveredComp==null&&sr.compressed!=null){recoveredComp=sr.compressed;route="SECONDARY";}}catch(Exception ignored){}}if(recoveredComp==null)throw new IOException("Both acoustic recovery branches were incomplete");CompactArchive a=parseRecoveredArchive(recoveredComp);verifyBitmapAgainstArchive(a);recoveredArchive=a;currentArchive=a;compressedArchive=CompactArchive.compress(a.serialize());rebuildArtFromArchive(a);Bitmap canon=BitmapFactory.decodeByteArray(a.originalBytes,0,a.originalBytes.length);final OuterTransport.RecoverResult frr=rr;final SecondaryTransport.RecoverResult fsr=sr;final String froute=route;RescueFsk.Info ri=ds.rescue;runOnUiThread(()->{preview.setImageBitmap(canon);enableRecoverySaves(a);wavBtn.setEnabled(true);phoneBtn.setEnabled(true);archiveBtn.setEnabled(true);labWavBtn.setEnabled(true);dnaBtn.setEnabled(true);status.setText("AIR FORTRESS PIXEL-PERFECT VERIFIED ✓");details.setText("Recovered "+a.width+" × "+a.height+" via "+froute+" modem\\nOriginal SHA-256: "+ByteUtil.hex(a.originalSha)+"\\nDecoded RGBA SHA-256: "+ByteUtil.hex(a.pixelSha)+"\\nMerkle root: "+ByteUtil.hex(a.merkleRoot)+"\\nPrimary packets: "+(frr==null?"unavailable":(frr.validPackets+" valid, "+frr.recoveredShards+" parity-recovered"))+"\\nSecondary packets: "+(fsr==null?"unavailable":(fsr.valid+" valid, "+fsr.recovered+" parity-recovered"))+"\\nRescue FSK: "+(ri!=null&&ri.valid?("VALID, "+ri.copiesValid+"/3 copies") : "not recovered"));});
    }catch(Exception e){fail(e);}});}'''
main = between(main, rec_start, rec_end, rec_new, 'recover wav')

# DNA import can read both new compact archives and older v3.1 archives.
imp_start = 'private void importDna(Uri uri){'
imp_end = 'private void runLab(){'
imp_new = '''private void importDna(Uri uri){setBusy("Consensus-style DNA ensemble recovery…");worker.execute(()->{try{DnaAdvancedCodec.DecodeResult dr;try(InputStream in=getContentResolver().openInputStream(uri)){byte[] dnaText=ByteUtil.readAll(in);String head=new String(dnaText,0,Math.min(dnaText.length,180),"US-ASCII");if(!head.contains(">SpectraDNA-MAX")){if(head.matches("(?s).*\\\\b[0-9a-fA-F]{64}\\\\b.*"))throw new IOException("That is a SHA-256 checksum file, not a DNA MAX archive");throw new IOException("This text file is not a SpectraDNA MAX DNA archive");}dr=DnaAdvancedCodec.decodeFasta(new ByteArrayInputStream(dnaText));}CompactArchive a=parseRecoveredArchive(dr.data);verifyBitmapAgainstArchive(a);recoveredArchive=a;currentArchive=a;compressedArchive=CompactArchive.compress(a.serialize());rebuildArtFromArchive(a);pendingWavName=safeBase(a.fileName)+"-SpectraDNA-MAX.wav";lastWavUri=null;Bitmap canon=BitmapFactory.decodeByteArray(a.originalBytes,0,a.originalBytes.length);runOnUiThread(()->{preview.setImageBitmap(canon);enableRecoverySaves(a);wavBtn.setEnabled(true);phoneBtn.setEnabled(true);archiveBtn.setEnabled(true);labWavBtn.setEnabled(true);playBtn.setEnabled(false);shareBtn.setEnabled(false);status.setText("DNA → EXACT SOURCE + PIXELS VERIFIED ✓");details.setText("DENSE valid: "+dr.validDense+"  •  SYNC valid: "+dr.validSync+"\\nRejected/damaged records: "+dr.badRecords+"  •  parity-recovered shards: "+dr.recoveredShards+"\\nOriginal SHA-256: "+ByteUtil.hex(a.originalSha)+"\\nDecoded RGBA SHA-256: "+ByteUtil.hex(a.pixelSha)+"\\nTile Merkle root: "+ByteUtil.hex(a.merkleRoot));});}catch(Exception e){fail(e);}});}'''
main = between(main, imp_start, imp_end, imp_new, 'import dna')

# Replace verification and add helpers before rawRgba.
verify_start = 'private void verifyBitmapAgainstArchive(CompactArchive a)throws Exception{'
verify_end = 'private byte[] rawRgba('
verify_new = '''private void verifyBitmapAgainstArchive(CompactArchive a)throws Exception{
        if(a.originalBytes==null||a.originalBytes.length==0)throw new IOException("Recovered archive has no original source bytes");
        if(!ByteUtil.equals(ByteUtil.sha256(a.originalBytes),a.originalSha))throw new IOException("Original source SHA-256 mismatch");
        Bitmap b=BitmapFactory.decodeByteArray(a.originalBytes,0,a.originalBytes.length);if(b==null)throw new IOException("Recovered source image could not decode");
        byte[] rgba=rawRgba(b);if(!ByteUtil.equals(ByteUtil.sha256(rgba),a.pixelSha))throw new IOException("Decoded-pixel SHA-256 mismatch");
        if(!TileIntegrity.verify(rgba,a.width,a.height,a.tileSize,a.tileHashes,a.merkleRoot))throw new IOException("Tile/Merkle verification failed after image decode");
    }
    private CompactArchive parseRecoveredArchive(byte[] packed)throws Exception{
        try{return CompactArchive.parse(CompactArchive.inflate(packed));}
        catch(Exception compactError){
            ArchiveContainer old=ArchiveContainer.parse(ArchiveContainer.inflate(packed));
            CompactArchive a=new CompactArchive();a.fileName=old.fileName;a.mime=old.mime;a.colorSpace=old.colorSpace;a.width=old.width;a.height=old.height;a.tileSize=old.tileSize;
            if(old.originalBytes!=null&&old.originalBytes.length>0)a.originalBytes=old.originalBytes;else if(old.canonicalPng!=null&&old.canonicalPng.length>0)a.originalBytes=old.canonicalPng;else throw compactError;
            a.originalSha=ByteUtil.sha256(a.originalBytes);a.canonicalSha=a.originalSha.clone();Bitmap b=BitmapFactory.decodeByteArray(a.originalBytes,0,a.originalBytes.length);if(b==null)throw compactError;byte[] rgba=rawRgba(b);a.pixelSha=ByteUtil.sha256(rgba);a.tileHashes=TileIntegrity.hashes(rgba,a.width,a.height,a.tileSize);a.merkleRoot=TileIntegrity.merkleRoot(a.tileHashes);return a;
        }
    }
    private void rebuildArtFromArchive(CompactArchive a)throws IOException{Bitmap canon=BitmapFactory.decodeByteArray(a.originalBytes,0,a.originalBytes.length);if(canon==null)throw new IOException("Recovered source image could not decode");Bitmap small=Bitmap.createScaledBitmap(canon,224,128,true);int[] px=new int[224*128];small.getPixels(px,0,224,0,0,224,128);spectrogramArt=SpectrogramArt.renderMax(px,224,128);}
    private void saveCanonicalPng(Uri uri){worker.execute(()->{try{Bitmap b=BitmapFactory.decodeByteArray(recoveredArchive.originalBytes,0,recoveredArchive.originalBytes.length);if(b==null)throw new IOException("Recovered source image could not decode");try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null||!b.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG export failed");}runOnUiThread(()->status.setText("CANONICAL PNG SAVED ✓"));}catch(Exception e){fail(e);}});}
    '''
main = between(main, verify_start, verify_end, verify_new, 'verification helpers')

main = rep(main,
    'private void enableRecoverySaves(CompactArchive a){savePngBtn.setEnabled(true);saveOriginalBtn.setEnabled(a.originalBytes!=null&&a.originalBytes.length>0);}',
    'private void enableRecoverySaves(CompactArchive a){savePngBtn.setEnabled(a.originalBytes!=null&&a.originalBytes.length>0);saveOriginalBtn.setEnabled(a.originalBytes!=null&&a.originalBytes.length>0);}', 'recovery save enable')

# ---------- WavVault compact RIFF sDNA path ----------
wav = rep(wav, 'import java.io.*;\n', 'import java.io.*;\nimport java.security.MessageDigest;\nimport java.util.Arrays;\nimport java.util.zip.CRC32;\n', 'wav imports')
insert_anchor = '    private static short clip(double v)'
compact_methods = r'''    private static final double COMPACT_SECONDS=18.0;

    /**
     * Short normal-use master. The exact compressed archive lives once in a
     * checked RIFF sDNA chunk; the PCM timeline is reserved for the visible
     * spectrogram and experimental BioWave recipe. AIR FORTRESS uses the old
     * full acoustic modem path instead.
     */
    public static void writeCompactProfile(OutputStream rawOut,short[] art,byte[] archive,WavProfile profile,int bioOptions,Progress cb)throws IOException{
        if(archive==null)throw new IOException("Missing exact archive payload");
        if(art==null)art=new short[0];
        long baseFrames=Math.round(COMPACT_SECONDS*OfdmCodec.SAMPLE_RATE);
        long frames=baseFrames*profile.factor;
        byte[] sdna=buildEmbeddedChunk(archive);
        long pad=sdna.length&1;
        long extra=8L+sdna.length+pad;
        long dataBytes=frames*4L;
        long riff=36L+dataBytes+extra;
        if(riff>0xffffffffL)throw new IOException("Compact WAV exceeds classic RIFF limit");
        BufferedOutputStream out=new BufferedOutputStream(rawOut,1024*1024);
        writeCompactHeader(out,frames,profile.sampleRate,extra);
        BioWaveCarrier bio=new BioWaveCarrier(archive,art,profile.sampleRate,frames,bioOptions);
        CRC32 seedCrc=new CRC32();seedCrc.update(archive);long seed=seedCrc.getValue();
        byte[] io=new byte[64*1024];int p=0;
        for(long f=0;f<frames;f++){
            double frac=f/(double)Math.max(1,frames-1);
            int ai=art.length==0?0:(int)Math.min(art.length-1,Math.floor(frac*art.length));
            double artSample=art.length==0?0:art[ai]*0.72;
            double env=experimentalTimingEnvelope(f,profile.sampleRate,seed);
            double bioL=bio.sample(f,false)*5000.0*env;
            double bioR=bio.sample(f,true)*5600.0*env;
            double t=f/(double)profile.sampleRate;
            double keyHz=1200.0+(seed%1800L);
            double key=Math.sin(2.0*Math.PI*keyHz*t)*900.0*(0.30+0.70*fireflyPulse(t,seed));
            short l=clip(artSample+bioL+key*0.35),r=clip(bioR+key);
            io[p++]=(byte)(l&255);io[p++]=(byte)((l>>>8)&255);io[p++]=(byte)(r&255);io[p++]=(byte)((r>>>8)&255);
            if(p>io.length-4){out.write(io,0,p);p=0;}
            if(cb!=null&&f%(profile.sampleRate/2L)==0)cb.onProgress((int)Math.min(94,95L*f/Math.max(1,frames)),"Rendering 18 s compact spectrogram + BioWave");
        }
        if(p>0)out.write(io,0,p);
        writeAscii(out,"sDNA");le32(out,sdna.length);out.write(sdna);if((sdna.length&1)!=0)out.write(0);
        out.flush();if(cb!=null)cb.onProgress(100,"Compact exact WAV complete");
    }

    /** Returns the compressed exact archive from a v3.2 WAV, or null for legacy/Fortress WAVs. */
    public static byte[] readEmbeddedArchive(InputStream rawIn)throws IOException{
        BufferedInputStream in=new BufferedInputStream(rawIn,256*1024);byte[] head=new byte[12];int first=in.read(head);if(first<0)return null;if(first<12){readFully(in,head,first,12-first);}if(!ascii(head,0,"RIFF")||!ascii(head,8,"WAVE"))throw new IOException("Expected RIFF/WAVE file");
        byte[] ch=new byte[8];
        while(true){int n=in.read(ch);if(n<0)return null;if(n<8)readFully(in,ch,n,8-n);String id=new String(ch,0,4,"US-ASCII");long size=i32le(ch,4)&0xffffffffL;if(size>0xffffffffL)throw new IOException("Invalid RIFF chunk size");
            if("sDNA".equals(id)){if(size>512L*1024L*1024L)throw new IOException("sDNA chunk is unreasonably large");byte[] payload=new byte[(int)size];readFully(in,payload,0,payload.length);return parseEmbeddedChunk(payload);}
            skipFully(in,size);if((size&1L)!=0)skipFully(in,1);
        }
    }

    private static byte[] buildEmbeddedChunk(byte[] archive)throws IOException{
        byte[] sha=sha256(archive);CRC32 crc=new CRC32();crc.update(archive);ByteArrayOutputStream b=new ByteArrayOutputStream(archive.length+48);writeAscii(b,"SDN4");le32(b,1);le32(b,archive.length);b.write(sha);le32(b,crc.getValue());b.write(archive);return b.toByteArray();
    }
    private static byte[] parseEmbeddedChunk(byte[] p)throws IOException{if(p.length<48||!ascii(p,0,"SDN4"))throw new IOException("Invalid sDNA chunk");int version=i32le(p,4);int len=i32le(p,8);if(version!=1||len<0||len!=p.length-48)throw new IOException("sDNA chunk length/version mismatch");byte[] expected=Arrays.copyOfRange(p,12,44);long stored=i32le(p,44)&0xffffffffL;byte[] archive=Arrays.copyOfRange(p,48,p.length);CRC32 crc=new CRC32();crc.update(archive);if(crc.getValue()!=stored)throw new IOException("sDNA CRC mismatch");if(!Arrays.equals(expected,sha256(archive)))throw new IOException("sDNA SHA-256 mismatch");return archive;}
    private static byte[] sha256(byte[] b)throws IOException{try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception e){throw new IOException("SHA-256 unavailable",e);}}
    private static void skipFully(InputStream in,long n)throws IOException{long left=n;byte[] buf=new byte[64*1024];while(left>0){long s=in.skip(left);if(s>0){left-=s;continue;}int r=in.read(buf,0,(int)Math.min(buf.length,left));if(r<0)throw new EOFException();left-=r;}}
    private static void writeCompactHeader(OutputStream out,long frames,int rate,long extra)throws IOException{long data=frames*4L;writeAscii(out,"RIFF");le32(out,36L+data+extra);writeAscii(out,"WAVE");writeAscii(out,"fmt ");le32(out,16);le16(out,1);le16(out,2);le32(out,rate);le32(out,rate*4L);le16(out,4);le16(out,16);writeAscii(out,"data");le32(out,data);}

    // Firefly-inspired phase-lock timing envelope. Optical wavelength numbers
    // remain metadata; this only borrows measured flash timing structure.
    private static double fireflyPulse(double t,long seed){double shifted=t+((seed&1023L)/1023.0)*0.55;double c=shifted%12.0;if(c<0)c+=12.0;double best=0;for(int i=0;i<8;i++){double d=Math.abs(c-i*0.55);if(d<0.125){double x=d/0.125;best=Math.max(best,0.5+0.5*Math.cos(Math.PI*x));}}return best;}
    private static double experimentalTimingEnvelope(long frame,int rate,long seed){double t=frame/(double)rate;double fire=fireflyPulse(t,seed);double lev=0.5+0.125*(Math.sin(2*Math.PI*7*t)+Math.sin(2*Math.PI*15*t)+Math.sin(2*Math.PI*60*t)+Math.sin(2*Math.PI*100*t));lev=Math.max(0,Math.min(1,lev));return 0.62+0.23*fire+0.15*lev;}

'''
if insert_anchor not in wav:
    raise SystemExit('missing WavVault compact insertion anchor')
wav = wav.replace(insert_anchor, compact_methods + insert_anchor, 1)

main_path.write_text(main)
wav_path.write_text(wav)
print('Applied SpectraDNA v3.2 COMPACT EXACT overlay')
