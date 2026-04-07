#!/usr/bin/env bash
# build_appimage.sh — builds a Linux AppImage.
# Called from CI: bash scripts/build_appimage.sh
set -euo pipefail

VERSION="${VERSION:-dev}"
VERSION_NO_V="${VERSION#v}"
BUNDLE="build/linux/x64/release/bundle"

# ── 1. Flutter build ──────────────────────────────────────────────────────────
flutter config --enable-linux-desktop
flutter pub get
flutter build linux --release \
  --dart-define=EVE_CLIENT_ID="${EVE_CLIENT_ID:-}" \
  --dart-define=EVE_CLIENT_SECRET="${EVE_CLIENT_SECRET:-}"

# ── 2. Create AppDir ──────────────────────────────────────────────────────────
rm -rf AppDir
mkdir -p AppDir/usr/bin \
         AppDir/usr/lib \
         AppDir/usr/share/applications \
         AppDir/usr/share/icons/hicolor/128x128/apps

# Main executable
cp "$BUNDLE/eve_ntt" AppDir/usr/bin/

# Flutter lib/ and data/ must sit next to the executable so the Flutter engine
# can find libapp.so (AOT snapshot) and icudtl.dat at runtime:
#   <exe_dir>/lib/libapp.so
#   <exe_dir>/data/flutter_assets/…
mkdir -p AppDir/usr/bin/lib
if [ -d "$BUNDLE/lib" ]; then
  find "$BUNDLE/lib" -name '*.so*' -exec cp {} AppDir/usr/bin/lib/ \;
fi
cp -r "$BUNDLE/data" AppDir/usr/bin/data

# Mirror Flutter .so to AppDir/usr/lib so linuxdeploy can find and bundle
# their transitive system dependencies (e.g. libgtk, libsecret, etc.)
if [ -d "$BUNDLE/lib" ]; then
  find "$BUNDLE/lib" -name '*.so*' -exec cp -n {} AppDir/usr/lib/ \;
fi

# Explicitly bundle libsqlite3.so into the AppImage.
# The sqlite3 package on Linux loads via DynamicLibrary.open('libsqlite3.so'),
# which searches LD_LIBRARY_PATH and standard paths. We put it in both lib dirs.
SQLITE3_SYSTEM="$(pkg-config --libs-only-L sqlite3 2>/dev/null | sed 's/^-L//' | head -1)"
if [ -n "$SQLITE3_SYSTEM" ] && [ -d "$SQLITE3_SYSTEM" ]; then
  SQLITE3_LIB="$(find "$SQLITE3_SYSTEM" -name 'libsqlite3.so*' | head -1)"
  if [ -n "$SQLITE3_LIB" ]; then
    echo "Bundling libsqlite3.so from: $SQLITE3_LIB"
    cp "$SQLITE3_LIB" AppDir/usr/bin/lib/
    cp "$SQLITE3_LIB" AppDir/usr/lib/
  fi
fi

# Fallback: try common system paths if pkg-config didn't find it
if [ ! -f AppDir/usr/bin/lib/libsqlite3.so* ]; then
  for candidate in \
    /usr/lib/x86_64-linux-gnu/libsqlite3.so.0 \
    /usr/lib/x86_64-linux-gnu/libsqlite3.so \
    /usr/lib64/libsqlite3.so.0 \
    /usr/lib64/libsqlite3.so; do
    if [ -f "$candidate" ]; then
      echo "Bundling libsqlite3.so from: $candidate"
      cp "$candidate" AppDir/usr/bin/lib/
      cp "$candidate" AppDir/usr/lib/
      break
    fi
  done
fi

# ── 3. Desktop entry ──────────────────────────────────────────────────────────
cat > AppDir/usr/share/applications/eve_ntt.desktop <<'DESKTOP'
[Desktop Entry]
Name=EVE Night Trade Tools
Exec=eve_ntt
Icon=eve_ntt
Type=Application
Categories=Game;
DESKTOP

# ── 5. Icon ───────────────────────────────────────────────────────────────────
python3 scripts/generate_icon.py \
  AppDir/usr/share/icons/hicolor/128x128/apps/eve_ntt.png

# ── 6. AppImage ───────────────────────────────────────────────────────────────
# APPIMAGE_EXTRACT_AND_RUN avoids FUSE requirement in CI sandbox.
export APPIMAGE_EXTRACT_AND_RUN=1

# Flutter plugin .so files are not installed system-wide; expose both lib dirs
# to ldd so linuxdeploy can resolve all dependencies.
export LD_LIBRARY_PATH="$(pwd)/AppDir/usr/bin/lib:$(pwd)/AppDir/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

./linuxdeploy-x86_64.AppImage \
  --appdir AppDir \
  --executable AppDir/usr/bin/eve_ntt \
  --desktop-file AppDir/usr/share/applications/eve_ntt.desktop \
  --icon-file AppDir/usr/share/icons/hicolor/128x128/apps/eve_ntt.png \
  --output appimage

# Rename to versioned filename expected by the release job.
PRODUCED=$(find . -maxdepth 1 -name '*.AppImage' ! -name 'linuxdeploy*' | head -1)
if [ -z "$PRODUCED" ]; then
  echo "ERROR: AppImage not created" >&2
  exit 1
fi
mv "$PRODUCED" "eve_ntt-${VERSION_NO_V}-linux-x86_64.AppImage"
echo "==> Created: eve_ntt-${VERSION_NO_V}-linux-x86_64.AppImage"
