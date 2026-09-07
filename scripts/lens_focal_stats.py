#!/usr/bin/env python3
"""Focal-length statistics per lens for a folder of photos (recursive).

Reads EXIF via exiftool. When a photo has no lens info it was shot with the
MCEX-11 extension tube (which breaks lens<->body comms); the lens is then
deduced from the reported focal length.

Also writes two chronological CSVs to the current directory and prints a
simulated "23mm + 56mm two primes instead of the XF16-80mm zoom" analysis:

  photo_sequence.csv    - every photo, sorted strictly by DateTimeOriginal
  xf16_80_sequence.csv  - only XF16-80mm shots, same columns plus focal_band
                          and nearest_prime classification columns

Usage: scripts/lens_focal_stats.py PATH [PATH ...]
       scripts/lens_focal_stats.py selfcheck
"""
import csv
import json
import math
import os
import re
import statistics
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime

# Known lenses. Zooms: (min, max). Primes: single focal length.
ZOOMS = {"XF16-80mmF4": (16, 80), "XF70-300mmF4-5.6": (70, 300)}
PRIMES = {12.0: "XF12mmF1.4", 23.0: "XF23mmF2"}

# The simulated two-prime kit we test against the XF16-80mm zoom.
SIM_PRIMES = (23.0, 56.0)
SESSION_GAP_S = 60 * 60  # > 60 min between frames => new shooting session


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


def is_xf16_80(lens):
    """True for any spelling of the Fujifilm XF16-80mm F4 zoom."""
    return bool(lens) and "16-80" in lens.replace(" ", "")


def focal_band(fl):
    """wide: 16-30mm, middle: >30-45mm, tele: >45-80mm."""
    if fl <= 30:
        return "wide"
    if fl <= 45:
        return "middle"
    return "tele"


def nearest_prime(fl):
    """Which of the simulated primes fl is geometrically closest to.

    Angle of view scales ~inversely with focal length, so "closeness" between
    focal lengths is multiplicative, not additive: compare |ln(fl/lo)| with
    |ln(fl/hi)|. The crossover is the geometric mean sqrt(23*56) ~= 35.9mm,
    not the linear midpoint 39.5mm.
    """
    lo, hi = SIM_PRIMES
    closer_lo = abs(math.log(fl / lo)) <= abs(math.log(fl / hi))
    return f"{lo:g}mm" if closer_lo else f"{hi:g}mm"


_DT_RE = re.compile(r"\s*(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})")


def parse_dt(s):
    """EXIF 'YYYY:MM:DD HH:MM:SS' -> datetime, or None if missing/malformed.

    Ignores trailing sub-seconds and timezone, and tolerates the all-zero
    stamp some cameras write when the clock is unset.
    """
    if not isinstance(s, str):
        return None
    m = _DT_RE.match(s)
    if not m:
        return None
    try:
        return datetime(*(int(g) for g in m.groups()))
    except ValueError:
        return None


def iter_runs(values):
    """Yield (value, run_length) for each maximal consecutive run."""
    it = iter(values)
    try:
        cur = next(it)
    except StopIteration:
        return
    n = 1
    for v in it:
        if v == cur:
            n += 1
        else:
            yield cur, n
            cur, n = v, 1
    yield cur, n


def count_transitions(values):
    return sum(1 for a, b in zip(values, values[1:]) if a != b)


def split_sessions(records):
    """Split time-sorted records wherever the gap exceeds SESSION_GAP_S."""
    sessions, cur = [], []
    for r in records:
        if cur and (r["dt"] - cur[-1]["dt"]).total_seconds() > SESSION_GAP_S:
            sessions.append(cur)
            cur = []
        cur.append(r)
    if cur:
        sessions.append(cur)
    return sessions


def fmt_dur(seconds):
    seconds = int(round(seconds))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h}h {m:02d}m {s:02d}s"


# ----------------------------------------------------------------------------
# existing per-lens focal-length statistics (unchanged output)
# ----------------------------------------------------------------------------
def print_focal_stats(data):
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


# ----------------------------------------------------------------------------
# chronological CSV export + simulated two-prime analysis
# ----------------------------------------------------------------------------
BASE_COLS = ["filename", "datetime_original", "date", "time",
             "lens_model", "focal_length_mm", "seconds_since_prev"]
XF_COLS = BASE_COLS + ["focal_band", "nearest_prime"]


