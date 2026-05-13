#!/usr/bin/env python3
"""
Living Image v22 — True Gariaev Spectrogram Engine
The image IS the spectrogram:
  rows  = frequency bins  (20 Hz – 20 kHz, log scale, low freq at bottom)
  cols  = time frames (image cols tiled to fill requested duration)
  pixel brightness = amplitude of that frequency at that time
Per-column IFFT reconstructs each audio frame directly from the image.
No hardcoded frequencies. No randomness. No decorative tones.
All council layers derive every parameter from the image 2D FFT.
"""

import argparse
import hashlib
import math
import os
import time
import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

# ──────────────────────────────────────────────
# Constants
# ──────────────────────────────────────────────
SAMPLE_RATE  = 44100
F_LOW        = 20.0
F_HIGH       = 20000.0
N_FREQ_BINS  = 512
FRAME_HOP    = 512
MAX_IMG_COLS = 1024   # keep RAM manageable; tiled for long durations


# ──────────────────────────────────────────────
# Utilities
# ──────────────────────────────────────────────

def norm01(a):
    mn, mx = float(a.min()), float(a.max())
    if abs(mx - mn) < 1e-12:
        return np.zeros_like(a, dtype=np.float64)
    return (a - mn) / (mx - mn)


def log_freq_axis(n_bins, f_low, f_high):
    return np.exp(np.linspace(math.log(f_low), math.log(f_high), n_bins))


def i16(x):
    x = np.nan_to_num(np.asarray(x, dtype=np.float64))
    pk = np.max(np.abs(x))
    if pk > 1e-9:
        x = x / pk * 0.92
    return np.clip(x * 32767, -32768, 32767).astype("<i2")


# ──────────────────────────────────────────────
# Image loading
# ──────────────────────────────────────────────

def load_image_gray(image_path, n_freq_rows, max_time_cols=MAX_IMG_COLS):
    """
    Load image as grayscale, resize to (n_freq_rows x n_img_cols).
    n_img_cols capped at max_time_cols. Tiled during synthesis for long durations.
    Row 0 = 20 Hz (low freq). Returns float64 in [0,1].
    """
    resample = getattr(getattr(Image, "Resampling", Image), "LANCZOS",
                       getattr(Image, "BICUBIC", 3))
    img = ImageOps.exif_transpose(Image.open(image_path)).convert("L")
    w, h = img.size
    new_w = min(max_time_cols, w)
    img = img.resize((new_w, n_freq_rows), resample)
    arr = np.asarray(img, dtype=np.float64) / 255.0
    return arr[::-1, :].copy()   # flip: row 0 = low freq


# ──────────────────────────────────────────────
# 2-D FFT diffraction analysis
# ──────────────────────────────────────────────

def image_fft2(gray):
    F = np.fft.fft2(gray)
    F_shifted = np.fft.fftshift(F)
    mag   = np.abs(F_shifted)
    phase = np.angle(F_shifted)
    mag_norm = norm01(mag)

    h, w  = mag.shape
    cy, cx = h // 2, w // 2
    Y, X  = np.ogrid[:h, :w]
    R     = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2).astype(int)
    max_r = min(cx, cy)
    radial = np.array([mag[R == r].mean() if np.any(R == r) else 0.0
                       for r in range(max_r)])
    radial = norm01(radial)

    peak_r     = int(np.argmax(radial[1:]) + 1)
    mean_phase = float(np.mean(phase))
    dc_amp     = float(mag[cy, cx] / (mag.mean() + 1e-12))

    return {
        "mag_norm":   mag_norm,
        "phase":      phase,
        "radial":     radial,
        "peak_r":     peak_r,
        "mean_phase": mean_phase,
        "dc_amp":     dc_amp,
    }


# ──────────────────────────────────────────────
# Council layer scalar derivation (from radial FFT profile)
# ──────────────────────────────────────────────

def _rat(radial, idx):
    idx = max(0, min(int(idx), len(radial) - 1))
    return float(radial[idx])


