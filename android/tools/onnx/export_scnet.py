"""Export SCNet (ZFTurbo's checkpoints) to ONNX without its STFT, checking each step.

The one published SCNet ONNX export we tried separates nothing when fed a Hann-windowed
spectrogram — because SCNet calls torch.stft *without* a window, which PyTorch treats as
rectangular, and nothing in its config says so. This script exports from the official
weights and proves every step against the original PyTorch forward:

1. The time-axis rfft/irfft in FeatureConversion becomes a DFT matmul for the fixed frame
   count (ONNX-exportable, same arithmetic) — checked against torch.fft.
2. The exported core (spectrogram in, source spectrograms out) plus a NumPy host
   STFT/iSTFT must reproduce the original waveform-to-waveform forward.
3. The ONNX file must reproduce the PyTorch core, and separate a real MUSDB18 excerpt
   exactly as well.

    pip install torch --index-url https://download.pytorch.org/whl/cpu
    pip install onnx onnxruntime pyyaml einops av numpy
    git clone https://github.com/ZFTurbo/Music-Source-Separation-Training msst
    R=https://github.com/ZFTurbo/Music-Source-Separation-Training/releases/download
    curl -Lo small.ckpt $R/v.1.0.6/scnet_checkpoint_musdb18.ckpt
    curl -Lo small.yaml $R/v.1.0.6/config_musdb18_scnet.yaml
    python3 export_scnet.py msst small.yaml small.ckpt scnet_small.onnx <musdb test dir>
"""
import sys, math, yaml, numpy as np, torch, onnxruntime as ort

MSST, CONFIG, CKPT, OUT, MUSDB = sys.argv[1:6]
sys.path.insert(0, MSST)
from models.scnet.scnet import SCNet
from models.scnet.separation import FeatureConversion

name = CONFIG
yaml.SafeLoader.add_constructor("tag:yaml.org,2002:python/tuple", lambda l, n: tuple(l.construct_sequence(n)))
cfg = yaml.safe_load(open(CONFIG))["model"]
m = SCNet(**cfg).eval()
m.load_state_dict(torch.load(CKPT, map_location="cpu", weights_only=True))
torch.set_grad_enabled(False)

CHUNK, HOP, NFFT = 485100, cfg["hop_size"], cfg["nfft"]
pad = HOP - CHUNK % HOP
if (CHUNK + pad) // HOP % 2 == 0: pad += HOP                 # SCNet's own padding rule
T = 1 + (CHUNK + pad) // HOP
print(f"{name}: params {sum(p.numel() for p in m.parameters())/1e6:.1f} M, chunk pad {pad}, frames {T}")

# --- a real excerpt to test with
import av, glob
trk = sorted(glob.glob(MUSDB.rstrip("/") + "/*.stem.mp4"))[0]
def dec(i):
    c = av.open(trk); s = c.streams.audio[i]
    x = np.concatenate([f.to_ndarray() for f in c.decode(s)], 1).astype(np.float32); c.close(); return x
mix = dec(0); n = mix.shape[1]
chunk = np.zeros((2, CHUNK), np.float32); chunk[:, :n] = mix
ref_wave = m(torch.from_numpy(chunk)[None])[0].numpy()               # original forward (4, 2, CHUNK)

# --- 1. DFT matmul in place of torch.fft over time
def install_dft(model, frames):
    K = frames // 2 + 1
    t = np.arange(frames)[:, None]; k = np.arange(K)[None, :]
    ang = 2 * np.pi * t * k / frames; s = 1 / math.sqrt(frames)
    c = np.full(K, 2.0); c[0] = 1.0; c[-1] = 1.0 if frames % 2 == 0 else 2.0
    mats = {"W_re": np.cos(ang) * s, "W_im": -np.sin(ang) * s,
            "iW_re": (c[:, None] * np.cos(ang.T)) * s, "iW_im": (-c[:, None] * np.sin(ang.T)) * s}
    for mod in model.modules():
        if isinstance(mod, FeatureConversion):
            for k2, v in mats.items(): mod.register_buffer(k2, torch.tensor(v, dtype=torch.float32), persistent=False)
            def fwd(self, x):
                x = x.float()
                if self.inverse:
                    h = self.channels // 2
                    return torch.matmul(x[:, :h], self.iW_re) + torch.matmul(x[:, h:], self.iW_im)
                return torch.cat([torch.matmul(x, self.W_re), torch.matmul(x, self.W_im)], dim=1)
            mod.forward = fwd.__get__(mod)
