#!/usr/bin/env python3
"""Repeat-factor oversampling (LVIS style) for a prepared YOLO dataset.

Each training image is repeated r = max over its classes of max(1, sqrt(t / f_c)), where f_c is the
fraction of images containing class c. Rare classes (animal, bus, truck, bicycle) get seen several
times per epoch without touching the label files. Writes train_balanced.txt and data_balanced.yaml
next to data.yaml; use them with `train_indian.py train --data <yolo>/data_balanced.yaml`.
"""
import argparse, math, zlib
from collections import Counter
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("--yolo", default=str(Path.home() / ".cache/lyne-data/yolo"))
ap.add_argument("--t", type=float, default=0.3, help="frequency threshold; classes rarer than this are oversampled")
ap.add_argument("--max-repeat", type=int, default=6)
a = ap.parse_args()

root = Path(a.yolo)
imgs = sorted(p for p in (root / "images/train").iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".bmp", ".webp"})
cls_of = {}
for im in imgs:
    lb = root / "labels/train" / (im.stem + ".txt")
    cls_of[im] = {int(l.split()[0]) for l in lb.read_text().splitlines() if l.strip()} if lb.exists() else set()
n = len(imgs)
freq = Counter(c for s in cls_of.values() for c in s)
rf_c = {c: max(1.0, math.sqrt(a.t / (k / n))) for c, k in freq.items()}
lines, per_cls = [], Counter()
for im, s in cls_of.items():
    r = min(a.max_repeat, max([rf_c[c] for c in s], default=1.0))
    k = int(r) + (1 if (zlib.crc32(im.name.encode()) % 1000) / 1000 < r - int(r) else 0)
    lines += [str(im)] * k
    for c in s: per_cls[c] += k
(root / "train_balanced.txt").write_text("\n".join(lines) + "\n")
y = (root / "data.yaml").read_text().replace("train: images/train", "train: train_balanced.txt")
(root / "data_balanced.yaml").write_text(y)
print(f"{n} images -> {len(lines)} entries")
for c in sorted(freq):
    print(f"class {c}: in {freq[c]} images ({freq[c]/n:.1%}), repeat {rf_c[c]:.2f}, seen {per_cls[c]} times/epoch")
