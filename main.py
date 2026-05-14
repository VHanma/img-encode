"""
Living Image v22 — Kivy Android app
v22 Gariaev spectrogram engine embedded inline (no import).
On-device mode locked.
"""

import math
import os
import time
import wave
import zipfile
import threading
import traceback
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

from kivy.app import App
from kivy.clock import mainthread
from kivy.core.window import Window
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.popup import Popup
from kivy.uix.progressbar import ProgressBar
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.utils import platform

Window.clearcolor = (0.05, 0.05, 0.10, 1)

# ──────────────────────────────────────────────
# v22 engine — inline (no import)
# ──────────────────────────────────────────────

SAMPLE_RATE  = 44100
F_LOW        = 20.0
F_HIGH       = 20000.0
N_FREQ_BINS  = 512
FRAME_HOP    = 512
MAX_IMG_COLS = 1024

LASER_NM      = 632.8
LASER_HZ      = 3e8 / (LASER_NM * 1e-9)
PIXEL_SIZE_UM = 5.0
SONIFY_BLEND  = 0.35


def _norm01(a):
    mn, mx = float(a.min()), float(a.max())
    if abs(mx - mn) < 1e-12:
        return np.zeros_like(a, dtype=np.float64)
    return (a - mn) / (mx - mn)


def _log_freq_axis(n_bins, f_low, f_high):
    return np.exp(np.linspace(math.log(f_low), math.log(f_high), n_bins))


def _i16(x):
    x = np.nan_to_num(np.asarray(x, dtype=np.float64))
    pk = np.max(np.abs(x))
    if pk > 1e-9:
        x = x / pk * 0.92
    return np.clip(x * 32767, -32768, 32767).astype("<i2")


def _load_image_gray(image_path, n_freq_rows, max_time_cols=MAX_IMG_COLS):
    resample = getattr(getattr(Image, "Resampling", Image), "LANCZOS",
                       getattr(Image, "BICUBIC", 3))
    img = ImageOps.exif_transpose(Image.open(image_path)).convert("L")
    w, h = img.size
    new_w = min(max_time_cols, w)
    img = img.resize((new_w, n_freq_rows), resample)
    arr = np.asarray(img, dtype=np.float64) / 255.0
    return arr[::-1, :].copy()


def _image_fft2(gray):
    F = np.fft.fft2(gray)
    F_shifted = np.fft.fftshift(F)
    mag   = np.abs(F_shifted)
    phase = np.angle(F_shifted)
    h, w  = mag.shape
    cy, cx = h // 2, w // 2
    Y, X  = np.ogrid[:h, :w]
    R     = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2).astype(int)
    max_r = min(cx, cy)
    radial = np.array([mag[R == r].mean() if np.any(R == r) else 0.0
                       for r in range(max_r)])
    radial = _norm01(radial)
    peak_r     = int(np.argmax(radial[1:]) + 1)
    mean_phase = float(np.mean(phase))
    dc_amp     = float(mag[cy, cx] / (mag.mean() + 1e-12))
    return {"mag_norm": _norm01(mag), "phase": phase, "radial": radial,
            "peak_r": peak_r, "mean_phase": mean_phase, "dc_amp": dc_amp}


def _laser_diffraction_layer(gray, fft_freqs, n_rfft):
    h, w = gray.shape
    F = np.fft.fft2(gray)
    mag = np.abs(np.fft.fftshift(F))
    pixel_size_m = PIXEL_SIZE_UM * 1e-6
    freq_y = np.fft.fftshift(np.fft.fftfreq(h, d=pixel_size_m))
    freq_x = np.fft.fftshift(np.fft.fftfreq(w, d=pixel_size_m))
    FX, FY = np.meshgrid(freq_x, freq_y)
    R_spatial = np.sqrt(FX**2 + FY**2)
    lambda_m = LASER_NM * 1e-9
    sin_theta = np.clip(R_spatial * lambda_m, -1.0, 1.0)
    theta = np.arcsin(sin_theta)
    half_pi = math.pi / 2.0
    audio_hz = (theta / half_pi) * (F_HIGH - F_LOW) + F_LOW
    audio_hz_flat = audio_hz.ravel()
    mag_flat = mag.ravel()
    spectrum = np.zeros(n_rfft, dtype=np.float64)
    bin_indices = np.searchsorted(fft_freqs, audio_hz_flat).clip(0, n_rfft - 1)
    np.add.at(spectrum, bin_indices, mag_flat)
    pk = spectrum.max()
    if pk > 1e-12:
        spectrum /= pk
    return spectrum


