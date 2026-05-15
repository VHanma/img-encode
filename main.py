import os
import math
import json
import zlib
import wave
import struct
import traceback
import threading
from pathlib import Path

from PIL import Image

from kivy.app import App
from kivy.clock import mainthread
from kivy.core.window import Window
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.progressbar import ProgressBar
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput


Window.clearcolor = (0.03, 0.03, 0.07, 1)

MAGIC = b"DFMAX01"
HEADER_FMT = ">7sIIBIIH"
HEADER_SIZE = struct.calcsize(HEADER_FMT)

DEFAULTS = {
    "f0": 6400.0,
    "f1": 7000.0,
    "bit_ms": 3.0,
    "sample_rate": 44100,
    "max_dim": 32,
    "lead_ms": 300.0,
    "preamble_freq": 6328.0,
    "preamble_ms": 750.0,
    "aura_freq": 528.0,
    "aura_amp": 0.07,
    "gate_freq": 7.83,
    "gate_depth": 0.35,
    "rife_start": 20.0,
    "rife_end": 20000.0,
    "tesla_gate": 7.83,
    "tesla_pulse": "3-6-9",
    "carrier_original_low_hz": 640000.0,
    "carrier_original_high_hz": 700000.0,
    "carrier_scale": 100.0,
    "coil_marker_hz": 384000.0,
    "infrared_nm": [632.8, 660.0, 850.0, 940.0],
}


def clamp(x):
    if x > 1.0:
        return 1.0
    if x < -1.0:
        return -1.0
    return x


def bytes_to_bits(data):
    for b in data:
        for i in range(7, -1, -1):
            yield (b >> i) & 1


def tone(freq, ms, sr, amp=0.5):
    n = int(sr * ms / 1000.0)
    fade = max(1, int(sr * 0.005))
    out = []

    for i in range(n):
        env = 1.0
        if i < fade:
            env = i / fade
        elif i > n - fade:
            env = max(0.0, (n - i) / fade)

        out.append(math.sin(2.0 * math.pi * freq * i / sr) * amp * env)

    return out


def write_wav(path, samples, sr):
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sr)

        frames = bytearray()
        for sample in samples:
            v = int(clamp(sample) * 32767)
            frames += struct.pack("<h", v)

        wav.writeframes(bytes(frames))


def morphogenetic_features(img):
    gray = img.convert("L")
    w, h = gray.size
    pix = gray.load()

    total = 0
    edge = 0
    mirror_error = 0

    for y in range(h):
        for x in range(w):
            v = pix[x, y]
            total += v

            if x + 1 < w:
                edge += abs(v - pix[x + 1, y])
            if y + 1 < h:
                edge += abs(v - pix[x, y + 1])

            mirror_error += abs(v - pix[w - 1 - x, y])

    count = max(1, w * h)
    brightness = total / (count * 255.0)
    edge_density = edge / max(1.0, count * 255.0 * 2.0)
    symmetry = 1.0 - (mirror_error / max(1.0, count * 255.0))

    return {
        "brightness_voltage_proxy": round(brightness, 6),
        "edge_polarity_boundary_score": round(edge_density, 6),
        "symmetry_morphogenesis_score": round(symmetry, 6),
        "width": w,
        "height": h,
    }


def make_packet(image_path, cfg):
    img = Image.open(image_path).convert("RGBA")
    img.thumbnail((int(cfg["max_dim"]), int(cfg["max_dim"])), Image.Resampling.LANCZOS)

    features = morphogenetic_features(img)

    raw = img.tobytes()
    compressed = zlib.compress(raw, 9)
    crc = zlib.crc32(compressed) & 0xFFFFFFFF

    meta = {
        "name": "DNA Forge Max",
        "dna_payload": "RGBA pixels compressed into binary packet",
        "levin_layer": "bioelectric morphogenesis map from brightness, edge, and symmetry",
        "gariaev_layer": {
            "zero_hz": cfg["f0"],
            "one_hz": cfg["f1"],
            "he_ne_preamble_hz": cfg["preamble_freq"],
            "original_carrier_hz": [
                cfg["carrier_original_low_hz"],
                cfg["carrier_original_high_hz"],
            ],
            "carrier_scale": cfg["carrier_scale"],
        },
        "rife_layer": {
            "sweep_start_hz": cfg["rife_start"],
            "sweep_end_hz": cfg["rife_end"],
            "mode": "metadata resonance sweep layer",
        },
        "tesla_layer": {
            "schumann_gate_hz": cfg["tesla_gate"],
            "pulse_rhythm": cfg["tesla_pulse"],
            "standing_wave": True,
        },
        "bearden_scalar_layer": {
            "infolded_potential_metadata": True,
            "phase_conjugate_reverse_hash": True,
            "coil_marker_hz": cfg["coil_marker_hz"],
        },
        "infrared_layer": {
            "wavelengths_nm": cfg["infrared_nm"],
            "note": "metadata and screen-light proxy only; phone speaker cannot emit infrared",
        },
        "morphogenetic_features": features,
    }

    meta_bytes = json.dumps(meta, separators=(",", ":")).encode("utf-8")

    if len(meta_bytes) > 65535:
        raise ValueError("Metadata too large")

    header = struct.pack(
        HEADER_FMT,
        MAGIC,
        img.size[0],
        img.size[1],
        4,
        len(compressed),
        crc,
        len(meta_bytes),
    )

    return header + meta_bytes + compressed, meta


