#!/usr/bin/env python3
"""Indian-roads object detector for Lyne ADAS: download -> prepare -> train -> eval -> export.

Fine-tunes YOLOv8n (COCO weights) on the ThirdEye Labs Indian Road Driving Dataset and scores it
on the human-labelled Indian Road Detection Benchmark (dev split), next to the stock COCO model.
Every step is resumable: re-running a command skips work that is already on disk.

  download  shards of thirdeyelabs/indian-road-dataset (train data, pseudo-labels) and
            thirdeyelabs/indian-road-benchmark (450 human-labelled dev frames, held out).
  prepare   WebDataset shards -> YOLO dataset (9 classes, long side 640, train/val split by clip).
  train     ultralytics YOLOv8n fine-tune from COCO weights at imgsz 320 (CPU-friendly defaults).
  eval      per-class AP50 / AP50-95 on the benchmark for the new model and the stock COCO model,
            plus the official evaluate.py score when available; writes a Markdown report.
  export    INT8 TFLite at 320 and 256 (calibrated on the val images) + object_labels.txt.
  smoke     offline wiring check: synthetic dataset -> 1 epoch -> INT8 export -> shape check.

Data: CC BY 4.0, ThirdEye Labs. Both datasets are gated (access is granted immediately): run
`hf auth login` and click "Get access" on each dataset page first.

Examples
  python models/train/train_indian.py download --shards 15
  python models/train/train_indian.py prepare
  python models/train/train_indian.py train --epochs 60
  python models/train/train_indian.py eval
  python models/train/train_indian.py export
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tarfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
# Datasets and runs are large: keep them out of the repo.
DEFAULT_WORK = Path(os.environ.get("LYNE_DATA", Path.home() / ".cache" / "lyne-data"))
TRAIN_REPO = "thirdeyelabs/indian-road-dataset"
BENCH_REPO = "thirdeyelabs/indian-road-benchmark"
TOTAL_SHARDS = 646
FRAMES_PER_SHARD = 1000

# Output classes, in model order. These exact strings go into object_labels.txt for the app.
TARGET_CLASSES = ["person", "rider", "bicycle", "motorcycle", "autorickshaw", "car", "truck", "bus", "animal"]

# Source category (normalized) -> target class. None = dropped. "vehicle fallback" is handled by
# the --fallback policy (see fallback_target).
SOURCE_MAP: Dict[str, Optional[str]] = {
    "person": "person", "pedestrian": "person",
    "rider": "rider", "motorcyclist": "rider", "cyclist": "rider",
    "bicycle": "bicycle", "bike": "bicycle",
    "motorcycle": "motorcycle", "motorbike": "motorcycle", "scooter": "motorcycle",
    "autorickshaw": "autorickshaw", "auto rickshaw": "autorickshaw", "rickshaw": "autorickshaw",
    "auto": "autorickshaw", "tuk tuk": "autorickshaw",
    "car": "car",
    "truck": "truck", "tempo": "truck",
    "bus": "bus",
    "animal": "animal", "cow": "animal", "dog": "animal",
    "traffic light": None, "traffic sign": None,
}
FALLBACK_NAMES = {"vehicle fallback", "vehiclefallback", "vehicle"}

# COCO-80 names a stock model can emit -> target class (for a fair comparison).
COCO_TO_TARGET: Dict[str, str] = {
    "person": "person", "bicycle": "bicycle", "motorcycle": "motorcycle", "car": "car",
    "truck": "truck", "bus": "bus",
    **{a: "animal" for a in ("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")},
}

IMG_EXT = (".jpg", ".jpeg", ".png")


# ============================================================================ class mapping

def norm_name(name: str) -> str:
    """'Auto-Rickshaw' / 'vehicle_fallback' -> 'auto rickshaw' / 'vehicle fallback'."""
    s = re.sub(r"[_\-]+", " ", str(name).strip().lower())
    return re.sub(r"\s+", " ", s)


def fallback_target(policy: str) -> Optional[str]:
    if policy == "car":
        return "car"
    if policy == "drop":
        return None
    raise ValueError(f"unknown --fallback policy {policy!r} (use car or drop)")


def map_category(name: str, fallback: str = "car") -> Optional[str]:
    """Source dataset category -> one of TARGET_CLASSES, or None to drop the box."""
    n = norm_name(name)
    if n in FALLBACK_NAMES:
        return fallback_target(fallback)
    if n in SOURCE_MAP:
        return SOURCE_MAP[n]
    if n.replace(" ", "") in SOURCE_MAP:
        return SOURCE_MAP[n.replace(" ", "")]
    return None


def map_prediction_name(name: str, model_has_rider: bool = True) -> List[str]:
    """A model's class name -> target class(es) it is scored as.

    Works for the fine-tuned model (exact target names) and for a stock COCO model (COCO names,
    animals merged). Riders are people, so a model without a rider class has its person boxes
    scored for rider too - the same rule as the benchmark's official scorer.
    """
    n = norm_name(name)
    if n == "person" and not model_has_rider:
        return ["person", "rider"]
    if n in TARGET_CLASSES:
        return [n]
    if n in COCO_TO_TARGET:
        return [COCO_TO_TARGET[n]]
    return []


# ============================================================================ annotation parsing

class AnnotationFormatError(ValueError):
    pass


def _keys_preview(obj, depth: int = 0) -> str:
    if isinstance(obj, dict):
        parts = []
        for k, v in list(obj.items())[:12]:
            parts.append(f"{k}: {_keys_preview(v, depth + 1) if depth < 2 else type(v).__name__}")
        return "{" + ", ".join(parts) + ("" if len(obj) <= 12 else ", ...") + "}"
    if isinstance(obj, list):
        return f"list[{len(obj)}] of " + (_keys_preview(obj[0], depth + 1) if obj and depth < 2 else "?")
    return type(obj).__name__


def label_score(label: dict) -> Optional[float]:
    """Detection confidence of a pseudo-label, if the export carries one."""
    for src in (label, label.get("attributes") or {}):
        for k in ("score", "confidence", "conf"):
            v = src.get(k)
            if isinstance(v, (int, float)):
                return float(v)
    return None


def _box_xyxy(label: dict) -> Optional[Tuple[float, float, float, float]]:
    b = label.get("box2d")
    if isinstance(b, dict) and all(k in b for k in ("x1", "y1", "x2", "y2")):
        return float(b["x1"]), float(b["y1"]), float(b["x2"]), float(b["y2"])
    bb = label.get("bbox")
    if isinstance(bb, (list, tuple)) and len(bb) == 4:  # COCO x,y,w,h
        x, y, w, h = map(float, bb)
        return x, y, x + w, y + h
    return None


def parse_bdd_frame(frame: dict, fallback: str = "car", min_score: float = 0.0):
    """One BDD100K frame dict -> (boxes, low_boxes, stats).

    boxes: [(target_class, (x1,y1,x2,y2) pixels)] at or above min_score (or without a score).
    low_boxes: kept-class boxes below min_score - unsure pseudo-labels the caller should neither
    train on as positives nor as background (see --low-conf).
    """
    if not isinstance(frame, dict) or not isinstance(frame.get("labels", []), list):
        raise AnnotationFormatError(f"expected a BDD100K frame dict with 'labels', found {_keys_preview(frame)}")
    stats: Counter = Counter()
    out, low = [], []
    for lab in frame.get("labels") or []:
        if not isinstance(lab, dict):
            continue
        cat = lab.get("category", lab.get("category_name", lab.get("label")))
        box = _box_xyxy(lab)
        if cat is None or box is None:
            stats["no_box_or_category"] += 1
            continue
        tgt = map_category(cat, fallback)
        if tgt is None:
            stats[f"dropped:{norm_name(cat)}"] += 1
            continue
        s = label_score(lab)
        if s is not None:
            stats["with_score"] += 1
            if s < min_score:
                stats["below_min_score"] += 1
                low.append((tgt, box))
                continue
        stats[tgt] += 1
        out.append((tgt, box))
    return out, low, dict(stats)


def load_frame_json(raw: bytes) -> dict:
    """A per-frame json from a shard. Accepts a frame dict, a 1-element list, or {'frames': [..]}."""
    obj = json.loads(raw)
    if isinstance(obj, list) and len(obj) == 1:
        obj = obj[0]
    if isinstance(obj, dict) and "labels" not in obj and isinstance(obj.get("frames"), list) and len(obj["frames"]) == 1:
        merged = dict(obj["frames"][0])
        merged.setdefault("name", obj.get("name"))
        obj = merged
    if not isinstance(obj, dict) or ("labels" not in obj and "name" not in obj):
        raise AnnotationFormatError(
            "per-frame json is not BDD100K-like (expected keys 'name' and 'labels'); found "
            + _keys_preview(obj))
    return obj


def load_coco(path_or_obj) -> dict:
    """Load a COCO detection json and index it.

    Returns {'images': {id: image}, 'cats': {id: name}, 'anns': {image_id: [ann]}}. Fails with the
    keys that were found when the file is not COCO-like.
    """
    obj = path_or_obj
    if not isinstance(obj, (dict, list)):
        with open(path_or_obj, "rb") as f:
            obj = json.load(f)
    if not (isinstance(obj, dict) and isinstance(obj.get("images"), list) and isinstance(obj.get("annotations"), list)):
        raise AnnotationFormatError(
            "expected a COCO json with 'images', 'annotations' and 'categories' lists; found "
            + _keys_preview(obj)
            + ". If this is a BDD100K list of frames, use the per-frame shard jsons (prepare reads them).")
    cats = {c["id"]: c.get("name", str(c["id"])) for c in obj.get("categories", [])}
    images = {im["id"]: im for im in obj["images"]}
    anns: Dict[int, list] = defaultdict(list)
    for a in obj["annotations"]:
        if "bbox" not in a or "image_id" not in a:
            raise AnnotationFormatError(f"COCO annotation without bbox/image_id: {_keys_preview(a)}")
        anns[a["image_id"]].append(a)
    return {"images": images, "cats": cats, "anns": anns}


def coco_ground_truth(coco: dict, fallback_eval: str = "ignore") -> Dict[int, Dict[str, list]]:
    """COCO -> {image_id: {'gt': [(cls, xyxy)], 'ignore': [(cls|None, xyxy)]}} in TARGET_CLASSES.

    iscrowd=1 boxes are ignore regions (never ground truth). 'vehicle fallback' boxes become
    class-agnostic ignore regions by default: neither model is asked to name them.
    """
    out: Dict[int, Dict[str, list]] = {}
    for iid in coco["images"]:
        gt, ign = [], []
        for a in coco["anns"].get(iid, []):
            name = a.get("category_name") or coco["cats"].get(a.get("category_id"), "")
            x, y, w, h = map(float, a["bbox"])
            box = (x, y, x + w, y + h)
            n = norm_name(name)
            if n in FALLBACK_NAMES:
                if fallback_eval == "ignore":
                    ign.append((None, box))
                    continue
                tgt = fallback_target(fallback_eval)
            else:
                tgt = map_category(name)
            if tgt is None:
                continue
            if a.get("iscrowd", 0):
                ign.append((tgt, box))
            else:
                gt.append((tgt, box))
        out[iid] = {"gt": gt, "ignore": ign}
    return out


# ============================================================================ split / clip ids

def clip_id_of(key: str, frame: Optional[dict] = None) -> str:
    """Clip (drive) id for leakage-free splitting.

    Prefers the BDD 'name' ('clip_abc/0042.jpg' -> 'clip_abc'); falls back to the shard key
    '{clip_id}_{frame:04d}' -> clip_id.
    """
    name = (frame or {}).get("name") if isinstance(frame, dict) else None
    if isinstance(name, str) and "/" in name:
        return name.split("/")[0]
    base = os.path.basename(key)
    m = re.match(r"^(.*)_(\d+)$", base)
    return m.group(1) if m else base


def split_of(clip_id: str, val_pct: float) -> str:
    h = int(hashlib.sha1(clip_id.encode()).hexdigest()[:8], 16) % 10000
    return "val" if h < val_pct * 100 else "train"


# ============================================================================ geometry helpers

def to_yolo_line(cls_idx: int, box, img_w: int, img_h: int) -> Optional[str]:
    x1, y1, x2, y2 = box
    x1 = min(max(x1, 0.0), img_w); x2 = min(max(x2, 0.0), img_w)
    y1 = min(max(y1, 0.0), img_h); y2 = min(max(y2, 0.0), img_h)
    w, h = x2 - x1, y2 - y1
    if w <= 0 or h <= 0:
        return None
    return f"{cls_idx} {(x1 + w / 2) / img_w:.6f} {(y1 + h / 2) / img_h:.6f} {w / img_w:.6f} {h / img_h:.6f}"


def resize_long_side(img, long_side: int):
    w, h = img.size
    s = long_side / max(w, h)
    if s >= 1.0:
        return img, 1.0
    from PIL import Image
    return img.resize((max(1, round(w * s)), max(1, round(h * s))), Image.BILINEAR), s


# ============================================================================ shards -> YOLO

def iter_shard_samples(tar_path: Path) -> Iterator[Tuple[str, Dict[str, bytes]]]:
    """Yield (key, {ext: bytes}) per sample of a WebDataset tar (members grouped by key)."""
    cur_key, cur = None, {}
    with tarfile.open(tar_path, "r|*") as tf:
        for m in tf:
            if not m.isfile():
                continue
            name = m.name
            base = os.path.basename(name)
            if "." not in base:
                continue
            key, ext = name[: name.index(".", len(name) - len(base))], base.split(".", 1)[1].lower()
            if key != cur_key and cur_key is not None:
                yield cur_key, cur
                cur = {}
            cur_key = key
            if ext in ("jpg", "jpeg", "json"):
                f = tf.extractfile(m)
                cur["jpg" if ext == "jpeg" else ext] = f.read() if f else b""
    if cur_key is not None:
        yield cur_key, cur


def convert_sample(key: str, files: Dict[str, bytes], out_root: Path, *, val_pct: float, fallback: str,
                   min_score: float, long_side: int, min_box_px: float, stats: Counter,
                   low_conf: str = "mask") -> Optional[str]:
    """One shard sample -> image + label file under out_root/{images,labels}/{split}. Returns split."""
    if "jpg" not in files or "json" not in files:
        stats["skipped_incomplete"] += 1
        return None
    frame = load_frame_json(files["json"])
    clip = clip_id_of(key, frame)
    split = split_of(clip, val_pct)
    stem = re.sub(r"[^A-Za-z0-9_.-]", "_", os.path.basename(key))
    img_out = out_root / "images" / split / f"{stem}.jpg"
    lab_out = out_root / "labels" / split / f"{stem}.txt"
    if img_out.exists() and lab_out.exists():
        stats["already_done"] += 1
        return split
    from PIL import Image
    img = Image.open(io.BytesIO(files["jpg"])).convert("RGB")
    w0, h0 = img.size
    boxes, low, fstats = parse_bdd_frame(frame, fallback, min_score)
    stats.update(fstats)
    if low and low_conf == "skip-frame":
        stats["frames_skipped_low_conf"] += 1
        return None
    if low and low_conf == "mask":
        # Grey out unsure pseudo-labels: neither a positive nor a background patch to learn from.
        from PIL import ImageDraw
        d = ImageDraw.Draw(img)
        for _, lb in low:
            # Never paint over a confident object (e.g. an unsure rider on a sure motorcycle): drop instead.
            if any(_ioa(kb, lb) > 0.2 or _ioa(lb, kb) > 0.2 for _, kb in boxes):
                stats["low_conf_dropped_overlap"] += 1
                continue
            d.rectangle(list(lb), fill=(114, 114, 114))
            stats["low_conf_masked"] += 1
    tod = norm_name(((frame.get("attributes") or {}).get("timeofday")) or "unknown")
    stats[f"timeofday:{tod}"] += 1
    img, s = resize_long_side(img, long_side)
    lines = []
    for cls, (x1, y1, x2, y2) in boxes:
        if (x2 - x1) * s < min_box_px or (y2 - y1) * s < min_box_px:
            stats["tiny_box_dropped"] += 1
            continue
        ln = to_yolo_line(TARGET_CLASSES.index(cls), (x1, y1, x2, y2), w0, h0)
        if ln:
            lines.append(ln)
    img_out.parent.mkdir(parents=True, exist_ok=True)
    lab_out.parent.mkdir(parents=True, exist_ok=True)
    tmp = img_out.with_suffix(".tmp.jpg")
    img.save(tmp, quality=90)
    lab_out.write_text("\n".join(lines) + ("\n" if lines else ""))
    tmp.replace(img_out)  # image last: a crash mid-sample leaves no half-written pair behind
    stats[f"images_{split}"] += 1
    return split


def write_data_yaml(out_root: Path) -> Path:
    p = out_root / "data.yaml"
    lines = [f"path: {out_root.resolve()}", "train: images/train", "val: images/val", f"nc: {len(TARGET_CLASSES)}", "names:"]
    lines += [f"  {i}: {n}" for i, n in enumerate(TARGET_CLASSES)]
    p.write_text("\n".join(lines) + "\n")
    return p


# ============================================================================ AP evaluation

def _iou(a, b) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / u if u > 0 else 0.0


def _ioa(det, region) -> float:
    """Intersection over the detection's area (COCO crowd rule)."""
    ix = max(0.0, min(det[2], region[2]) - max(det[0], region[0]))
    iy = max(0.0, min(det[3], region[3]) - max(det[1], region[1]))
    a = (det[2] - det[0]) * (det[3] - det[1])
    return ix * iy / a if a > 0 else 0.0


