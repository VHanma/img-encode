import json
import math
import wave
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


DEFAULT_SAMPLE_RATE = 48000
DEFAULT_DURATION_SEC = 72.0

BANDS = {
    "foundation": (300.0, 900.0),
    "bridge": (1700.0, 1900.0),
    "carrier": (2400.0, 2600.0),
    "overdrive": (3600.0, 3800.0),
}

DEFAULT_WEIGHTS = {
    "levin": 0.34,
    "gariaev": 0.36,
    "scalar": 0.18,
    "rife": 0.12,
}

HELIX_DEFAULTS = {
    "strand_stiffness": 1.0,
    "bond_spring_k": 0.65,
    "bond_damping_c": 0.28,
    "twist_rate": 1.15,
    "curvature": 0.72,
    "fluid_drag": 0.22,
    "thermal_bias": 0.05,
    "mass_light": 0.85,
    "mass_medium": 1.00,
    "mass_heavy": 1.20,
    "bend_mode": 0.40,
    "torsion_mode": 0.30,
    "coupling_mode": 0.20,
    "shear_mode": 0.10,
}

PHASE_CYCLE = np.array([0, 45, 90, 135, 180, 225, 270, 315], dtype=np.float64)


def clamp(x, lo, hi):
    return max(lo, min(hi, x))


def normalize(arr, eps=1e-12):
    arr = np.asarray(arr, dtype=np.float64)
    mn = np.min(arr)
    mx = np.max(arr)
    if mx - mn < eps:
        return np.zeros_like(arr)
    return (arr - mn) / (mx - mn)


def smooth1d(arr, win):
    arr = np.asarray(arr, dtype=np.float64)
    if win <= 1:
        return arr.copy()
    kernel = np.ones(win, dtype=np.float64) / win
    return np.convolve(arr, kernel, mode="same")


def file_sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def stereo_from_mono_and_phase(mono, phase_deg=0.0, side_amt=0.15, mod=None):
    mono = np.asarray(mono, dtype=np.float64)
    if mod is None:
        mod = np.ones_like(mono)
    phase = np.deg2rad(phase_deg)
    side = np.sin(np.linspace(0.0, 2.0 * np.pi * (0.7 + abs(math.sin(phase))), len(mono)))
    side = side * mod
    left = mono * (1.0 - side_amt) + side_amt * side
    right = mono * (1.0 - side_amt) - side_amt * side
    return np.stack([left, right], axis=1)


def hash_text_to_unit(text):
    h = hashlib.sha256(text.encode("utf-8")).hexdigest()
    return int(h[:8], 16) / 0xFFFFFFFF


def safe_image_open(path, size=(256, 256), mode="L"):
    return Image.open(path).convert(mode).resize(size)


def write_wav_stereo(path, stereo, sample_rate, master_norm=0.92):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    stereo = np.asarray(stereo, dtype=np.float64)
    peak = max(np.max(np.abs(stereo)), 1e-12)
    stereo = master_norm * stereo / peak
    pcm = (np.clip(stereo, -0.98, 0.98) * 32767.0).astype(np.int16)

    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(pcm.tobytes())


def extract_image_maps(image_path, size=(256, 256), grid=(8, 8)):
    img = safe_image_open(image_path, size=size, mode="L")
    arr = np.asarray(img, dtype=np.float64) / 255.0

    gx = np.zeros_like(arr)
    gy = np.zeros_like(arr)
    gx[:, 1:-1] = arr[:, 2:] - arr[:, :-2]
    gy[1:-1, :] = arr[2:, :] - arr[:-2, :]
    grad = np.sqrt(gx**2 + gy**2)
    grad_n = normalize(grad)

    flip = np.fliplr(arr)
    sym_local = 1.0 - normalize(np.abs(arr - flip))

    h, w = arr.shape
    gh, gw = grid
    bh = h // gh
    bw = w // gw
    features = []

    for r in range(gh):
        for c in range(gw):
            y0, y1 = r * bh, (r + 1) * bh
            x0, x1 = c * bw, (c + 1) * bw
            block = arr[y0:y1, x0:x1]
            eblock = grad_n[y0:y1, x0:x1]
            sblock = sym_local[y0:y1, x0:x1]
            features.append({
                "region": [r, c],
                "brightness": float(np.mean(block)),
                "contrast": float(np.std(block)),
                "edge_density": float(np.mean(eblock > 0.35)),
                "symmetry": float(np.mean(sblock)),
                "center_xy": [float((x0 + x1) / 2 / w), float((y0 + y1) / 2 / h)],
            })

    summary = {
        "brightness_mean": float(np.mean([f["brightness"] for f in features])),
        "contrast_mean": float(np.mean([f["contrast"] for f in features])),
        "edge_density_mean": float(np.mean([f["edge_density"] for f in features])),
        "symmetry_mean": float(np.mean([f["symmetry"] for f in features])),
        "region_count": len(features),
    }

    return {
        "image_array": arr,
        "edge_map": grad_n,
        "symmetry_map": sym_local,
        "features": features,
        "summary": summary,
    }


