# Model files served to the app

`scnet_small.onnx` is SCNet Small (starrytong's MUSDB18 checkpoint, via ZFTurbo's
Music-Source-Separation-Training release v.1.0.6), exported to ONNX without its STFT by
[`android/tools/onnx/export_scnet.py`](../android/tools/onnx/export_scnet.py). The app
downloads it on first use and refuses it unless it matches:

```
sha256  2f055ae5bb5e2bb3a38adba56e11c8c8723563a2c0a2b6db314dec1eb038c995
size    48 177 654 bytes
```

It lives here because nothing else hosts a working export: the one SCNet ONNX file
published on Hugging Face runs, but separates nothing when fed the Hann-windowed
spectrogram everyone would assume — SCNet's STFT is rectangular, and only its code says
so. The export script checks each step against the original PyTorch model; the app's
pipeline reproduces that forward to 7e-7.

On the MUSDB18 test excerpts (median SDR, 50 tracks), next to the models it sits with:

|  | vocals | instrumental | drums | bass | other |
| --- | ---: | ---: | ---: | ---: | ---: |
| SCNet Small | 9.75 | 13.91 | 9.27 | 8.04 | 6.00 |
| HT-Demucs | 8.78 | 13.35 | 9.51 | 9.03 | 5.21 |

Licences: SCNet © 2024 starrytong, MIT
([starrytong/SCNet](https://github.com/starrytong/SCNet)); the checkpoint release and
model code, ZFTurbo, MIT.

To regenerate, see the header of `export_scnet.py`. A fresh export may not be
byte-identical (exporter versions differ); pin the new file's hash in
`ModelCatalog.kt` if you replace this one.