def apply_layers(samples, sr, cfg):
    aura_freq = float(cfg["aura_freq"])
    aura_amp = float(cfg["aura_amp"])
    gate_freq = float(cfg["gate_freq"])
    gate_depth = float(cfg["gate_depth"])

    out = []

    for i, s
cd ~

git config --global user.name "VHanma"
git config --global user.email "VHanma@users.noreply.github.com"
git config --global --add safe.directory '*'

rm -rf img-encode-total-fix
git clone https://github.com/VHanma/img-encode img-encode-total-fix
cd img-encode-total-fix

BASE="$(gh repo view VHanma/img-encode --json defaultBranchRef --jq '.defaultBranchRef.name')"
git checkout "$BASE"

BR="fix/clean-dna-forge-max-$(date +%Y%m%d-%H%M%S)"
git checkout -b "$BR"

mkdir -p .github/workflows docs

cat > main.py <<'PY'
import os
import math
import json
import zlib
import wave
import struct
import traceback
import threading
from pathlib import Path

from PIL import Image

from kivy.app import App
from kivy.clock import mainthread
from kivy.core.window import Window
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.progressbar import ProgressBar
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput


Window.clearcolor = (0.03, 0.03, 0.07, 1)

MAGIC = b"DFMAX01"
HEADER_FMT = ">7sIIBIIH"
HEADER_SIZE = struct.calcsize(HEADER_FMT)

DEFAULTS = {
    "f0": 6400.0,
    "f1": 7000.0,
    "bit_ms": 3.0,
    "sample_rate": 44100,
    "max_dim": 32,
    "lead_ms": 300.0,
    "preamble_freq": 6328.0,
    "preamble_ms": 750.0,
    "aura_freq": 528.0,
    "aura_amp": 0.07,
    "gate_freq": 7.83,
    "gate_depth": 0.35,
    "rife_start": 20.0,
    "rife_end": 20000.0,
    "tesla_gate": 7.83,
    "tesla_pulse": "3-6-9",
    "carrier_original_low_hz": 640000.0,
    "carrier_original_high_hz": 700000.0,
    "carrier_scale": 100.0,
    "coil_marker_hz": 384000.0,
    "infrared_nm": [632.8, 660.0, 850.0, 940.0],
}


def clamp(x):
    if x > 1.0:
        return 1.0
    if x < -1.0:
        return -1.0
    return x


def bytes_to_bits(data):
    for b in data:
        for i in range(7, -1, -1):
            yield (b >> i) & 1


def tone(freq, ms, sr, amp=0.5):
    n = int(sr * ms / 1000.0)
    fade = max(1, int(sr * 0.005))
    out = []

    for i in range(n):
        env = 1.0
        if i < fade:
            env = i / fade
        elif i > n - fade:
            env = max(0.0, (n - i) / fade)

        out.append(math.sin(2.0 * math.pi * freq * i / sr) * amp * env)

    return out


def write_wav(path, samples, sr):
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sr)

        frames = bytearray()
        for sample in samples:
            v = int(clamp(sample) * 32767)
            frames += struct.pack("<h", v)

        wav.writeframes(bytes(frames))


