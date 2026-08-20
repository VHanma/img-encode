#!/usr/bin/env python3
import pathlib, sys

if len(sys.argv) != 2:
    raise SystemExit('usage: tighten_compact.py <SpectraDNA source root>')
root = pathlib.Path(sys.argv[1])
main_path = root / 'app/src/main/java/com/vaan/spectradna/MainActivity.java'
wav_path = root / 'app/src/main/java/com/vaan/spectradna/WavVault.java'
main = main_path.read_text()
wav = wav_path.read_text()

old = 'private static final double COMPACT_SECONDS=18.0;'
if old not in wav:
    raise SystemExit('18-second compact constant anchor missing')
wav = wav.replace(old, 'private static final double COMPACT_SECONDS=12.0;', 1)
wav = wav.replace('Rendering 18 s compact spectrogram + BioWave', 'Rendering 12 s compact spectrogram + BioWave')
main = main.replace('18 s playable master', '12 s playable master')
main = main.replace('18 s AUDIO + EXACT SOURCE', '12 s AUDIO + EXACT SOURCE')
main = main.replace('18 s compact timeline', '12 s compact timeline')
main_path.write_text(main)
wav_path.write_text(wav)
print('Tightened COMPACT EXACT timeline to one 12-second firefly cycle')
