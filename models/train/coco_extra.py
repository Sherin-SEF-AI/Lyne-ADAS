#!/usr/bin/env python3
"""Add human-labelled COCO images for the classes the ThirdEye data barely has: animals and buses.

After label cleaning the Indian training set holds only ~59 real animal boxes and ~1k buses, so the
fine-tuned model never learns them. This picks COCO train2017 images that contain an animal Lyne
warns about (cow, dog, horse, sheep, elephant, cat) or a bus, downloads them, and writes YOLO labels
in Lyne's 9-class scheme. Images with motorcycles or bicycles are skipped: COCO labels their riders
"person", Lyne's scheme calls them "rider", and mixing the two would contradict the Indian labels.

Output: <out>/images/{train,val}, <out>/labels/{train,val}; then a combined data yaml listing both
<yolo_clean> and this folder (written next to it as data_plus_coco.yaml).
"""
import argparse, json, random, urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

OURS = {"person": 0, "bicycle": 2, "motorcycle": 3, "car": 5, "truck": 6, "bus": 7,
        "cow": 8, "dog": 8, "horse": 8, "sheep": 8, "elephant": 8, "cat": 8}
WANT = {"cow": 900, "dog": 700, "horse": 500, "sheep": 350, "elephant": 200, "cat": 150, "bus": 900}
SKIP = {"motorcycle", "bicycle"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--coco", default=str(Path.home() / ".cache/lyne-data/coco"))
    ap.add_argument("--clean", default=str(Path.home() / ".cache/lyne-data/yolo_clean"))
    ap.add_argument("--val-pct", type=float, default=8.0)
    ap.add_argument("--min-box", type=float, default=0.01, help="drop boxes thinner than this (normalized)")
    a = ap.parse_args()
    root = Path(a.coco); random.seed(0)
    d = json.loads((root / "annotations/instances_train2017.json").read_text())
    cname = {c["id"]: c["name"] for c in d["categories"]}
    imgs = {i["id"]: i for i in d["images"]}
    anns = defaultdict(list)
    for x in d["annotations"]:
        if not x.get("iscrowd"): anns[x["image_id"]].append(x)
    has = defaultdict(set)
    for iid, lst in anns.items():
        for x in lst: has[iid].add(cname[x["category_id"]])
    chosen = set()
    for cls, n in WANT.items():
        pool = [i for i in has if cls in has[i] and not (has[i] & SKIP) and i not in chosen]
        random.shuffle(pool); chosen.update(pool[:n])
    chosen = sorted(chosen)
    out = root
    stats = Counter()

    def fetch(iid):
        im = imgs[iid]; split = "val" if random.Random(iid).random() * 100 < a.val_pct else "train"
        dst = out / "images" / split / im["file_name"]
        if not dst.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            urllib.request.urlretrieve(f"http://images.cocodataset.org/train2017/{im['file_name']}", dst)
        W, H = im["width"], im["height"]; rows = []
        for x in anns[iid]:
            c = OURS.get(cname[x["category_id"]])
            if c is None: continue
            bx, by, bw, bh = x["bbox"]
            if bw / W < a.min_box or bh / H < a.min_box: continue
            rows.append(f"{c} {(bx + bw / 2) / W:.6f} {(by + bh / 2) / H:.6f} {bw / W:.6f} {bh / H:.6f}\n")
        lab = out / "labels" / split / (Path(im["file_name"]).stem + ".txt")
        lab.parent.mkdir(parents=True, exist_ok=True); lab.write_text("".join(rows))
        return split, [int(r.split()[0]) for r in rows]

    with ThreadPoolExecutor(16) as ex:
        for split, cls in ex.map(fetch, chosen):
            stats[f"{split}_images"] += 1
            for c in cls: stats[f"{split}_cls{c}"] += 1
    clean = Path(a.clean)
    y = (clean / "data.yaml").read_text()
    y = "\n".join(l for l in y.splitlines() if not l.startswith(("path:", "train:", "val:")))
    y = (f"path: /\ntrain:\n  - {clean}/images/train\n  - {out}/images/train\n"
         f"val:\n  - {clean}/images/val\n  - {out}/images/val\n" + y + "\n")
    (clean / "data_plus_coco.yaml").write_text(y)
    print(json.dumps(dict(sorted(stats.items())), indent=1))


if __name__ == "__main__":
    main()
