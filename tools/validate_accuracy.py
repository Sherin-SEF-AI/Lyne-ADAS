#!/usr/bin/env python3
"""
Lyne ADAS accuracy validation.

Turns the app's exported CSVs into honest, shareable numbers. Python 3.8+, standard library only.

  static    Parked known-gap test (in-app: Calibrate -> Range check). Reads rangecheck.csv.
  drive     Per-frame drive log (Settings -> Accuracy log) vs a reference distance source
            (radar / lidar / laser logger). Time-aligns, then reports error by distance band.
  warnings  Audits forward-collision warnings in a drive log: justified vs false, plus misses,
            using a reference distance log or a hand-labelled CSV (checked against dashcam video).
  selftest  Generates synthetic data with known errors and checks the analysis recovers them.

Add --report out.md to any command to also write a Markdown report you can paste into an issue.

Examples
  python3 tools/validate_accuracy.py static rangecheck.csv --report static.md
  python3 tools/validate_accuracy.py drive lead_20261108_0912.csv radar.csv --auto-offset
  python3 tools/validate_accuracy.py warnings lead_20261108_0912.csv --reference radar.csv
  python3 tools/validate_accuracy.py warnings lead_20261108_0912.csv --labels labels.csv
  python3 tools/validate_accuracy.py selftest
"""
from __future__ import annotations

import argparse
import bisect
import csv
import math
import os
import random
import statistics
import sys
import tempfile
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

BANDS = [(0, 10), (10, 20), (20, 30), (30, 50), (50, 80), (80, 250)]


# ----------------------------------------------------------------------------- helpers

def fnum(s: Optional[str]) -> Optional[float]:
    if s is None:
        return None
    s = s.strip()
    if not s:
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    return v if math.isfinite(v) else None


def read_csv(path: str) -> List[Dict[str, str]]:
    with open(path, newline="", encoding="utf-8-sig") as f:
        return [{(k or "").strip().lower(): (v or "") for k, v in row.items()} for row in csv.DictReader(f)]


def pick_col(rows: List[Dict[str, str]], wanted: Optional[str], candidates: Sequence[str], what: str) -> str:
    if not rows:
        raise SystemExit(f"error: no rows found while looking for the {what} column")
    cols = list(rows[0].keys())
    if wanted:
        if wanted.lower() not in cols:
            raise SystemExit(f"error: column '{wanted}' not found; columns are {cols}")
        return wanted.lower()
    for c in candidates:
        if c in cols:
            return c
    raise SystemExit(f"error: can't find a {what} column (tried {list(candidates)}); columns are {cols}. "
                     f"Pass it explicitly.")


def pct(sorted_vals: List[float], q: float) -> float:
    if not sorted_vals:
        return float("nan")
    k = (len(sorted_vals) - 1) * q
    lo, hi = int(math.floor(k)), int(math.ceil(k))
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def linfit(xs: List[float], ys: List[float]) -> Tuple[float, float]:
    """Least squares y = a + b x."""
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx <= 1e-12:
        return my, 0.0
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    return my - b * mx, b


def table(headers: List[str], rows: List[List[str]]) -> str:
    widths = [max(len(h), *(len(r[i]) for r in rows)) if rows else len(h) for i, h in enumerate(headers)]
    line = lambda cells: "| " + " | ".join(c.ljust(w) for c, w in zip(cells, widths)) + " |"
    sep = "|" + "|".join("-" * (w + 2) for w in widths) + "|"
    return "\n".join([line(headers), sep] + [line(r) for r in rows])


class Report:
    def __init__(self) -> None:
        self.parts: List[str] = []

    def h(self, text: str) -> None:
        self.parts.append(f"\n## {text}\n")
        print(f"\n== {text} ==")

    def p(self, text: str = "") -> None:
        self.parts.append(text)
        print(text)

    def t(self, headers: List[str], rows: List[List[str]]) -> None:
        s = table(headers, rows)
        self.parts.append(s + "\n")
        print(s)

    def save(self, path: Optional[str], title: str) -> None:
        if not path:
            return
        with open(path, "w", encoding="utf-8") as f:
            f.write(f"# {title}\n" + "\n".join(self.parts) + "\n")
        print(f"\nreport written: {path}")


@dataclass
class ErrStats:
    n: int
    mae: float
    bias: float
    rmse: float
    p90_abs: float
    rel_mae_pct: float


