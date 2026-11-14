#!/usr/bin/env python3
"""Real-drive replay: run the YOLO detector over a drive video and save per-frame detections.

The CSV feeds the JVM regression test (app/src/test/.../fusion/ReplayTest.kt), which pushes the
detections through ObjectTracker + FusionEngine and checks sanity invariants on real data.

Output (compact CSV):
  # lyne-replay v1 source=<file> start_s=196.0 end_s=296.0 frame_ms=125 aspect=2.1265 imgsz=320 conf=0.25 mask=hud
  t_ms,cls,score,l,t,r,b
  0,car,0.87,0.123,0.456,0.234,0.612
  ...
t_ms is relative to start_s. Every replayed frame time is k*frame_ms (k = 0..); a frame with no
rows simply had no detections. Boxes are normalized [0,1] to the full video frame.

Class names are the labels Lyne maps (COCO animals become "animal"); everything else is dropped.

--mask hud greys out the app's own HUD widgets before detection when the source is a screen
recording of Lyne (status/perf panels, lead card, gauges, control bar), and drops any detection
centred inside them. Boxes the app drew around real cars cannot be masked and stay in the video.

Example
  python tools/replay_drive.py master_hq.mp4 --start 196 --end 296 --fps 8 --mask hud \
      --out app/src/test/resources/replays/bengaluru_196_296.csv
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

KEEP = {"person": "person", "bicycle": "bicycle", "car": "car", "motorcycle": "motorcycle", "bus": "bus",
        "truck": "truck", "stop sign": "stop sign", "autorickshaw": "autorickshaw", "rider": "rider", "animal": "animal",
        **{a: "animal" for a in ("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")}}

# Normalized (l, t, r, b) regions of the Lyne HUD in a landscape screen recording.
HUD_MASK = [
    (0.00, 0.00, 0.25, 0.13),  # status panel (LIVE / tier / auto-cal)
    (0.36, 0.00, 0.64, 0.14),  # lead card (top centre)
    (0.71, 0.00, 1.00, 0.17),  # fps / perf panel
    (0.00, 0.17, 0.15, 0.78),  # TTC + headway gauges
    (0.85, 0.33, 1.00, 0.62),  # speed gauge
    (0.33, 0.81, 0.67, 1.00),  # control bar (bottom centre)
    (0.00, 0.84, 0.07, 0.93),  # class tag bottom-left
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video")
    ap.add_argument("--out", required=True)
    ap.add_argument("--weights", default="yolov8n.pt", help="detector weights (default COCO yolov8n.pt, as bundled)")
    ap.add_argument("--imgsz", type=int, default=320)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--start", type=float, default=0.0, help="start time in the source (s)")
    ap.add_argument("--end", type=float, default=0.0, help="end time in the source (s), 0 = to the end")
    ap.add_argument("--fps", type=float, default=8.0, help="replay rate (the app runs ~5-15 fps)")
    ap.add_argument("--mask", choices=["none", "hud"], default="none")
    ap.add_argument("--device", default="cpu")
    a = ap.parse_args()

    import cv2
    from ultralytics import YOLO

    cap = cv2.VideoCapture(a.video)
    if not cap.isOpened():
        sys.exit(f"cannot open {a.video}")
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    n_src = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    end = a.end if a.end > 0 else n_src / src_fps
    frame_ms = int(round(1000.0 / a.fps))
    aspect = cap.get(cv2.CAP_PROP_FRAME_WIDTH) / max(1.0, cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    model = YOLO(a.weights)
    masks = HUD_MASK if a.mask == "hud" else []

    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    rows, k = 0, 0
    cap.set(cv2.CAP_PROP_POS_MSEC, a.start * 1000.0)  # one seek, then read sequentially (seeking per frame is slow)
    src_idx = int(round(cap.get(cv2.CAP_PROP_POS_FRAMES)))
    with out.open("w") as f:
        f.write(f"# lyne-replay v1 source={Path(a.video).name} start_s={a.start} end_s={end} frame_ms={frame_ms} "
                f"aspect={aspect:.4f} imgsz={a.imgsz} conf={a.conf} mask={a.mask} weights={Path(a.weights).name}\n")
        f.write("t_ms,cls,score,l,t,r,b\n")
        while True:
            t_ms = k * frame_ms
            src_t = a.start + t_ms / 1000.0
            if src_t >= end:
                break
            want = int(round(src_t * src_fps))
            ok = True
            while ok and src_idx < want:
                ok = cap.grab()
                src_idx += 1
            ok, img = cap.read() if ok else (False, None)
            src_idx += 1
            if not ok:
                break
            h, w = img.shape[:2]
            for (l, t, r, b) in masks:
                img[int(t * h):int(b * h), int(l * w):int(r * w)] = 114
            res = model.predict(img, imgsz=a.imgsz, conf=a.conf, device=a.device, verbose=False)[0]
            for c, s, xy in zip(res.boxes.cls.tolist(), res.boxes.conf.tolist(), res.boxes.xyxyn.tolist()):
                name = KEEP.get(res.names[int(c)])
                if name is None:
                    continue
                l, t, r, b = xy
                cx, cy = (l + r) / 2, (t + b) / 2
                if any(ml <= cx <= mr and mt <= cy <= mb for ml, mt, mr, mb in masks):
                    continue
                f.write(f"{t_ms},{name},{s:.2f},{l:.3f},{t:.3f},{r:.3f},{b:.3f}\n")
                rows += 1
            k += 1
    print(f"{k} frames, {rows} detections -> {out} ({out.stat().st_size / 1024:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
