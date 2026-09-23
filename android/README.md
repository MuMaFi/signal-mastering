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
ulps of fp16.

A dynamic time axis also means the chunk no longer has to be 11 s, and the attention
working set shrinks with it. Together with the graph optimiser switched off (see
[what went wrong in 1.0.0](#100-froze-phones--what-happened)) — which also leaves the
weights memory-mapped from disk — the app now runs 5.5 s chunks:

| RoFormer, optimiser off | Peak RSS | RTF |
| --- | ---: | ---: |
| 11 s chunks | 2.66 GB | 2.08 |
| **5.5 s chunks (what the app runs)** | **1.77 GB** | **1.80** |

Shorter context could cost separation quality, so it is checked against ground truth
rather than assumed: [`tools/verify/musdb_eval.py`](tools/verify/musdb_eval.py) runs
both chunk lengths through the app's pipeline on the MUSDB18 test split.

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

## Design

The interface follows Apple's Human Interface Guidelines rather than Material:
grouped inset lists with hairlines inset to the text, a large title that collapses into
a translucent bar as you scroll, UIKit-sized switches (51×31) and segmented control,
a single tint colour, and press highlights instead of ripples. Light and dark follow
the system.

- **Type.** SF Pro may not ship outside Apple platforms, so the app uses **Inter**
  (SIL OFL), drawn to sit close to SF. Like SF it has an optical-size axis, and each
  iOS text style pins it to its own point size — the tighter display cut for the
  34 sp large title, the open text cut for 13–17 sp copy. Latin subset, 165 KB.
- **Colour.** Apple's system colours, with two changes made for contrast: systemMint
  carries white text at only 2.2:1, and Apple's 60 % secondary label is 3.4:1 on
  white. [`web/contrast.mjs`](../web/contrast.mjs) composites every translucent token
  over the surface it is drawn on and checks all 26 pairs against WCAG AA.
- **Checked by eye, not only compiled.** [Paparazzi](https://github.com/cashapp/paparazzi)
  renders every screen, light and dark, to PNG on the JVM — no emulator. The images
  are in [`app/src/test/snapshots/images`](app/src/test/snapshots/images) and CI fails
  if a change alters them. Re-record after an intended change with
  `./gradlew :app:recordPaparazziDebug`.

**Try the design without a phone:** [`../web`](../web) is the same interface in a
browser, served by a zero-dependency local server — `cd web && npm start`, then open
http://localhost:4173. The separation there is simulated; the screens, copy and numbers
are the app's.

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

Peak memory is the number that decides whether this works at all, so it is measured
from the kernel's own high-water mark (VmHWM) across several consecutive chunks — the
steady state of a real song, not a single run — four threads as on an 8-core phone:

| | Peak RSS | RTF | A 4-minute song |
| --- | ---: | ---: | --- |
| Mel-Band RoFormer | 1.81 GB | 1.8 | ~7 minutes |
| HT-Demucs | 1.11 GB | 0.35 | ~2 minutes |

RTF is from an uncontended run on the development machine's x86 CPU, not from a phone;
treat the times as an order of magnitude. Run it plugged in — it is minutes of full
load — and leave the app if you like: the work runs in a foreground service.

Before it downloads anything, and again right before it loads the model, the app
compares each model's measured peak against the memory Android reports as free *now*,
above the point where Android starts killing apps. If it does not fit, it says so and
does not start. During a run it stops cleanly as soon as Android signals low memory.

The app is arm64-only by design. 32-bit ARM cannot address enough memory for these
models, so shipping that slice would only produce crashes.

<br>

## 1.0.0 froze phones — what happened

The first release made an 8 GB phone (Honor Magic 4 Pro) unresponsive for about ten
minutes. Two mistakes combined:

- **ONNX Runtime's graph optimiser, left on.** It constant-folds while it builds the
  session. On HT-Demucs that folding alone peaks at 6.6 GB before a single sample is
  processed; through the Java API it reached 7.4 GB and was killed by the kernel's OOM
  killer on the development machine. With the optimiser off: 1.24 GB, about 15 %
  slower. The RoFormer went from 3.2 GB to 1.9 GB, the rest of that coming from shorter
  chunks (see [the graph section](#the-published-roformer-does-not-fit-on-a-phone--so-the-app-changes-the-graph)).
  The earlier memory measurements tuned the arena and the memory-pattern planner with
  the optimiser on, where its spike swamped everything else, and sampled RSS every
  50 ms — which misses a spike that short. They were wrong, and so was the advice built
  on them.
- **A check against total RAM, not free RAM.** "8 GB recommended" let an 8 GB phone
  through, when Android itself holds a large part of that. And because separation runs
  as a foreground service, Android protected the app and killed everything else first —
  which is why the whole phone stalled rather than just the app.

Both are fixed as described above. If Android does kill a run anyway, the app now leaves
a note behind and tells you, on the next launch, which stage the run died in.

<br>

## Credits

- **Mel-Band RoFormer** — architecture by Wang, Lu and Won ([arXiv:2310.01809](https://arxiv.org/abs/2310.01809)),
  reference implementation [lucidrains/BS-RoFormer](https://github.com/lucidrains/BS-RoFormer) (MIT).
  Weights © Kimberley Jensen / SYH99999. ONNX export © musetric, re-hosted by
  [Silverdaw](https://github.com/irarainey/silverdaw).
- **HT-Demucs** — [facebookresearch/demucs](https://github.com/facebookresearch/demucs) (MIT),
  ONNX exports by [StemSplitio](https://huggingface.co/StemSplitio).
- **ONNX Runtime** — Microsoft (MIT).
- **Inter** — Rasmus Andersson and the Inter Project Authors, SIL Open Font License
  1.1. The licence ships inside the APK at `assets/licenses/Inter-OFL.txt`.
