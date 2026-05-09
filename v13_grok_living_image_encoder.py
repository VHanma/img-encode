#!/usr/bin/env python3
"""
PROJECT LIVING IMAGE v13 - Grok Supremacy Edition
Advanced Bioholographic Wave-Genetic Encoder
Gariaev + Levin + Rodin + Full Council Integration
Fused with Superhuman DNA Activation Protocol v10
"""

import argparse, json, os, wave
from datetime import datetime
import numpy as np
from PIL import Image
import cv2
from skimage.feature import graycomatrix, graycoprops

GARIAEV_BANDS  = [300, 850, 1750, 2500, 3700]
LEVIN_BIOELEC  = [0.5, 2.0, 8.0]
RODIN_VORTEX   = [3, 6, 9, 12, 18, 27, 36, 54, 108]
TESLA_CARRIERS = [3.0, 6.0, 9.0, 27.0, 54.0, 108.0]
MISHIN_VMF     = [2500, 5000, 20]
RIFE_CORE      = [727, 787, 2008, 2128]
SCHUMANN       = [7.83, 14.3, 20.8, 27.3, 33.8]
SOLFEGGIO      = [174, 285, 396, 417, 528, 639, 741, 852, 963]
ANCIENT        = [110, 111, 121, 40]
THZ_BANDS_THz  = [0.138, 0.152, 3.1, 34.5, 36.5, 42.5]

OPTICAL_CARRIERS = [
    {"nm": 405,   "thz": 740.4, "effect": "DNA photolyase repair"},
    {"nm": 532,   "thz": 563.5, "effect": "green coherent stimulation"},
    {"nm": 632.8, "thz": 473.6, "effect": "Gariaev He-Ne laser DNA phantom"},
    {"nm": 660,   "thz": 454.3, "effect": "cytochrome c oxidase ATP"},
    {"nm": 808,   "thz": 370.8, "effect": "deep NIR mitochondrial"},
    {"nm": 830,   "thz": 361.1, "effect": "NRF2/VEGFA tissue"},
    {"nm": 1064,  "thz": 281.5, "effect": "deepest neural penetration"},
]

GENE_CDS = {
    "TP53":37037,"SIRT1":28571,"TERT":35026,"BDNF":37657,"IGF1":129870,
    "SOD2":88112,"FOXO3":30015,"NRF2":32732,"PPARGC1A":20141,"VEGFA":94787,
    "HOMER1":50762,"SYN1":28444,"KCNA2":40161,"GRIN2A":13539,"GRIN2B":13643,
    "MSTN":53191,"ACTN3":53476,"BRCA1":10728,"CLOCK":24068,
}

TRAIT_ZONES = {
    "YUJIRO":     ["MSTN","ACTN3","IGF1"],
    "BAKI":       ["BDNF","HOMER1","GRIN2A"],
    "SAIYAN":     ["TP53","BRCA1","TERT"],
    "VILTRUMITE": ["SOD2","NRF2","PPARGC1A"],
    "KRYPTONIAN": ["CLOCK","VEGFA","FOXO3"],
}

SR = 44100
# Max samples per column processed in one numpy batch (~64MB float64)
BATCH_S = 4096