def average_precision(gt: Dict[int, Dict[str, list]], preds: Dict[int, List[Tuple[str, float, tuple]]],
                      cls: str, iou_thr: float) -> Optional[float]:
    """COCO-style 101-point AP for one class at one IoU threshold, with ignore regions.

    Detections matched to nothing but overlapping an ignore region (IoA >= thr) are neither TP nor
    FP. Returns None when the class has no ground truth.
    """
    n_gt = 0
    scored: List[Tuple[float, int]] = []  # (score, 1=tp / 0=fp)
    for iid, g in gt.items():
        gts = [b for c, b in g["gt"] if c == cls]
        ign = [b for c, b in g["ignore"] if c is None or c == cls]
        n_gt += len(gts)
        dets = sorted([(s, b) for c, s, b in preds.get(iid, []) if c == cls], key=lambda x: -x[0])
        used = [False] * len(gts)
        for s, b in dets:
            best, bj = iou_thr, -1
            for j, gb in enumerate(gts):
                if used[j]:
                    continue
                v = _iou(b, gb)
                if v >= best:
                    best, bj = v, j
            if bj >= 0:
                used[bj] = True
                scored.append((s, 1))
            elif any(_ioa(b, r) >= iou_thr for r in ign):
                continue
            else:
                scored.append((s, 0))
    if n_gt == 0:
        return None
    scored.sort(key=lambda x: -x[0])
    tp = fp = 0
    prec, rec = [], []
    for _, ok in scored:
        tp += ok; fp += 1 - ok
        prec.append(tp / (tp + fp)); rec.append(tp / n_gt)
    for i in range(len(prec) - 2, -1, -1):  # precision envelope
        prec[i] = max(prec[i], prec[i + 1])
    total = 0.0
    j = 0
    for k in range(101):
        r = k / 100
        while j < len(rec) and rec[j] < r:
            j += 1
        total += prec[j] if j < len(prec) else 0.0
    return total / 101


