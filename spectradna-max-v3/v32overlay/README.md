# SpectraDNA Vault MAX v3.2 — COMPACT EXACT

Image → short visible spectrogram/BioWave audio → exact embedded source → cryptographic pixel verification.

## What changed

v3.1 proved the full acoustic archive path, but its default behavior was too expensive for normal phone use: it generated 48, 96 and 192 kHz masters, a redundant DNA text archive, multiple modem branches and heavy time-diversity. A single image could therefore become a very long, very large set of files.

v3.2 makes **COMPACT EXACT** the default and keeps the full acoustic system as an optional **AIR FORTRESS** mode.

## COMPACT EXACT default

After one image selection the app automatically creates:

- one 48 kHz stereo WAV in `Music/SpectraDNA`
- one small SHA/Merkle manifest in `Downloads/SpectraDNA`

The normal master uses an **18-second playable timeline**. It contains the deterministic visible-spectrogram/BioWave sonification and a custom RIFF `sDNA` chunk carrying the compressed exact source archive.

The `sDNA` chunk contains:

- exact compressed source archive
- SHA-256 of that archive
- CRC32 transport check
- format/version/length framing

Recovery checks the `sDNA` hash before the image archive is accepted.

### Exactness

The compact archive stores the source image file **exactly once**. v3.1's duplicate full canonical PNG and full raw-RGBA payload are removed from storage.

The app still verifies three independent truths:

1. **Original/source SHA-256** — byte-for-byte stored source file
2. **Decoded RGBA SHA-256** — exact decoded pixel state
3. **Tile hashes + Merkle root** — spatial integrity of the decoded pixels

Raw RGBA is computed transiently during encode/recovery for verification, then discarded instead of being permanently duplicated in the payload.

If the user asks to export a canonical PNG, the APK regenerates it from the verified recovered source at that moment.

## Important RIFF distinction

COMPACT EXACT is optimized for **small, fast, exact WAV-file storage and transfer**. The exact archive is carried in a RIFF metadata/data chunk, so normal file copying preserves it.

An audio editor or transcoder that strips unknown RIFF chunks can remove that exact archive. Playing the compact WAV through a speaker and recording only its audible waveform also does not transmit the embedded file chunk.

For experiments that specifically need the picture encoded through the acoustic waveform itself, enable **AIR FORTRESS**.

## AIR FORTRESS optional mode

AIR FORTRESS retains the v3.1 protected acoustic architecture:

- frequency-diverse QPSK-OFDM primary modem
- independent secondary OFDM branch
- rescue BFSK header
- GF(256) erasure transport
- Hamming protection, CRC and SHA verification
- time diversity
- 48 kHz phone master
- optional 96 kHz archive master
- optional 192 kHz lab master
- redundant DNA text archive

It is intentionally larger and longer because it is solving a different problem: redundant recovery from the waveform itself rather than compact exact file storage.

## Experimental BioWave timing layer

The biological/fringe-inspired layer remains separate from the exact codec. It cannot corrupt the stored archive or be used as proof of biological transfer.

### Firefly phase-lock recipe

The compact timeline now contains a deterministic pulse-synchronization envelope inspired by measured synchronous-firefly timing:

- 125 ms representative flash pulse
- approximately 0.55 s spacing
- 8-pulse packet
- 12 s global cycle
- archive-derived phase offset, so different images produce deterministic but distinct phase placement

This borrows a biological oscillator/synchronization strategy. The APK produces audio modulation, not literal firefly light.

### Levin-related timing palette

The experimental amplitude envelope contains 7, 15, 60 and 100 Hz components.

These values are deliberately labeled **Levin-related experimental timing references**, not universal “bioelectricity frequencies”:

- 7, 15 and 100 Hz relate to low-frequency mechanical-stimulation work in developmental models
- 60 Hz relates to earlier electromagnetic-field work

The mechanisms and experimental conditions are different, so v3.2 keeps them as a modulation palette rather than claiming one biological law.

### Optical/biophoton reference metadata

The manifest can preserve optical reference bands for future phone-light/external-light experiments:

- 560 nm
- 600–650 nm
- 634 nm
- 660 nm
- 703 nm
- 810 nm
- 830 nm
- 1064 nm
- 1270 nm

These are wavelength metadata. A phone speaker does not emit those optical wavelengths.

## Existing BioWave families retained

- Gariaev-inspired reported carrier regions
- DNA collective-mode / phonon-ratio sonification
- deterministic A/C/G/T soliton signature
- phase-conjugate / counter-phase stereo structure
- extended-band experimental layer on capable high-rate profiles

They remain exploratory layers while the exact image-recovery path is tested independently.

## Storage target

For the default 48 kHz stereo 16-bit compact master, 18 seconds of PCM is about **3.46 MB**, plus the compressed exact image archive and a small RIFF overhead. The APK no longer automatically creates the 96 kHz, 192 kHz and DNA-text duplicates.

That means the output size now scales mostly with:

`~3.46 MB audio body + exact source archive`

rather than with hours of PCM multiplied across several masters.

## Build validation

The GitHub workflow still runs the existing codec/self-test suite, then compiles the v3.2 overlay and APK. The full v3.1 acoustic decoder is retained for AIR FORTRESS and legacy recovery.
