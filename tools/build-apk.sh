#!/usr/bin/env bash
# Builds the Android APK from the same index.html the web version uses.
#
# The page is copied into the app's assets rather than kept in two places, and
# the one CDN dependency the offline features need — lamejs, for MP3 export — is
# vendored next to it. The AAC converter (ffmpeg.wasm, ~30 MB) stays online-only;
# it is too large to ship and M4A export simply needs a connection.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets"
LAME_URL="https://cdnjs.cloudflare.com/ajax/libs/lamejs/1.2.1/lame.min.js"

mkdir -p "$ASSETS"
cp "$ROOT/index.html" "$ASSETS/index.html"

if [ ! -f "$ASSETS/lame.min.js" ]; then
  echo "fetching lamejs for offline MP3 export…"
  curl -sSL -o "$ASSETS/lame.min.js" "$LAME_URL"
fi

# Point the page at the vendored copy so MP3 export works with no connection.
python3 - "$ASSETS/index.html" "$LAME_URL" <<'PY'
import io, sys
path, url = sys.argv[1], sys.argv[2]
s = io.open(path, encoding='utf-8').read()
before = s
s = s.replace('<script src="%s"></script>' % url, '<script src="lame.min.js"></script>')
s = s.replace('window.LAMEJS_URL = "%s"' % url, 'window.LAMEJS_URL = "lame.min.js"')
if s == before:
    sys.exit('build-apk: lamejs URL not found in index.html — did the CDN reference change?')
io.open(path, 'w', encoding='utf-8').write(s)
PY

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

cd "$ROOT/android"
TASK="${1:-assembleDebug}"
gradle --no-daemon "$TASK"

find "$ROOT/android/app/build/outputs/apk" -name '*.apk' -exec ls -la {} \;