def err_stats(est: List[float], ref: List[float]) -> ErrStats:
    errs = [e - r for e, r in zip(est, ref)]
    abs_e = sorted(abs(e) for e in errs)
    return ErrStats(
        n=len(errs),
        mae=sum(abs_e) / len(abs_e),
        bias=sum(errs) / len(errs),
        rmse=math.sqrt(sum(e * e for e in errs) / len(errs)),
        p90_abs=pct(abs_e, 0.9),
        rel_mae_pct=100.0 * sum(abs(e) / r for e, r in zip(errs, ref)) / len(errs),
    )


def band_of(d: float) -> Tuple[int, int]:
    for lo, hi in BANDS:
        if lo <= d < hi:
            return lo, hi
    return BANDS[-1]


# ----------------------------------------------------------------------------- static

def cmd_static(args: argparse.Namespace) -> int:
    rows = read_csv(args.csv)
    data = []
    for r in rows:
        t, m = fnum(r.get("true_m")), fnum(r.get("median_m"))
        if t is None or m is None or t <= 0:
            continue
        data.append({
            "true": t, "est": m,
            "p10": fnum(r.get("p10_m")) or m, "p90": fnum(r.get("p90_m")) or m,
            "sigma": fnum(r.get("sigma_m")) or 0.0, "n": int(fnum(r.get("samples")) or 0),
        })
    if not data:
        raise SystemExit("error: no usable rows (expected columns true_m, median_m, ...)")
    data.sort(key=lambda d: d["true"])

    rep = Report()
    rep.h("Parked range check")
    rows_out = []
    for d in data:
        e = d["est"] - d["true"]
        inside = d["p10"] <= d["true"] <= d["p90"]
        within2s = d["sigma"] > 0 and abs(e) <= 2 * d["sigma"]
        rows_out.append([
            f"{d['true']:.1f}", f"{d['est']:.1f}", f"{e:+.1f}", f"{100 * e / d['true']:+.1f}%",
            f"{d['p10']:.1f}-{d['p90']:.1f}", f"±{d['sigma']:.1f}",
            "yes" if within2s else "NO", "yes" if inside else "no", str(d["n"]),
        ])
    rep.t(["true m", "app m", "error m", "error %", "p10-p90", "app ±σ", "|err|≤2σ", "truth in p10-p90", "samples"], rows_out)

    s = err_stats([d["est"] for d in data], [d["true"] for d in data])
    cov2 = sum(1 for d in data if d["sigma"] > 0 and abs(d["est"] - d["true"]) <= 2 * d["sigma"]) / len(data)
    rep.h("Summary")
    rep.p(f"checks: {s.n}   mean abs error: {s.mae:.2f} m ({s.rel_mae_pct:.1f}%)   bias: {s.bias:+.2f} m   "
          f"RMSE: {s.rmse:.2f} m   90% of errors within: {s.p90_abs:.2f} m")
    rep.p(f"uncertainty honesty: {100 * cov2:.0f}% of checks within the app's own 2σ (ideal ≈ 95%)")

    # Diagnose the error shape: constant % (scale) vs growing with distance (horizon / pitch / slope).
    rep.h("Diagnosis")
    if len(data) >= 3:
        xs = [d["true"] for d in data]
        rel = [100.0 * (d["est"] - d["true"]) / d["true"] for d in data]
        a, b = linfit(xs, rel)
        med_ratio = statistics.median(d["est"] / d["true"] for d in data)
        rep.p(f"relative error ≈ {a:+.1f}% {b:+.2f}%/m × distance")
        if abs(b) * (max(xs) - min(xs)) < 6 and abs(med_ratio - 1) > 0.04:
            rep.p(f"→ mostly a constant scale error. In Calibrate → Range check, apply a fine-tune of "
                  f"×{1 / med_ratio:.2f} (or check the vehicle height preset / field of view).")
        elif abs(b) * (max(xs) - min(xs)) >= 6:
            direction = "long" if b > 0 else "short"
            rep.p(f"→ error grows with distance (reads {direction} far away). That is the horizon: check the mount "
                  f"is rigid, the car is on level ground, and the tilt reading in Calibrate step 1 is stable. "
                  f"A 0.3° horizon error is ~1 m at 20 m but ~5 m at 50 m.")
        else:
            rep.p("→ no systematic pattern; remaining error is noise. Calibration looks good.")
    else:
        rep.p("Record at least 3 different gaps (e.g. 5, 15, 30 m) to separate scale error from horizon error.")
    rep.save(args.report, "Lyne ADAS parked range check")
    return 0


