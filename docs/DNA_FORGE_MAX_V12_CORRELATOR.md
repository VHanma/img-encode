# DNA Forge Max v12 — Gariaev Optical Correlator Emulator

v12 adds a Gariaev-style optical correlator emulator on top of v11.

## New flow

1. Donor image scan
2. Mic resonance scan
3. Gariaev correlator scan
4. Forge v12 WAV

## Correlator scan passes

- Dark-frame control
- Red reference illumination
- Dim-red reference illumination
- 7.83 Hz pulse proxy
- 3/6/9 pulse proxy

## Extracted values

- Speckle-change proxy
- Brightness fluctuation
- Edge fluctuation
- Color shift
- Optical feedback matrix
- MBER modulation index

## Engine changes

The optical feedback matrix is injected into:

- dynamic phase-shift matrix
- right-channel optical feedback surrogate
- WAV pre-payload optical feedback layer
- JSON hardware/export manifest

## Safety

This is a phone-based software emulator/controller.

It does not create real 632.8 nm He-Ne laser emission.
It does not create true MBER or UHF fields by itself.
It is for symbolic signal design, research logging, and external-hardware profile export.