def spiral_order(features):
    def key_fn(f):
        x, y = f["center_xy"]
        dx = x - 0.5
        dy = y - 0.5
        r = math.hypot(dx, dy)
        ang = math.atan2(dy, dx)
        return (round(r, 4), ang)
    return sorted(features, key=key_fn)


def raster_order(features):
    return sorted(features, key=lambda f: (f["region"][0], f["region"][1]))


def render_text_token_map(tokens, size=(256, 256)):
    canvas = Image.new("L", size, color=0)
    draw = ImageDraw.Draw(canvas)
    w, h = size

    for i, token in enumerate(tokens):
        u = hash_text_to_unit(token)
        v = hash_text_to_unit(token[::-1] + f"_{i}")
        cx = int((0.15 + 0.7 * u) * w)
        cy = int((0.15 + 0.7 * v) * h)
        rr = int(12 + 40 * hash_text_to_unit(token + "_r"))
        intensity = int(100 + 155 * hash_text_to_unit(token + "_i"))
        draw.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=intensity)

        ang = 2 * np.pi * hash_text_to_unit(token + "_a")
        dx = int(rr * 1.8 * np.cos(ang))
        dy = int(rr * 1.8 * np.sin(ang))
        draw.line((cx, cy, cx + dx, cy + dy), fill=intensity, width=2)

    arr = np.asarray(canvas, dtype=np.float64) / 255.0
    return normalize(arr)


def build_levin_layer(t, image_maps, strength=1.0):
    summary = image_maps["summary"]
    b = summary["brightness_mean"]
    c = summary["contrast_mean"]
    e = summary["edge_density_mean"]
    s = summary["symmetry_mean"]

    env = (
        0.45
        + 0.22 * np.sin(2 * np.pi * (0.065 + 0.03 * b) * t)
        + 0.18 * np.sin(2 * np.pi * (0.11 + 0.05 * s) * t + np.pi / 3)
        + 0.10 * np.sin(2 * np.pi * (0.17 + 0.06 * e) * t + np.pi / 7)
    )
    env = normalize(env)
    env = 0.35 + 0.65 * env

    base = (
        0.55 * np.sin(2 * np.pi * (72 + 18 * b) * t)
        + 0.30 * np.sin(2 * np.pi * (144 + 40 * c) * t + 0.8)
        + 0.15 * np.sin(2 * np.pi * (288 + 50 * s) * t + 1.3)
    )

    mono = strength * env * base
    stereo = stereo_from_mono_and_phase(mono, phase_deg=180 * s, side_amt=0.08, mod=env)
    return stereo, env


def choose_band(brightness, contrast, edge_density, symmetry):
    scores = {
        "foundation": (1.0 - brightness) * 0.5 + symmetry * 0.3 + contrast * 0.2,
        "bridge": brightness * 0.5 + (1.0 - edge_density) * 0.3 + symmetry * 0.2,
        "carrier": contrast * 0.4 + brightness * 0.2 + edge_density * 0.2 + symmetry * 0.2,
        "overdrive": edge_density * 0.6 + contrast * 0.2 + (1.0 - symmetry) * 0.2,
    }
    return max(scores, key=scores.get)


def map_feature_to_freq_pair(band_name, brightness, contrast, edge_density, symmetry):
    lo, hi = BANDS[band_name]
    p1 = clamp(0.12 + 0.68 * brightness, 0.0, 1.0)
    p2 = clamp(0.12 + 0.68 * (0.5 * contrast + 0.5 * edge_density), 0.0, 1.0)
    f1 = lo + (hi - lo) * p1
    f2 = lo + (hi - lo) * p2
    if symmetry > 0.7:
        f1, f2 = f2, f1
    return f1, f2