# ----------------------------------------------------------------------------- drive

@dataclass
class Series:
    t: List[float]
    v: List[float]

    def at(self, t: float, max_gap_ms: float) -> Optional[float]:
        i = bisect.bisect_left(self.t, t)
        if i <= 0 or i >= len(self.t):
            return None
        t0, t1 = self.t[i - 1], self.t[i]
        if t1 - t0 > max_gap_ms:
            return None
        w = (t - t0) / (t1 - t0) if t1 > t0 else 0.0
        return self.v[i - 1] + w * (self.v[i] - self.v[i - 1])


def load_lead(path: str) -> List[Dict[str, Optional[float]]]:
    rows = read_csv(path)
    out = []
    for r in rows:
        t = fnum(r.get("t_ms"))
        if t is None:
            continue
        out.append({
            "t": t, "d": fnum(r.get("distance_m")), "sigma": fnum(r.get("sigma_m")),
            "closing": fnum(r.get("closing_mps")), "ttc": fnum(r.get("ttc_s")),
            "speed": fnum(r.get("speed_kph")), "id": fnum(r.get("lead_id")),
            "fcw": (r.get("fcw") or "NONE").strip().upper(),
        })
    if not out:
        raise SystemExit(f"error: {path} has no rows with t_ms (is it an app accuracy log?)")
    return out


def load_reference(path: str, tcol: Optional[str], dcol: Optional[str], tscale: float) -> Series:
    rows = read_csv(path)
    tc = pick_col(rows, tcol, ["t_ms", "time_ms", "timestamp_ms", "timestamp", "time", "t"], "time")
    dc = pick_col(rows, dcol, ["distance_m", "range_m", "distance", "range", "dist", "d"], "distance")
    pts = []
    for r in rows:
        t, d = fnum(r.get(tc)), fnum(r.get(dc))
        if t is None or d is None or d <= 0:
            continue
        pts.append((t * tscale, d))
    if len(pts) < 2:
        raise SystemExit(f"error: reference {path} has fewer than 2 valid rows")
    pts.sort()
    return Series([p[0] for p in pts], [p[1] for p in pts])


def pairs(lead, ref: Series, offset_ms: float, max_gap_ms: float, max_range: float):
    out = []
    for r in lead:
        d = r["d"]
        if d is None:
            continue
        rv = ref.at(r["t"] + offset_ms, max_gap_ms)
        if rv is None or rv > max_range:
            continue
        out.append((r, rv))
    return out


def best_offset(lead, ref: Series, max_gap_ms: float, max_range: float, span_ms: float) -> Tuple[float, int]:
    def score(off: float) -> Tuple[float, int]:
        ps = pairs(lead, ref, off, max_gap_ms, max_range)
        if len(ps) < 20:
            return float("inf"), len(ps)
        return statistics.median(abs(r["d"] - rv) for r, rv in ps), len(ps)

    best = (float("inf"), 0.0, 0)
    off = -span_ms
    while off <= span_ms:                       # coarse 100 ms grid
        s, n = score(off)
        if s < best[0]:
            best = (s, off, n)
        off += 100
    c = best[1]
    for off in [c + k * 10 for k in range(-10, 11)]:   # refine at 10 ms
        s, n = score(off)
        if s < best[0]:
            best = (s, off, n)
    return best[1], best[2]


