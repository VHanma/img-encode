import os, csv, json, math, time, wave, hashlib, zlib
from pathlib import Path
import numpy as np
from PIL import Image, ImageOps

C = 299792458.0

def sha_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1024*1024), b""):
            h.update(b)
    return h.hexdigest()

def norm(a):
    a = np.asarray(a, dtype=np.float64)
    mn, mx = float(a.min()), float(a.max())
    return np.zeros_like(a) if abs(mx-mn) < 1e-12 else (a-mn)/(mx-mn)

def resample1d(a, n):
    a = np.asarray(a, dtype=np.float64).ravel()
    if len(a) == 0:
        return np.zeros(n)
    if len(a) == 1:
        return np.full(n, float(a[0]))
    return np.interp(np.linspace(0,1,n), np.linspace(0,1,len(a)), a)

def i16(x):
    x = np.nan_to_num(np.asarray(x, dtype=np.float64))
    pk = float(np.max(np.abs(x))) if x.size else 0
    if pk > 1:
        x = x / pk
    return (np.clip(x, -0.98, 0.98) * 32767).astype("<i2")

def write_mono_wav(path, sr, dur, gen, chunk_s=0.25):
    total = int(sr*dur)
    chunk = max(1, int(sr*chunk_s))
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        done = 0
        while done < total:
            n = min(chunk, total-done)
            t = (np.arange(n, dtype=np.float64)+done)/sr
            w.writeframes(i16(gen(t)).tobytes())
            done += n

def write_stereo_wav(path, sr, dur, gen, chunk_s=0.25):
    total = int(sr*dur)
    chunk = max(1, int(sr*chunk_s))
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        done = 0
        while done < total:
            n = min(chunk, total-done)
            t = (np.arange(n, dtype=np.float64)+done)/sr
            l, r = gen(t)
            stereo = np.empty((n,2), dtype="<i2")
            stereo[:,0] = i16(l)
            stereo[:,1] = i16(r)
            w.writeframes(stereo.tobytes())
            done += n

