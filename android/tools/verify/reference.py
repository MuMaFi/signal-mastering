"""NumPy reference for the host-STFT RoFormer contract, used to check the Kotlin port.

Mirrors torch.stft/torch.istft with center=True, reflect padding, periodic Hann,
which is what the export's README specifies.
"""
import gc, sys, numpy as np, onnxruntime as ort, os

N_FFT, HOP, FRAMES, CH = 2048, 441, 1101, 2
BINS = N_FFT // 2 + 1
WINDOW = (FRAMES - 1) * HOP
DEMUCS_SEG = 343980
OUT = sys.argv[1]
MODELS = sys.argv[2]

def hann(n):
    return 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)

W = hann(N_FFT).astype(np.float64)

def stft(x):
    p = N_FFT // 2
    xp = np.pad(x, (p, p), mode="reflect")
    out = np.empty((FRAMES, BINS), dtype=np.complex128)
    for t in range(FRAMES):
        out[t] = np.fft.rfft(xp[t * HOP: t * HOP + N_FFT] * W)
    return out

def istft(S):
    p = N_FFT // 2
    total = WINDOW + 2 * p
    acc = np.zeros(total)
    nrm = np.zeros(total)
    for t in range(FRAMES):
        frame = np.fft.irfft(S[t], n=N_FFT)
        acc[t * HOP: t * HOP + N_FFT] += frame * W
        nrm[t * HOP: t * HOP + N_FFT] += W * W
    return (acc / np.maximum(nrm, 1e-8))[p:p + WINDOW]

rng = np.random.default_rng(1234)
n = np.arange(WINDOW)
# Something with real structure: tonal content, a sweep, transients and noise.
sig = np.zeros((CH, WINDOW))
for c in range(CH):
    sig[c] += 0.30 * np.sin(2 * np.pi * (220 + 55 * c) * n / 44100)
    sig[c] += 0.18 * np.sin(2 * np.pi * 1320 * n / 44100 + 0.7 * c)
    sig[c] += 0.12 * np.sin(2 * np.pi * (3000 + 2000 * n / WINDOW) * n / 44100)
    sig[c] += 0.05 * rng.standard_normal(WINDOW)
env = (np.sin(2 * np.pi * 2.0 * n / 44100) ** 2) * 0.5 + 0.5
sig *= env
sig = np.clip(sig, -1.0, 1.0).astype(np.float32)

interleaved = sig.T.reshape(-1).astype(np.float32)
interleaved.tofile(os.path.join(OUT, "test_mix.f32"))

spec = [stft(sig[c].astype(np.float64)) for c in range(CH)]
tensor = np.zeros((1, BINS * CH, FRAMES, 2), dtype=np.float32)
for f in range(BINS):
    for c in range(CH):
        tensor[0, 2 * f + c, :, 0] = spec[c][:, f].real
        tensor[0, 2 * f + c, :, 1] = spec[c][:, f].imag
tensor.reshape(-1).tofile(os.path.join(OUT, "ref_stft.f32"))

# Round-trip sanity for the reference itself.
rt = np.stack([istft(spec[c]) for c in range(CH)])
print("reference stft/istft round-trip max abs err: %.3e" % np.abs(rt - sig).max())

so = ort.SessionOptions()
so.intra_op_num_threads = max(1, os.cpu_count() // 2)
so.log_severity_level = 3
so.enable_mem_pattern = False   # matches the app's session options
so.enable_cpu_mem_arena = True
core = os.path.join(MODELS, "roformer_core_dyn_time.onnx")
if not os.path.isfile(core):
    core = os.path.join(MODELS, "syhft_core_folded_fp16_webgpu.onnx")
print("roformer graph:", os.path.basename(core))
sess = ort.InferenceSession(core, so, providers=["CPUExecutionProvider"])
mask = sess.run(None, {"stft_repr": tensor})[0]
mask.reshape(-1).tofile(os.path.join(OUT, "ref_mask.f32"))

masked = []
for c in range(CH):
    mr = mask[0, 2 * np.arange(BINS) + c, :, 0].T
    mi = mask[0, 2 * np.arange(BINS) + c, :, 1].T
    masked.append(spec[c] * (mr + 1j * mi))
vocals = np.stack([istft(masked[c]) for c in range(CH)]).astype(np.float32)
vocals.T.reshape(-1).tofile(os.path.join(OUT, "ref_vocals.f32"))
instr = (sig - vocals).astype(np.float32)
instr.T.reshape(-1).tofile(os.path.join(OUT, "ref_instrumental.f32"))
print("roformer vocals rms %.6f peak %.6f" % (np.sqrt((vocals**2).mean()), np.abs(vocals).max()))

# Release the RoFormer before opening Demucs: each session peaks around 3.5 GB and
# holding both at once needs more RAM than most machines have.
del sess, mask, masked
gc.collect()

# --- HT-Demucs reference ---
seg = np.ascontiguousarray(sig[:, :DEMUCS_SEG].astype(np.float32))
sess2 = ort.InferenceSession(
    os.path.join(MODELS, "htdemucs_fp16weights.onnx"), so,
    providers=["CPUExecutionProvider"])
stems = sess2.run(None, {sess2.get_inputs()[0].name: seg[None]})[0][0]
stems.astype(np.float32).reshape(-1).tofile(os.path.join(OUT, "ref_demucs_stems.f32"))
for i, nm in enumerate(["drums", "bass", "other", "vocals"]):
    print("  demucs %-7s rms %.6f peak %.6f"
          % (nm, np.sqrt((stems[i] ** 2).mean()), np.abs(stems[i]).max()))
print("REFERENCE OK")