def build_gariaev_layer(t, image_maps, control_env, sample_rate, strength=1.0, scan_mode="spiral"):
    features = image_maps["features"]
    ordered = spiral_order(features) if scan_mode == "spiral" else raster_order(features)

    mono_l = np.zeros_like(t)
    mono_r = np.zeros_like(t)

    total_dur = t[-1] if len(t) > 1 else 1.0
    region_span = total_dur / max(1, int(len(ordered) * 0.45))

    for i, f in enumerate(ordered):
        b = f["brightness"]
        c = f["contrast"]
        e = f["edge_density"]
        s = f["symmetry"]

        band = choose_band(b, c, e, s)
        f1, f2 = map_feature_to_freq_pair(band, b, c, e, s)

        start = (i * 0.33 * region_span) % max(total_dur, 1e-6)
        dur = 0.9 + 0.8 * clamp(0.4 * c + 0.3 * e + 0.3 * s, 0.0, 1.0)
        local = np.clip((t - start) / dur, 0.0, 1.0)

        gate = np.where((t >= start) & (t <= start + dur), 1.0, 0.0)
        gate *= np.sin(np.pi * local) ** 2

        sweep = f1 + (f2 - f1) * local
        phase_base = 2 * np.pi * np.cumsum(sweep) / sample_rate

        pulse_rate = 2.0 + 9.0 * e
        pulse = 0.55 + 0.45 * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * pulse_rate * t)))
        am = 0.75 + 0.25 * np.sin(2 * np.pi * (0.18 + 0.2 * b) * t + i * 0.17)

        replica2_delay = int(sample_rate * (0.012 + 0.02 * s))
        replica3_delay = int(sample_rate * (0.025 + 0.03 * c))

        sig = (
            0.62 * np.sin(phase_base + i * 0.11)
            + 0.23 * np.sin(2.0 * phase_base + i * 0.07)
            + 0.15 * np.sin(0.5 * phase_base + i * 0.19)
        )

        sig *= gate * pulse * am * (0.72 + 0.28 * control_env)

        sig2 = np.roll(sig, replica2_delay) * (0.42 + 0.18 * s)
        sig3 = np.roll(-sig, replica3_delay) * (0.25 + 0.10 * c)

        phase_deg = PHASE_CYCLE[i % len(PHASE_CYCLE)]
        phase_rad = np.deg2rad(phase_deg)

        mono_l += sig + np.cos(phase_rad) * sig2 + np.sin(phase_rad) * sig3
        mono_r += sig - np.cos(phase_rad) * sig2 - np.sin(phase_rad) * sig3

    stereo = np.stack([mono_l, mono_r], axis=1)
    stereo *= strength / max(np.max(np.abs(stereo)), 1e-12)
    return stereo


def build_scalar_layer(t, image_maps, tokens, symbol_image_path=None, strength=1.0):
    base_arr = image_maps["image_array"]
    if symbol_image_path:
        sym = safe_image_open(symbol_image_path, size=base_arr.shape[::-1], mode="L")
        sym_arr = np.asarray(sym, dtype=np.float64) / 255.0
        sym_arr = normalize(sym_arr)
    else:
        sym_arr = render_text_token_map(tokens, size=base_arr.shape[::-1])

    combined = normalize(0.45 * base_arr + 0.55 * sym_arr)
    mean_val = float(np.mean(combined))
    edge_bias = float(np.mean(image_maps["edge_map"]))
    sym_bias = float(np.mean(image_maps["symmetry_map"]))

    token_units = [hash_text_to_unit(tok) for tok in tokens] if tokens else [0.5]
    token_units = np.array(token_units, dtype=np.float64)

    token_freqs = 220 + 880 * token_units
    token_phases = 2 * np.pi * token_units

    mono = np.zeros_like(t)
    for i, (tf, ph) in enumerate(zip(token_freqs, token_phases)):
        env = 0.55 + 0.45 * np.sin(2 * np.pi * (0.09 + 0.03 * i) * t + ph)
        mono += (0.4 + 0.2 * mean_val) * env * np.sin(2 * np.pi * tf * t + ph)

    geom = (
        0.30 * np.sin(2 * np.pi * (600 + 400 * mean_val) * t)
        + 0.22 * np.sin(2 * np.pi * (1300 + 500 * edge_bias) * t + 0.7)
        + 0.16 * np.sin(2 * np.pi * (2100 + 700 * sym_bias) * t + 1.4)
    )

    mono = 0.68 * mono + 0.32 * geom
    mono *= 0.70 + 0.30 * np.sin(2 * np.pi * (0.14 + 0.08 * mean_val) * t)
    stereo = stereo_from_mono_and_phase(mono, phase_deg=90 + 180 * sym_bias, side_amt=0.18)
    stereo *= strength / max(np.max(np.abs(stereo)), 1e-12)
    return stereo


