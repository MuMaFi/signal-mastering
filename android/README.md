# signal — Android build

A thin shell around the same `index.html` the web version runs. All mastering,
metering and encoding happen inside the WebView; the native side exists only to
do the three things a WebView cannot.

## Build

```sh
./tools/build-apk.sh              # debug APK, installable by sideloading
./tools/build-apk.sh assembleRelease
```

Needs a JDK (17+), Gradle, and an Android SDK with platform 34 and
build-tools 34. Point `ANDROID_HOME` at the SDK if it is not at
`/opt/android-sdk`. The script copies `index.html` into the app's assets and
vendors lamejs next to it, so `android/app/src/main/assets/` is generated — not
edited by hand, and not checked in.

## Why the shell does what it does

**The page is served over `https://appassets.androidplatform.net`, not
`file://`.** A `file://` page has an opaque origin and is not allowed to load a
Blob-backed Web Worker. The DSP worker is created from a Blob URL, so on
`file://` the app would quietly fall back to running every master on the UI
thread — correct output, frozen interface.

**Exports go through a JavaScript bridge, not a download.** A WebView has no
path for downloading a `blob:` URL; the anchor the web build uses does nothing
at all. The page hands the bytes over in 256 KB chunks instead and they are
written straight into `MediaStore.Downloads`, which on API 29+ needs no storage
permission. Chunked because a mastered WAV runs to tens of megabytes, and base64
of the whole file in one string would hold it several times over at once.

**Back steps through the app's screens** before it leaves, via
`window.signalHandleBack()`. Otherwise one stray swipe on the result screen
closes the app and discards a finished master.

## Limits

- `minSdk 29` (Android 10). That is where `MediaStore.Downloads` arrives, which
  is what keeps the app permission-free.
- WAV and MP3 export work offline. M4A does not: it pulls a ~30 MB ffmpeg.wasm
  build on first use, which is too large to bundle.
- Mastering is real DSP and a phone is not a laptop. Measured against a desktop
  browser throttled to roughly mid-range phone CPU, a 20 s track takes about
  2.4 s; expect a few tens of seconds for a full-length track, and longer again
  for a 96 kHz source.