def build_records(data):
    """(sorted photo records with a valid timestamp, [(filename, reason), ...])."""
    records, bad = [], []
    for tags in data:
        raw = tags.get("DateTimeOriginal")
        dt = parse_dt(raw)
        name = os.path.basename(tags.get("SourceFile", "?"))
        if dt is None:
            reason = "missing" if not isinstance(raw, str) or not raw.strip() \
                else f"malformed: {raw!r}"
            bad.append((name, reason))
            continue
        fl = tags.get("FocalLength")
        fl = float(fl) if fl is not None else None  # not rounded
        lens = lens_name(tags) or (deduce_lens(fl) if fl is not None else "")
        records.append({"file": name, "dt": dt, "dt_raw": raw.strip(),
                        "lens": lens, "fl": fl})
    records.sort(key=lambda r: (r["dt"], r["file"]))
    return records, bad


def base_row(r, prev_dt):
    dt = r["dt"]
    return {
        "filename": r["file"],
        "datetime_original": r["dt_raw"],
        "date": dt.strftime("%Y-%m-%d"),
        "time": dt.strftime("%H:%M:%S"),
        "lens_model": r["lens"],
        "focal_length_mm": "" if r["fl"] is None else str(r["fl"]),
        "seconds_since_prev": "" if prev_dt is None
            else f"{(dt - prev_dt).total_seconds():.0f}",
    }


def write_csv(path, cols, rows):
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    return os.path.abspath(path)


def export_sequences(data):
    records, bad = build_records(data)

    rows, prev = [], None
    for r in records:
        rows.append(base_row(r, prev))
        prev = r["dt"]
    p1 = write_csv("photo_sequence.csv", BASE_COLS, rows)

    xf = [r for r in records if is_xf16_80(r["lens"]) and r["fl"] is not None]
    rows, prev = [], None
    for r in xf:
        row = base_row(r, prev)
        row["focal_band"] = focal_band(r["fl"])
        row["nearest_prime"] = nearest_prime(r["fl"])
        rows.append(row)
        prev = r["dt"]
    p2 = write_csv("xf16_80_sequence.csv", XF_COLS, rows)

    print(f"wrote {p1}  ({len(records)} photos)")
    print(f"wrote {p2}  ({len(xf)} XF16-80mm photos)")
    if bad:
        print(f"\n{len(bad)} file(s) with missing/malformed DateTimeOriginal "
              f"(excluded from CSVs and analysis):")
        for name, why in bad:
            print(f"  {name}: {why}")

    analyse_two_prime(xf)


def analyse_two_prime(xf):
    lo, hi = (f"{p:g}mm" for p in SIM_PRIMES)
    print(f"\n=== simulated {lo} + {hi} two-prime kit vs XF16-80mm zoom ===")
    if not xf:
        print("no XF16-80mm photos - nothing to analyse")
        return

    cls = [nearest_prime(r["fl"]) for r in xf]
    n = len(cls)
    n_lo, n_hi = cls.count(lo), cls.count(hi)
    print(f"XF16-80mm photos: {n}")
    print(f"  would use {lo}: {n_lo}  ({100 * n_lo / n:.1f}%)")
    print(f"  would use {hi}: {n_hi}  ({100 * n_hi / n:.1f}%)")
    print(f"classification transitions over the whole sequence: {count_transitions(cls)}")

    print("\nper calendar day:")
    by_day = defaultdict(list)
    for r, c in zip(xf, cls):
        by_day[r["dt"].strftime("%Y-%m-%d")].append(c)
    for day in sorted(by_day):
        d = by_day[day]
        print(f"  {day}: {len(d):4d} photos   {count_transitions(d):3d} swaps   "
              f"({d.count(lo)}x{lo} / {d.count(hi)}x{hi})")

    sessions = split_sessions(xf)
    print(f"\nper shooting session ( > {SESSION_GAP_S // 60} min gap = new session ):"
          f"  {len(sessions)} session(s)")
    swap_run_lengths = Counter()  # runs that ended because the lens had to change
    sessions_without_swap = 0
    total_swaps = 0
    for i, sess in enumerate(sessions, 1):
        scls = [nearest_prime(r["fl"]) for r in sess]
        dur = (sess[-1]["dt"] - sess[0]["dt"]).total_seconds()
        swaps = count_transitions(scls)
        total_swaps += swaps
        runs = list(iter_runs(scls))
        longest = {lo: 0, hi: 0}
        for val, length in runs:
            longest[val] = max(longest[val], length)
        for _, length in runs[:-1]:          # every run except the last ends in a swap
            swap_run_lengths[length] += 1
        if len(runs) == 1:
            sessions_without_swap += 1
        sph = f"{swaps / (dur / 3600):.2f}" if dur > 0 else "n/a"
        print(f"  session {i}: {sess[0]['dt']:%Y-%m-%d %H:%M} -> {sess[-1]['dt']:%H:%M}")
        print(f"    photos: {len(sess)}   duration: {fmt_dur(dur)}")
        print(f"    simulated lens swaps: {swaps}   swaps/hour: {sph}")
        print(f"    longest run {lo}: {longest[lo]}   longest run {hi}: {longest[hi]}")

    print(f"\ntotal simulated lens swaps across all sessions: {total_swaps}")
    print(f"sessions with no swap needed: {sessions_without_swap} / {len(sessions)}")

    print("\ndistribution of consecutive run lengths before the simulated lens "
          "would need to change:")
    if not swap_run_lengths:
        print("  (no swaps in any session)")
    else:
        tot = sum(swap_run_lengths.values())
        for length in sorted(swap_run_lengths):
            c = swap_run_lengths[length]
            print(f"  {length:3d} photo(s):  {c:4d} run(s)  ({100 * c / tot:.1f}%)")


