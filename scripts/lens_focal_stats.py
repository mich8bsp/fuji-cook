#!/usr/bin/env python3
"""Focal-length statistics per lens for a folder of photos (recursive).

Reads EXIF via exiftool. When a photo has no lens info it was shot with the
MCEX-11 extension tube (which breaks lens<->body comms); the lens is then
deduced from the reported focal length.

Usage: scripts/lens_focal_stats.py PATH [PATH ...]
"""
import json
import statistics
import subprocess
import sys
from collections import defaultdict

# Known lenses. Zooms: (min, max). Primes: single focal length.
ZOOMS = {"XF16-80mmF4": (16, 80), "XF70-300mmF4-5.6": (70, 300)}
PRIMES = {12.0: "XF12mmF1.4", 23.0: "XF23mmF2"}


def deduce_lens(fl):
    """Guess the lens on the MCEX-11 tube from focal length alone."""
    if fl is None:
        return "MCEX-11 (unknown lens, no focal length)"
    if fl in PRIMES:
        return f"MCEX-11 + {PRIMES[fl]}"
    # ponytail: 70-80mm overlaps both zooms; bias to 16-80 below 81, 70-300 above.
    # Tighten only if the collection actually has ambiguous shots worth splitting.
    if fl <= 69:
        return "MCEX-11 + XF16-80mmF4"
    if fl >= 81:
        return "MCEX-11 + XF70-300mmF4-5.6"
    return "MCEX-11 + XF16-80mmF4 or XF70-300mmF4-5.6 (ambiguous)"


def lens_name(tags):
    for k in ("LensModel", "LensID", "LensType", "Lens"):
        v = tags.get(k)
        if v and str(v).strip() and str(v) != "0":
            return str(v).strip()
    return None


def main(paths):
    out = subprocess.run(
        ["exiftool", "-j", "-n", "-r", "-ext", "jpg", "-ext", "jpeg",
         "-ext", "raf", "-ext", "tif", "-ext", "tiff", "-ext", "heic",
         "-FocalLength", "-LensModel", "-LensID", "-LensType", "-Lens", *paths],
        capture_output=True, text=True,
    )
    data = json.loads(out.stdout or "[]")

    by_lens = defaultdict(list)  # lens -> [focal lengths]
    no_fl = defaultdict(int)     # lens -> count of shots with lens but no focal length
    skipped = 0                  # files with neither lens nor focal length (not camera photos)
    for tags in data:
        fl = tags.get("FocalLength")
        lens = lens_name(tags)
        if lens is None and fl is None:
            skipped += 1
            continue
        if lens is None:
            lens = deduce_lens(fl)  # no lens comms => MCEX-11 tube
        if fl is None:
            no_fl[lens] += 1
            continue
        by_lens[lens].append(float(fl))

    total = sum(len(v) for v in by_lens.values()) + sum(no_fl.values())
    print(f"{total} photos across {len(by_lens) or len(no_fl)} lens(es)"
          f"{f'  ({skipped} files skipped, no camera EXIF)' if skipped else ''}\n")

    for lens in sorted(by_lens, key=lambda k: -len(by_lens[k])):
        fls = sorted(by_lens[lens])
        n = len(fls)
        print(f"{lens}  ({n} photos)")
        print(f"  focal length: min {min(fls):g}  max {max(fls):g}  "
              f"mean {statistics.mean(fls):.1f}  median {statistics.median(fls):g}")
        hist = defaultdict(int)
        for fl in fls:
            hist[round(fl)] += 1
        dist = "  ".join(f"{mm}mm:{c}" for mm, c in sorted(hist.items()))
        print(f"  distribution: {dist}")
        if no_fl.get(lens):
            print(f"  (+{no_fl[lens]} photos with no focal length recorded)")
        print()

    for lens, c in no_fl.items():
        if lens not in by_lens:
            print(f"{lens}  ({c} photos, no focal length recorded)\n")


def _selfcheck():
    assert deduce_lens(12.0) == "MCEX-11 + XF12mmF1.4"
    assert deduce_lens(23.0) == "MCEX-11 + XF23mmF2"
    assert deduce_lens(50.0) == "MCEX-11 + XF16-80mmF4"
    assert deduce_lens(200.0) == "MCEX-11 + XF70-300mmF4-5.6"
    assert "ambiguous" in deduce_lens(75.0)
    assert deduce_lens(None).startswith("MCEX-11")
    assert lens_name({"LensModel": "XF16-80mmF4 R OIS WR"}) == "XF16-80mmF4 R OIS WR"
    assert lens_name({"LensID": "0"}) is None
    print("selfcheck ok")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "selfcheck":
        _selfcheck()
    else:
        main(sys.argv[1:])