def build_rife_layer(t, image_maps, sample_rate, strength=1.0):
    scan_bands = ((700, 900), (1500, 1600), (2000, 2200), (4000, 5000))
    edge = image_maps["summary"]["edge_density_mean"]
    contrast = image_maps["summary"]["contrast_mean"]

    strike_period_sec = 8.0
    strike_width_sec = 1.65
    strike_phase = np.mod(t, strike_period_sec)
    strike_gate = np.where(strike_phase < strike_width_sec, 1.0, 0.28)

    mono = np.zeros_like(t)

    for i, (lo, hi) in enumerate(scan_bands):
        sweep_rate = 0.08 + 0.03 * i + 0.04 * edge
        center = (lo + hi) / 2.0
        span = (hi - lo) / 2.0

        freq = center + span * np.sin(2 * np.pi * sweep_rate * t + i * 0.8)
        phase = 2 * np.pi * np.cumsum(freq) / sample_rate

        band_sig = np.sin(phase)
        pulse = 0.45 + 0.55 * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * (2.5 + i + 2 * contrast) * t)))
        band_sig *= strike_gate * pulse

        mono += (0.38 - 0.06 * i) * band_sig

    stereo = stereo_from_mono_and_phase(mono, phase_deg=225, side_amt=0.10, mod=strike_gate)
    stereo *= strength / max(np.max(np.abs(stereo)), 1e-12)
    return stereo


def build_ultrasonic_layer(t, image_maps, strength=1.0, enabled=True):
    if not enabled:
        return np.zeros((len(t), 2), dtype=np.float64)

    b = image_maps["summary"]["brightness_mean"]
    c = image_maps["summary"]["contrast_mean"]
    e = image_maps["summary"]["edge_density_mean"]
    s = image_maps["summary"]["symmetry_mean"]

    f1 = 16000 + 2500 * b
    f2 = 18500 + 3000 * e
    f3 = 21000 + 1200 * c

    mono = (
        0.50 * np.sin(2 * np.pi * f1 * t)
        + 0.30 * np.sin(2 * np.pi * f2 * t + 0.7)
        + 0.20 * np.sin(2 * np.pi * f3 * t + 1.4)
    )
    env = 0.65 + 0.35 * np.sin(2 * np.pi * (0.13 + 0.05 * s) * t)
    mono *= env

    stereo = stereo_from_mono_and_phase(mono, phase_deg=315, side_amt=0.06, mod=env)
    stereo *= strength / max(np.max(np.abs(stereo)), 1e-12)
    return stereo


def classify_pair_mass(feature):
    b = feature["brightness"]
    c = feature["contrast"]
    e = feature["edge_density"]
    s = feature["symmetry"]
    score = 0.35 * c + 0.35 * e + 0.15 * (1.0 - b) + 0.15 * (1.0 - s)

    if score < 0.33:
        return "light"
    if score < 0.66:
        return "medium"
    return "heavy"


def build_helix_region_params(features, helix_cfg=None):
    if helix_cfg is None:
        helix_cfg = HELIX_DEFAULTS.copy()

    region_params = []
    for f in features:
        mass_class = classify_pair_mass(f)
        if mass_class == "light":
            base_mass = helix_cfg["mass_light"]
        elif mass_class == "medium":
            base_mass = helix_cfg["mass_medium"]
        else:
            base_mass = helix_cfg["mass_heavy"]

        strand_stiffness = helix_cfg["strand_stiffness"] * (0.85 + 0.30 * f["symmetry"])
        bond_spring_k = helix_cfg["bond_spring_k"] * (0.80 + 0.35 * f["edge_density"])
        bond_damping_c = helix_cfg["bond_damping_c"] * (0.75 + 0.50 * f["contrast"])
        twist_rate = helix_cfg["twist_rate"] * (0.85 + 0.30 * f["brightness"])
        curvature = helix_cfg["curvature"] * (0.80 + 0.40 * (1.0 - f["brightness"]))

        region_params.append({
            "mass_class": mass_class,
            "base_mass": base_mass,
            "strand_stiffness": strand_stiffness,
            "bond_spring_k": bond_spring_k,
            "bond_damping_c": bond_damping_c,
            "twist_rate": twist_rate,
            "curvature": curvature,
        })

    return region_params