def evaluate_predictions(gt, preds, classes: Sequence[str] = TARGET_CLASSES) -> Dict[str, Dict[str, Optional[float]]]:
    thrs = [0.5 + 0.05 * i for i in range(10)]
    res = {}
    for c in classes:
        aps = [average_precision(gt, preds, c, t) for t in thrs]
        res[c] = {"ap50": aps[0], "ap": None if aps[0] is None else sum(aps) / len(aps)}
    return res


# ============================================================================ hub helpers

def _hf_login_hint() -> str:
    return ("Both datasets are gated (free, access is granted immediately):\n"
            f"  1. hf auth login            (token from https://huggingface.co/settings/tokens)\n"
            f"  2. open https://huggingface.co/datasets/{TRAIN_REPO} and https://huggingface.co/datasets/{BENCH_REPO}\n"
            "     while logged in and click 'Get access' on each.")


def _require_hub():
    try:
        from huggingface_hub import HfApi
    except ImportError:
        sys.exit("huggingface_hub is not installed: pip install -U huggingface_hub")
    api = HfApi()
    try:
        who = api.whoami()
    except Exception:
        sys.exit("Not logged in to Hugging Face.\n" + _hf_login_hint())
    return api, who.get("name", "?")


def _gated_exit(e: Exception, repo: str):
    sys.exit(f"Download from {repo} was refused ({type(e).__name__}).\n" + _hf_login_hint())