def cmd_drive(args: argparse.Namespace) -> int:
    lead = load_lead(args.lead_csv)
    ref = load_reference(args.reference, args.ref_time_col, args.ref_dist_col, args.ref_time_scale)
    rep = Report()
    offset = args.offset_ms
    if args.auto_offset:
        offset, n = best_offset(lead, ref, args.max_gap_ms, args.max_range, args.offset_span_ms)
        rep.p(f"auto time offset: {offset:+.0f} ms (reference = app time {offset:+.0f} ms), {n} matched frames")
    ps = pairs(lead, ref, offset, args.max_gap_ms, args.max_range)
    if len(ps) < 5:
        raise SystemExit("error: fewer than 5 overlapping frames. Check both clocks, or use --auto-offset / --offset-ms.")

    est = [r["d"] for r, _ in ps]
    tru = [rv for _, rv in ps]
    s = err_stats(est, tru)
    rep.h("Range accuracy vs reference")
    rep.p(f"matched frames: {s.n}   mean abs error: {s.mae:.2f} m ({s.rel_mae_pct:.1f}%)   bias: {s.bias:+.2f} m   "
          f"RMSE: {s.rmse:.2f} m   90% within: {s.p90_abs:.2f} m")

    rows_out = []
    for lo, hi in BANDS:
        sel = [(r, rv) for r, rv in ps if lo <= rv < hi]
        if not sel:
            continue
        bs = err_stats([r["d"] for r, _ in sel], [rv for _, rv in sel])
        sig = [r["sigma"] for r, _ in sel if r["sigma"]]
        cov = [abs(r["d"] - rv) <= 2 * r["sigma"] for r, rv in sel if r["sigma"]]
        rows_out.append([
            f"{lo}-{hi} m", str(bs.n), f"{bs.mae:.2f}", f"{bs.rel_mae_pct:.1f}%", f"{bs.bias:+.2f}", f"{bs.p90_abs:.2f}",
            f"±{statistics.median(sig):.1f}" if sig else "-",
            f"{100 * sum(cov) / len(cov):.0f}%" if cov else "-",
        ])
    rep.t(["band (ref)", "frames", "MAE m", "MAE %", "bias m", "p90 |err| m", "app ±σ (median)", "within 2σ"], rows_out)

    # Closing-speed accuracy where the reference allows a derivative.
    cl = []
    for r, rv in ps:
        if r["closing"] is None:
            continue
        a = ref.at(r["t"] + offset - 250, args.max_gap_ms)
        b = ref.at(r["t"] + offset + 250, args.max_gap_ms)
        if a is None or b is None:
            continue
        cl.append((r["closing"], (a - b) / 0.5))
    if len(cl) >= 10:
        ce = sorted(abs(x - y) for x, y in cl)
        rep.p(f"closing speed: median abs error {pct(ce, 0.5) * 3.6:.1f} km/h, 90% within {pct(ce, 0.9) * 3.6:.1f} km/h "
              f"({len(cl)} frames)")
    coverage = sum(1 for r in lead if r["d"] is not None) / len(lead)
    rep.p(f"lead availability: a lead distance was reported in {100 * coverage:.0f}% of logged frames")
    rep.save(args.report, "Lyne ADAS drive accuracy")
    return 0


# ----------------------------------------------------------------------------- warnings

def fcw_onsets(lead) -> List[Tuple[float, str]]:
    out, prev = [], "NONE"
    for r in lead:
        cur = r["fcw"] if r["fcw"] in ("NONE", "INFO", "CAUTION", "CRITICAL") else "NONE"
        rank = {"NONE": 0, "INFO": 0, "CAUTION": 1, "CRITICAL": 2}
        if rank[cur] > rank[prev]:
            out.append((r["t"], cur))
        prev = cur
    return out


def ref_ttc(ref: Series, t: float, max_gap_ms: float) -> Optional[float]:
    """Reference TTC from a +-0.4 s least-squares slope of reference range."""
    pts = []
    for k in range(-4, 5):
        v = ref.at(t + k * 100.0, max_gap_ms)
        if v is not None:
            pts.append((k * 0.1, v))
    if len(pts) < 5:
        return None
    a, b = linfit([p[0] for p in pts], [p[1] for p in pts])
    closing = -b
    d = a
    if closing <= 0.3:
        return None
    return d / closing