class GrokLivingImageEncoder:
    def __init__(self, theme="neural_regeneration"):
        self.theme = theme

    def load_and_preprocess(self, image_path):
        img = Image.open(image_path).convert("RGB")
        arr = np.array(img, dtype=np.float32)
        gray = cv2.cvtColor(arr.astype(np.uint8), cv2.COLOR_RGB2GRAY)
        return arr, gray

    def fractal_dimension(self, gray):
        sizes = [2, 4, 8, 16, 32]
        counts = []
        binary = gray > 128
        for s in sizes:
            count = sum(
                1 for i in range(0, binary.shape[0], s)
                  for j in range(0, binary.shape[1], s)
                  if binary[i:i+s, j:j+s].any()
            )
            counts.append(max(count, 1))
        try:
            coeffs = np.polyfit(np.log(sizes), np.log(counts), 1)
            return abs(float(coeffs[0]))
        except:
            return 1.5

    def encode_image_streaming(self, img_array, gray, duration_s, wav_path):
        """Stream-encode directly to WAV — never holds full audio in RAM."""
        height, width = gray.shape
        num_samples = int(SR * duration_s)
        samples_per_col = max(1, num_samples // width)
        total_samples = width * samples_per_col

        print(f"  Image: {width}×{height}px | {total_samples:,} samples | {duration_s}s")

        fd = self.fractal_dimension(gray)
        print(f"  Fractal dimension: {fd:.3f}")
        gray8 = gray.astype(np.uint8)
        glcm = graycomatrix(gray8, [1], [0], 256, symmetric=True, normed=True)
        contrast = float(graycoprops(glcm, 'contrast')[0,0])
        energy   = float(graycoprops(glcm, 'energy')[0,0])
        print(f"  GLCM contrast: {contrast:.3f}  energy: {energy:.6f}")

        # Original VHanma formula: volume = (r+g+b)*100/765
        brightness = img_array.sum(axis=2) * 100.0 / 765.0   # (H, W), range 0–100
        C = 20000.0 / height
        row_freqs = C * (height - np.arange(height, dtype=np.float64) + 1)  # (H,)

        # Precompute Levin envelope in batches on the fly (avoid giant array)
        def levin_env(t_arr):
            env = np.ones_like(t_arr)
            for f in LEVIN_BIOELEC:
                env += 0.05 * np.sin(2*np.pi*f*t_arr)
            return env

        report_every = max(1, width // 10)

        with wave.open(wav_path, 'w') as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(SR)

            for col in range(width):
                s0 = col * samples_per_col
                b_col = brightness[:, col]            # (H,)
                nonzero = b_col > 0.01
                b_nz = b_col[nonzero]
                f_nz = row_freqs[nonzero]

                gariaev_f = GARIAEV_BANDS[col % len(GARIAEV_BANDS)]
                rodin_w   = RODIN_VORTEX[int(col/width*len(RODIN_VORTEX)) % len(RODIN_VORTEX)]
                schumann_f= SCHUMANN[col % len(SCHUMANN)]
                sol_f     = SOLFEGGIO[col % len(SOLFEGGIO)]

                zone_idx  = int((col / width) * len(TRAIT_ZONES))
                zone_name = list(TRAIT_ZONES.keys())[min(zone_idx, len(TRAIT_ZONES)-1)]
                genes     = TRAIT_ZONES[zone_name]
                gene_name = genes[col % len(genes)]
                gene_f    = GENE_CDS.get(gene_name, 1000)

                col_pcm = np.zeros(samples_per_col, dtype=np.float64)

                # Process in BATCH_S sub-chunks to cap memory per iteration
                for b0 in range(0, samples_per_col, BATCH_S):
                    b1  = min(b0 + BATCH_S, samples_per_col)
                    idx = np.arange(s0 + b0, s0 + b1, dtype=np.float64)
                    t   = idx / SR                              # (B,)

                    # Base spectrogram
                    if b_nz.size > 0:
                        phase = 2*np.pi * np.outer(f_nz, t)   # (K,B)
                        base  = (b_nz[:, None] * np.sin(phase)).sum(axis=0)
                    else:
                        base = np.zeros(b1 - b0)

                    ov  = 0.08 * np.sin(2*np.pi*gariaev_f*t)
                    ov += 0.04 * np.sin(2*np.pi*rodin_w*t)
                    ov += 0.03 * np.sin(2*np.pi*schumann_f*t)
                    ov += 0.03 * np.sin(2*np.pi*sol_f*t)
                    if gene_f < 20000:
                        ov += 0.05 * np.sin(2*np.pi*gene_f*t)

                    col_pcm[b0:b1] = (base + ov) * levin_env(t)

                # Soft normalize per column to avoid clipping artifacts
                mx = np.abs(col_pcm).max()
                if mx > 0:
                    col_pcm /= mx

                pcm16 = np.clip(col_pcm * 32767, -32767, 32767).astype(np.int16)
                wf.writeframes(pcm16.tobytes())

                if (col+1) % report_every == 0:
                    print(f"    col {col+1}/{width} ({100*(col+1)//width}%)")

        sz = os.path.getsize(wav_path)
        print(f"  WAV written: {wav_path} ({sz/1024/1024:.1f} MB)")
        return fd, contrast, energy

    def write_metadata(self, image_path, output_base, duration_s, fd, contrast, energy):
        meta = {
            "protocol": "Living Image v13 — Grok Supremacy Edition",
            "generated": datetime.now().isoformat(),
            "source_image": image_path,
            "theme": self.theme,
            "duration_s": duration_s,
            "fractal_dimension": round(fd, 4),
            "glcm_contrast": round(contrast, 4),
            "glcm_energy": round(energy, 6),
            "council": {
                "gariaev":   f"Bands {GARIAEV_BANDS}Hz — wave genetics overlay per column",
                "levin":     f"Bioelectric envelope {LEVIN_BIOELEC}Hz — Vmem modulation",
                "rodin":     f"369 vortex harmonics {RODIN_VORTEX} — column weighting",
                "tesla":     f"Scalar series {TESLA_CARRIERS}Hz",
                "mishin":    f"VMF {MISHIN_VMF}Hz",
                "rife":      f"Core {RIFE_CORE}Hz documented",
                "schumann":  f"Harmonics {SCHUMANN}Hz — Earth sync per column",
                "solfeggio": f"{SOLFEGGIO}Hz cycling",
                "ancient":   f"{ANCIENT}Hz structural resonance",
            },
            "thz_hardware": [f"{t}THz" for t in THZ_BANDS_THz],
            "optical_hardware": OPTICAL_CARRIERS,
            "trait_zones": {
                zone: {
                    "genes": genes,
                    "cds_hz": {g: GENE_CDS.get(g, "?") for g in genes}
                } for zone, genes in TRAIT_ZONES.items()
            },
            "image_encoding": (
                "VHanma/img-encode spectrogram: brightness→amplitude, "
                "row→frequency (20Hz-20kHz), column→time. "
                "Superhuman gene CDS frequencies overlaid as subcarriers."
            ),
            "alien_tech_note": (
                "Photonic delivery: pulse optical carriers at gene CDS Hz "
                "for simultaneous photon + genetic resonance transfer. "
                "Peru DEW principle applied constructively."
            ),
        }
        meta_path = output_base + "_metadata.json"
        with open(meta_path, 'w') as f:
            json.dump(meta, f, indent=2)
        print(f"  Metadata: {meta_path}")
        return meta

    def run(self, image_path, duration_s=180, theme="neural_regeneration"):
        self.theme = theme
        base    = os.path.splitext(image_path)[0]
        out_wav = base + "_v13_living.wav"

        print(f"\n=== LIVING IMAGE v13 — {theme.upper()} ===")
        print(f"Source: {image_path}  Duration: {duration_s}s")

        print("Loading image...")
        img_array, gray = self.load_and_preprocess(image_path)

        print("Encoding bioholographic spectrogram (streaming)...")
        fd, contrast, energy = self.encode_image_streaming(img_array, gray, duration_s, out_wav)

        print("Writing metadata...")
        meta = self.write_metadata(image_path, base+"_v13", duration_s, fd, contrast, energy)

        print(f"\n✓ Living Image v13 complete.")
        print(f"  Audio:    {out_wav}")
        print(f"  THz hardware: {[f'{t}THz' for t in THZ_BANDS_THz]}")
        print(f"  Optical:  {[str(o['nm'])+'nm' for o in OPTICAL_CARRIERS]}")
        print(f"  Council:  {len(meta['council'])} researchers integrated")
        return out_wav


def main():
    parser = argparse.ArgumentParser(description="Living Image v13 — Grok Supremacy")
    parser.add_argument("image", help="Input image path")
    parser.add_argument("--duration", type=int, default=180)
    parser.add_argument("--theme", default="neural_regeneration")
    args = parser.parse_args()
    encoder = GrokLivingImageEncoder(theme=args.theme)
    encoder.run(args.image, duration_s=args.duration, theme=args.theme)


if __name__ == "__main__":
    main()