def build_helix_resonance_layer(t, image_maps, features, helix_cfg=None, strength=1.0):
    if helix_cfg is None:
        helix_cfg = HELIX_DEFAULTS.copy()

    summary = image_maps["summary"]
    contrast_mean = summary["contrast_mean"]
    edge_mean = summary["edge_density_mean"]
    symmetry_mean = summary["symmetry_mean"]

    region_params = build_helix_region_params(features, helix_cfg=helix_cfg)

    fluid_drag = helix_cfg["fluid_drag"] * (0.75 + 0.50 * edge_mean)
    thermal_bias = helix_cfg["thermal_bias"] * (0.70 + 0.60 * contrast_mean)

    bend_mode = helix_cfg["bend_mode"]
    torsion_mode = helix_cfg["torsion_mode"]
    coupling_mode = helix_cfg["coupling_mode"]
    shear_mode = helix_cfg["shear_mode"]

    mono = np.zeros_like(t)
    resonance_envelope = np.zeros_like(t)

    total_dur = t[-1] if len(t) > 1 else 1.0
    region_span = total_dur / max(1, int(len(features) * 0.52))

    region_meta = []

    for i, (f, rp) in enumerate(zip(features, region_params)):
        start = (i * 0.27 * region_span) % max(total_dur, 1e-6)
        dur = 1.0 + 0.9 * (0.35 * f["contrast"] + 0.35 * f["edge_density"] + 0.30 * f["symmetry"])
        local = np.clip((t - start) / dur, 0.0, 1.0)

        gate = np.where((t >= start) & (t <= start + dur), 1.0, 0.0)
        gate *= np.sin(np.pi * local) ** 2

        nat = (
            180.0
            + 120.0 * rp["strand_stiffness"]
            + 90.0 * rp["bond_spring_k"]
            + 40.0 * rp["twist_rate"]
            + 25.0 * rp["curvature"]
        ) / max(rp["base_mass"], 1e-9)

        f_bend = nat * (0.85 + 0.10 * bend_mode)
        f_torsion = nat * (1.25 + 0.10 * torsion_mode)
        f_coupling = nat * (1.75 + 0.08 * coupling_mode)
        f_shear = nat * (2.20 + 0.05 * shear_mode)

        damping = clamp(0.05 + fluid_drag + 0.20 * rp["bond_damping_c"], 0.05, 0.85)
        envelope = np.exp(-damping * local) * gate

        thermal_shift = 1.0 + thermal_bias * (0.5 + 0.5 * np.sin(2 * np.pi * 0.07 * t + i * 0.21))

        sig = (
            bend_mode * np.sin(2 * np.pi * (f_bend * thermal_shift) * t + i * 0.05)
            + torsion_mode * np.sin(2 * np.pi * (f_torsion * thermal_shift) * t + i * 0.09)
            + coupling_mode * np.sin(2 * np.pi * (f_coupling * thermal_shift) * t + i * 0.13)
            + shear_mode * np.sin(2 * np.pi * (f_shear * thermal_shift) * t + i * 0.17)
        )

        sig *= envelope
        mono += sig
        resonance_envelope += envelope * (0.5 + 0.5 * rp["bond_spring_k"])

        region_meta.append({
            "index": i,
            "mass_class": rp["mass_class"],
            "base_mass": round(float(rp["base_mass"]), 5),
            "strand_stiffness": round(float(rp["strand_stiffness"]), 5),
            "bond_spring_k": round(float(rp["bond_spring_k"]), 5),
            "bond_damping_c": round(float(rp["bond_damping_c"]), 5),
            "twist_rate": round(float(rp["twist_rate"]), 5),
            "curvature": round(float(rp["curvature"]), 5),
            "nat_freq_symbolic": round(float(nat), 5),
        })

    resonance_envelope = normalize(resonance_envelope)
    resonance_envelope = 0.35 + 0.65 * resonance_envelope

    mono *= resonance_envelope
    stereo = stereo_from_mono_and_phase(
        mono,
        phase_deg=135 + 90 * symmetry_mean,
        side_amt=0.12,
        mod=resonance_envelope,
    )
    stereo *= strength / max(np.max(np.abs(stereo)), 1e-12)

    return stereo, resonance_envelope, {
        "fluid_drag": round(float(fluid_drag), 5),
        "thermal_bias": round(float(thermal_bias), 5),
        "region_params": region_meta,
    }