def cmd_warnings(args: argparse.Namespace) -> int:
    lead = load_lead(args.lead_csv)
    onsets = fcw_onsets(lead)
    rep = Report()
    dur_h = (lead[-1]["t"] - lead[0]["t"]) / 3.6e6
    rep.h("Forward-collision warnings")
    rep.p(f"log length: {dur_h * 60:.1f} min   warning onsets: {len(onsets)} "
          f"({sum(1 for _, s in onsets if s == 'CRITICAL')} critical, {sum(1 for _, s in onsets if s == 'CAUTION')} caution)")

    if args.labels:
        rows = read_csv(args.labels)
        tc = pick_col(rows, None, ["t_ms", "time_ms", "t"], "time")
        lc = pick_col(rows, None, ["label", "real", "verdict"], "label")
        labels = [(fnum(r.get(tc)), (r.get(lc) or "").strip().lower()) for r in rows]
        labels = [(t, l) for t, l in labels if t is not None and l]
        real = lambda l: l in ("real", "true", "yes", "1", "tp", "justified")
        tp = sum(1 for _, l in labels if real(l))
        fp = len(labels) - tp
        rep.p(f"hand-labelled: {tp} justified, {fp} false  →  precision {100 * tp / max(1, len(labels)):.0f}%  "
              f"({fp / max(dur_h, 1e-9):.1f} false warnings per hour)")
    elif args.reference:
        ref = load_reference(args.reference, args.ref_time_col, args.ref_dist_col, args.ref_time_scale)
        rows_out, tp = [], 0
        for t, sev in onsets:
            ttcs = [ref_ttc(ref, t + args.offset_ms + k * 100.0, args.max_gap_ms) for k in range(-10, 31)]
            ttcs = [x for x in ttcs if x is not None]
            m = min(ttcs) if ttcs else None
            ok = m is not None and m < args.ttc_true
            tp += ok
            rows_out.append([f"{(t - lead[0]['t']) / 1000:.1f}", sev, f"{m:.1f}" if m else "-", "justified" if ok else "FALSE"])
        if rows_out:
            rep.t(["t (s)", "severity", "min reference TTC (−1…+3 s)", "verdict"], rows_out)
        fp = len(onsets) - tp
        rep.p(f"justified: {tp}   false: {fp}   precision: {100 * tp / max(1, len(onsets)):.0f}%   "
              f"false per hour: {fp / max(dur_h, 1e-9):.1f}   (justified = reference TTC < {args.ttc_true:.1f} s)")

        # Misses: reference says TTC < threshold for >= 0.5 s with no warning nearby.
        miss, t, step, run_start = 0, lead[0]["t"], 100.0, None
        events = []
        while t <= lead[-1]["t"]:
            x = ref_ttc(ref, t + args.offset_ms, args.max_gap_ms)
            danger = x is not None and x < args.ttc_miss
            if danger and run_start is None:
                run_start = t
            if (not danger or t + step > lead[-1]["t"]) and run_start is not None:
                if t - run_start >= 500:
                    events.append((run_start, t))
                run_start = None
            t += step
        for a, b in events:
            if not any(a - 1500 <= ot <= b for ot, _ in onsets):
                miss += 1
        rep.p(f"reference danger episodes (TTC < {args.ttc_miss:.1f} s for ≥0.5 s): {len(events)}   missed by the app: {miss}")
    else:
        out = args.labels_template or "fcw_labels.csv"
        with open(out, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["t_ms", "t_s_from_start", "severity", "label"])
            for t, sev in onsets:
                w.writerow([int(t), f"{(t - lead[0]['t']) / 1000:.1f}", sev, ""])
        rep.p(f"No reference or labels given. Wrote {out}: watch the dashcam video at each time, "
              f"fill label with 'real' or 'false', then rerun with --labels {out}")
    rep.save(args.report, "Lyne ADAS warning audit")
    return 0


# ----------------------------------------------------------------------------- selftest

