#!/usr/bin/env python3
"""Clean the machine-generated ThirdEye labels with an independent, larger COCO detector (teacher).

Why: the dataset's labels for rare classes are mostly hallucinated. On the val split a YOLOv8m
cross-check confirmed 0% of animal, 5% of bicycle, 8% of bus and 13% of truck boxes, and whole clips
are labelled with vehicles floating in trees and sky. A model trained on that learns to never fire
on those classes (it scored 0 AP on animals).

What it does, per split:
  1. Runs the teacher on every image once (cached to <out>/teacher_<split>.json).
  2. Scores each clip: share of its label boxes the teacher confirms (any class, IoU > 0.3).
     Clips below --min-clip-agree are dropped (they teach the model to fire on background).
  3. In kept frames, drops rare-class boxes (bicycle, truck, bus, animal) the teacher does not
     confirm as a compatible class, and adds teacher boxes for those classes (conf >= --add-conf)
     that no existing label covers.
  Common classes (person, rider, motorcycle, autorickshaw, car) in kept clips are left alone: they
  are mostly right, and COCO cannot name riders or autorickshaws.

Writes a parallel dataset at <out> (images symlinked) plus data.yaml and clean_stats.json.
"""
import argparse, json, os
from collections import Counter, defaultdict
from pathlib import Path

RARE = {2, 6, 7, 8}  # bicycle, truck, bus, animal
COCO_TO_OURS = {"person": 0, "bicycle": 2, "motorcycle": 3, "car": 5, "truck": 6, "bus": 7}
ANIMALS = {"cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe"}
# label class -> teacher classes that count as confirmation
COMPAT = {0: {0}, 1: {0, 3, 2}, 2: {2, 3}, 3: {3, 2}, 4: {5, 6, 3}, 5: {5, 6, 7}, 6: {6, 5, 7}, 7: {7, 6}, 8: {8}}


def teacher_class(name):
    return 8 if name in ANIMALS else COCO_TO_OURS.get(name)


def iou(a, b):  # a: (cx, cy, w, h) normalized; b: (x0, y0, x1, y1) normalized
    ax0, ay0, ax1, ay1 = a[0] - a[2] / 2, a[1] - a[3] / 2, a[0] + a[2] / 2, a[1] + a[3] / 2
    iw = max(0.0, min(ax1, b[2]) - max(ax0, b[0])); ih = max(0.0, min(ay1, b[3]) - max(ay0, b[1]))
    i = iw * ih
    return i / (a[2] * a[3] + (b[2] - b[0]) * (b[3] - b[1]) - i + 1e-9)


def run_teacher(model_path, images, cache, imgsz, conf, device):
    done = json.loads(cache.read_text()) if cache.exists() else {}
    todo = [p for p in images if p.stem not in done]
    if todo:
        from ultralytics import YOLO
        m = YOLO(model_path)
        for i in range(0, len(todo), 32):
            batch = todo[i:i + 32]
            for p, r in zip(batch, m.predict([str(x) for x in batch], imgsz=imgsz, conf=conf, device=device, verbose=False)):
                done[p.stem] = [[teacher_class(r.names[int(c)]), round(float(s), 3), [round(v, 5) for v in b]]
                                for c, s, b in zip(r.boxes.cls.tolist(), r.boxes.conf.tolist(), r.boxes.xyxyn.tolist())
                                if teacher_class(r.names[int(c)]) is not None]
            if (i // 32) % 20 == 0:
                cache.write_text(json.dumps(done)); print(f"  teacher {cache.name}: {len(done)}/{len(images)}", flush=True)
        cache.write_text(json.dumps(done))
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(Path.home() / ".cache/lyne-data/yolo"))
    ap.add_argument("--out", default=str(Path.home() / ".cache/lyne-data/yolo_clean"))
    ap.add_argument("--teacher", default="yolov8m.pt")
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--conf", type=float, default=0.15, help="teacher threshold for confirming a label")
    ap.add_argument("--add-conf", type=float, default=0.5, help="teacher threshold for adding a missing rare-class box")
    ap.add_argument("--min-clip-agree", type=float, default=0.4)
    ap.add_argument("--device", default="cpu")
    a = ap.parse_args()
    src, out = Path(a.src), Path(a.out)
    stats = Counter()
    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True, exist_ok=True)
        (out / "labels" / split).mkdir(parents=True, exist_ok=True)
        images = sorted(p for p in (src / "images" / split).iterdir() if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
        teach = run_teacher(a.teacher, images, out / f"teacher_{split}.json", a.imgsz, a.conf, a.device)
        labels = {}
        for p in images:
            f = src / "labels" / split / (p.stem + ".txt")
            labels[p.stem] = [(int(r[0]), [float(v) for v in r[1:5]]) for r in
                              (l.split() for l in (f.read_text().splitlines() if f.exists() else [])) if r]
        clip_tot, clip_ok = Counter(), Counter()
        for stem, rows in labels.items():
            clip = stem.rsplit("_", 1)[0]
            for c, b in rows:
                clip_tot[clip] += 1
                if any(iou(b, t[2]) > 0.3 for t in teach.get(stem, [])): clip_ok[clip] += 1
        bad = {c for c in clip_tot if clip_tot[c] >= 20 and clip_ok[c] / clip_tot[c] < a.min_clip_agree}
        stats[f"{split}_clips"] = len({s.rsplit('_', 1)[0] for s in labels}); stats[f"{split}_clips_dropped"] = len(bad)
        for p in images:
            stem = p.stem
            if stem.rsplit("_", 1)[0] in bad: stats[f"{split}_frames_dropped"] += 1; continue
            t = [x for x in teach.get(stem, [])]
            kept = []
            for c, b in labels[stem]:
                if c in RARE and not any(iou(b, x[2]) > 0.3 and x[0] in COMPAT[c] for x in t):
                    stats[f"{split}_dropped_cls{c}"] += 1; continue
                kept.append((c, b))
            for c, s, bb in t:
                if c in RARE and s >= a.add_conf:
                    cxcywh = [(bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2, bb[2] - bb[0], bb[3] - bb[1]]
                    if cxcywh[2] * cxcywh[3] < 1e-4: continue
                    if not any(iou(k[1], bb) > 0.3 for k in kept):
                        kept.append((c, cxcywh)); stats[f"{split}_added_cls{c}"] += 1
            for c, _ in kept: stats[f"{split}_cls{c}"] += 1
            dst = out / "images" / split / p.name
            if not dst.exists(): os.symlink(p.resolve(), dst)
            (out / "labels" / split / (stem + ".txt")).write_text(
                "".join(f"{c} {b[0]:.6f} {b[1]:.6f} {b[2]:.6f} {b[3]:.6f}\n" for c, b in kept))
            stats[f"{split}_frames"] += 1
    y = (src / "data.yaml").read_text().replace(str(src), str(out))
    (out / "data.yaml").write_text(y)
    (out / "clean_stats.json").write_text(json.dumps(dict(sorted(stats.items())), indent=1))
    print(json.dumps(dict(sorted(stats.items())), indent=1))


if __name__ == "__main__":
    main()