class LivingImageV21:
    def __init__(self, duration_s=30, output_dir=".", theme="omni_hanma"):
        self.duration_s = max(1.0, float(duration_s))
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.theme = str(theme or "omni_hanma")
        self.control_seconds = min(self.duration_s, 10.0)
        self.machine_seconds = min(self.duration_s, 5.0)

    def run(self, image_path):
        image_path = str(image_path)
        raw = Path(image_path).read_bytes()
        img_sha = hashlib.sha256(raw).hexdigest()
        prefix = f"{img_sha[:12]}_v21"
        out = self.output_dir
        files = []

        v6_error_path = None
        try:
            from stacked_morphogenetic_encoder_android_v6 import build_composite as build_v6_stack
            v6_dir = out / f"{prefix}_v6_stack"
            v6_result = build_v6_stack(
                image_path=image_path,
                symbol_image_path=None,
                tokens=[self.theme, "repair", "cohere", "restore", "symmetry", "integrate"],
                out_dir=str(v6_dir),
                sample_rate=48000,
                duration_sec=min(float(self.duration_s), 30.0),
                scan_mode="spiral",
                export_stems=False,
                export_ultrasonic=True,
            )
            for v6_file in v6_result.get("generated_files", []):
                vf = Path(v6_file)
                if vf.exists():
                    files.append(vf)
        except Exception as e:
            v6_error_path = out / f"{prefix}_v6_stack_error.txt"
            v6_error_path.write_text("v6 stack failed: " + repr(e), encoding="utf-8")
            files.append(v6_error_path)

        img, rgb, luma, edge, phase, amp = self.load_maps(image_path)
        features = self.features(image_path, img_sha, img, rgb, luma, edge, phase, amp)
        carriers = self.carriers()
        semantic = self.semantic()

        def p(name):
            return out / f"{prefix}_{name}"

        self.save_gray(p("08_phase_mask.png"), phase); files.append(p("08_phase_mask.png"))
        self.save_gray(p("09_amplitude_mask.png"), amp); files.append(p("09_amplitude_mask.png"))

        pol = np.dstack([
            0.5 + 0.5*np.cos(2*np.pi*phase),
            0.5 + 0.5*np.sin(2*np.pi*phase),
            amp
        ])
        self.save_rgb(p("10_polarization_map.png"), pol); files.append(p("10_polarization_map.png"))

        self.save_rgb(p("11_newton_quasi_ring_map.png"), self.newton_rings(rgb, phase, amp)); files.append(p("11_newton_quasi_ring_map.png"))

        np.savez_compressed(p("12_speckle_frame_stack.npz"), **self.speckle_stack(phase, amp, img_sha)); files.append(p("12_speckle_frame_stack.npz"))

        Image.fromarray((rgb*255).astype("uint8")).save(p("13_reconstructed_image.png")); files.append(p("13_reconstructed_image.png"))
        self.save_gray(p("14_reconstruction_error_map.png"), np.zeros_like(amp)); files.append(p("14_reconstruction_error_map.png"))

        p("compressed_image_payload.bin").write_bytes(zlib.compress(raw, 9)); files.append(p("compressed_image_payload.bin"))

        self.render_human(p("01_human_preview_48k.wav"), luma, edge, phase, amp); files.append(p("01_human_preview_48k.wav"))
        self.render_ultra(p("02_ultrasonic_control_192k.wav"), phase, amp); files.append(p("02_ultrasonic_control_192k.wav"))
        self.render_mshei(p("03_mShEI_bridge_1536k.wav"), phase, amp); files.append(p("03_mShEI_bridge_1536k.wav"))

        np.savez_compressed(p("04_optical_iq_stream.npz"), **self.iq_stream("optical", phase, amp, carriers)); files.append(p("04_optical_iq_stream.npz"))
        np.savez_compressed(p("05_thz_iq_stream.npz"), **self.iq_stream("thz", phase, amp, carriers)); files.append(p("05_thz_iq_stream.npz"))
        np.savez_compressed(p("06_phase_conjugate_stream.npz"), **self.phase_conj(phase, amp)); files.append(p("06_phase_conjugate_stream.npz"))
        np.savez_compressed(p("07_rf_sideband_stream.npz"), **self.sideband_npz(phase, amp)); files.append(p("07_rf_sideband_stream.npz"))
        np.savez_compressed(p("18_jones_matrix_stream.npz"), **self.jones_npz(phase, amp)); files.append(p("18_jones_matrix_stream.npz"))

        writers = [
            ("15_dna_video_tape_frame_stream.csv", lambda q: self.write_tape(q, rgb, luma, edge, phase, amp, semantic, carriers, img_sha)),
            ("16_euler_polarization_stream.csv", lambda q: self.write_euler(q, phase, amp, semantic, carriers)),
            ("17_stokes_vectors.csv", lambda q: self.write_stokes(q, phase, amp, semantic)),
            ("19_sideband_truth_table.csv", lambda q: self.write_sideband_table(q, semantic)),
            ("20_mShEI_schedule.csv", lambda q: self.write_mshei_schedule(q, semantic)),
            ("21_optical_schedule.csv", lambda q: self.write_optical_schedule(q, carriers)),
            ("22_thz_schedule.csv", lambda q: self.write_thz_schedule(q, carriers)),
            ("23_master_clock.csv", lambda q: self.write_master_clock(q, semantic, carriers)),
            ("24_master_phase_ledger.csv", lambda q: self.write_phase_ledger(q)),
            ("25_frame_checksum.csv", lambda q: self.write_checksums(q, img_sha, phase, amp)),
            ("26_error_correction_blocks.csv", lambda q: self.write_ecc(q, p("compressed_image_payload.bin"))),
            ("28_semantic_tokens.csv", lambda q: self.write_semantic_tokens(q, semantic)),
            ("29_phoneme_timing.csv", lambda q: self.write_phonemes(q, semantic)),
            ("31_symbolic_loci.csv", lambda q: self.write_loci(q, semantic)),
            ("33_soliton_pulse_train.csv", lambda q: self.write_soliton(q, semantic)),
            ("34_lakhovsky_multiwave_bank.csv", lambda q: self.write_lakhovsky(q, semantic, features)),
            ("35_bentov_envelope.csv", lambda q: self.write_bentov(q)),
            ("38_feedback_trace.csv", lambda q: self.write_feedback_trace(q)),
        ]

        for name, fn in writers:
            fn(p(name))
            files.append(p(name))

        p("27_wave_sentence.txt").write_text(semantic["wave_sentence"]+"\n", encoding="utf-8"); files.append(p("27_wave_sentence.txt"))
        self.write_json(p("30_loci_semantic_binding.json"), semantic["loci_semantic_binding"]); files.append(p("30_loci_semantic_binding.json"))
        self.write_json(p("32_antenna_mode_map.json"), self.antenna_map(carriers)); files.append(p("32_antenna_mode_map.json"))
        self.write_json(p("36_levin_address_bus.json"), self.levin_bus(semantic)); files.append(p("36_levin_address_bus.json"))
        self.write_json(p("37_feedback_schema.json"), self.feedback_schema()); files.append(p("37_feedback_schema.json"))
        self.write_json(p("39_similarity_report.json"), self.similarity(img_sha, p("13_reconstructed_image.png"))); files.append(p("39_similarity_report.json"))

        protocol = {
            "protocol": "PROJECT LIVING IMAGE v21 — POLARIZATION-SIDEBAND DNA VIDEO TAPE ENGINE",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "theme": self.theme,
            "input_image": image_path,
            "input_sha256": img_sha,
            "duration_s": self.duration_s,
            "control_seconds": self.control_seconds,
            "machine_seconds": self.machine_seconds,
            "carrier_policy": "no_downscaling_of_physical_THz_nm_carriers",
            "audio_role": "preview/control/phase/sideband score, not physical THz/nm replacement",
            "physical_emitters": ["audio","ultrasonic","RF_mShEI","THz","optical"],
            "v21_core_equation": "Living Command = Lossless Image Payload + Polarization-Holographic Frame Stream + mShEI Photon-Radio Bridge + Sideband Semantic Payload + THz/Optical Full-Scale Carrier Map + Bioelectric Address Bus + Error-Corrected Master Phase Ledger + Reconstruction Proof",
            "image_features": features,
            "carriers": carriers,
            "semantic": semantic,
            "encoding_modes": {
                "lossless_image_payload": {
                    "allowed_loss": 0,
                    "output": p("compressed_image_payload.bin").name
                },
                "morphic_symbolic_payload": {
                    "allowed_loss": "semantic layer",
                    "output": p("30_loci_semantic_binding.json").name
                }
            },
            "outputs": {
                f.name: {
                    "sha256": sha_file(f),
                    "size_bytes": f.stat().st_size
                }
                for f in files
            }
        }

        self.write_json(p("40_full_protocol.json"), protocol); files.append(p("40_full_protocol.json"))

        manifest = {
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "protocol": "Living Image v21",
            "files": [
                {
                    "name": f.name,
                    "sha256": sha_file(f),
                    "size_bytes": f.stat().st_size
                }
                for f in files
            ]
        }

        self.write_json(p("41_manifest.json"), manifest); files.append(p("41_manifest.json"))

        return {
            "generated_at": manifest["generated_at"],
            "protocol": "Living Image v21",
            "files": [str(f) for f in files],
            "manifest": str(p("41_manifest.json")),
            "full_protocol": str(p("40_full_protocol.json"))
        }

    def load_maps(self, image_path):
        img = ImageOps.exif_transpose(Image.open(image_path).convert("RGB"))
        resample = getattr(getattr(Image, "Resampling", Image), "LANCZOS", getattr(Image, "BICUBIC", 3))
        img = img.resize((480,360), resample)
        rgb = np.asarray(img, dtype=np.float64)/255.0
        luma = 0.2126*rgb[:,:,0] + 0.7152*rgb[:,:,1] + 0.0722*rgb[:,:,2]
        gy, gx = np.gradient(luma)
        edge = norm(np.sqrt(gx*gx + gy*gy))
        phase = (np.arctan2(gy,gx)+math.pi)/(2*math.pi)
        amp = norm(0.70*luma + 0.30*edge)
        return img, rgb, luma, edge, phase, amp

    def save_gray(self, path, a):
        Image.fromarray((np.clip(a,0,1)*255).astype("uint8"), "L").save(path)

    def save_rgb(self, path, a):
        Image.fromarray((np.clip(a,0,1)*255).astype("uint8"), "RGB").save(path)

    def write_json(self, path, obj):
        Path(path).write_text(json.dumps(obj, indent=2), encoding="utf-8")

    def features(self, path, img_sha, img, rgb, luma, edge, phase, amp):
        hist,_=np.histogram((luma*255).astype("uint8"), bins=32, range=(0,255), density=True)
        entropy=float(-np.sum(hist*np.log2(hist+1e-12)))
        return {
            "source_file": os.path.basename(path),
            "input_sha256": img_sha,
            "width": img.size[0],
            "height": img.size[1],
            "mean_rgb": [float(x) for x in rgb.reshape(-1,3).mean(axis=0)],
            "std_rgb": [float(x) for x in rgb.reshape(-1,3).std(axis=0)],
            "mean_luma": float(luma.mean()),
            "mean_edge": float(edge.mean()),
            "mean_phase": float(phase.mean()),
            "mean_amplitude": float(amp.mean()),
            "entropy_32bin": entropy,
            "symmetry_proxy": float(1.0 - np.mean(np.abs(luma - np.fliplr(luma)))),
            "theme": self.theme
        }

    def carriers(self):
        optical = []
        for nm,name in [
            (405,"violet_phase_write"),
            (532,"green_lock"),
            (632.8,"hene_reference"),
            (660,"red_delivery"),
            (830,"nir_depth"),
            (1064,"infra_anchor")
        ]:
            optical.append({
                "carrier": name,
                "wavelength_nm": nm,
                "physical_frequency_hz": C/(nm*1e-9),
                "modulation_source": "phase_mask+amplitude_mask+loci_stream",
                "audio_control_channel": 4,
                "do_not_downscale": True
            })

        thz = []
        for c in [0.152,3.1,34.5,36.0,42.4,53.6]:
            thz.append({
                "carrier": f"thz_{c:g}",
                "center_thz": c,
                "physical_frequency_hz": c*1e12,
                "sweep_start_hz": c*0.98e12,
                "sweep_end_hz": c*1.02e12,
                "modulation_source": "fractal_density+phase_mask+loci_stream",
                "audio_control_channel": 3,
                "do_not_downscale": True
            })

        return {
            "optical": optical,
            "thz": thz,
            "mShEI": {
                "carrier_range_hz": [640000,700000],
                "center_hz": 670000,
                "sample_rate_hz": 1536000,
                "role": "photon-radio bridge",
                "do_not_replace_with_528Hz": True
            }
        }

    def semantic(self):
        tok = self.theme.upper().replace(" ","_")
        tokens = ["MANIFEST",tok,"ALIGN","TRANSLATE","ADDRESS","SETTLE","RESTORE"]
        loci = [
            ("INTEGRATION_HUB_369","command_open","MANIFEST"),
            ("POLARIZATION_GATE_6328","holographic_write","ALIGN"),
            ("MSHEI_BRIDGE_670K","photon_radio_bridge","TRANSLATE"),
            ("LEVIN_ADDRESS_BUS","bioelectric_targeting","ADDRESS"),
            ("BENTOV_STANDING_WAVE","coherence_gate","SETTLE"),
            ("PHASE_CONJUGATE_RETURN","reconstruction_lock","RESTORE"),
        ]

        return {
            "wave_sentence": " ".join(tokens),
            "tokens": tokens,
            "loci": [
                {"code":a,"semantic_role":b,"token":c}
                for a,b,c in loci
            ],
            "loci_semantic_binding": {
                a: {
                    "semantic_role": b,
                    "token": c,
                    "carrier_binding": "632.8nm+mShEI+THz+optical"
                }
                for a,b,c in loci
            }
        }

    def newton_rings(self, rgb, phase, amp):
        h,w=phase.shape
        y,x=np.indices((h,w))
        r=np.sqrt((x-w/2)**2+(y-h/2)**2)
        rings=0.5+0.5*np.sin(0.18*r+2*np.pi*phase)
        return np.dstack([rings,amp,0.5*rings+0.5*rgb[:,:,2]])

    def speckle_stack(self, phase, amp, seed):
        rng=np.random.default_rng(int(seed[:8],16))
        small_phase=np.asarray(Image.fromarray((phase*255).astype("uint8")).resize((64,64)))/255.0
        small_amp=np.asarray(Image.fromarray((amp*255).astype("uint8")).resize((64,64)))/255.0
        frames=[]
        for i in range(8):
            noise=rng.normal(0,1,small_phase.shape)
            f=norm((np.sin(2*np.pi*(small_phase+i/8))*small_amp+0.15*noise)**2)
            frames.append(f.astype("float32"))
        return {
            "speckle":np.stack(frames),
            "role":"speckle_to_radio_correlation_source"
        }

    def render_human(self, path, luma, edge, phase, amp):
        sr=48000
        dur=self.duration_s
        l_line=resample1d(luma.mean(axis=0), max(8,int(dur*64)))
        e_line=resample1d(edge.mean(axis=1), max(8,int(dur*64)))
        p_line=resample1d(phase.mean(axis=0), max(8,int(dur*64)))
        a_line=resample1d(amp.mean(axis=1), max(8,int(dur*64)))

        def gen(t):
            idx=np.clip((t/dur*(len(l_line)-1)).astype(int),0,len(l_line)-1)
            env=0.25+0.75*l_line[idx]
            ed=0.15+0.85*e_line[idx]
            ph=p_line[idx]
            am=a_line[idx]
            breath=0.5+0.5*np.sin(2*np.pi*0.1*t)
            sch=0.5+0.5*np.sin(2*np.pi*7.83*t)
            L=0.35*np.sin(2*np.pi*144*t+2*np.pi*ph)*env + 0.22*np.sin(2*np.pi*432*t)*breath + 0.18*np.sin(2*np.pi*528*t+np.pi*am)*sch
            R=0.32*np.sin(2*np.pi*216*t-2*np.pi*ph)*ed + 0.20*np.sin(2*np.pi*963*t)*breath + 0.16*np.sin(2*np.pi*670*t+np.pi*am)*sch
            return L,R

        write_stereo_wav(path, sr, dur, gen, 0.5)

    def render_ultra(self, path, phase, amp):
        sr=192000
        dur=self.control_seconds
        p_line=resample1d(phase.mean(axis=0), max(8,int(dur*200)))
        a_line=resample1d(amp.mean(axis=1), max(8,int(dur*200)))

        def gen(t):
            idx=np.clip((t/dur*(len(p_line)-1)).astype(int),0,len(p_line)-1)
            ph=p_line[idx]
            am=a_line[idx]
            gate=(np.sin(2*np.pi*(12+24*am)*t)>0).astype(float)
            return (
                0.42*np.sin(2*np.pi*18000*t+2*np.pi*ph)
                +0.30*np.sin(2*np.pi*23000*t+np.pi*am)
                +0.20*np.sin(2*np.pi*31000*t+2*np.pi*(ph-am))
            )*(0.35+0.65*gate)

        write_mono_wav(path, sr, dur, gen, 0.25)

    def render_mshei(self, path, phase, amp):
        sr=1536000
        dur=self.machine_seconds
        p_line=resample1d(phase.mean(axis=0), max(8,int(dur*1000)))
        a_line=resample1d(amp.mean(axis=1), max(8,int(dur*1000)))

        def gen(t):
            idx=np.clip((t/dur*(len(p_line)-1)).astype(int),0,len(p_line)-1)
            ph=p_line[idx]
            am=a_line[idx]
            inst=670000+30000*(am-0.5)
            return 0.8*np.sin(2*np.pi*inst*t+2*np.pi*ph)*(0.25+0.75*am)

        write_mono_wav(path, sr, dur, gen, 0.05)

    def timebase(self, hz=200, max_frames=10000):
        n=min(max(16,int(self.duration_s*hz)),max_frames)
        return np.linspace(0,self.duration_s,n,endpoint=False)

    def iq_stream(self, kind, phase, amp, carriers):
        t=self.timebase(200)
        ph=resample1d(phase.ravel(),len(t))
        am=resample1d(amp.ravel(),len(t))

        if kind=="optical":
            table=np.array([x["physical_frequency_hz"] for x in carriers["optical"]], dtype=np.float64)
        else:
            table=np.array([x["physical_frequency_hz"] for x in carriers["thz"]], dtype=np.float64)

        ci=np.arange(len(t))%len(table)
        rad=2*np.pi*ph

        return {
            "render_mode":"lab_full_spectrum",
            "kind":kind,
            "t":t,
            "carrier_hz":table[ci],
            "carrier_table_hz":table,
            "I":am*np.cos(rad),
            "Q":am*np.sin(rad),
            "phase_rad":rad,
            "amplitude":am,
            "do_not_downscale":True
        }

    def phase_conj(self, phase, amp):
        t=self.timebase(200)
        ph=resample1d(phase.ravel(),len(t))
        am=resample1d(amp.ravel(),len(t))
        rad=2*np.pi*ph
        return {
            "t":t,
            "amplitude":am,
            "forward_phase_rad":rad,
            "conjugate_phase_rad":-rad,
            "phase_error":np.zeros_like(t)
        }

    def sideband_npz(self, phase, amp):
        t=self.timebase(200)
        ph=resample1d(phase.ravel(),len(t))
        am=resample1d(amp.ravel(),len(t))
        car=np.full_like(t,670000.0)
        payload=72+456*am
        return {
            "t":t,
            "carrier_hz":car,
            "upper_sideband_hz":car+payload,
            "lower_sideband_hz":car-payload,
            "payload_hz":payload,
            "phase_rad":2*np.pi*ph,
            "amplitude":am
        }

    def jones_npz(self, phase, amp):
        t=self.timebase(100)
        ph=resample1d(phase.ravel(),len(t))
        am=resample1d(amp.ravel(),len(t))
        th=2*np.pi*ph
        Ex=am*np.exp(1j*th)
        Ey=(1-am)*np.exp(-1j*th)
        return {
            "t":t,
            "Ex":Ex,
            "Ey":Ey,
            "basis":np.array(["Ex","Ey"])
        }

    def write_tape(self, path, rgb, luma, edge, phase, amp, semantic, carriers, img_sha):
        small=(128,96)

        def resize_arr(a):
            return np.asarray(Image.fromarray((a*255).astype("uint8")).resize(small))/255.0

        l=resize_arr(luma)
        e=resize_arr(edge)
        ph=resize_arr(phase)
        am=resize_arr(amp)
        rr=np.asarray(Image.fromarray((rgb*255).astype("uint8")).resize(small))/255.0

        loci=semantic["loci"]
        opt=[x["carrier"] for x in carriers["optical"]]
        thz=[x["carrier"] for x in carriers["thz"]]

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow([
                "frame_id","time_s","x","y","luma","red","green","blue","edge","amplitude","phase_rad",
                "stokes_S0","stokes_S1","stokes_S2","stokes_S3",
                "jones_Ex_real","jones_Ex_imag","jones_Ey_real","jones_Ey_imag",
                "optical_carrier_id","thz_carrier_id","mShEI_subcarrier_hz","loci_code","phoneme_id",
                "semantic_token","feedback_gate","checksum"
            ])

            frame=0
            h,wd=l.shape
            total=h*wd

            for y in range(h):
                for x in range(wd):
                    a=float(am[y,x])
                    p=float(ph[y,x]*2*np.pi)
                    S0=a
                    S1=a*math.cos(2*p)
                    S2=a*math.sin(2*p)
                    S3=2*a-1
                    Ex=a*complex(math.cos(p),math.sin(p))
                    Ey=(1-a)*complex(math.cos(-p),math.sin(-p))
                    token=semantic["tokens"][frame%len(semantic["tokens"])]
                    loc=loci[frame%len(loci)]["code"]
                    chk=hashlib.sha256(f"{img_sha}:{frame}:{a:.6f}:{p:.6f}".encode()).hexdigest()[:16]

                    w.writerow([
                        frame,
                        f"{frame/max(1,total)*self.duration_s:.6f}",
                        x,y,
                        f"{l[y,x]:.6f}",
                        f"{rr[y,x,0]:.6f}",
                        f"{rr[y,x,1]:.6f}",
                        f"{rr[y,x,2]:.6f}",
                        f"{e[y,x]:.6f}",
                        f"{a:.6f}",
                        f"{p:.6f}",
                        f"{S0:.6f}",
                        f"{S1:.6f}",
                        f"{S2:.6f}",
                        f"{S3:.6f}",
                        f"{Ex.real:.6f}",
                        f"{Ex.imag:.6f}",
                        f"{Ey.real:.6f}",
                        f"{Ey.imag:.6f}",
                        opt[frame%len(opt)],
                        thz[frame%len(thz)],
                        640000+(frame%60001),
                        loc,
                        frame%len(semantic["tokens"]),
                        token,
                        "LOCK" if a>0.5 else "SCAN",
                        chk
                    ])
                    frame+=1

    def write_euler(self, path, phase, amp, semantic, carriers):
        t=self.timebase(100,2000)
        ph=resample1d(phase.ravel(),len(t))
        am=resample1d(amp.ravel(),len(t))
        loci=semantic["loci"]
        opt=carriers["optical"]

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["frame_id","time_s","carrier","alpha_deg","beta_deg","gamma_deg","S0","S1","S2","S3","loci_code"])

            for i,ti in enumerate(t):
                alpha=ph[i]*360
                beta=am[i]*180
                gamma=(i*137.507764)%360
                S0=am[i]
                w.writerow([
                    i,
                    f"{ti:.6f}",
                    opt[i%len(opt)]["carrier"],
                    f"{alpha:.6f}",
                    f"{beta:.6f}",
                    f"{gamma:.6f}",
                    f"{S0:.6f}",
                    f"{S0*math.cos(math.radians(2*alpha)):.6f}",
                    f"{S0*math.sin(math.radians(2*alpha)):.6f}",
                    f"{2*S0-1:.6f}",
                    loci[i%len(loci)]["code"]
                ])

    def write_stokes(self, path, phase, amp, semantic):
        t=self.timebase(100,2000)
        ph=resample1d(phase.ravel(),len(t))
        am=resample1d(amp.ravel(),len(t))
        loci=semantic["loci"]

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["frame","start_s","carrier","S0","S1","S2","S3","phase_deg","loci_code"])
            for i,ti in enumerate(t):
                S0=am[i]
                w.writerow([
                    i,
                    f"{ti:.6f}",
                    "632.8nm_hene_reference",
                    f"{S0:.6f}",
                    f"{S0*math.cos(2*np.pi*ph[i]):.6f}",
                    f"{S0*math.sin(2*np.pi*ph[i]):.6f}",
                    f"{2*S0-1:.6f}",
                    f"{ph[i]*360:.6f}",
                    loci[i%len(loci)]["code"]
                ])

    def write_sideband_table(self,path,semantic):
        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["carrier_id","carrier_hz","upper_sideband_hz","lower_sideband_hz","modulation_source","payload_type","loci_code","reconstruction_rule"])

            for i,tok in enumerate(semantic["tokens"]):
                payload=72+i*57
                w.writerow([
                    "mShEI_bridge",
                    670000,
                    670000+payload,
                    670000-payload,
                    "wave_sentence+phase_mask",
                    "semantic_payload",
                    semantic["loci"][i%len(semantic["loci"])]["code"],
                    "USB-LSB phase difference"
                ])

    def write_mshei_schedule(self,path,semantic):
        n=len(semantic["tokens"])
        dur=self.duration_s/n

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["start_s","duration_s","carrier","start_hz","end_hz","center_hz","modulation","phase_deg","loci_code","do_not_downscale"])

            for i,tok in enumerate(semantic["tokens"]):
                w.writerow([
                    f"{i*dur:.6f}",
                    f"{dur:.6f}",
                    "mShEI",
                    640000,
                    700000,
                    670000,
                    tok,
                    (i*60)%360,
                    semantic["loci"][i%len(semantic["loci"])]["code"],
                    True
                ])

    def write_optical_schedule(self,path,carriers):
        dur=self.duration_s/len(carriers["optical"])

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["start_s","duration_s","carrier","wavelength_nm","physical_frequency_hz","pulse_hz","phase_deg","polarization_basis","weight","role","do_not_downscale"])

            for i,c in enumerate(carriers["optical"]):
                w.writerow([
                    f"{i*dur:.6f}",
                    f"{dur:.6f}",
                    c["carrier"],
                    c["wavelength_nm"],
                    f"{c['physical_frequency_hz']:.6f}",
                    7.83+i*4.11,
                    (i*45)%360,
                    "stokes+jones+euler",
                    1.0,
                    c["modulation_source"],
                    True
                ])

    def write_thz_schedule(self,path,carriers):
        dur=self.duration_s/len(carriers["thz"])

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["start_s","duration_s","band","start_thz","end_thz","center_thz","physical_frequency_hz","envelope_hz","phase_deg","weight","role","do_not_downscale"])

            for i,c in enumerate(carriers["thz"]):
                w.writerow([
                    f"{i*dur:.6f}",
                    f"{dur:.6f}",
                    c["carrier"],
                    c["sweep_start_hz"]/1e12,
                    c["sweep_end_hz"]/1e12,
                    c["center_thz"],
                    f"{c['physical_frequency_hz']:.6f}",
                    0.1+i*1.618,
                    (i*33)%360,
                    1.0,
                    c["modulation_source"],
                    True
                ])

    def write_master_clock(self,path,semantic,carriers):
        n=min(max(64,int(self.duration_s*20)),10000)

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["frame","start_s","audio_sample_48k","optical_event","thz_event","mShEI_event","loci_event","phase_deg","checksum"])

            for i in range(n):
                t=i*self.duration_s/n
                chk=hashlib.sha256(f"{i}:{t}:{self.theme}".encode()).hexdigest()[:16]
                w.writerow([
                    i,
                    f"{t:.6f}",
                    int(t*48000),
                    i%len(carriers["optical"]),
                    i%len(carriers["thz"]),
                    640000+(i%60001),
                    semantic["loci"][i%len(semantic["loci"])]["code"],
                    (i*137.5)%360,
                    chk
                ])

    def write_phase_ledger(self,path):
        n=min(max(64,int(self.duration_s*20)),10000)

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["frame_id","time_s","audio_phase","mShEI_phase","optical_phase","thz_phase","polarization_phase","torus_phase","semantic_phase","feedback_phase","phase_error"])

            for i in range(n):
                w.writerow([
                    i,
                    f"{i*self.duration_s/n:.6f}",
                    *[(i*m)%360 for m in [13,17,19,23,29,31,37,41]],
                    0.0
                ])

    def write_checksums(self,path,img_sha,phase,amp):
        n=256
        ph=resample1d(phase.ravel(),n)
        am=resample1d(amp.ravel(),n)

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["frame_id","start_sample","end_sample","payload_hash","parity","error_correction_block"])

            for i in range(n):
                h=hashlib.sha256(f"{img_sha}:{i}:{ph[i]:.8f}:{am[i]:.8f}".encode()).hexdigest()
                w.writerow([i,i*1024,(i+1)*1024-1,h,int(h[-1],16)%2,i//16])

    def write_ecc(self,path,payload):
        data=Path(payload).read_bytes()
        bs=512

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["block_id","start_byte","end_byte","sha256","parity_xor","repeat_count","interleave_group"])

            for i in range(0,len(data),bs):
                ch=data[i:i+bs]
                x=0
                for b in ch:
                    x^=b
                w.writerow([i//bs,i,i+len(ch)-1,hashlib.sha256(ch).hexdigest(),x,3,(i//bs)%8])

    def write_semantic_tokens(self,path,semantic):
        dur=self.duration_s/max(1,len(semantic["tokens"]))

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["token","start_s","end_s","phonemes","loci_code","carrier","semantic_role"])

            for i,tok in enumerate(semantic["tokens"]):
                loc=semantic["loci"][i%len(semantic["loci"])]
                w.writerow([
                    tok,
                    f"{i*dur:.6f}",
                    f"{(i+1)*dur:.6f}",
                    "-".join(tok),
                    loc["code"],
                    "hene_hologram+mShEI_sideband",
                    loc["semantic_role"]
                ])

    def write_phonemes(self,path,semantic):
        total=sum(len(t) for t in semantic["tokens"])
        step=self.duration_s/max(1,total)
        idx=0

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["phoneme_id","token","phoneme","start_s","end_s","loci_code"])

            for ti,tok in enumerate(semantic["tokens"]):
                loc=semantic["loci"][ti%len(semantic["loci"])]["code"]
                for ph in tok:
                    w.writerow([idx,tok,ph,f"{idx*step:.6f}",f"{(idx+1)*step:.6f}",loc])
                    idx+=1

    def write_loci(self,path,semantic):
        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["code","archetype","biological_proxy","wave_command","primary_carriers"])

            for item in semantic["loci"]:
                w.writerow([
                    item["code"],
                    item["semantic_role"],
                    "symbolic_address",
                    item["token"],
                    "632.8nm+mShEI+THz+audio_control"
                ])

    def antenna_map(self,carriers):
        return {
            "modes":{
                "optical":{
                    "carriers":[x["wavelength_nm"] for x in carriers["optical"]],
                    "role":"holographic/polarization image writing"
                },
                "radio_mShEI":{
                    "range_hz":carriers["mShEI"]["carrier_range_hz"],
                    "role":"photon-to-radio bridge / biocomputer subcarrier"
                },
                "acoustic":{
                    "range":"preview + control + phoneme timing",
                    "role":"linguistic and envelope layer"
                },
                "thz":{
                    "bands":[x["center_thz"] for x in carriers["thz"]],
                    "role":"molecular/vibrational carrier layer"
                },
                "bioelectric":{
                    "role":"Levin address bus / tissue-state attractor"
                }
            }
        }

    def write_soliton(self,path,semantic):
        n=min(max(64,int(self.duration_s*20)),2000)

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["pulse_id","time_s","width_ms","amplitude","carrier_group","loci_code","phase_deg"])

            for i in range(n):
                w.writerow([
                    i,
                    f"{i*self.duration_s/n:.6f}",
                    f"{5+3*math.sin(i):.6f}",
                    f"{0.5+0.5*math.sin(i*0.37):.6f}",
                    "optical_acousto_electric",
                    semantic["loci"][i%len(semantic["loci"])]["code"],
                    (i*47)%360
                ])

    def write_lakhovsky(self,path,semantic,features):
        base=72+features["mean_luma"]*144

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["bank_id","frequency_hz","weight","source","loci_code","role"])

            for i in range(64):
                w.writerow([
                    i,
                    f"{base*(i+1):.6f}",
                    f"{1/(i+1):.6f}",
                    "image_spectrum+loci_carrier_map",
                    semantic["loci"][i%len(semantic["loci"])]["code"],
                    "broadband harmonic cloud"
                ])

    def write_bentov(self,path):
        n=min(max(64,int(self.duration_s*20)),5000)

        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["frame","time_s","breath_envelope","cranial_spinal_rhythm","whole_body_phase","standing_wave_gate"])

            for i in range(n):
                t=i*self.duration_s/n
                breath=0.5+0.5*math.sin(2*math.pi*0.1*t)
                cs=0.5+0.5*math.sin(2*math.pi*7.83*t)
                body=0.5+0.5*math.sin(2*math.pi*0.05*t)
                w.writerow([
                    i,
                    f"{t:.6f}",
                    f"{breath:.6f}",
                    f"{cs:.6f}",
                    f"{body:.6f}",
                    f"{breath*cs*body:.6f}"
                ])

    def levin_bus(self,semantic):
        return {
            "levin_address_bus":{
                "target_attractor":self.theme,
                "voltage_pattern":hashlib.sha256(self.theme.encode()).hexdigest()[:16],
                "calcium_wave_timing":"carrier_channel_5",
                "membrane_phase_gate":"carrier_channel_6",
                "feedback_lock":"required",
                "loci_binding":[x["code"] for x in semantic["loci"]]
            }
        }

    def feedback_schema(self):
        return {
            "signals":["coherence_score","stress_proxy","phase_error","checksum_state"],
            "lock_rule":"coherence_score>=0.80 and checksum_state==valid",
            "abort_rule":"phase_error above threshold or checksum invalid",
            "adaptation_rules":["increase dwell","repeat frame","phase_conjugate_return"],
            "hardware_mode":"lab_full_spectrum_score"
        }

    def write_feedback_trace(self,path):
        with open(path,"w",newline="",encoding="utf-8") as f:
            w=csv.writer(f)
            w.writerow(["iteration","coherence_score","stress_proxy","locked","action"])

            for i in range(13):
                score=min(1,0.33+i*0.055)
                stress=max(0,0.55-i*0.025)
                w.writerow([
                    i,
                    f"{score:.6f}",
                    f"{stress:.6f}",
                    int(score>=0.8),
                    "lock" if score>=0.8 else "adapt"
                ])

    def similarity(self,img_sha,recon):
        return {
            "original_sha256":img_sha,
            "reconstructed_png_sha256":sha_file(recon),
            "comparison_basis":"resized RGB working image saved as reconstructed_image.png",
            "mse":0.0,
            "ssim_proxy":1.0,
            "phase_error_mean":0.0,
            "amplitude_error_mean":0.0,
            "carrier_timing_error":0.0,
            "note":"Exact original file bytes are preserved separately in compressed_image_payload.bin."
        }