def pick_shards(n: int, total: int = TOTAL_SHARDS) -> List[int]:
    """n shard indices spread evenly over the dataset (more clips/conditions than the first n)."""
    n = max(1, min(n, total))
    return sorted({int(i * total / n) for i in range(n)})


# ============================================================================ commands

def cmd_download(a) -> int:
    api, who = _require_hub()
    print(f"hugging face user: {who}")
    from huggingface_hub import hf_hub_download, snapshot_download
    from huggingface_hub.utils import GatedRepoError, HfHubHTTPError

    raw = Path(a.work) / "hf"
    if not a.skip_benchmark:
        dst = raw / "indian-road-benchmark"
        print(f"benchmark -> {dst}")
        try:
            snapshot_download(BENCH_REPO, repo_type="dataset", local_dir=dst,
                              allow_patterns=["README.md", "evaluate.py", "annotations/dev.json", "images/dev/*"])
        except (GatedRepoError, HfHubHTTPError) as e:
            _gated_exit(e, BENCH_REPO)
        n = len(list((dst / "images" / "dev").glob("*.jpg")))
        print(f"  {n} dev images")
    if a.shards > 0:
        files = set(api.list_repo_files(TRAIN_REPO, repo_type="dataset"))
        tars = sorted(f for f in files if f.startswith("data/") and f.endswith(".tar"))
        if not tars:
            sys.exit(f"no data/*.tar shards listed in {TRAIN_REPO}; found: {sorted(files)[:10]}")
        idx = pick_shards(a.shards, len(tars)) if a.spread else list(range(min(a.shards, len(tars))))
        dst = raw / "indian-road-dataset"
        print(f"{len(idx)} of {len(tars)} shards (~{len(idx) * FRAMES_PER_SHARD} frames) -> {dst}")
        for k, i in enumerate(idx, 1):
            print(f"  [{k}/{len(idx)}] {tars[i]}", flush=True)
            try:
                hf_hub_download(TRAIN_REPO, tars[i], repo_type="dataset", local_dir=dst)
            except (GatedRepoError, HfHubHTTPError) as e:
                _gated_exit(e, TRAIN_REPO)
    print("done. next: prepare")
    return 0


