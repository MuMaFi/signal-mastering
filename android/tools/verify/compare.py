"""Compares the Kotlin harness output against the NumPy reference.

Run `reference.py` first, then `gradlew :tools:verify:run`, then this.
"""
import sys, os, numpy as np

D = sys.argv[1] if len(sys.argv) > 1 else "data"
DEMUCS_SEG = 343980
failures = 0


def load(name):
    return np.fromfile(os.path.join(D, name), dtype=np.float32)


def check(label, ref, got, tolerance=5e-6):
    global failures
    if ref.shape != got.shape:
        print("%-26s SHAPE MISMATCH %s vs %s" % (label, ref.shape, got.shape))
        failures += 1
        return
    err = float(np.abs(ref - got).max())
    ok = err <= tolerance
    failures += 0 if ok else 1
    print("%-26s max abs diff %.3e  (peak %.4f)  %s"
          % (label, err, float(np.abs(ref).max()), "ok" if ok else "FAILED"))


check("roformer vocals", load("ref_vocals.f32"), load("kt_roformer_vocals.f32"))
check("roformer instrumental", load("ref_instrumental.f32"), load("kt_roformer_instrumental.f32"))

stems = load("ref_demucs_stems.f32").reshape(4, 2, DEMUCS_SEG)
for i, name in enumerate(["drums", "bass", "other", "vocals"]):
    got = load("kt_demucs_%s.f32" % name).reshape(-1, 2).T
    check("demucs " + name, stems[i], got)
check("demucs instrumental", stems[0] + stems[1] + stems[2],
      load("kt_demucs_instrumental.f32").reshape(-1, 2).T)

print("\nALL COMPARISONS PASSED" if failures == 0 else "\n%d COMPARISON(S) FAILED" % failures)
raise SystemExit(0 if failures == 0 else 1)