def save_gray(path, arr):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray((normalize(arr) * 255).astype(np.uint8), mode="L").save(path)


def build_composite(
    image_path,
    symbol_image_path=None,
    tokens=None,
    out_dir="encoder_android_output_v6",
    sample_rate=DEFAULT_SAMPLE_RATE,
    duration_sec=DEFAULT_DURATION_SEC,
    weights=None,
    scan_mode="spiral",
    export_stems=True,
    export_ultrasonic=True,
):
    if tokens is None:
        tokens = ["repair", "cohere", "restore", "symmetry", "integrate"]

    if weights is None:
        weights = DEFAULT_WEIGHTS.copy()

    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    n = int(sample_rate * duration_sec)
    t = np.arange(n, dtype=np.float64) / sample_rate

    image_maps = extract_image_maps(image_path, size=(256, 256), grid=(8, 8))
    ordered_features = spiral_order(image_maps["features"]) if scan_mode == "spiral" else raster_order(image_maps["features"])

    helix_stereo, helix_env, helix_meta = build_helix_resonance_layer(
        t,
        image_maps,
        ordered_features,
        helix_cfg=HELIX_DEFAULTS,
        strength=1.0,
    )

    levin_stereo, levin_env = build_levin_layer(t, image_maps, strength=1.0)
    gariaev_stereo = build_gariaev_layer(
        t,
        image_maps,
        0.55 * levin_env + 0.45 * helix_env,
        sample_rate=sample_rate,
        strength=1.0,
        scan_mode=scan_mode,
    )
    scalar_stereo = build_scalar_layer(t, image_maps, tokens, symbol_image_path, strength=1.0)
    rife_stereo = build_rife_layer(t, image_maps, sample_rate=sample_rate, strength=1.0)
    ultra_stereo = build_ultrasonic_layer(t, image_maps, strength=0.25, enabled=export_ultrasonic)

    mod_scalar = 0.85 + 0.15 * np.sin(2 * np.pi * 0.12 * t)
    mod_gariaev = 0.82 + 0.10 * levin_env + 0.08 * helix_env
    mod_levin = 0.88 + 0.12 * helix_env
    mod_helix = 0.80 + 0.20 * levin_env
    mod_ultra = 0.80 + 0.10 * levin_env + 0.10 * helix_env

    composite = (
        0.16 * helix_stereo * mod_helix[:, None]
        + weights["levin"] * levin_stereo * mod_levin[:, None]
        + weights["gariaev"] * gariaev_stereo * mod_gariaev[:, None]
        + weights["scalar"] * scalar_stereo * mod_scalar[:, None]
        + weights["rife"] * rife_stereo
        + 0.10 * ultra_stereo * mod_ultra[:, None]
    )

    composite[:, 0] = smooth1d(composite[:, 0], 5)
    composite[:, 1] = smooth1d(composite[:, 1], 5)

    composite_path = out_dir / "stacked_morphogenetic_encoder_android_v6_composite.wav"
    write_wav_stereo(composite_path, composite, sample_rate)

    generated = [composite_path]

    if export_stems:
        stems = {
            "stem_helix.wav": helix_stereo,
            "stem_levin.wav": levin_stereo,
            "stem_gariaev.wav": gariaev_stereo,
            "stem_scalar.wav": scalar_stereo,
            "stem_rife.wav": rife_stereo,
        }
        if export_ultrasonic:
            stems["stem_ultrasonic.wav"] = ultra_stereo

        for name, data in stems.items():
            stem_path = out_dir / name
            write_wav_stereo(stem_path, data, sample_rate)
            generated.append(stem_path)

    phase_map = normalize(0.40 * image_maps["image_array"] + 0.35 * image_maps["edge_map"] + 0.25 * image_maps["symmetry_map"])
    amplitude_map = normalize(image_maps["image_array"] + image_maps["edge_map"])
    symmetry_map = image_maps["symmetry_map"]

    phase_path = out_dir / "v6_phase_map.png"
    amplitude_path = out_dir / "v6_amplitude_map.png"
    symmetry_path = out_dir / "v6_symmetry_map.png"

    save_gray(phase_path, phase_map)
    save_gray(amplitude_path, amplitude_map)
    save_gray(symmetry_path, symmetry_map)

    generated += [phase_path, amplitude_path, symmetry_path]

    feature_path = out_dir / "v6_region_features.json"
    helix_path = out_dir / "v6_helix_resonance.json"
    manifest_path = out_dir / "stacked_morphogenetic_encoder_android_v6_manifest.json"

    feature_data = {
        "input_image": str(image_path),
        "image_summary": image_maps["summary"],
        "scan_mode": scan_mode,
        "tokens": tokens,
        "bands_hz": BANDS,
        "weights": weights,
        "features": ordered_features,
    }

    helix_data = {
        "helix_defaults": HELIX_DEFAULTS,
        "helix_meta": helix_meta,
    }

    feature_path.write_text(json.dumps(feature_data, indent=2), encoding="utf-8")
    helix_path.write_text(json.dumps(helix_data, indent=2), encoding="utf-8")

    generated += [feature_path, helix_path]

    manifest = {
        "engine": "stacked_morphogenetic_encoder_android_v6",
        "input_image": str(image_path),
        "symbol_image": None if symbol_image_path is None else str(symbol_image_path),
        "sample_rate": sample_rate,
        "duration_sec": duration_sec,
        "weights": weights,
        "tokens": tokens,
        "scan_mode": scan_mode,
        "bands_hz": BANDS,
        "image_summary": image_maps["summary"],
        "export_stems": export_stems,
        "export_ultrasonic": export_ultrasonic,
        "helix_defaults": HELIX_DEFAULTS,
        "helix_meta": helix_meta,
        "generated_files": [
            {
                "path": str(p),
                "name": p.name,
                "size_bytes": p.stat().st_size,
                "sha256": file_sha256(p),
            }
            for p in generated if p.exists()
        ],
    }

    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    generated.append(manifest_path)

    print(f"[DONE] Composite WAV: {composite_path}")
    print(f"[DONE] Manifest: {manifest_path}")
    print(f"[DONE] Output folder: {out_dir.resolve()}")

    return {
        "wav": str(composite_path),
        "manifest": str(manifest_path),
        "phase_map": str(phase_path),
        "amplitude_map": str(amplitude_path),
        "symmetry_map": str(symmetry_path),
        "feature_json": str(feature_path),
        "helix_json": str(helix_path),
        "generated_files": [str(p) for p in generated if p.exists()],
    }


