# Verification harness

The APK's DSP and inference code has to agree with the reference implementations the
model authors published, or the stems come out subtly wrong — a half-frame of STFT
offset or a swapped stem index is inaudible in a smoke test and obvious in a mix.

This harness compiles the app's **actual** sources (`app/src/main/java`, minus the
files that touch the Android framework) into a plain JVM program and runs them against
fixtures produced by a NumPy reference.

## Run it

```bash
pip install numpy onnxruntime

# 1. Fetch the weights once (≈ 900 MB).
mkdir -p models && cd models
curl -LO https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/main/syhft_core_folded_fp16_webgpu.onnx
curl -LO https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/main/syhft_core_folded_fp16_webgpu.onnx.data
curl -LO https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx
cd ..

# 2. Build the fixtures and the reference outputs.
mkdir -p data && python3 reference.py data models

# 3. Run the app's code over the same fixtures.
cd ../.. && ./gradlew :tools:verify:run --args="tools/verify/data tools/verify/models"

#    One model at a time, so peak RSS is not polluted by the previous session:
#      --only=roformer | --only=demucs
#    Re-measure the ONNX Runtime knobs:
#      --tune=memoryPattern,arena[,threads]   e.g. --tune=false,true,4

# 4. Diff the two.
cd tools/verify && python3 compare.py data
```

## What it checks

| Check | Tolerance |
| --- | --- |
| FFT: peak bin placement and `inverse(forward(x)) == x` | 1e-5 |
| STFT: frame count is PyTorch's `1 + len/hop`, `istft(stft(x)) == x` | 1e-4 |
| Overlap-add: a constant signal survives the window and the weight division | 1e-6 |
| Resampler: a 1 kHz tone stays a 1 kHz tone through 48 k → 44.1 k | 5e-3 |
| Packed STFT tensor vs NumPy, element for element | 1e-5 relative |
| Mel-Band RoFormer vocals and instrumental vs the NumPy pipeline | 5e-6 absolute |
| HT-Demucs: all four stems, plus the summed instrumental | 5e-6 absolute |

Note that `reference.py` prefers `roformer_core_dyn_time.onnx` if it is in the models
directory, so the reference runs the same graph the app does. Copy it there from
`app/src/main/assets/`, or regenerate it with `tools/onnx/make_dynamic_time.py`.

## Last measured run

```
FFT peak bin 7, round-trip 3.6e-07
STFT round-trip 1.8e-07, frames 1101
Overlap-add constant error 6.0e-08 over 1 543 500 frames
Resampler 48k -> 44.1k error 1.3e-04
Packed STFT vs NumPy 2.0e-07 relative

roformer vocals        max abs diff 6.1e-07     RTF 2.1   peak RSS 4.11 GB
roformer instrumental  max abs diff 6.1e-07
demucs drums/bass/other/vocals/instrumental
                       max abs diff 0.0         RTF 0.47  peak RSS 6.51 GB
```

HT-Demucs needs no host transform, so it matches the reference bit for bit. The RoFormer
residual is float32 rounding between this FFT and NumPy's `rfft` — and it holds with the
rewritten dynamic-time graph, which is the point: the rewrite changes the schedule, not
the arithmetic.
