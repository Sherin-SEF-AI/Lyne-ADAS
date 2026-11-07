# Validating Lyne's accuracy

Simulation numbers are in the [README](README.md#measured-reproducibly). This page explains how to measure
the real thing on your own car and phone. You need the app, a tape measure or laser rangefinder, and
optionally a reference distance logger. The analysis script is `tools/validate_accuracy.py` (Python 3.8+,
no dependencies). Check it works with `python3 tools/validate_accuracy.py selftest`.

## 1. Parked range check (10 minutes, no extra hardware)

1. Mount the phone exactly as you drive with it. Finish **Calibrate** steps 1–2 (mount and vehicle height).
2. Park on **level ground** directly behind a parked car.
3. Measure from the car's **rear bumper to the phone** (not to your own bumper).
4. In **Calibrate → Range check**, pick that distance and tap **RECORD 5 s**. The error shows immediately.
5. Repeat at several gaps. Roll back, measure again, record. Aim for **5, 10, 15, 20, 30 and 40 m**.
6. Tap **SHARE RESULTS** and send `rangecheck.csv` to your computer, then run:

```bash
python3 tools/validate_accuracy.py static rangecheck.csv --report static.md
```

You get a per-gap table and an overall error. You also get a **diagnosis**:
- a constant percentage error points to scale (vehicle height preset or field of view), and the script
  suggests a fine-tune factor;
- an error that grows with distance points to the horizon (mount rigidity, a sloped parking spot, or
  unstable tilt).

## 2. Drive vs a reference (best evidence)

1. Turn on **Settings → Accuracy log (CSV)**. Each drive writes `lead_YYYYMMDD_HHMMSS.csv` with per-frame
   distance, uncertainty, closing speed, TTC, speed and warning state, timestamped in phone wall-clock ms.
2. Log a reference distance to the car ahead at the same time: an automotive radar/lidar logger, a
   rangefinder with logging, or an ADAS dev kit. Any CSV with a time column and a distance column works.
   Times can be in ms or seconds (`--ref-time-scale 1000` for seconds).
3. Run:

```bash
python3 tools/validate_accuracy.py drive lead_20261108_091200.csv reference.csv --auto-offset --report drive.md
```

`--auto-offset` finds the clock offset between the two logs (±5 s search). If you know it, pass
`--offset-ms` instead. The report breaks error down by distance band. It also checks whether the app's own
± uncertainty is honest (about 95% of errors should fall within 2σ).

## 3. Warning audit (false alarms and misses)

With a reference log:

```bash
python3 tools/validate_accuracy.py warnings lead_....csv --reference reference.csv --offset-ms <from step 2>
```

A warning counts as **justified** if the reference TTC drops below 4 s within −1…+3 s of it (`--ttc-true`).
A **miss** is a reference episode with TTC below 2 s for at least 0.5 s and no warning (`--ttc-miss`).

Without a reference, use dashcam video:

```bash
python3 tools/validate_accuracy.py warnings lead_....csv          # writes fcw_labels.csv
# watch the video at each listed time, fill label = real / false
python3 tools/validate_accuracy.py warnings lead_....csv --labels fcw_labels.csv
```

The result is precision and false warnings per hour.

## Sharing results

Open an issue with the `--report` Markdown, your phone model, the vehicle preset and a mount photo.
Results from many phones and cars are the best way to make Lyne better, and to keep its claims honest.
