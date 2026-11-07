#!/usr/bin/env python3
"""Extract Android's real bionic runtime (linker64, libc.so, libm.so, libdl.so)
from an Android emulator system image, so x86_64 Android binaries can be run
on an x86_64 Linux build machine for smoke testing.

    extract-bionic.py <system.img> <out-dir>

system.img layout (Android 10+ emulator images): GPT disk -> "super" partition
(dynamic partitions, LP metadata) -> "system" logical partition (ext4,
system-as-root). Files are read with debugfs, so no mounting/root is needed.
"""
import os
import struct
import subprocess
import sys
import tempfile

SECTOR = 512
FILES = {
    "/system/bin/bootstrap/linker64": "linker64",
    "/system/lib64/bootstrap/libc.so": "libc.so",
    "/system/lib64/bootstrap/libm.so": "libm.so",
    "/system/lib64/bootstrap/libdl.so": "libdl.so",
}


def gpt_partitions(f):
    f.seek(SECTOR)
    hdr = f.read(92)
    if hdr[:8] != b"EFI PART":
        raise SystemExit("not a GPT disk image")
    lba, count, size = struct.unpack("<QII", hdr[72:88])
    f.seek(lba * SECTOR)
    parts = {}
    for _ in range(count):
        e = f.read(size)
        if e[:16] == b"\0" * 16:
            continue
        first, last = struct.unpack("<QQ", e[32:48])
        name = e[56:128].decode("utf-16le").rstrip("\0")
        parts[name] = (first * SECTOR, (last - first + 1) * SECTOR)
    return parts


def lp_partition(f, super_off, wanted):
    """Return (byte offset, size) of a linear logical partition in super."""
    f.seek(super_off + 4096)
    geo = f.read(52)
    if struct.unpack("<I", geo[:4])[0] != 0x616C4467:
        raise SystemExit("LP geometry magic not found")
    meta = super_off + 4096 * 3  # reserved + primary/backup geometry
    f.seek(meta)
    h = f.read(256)
    if struct.unpack("<I", h[:4])[0] != 0x414C5030:
        raise SystemExit("LP metadata magic not found")
    header_size = struct.unpack("<I", h[8:12])[0]
    tables_size = struct.unpack("<I", h[44:48])[0]
    d = [struct.unpack("<III", h[80 + 12 * i:92 + 12 * i]) for i in range(4)]
    f.seek(meta + header_size)
    t = f.read(tables_size)
    (po, pn, ps), (eo, en, es) = d[0], d[1]
    ext = [struct.unpack("<QIQI", t[eo + es * i:eo + es * i + 24]) for i in range(en)]
    for i in range(pn):
        e = t[po + ps * i:po + ps * (i + 1)]
        name = e[:36].rstrip(b"\0").decode()
        _attrs, first, num, _grp = struct.unpack("<IIII", e[36:52])
        if name == wanted:
            if num != 1 or ext[first][1] != 0:
                raise SystemExit(f"{name}: only single linear extents supported")
            sectors, _typ, phys, _src = ext[first]
            return super_off + phys * SECTOR, sectors * SECTOR
    raise SystemExit(f"logical partition {wanted!r} not found")


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    img, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    with open(img, "rb") as f:
        parts = gpt_partitions(f)
        if "super" not in parts:
            raise SystemExit(f"no super partition (found: {list(parts)})")
        off, size = lp_partition(f, parts["super"][0], "system")
        with tempfile.NamedTemporaryFile(suffix=".ext4", dir=out) as tmp:
            f.seek(off)
            left = size
            while left:
                chunk = f.read(min(left, 16 << 20))
                tmp.write(chunk)
                left -= len(chunk)
            tmp.flush()
            for src, dst in FILES.items():
                dst = os.path.join(out, dst)
                subprocess.run(["debugfs", "-R", f"dump {src} {dst}", tmp.name],
                               check=True, stderr=subprocess.DEVNULL)
                if not os.path.getsize(dst):
                    raise SystemExit(f"failed to extract {src}")
    os.chmod(os.path.join(out, "linker64"), 0o755)
    print(f"bionic runtime extracted to {out}")


if __name__ == "__main__":
    main()