def _sonify_column(col_pixels, fft_freqs, n_rfft):
    n_rows  = len(col_pixels)
    freqs   = _log_freq_axis(n_rows, F_LOW, F_HIGH)
    X       = np.zeros(n_rfft, dtype=np.complex128)
    bin_idx = np.interp(freqs, fft_freqs, np.arange(n_rfft)).astype(int)
    bin_idx = np.clip(bin_idx, 0, n_rfft - 1)
    mask    = col_pixels > 1e-9
    np.add.at(X, bin_idx[mask], col_pixels[mask].astype(np.complex128))
    return X


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


def _nearest_bin(fft_freqs, freq_hz):
    return int(np.argmin(np.abs(fft_freqs - freq_hz)))

def _img_freq(f_low, f_high, ratio):
    return f_low * ((f_high / f_low) ** max(0.0, min(1.0, ratio)))

def _add_levin(X, fft_freqs, col, n_frames, amp):
    bi = _nearest_bin(fft_freqs, _img_freq(40.0, 200.0, 0.25))
    X[bi] += amp * 0.15 * np.exp(1j * 2 * math.pi * col / max(1, n_frames))

def _add_scalar(X, fft_freqs, col, n_frames, amp, mean_phase):
    bi = _nearest_bin(fft_freqs, _img_freq(300.0, 900.0, 0.5))
    X[bi] += amp * 0.12 * np.exp(1j * (mean_phase + 2 * math.pi * col / max(1, n_frames)))

def _add_morphic(X, fft_freqs, col, n_frames, amp):
    bi = _nearest_bin(fft_freqs, _img_freq(60.0, 250.0, 0.5))
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


def _write_wav_streaming(path, sr, duration_s, spec, fft_data,
                         n_total_frames=None, chunk_cols=256,
                         progress_cb=None):
    n_freq_rows, n_img_cols = spec.shape
    fft_size = N_FREQ_BINS * 2
    hop      = FRAME_HOP
    target_samples = int(sr * duration_s)
    if n_total_frames is None:
        n_total_frames = max(64, int(sr * duration_s / hop))

    freqs     = _log_freq_axis(n_freq_rows, F_LOW, F_HIGH)
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

    laser_spec = _laser_diffraction_layer(spec, fft_freqs, n_rfft)

    ph_ys     = np.clip(
        (np.arange(n_freq_rows) * fft_data["phase"].shape[0] / n_freq_rows).astype(int),
        0, fft_data["phase"].shape[0] - 1)
    ph_n_cols = fft_data["phase"].shape[1]

    samples_written = 0

    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)

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

                ph_x = min(int(col_idx * ph_n_cols / n_img_cols), ph_n_cols - 1)
                phis = fft_data["phase"][ph_ys, ph_x]
                mask = col_amp > 1e-9
                np.add.at(X, fft_bin_idx[mask],
                          col_amp[mask] * np.exp(1j * phis[mask]))

                X_sonify = _sonify_column(col_amp, fft_freqs, n_rfft)
                X += SONIFY_BLEND * X_sonify

                frame_phase = 2 * math.pi * frame_idx / max(1, n_total_frames)
                X += 0.15 * laser_spec * np.exp(1j * frame_phase)

                _add_levin(X,    fft_freqs, frame_idx, n_total_frames, levin_amp)
                _add_scalar(X,   fft_freqs, frame_idx, n_total_frames, scalar_amp, mean_phase)
                _add_morphic(X,  fft_freqs, frame_idx, n_total_frames, morphic_amp)
                _add_zpf(X,      zpf_amp)
                _add_tesla(X,    fft_freqs, frame_idx, n_total_frames, tesla_amp)
                _add_schumann(X, fft_freqs, frame_idx, n_total_frames, schumann_amp)

                frame_audio = np.fft.irfft(X, n=fft_size)[:hop]
                buf[ci * hop: ci * hop + len(frame_audio)] += frame_audio

            remaining = target_samples - samples_written
            out_chunk = buf[:min(len(buf), remaining)]
            wf.writeframes(_i16(out_chunk).tobytes())
            samples_written += len(out_chunk)

            if progress_cb:
                progress_cb(10 + int(80 * samples_written / target_samples))

            if samples_written >= target_samples:
                break