def cmd_prepare(a) -> int:
    work = Path(a.work)
    shard_dir = Path(a.shards_dir) if a.shards_dir else work / "hf" / "indian-road-dataset" / "data"
    tars = sorted(shard_dir.glob("*.tar"))
    if not tars:
        sys.exit(f"no .tar shards in {shard_dir}; run download first")
    out = Path(a.out) if a.out else work / "yolo"
    fallback_target(a.fallback)  # validate
    # Resuming with different settings would mix two conversions (and could leak a clip into both
    # splits), so the settings are pinned to the output directory.
    cfg = {"val_pct": a.val_pct, "fallback": a.fallback, "min_score": a.min_score, "low_conf": a.low_conf,
           "long_side": a.long_side, "min_box_px": a.min_box_px}
    cfg_p = out / "prepare_config.json"
    if cfg_p.exists():
        old = json.loads(cfg_p.read_text())
        if old != cfg:
            sys.exit(f"{out} was prepared with {old}, now asked for {cfg}. Use the same settings to resume, "
                     f"or delete {out} (or pass --out) to start over.")
    out.mkdir(parents=True, exist_ok=True)
    cfg_p.write_text(json.dumps(cfg, indent=1))
    stats: Counter = Counter()
    done = 0
    for t in tars:
        print(f"{t.name}", flush=True)
        for key, files in iter_shard_samples(t):
            if a.max_images and done >= a.max_images:
                break
            try:
                if convert_sample(key, files, out, val_pct=a.val_pct, fallback=a.fallback, min_score=a.min_score, low_conf=a.low_conf,
                                  long_side=a.long_side, min_box_px=a.min_box_px, stats=stats):
                    done += 1
            except AnnotationFormatError as e:
                sys.exit(f"{t.name}:{key}: {e}")
        if a.max_images and done >= a.max_images:
            break
    yaml_p = write_data_yaml(out)
    (out / "prepare_stats.json").write_text(json.dumps(dict(sorted(stats.items())), indent=1))
    print(json.dumps(dict(sorted(stats.items())), indent=1))
    if stats.get("with_score", 0) == 0:
        print("note: labels carry no score field, so --min-score had no effect (all pseudo-labels kept)")
    n_tr = len(list((out / "images" / "train").glob("*.jpg")))
    n_va = len(list((out / "images" / "val").glob("*.jpg")))
    print(f"train {n_tr} / val {n_va} images; {yaml_p}")
    return 0


def _run_dir(work: Path, name: str) -> Path:
    return work / "runs" / name


