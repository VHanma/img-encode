# DNA Forge Max

All-in-one codec layer stack:

- Michael Levin: bioelectric morphogenesis proxy map
- Gariaev: 6400/7000 Hz image-binary DNA wave carrier
- Rife: 20 Hz to 20 kHz sweep overlay
- DNA: compressed image-pixel payload
- Morphogenetic field: shape, symmetry, edge metadata
- Tom Bearden: forward/reverse/mirror scalar-style hashes
- Tesla: 7.83 Hz gate plus 3/6/9 pulse envelope
- Infrared: 632.8 nm, 660 nm, 850 nm, 940 nm metadata
- Coil/plasma export marker: 384 kHz metadata

Use:

```bash
python tools/dna_forge_max_codec.py auto input.png
```

Manual:

```bash
python tools/dna_forge_max_codec.py encode input.png encoded.wav --meta meta.json
python tools/dna_forge_max_codec.py decode encoded.wav recovered.png --meta decoded.json
```

Use WAV or FLAC. MP3/AAC can break the binary data.