def _write_svg(path, spec):
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
    Path(path).write_text("\n".join(rows), encoding="utf-8")


def run_v22(image_path, duration_s, output_dir, output_prefix=None, progress_cb=None):
    """Run the v22 engine. Returns dict with wav/svg/report paths."""
    image_path = str(image_path)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    spec     = _load_image_gray(image_path, N_FREQ_BINS, MAX_IMG_COLS)
    fft_data = _image_fft2(spec)

    prefix     = output_prefix or Path(image_path).stem
    out_wav    = output_dir / f"{prefix}_v22.wav"
    out_svg    = output_dir / f"{prefix}_v22.svg"
    out_report = output_dir / f"{prefix}_v22_report.txt"

    n_total_frames = max(64, int(duration_s * SAMPLE_RATE / FRAME_HOP))

    _write_wav_streaming(out_wav, SAMPLE_RATE, duration_s, spec, fft_data,
                         n_total_frames=n_total_frames, progress_cb=progress_cb)
    _write_svg(out_svg, spec)

    # minimal inline report
    lines = [
        "=" * 60,
        "LIVING IMAGE v22 — GARIAEV + SONIFY + LASER REPORT",
        "=" * 60,
        f"Input image   : {os.path.basename(image_path)}",
        f"Output prefix : {prefix}",
        f"Duration      : {duration_s:.1f} s",
        f"Layers        : Gariaev IFFT + Sonify (blend={SONIFY_BLEND}) + He-Ne {LASER_NM}nm",
        f"Generated     : {time.strftime('%Y-%m-%dT%H:%M:%S')}",
        "=" * 60,
    ]
    out_report.write_text("\n".join(lines), encoding="utf-8")

    return {"wav": str(out_wav), "svg": str(out_svg), "report": str(out_report),
            "prefix": prefix}


# ──────────────────────────────────────────────
# Android helpers
# ──────────────────────────────────────────────

def get_output_dir():
    if platform == "android":
        try:
            from jnius import autoclass
            PythonActivity = autoclass("org.kivy.android.PythonActivity")
            context = PythonActivity.mActivity
            base = context.getExternalFilesDir(None).getAbsolutePath()
            out_dir = os.path.join(base, "LivingImage_outputs")
        except Exception:
            app = App.get_running_app()
            out_dir = os.path.join(app.user_data_dir, "LivingImage_outputs")
    else:
        out_dir = os.path.expanduser("~/LivingImage_outputs")
    os.makedirs(out_dir, exist_ok=True)
    return out_dir


def export_output_zip(out_dir):
    zip_name = "LivingImage_export_%s.zip" % time.strftime("%Y%m%d_%H%M%S")
    zip_path = os.path.join(out_dir, zip_name)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(out_dir):
            for name in files:
                full = os.path.join(root, name)
                if full == zip_path or name.lower().endswith(".zip"):
                    continue
                z.write(full, os.path.relpath(full, out_dir))

    if platform != "android":
        return zip_path, zip_path

    try:
        from jnius import autoclass
        from androidstorage4kivy import SharedStorage
        Environment = autoclass("android.os.Environment")
        ss = SharedStorage()
        shared_ref = ss.copy_to_shared(
            zip_path,
            collection=Environment.DIRECTORY_DOWNLOADS,
            filepath=os.path.join("LivingImage_export", zip_name),
        )
        if not shared_ref:
            shared_ref = ss.copy_to_shared(
                zip_path,
                collection=Environment.DIRECTORY_DOCUMENTS,
                filepath=os.path.join("LivingImage_export", zip_name),
            )
        return zip_path, str(shared_ref)
    except Exception as e:
        return zip_path, "Shared export failed: %r" % e


# ──────────────────────────────────────────────
# Kivy app
# ──────────────────────────────────────────────

