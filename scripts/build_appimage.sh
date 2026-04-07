#!/usr/bin/env bash
# build_appimage.sh — run inside `nix develop` to produce a Linux AppImage.
# Called from CI: nix develop --command bash scripts/build_appimage.sh
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

# Flutter bundle .so files (libflutter_linux_gtk.so, libapp.so, plugin libs)
if [ -d "$BUNDLE/lib" ]; then
  find "$BUNDLE/lib" -name '*.so*' -exec cp {} AppDir/usr/lib/ \;
fi

# Flutter data directory (assets, icudtl.dat, fonts, etc.)
cp -r "$BUNDLE/data" AppDir/usr/

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
