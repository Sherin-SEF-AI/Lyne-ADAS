#!/usr/bin/env python3
"""Export YOLOv8n to an INT8 .tflite for Lyne ADAS.

By default exports the COCO-pretrained yolov8n (person/bicycle/car/motorcycle/bus/truck are the
classes Lyne maps). For India-specific classes (autorickshaw) train/fine-tune first, then pass
--weights your_model.pt.

Usage:
  python convert_yolov8.py --imgsz 320 --out app/src/main/assets/models/object_yolov8n_int8.tflite
"""
import argparse
import shutil
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", default="yolov8n.pt", help="path to .pt (downloads COCO if absent)")
    ap.add_argument("--imgsz", type=int, default=320)
    ap.add_argument("--data", default="coco128.yaml", help="dataset yaml used as INT8 calibration set")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.weights)
    # int8=True triggers full-integer quantization using `data` as the representative dataset.
    exported = model.export(format="tflite", int8=True, imgsz=args.imgsz, data=args.data)

    src = Path(exported)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(src, out)
    print(f"wrote {out} ({out.stat().st_size/1e6:.1f} MB) from {src}")


if __name__ == "__main__":
    main()
