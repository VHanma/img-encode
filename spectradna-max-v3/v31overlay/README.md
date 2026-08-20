# SpectraDNA Vault MAX v3.1 — AUTO FAST BioWave MAX

Image → visible spectrogram → exact acoustic archive → redundant DNA text archive.

## Primary goal

The audio file is the primary artifact. The app never treats a visually similar image as recovery success. Exact recovery is verified from stored bytes, canonical pixels, SHA-256 hashes, tile hashes and a Merkle root.

## Zero-tap-after-selection AUTO FAST build

Pick an image once. The app immediately starts **AUTO BUILD EVERYTHING** with no second button press. It computes the expensive protected 48 kHz OFDM/spectrogram grid once, reuses it for all three masters, makes the 48 kHz phone master first and auto-plays it while the remaining outputs continue in the background. Quality, sample rates, ECC and BioWave layers are unchanged.

The automatic build writes:

- `*-SpectraDNA-PHONE-48k.wav` to `Music/SpectraDNA`
- `*-SpectraDNA-ARCHIVE-96k.wav` to `Music/SpectraDNA`
- `*-SpectraDNA-LAB-192k.wav` to `Music/SpectraDNA`
- `SpectraDNA-MAX-archive.fasta.txt` to `Downloads/SpectraDNA`
- a SHA/Merkle manifest to `Downloads/SpectraDNA`

The phone master auto-plays as soon as it is ready; 96 kHz, 192 kHz, DNA and the manifest keep finishing automatically.

## Exact acoustic layer

Every master contains a protected **48 kHz sample grid**:

- right: frequency-diverse QPSK-OFDM primary modem
- left: independent high-band QPSK modem + BFSK rescue header + visible spectrogram
- primary 6+4 GF(256) erasure transport, three time-diversity passes
- secondary independent 4+4 transport
- Hamming(8,4), CRC and SHA verification

The 96/192 kHz masters put experimental samples **between** the protected-grid samples. Selecting each 2nd/4th sample recreates the protected 48 kHz grid exactly in the file domain.

## Visible spectrogram layer

The image is rendered with deterministic complex STFT and inverse FFT, without Griffin-Lim phase estimation. MAX v3 displays three spectrogram passes:

1. full image
2. coarse/pixelated multiresolution image
3. edge reconstruction

This provides visual multiresolution redundancy while the exact file/pixel bytes remain in the modem.

## Experimental BioWave layer

All are individually switchable and default ON in MAX mode:

- **Gariaev-inspired carrier bank**: DNA-signature modulation moves continuously inside 150–300, 800–900, 1700–1900, 2400–2600 and 3600–3800 Hz regions reported in wave-genetics material. These are experimental source-derived regions, not established universal DNA resonances.
- **DNA phonon-ratio sonification**: an audible ratio projection of representative THz collective-mode values. Frequency ratios are preserved; the audio is not claimed to physically reproduce THz radiation.
- **DNA alphabet soliton signature**: image/archive hashes become a deterministic A/C/G/T signature that controls sech² pulse envelopes, carrier selection, phase and chirp.
- **Phase-conjugate / scalar-inspired stereo**: counter-phase stereo representation. The implementation is measurable stereo phase structure and does not claim an established exotic scalar field.
- **~45 kHz extended-band carrier** in 96/192 kHz files. Its macro envelope mirrors the 40 s exposure window used in the 2025 SUPER sonogenetic study, without claiming the file reproduces that study's calibrated acoustic intensity. Actual acoustic output depends on playback hardware/transducer bandwidth.

## DNA archive

- DENSE differential branch
- SYNC/edit-repair branch
- GF(256) outer parity
- per-record CRC
- master SHA-256 verification
- insertion/deletion resynchronization support
- DNA import rejects checksum/text files immediately with a clear error instead of a Java stack trace

## Validation

Local pure-Java validation for this source revision:

- outer/DNA destruction suite: **22/22 PASS**
- 48 kHz master round-trip: PASS
- 96 kHz master round-trip: PASS
- 192 kHz master round-trip: PASS
- 96 kHz non-protected interstitial samples replaced with random noise: exact protected-grid recovery PASS
- 192 kHz non-protected interstitial samples replaced with random noise: exact protected-grid recovery PASS
- DNA A/C/G/T signature alphabet validation: PASS

These are deterministic codec/file-domain tests. Physical speaker → air → microphone performance depends on the hardware and channel.

## Research separation

The code intentionally separates two questions:

1. **Can the image be recovered exactly from the sound?** This is tested digitally and cryptographically.
2. **Do experimental acoustic/phase patterns have biological effects?** Those layers are exploratory and are labeled as such rather than being used as proof of biological transfer.

Relevant research families behind the experimental modes include measured THz collective modes of DNA, ultrasound/mechanogenetic gene-control research, and Gariaev/wave-genetics claims. The experimental carrier does not convert an audible frequency into literal THz radiation or optical polarization.