def main(paths):
    out = subprocess.run(
        ["exiftool", "-j", "-n", "-r", "-ext", "jpg", "-ext", "jpeg",
         "-ext", "raf", "-ext", "tif", "-ext", "tiff", "-ext", "heic",
         "-FocalLength", "-LensModel", "-LensID", "-LensType", "-Lens",
         "-DateTimeOriginal", *paths],
        capture_output=True, text=True,
    )
    data = json.loads(out.stdout or "[]")
    print_focal_stats(data)
    export_sequences(data)


def _selfcheck():
    assert deduce_lens(12.0) == "MCEX-11 + XF12mmF1.4"
    assert deduce_lens(23.0) == "MCEX-11 + XF23mmF2"
    assert deduce_lens(50.0) == "MCEX-11 + XF16-80mmF4"
    assert deduce_lens(200.0) == "MCEX-11 + XF70-300mmF4-5.6"
    assert "ambiguous" in deduce_lens(75.0)
    assert deduce_lens(None).startswith("MCEX-11")
    assert lens_name({"LensModel": "XF16-80mmF4 R OIS WR"}) == "XF16-80mmF4 R OIS WR"
    assert lens_name({"LensID": "0"}) is None

    assert is_xf16_80("XF16-80mmF4 R OIS WR")
    assert is_xf16_80("Fujifilm XF 16-80mm F4 R OIS WR")
    assert not is_xf16_80("XF23mmF2 R WR")
    assert not is_xf16_80(None)

    assert focal_band(16) == "wide" and focal_band(30) == "wide"
    assert focal_band(30.0001) == "middle" and focal_band(45) == "middle"
    assert focal_band(45.0001) == "tele" and focal_band(80) == "tele"

    # geometric crossover is sqrt(23*56) ~= 35.86mm, not linear 39.5mm
    assert nearest_prime(23.0) == "23mm"
    assert nearest_prime(56.0) == "56mm"
    assert nearest_prime(16.0) == "23mm"
    assert nearest_prime(80.0) == "56mm"
    assert nearest_prime(35.0) == "23mm"     # below geometric mean
    assert nearest_prime(37.0) == "56mm"     # above geometric mean
    assert nearest_prime(39.5) == "56mm"     # linear midpoint lands on 56

    assert parse_dt("2024:03:15 14:23:01") == datetime(2024, 3, 15, 14, 23, 1)
    assert parse_dt("2024:03:15 14:23:01.55") == datetime(2024, 3, 15, 14, 23, 1)
    assert parse_dt("2024:03:15 14:23:01+02:00") == datetime(2024, 3, 15, 14, 23, 1)
    assert parse_dt("0000:00:00 00:00:00") is None
    assert parse_dt("not a date") is None
    assert parse_dt(None) is None

    assert list(iter_runs(["a", "a", "b", "a"])) == [("a", 2), ("b", 1), ("a", 1)]
    assert list(iter_runs([])) == []
    assert count_transitions(["a", "a", "b", "a"]) == 2
    assert count_transitions(["a"]) == 0

    def rec(y, mo, d, h, mi):
        return {"dt": datetime(y, mo, d, h, mi), "file": "x"}
    s = split_sessions([rec(2024, 1, 1, 10, 0), rec(2024, 1, 1, 10, 30),
                        rec(2024, 1, 1, 12, 0)])  # 90-min gap splits
    assert [len(x) for x in s] == [2, 1]

    print("selfcheck ok")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "selfcheck":
        _selfcheck()
    else:
        main(sys.argv[1:])
