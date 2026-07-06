# App icon

`icon.svg` is the source of truth. To regenerate everything after editing it:

```bash
cd app/icons

for size in 16 32 48 64 128 256 512 1024; do
  magick -background none icon.svg -resize ${size}x${size} icon_${size}.png
done

magick icon_256.png icon_128.png icon_64.png icon_48.png icon_32.png icon_16.png icon.ico
python3 build_icns.py . icon.icns
cp icon_512.png icon.png
cp icon.png ../src/main/resources/icon.png   # used for the running app's window icon

rm -f icon_16.png icon_32.png icon_48.png icon_64.png icon_128.png icon_256.png icon_512.png icon_1024.png
```

Requires ImageMagick built with the `rsvg` delegate (`magick -list delegate | grep svg`) and Python 3 — no macOS-only tools (`iconutil`, `png2icns`) needed; `build_icns.py` assembles the `.icns` directly since modern icns types are just wrapped PNGs.

- `icon.ico` — Windows installer/exe icon (`nativeDistributions.windows.iconFile`)
- `icon.icns` — macOS installer/app icon (`nativeDistributions.macOS.iconFile`)
- `icon.png` — Linux installer icon (`nativeDistributions.linux.iconFile`); a copy also lives at `app/src/main/resources/icon.png` for the running window's icon (`painterResource("icon.png")` in `Main.kt`)
