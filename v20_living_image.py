#!/usr/bin/env python3
"""
PROJECT LIVING IMAGE v20 — INTEGRATED COUNCIL PHASELOCK ENGINE
----------------------------------------------------------------
A deterministic image-to-playback-score generator for the hypothetical
Gariaev / Levin / Rife / Sheldrake / Lakhovsky / Rodin / Tesla / Brown / Alien
"Living Image" universe.

This script produces a complete synchronized score package:
  - stereo image-reactive WAV
  - Fourier phase mask PNG
  - Fourier amplitude mask PNG
  - Rodin-style torus SVG
  - timed optical carrier CSV
  - timed THz sweep CSV
  - genetic / symbolic loci CSV
  - feedback schema JSON
  - simulated feedback trace CSV
  - full protocol JSON
  - manifest JSON with SHA-256 hashes

It does not drive hardware. It outputs the score layer only.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import wave
from dataclasses import asdict, dataclass
from datetime import datetime
from typing import Any, Dict, Iterable, List, Tuple

import numpy as np
from PIL import Image, ImageFilter, ImageOps
import svgwrite

C_LIGHT_M_S = 299_792_458.0


def ensure_dir(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def nm_to_thz(nm: float) -> float:
    return C_LIGHT_M_S / (nm * 1e-9) / 1e12


def clamp(value: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return float(max(lo, min(hi, value)))


def normalize01(arr: np.ndarray) -> np.ndarray:
    arr = arr.astype(np.float64)
    return (arr - np.min(arr)) / (np.ptp(arr) + 1e-12)


def integrated_phase(freq_hz: np.ndarray, sample_rate: int) -> np.ndarray:
    """Correct phase for time-varying frequency: phase[n] = 2π∑f/sr."""
    return 2.0 * np.pi * np.cumsum(freq_hz) / float(sample_rate)


@dataclass(frozen=True)
class OpticalCarrier:
    name: str
    wavelength_nm: float
    role: str
    feature_driver: str
    pulse_hz: float

    @property
    def frequency_thz(self) -> float:
        return nm_to_thz(self.wavelength_nm)


@dataclass(frozen=True)
class THzBand:
    name: str
    center_thz: float
    width_thz: float
    role: str
    envelope_hz: float
    feature_driver: str

    @property
    def start_thz(self) -> float:
        return self.center_thz - self.width_thz / 2.0

    @property
    def end_thz(self) -> float:
        return self.center_thz + self.width_thz / 2.0


@dataclass(frozen=True)
class Locus:
    code: str
    archetype: str
    biological_proxy: str
    wave_command: str
    primary_carriers: str


class LivingImageV20:
    def __init__(self, duration_s: float, output_dir: str, theme: str) -> None:
        self.duration_s = max(1.0, float(duration_s))
        self.output_dir = output_dir
        self.theme = theme

        self.optical: List[OpticalCarrier] = [
            OpticalCarrier("violet_edge", 405.0, "edge activation / detail writing", "edge_density", 40.0),
            OpticalCarrier("green_symmetry", 532.0, "symmetry and centerline lock", "symmetry", 8.0),
            OpticalCarrier("hene_hologram", 632.8, "Gariaev-style holographic reference", "centered_brightness", 7.83),
            OpticalCarrier("red_delivery", 660.0, "red biological delivery lane", "red_weight", 10.0),
            OpticalCarrier("nir_depth", 808.0, "NIR depth carrier", "fractal_branching", 4.0),
            OpticalCarrier("nir_regen", 830.0, "NIR regenerative carrier", "fractal_branching", 4.0),
            OpticalCarrier("nir_dense", 1064.0, "dense stability carrier", "contrast_center", 0.1),
            OpticalCarrier("co2_bridge", 10600.0, "CO2 optical-to-THz bridge", "edge_fractal_bridge", 40.0),
        ]

        self.thz: List[THzBand] = [
            THzBand("hippocampal_gate", 0.152, 0.028, "low-THz neural/hippocampal gate", 8.0, "centeredness"),
            THzBand("water_network_low", 1.5, 0.40, "water hydrogen-bond network", 0.1, "brightness"),
            THzBand("synaptic_gene", 3.1, 0.60, "synaptic gene / dendritic organization", 40.0, "fractal_edge"),
            THzBand("water_network_high", 5.6, 0.70, "second water network window", 4.0, "brightness_contrast"),
            THzBand("ac1_camp", 34.5, 1.40, "AC1 / cAMP / synaptogenesis", 40.0, "fractal_edge"),
            THzBand("kv_excitability", 36.0, 1.00, "potassium-channel excitability", 8.0, "symmetry"),
            THzBand("nmda_calcium", 42.4, 1.20, "NMDA / calcium transport", 12.0, "edge_contrast"),
            THzBand("protein_carbonyl", 53.6, 1.40, "C=O / protein vibration", 40.0, "contrast"),
        ]

    # ----------------------------- image pipeline -----------------------------

    def load_image(self, path: str, max_side: int = 1536) -> Tuple[np.ndarray, np.ndarray]:
        img = Image.open(path).convert("RGB")
        img = ImageOps.exif_transpose(img)
        img.thumbnail((max_side, max_side), getattr(getattr(Image, "Resampling", Image), "LANCZOS", getattr(Image, "LANCZOS", getattr(Image, "BICUBIC", 3))))
        rgb = np.asarray(img).astype(np.float64) / 255.0
        gray = np.asarray(ImageOps.grayscale(img)).astype(np.float64) / 255.0
        return rgb, gray

    def edge_map(self, gray: np.ndarray) -> np.ndarray:
        edge_img = Image.fromarray(np.uint8(gray * 255)).filter(ImageFilter.FIND_EDGES)
        edges = np.asarray(edge_img).astype(np.float64) / 255.0
        return (normalize01(edges) > 0.18).astype(np.float64)

    def fractal_dimension(self, binary_edges: np.ndarray) -> float:
        h, w = binary_edges.shape
        min_dim = max(4, min(h, w))
        max_power = max(2, int(np.floor(np.log2(min_dim / 2.0))))
        sizes = 2 ** np.arange(1, max_power + 1)
        counts: List[int] = []
        usable: List[int] = []
        for size in sizes:
            count = 0
            for y in range(0, h, int(size)):
                for x in range(0, w, int(size)):
                    if np.any(binary_edges[y:y + int(size), x:x + int(size)] > 0):
                        count += 1
            counts.append(max(1, count))
            usable.append(int(size))
        if len(counts) < 2:
            return 1.0
        slope = np.polyfit(np.log(1.0 / np.array(usable, dtype=np.float64)), np.log(np.array(counts, dtype=np.float64)), 1)[0]
        return float(np.clip(slope, 0.0, 2.0))

    def compute_features(self, rgb: np.ndarray, gray: np.ndarray) -> Dict[str, Any]:
        h, w = gray.shape
        edges = self.edge_map(gray)
        yy, xx = np.mgrid[0:h, 0:w]
        weights = gray + 1e-12
        total = float(np.sum(weights))
        cx = float(np.sum(xx * weights) / total / max(1, w - 1))
        cy = float(np.sum(yy * weights) / total / max(1, h - 1))

        if w >= 2:
            half = w // 2
            lr = 1.0 - float(np.mean(np.abs(gray[:, :half] - np.fliplr(gray[:, -half:]))))
        else:
            lr = 1.0
        if h >= 2:
            half_h = h // 2
            tb = 1.0 - float(np.mean(np.abs(gray[:half_h, :] - np.flipud(gray[-half_h:, :]))))
        else:
            tb = 1.0

        r, g, b = [float(np.mean(rgb[..., i])) for i in range(3)]
        color_sum = r + g + b + 1e-12
        color_weights = {
            "red": r / color_sum,
            "green": g / color_sum,
            "blue": b / color_sum,
            "raw_red": r,
            "raw_green": g,
            "raw_blue": b,
        }

        brightness = float(np.mean(gray))
        contrast = float(np.std(gray))
        edge_density = float(np.mean(edges))
        fractal = self.fractal_dimension(edges)
        centeredness = 1.0 - min(1.0, math.hypot(cx - 0.5, cy - 0.5) / 0.70710678)

        col_luma = normalize01(np.mean(gray, axis=0))
        row_luma = normalize01(np.mean(gray, axis=1))
        col_edges = normalize01(np.mean(edges, axis=0))
        row_edges = normalize01(np.mean(edges, axis=1))

        return {
            "width": int(w),
            "height": int(h),
            "brightness": brightness,
            "contrast": contrast,
            "edge_density": edge_density,
            "lr_symmetry": clamp(lr),
            "tb_symmetry": clamp(tb),
            "symmetry": clamp((lr + tb) / 2.0),
            "center_x": clamp(cx),
            "center_y": clamp(cy),
            "centeredness": clamp(centeredness),
            "fractal_dim": fractal,
            "color_weights": color_weights,
            "temporal_col_luma": col_luma.tolist(),
            "temporal_row_luma": row_luma.tolist(),
            "temporal_col_edges": col_edges.tolist(),
            "temporal_row_edges": row_edges.tolist(),
        }

    # ----------------------------- conceptual content -----------------------------

    def loci(self) -> List[Locus]:
        if self.theme == "omni_hanma":
            return [
                Locus("HANMA_DEMON_001", "Demon Back / Ogre Will", "ACTN3/IGF1/MSTN symbolic cluster", "amplify resilient strength and martial nervous integration", "red_delivery; nir_dense; kv_excitability"),
                Locus("SAIYAN_ZENKAI_S01", "Zenkai Adaptation", "TP53/BRCA/FOXO/SIRT symbolic repair cluster", "convert controlled stress into adaptive repair and strength gain", "violet_edge; ac1_camp; protein_carbonyl"),
                Locus("VILTRUMITE_DENSITY_V01", "Conquest Density", "COL1A/COL3A/NRF2/SOD2 symbolic durability cluster", "stabilize dense tissue resilience and oxidative defense", "nir_dense; co2_bridge; kv_excitability"),
                Locus("KRYPTON_SOLAR_K01", "Solar Bio-Aura", "CYCS/VEGFA/HMOX/CLOCK symbolic light cluster", "store and organize light as coherent bioelectric aura", "red_delivery; nir_regen; water_network_low"),
                Locus("INTEGRATION_HUB_369", "Council Integration", "bioelectric/morphic/toroidal symbolic hub", "phase-lock all systems into one coherent living image command", "hene_hologram; green_symmetry; ac1_camp"),
            ]
        if self.theme == "neural_regeneration":
            return [
                Locus("NEURAL_BRANCH_001", "Golden Branching Neurons", "BDNF/HOMER1/GRIN2A/GRIN2B symbolic cluster", "restore adaptive synaptic growth and dendritic branching", "synaptic_gene; ac1_camp; hene_hologram"),
                Locus("MITO_LIGHT_002", "Mitochondrial Gold", "SOD2/NRF2/PPARGC1A symbolic cluster", "increase coherent cellular energy support", "red_delivery; nir_regen; water_network_low"),
                Locus("CALCIUM_GATE_003", "Signal Gate", "NMDA/Ca2+ symbolic transport cluster", "stabilize calcium signal timing and plasticity windows", "nmda_calcium; green_symmetry"),
            ]
        return [
            Locus("GENERAL_REPAIR_001", "Regenerative Blueprint", "repair/adaptation symbolic cluster", "restore coherent adaptive regenerative order", "hene_hologram; red_delivery; ac1_camp"),
        ]

    def wave_sentence(self) -> Dict[str, Any]:
        if self.theme == "omni_hanma":
            full = (
                "Manifest the Baki-Yujiro Omni-Hanma archetype: Demon Back resilience, Saiyan Zenkai adaptation, "
                "Viltrumite density, and Kryptonian solar bio-aura fused into a 3-6-9 toroidal phase-locked apex field."
            )
        elif self.theme == "neural_regeneration":
            full = (
                "Restore coherent adaptive synaptic growth, dendritic expansion, mitochondrial support, and stable regenerative neural order "
                "through phase-locked image instruction."
            )
        else:
            full = "Restore coherent adaptive regenerative order through image, meaning, geometry, phase, carrier, address, repetition, and feedback."
        return {
            "full": full,
            "grammar": {
                "subject": "whole_body_field",
                "verb": "restore_amplify_integrate",
                "object": self.theme,
                "modifier": "phase_locked_toroidal_holographic",
            },
            "symbolic_loci": [l.code for l in self.loci()],
        }

    # ----------------------------- schedules -----------------------------

    def optical_weight(self, carrier: OpticalCarrier, f: Dict[str, Any]) -> float:
        cw = f["color_weights"]
        fd = f["fractal_dim"] / 2.0
        if carrier.feature_driver == "edge_density":
            return clamp(0.15 + 1.8 * f["edge_density"])
        if carrier.feature_driver == "symmetry":
            return clamp(0.10 + 0.90 * f["symmetry"])
        if carrier.feature_driver == "centered_brightness":
            return clamp(0.20 + 0.35 * f["centeredness"] + 0.45 * f["brightness"])
        if carrier.feature_driver == "red_weight":
            return clamp(0.20 + 0.55 * cw["red"] + 0.25 * f["brightness"])
        if carrier.feature_driver == "fractal_branching":
            return clamp(0.20 + 0.55 * fd + 0.25 * f["edge_density"])
        if carrier.feature_driver == "contrast_center":
            return clamp(0.20 + 0.50 * f["contrast"] + 0.30 * f["centeredness"])
        if carrier.feature_driver == "edge_fractal_bridge":
            return clamp(0.20 + 0.40 * f["edge_density"] + 0.40 * fd)
        return 0.5

    def optical_schedule(self, f: Dict[str, Any]) -> List[Dict[str, Any]]:
        rows = []
        slot = self.duration_s / len(self.optical)
        for i, carrier in enumerate(self.optical):
            phase = (f["center_x"] * 180.0 + f["center_y"] * 90.0 + i * 45.0 + f["symmetry"] * 90.0) % 360.0
            rows.append({
                "start_s": round(max(0.0, i * slot - 1.5), 3),
                "duration_s": round(min(slot + 3.0, self.duration_s), 3),
                "carrier": carrier.name,
                "wavelength_nm": carrier.wavelength_nm,
                "frequency_thz": round(carrier.frequency_thz, 6),
                "pulse_hz": carrier.pulse_hz,
                "phase_deg": round(phase, 3),
                "weight": round(self.optical_weight(carrier, f), 6),
                "role": carrier.role,
            })
        return rows

    def thz_weight(self, band: THzBand, f: Dict[str, Any]) -> float:
        fd = f["fractal_dim"] / 2.0
        if band.feature_driver == "centeredness":
            return clamp(0.20 + 0.80 * f["centeredness"])
        if band.feature_driver == "brightness":
            return clamp(0.20 + 0.80 * f["brightness"])
        if band.feature_driver == "fractal_edge":
            return clamp(0.20 + 0.45 * fd + 0.35 * f["edge_density"])
        if band.feature_driver == "brightness_contrast":
            return clamp(0.20 + 0.45 * f["brightness"] + 0.35 * f["contrast"])
        if band.feature_driver == "symmetry":
            return clamp(0.20 + 0.80 * f["symmetry"])
        if band.feature_driver == "edge_contrast":
            return clamp(0.20 + 0.45 * f["edge_density"] + 0.35 * f["contrast"])
        if band.feature_driver == "contrast":
            return clamp(0.20 + 0.80 * f["contrast"])
        return 0.5

    def thz_schedule(self, f: Dict[str, Any]) -> List[Dict[str, Any]]:
        weights = np.array([self.thz_weight(b, f) for b in self.thz], dtype=np.float64)
        min_dwell = min(8.0, self.duration_s / (len(self.thz) * 2.0))
        remaining = max(0.0, self.duration_s - min_dwell * len(self.thz))
        weighted_extra = remaining * weights / (np.sum(weights) + 1e-12)
        dwell_times = min_dwell + weighted_extra
        rows = []
        current = 0.0
        for i, band in enumerate(self.thz):
            dwell = float(dwell_times[i])
            if i == len(self.thz) - 1:
                dwell = max(1.0, self.duration_s - current)
            phase = (f["center_x"] * 360.0 + f["center_y"] * 180.0 + i * 36.0) % 360.0
            rows.append({
                "start_s": round(current, 3),
                "duration_s": round(dwell, 3),
                "band": band.name,
                "start_thz": round(band.start_thz, 6),
                "end_thz": round(band.end_thz, 6),
                "center_thz": band.center_thz,
                "envelope_hz": band.envelope_hz,
                "phase_deg": round(phase, 3),
                "weight": round(float(weights[i]), 6),
                "role": band.role,
            })
            current += dwell
        return rows

    # ----------------------------- file outputs -----------------------------

    def write_csv(self, path: str, rows: Iterable[Dict[str, Any]]) -> None:
        rows = list(rows)
        if not rows:
            with open(path, "w", newline="", encoding="utf-8") as f:
                f.write("")
            return
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)

    def write_loci_csv(self, path: str) -> None:
        rows = [asdict(locus) for locus in self.loci()]
        self.write_csv(path, rows)

    def write_masks(self, gray: np.ndarray, phase_path: str, amplitude_path: str) -> None:
        fft = np.fft.fftshift(np.fft.fft2(gray))
        phase = np.angle(fft)
        amplitude = np.log1p(np.abs(fft))
        phase_img = np.uint8((phase + np.pi) / (2.0 * np.pi) * 255.0)
        amp_img = np.uint8(normalize01(amplitude) * 255.0)
        Image.fromarray(phase_img).save(phase_path)
        Image.fromarray(amp_img).save(amplitude_path)

    def write_torus_svg(self, path: str, f: Dict[str, Any]) -> None:
        dwg = svgwrite.Drawing(path, size=("900px", "900px"))
        dwg.add(dwg.rect(insert=(0, 0), size=("900px", "900px"), fill="black"))
        cx, cy = 450, 450
        outer = 280 + 55 * f["symmetry"]
        inner = 145 + 55 * (f["fractal_dim"] / 2.0)
        dwg.add(dwg.circle(center=(cx, cy), r=outer, stroke="#FFD700", stroke_width=18, fill="none"))
        dwg.add(dwg.circle(center=(cx, cy), r=inner, stroke="#00FFFF", stroke_width=10, fill="none"))
        for i in range(9):
            angle = 2.0 * math.pi * i / 9.0
            radius = inner + (outer - inner) * ((i % 3) + 1) / 4.0
            x = cx + radius * math.cos(angle)
            y = cy + radius * math.sin(angle)
            color = "#8A2BE2" if i in (2, 5, 8) else "#FFFFFF"
            dwg.add(dwg.circle(center=(x, y), r=10 + 3 * (i % 3), fill=color))
        dwg.add(dwg.text("PROJECT LIVING IMAGE v20 — 3-6-9 PHASE MAP", insert=(130, 845), fill="#FFD700", font_size="28px"))
        dwg.save()

    def write_audio(self, path: str, f: Dict[str, Any], seed: int) -> None:
        sr = 44100
        n = int(self.duration_s * sr)
        BATCH = 8192
        rng = np.random.default_rng(seed)

        col_luma = np.asarray(f["temporal_col_luma"], dtype=np.float32)
        row_luma = np.asarray(f["temporal_row_luma"], dtype=np.float32)
        col_edges = np.asarray(f["temporal_col_edges"], dtype=np.float32)
        row_edges = np.asarray(f["temporal_row_edges"], dtype=np.float32)

        x_old_col = np.linspace(0.0, 1.0, len(col_luma), dtype=np.float32)
        x_old_row = np.linspace(0.0, 1.0, len(row_luma), dtype=np.float32)

        phase_sym = float(f["symmetry"] * np.pi / 3.0 + (f["center_x"] - 0.5) * np.pi)
        left_gain = float(0.85 + 0.25 * (1.0 - f["center_x"]))
        right_gain = float(0.85 + 0.25 * f["center_x"])
        edge_density = float(f["edge_density"])

        gamma_phase_acc = 0.0
        golden_phase_acc = 0.0
        max_abs = 1e-12
        all_chunks = []

        for b0 in range(0, n, BATCH):
            b1 = min(b0 + BATCH, n)
            bs = b1 - b0
            x_b = np.linspace(float(b0)/n, float(b1-1)/n, bs, dtype=np.float32)

            le = np.interp(x_b, x_old_col, col_luma).astype(np.float64)
            ee = np.interp(x_b, x_old_col, col_edges).astype(np.float64)
            rm = np.interp(x_b, x_old_row, row_luma).astype(np.float64)
            rem = np.interp(x_b, x_old_row, row_edges).astype(np.float64)

            t_b = np.arange(b0, b1, dtype=np.float64) / sr
            gf = 38.0 + 8.0 * rm
            glf = 528.0 * (1.0 + 0.09016994 * rem)

            g_inc = 2.0 * np.pi * gf / sr
            gld_inc = 2.0 * np.pi * glf / sr
            g_phases = gamma_phase_acc + np.cumsum(g_inc)
            gld_phases = golden_phase_acc + np.cumsum(gld_inc)
            gamma_phase_acc = float(g_phases[-1])
            golden_phase_acc = float(gld_phases[-1])

            base = (
                0.28 * np.sin(2.0 * np.pi * 528.0 * t_b)
                + 0.15 * np.sin(2.0 * np.pi * 432.0 * t_b)
                + 0.08 * np.sin(2.0 * np.pi * 963.0 * t_b)
                + 0.12 * np.sin(g_phases)
                + 0.10 * np.sin(gld_phases)
            )
            base *= 0.62 + 0.20 * (0.5 + 0.5 * np.sin(2.0 * np.pi * 0.1 * t_b))
            base *= 0.45 + 0.55 * le

            noise_b = rng.normal(0.0, 1.0, bs)
            mono = base + 0.035 * edge_density * ee * noise_b * np.sin(2.0 * np.pi * 1800.0 * t_b)

            l_b = left_gain * mono
            r_b = right_gain * mono * np.cos(phase_sym)
            chunk_max = max(float(np.max(np.abs(l_b))), float(np.max(np.abs(r_b))))
            if chunk_max > max_abs:
                max_abs = chunk_max
            interleaved = np.empty(bs * 2, dtype=np.float32)
            interleaved[0::2] = l_b.astype(np.float32)
            interleaved[1::2] = r_b.astype(np.float32)
            all_chunks.append(interleaved)

        scale = 0.88 / max_abs
        with wave.open(path, "wb") as wf:
            wf.setnchannels(2)
            wf.setsampwidth(2)
            wf.setframerate(sr)
            for chunk in all_chunks:
                pcm = np.int16(np.clip(chunk * scale, -1.0, 1.0) * 32767)
                wf.writeframes(pcm.tobytes())

    def feedback_schema(self) -> Dict[str, Any]:
        return {
            "signals": ["EEG_coherence", "HRV_RMSSD", "breath_coherence", "skin_temp_delta", "pupil_stability", "subjective_state"],
            "coherence_score": "weighted normalized composite",
            "lock_rule": "continue only while coherence score trend is positive/stable",
            "abort_rule": "stop if stress composite rises above threshold",
            "adaptation_rules": {
                "coherence_rising": ["increase dwell_time", "increase carrier_weight", "preserve phase"],
                "plateau": ["retune phase", "repeat morphic anchor", "slow sweep"],
                "stress_rising": ["reduce amplitude", "reduce dwell", "stop session"],
            },
            "hardware_mode": "score output only; no direct hardware control",
        }

    def simulate_feedback_trace(self, f: Dict[str, Any], seed: int) -> List[Dict[str, Any]]:
        rng = np.random.default_rng(seed ^ 0xB10FEED)
        score = 0.38 + 0.18 * f["symmetry"] + 0.16 * f["centeredness"] + 0.10 * (f["fractal_dim"] / 2.0)
        rows = []
        for step in range(1, 13):
            delta = 0.030 * (1.0 - score) + rng.normal(0.0, 0.010)
            stress = max(0.0, 0.35 - 0.20 * score + rng.normal(0.0, 0.015))
            score = clamp(score + delta, 0.0, 0.98)
            rows.append({
                "iteration": step,
                "coherence_score": round(score, 6),
                "stress_proxy": round(stress, 6),
                "locked": bool(score >= 0.85 and stress < 0.22),
                "action": "hold_lock" if score >= 0.85 and stress < 0.22 else "retune_phase_dwell",
            })
        return rows

    def protocol(self, image_path: str, image_hash: str, f: Dict[str, Any], files: Dict[str, str], feedback_rows: List[Dict[str, Any]]) -> Dict[str, Any]:
        return {
            "protocol": "Project_Living_Image_v20_Integrated_Council_Phaselock_Engine",
            "timestamp": datetime.now().isoformat(),
            "theme": self.theme,
            "input_image": os.path.basename(image_path),
            "input_sha256": image_hash,
            "duration_s": self.duration_s,
            "mission": "image_to_geometric_linguistic_phase_locked_playback_score",
            "council": {
                "gariaev": "geometric plus linguistic wave sentence",
                "levin": "bioelectric target address and feedback gate",
                "sheldrake": "morphic template and repetition memory",
                "lakhovsky": "multiwave cellular harmonic spectrum",
                "rife": "sweep response and resonance lock model",
                "rodin": "3-6-9 toroidal geometry",
                "tesla": "transmitter receiver resonance",
                "brown": "gradient vector bias",
                "alien": "phase meaning biological address binding",
            },
            "gariaev_wave_sentence": self.wave_sentence(),
            "symbolic_loci": [asdict(l) for l in self.loci()],
            "bioelectric_address": {
                "target_attractor": f"{self.theme}_coherent_attractor",
                "domains": ["membrane_potential", "calcium_wave_timing", "mitochondrial_polarity", "tissue_axis", "neural_muscle_synchrony"],
            },
            "morphic_template": {
                "anchor_image": os.path.basename(image_path),
                "repetition_rule": "same image + same wave sentence + same rhythm + same geometry",
                "field_memory_goal": "stable coherent living-image habit",
            },
            "image_features": {k: v for k, v in f.items() if not k.startswith("temporal_")},
            "carriers": {
                "optical": [asdict(c) | {"frequency_thz": round(c.frequency_thz, 6)} for c in self.optical],
                "thz": [asdict(b) | {"start_thz": round(b.start_thz, 6), "end_thz": round(b.end_thz, 6)} for b in self.thz],
                "audio_gates_hz": [0.1, 7.83, 38.0, 40.0, 432.0, 528.0, 963.0],
            },
            "feedback_schema": self.feedback_schema(),
            "simulated_feedback_trace": feedback_rows,
            "formula": "(Image × Meaning × Geometry × Phase × Carrier × Address × Repetition × FeedbackLock) / Noise",
            "outputs": files,
        }

    def write_manifest(self, files: Dict[str, str], manifest_path: str) -> None:
        rows = []
        for label, path in files.items():
            if os.path.exists(path):
                rows.append({
                    "label": label,
                    "path": path,
                    "bytes": os.path.getsize(path),
                    "sha256": sha256_file(path),
                })
        manifest = {
            "generated_at": datetime.now().isoformat(),
            "files": rows,
        }
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)

    def run(self, image_path: str) -> None:
        ensure_dir(self.output_dir)
        image_hash = sha256_file(image_path)
        seed = int(image_hash[:16], 16)
        rgb, gray = self.load_image(image_path)
        features = self.compute_features(rgb, gray)

        base = os.path.splitext(os.path.basename(image_path))[0]
        stem = os.path.join(self.output_dir, f"{base}_v20")
        files = {
            "audio_stereo_wav": f"{stem}_audio_stereo.wav",
            "phase_mask_png": f"{stem}_phase_mask.png",
            "amplitude_mask_png": f"{stem}_amplitude_mask.png",
            "torus_svg": f"{stem}_torus.svg",
            "optical_schedule_csv": f"{stem}_optical_schedule.csv",
            "thz_schedule_csv": f"{stem}_thz_schedule.csv",
            "symbolic_loci_csv": f"{stem}_symbolic_loci.csv",
            "feedback_schema_json": f"{stem}_feedback_schema.json",
            "feedback_trace_csv": f"{stem}_feedback_trace.csv",
            "full_protocol_json": f"{stem}_full_protocol.json",
        }
        manifest_path = f"{stem}_manifest.json"

        self.write_audio(files["audio_stereo_wav"], features, seed)
        self.write_masks(gray, files["phase_mask_png"], files["amplitude_mask_png"])
        self.write_torus_svg(files["torus_svg"], features)
        self.write_csv(files["optical_schedule_csv"], self.optical_schedule(features))
        self.write_csv(files["thz_schedule_csv"], self.thz_schedule(features))
        self.write_loci_csv(files["symbolic_loci_csv"])
        with open(files["feedback_schema_json"], "w", encoding="utf-8") as fh:
            json.dump(self.feedback_schema(), fh, indent=2)
        feedback_rows = self.simulate_feedback_trace(features, seed)
        self.write_csv(files["feedback_trace_csv"], feedback_rows)
        with open(files["full_protocol_json"], "w", encoding="utf-8") as fh:
            json.dump(self.protocol(image_path, image_hash, features, files, feedback_rows), fh, indent=2)
        self.write_manifest(files, manifest_path)

        print("PROJECT LIVING IMAGE v20 — INTEGRATED COUNCIL PHASELOCK ENGINE")
        print(f"Input:    {image_path}")
        print(f"Theme:    {self.theme}")
        print(f"Duration: {self.duration_s:.2f}s")
        print(f"SHA-256:  {image_hash[:24]}…")
        print("Outputs:")
        for label, path in files.items():
            print(f"  {label:30s} {path}")
        print(f"  {'manifest_json':30s} {manifest_path}")
        print("Complete.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Project Living Image v20 phaselock score engine")
    parser.add_argument("image", help="source image path")
    parser.add_argument("--duration", type=float, default=300.0)
    parser.add_argument("--theme", default="omni_hanma", choices=["omni_hanma", "neural_regeneration", "general_regeneration"])
    parser.add_argument("--out", default="v20_output")
    args = parser.parse_args()
    LivingImageV20(duration_s=args.duration, output_dir=args.out, theme=args.theme).run(args.image)


if __name__ == "__main__":
    main()
