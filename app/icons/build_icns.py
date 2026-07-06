#!/usr/bin/env python3
"""Assembles a macOS .icns from PNGs — modern icns types just wrap PNG bytes directly,
so this avoids needing iconutil/png2icns (both macOS-only, unavailable on Linux CI/dev)."""
import struct
import sys
from pathlib import Path

# type code -> source PNG (icX1 = @2x retina slot reusing the next size up's pixels)
TYPES = {
    "icp4": "icon_16.png",     # 16x16
    "icp5": "icon_32.png",     # 32x32
    "ic11": "icon_32.png",     # 16x16@2x
    "icp6": "icon_64.png",     # 64x64
    "ic12": "icon_64.png",     # 32x32@2x
    "ic07": "icon_128.png",    # 128x128
    "ic13": "icon_256.png",    # 128x128@2x
    "ic08": "icon_256.png",    # 256x256
    "ic14": "icon_512.png",    # 256x256@2x
    "ic09": "icon_512.png",    # 512x512
    "ic10": "icon_1024.png",   # 512x512@2x
}


def build(icon_dir: Path, out_path: Path) -> None:
    chunks = bytearray()
    for type_code, filename in TYPES.items():
        data = (icon_dir / filename).read_bytes()
        chunks += type_code.encode("ascii")
        chunks += struct.pack(">I", 8 + len(data))
        chunks += data

    total_len = 8 + len(chunks)
    out_path.write_bytes(b"icns" + struct.pack(">I", total_len) + bytes(chunks))


if __name__ == "__main__":
    icon_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out_path = Path(sys.argv[2]) if len(sys.argv) > 2 else icon_dir / "icon.icns"
    build(icon_dir, out_path)
    print(f"Wrote {out_path} ({out_path.stat().st_size} bytes)")