class LivingImageApp(App):
    def build(self):
        self.image_path    = None
        self.running       = False
        self.custom_prefix = None

        root = BoxLayout(orientation="vertical", padding=12, spacing=8)

        root.add_widget(Label(
            text="[b]Living Image v22[/b]",
            markup=True, font_size="22sp",
            size_hint_y=None, height=42,
            color=(0.4, 0.9, 1, 1),
        ))

        root.add_widget(Label(
            text="[b]On-Device  •  Gariaev + Sonify + Laser[/b]",
            markup=True, font_size="13sp",
            size_hint_y=None, height=26,
            color=(0.4, 1, 0.6, 1),
        ))

        self.pick_btn = Button(
            text="Pick Image",
            size_hint_y=None, height=46,
            background_color=(0.1, 0.55, 0.25, 1),
            font_size="16sp",
        )
        self.pick_btn.bind(on_press=self.pick_image)
        root.add_widget(self.pick_btn)

        self.img_label = Label(
            text="No image selected",
            size_hint_y=None, height=28,
            color=(0.65, 0.65, 0.65, 1),
            font_size="12sp",
        )
        root.add_widget(self.img_label)

        dur_row = BoxLayout(size_hint_y=None, height=40, spacing=8)
        dur_row.add_widget(Label(
            text="Duration (s):",
            size_hint_x=0.38, color=(0.85, 0.85, 0.85, 1),
        ))
        self.duration_input = TextInput(
            text="30", multiline=False, input_filter="int",
            background_color=(0.1, 0.1, 0.2, 1),
            foreground_color=(1, 1, 1, 1),
            size_hint_x=0.62, font_size="15sp",
            hint_text="10 – 600",
        )
        dur_row.add_widget(self.duration_input)
        root.add_widget(dur_row)

        self.run_btn = Button(
            text="RUN ENCODE",
            size_hint_y=None, height=52,
            background_color=(0.65, 0.15, 0.75, 1),
            font_size="18sp", bold=True,
        )
        self.run_btn.bind(on_press=self.show_naming_dialog)
        root.add_widget(self.run_btn)

        self.progress = ProgressBar(max=100, value=0, size_hint_y=None, height=14)
        root.add_widget(self.progress)

        scroll = ScrollView()
        self.log_label = Label(
            text="Ready. v22 engine active.\n",
            markup=True, font_size="12sp",
            size_hint_y=None, valign="top", halign="left",
            color=(0.7, 1, 0.7, 1), padding=(6, 6),
        )
        self.log_label.bind(texture_size=self.log_label.setter("size"))
        scroll.add_widget(self.log_label)
        root.add_widget(scroll)

        return root

    # ── File naming dialog ─────────────────────

    def show_naming_dialog(self, *args):
        if self.running:
            return
        if not self.image_path or not os.path.exists(self.image_path):
            self.log("[color=ff4444]Pick an image first.[/color]")
            return

        # default name = image stem + timestamp
        stem    = Path(self.image_path).stem
        ts      = time.strftime("%Y%m%d_%H%M%S")
        default = f"{stem}_{ts}"

        content = BoxLayout(orientation="vertical", padding=10, spacing=10)
        content.add_widget(Label(
            text="Name your file:",
            size_hint_y=None, height=32,
            color=(0.85, 0.85, 0.85, 1),
        ))
        name_input = TextInput(
            text=default, multiline=False,
            background_color=(0.1, 0.1, 0.2, 1),
            foreground_color=(1, 1, 1, 1),
            font_size="14sp",
            size_hint_y=None, height=40,
        )
        content.add_widget(name_input)

        btn_row = BoxLayout(size_hint_y=None, height=44, spacing=8)
        cancel_btn = Button(text="Cancel", background_color=(0.3, 0.3, 0.3, 1))
        start_btn  = Button(text="Start Encode", background_color=(0.65, 0.15, 0.75, 1))
        btn_row.add_widget(cancel_btn)
        btn_row.add_widget(start_btn)
        content.add_widget(btn_row)

        popup = Popup(
            title="Name Output Files",
            content=content,
            size_hint=(0.9, 0.4),
            background_color=(0.05, 0.05, 0.1, 1),
        )

        def on_cancel(*a):
            popup.dismiss()

        def on_start(*a):
            raw = name_input.text.strip()
            # sanitise: keep alphanumeric, dash, underscore
            safe = "".join(c for c in raw if c.isalnum() or c in "-_") or default
            self.custom_prefix = safe
            popup.dismiss()
            self.run_encode()

        cancel_btn.bind(on_press=on_cancel)
        start_btn.bind(on_press=on_start)
        popup.open()

    # ── Core encode flow ───────────────────────

    def run_encode(self):
        try:
            duration = max(10, min(600, int(self.duration_input.text or "30")))
        except Exception:
            duration = 30

        self.running = True
        self.run_btn.disabled = True
        self.set_progress(0)
        prefix = self.custom_prefix or Path(self.image_path).stem
        self.log("Starting v22 on-device encode (%ds) — prefix: [b]%s[/b]…" % (duration, prefix))

        threading.Thread(
            target=self._run_device,
            args=(self.image_path, duration, prefix),
            daemon=True,
        ).start()

    def _run_device(self, image_path, duration, prefix):
        try:
            out_dir = get_output_dir()
            self.log("Output dir: %s" % out_dir)
            self.set_progress(5)

            result = run_v22(
                image_path, duration, out_dir,
                output_prefix=prefix,
                progress_cb=self.set_progress,
            )

            self.set_progress(92)
            zip_path, shared_ref = export_output_zip(out_dir)
            self.set_progress(100)

            wav_size = os.path.getsize(result["wav"]) if os.path.exists(result["wav"]) else 0
            svg_size = os.path.getsize(result["svg"]) if os.path.exists(result["svg"]) else 0

            self.log(
                "[color=00ff99]Done![/color]\n"
                "  Prefix: [b]%s[/b]\n"
                "  WAV: %s  (%.1f MB)\n"
                "  SVG: %s  (%.1f MB)\n"
                "  ZIP: %s\n"
                "  Shared: %s\n"
                "[color=00ff99]Check Files app › Downloads › LivingImage_export[/color]"
                % (
                    result["prefix"],
                    os.path.basename(result["wav"]), wav_size / 1e6,
                    os.path.basename(result["svg"]), svg_size / 1e6,
                    zip_path, shared_ref,
                )
            )

        except Exception as e:
            self.log("[color=ff4444]Error: %s\n%s[/color]" % (e, traceback.format_exc()))
        finally:
            self._done()

    # ── Image picker ───────────────────────────

    def pick_image(self, *args):
        if platform == "android":
            try:
                from android.permissions import request_permissions, Permission
                perms = []
                if hasattr(Permission, "READ_MEDIA_IMAGES"):
                    perms.append(Permission.READ_MEDIA_IMAGES)
                if hasattr(Permission, "READ_EXTERNAL_STORAGE"):
                    perms.append(Permission.READ_EXTERNAL_STORAGE)
                if hasattr(Permission, "WRITE_EXTERNAL_STORAGE"):
                    perms.append(Permission.WRITE_EXTERNAL_STORAGE)
                if perms:
                    request_permissions(perms)
            except Exception:
                pass
            try:
                from androidstorage4kivy import Chooser
                self._chooser = Chooser(self._on_image_chosen)
                self._chooser.choose_content("image/*")
            except Exception as e:
                self.log("[color=ff4444]Picker error: %s[/color]" % e)
        else:
            from kivy.uix.filechooser import FileChooserIconView
            chooser = FileChooserIconView(filters=["*.jpg", "*.jpeg", "*.png"])
            popup = Popup(title="Pick Image", content=chooser, size_hint=(0.9, 0.9))

            def on_submit(instance, selection, touch=None):
                if selection:
                    self.image_path = selection[0]
                    self.img_label.text = os.path.basename(self.image_path)
                    popup.dismiss()

            chooser.bind(on_submit=on_submit)
            popup.open()

    def _on_image_chosen(self, uri_list):
        if not uri_list:
            return
        try:
            from androidstorage4kivy import SharedStorage
            ss = SharedStorage()
            path = ss.copy_from_shared(uri_list[0])
            if path:
                self.image_path = path
                self.img_label.text = os.path.basename(path)
                self.log("Image: %s" % os.path.basename(path))
            else:
                self.log("[color=ff4444]Image picker returned no private file.[/color]")
        except Exception as e:
            self.log("[color=ff4444]Image load error: %s[/color]" % e)

    # ── Kivy thread helpers ────────────────────

    @mainthread
    def log(self, msg):
        self.log_label.text += msg + "\n"

    @mainthread
    def set_progress(self, value):
        self.progress.value = value

    @mainthread
    def _done(self):
        self.running = False
        self.run_btn.disabled = False


if __name__ == "__main__":
    LivingImageApp().run()