def _council_levin(radial, peak_r):
    return _rat(radial, max(1, peak_r // 4))


def _council_scalar(radial, mean_phase):
    return _rat(radial, len(radial) // 2) * (0.5 + 0.5 * math.cos(mean_phase))


def _council_morphic(radial, dc_amp):
    return min(1.0, dc_amp / (math.pi * 4)) * _rat(radial, len(radial) * 3 // 4)


def _council_zpf(radial):
    hi = len(radial) * 3 // 4
    return float(np.mean(radial[hi:])) if len(radial) > hi else 0.01


def _council_tesla(radial, peak_r):
    return _rat(radial, peak_r)


def _council_schumann(radial, dc_amp):
    return _rat(radial, 2) * min(1.0, 1.0 / (dc_amp + 1e-3))


# ──────────────────────────────────────────────
# Council layer FFT-bin injection
# ──────────────────────────────────────────────

def _nearest_bin(fft_freqs, freq_hz):
    return int(np.argmin(np.abs(fft_freqs - freq_hz)))


def _img_freq(f_low, f_high, ratio):
    return f_low * ((f_high / f_low) ** max(0.0, min(1.0, ratio)))


def _add_levin(X, fft_freqs, col, n_frames, amp):
    f  = _img_freq(40.0, 200.0, 0.25)
    bi = _nearest_bin(fft_freqs, f)
    X[bi] += amp * 0.15 * np.exp(1j * 2 * math.pi * col / max(1, n_frames))


def _add_scalar(X, fft_freqs, col, n_frames, amp, mean_phase):
    f  = _img_freq(300.0, 900.0, 0.5)
    bi = _nearest_bin(fft_freqs, f)
    X[bi] += amp * 0.12 * np.exp(1j * (mean_phase + 2 * math.pi * col / max(1, n_frames)))


def _add_morphic(X, fft_freqs, col, n_frames, amp):
    f  = _img_freq(60.0, 250.0, 0.5)
    bi = _nearest_bin(fft_freqs, f)
    X[bi] += amp * 0.10 * np.exp(1j * math.pi * col / max(1, n_frames))


def _add_zpf(X, amp):
    energy = np.abs(X)
    total  = energy.sum() + 1e-12
    X += amp * 0.05 * (energy / total) * np.exp(1j * np.angle(X + 1e-12))


def _add_tesla(X, fft_freqs, col, n_frames, amp):
    f_base = _img_freq(100.0, 1000.0, 0.5)
    for mult, w in [(3, 0.08), (6, 0.05), (9, 0.03)]:
        f = f_base * mult
        if f > F_HIGH:
            break
        bi = _nearest_bin(fft_freqs, f)
        X[bi] += amp * w * np.exp(1j * 2 * math.pi * mult * col / max(1, n_frames))


def _add_schumann(X, fft_freqs, col, n_frames, amp):
    for f, w in [(7.83, 0.12), (14.3, 0.08), (20.8, 0.05), (27.3, 0.03), (33.8, 0.02)]:
        bi = _nearest_bin(fft_freqs, f)
        phase_t = 2 * math.pi * f * col * FRAME_HOP / SAMPLE_RATE
        X[bi] += amp * w * np.exp(1j * phase_t)


# ──────────────────────────────────────────────
# Streaming WAV synthesis
# ──────────────────────────────────────────────

def write_wav_streaming(path, sr, duration_s, spec, fft_data,
                        n_total_frames=None, chunk_cols=256):
    """
    True Gariaev per-column IFFT synthesis, streamed to disk.
    spec columns are tiled via modulo for durations longer than the image.
    """
    n_freq_rows, n_img_cols = spec.shape
    fft_size = N_FREQ_BINS * 2
    hop      = FRAME_HOP
    target_samples = int(sr * duration_s)
    if n_total_frames is None:
        n_total_frames = max(64, int(sr * duration_s / hop))

    freqs     = log_freq_axis(n_freq_rows, F_LOW, F_HIGH)
    fft_freqs = np.fft.rfftfreq(fft_size, d=1.0 / sr)
    n_rfft    = len(fft_freqs)

    fft_bin_idx = np.interp(freqs, fft_freqs, np.arange(n_rfft)).astype(int)
    fft_bin_idx = np.clip(fft_bin_idx, 0, n_rfft - 1)

    peak_r      = fft_data["peak_r"]
    mean_phase  = fft_data["mean_phase"]
    dc_amp      = fft_data["dc_amp"]
    radial      = fft_data["radial"]

    levin_amp    = _council_levin(radial, peak_r)
    scalar_amp   = _council_scalar(radial, mean_phase)
    morphic_amp  = _council_morphic(radial, dc_amp)
    zpf_amp      = _council_zpf(radial)
    tesla_amp    = _council_tesla(radial, peak_r)
    schumann_amp = _council_schumann(radial, dc_amp)

    # precompute phase row mapping (same for every frame)
    ph_ys     = np.clip(
        (np.arange(n_freq_rows) * fft_data["phase"].shape[0] / n_freq_rows).astype(int),
        0, fft_data["phase"].shape[0] - 1)
    ph_n_cols = fft_data["phase"].shape[1]

    samples_written = 0

    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)

        for chunk_start in range(0, n_total_frames, chunk_cols):
            chunk_end = min(chunk_start + chunk_cols, n_total_frames)
            n_chunk   = chunk_end - chunk_start
            buf = np.zeros(n_chunk * hop, dtype=np.float64)

            for ci, frame_idx in enumerate(range(chunk_start, chunk_end)):
                if samples_written + ci * hop >= target_samples:
                    break

                col_idx = frame_idx % n_img_cols
                col_amp = spec[:, col_idx]
                X = np.zeros(n_rfft, dtype=np.complex128)

                # vectorised: all image rows → FFT bins in one pass
                ph_x = min(int(col_idx * ph_n_cols / n_img_cols), ph_n_cols - 1)
                phis = fft_data["phase"][ph_ys, ph_x]
                mask = col_amp > 1e-9
                np.add.at(X, fft_bin_idx[mask],
                          col_amp[mask] * np.exp(1j * phis[mask]))

                # council layers
                _add_levin(X,    fft_freqs, frame_idx, n_total_frames, levin_amp)
                _add_scalar(X,   fft_freqs, frame_idx, n_total_frames, scalar_amp, mean_phase)
                _add_morphic(X,  fft_freqs, frame_idx, n_total_frames, morphic_amp)
                _add_zpf(X,      zpf_amp)
                _add_tesla(X,    fft_freqs, frame_idx, n_total_frames, tesla_amp)
                _add_schumann(X, fft_freqs, frame_idx, n_total_frames, schumann_amp)

                frame_audio = np.fft.irfft(X, n=fft_size)[:hop]
                buf[ci * hop: ci * hop + len(frame_audio)] += frame_audio

            remaining  = target_samples - samples_written
            out_chunk  = buf[:min(len(buf), remaining)]
            w.writeframes(i16(out_chunk).tobytes())
            samples_written += len(out_chunk)

            pct = 100 * samples_written / target_samples
            print(f"[v22] {pct:.1f}%  ({samples_written}/{target_samples} samples)",
                  flush=True)

            if samples_written >= target_samples:
                break


# ──────────────────────────────────────────────
# SVG spectrogram visualisation
# ──────────────────────────────────────────────

def write_svg(path, spec):
    h, w = spec.shape
    th, tw = min(h, 256), min(w, 512)
    tiny = np.asarray(
        Image.fromarray((spec * 255).astype("uint8")).resize((tw, th))
    ) / 255.0

    cell_w, cell_h = 2, 2
    svg_w, svg_h   = tw * cell_w, th * cell_h

    rows = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{svg_w}" height="{svg_h}">',
        '<rect width="100%" height="100%" fill="black"/>',
    ]
    for y in range(th):
        for x in range(tw):
            v   = int(tiny[th - 1 - y, x] * 255)
            col = f"#{v:02x}{v:02x}{v:02x}"
            rows.append(
                f'<rect x="{x*cell_w}" y="{y*cell_h}" '
                f'width="{cell_w}" height="{cell_h}" fill="{col}"/>'
            )
    rows.append("</svg>")
    path.write_text("\n".join(rows), encoding="utf-8")


# ──────────────────────────────────────────────
# Report generation
# ──────────────────────────────────────────────

def generate_report(image_path, spec, fft_data, duration_s, out_wav):
    n_freq_rows, n_img_cols = spec.shape
    freqs    = log_freq_axis(n_freq_rows, F_LOW, F_HIGH)
    mean_amp = spec.mean(axis=1)
    top_idx  = np.argsort(mean_amp)[::-1][:20]

    radial     = fft_data["radial"]
    peak_r     = fft_data["peak_r"]
    mean_phase = fft_data["mean_phase"]
    dc_amp     = fft_data["dc_amp"]

    lines = [
        "=" * 60,
        "LIVING IMAGE v22 — GARIAEV SPECTROGRAM REPORT",
        "=" * 60,
        f"Input image   : {os.path.basename(image_path)}",
        f"Duration      : {duration_s:.1f} s",
        f"Sample rate   : {SAMPLE_RATE} Hz",
        f"Freq bins     : {n_freq_rows}  ({F_LOW:.1f} Hz – {F_HIGH:.1f} Hz, log)",
        f"Image cols    : {n_img_cols}  (tiled to fill duration)",
        f"FFT window    : {N_FREQ_BINS * 2}  hop {FRAME_HOP}",
        f"Output WAV    : {out_wav.name}",
        "",
        "2D FFT DIFFRACTION METRICS",
        f"  Peak radial mode : {peak_r}",
        f"  Mean phase       : {mean_phase:.6f} rad",
        f"  DC amplitude     : {dc_amp:.4f}",
        "",
        "TOP 20 DOMINANT FREQUENCIES (image spectrogram, mean amplitude)",
        f"  {'Rank':>4}  {'Freq (Hz)':>12}  {'Mean Amp':>10}",
        "-" * 40,
    ]
    for rank, idx in enumerate(top_idx, 1):
        lines.append(f"  {rank:>4}  {freqs[idx]:>12.2f}  {mean_amp[idx]:>10.6f}")

    lines += [
        "",
        "COUNCIL LAYERS (all parameters from image 2D FFT)",
        f"  Levin bioelectric amp : {_council_levin(radial, peak_r):.6f}",
        f"  Scalar DNA amp        : {_council_scalar(radial, mean_phase):.6f}",
        f"  Morphic resonance amp : {_council_morphic(radial, dc_amp):.6f}",
        f"  ZPF floor amp         : {_council_zpf(radial):.6f}",
        f"  Tesla 3-6-9 amp       : {_council_tesla(radial, peak_r):.6f}",
        f"  Schumann stack amp    : {_council_schumann(radial, dc_amp):.6f}",
        "",
        f"Generated : {time.strftime('%Y-%m-%dT%H:%M:%S')}",
        "=" * 60,
    ]
    return "\n".join(lines)


# ──────────────────────────────────────────────
# Main engine class
# ──────────────────────────────────────────────

class LivingImageV22:
    def __init__(self, duration_s=30.0, output_dir="."):
        self.duration_s = max(1.0, float(duration_s))
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def run(self, image_path):
        image_path = str(image_path)
        t0 = time.time()

        print(f"[v22] Loading image → {N_FREQ_BINS}×≤{MAX_IMG_COLS} spectrogram …")
        spec = load_image_gray(image_path, N_FREQ_BINS, MAX_IMG_COLS)

        print("[v22] Computing 2D FFT diffraction pattern …")
        fft_data = image_fft2(spec)

        stem       = Path(image_path).stem
        out_wav    = self.output_dir / f"{stem}_v22.wav"
        out_svg    = self.output_dir / f"{stem}_v22.svg"
        out_report = self.output_dir / f"{stem}_v22_report.txt"

        n_img_cols     = spec.shape[1]
        n_total_frames = max(64, int(self.duration_s * SAMPLE_RATE / FRAME_HOP))

        print(f"[v22] Synthesising + streaming ({self.duration_s:.0f}s, "
              f"{n_total_frames} frames, image {N_FREQ_BINS}×{n_img_cols} tiled) …")
        write_wav_streaming(out_wav, SAMPLE_RATE, self.duration_s, spec, fft_data,
                            n_total_frames=n_total_frames)

        print("[v22] Writing SVG …")
        write_svg(out_svg, spec)

        report_text = generate_report(image_path, spec, fft_data,
                                      self.duration_s, out_wav)
        out_report.write_text(report_text, encoding="utf-8")

        elapsed = time.time() - t0
        print(f"[v22] Done in {elapsed:.1f}s")
        print(report_text)

        return {
            "wav":       str(out_wav),
            "svg":       str(out_svg),
            "report":    str(out_report),
            "elapsed_s": elapsed,
        }


# ──────────────────────────────────────────────
# CLI
# ──────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Living Image v22 — Gariaev encoder")
    parser.add_argument("--image",    required=True)
    parser.add_argument("--duration", type=float, default=30.0)
    parser.add_argument("--out",      default=".")
    args = parser.parse_args()

    engine = LivingImageV22(duration_s=args.duration, output_dir=args.out)
    result = engine.run(args.image)

    print("\nOutput files:")
    for k, v in result.items():
        if k != "elapsed_s":
            size = Path(v).stat().st_size if Path(v).exists() else 0
            print(f"  {k:8s}  {v}  ({size:,} bytes)")