def cmd_selftest(_: argparse.Namespace) -> int:
    rnd = random.Random(7)
    tmp = tempfile.mkdtemp(prefix="lyne_validate_")
    ok = True

    def check(name: str, cond: bool, detail: str) -> None:
        nonlocal ok
        ok &= cond
        print(f"[{'PASS' if cond else 'FAIL'}] {name}: {detail}")

    # static: +5% scale error
    p = os.path.join(tmp, "rangecheck.csv")
    with open(p, "w") as f:
        f.write("t_ms,true_m,median_m,p10_m,p90_m,sigma_m,samples\n")
        for d in (5, 10, 15, 20, 30, 40):
            m = d * 1.05 + rnd.gauss(0, 0.1)
            f.write(f"0,{d},{m:.3f},{m - 0.5:.3f},{m + 0.5:.3f},{0.05 * d:.3f},70\n")
    rows = read_csv(p)
    ratio = statistics.median(float(r["median_m"]) / float(r["true_m"]) for r in rows)
    check("static scale recovery", abs(ratio - 1.05) < 0.01, f"median ratio {ratio:.3f} (expected 1.05)")

    # drive: reference clock 1200 ms ahead, app noise 3%
    lead_p, ref_p = os.path.join(tmp, "lead.csv"), os.path.join(tmp, "ref.csv")
    def truth(t: float) -> float:
        base = 25 + 10 * math.sin(t / 7000.0)
        if 60000 <= t < 62000:      # real threat: lead brakes, gap shrinks 15 m in 2 s
            return base - 15 * (t - 60000) / 2000
        if 62000 <= t < 64000:      # gap recovers
            return base - 15 * (64000 - t) / 2000
        return base
    with open(ref_p, "w") as f:
        f.write("timestamp_ms,range_m\n")
        for i in range(0, 120000, 50):
            f.write(f"{i + 1200},{truth(i):.3f}\n")
    with open(lead_p, "w") as f:
        f.write("t_ms,speed_kph,lead_id,distance_m,sigma_m,closing_mps,ttc_s,headway_s,fcw,conf,horizon_y,height_scale\n")
        for i in range(0, 120000, 66):
            d = truth(i) * (1 + rnd.gauss(0, 0.03))
            fcw = "CAUTION" if 60800 < i < 62300 or 90000 < i < 90600 else "NONE"
            f.write(f"{i},60,1,{d:.3f},{0.06 * d:.3f},,,,{fcw},0.8,0.45,1.0\n")
    lead = load_lead(lead_p)
    ref = load_reference(ref_p, None, None, 1.0)
    off, n = best_offset(lead, ref, 300, 200, 5000)
    check("drive auto time offset", abs(off - 1200) <= 60, f"{off:+.0f} ms (expected +1200), {n} frames")
    ps = pairs(lead, ref, off, 300, 200)
    s = err_stats([r["d"] for r, _ in ps], [rv for _, rv in ps])
    check("drive error recovery", 1.5 < s.rel_mae_pct < 3.2, f"rel MAE {s.rel_mae_pct:.2f}% (expected ~2.4% for 3% noise)")

    onsets = fcw_onsets(lead)
    verdicts = []
    for t, _ in onsets:
        ttcs = [ref_ttc(ref, t + off + k * 100.0, 300) for k in range(-10, 31)]
        ttcs = [x for x in ttcs if x is not None]
        verdicts.append(bool(ttcs) and min(ttcs) < 4.0)
    check("warning audit", verdicts == [True, False], f"verdicts {verdicts} (expected one justified, one false)")

    # run the real commands end to end (prints reports)
    ns = argparse.Namespace(csv=p, report=os.path.join(tmp, "static.md"))
    cmd_static(ns)
    print(f"\nselftest data in {tmp}")
    print("SELFTEST " + ("PASSED" if ok else "FAILED"))
    return 0 if ok else 1


# ----------------------------------------------------------------------------- cli

def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def ref_opts(p: argparse.ArgumentParser) -> None:
        p.add_argument("--ref-time-col", help="reference time column (default: auto)")
        p.add_argument("--ref-dist-col", help="reference distance column (default: auto)")
        p.add_argument("--ref-time-scale", type=float, default=1.0,
                       help="multiply reference time by this to get milliseconds (e.g. 1000 for seconds)")
        p.add_argument("--offset-ms", type=float, default=0.0, help="reference_time = app_time + offset")
        p.add_argument("--max-gap-ms", type=float, default=300.0, help="max reference sample gap to interpolate")

    p = sub.add_parser("static", help="parked known-gap test (rangecheck.csv)")
    p.add_argument("csv")
    p.add_argument("--report")
    p.set_defaults(fn=cmd_static)

    p = sub.add_parser("drive", help="drive log vs reference distance log")
    p.add_argument("lead_csv")
    p.add_argument("reference")
    ref_opts(p)
    p.add_argument("--auto-offset", action="store_true", help="search for the clock offset that best aligns the logs")
    p.add_argument("--offset-span-ms", type=float, default=5000.0)
    p.add_argument("--max-range", type=float, default=120.0, help="ignore reference ranges beyond this (m)")
    p.add_argument("--report")
    p.set_defaults(fn=cmd_drive)

    p = sub.add_parser("warnings", help="audit forward-collision warnings")
    p.add_argument("lead_csv")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--reference", help="reference distance log")
    g.add_argument("--labels", help="hand-labelled CSV: t_ms,label (real/false)")
    ref_opts(p)
    p.add_argument("--ttc-true", type=float, default=4.0, help="warning counts as justified if reference TTC drops below this")
    p.add_argument("--ttc-miss", type=float, default=2.0, help="reference TTC below this for 0.5 s is a danger episode")
    p.add_argument("--labels-template", help="where to write the labelling template")
    p.add_argument("--report")
    p.set_defaults(fn=cmd_warnings)

    p = sub.add_parser("selftest", help="check the analysis on synthetic data")
    p.set_defaults(fn=cmd_selftest)

    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