def prompt_yes_no(question, default="n"):
    value = input(f"{question} [{'Y/n' if default == 'y' else 'y/N'}]: ").strip().lower()
    if not value:
        value = default
    return value in ("y", "yes")


def main():
    print("=== Android Morphogenetic Encoder v6 ===")
    print("Paste full image path when asked.")
    print("Example: /storage/emulated/0/DCIM/Restored/yourimage.jpg")
    print()

    image_path = input("Main image path: ").strip()
    if not image_path:
        print("No image path provided. Exiting.")
        return

    symbol_image_path = None
    if prompt_yes_no("Add symbol/sigil overlay image?", "n"):
        symbol_image_path = input("Symbol image path: ").strip()
        if not symbol_image_path:
            symbol_image_path = None

    tokens_raw = input("Tokens comma-separated [repair, cohere, restore, symmetry, integrate]: ").strip()
    if tokens_raw:
        tokens = [t.strip() for t in tokens_raw.split(",") if t.strip()]
    else:
        tokens = ["repair", "cohere", "restore", "symmetry", "integrate"]

    duration_raw = input("Duration in seconds [72]: ").strip()
    duration_sec = float(duration_raw) if duration_raw else 72.0

    scan_mode = input("Scan mode spiral/raster [spiral]: ").strip().lower()
    if scan_mode not in {"spiral", "raster"}:
        scan_mode = "spiral"

    build_composite(
        image_path=image_path,
        symbol_image_path=symbol_image_path,
        tokens=tokens,
        out_dir="encoder_android_output_v6",
        sample_rate=DEFAULT_SAMPLE_RATE,
        duration_sec=duration_sec,
        weights=DEFAULT_WEIGHTS,
        scan_mode=scan_mode,
        export_stems=True,
        export_ultrasonic=True,
    )


if __name__ == "__main__":
    main()