def morphogenetic_features(img):
    gray = img.convert("L")
    w, h = gray.size
    pix = gray.load()

    total = 0
    edge = 0
    mirror_error = 0

    for y in range(h):
        for x in range(w):
            v = pix[x, y]
            total += v

            if x + 1 < w:
                edge += abs(v - pix[x + 1, y])
            if y + 1 < h:
                edge += abs(v - pix[x, y + 1])

            mirror_error += abs(v - pix[w - 1 - x, y])

    count = max(1, w * h)
    brightness = total / (count * 255.0)
    edge_density = edge / max(1.0, count * 255.0 * 2.0)
    symmetry = 1.0 - (mirror_error / max(1.0, count * 255.0))

    return {
        "brightness_voltage_proxy": round(brightness, 6),
        "edge_polarity_boundary_score": round(edge_density, 6),
        "symmetry_morphogenesis_score": round(symmetry, 6),
        "width": w,
        "height": h,
    }


def make_packet(image_path, cfg):
    img = Image.open(image_path).convert("RGBA")
    img.thumbnail((int(cfg["max_dim"]), int(cfg["max_dim"])), Image.Resampling.LANCZOS)

    features = morphogenetic_features(img)

    raw = img.tobytes()
    compressed = zlib.compress(raw, 9)
    crc = zlib.crc32(compressed) & 0xFFFFFFFF

    meta = {
        "name": "DNA Forge Max",
        "dna_payload": "RGBA pixels compressed into binary packet",
        "levin_layer": "bioelectric morphogenesis map from brightness, edge, and symmetry",
        "gariaev_layer": {
            "zero_hz": cfg["f0"],
            "one_hz": cfg["f1"],
            "he_ne_preamble_hz": cfg["preamble_freq"],
            "original_carrier_hz": [
                cfg["carrier_original_low_hz"],
                cfg["carrier_original_high_hz"],
            ],
            "carrier_scale": cfg["carrier_scale"],
        },
        "rife_layer": {
            "sweep_start_hz": cfg["rife_start"],
            "sweep_end_hz": cfg["rife_end"],
            "mode": "metadata resonance sweep layer",
        },
        "tesla_layer": {
            "schumann_gate_hz": cfg["tesla_gate"],
            "pulse_rhythm": cfg["tesla_pulse"],
            "standing_wave": True,
        },
        "bearden_scalar_layer": {
            "infolded_potential_metadata": True,
            "phase_conjugate_reverse_hash": True,
            "coil_marker_hz": cfg["coil_marker_hz"],
        },
        "infrared_layer": {
            "wavelengths_nm": cfg["infrared_nm"],
            "note": "metadata and screen-light proxy only; phone speaker cannot emit infrared",
        },
        "morphogenetic_features": features,
    }

    meta_bytes = json.dumps(meta, separators=(",", ":")).encode("utf-8")

    if len(meta_bytes) > 65535:
        raise ValueError("Metadata too large")

    header = struct.pack(
        HEADER_FMT,
        MAGIC,
        img.size[0],
        img.size[1],
        4,
        len(compressed),
        crc,
        len(meta_bytes),
    )

    return header + meta_bytes + compressed, meta


def apply_layers(samples, sr, cfg):
    aura_freq = float(cfg["aura_freq"])
    aura_amp = float(cfg["aura_amp"])
    gate_freq = float(cfg["gate_freq"])
    gate_depth = float(cfg["gate_depth"])

    out = []

    for i, s in enumerate(samples):
        t = i / sr

        aura = math.sin(2.0 * math.pi * aura_freq * t) * aura_amp

        gate_wave = (1.0 + math.sin(2.0 * math.pi * gate_freq * t)) / 2.0
        gate_env = 1.0 - gate_depth + gate_depth * gate_wave

        # 3/6/9 pulse accent
        pulse = 1.0
        beat = int(t * 9.0) % 9
        if beat in (2, 5, 8):
            pulse = 1.08

        out.append(clamp((s * gate_env * pulse) + aura))

    return out


