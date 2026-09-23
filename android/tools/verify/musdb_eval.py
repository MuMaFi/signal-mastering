"""Separation quality on MUSDB18, run through the app's own pipeline.

Answers two questions with ground truth instead of assumptions:

1. Does the pipeline separate real music as well as these models should? (A wrong STFT
   contract or stem index still produces audio — just worse audio.)
2. Does running the RoFormer at 5.5 s chunks instead of the traced 11 s cost quality?
   The shorter chunk is what brings its peak memory from 2.66 GB to 1.77 GB.

Data: the free MUSDB18 7-second excerpts (zenodo.org/records/3270814), test split.
SDR is the MDX-challenge definition, 10·log10(Σref² / Σ(ref−est)²) over the clip,
reported as the median across tracks. Excerpts whose vocals are silent are skipped —
SDR is undefined against a silent reference. Every model runs with the graph optimiser
off, as the app does.

    pip install numpy onnxruntime av
    python3 musdb_eval.py <musdb dir with test/*.stem.mp4> <models dir> [track limit]
"""
import sys, glob, time, numpy as np, onnxruntime as ort, av

MUSDB, M = sys.argv[1], sys.argv[2].rstrip("/") + "/"
LIMIT = int(sys.argv[3]) if len(sys.argv) > 3 else 50
TRACKS = sorted(glob.glob(MUSDB.rstrip("/") + "/test/*.stem.mp4"))[:LIMIT]
SR, NFFT, HOP = 44100, 2048, 441
BINS = NFFT // 2 + 1
WIN = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(NFFT) / NFFT)).astype(np.float64)

def decode(path, stream):
    c = av.open(path)
    s = c.streams.audio[stream]
    parts = [f.to_ndarray() for f in c.decode(s)]
    c.close()
    x = np.concatenate(parts, axis=1).astype(np.float32)
    return x if x.shape[0] == 2 else np.repeat(x[:1], 2, 0)

def stft(x):                       # torch.stft(center=True, reflect), periodic Hann
    p = NFFT // 2
    xp = np.pad(x, (p, p), mode="reflect")
    frames = 1 + len(x) // HOP
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(frames)[:, None]
    return np.fft.rfft(xp[idx] * WIN, axis=1)            # (frames, bins)

def istft(S, length):
    p = NFFT // 2
    frames = S.shape[0]
    total = (frames - 1) * HOP + NFFT
    acc = np.zeros(total); nrm = np.zeros(total)
    y = np.fft.irfft(S, n=NFFT, axis=1) * WIN
    for t in range(frames):
        acc[t * HOP:t * HOP + NFFT] += y[t]
        nrm[t * HOP:t * HOP + NFFT] += WIN * WIN
    return (acc / np.maximum(nrm, 1e-8))[p:p + length]

def trapezoid(window, stride):
    fade = window - stride
    w = np.ones(window)
    if fade > 0:
        r = np.arange(1, fade + 1) / (fade + 1)
        w[:fade] = r; w[-fade:] = r[::-1]
    return w

def overlap_add(mix, window, stride, run):
    """The app's chunking: zero-padded windows, trapezoid weights, weight division."""
    n = mix.shape[1]
    chunks = max(1, int(np.ceil((n - window) / stride)) + 1)
    w = trapezoid(window, stride)
    out = None; weight = np.zeros(n)
    for i in range(chunks):
        a = i * stride
        seg = np.zeros((2, window), np.float32)
        take = min(window, n - a)
        seg[:, :take] = mix[:, a:a + take]
        est = run(seg)                                     # dict stem -> (2, window)
        if out is None: out = {k: np.zeros((2, n)) for k in est}
        for k, v in est.items():
            out[k][:, a:a + take] += v[:, :take] * w[:take]
        weight[a:a + take] += w[:take]
    return {k: v / np.maximum(weight, 1e-8) for k, v in out.items()}, chunks

def sess(path):
    so = ort.SessionOptions(); so.log_severity_level = 3; so.intra_op_num_threads = 4
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])

def roformer_runner(s, frames):
    def run(seg):
        L = seg.shape[1]
        spec = [stft(seg[c].astype(np.float64)) for c in range(2)]
        t = np.zeros((1, BINS * 2, frames, 2), np.float32)
        for c in range(2):
            t[0, c::2, :, 0] = spec[c].T.real; t[0, c::2, :, 1] = spec[c].T.imag
        m = s.run(None, {"stft_repr": t})[0][0]
        voc = np.stack([istft(spec[c] * (m[c::2, :, 0].T + 1j * m[c::2, :, 1].T), L) for c in range(2)])
        return {"vocals": voc, "instrumental": seg - voc}
    return run

def demucs_runner(s):
    def run(seg):
        st = s.run(None, {"mix": seg[None]})[0][0]
        return {"vocals": st[3], "instrumental": st[0] + st[1] + st[2]}
    return run

def sdr(ref, est, eps=1e-8):
    return 10 * np.log10((np.sum(ref ** 2) + eps) / (np.sum((ref - est) ** 2) + eps))

configs = {}
ro = sess(M + "roformer_core_dyn_time.onnx")
for frames, stride_s in [(1101, 8.0), (551, 4.0)]:
    window = (frames - 1) * HOP
    configs[f"RoFormer {window/SR:4.1f} s"] = (roformer_runner(ro, frames), window, int(stride_s * SR))
de = sess(M + "htdemucs_fp16weights.onnx")
configs["HT-Demucs   7.8 s"] = (demucs_runner(de), 343980, 343980 - 343980 // 4)

scores = {k: {"vocals": [], "instrumental": []} for k in configs}
t0 = time.time()
for i, path in enumerate(TRACKS):
    mix, voc = decode(path, 0), decode(path, 4)
    n = min(mix.shape[1], voc.shape[1]); mix, voc = mix[:, :n], voc[:, :n]
    acc = mix - voc
    if np.sum(voc ** 2) < 1e-3 * np.sum(mix ** 2):
        print(f"[{i+1:2d}] skip (vocals silent in excerpt): {path.split('/')[-1]}", flush=True)
        continue
    line = []
    for name, (run, window, stride) in configs.items():
        est, chunks = overlap_add(mix, window, stride, run)
        v, a = sdr(voc, est["vocals"]), sdr(acc, est["instrumental"])
        scores[name]["vocals"].append(v); scores[name]["instrumental"].append(a)
        line.append(f"{name.split()[0][:4]}{name.split()[1] if len(name.split())>2 else ''}: v{v:5.1f} i{a:5.1f} ({chunks}ch)")
    print(f"[{i+1:2d}] {path.split('/')[-1][:34]:34s} " + " | ".join(line) + f"  [{time.time()-t0:4.0f}s]", flush=True)

print("\nmedian SDR over %d tracks with vocals" % len(next(iter(scores.values()))["vocals"]))
for name, s in scores.items():
    print(f"  {name}:  vocals {np.median(s['vocals']):5.2f} dB   instrumental {np.median(s['instrumental']):5.2f} dB")