install_dft(m, T)
dft_wave = m(torch.from_numpy(chunk)[None])[0].numpy()
print(f"  1. DFT matmul vs torch.fft          max abs diff {np.abs(dft_wave - ref_wave).max():.2e}")

# --- 2. core + host STFT reproduces the original forward
class Core(torch.nn.Module):
    def __init__(self, net): super().__init__(); self.net = net
    def forward(self, x):                                            # (B, 4, F, T), normalised STFT
        B, _, Fr, Tn = x.shape
        net, skips, lens, olens = self.net, [], [], []
        for sd in net.encoder:
            x, skip, l, ol = sd(x); skips.append(skip); lens.append(l); olens.append(ol)
        x = net.separation_net(x)
        for fusion, su in net.decoder:
            x = fusion(x, skips.pop()); x = su(x, lens.pop(), olens.pop())
        return x.view(B, net.dims[0], -1, Fr, Tn)                    # (B, src, 2*ch+ri, F, T)
core = Core(m).eval()

# SCNet calls torch.stft without a window argument, so the window is rectangular.
WIN = np.ones(NFFT); NORM = math.sqrt(NFFT)
def stft(x):
    p = NFFT // 2; xp = np.pad(x, (p, p), mode="reflect"); fr = 1 + len(x) // HOP
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(fr)[:, None]
    return np.fft.rfft(xp[idx] * WIN, axis=1) / NORM
def istft(S, length):
    p = NFFT // 2; fr = S.shape[0]; tot = (fr - 1) * HOP + NFFT; acc = np.zeros(tot); nrm = np.zeros(tot)
    y = np.fft.irfft(S * NORM, n=NFFT, axis=1) * WIN
    for t in range(fr): acc[t*HOP:t*HOP+NFFT] += y[t]; nrm[t*HOP:t*HOP+NFFT] += WIN * WIN
    return (acc / np.maximum(nrm, 1e-8))[p:p + length]
def host_in(seg):
    x = np.pad(seg.astype(np.float64), ((0, 0), (0, pad)))
    inp = np.zeros((1, 4, NFFT // 2 + 1, T), np.float32)
    for ch in range(2):
        S = stft(x[ch]); inp[0, 2*ch] = S.T.real; inp[0, 2*ch+1] = S.T.imag
    return inp
def host_out(o):
    w = np.zeros((4, 2, CHUNK))
    for s in range(4):
        for ch in range(2):
            w[s, ch] = istft((o[s, 2*ch] + 1j * o[s, 2*ch+1]).T, CHUNK + pad)[:CHUNK]
    return w
inp = host_in(chunk)
core_spec = core(torch.from_numpy(inp)).numpy()[0]
host_wave = host_out(core_spec)
print(f"  2. host STFT + core vs original      max abs diff {np.abs(host_wave - ref_wave).max():.2e}  (signal peak {np.abs(ref_wave).max():.3f})")

# --- 3. ONNX export and parity
torch.onnx.export(core, torch.from_numpy(inp), OUT, opset_version=17, dynamo=False,
                  input_names=["spectrogram"], output_names=["sources"])
so = ort.SessionOptions(); so.log_severity_level = 3
so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
onnx_spec = ort.InferenceSession(OUT, so, providers=["CPUExecutionProvider"]).run(None, {"spectrogram": inp})[0][0]
print(f"  3. ONNX vs PyTorch core             max abs diff {np.abs(onnx_spec - core_spec).max():.2e}  (spec peak {np.abs(core_spec).max():.3f})")

voc = dec(4)[:, :n]
def sdr(r, e): return 10 * np.log10(np.sum(r**2) / np.sum((r - e)**2))
print(f"  vocals SDR on '{trk.split('/')[-1][:30]}': original {sdr(voc, ref_wave[3][:, :n]):.2f} dB, ONNX pipeline {sdr(voc, host_out(onnx_spec)[3][:, :n]):.2f} dB")
import os; print(f"  wrote {OUT} ({os.path.getsize(OUT)/1e6:.1f} MB)")