def encode_image_to_wav(image_path, output_dir, log=None):
    cfg = DEFAULTS.copy()
    sr = int(cfg["sample_rate"])

    out_dir = Path(output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    stem = Path(image_path).stem
    wav_path = out_dir / f"{stem}_DNA_FORGE_MAX.wav"
    json_path = out_dir / f"{stem}_DNA_FORGE_MAX_manifest.json"

    if log:
        log("Reading image and building DNA binary packet...")

    packet, meta = make_packet(image_path, cfg)
    bits = list(bytes_to_bits(packet))

    bit_ms = float(cfg["bit_ms"])
    spb = int(sr * bit_ms / 1000.0)

    samples = []
    samples += [0.0] * int(sr * cfg["lead_ms"] / 1000.0)

    if log:
        log("Adding 6328 Hz He-Ne/Gariaev preamble...")

    samples += tone(cfg["preamble_freq"], cfg["preamble_ms"], sr, 0.45)
    samples += [0.0] * int(sr * 0.05)

    if log:
        log("Writing binary pixels as 6400/7000 Hz tones...")

    phase = 0.0
    for bit in bits:
        freq = cfg["f1"] if bit else cfg["f0"]
        step = 2.0 * math.pi * freq / sr

        for _ in range(spb):
            samples.append(math.sin(phase) * 0.70)
            phase = (phase + step) % (2.0 * math.pi)

    samples += [0.0] * int(sr * cfg["lead_ms"] / 1000.0)

    if log:
        log("Adding 528 Hz aura bed + 7.83 Hz Tesla/Schumann gate + 3/6/9 pulse...")

    samples = apply_layers(samples, sr, cfg)

    write_wav(str(wav_path), samples, sr)

    manifest = {
        "wav": str(wav_path),
        "seconds": round(len(samples) / sr, 3),
        "packet_bytes": len(packet),
        "bits": len(bits),
        "sample_rate": sr,
        "layers": meta,
    }

    json_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    return str(wav_path), str(json_path), manifest


class DNAForgeMaxApp(App):
    def build(self):
        self.running = False

        root = BoxLayout(orientation="vertical", padding=14, spacing=10)

        title = Label(
            text="[b]DNA Forge Max[/b]\nLevin + Gariaev + Rife + Tesla + Bearden + Infrared Metadata",
            markup=True,
            font_size="18sp",
            size_hint_y=None,
            height=82,
            color=(0.45, 0.95, 1.0, 1.0),
        )
        root.add_widget(title)

        self.path_input = TextInput(
            text="monalisa.jpg",
            multiline=False,
            size_hint_y=None,
            height=48,
            hint_text="Image path, example: monalisa.jpg",
            foreground_color=(1, 1, 1, 1),
            background_color=(0.08, 0.08, 0.16, 1),
        )
        root.add_widget(self.path_input)

        self.button = Button(
            text="ENCODE IMAGE TO DNA FORGE WAV",
            size_hint_y=None,
            height=62,
            font_size="16sp",
            bold=True,
            background_color=(0.55, 0.15, 0.9, 1),
        )
        self.button.bind(on_press=self.start_encode)
        root.add_widget(self.button)

        self.progress = ProgressBar(max=100, value=0, size_hint_y=None, height=18)
        root.add_widget(self.progress)

        scroll = ScrollView()
        self.log_label = Label(
            text="Ready.\nDefault image path is monalisa.jpg if bundled in repo.\n",
            markup=True,
            font_size="12sp",
            size_hint_y=None,
            valign="top",
            halign="left",
            color=(0.75, 1.0, 0.75, 1.0),
            padding=(8, 8),
        )
        self.log_label.bind(texture_size=self.log_label.setter("size"))
        scroll.add_widget(self.log_label)
        root.add_widget(scroll)

        return root

    def start_encode(self, *_):
        if self.running:
            return

        path = self.path_input.text.strip()

        if not os.path.exists(path):
            alt = os.path.join(os.getcwd(), path)
            if os.path.exists(alt):
                path = alt

        if not os.path.exists(path):
            self.log(f"[color=ff5555]Image not found: {self.path_input.text}[/color]")
            self.log("Tip: use bundled monalisa.jpg first.")
            return

        self.running = True
        self.button.disabled = True
        self.progress.value = 5

        threading.Thread(target=self.worker, args=(path,), daemon=True).start()

    def worker(self, path):
        try:
            self.log("Starting DNA Forge Max encode...")
            self.set_progress(10)

            out_dir = os.path.join(self.user_data_dir, "DNA_FORGE_MAX_OUTPUT")
            wav_path, json_path, manifest = encode_image_to_wav(path, out_dir, self.log)

            self.set_progress(100)
            self.log("[color=00ff99]DONE[/color]")
            self.log(f"WAV: {wav_path}")
            self.log(f"Manifest: {json_path}")
            self.log(f"Duration: {manifest['seconds']} sec")
        except Exception as e:
            self.log(f"[color=ff5555]ERROR: {e}[/color]")
            self.log(traceback.format_exc())
        finally:
            self.done()

    @mainthread
    def log(self, msg):
        self.log_label.text += str(msg) + "\n"

    @mainthread
    def set_progress(self, val):
        self.progress.value = val

    @mainthread
    def done(self):
        self.running = False
        self.button.disabled = False


if __name__ == "__main__":
    DNAForgeMaxApp().run()
