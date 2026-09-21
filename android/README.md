# signal.isolate

An Android app that separates a song into vocals and instrumental **entirely on the
device**. No upload, no account, no server. Pick a track, pick a model, get WAV files.

<br>

## Why these models

The request was "the best open-source model". The honest answer has two halves, because
the best model on a leaderboard and the best model you can actually run on a phone are
not the same thing.

**What is actually state of the art.** On
[mvsep.com's multisong leaderboard](https://mvsep.com/en/algorithms) the top entry is
*BS RoFormer 124 bands (ver. 2026.07)* at **12.33 dB SDR vocals / 18.64 dB
instrumental**. Its weights are not published — it is a service. The same is true of
most of the top ten: they are either service-only, or they ship as PyTorch `.ckpt`
files that need the training repo and a GPU to run.

**What is state of the art *and* runnable offline on ARM.** Filtering the leaderboard
down to checkpoints with a working ONNX export leaves a clear winner and a clear
fallback, and those are what the app ships:

| | Model | Download | Quality | Measured speed |
| --- | --- | --- | --- | --- |
| **Maximum** | [Mel-Band RoFormer (SYHFT / "Kim Vocal" lineage)](https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx) | 741 MB | Best offline separation there is. Clean sibilance, very little instrumental bleed. | RTF 2.1 |
| **Faster** | [HT-Demucs FT, vocals specialist](https://huggingface.co/StemSplitio/htdemucs-ft-onnx) | 166 MB | 9.19 dB median vocals SDR on MUSDB18-HQ | RTF 0.47 |
| **Four stems** | [HT-Demucs](https://huggingface.co/StemSplitio/htdemucs-onnx) | 166 MB | Drums / bass / other / vocals in one pass | RTF 0.47 |

All three are MIT-licensed. The app downloads the weights once, pins each file to a
SHA-256 baked into [`ModelCatalog.kt`](app/src/main/java/app/signal/isolate/model/ModelCatalog.kt),
and refuses anything that does not match.

**Vocals *and* instrumental.** There are separately trained instrumental RoFormer
checkpoints, but none with an ONNX export — they are PyTorch-only, so they cannot run
here. The app does what the reference desktop host does instead: it takes the vocal
stem from the model and reconstructs the instrumental as the residual `mix − vocals`.
That is not a compromise for this use case — it means the two stems sum back to the
original mix *exactly*, with no phase or level drift. For HT-Demucs in four-stem mode
the instrumental is `drums + bass + other`, the model's own decomposition.

<br>

## The published RoFormer does not fit on a phone — so the app changes the graph

This is the part worth reading.

The upstream export is traced at a fixed 1101 STFT frames. Run it and ONNX Runtime
plans every buffer up front, and one 11-second chunk peaks at **10.4 GB of RSS**. No
phone has that. The weights only account for ~2.5 GB of it; the rest is the static
allocation plan holding the band and time attention workspaces of all twelve layers
alive at once — the time attention alone is `[60 bands, 8 heads, 1101, 1101]`.

Marking the time axis dynamic forces ONNX Runtime to allocate per run instead, reusing
and freeing as it goes. Same weights, same arithmetic, different schedule:

| RoFormer graph | Peak RSS | Time per 11 s chunk |
| --- | ---: | ---: |
| as published (static 1101 frames) | 10.7 GB | 29.5 s |
| time axis made dynamic | **3.4 GB** | **16.2 s** |

3.1× less memory *and* 1.8× faster, with a maximum output difference of 4.9e-04 — two
ulps of fp16. That is what moves this model from "impossible on a phone" to "runs on a
flagship".

[`tools/onnx/make_dynamic_time.py`](tools/onnx/make_dynamic_time.py) performs the
rewrite and documents exactly what it touches: 17 `Reshape` shape constants, and the
rotary embedding table's slice bound, rewired to `Shape(stft_repr)[2]` so the table
follows the real frame count. The resulting 5 MB graph ships inside the APK
(`app/src/main/assets/`) — only the 741 MB weight blob is downloaded, and the rewritten
graph still references it by its original filename.

<br>

## How it works

```
audio file ──▶ MediaCodec ──▶ Kaiser-sinc resample ──▶ interleaved f32 on disk
                                                               │
                                       ┌───────────────────────┘
                                       ▼
                              one window at a time
                                       │
              ┌────────────────────────┴───────────────────────┐
              ▼                                                ▼
     RoFormer (host STFT)                              HT-Demucs (waveform)
     STFT → ONNX mask → iSTFT                          ONNX → 4 stems
              └────────────────────────┬───────────────────────┘
                                       ▼
                       trapezoid overlap-add, streamed
                                       ▼
                                  WAV per stem
```

A few decisions worth calling out:

**Audio never sits in the Java heap.** A five-minute stereo track is ~105 MB of floats,
and the model needs every byte of native memory it can get. So the decoder writes PCM
straight to a scratch file, the engine reads one window at a time, and the overlap-add
streams finished samples into the output writers. Heap stays at a few tens of megabytes
regardless of track length.

**The RoFormer export has no STFT in it.** `torch.stft` / `torch.istft` were stripped
out of the graph, so the app owns the transform: `n_fft = 2048`, `hop = 441`, periodic
Hann, centred reflect padding, packed into `[1, 2050, 1101, 2]` indexed `2·freq +
channel`. Get any of that wrong and the model still runs and still produces audio — it
is just quietly worse. Hence the verification harness below.

**The overlap window is a trapezoid, not a Hamming.** Its ramps are exactly as long as
the chunk overlap, so consecutive windows sum to 1 and the middle of every chunk comes
through exactly as the model produced it, with no cross-fade smearing. The running
weight division handles the first and last chunk.

**Resampling is a windowed sinc, not linear.** Both models are 44.1 kHz-only, and a
cheap resample smears exactly the high-frequency detail that separation quality is
judged on. 32 taps, 1024 phases, Kaiser β = 8.6.

**Runtime options are swept, not guessed.** `RuntimeTuning` is a parameter the harness
can sweep (`--tune=pattern,arena,threads`). The sweep did not produce a reliable reason
to move off ONNX Runtime's own defaults, so the app keeps them — see the comment on that
type for the table, including the numbers that refused to repeat cleanly.

<br>

## Verified, not assumed

[`tools/verify`](tools/verify) compiles the app's real sources into a JVM program and
runs them against fixtures produced by a NumPy reference implementation of the same
pipeline:

```
FFT peak bin 7, round-trip                    3.6e-07
STFT round-trip, 1101 frames                  1.8e-07
Overlap-add constant over 1 543 500 frames    6.0e-08
Resampler 48k -> 44.1k                        1.3e-04
Packed STFT tensor vs NumPy                   2.0e-07 relative

roformer vocals vs reference                  6.1e-07 max abs
roformer instrumental vs reference            6.1e-07 max abs
demucs drums/bass/other/vocals/instrumental   0.0     (bit-exact)
```

HT-Demucs matches the reference bit for bit. The RoFormer residual is float32 rounding
between this FFT and NumPy's `rfft`. See [tools/verify/README.md](tools/verify/README.md).

<br>

## Build

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

APKs land in `app/build/outputs/apk/debug/`. The build splits by ABI:

| APK | Size |
| --- | --- |
| `app-arm64-v8a-debug.apk` | 45 MB — this is the one for a phone |
| `app-x86_64-debug.apk` | 51 MB — emulators |
| `app-universal-debug.apk` | 84 MB — both |

Most of that is ONNX Runtime's native library. The model weights are *not* in the APK;
the app fetches them on first use.

`assembleRelease` is minified and currently debug-signed so CI produces something
installable — swap in a real keystore before distributing.

<br>

## What to expect on a phone

Separation is heavy, and it is honest to say so up front. Measured through the Java API
on x86_64 — the closest proxy available here for the Android AAR, **not** a measurement
on a real phone:

| | Peak RSS | RTF | A 4-minute song |
| --- | ---: | ---: | --- |
| Mel-Band RoFormer | ~4.1 GB | 2.1 | ~8 minutes |
| HT-Demucs | 6.5 GB (varied 6.5–8.7 across repeats) | 0.47 | ~2 minutes |

Two things follow from that, and neither is comfortable:

- **Both models want an 8 GB phone.** HT-Demucs is the faster and much smaller
  download, but it is not the low-memory option — its measured peak is in the same range
  as the RoFormer's. The app reads the device's total RAM and says so on each model
  before you pick it, rather than letting you find out twenty minutes into a render.
- **The Demucs figures did not repeat cleanly** on a contended machine. Treat them as a
  range. The RoFormer numbers, and the graph-rewrite result above, reproduced across
  both runtimes and every repeat.

Run it with the screen off and the phone plugged in. The work runs in a foreground
service, so leaving the app does not kill it.

The app is arm64-only by design. 32-bit ARM cannot address enough memory for these
models, so shipping that slice would only produce crashes.

<br>

## Credits

- **Mel-Band RoFormer** — architecture by Wang, Lu and Won ([arXiv:2310.01809](https://arxiv.org/abs/2310.01809)),
  reference implementation [lucidrains/BS-RoFormer](https://github.com/lucidrains/BS-RoFormer) (MIT).
  Weights © Kimberley Jensen / SYH99999. ONNX export © musetric, re-hosted by
  [Silverdaw](https://github.com/irarainey/silverdaw).
- **HT-Demucs** — [facebookresearch/demucs](https://github.com/facebookresearch/demucs) (MIT),
  ONNX exports by [StemSplitio](https://huggingface.co/StemSplitio).
- **ONNX Runtime** — Microsoft (MIT).