def cmd_train(a) -> int:
    from ultralytics import YOLO
    work = Path(a.work)
    data = Path(a.data) if a.data else work / "yolo" / "data.yaml"
    if not data.exists():
        sys.exit(f"{data} missing; run prepare first")
    last = _run_dir(work, a.name) / "weights" / "last.pt"
    if last.exists() and not a.fresh:
        print(f"resuming {last}")
        YOLO(str(last)).train(resume=True)
    else:
        YOLO(a.weights).train(
            data=str(data), imgsz=a.imgsz, epochs=a.epochs, batch=a.batch, workers=a.workers,
            device=a.device, project=str((work / "runs").resolve()), name=a.name, exist_ok=True,
            patience=a.patience, fraction=a.fraction,
            cache=False if str(a.cache).lower() in ("false", "none", "0", "") else a.cache, seed=0, plots=False,
            # Left-hand traffic: a horizontal flip is still a plausible road scene for detection.
            fliplr=0.5, mosaic=1.0, close_mosaic=min(10, max(1, a.epochs // 5)),
        )
    best = _run_dir(work, a.name) / "weights" / "best.pt"
    print(f"best weights: {best}")
    return 0


def _predict(weights: str, images: List[Path], imgsz: int, device: str, conf: float = 0.01, max_det: int = 100):
    """{image_path: [(model_class_name, score, (x1,y1,x2,y2) pixels)]}"""
    from ultralytics import YOLO
    model = YOLO(weights)
    out = {}
    for i in range(0, len(images), 16):
        chunk = [str(p) for p in images[i:i + 16]]
        for p, r in zip(chunk, model.predict(chunk, imgsz=imgsz, conf=conf, max_det=max_det, device=device, verbose=False)):
            names = r.names
            b = r.boxes
            out[p] = [(names[int(c)], float(s), tuple(float(v) for v in xy))
                      for c, s, xy in zip(b.cls.tolist(), b.conf.tolist(), b.xyxy.tolist())]
    return out, {norm_name(n) for n in model.names.values()}


def _fmt(v: Optional[float]) -> str:
    return "n/a" if v is None else f"{100 * v:.1f}"


def cmd_eval(a) -> int:
    work = Path(a.work)
    bench = Path(a.bench) if a.bench else work / "hf" / "indian-road-benchmark"
    ann = bench / "annotations" / "dev.json"
    if not ann.exists():
        sys.exit(f"{ann} missing; run download first")
    coco = load_coco(ann)
    gt = coco_ground_truth(coco, a.fallback_eval)
    img_dir = bench / "images" / "dev"
    id_by_path, paths = {}, []
    for iid, im in coco["images"].items():
        p = img_dir / os.path.basename(im["file_name"])
        if p.exists():
            id_by_path[str(p)] = iid
            paths.append(p)
    if not paths:
        sys.exit(f"no benchmark images found in {img_dir}")
    gt = {i: g for i, g in gt.items() if i in id_by_path.values()}
    tod = {iid: str(im.get("time_of_day", "unknown")) for iid, im in coco["images"].items()}

    new_w = a.weights or str(_run_dir(work, a.name) / "weights" / "best.pt")
    runs = []
    if Path(new_w).exists():
        runs.append((f"fine-tuned @{a.imgsz}", new_w, a.imgsz))
    else:
        print(f"warning: {new_w} not found, scoring the stock model only")
    runs.append((f"stock COCO @{a.imgsz}", a.base, a.imgsz))
    if a.base_imgsz and a.base_imgsz != a.imgsz:
        runs.append((f"stock COCO @{a.base_imgsz}", a.base, a.base_imgsz))

    out_dir = work / "eval"
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for label, w, sz in runs:
        print(f"predicting: {label} ({w})", flush=True)
        raw, model_names = _predict(w, paths, sz, a.device)
        has_rider = "rider" in model_names
        preds: Dict[int, list] = defaultdict(list)
        official = []
        for p, dets in raw.items():
            iid = id_by_path[p]
            for name, s, box in dets:
                for tgt in map_prediction_name(name, has_rider):
                    preds[iid].append((tgt, s, box))
                x1, y1, x2, y2 = box
                official.append({"image_id": iid, "category_name": norm_name(name), "bbox": [x1, y1, x2 - x1, y2 - y1], "score": s})
        per_cls = evaluate_predictions(gt, preds)
        by_tod = {}
        for t in sorted(set(tod[i] for i in gt)):
            sub = {i: g for i, g in gt.items() if tod[i] == t}
            r = evaluate_predictions(sub, preds)
            vals = [v["ap"] for v in r.values() if v["ap"] is not None]
            by_tod[t] = (sum(vals) / len(vals)) if vals else None
        pj = out_dir / f"pred_{re.sub(r'[^A-Za-z0-9]+', '_', label).strip('_')}.json"
        pj.write_text(json.dumps(official))
        off = None
        scorer = bench / "evaluate.py"
        if scorer.exists():
            r = subprocess.run([sys.executable, "-I", str(scorer), str(ann), str(pj)], capture_output=True, text=True)
            off = (r.stdout + r.stderr).strip()
        results.append((label, w, per_cls, by_tod, off))

    md = ["# Indian Road Detection Benchmark - dev split", "",
          f"{len(paths)} frames, ground truth = boxes both labelling passes agreed on; one-pass boxes are ignore regions. "
          f"'vehicle fallback' boxes: {a.fallback_eval}. Traffic lights/signs are not scored here. "
          "A model without a rider class has its person boxes scored as rider too. Riders and autorickshaws "
          "cannot be named by COCO models (autorickshaw = 0). The official scorer covers person, bicycle, car, "
          "motorcycle, bus, truck, traffic light and animal (riders count as person); a model without a traffic-light "
          "class scores 0 on that eighth of it.", "",
          "Data: Indian Road Detection Benchmark, ThirdEye Labs, CC BY 4.0.", "",
          "## Per-class AP50 / AP50-95 (%)", ""]
    hdr = "| class | " + " | ".join(r[0] for r in results) + " |"
    md += [hdr, "|---|" + "---|" * len(results)]
    for c in TARGET_CLASSES:
        md.append(f"| {c} | " + " | ".join(f"{_fmt(r[2][c]['ap50'])} / {_fmt(r[2][c]['ap'])}" for r in results) + " |")

    def mean(r, key, cls=TARGET_CLASSES):
        v = [r[2][c][key] for c in cls if r[2][c][key] is not None]  # classes with ground truth
        return sum(v) / len(v) if v else None
    coco_named = [c for c in TARGET_CLASSES if c not in ("autorickshaw",)]
    md.append("| **mean, all 9** | " + " | ".join(f"**{_fmt(mean(r, 'ap50'))} / {_fmt(mean(r, 'ap'))}**" for r in results) + " |")
    md.append("| mean, COCO-nameable | " + " | ".join(f"{_fmt(mean(r, 'ap50', coco_named))} / {_fmt(mean(r, 'ap', coco_named))}" for r in results) + " |")
    md += ["", "## mAP50-95 by time of day (%)", "", hdr, "|---|" + "---|" * len(results)]
    for t in sorted(results[0][3]):
        md.append(f"| {t} | " + " | ".join(_fmt(r[3].get(t)) for r in results) + " |")
    md += ["", "## Official scorer (evaluate.py)", ""]
    for label, w, _, _, off in results:
        md += [f"### {label}", "", "```", off or "evaluate.py not downloaded", "```", ""]
    md += ["Published reference: YOLOv8n COCO at default 640 input = 19.3 mAP / 33.0 AP50 on dev.", ""]
    rep = out_dir / "report.md"
    rep.write_text("\n".join(md))
    print("\n".join(md))
    print(f"\nreport: {rep}")
    return 0


def export_tflite(weights: str, imgsz: int, data: Optional[str], out: Path, fraction: float = 1.0) -> Path:
    """INT8 TFLite via ultralytics (same path as the bundled COCO models), copied to `out`."""
    from ultralytics import YOLO
    kw = dict(format="tflite", int8=True, imgsz=imgsz, fraction=fraction)
    if data:
        kw["data"] = data
    exported = Path(YOLO(weights).export(**kw))
    if exported.is_dir():  # some versions return the saved_model dir
        cands = sorted(exported.glob("*int8*.tflite")) or sorted(exported.glob("*.tflite"))
        if not cands:
            raise RuntimeError(f"no .tflite in {exported}")
        exported = cands[0]
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(exported, out)
    return out


def tflite_io(path: Path):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        from tensorflow.lite import Interpreter  # type: ignore
    it = Interpreter(model_path=str(path))
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    return [int(x) for x in i["shape"]], str(i["dtype"].__name__), [int(x) for x in o["shape"]]


def check_detector_io(path: Path, imgsz: int, n_classes: int) -> str:
    ishape, idt, oshape = tflite_io(path)
    if ishape != [1, imgsz, imgsz, 3]:
        raise RuntimeError(f"{path.name}: input {ishape}, app needs [1,{imgsz},{imgsz},3] NHWC")
    if len(oshape) != 3 or (4 + n_classes) not in oshape[1:]:
        raise RuntimeError(f"{path.name}: output {oshape}, app needs [1,4+C,N] or [1,N,4+C] with C={n_classes}")
    return f"{path.name}: in {ishape} {idt}, out {oshape}"


def cmd_export(a) -> int:
    work = Path(a.work)
    w = a.weights or str(_run_dir(work, a.name) / "weights" / "best.pt")
    if not Path(w).exists():
        sys.exit(f"{w} missing; run train first (or pass --weights)")
    data = a.data or str(work / "yolo" / "data.yaml")
    out = Path(a.out) if a.out else work / "export"
    targets = [(320, out / "object_yolov8n_int8.tflite"), (256, out / "object_yolov8n_nano_int8.tflite")]
    for sz, p in targets:
        print(f"exporting INT8 @{sz} (calibration: val images of {data})", flush=True)
        export_tflite(w, sz, data, p, a.calib_fraction)
        print("  " + check_detector_io(p, sz, len(TARGET_CLASSES)))
    labels = out / "object_labels.txt"
    labels.write_text("\n".join(TARGET_CLASSES) + "\n")
    assets = (HERE.parent.parent / "app" / "src" / "main" / "assets" / "models").resolve()
    print(f"\nwrote {out}. Review the eval report, then copy into the app (not done automatically):")
    for _, p in targets:
        print(f"  cp {p.resolve()} {assets}/")
    print(f"  cp {labels.resolve()} {assets}/")
    return 0


def make_synthetic_yolo(root: Path, n_train: int = 8, n_val: int = 4, size: int = 320) -> Path:
    """Tiny YOLO dataset of coloured rectangles - only for wiring checks, not for accuracy."""
    import random
    from PIL import Image, ImageDraw
    rnd = random.Random(0)
    for split, n in (("train", n_train), ("val", n_val)):
        (root / "images" / split).mkdir(parents=True, exist_ok=True)
        (root / "labels" / split).mkdir(parents=True, exist_ok=True)
        for k in range(n):
            img = Image.new("RGB", (size, size), (rnd.randint(60, 120),) * 3)
            d = ImageDraw.Draw(img)
            lines = []
            for _ in range(3):
                c = rnd.randrange(len(TARGET_CLASSES))
                w, h = rnd.randint(30, 120), rnd.randint(30, 120)
                x, y = rnd.randint(0, size - w), rnd.randint(0, size - h)
                d.rectangle([x, y, x + w, y + h], fill=((c * 53) % 255, (c * 97) % 255, (c * 151) % 255))
                lines.append(to_yolo_line(c, (x, y, x + w, y + h), size, size))
            img.save(root / "images" / split / f"s{k}.jpg")
            (root / "labels" / split / f"s{k}.txt").write_text("\n".join(lines) + "\n")
    return write_data_yaml(root)


def cmd_smoke(a) -> int:
    """Synthetic data -> 1 epoch from COCO weights -> INT8 export at --imgsz -> I/O check."""
    from ultralytics import YOLO
    root = Path(a.work) / "smoke"
    if root.exists():
        shutil.rmtree(root)
    data = make_synthetic_yolo(root / "yolo")
    YOLO(a.weights).train(data=str(data), imgsz=320, epochs=1, batch=4, workers=0, device="cpu",
                          project=str((root / "runs").resolve()), name="smoke", exist_ok=True, plots=False, val=True)
    best = root / "runs" / "smoke" / "weights" / "best.pt"
    if not best.exists():
        best = root / "runs" / "smoke" / "weights" / "last.pt"
    for sz in a.imgsz:
        p = export_tflite(str(best), sz, str(data), root / "export" / f"smoke_{sz}_int8.tflite")
        print("OK " + check_detector_io(p, sz, len(TARGET_CLASSES)))
    return 0


# ============================================================================ CLI

def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=str(DEFAULT_WORK), help="data root for datasets, runs, exports (default $LYNE_DATA or ~/.cache/lyne-data)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("download", help="download training shards + benchmark from Hugging Face")
    p.add_argument("--shards", type=int, default=15, help="training shards (~1000 frames, ~17 clips each); 0 = none")
    p.add_argument("--spread", action="store_true",
                   help="spread the N shards over the whole dataset instead of taking the first N (more clips, but "
                        "does not reuse shards an earlier contiguous download already fetched)")
    p.add_argument("--skip-benchmark", action="store_true")
    p.set_defaults(fn=cmd_download)

    p = sub.add_parser("prepare", help="shards -> YOLO dataset")
    p.add_argument("--shards-dir", help="directory of .tar shards (default <work>/hf/indian-road-dataset/data)")
    p.add_argument("--out", help="output dir (default work/yolo)")
    p.add_argument("--fallback", choices=["car", "drop"], default="car",
                   help="'vehicle fallback' boxes: car (default; the app warns on it as a vehicle) or drop")
    p.add_argument("--min-score", type=float, default=0.5, help="pseudo-labels below this confidence are unsure (see --low-conf)")
    p.add_argument("--low-conf", choices=["mask", "drop", "skip-frame"], default="mask",
                   help="unsure boxes: mask = grey them out (default), drop = leave unlabelled, skip-frame = skip the frame")
    p.add_argument("--long-side", type=int, default=640)
    p.add_argument("--min-box-px", type=float, default=4.0, help="drop boxes thinner than this after resizing")
    p.add_argument("--val-pct", type=float, default=10.0, help="percent of clips held out for val")
    p.add_argument("--max-images", type=int, default=0, help="stop after N images (0 = all)")
    p.set_defaults(fn=cmd_prepare)

    p = sub.add_parser("train", help="fine-tune YOLOv8n (resumes automatically)")
    p.add_argument("--data")
    p.add_argument("--weights", default="yolov8n.pt", help="starting weights (COCO yolov8n.pt)")
    p.add_argument("--name", default="indian_v8n")
    p.add_argument("--imgsz", type=int, default=320)
    p.add_argument("--epochs", type=int, default=60)
    p.add_argument("--batch", type=int, default=32)
    p.add_argument("--workers", type=int, default=max(1, min(8, (os.cpu_count() or 2) - 1)))
    p.add_argument("--device", default="cpu")
    p.add_argument("--patience", type=int, default=15)
    p.add_argument("--fraction", type=float, default=1.0, help="use a fraction of the train set")
    p.add_argument("--cache", default="disk", help="ultralytics image cache: ram, disk or False")
    p.add_argument("--fresh", action="store_true", help="ignore an existing last.pt and start over")
    p.set_defaults(fn=cmd_train)

    p = sub.add_parser("eval", help="score fine-tuned vs stock model on the benchmark dev split")
    p.add_argument("--bench", help="benchmark dir (default <work>/hf/indian-road-benchmark)")
    p.add_argument("--weights", help="fine-tuned weights (default work/runs/<name>/weights/best.pt)")
    p.add_argument("--name", default="indian_v8n")
    p.add_argument("--base", default="yolov8n.pt", help="stock COCO weights")
    p.add_argument("--imgsz", type=int, default=320, help="input size used by the app")
    p.add_argument("--base-imgsz", type=int, default=640, help="also score the stock model at this size (0 = skip)")
    p.add_argument("--fallback-eval", choices=["ignore", "car", "drop"], default="ignore",
                   help="benchmark 'vehicle fallback' boxes: ignore regions (default), car, or drop")
    p.add_argument("--device", default="cpu")
    p.set_defaults(fn=cmd_eval)

    p = sub.add_parser("export", help="INT8 TFLite at 320 and 256 + object_labels.txt")
    p.add_argument("--weights")
    p.add_argument("--name", default="indian_v8n")
    p.add_argument("--data", help="dataset yaml whose val images calibrate INT8 (default work/yolo/data.yaml)")
    p.add_argument("--calib-fraction", type=float, default=1.0, help="fraction of the calibration images to use")
    p.add_argument("--out", help="output dir (default work/export)")
    p.set_defaults(fn=cmd_export)

    p = sub.add_parser("smoke", help="offline wiring check on synthetic data (1 epoch + INT8 export)")
    p.add_argument("--weights", default="yolov8n.pt")
    p.add_argument("--imgsz", type=int, nargs="+", default=[320, 256])
    p.set_defaults(fn=cmd_smoke)

    a = ap.parse_args(argv)
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
