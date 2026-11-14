# Lyne ADAS - Models

The app ships with **no real weights**; it runs on in-code stub detectors until you drop INT8
`.tflite` files into [`app/src/main/assets/models/`](../app/src/main/assets/models/). Detectors are
selected at runtime by [`DetectorProvider`](../app/src/main/java/com/lyne/adas/l1/inference/DetectorProvider.kt):
if a model asset exists and loads on a usable backend it is used, otherwise the stub is.

## Expected files

| File | Model | Used by tier |
|---|---|---|
| `object_yolov8n_int8.tflite` | YOLOv8n, INT8 | A / B |
| `object_yolov8n_nano_int8.tflite` | YOLOv8n @ smaller imgsz, INT8 | C |
| `lane_ufld_int8.tflite` | UFLD / ultralight lane seg, INT8 | A / B |
| `lane_ufld_nano_int8.tflite` | lane @ smaller imgsz, INT8 | C |
| `sign_mobilenetv3_int8.tflite` | MobileNetV3-Small classifier, INT8 | A / B (TSR off on C) |
| `drivable_twinlite.tflite` | TwinLiteNet drivable-area segmentation, FP32, NHWC [1,360,640,3]→[1,360,640,2] | A / B (Tier C uses classical) |
| `object_labels.txt`, `sign_labels.txt` | label maps | always (bundled) |

## I/O contract (must match, or adjust the decoder)

All models: **NHWC**, single input `[1, H, W, 3]`, **RGB**, pixels normalized to **[0,1]**.
`TensorPreprocessor` reads the input tensor's dtype + quant params and writes accordingly:
- FLOAT32 input → writes `pixel/255`.
- UINT8 input → writes raw `0..255`.
- INT8 input → writes `round((pixel/255)/scale + zeroPoint)`.

So an INT8 export whose input quant is `scale≈1/255, zeroPoint≈-128` is handled correctly.

### Object detector (YOLOv8n)
- Input: `[1,320,320,3]` (Tier A/B) or `[1,256,256,3]` (nano / Tier C).
- Output: `[1, 4+numClasses, numBoxes]` **or** `[1, numBoxes, 4+numClasses]` - the decoder detects
  layout automatically. Box channels are `cx,cy,w,h`, normalized `[0,1]` (input-pixel coords are
  auto-normalized if values exceed ~1.5). Class scores follow (post-sigmoid).
- Decode + per-class NMS happen in `ObjectDetector`.

### Lane (UFLD / segmentation)
- Input: `[1,288,512,3]` typical (any HxW works).
- Output (assumed): per-pixel lane probabilities `[1,H,W,C]` or `[1,C,H,W]`, channel 0 = background.
  If you use the **UFLD griding** head instead (`[1, gridding+1, rows, lanes]`), adapt the decode in
  `LaneDetector` - the current decoder is segmentation-style.

### Sign (MobileNetV3-Small)
- Input: `[1,224,224,3]`.
- Output: `[1, numClasses]` probability vector; labels in `sign_labels.txt`.

## Drivable-area segmentation (TwinLiteNet) + Indian-road fine-tuning
`drivable_twinlite.tflite` is produced by `models/convert/convert_twinlite.py` (clones TwinLiteNet,
bakes RGB→BGR + /255 into the graph, exports the drivable head via litert-torch, NHWC I/O). The app
renders the actual mask as the green carpet (`DrivableSegDetector`); Tier C falls back to the
classical `DrivableAreaDetector`.

For true **Indian-road** accuracy, fine-tune on the India Driving Dataset (IDD) - a GPU step, not run
in this build: `models/convert/finetune_idd.py` (download IDD, build drivable masks, fine-tune the
DA head), then re-run `convert_twinlite.py` to refresh the bundled tflite.

## Indian-roads object detector (training pipeline)

[`models/train/train_indian.py`](train/train_indian.py) fine-tunes YOLOv8n (COCO weights) for Indian
traffic and exports it in the app's format. Output classes, in model order (these exact strings go
into `object_labels.txt`):

`person, rider, bicycle, motorcycle, autorickshaw, car, truck, bus, animal`

Data (both CC BY 4.0, **ThirdEye Labs**):
- Training: [Indian Road Driving Dataset](https://huggingface.co/datasets/thirdeyelabs/indian-road-dataset)
  (Delhi NCR dashcam keyframes, day/dusk/night/rain). Its labels are **machine-generated pseudo-labels**
  (YOLO11m fine-tuned on IDD + ByteTrack, each box with a `confidence`), not human-verified.
- Test: [Indian Road Detection Benchmark](https://huggingface.co/datasets/thirdeyelabs/indian-road-benchmark)
  dev split, 450 frames labelled twice by people; never used for training or model selection.

Attribution: "Indian Road Driving Dataset" and "Indian Road Detection Benchmark" by ThirdEye Labs,
licensed CC BY 4.0. Both are gated (access is granted immediately): run `hf auth login` and click
**Get access** on both dataset pages.

```bash
P=models/train/train_indian.py            # data lives in $LYNE_DATA (default ~/.cache/lyne-data)
python $P download --shards 15            # first 15 shards (~1,000 frames each) + benchmark; --spread to sample wider
python $P prepare                         # -> YOLO dataset, long side 640, split by clip
python $P train --epochs 60               # imgsz 320, CPU defaults; re-run the same command to resume
python $P eval                            # fine-tuned vs stock COCO, per-class AP + official scorer
python $P export                          # INT8 TFLite @320 and @256 + object_labels.txt
python $P smoke                           # offline wiring check (synthetic data, 1 epoch, export)
python -m unittest discover -s models/train -p 'test_*.py'   # converter / mapping / AP unit tests
```

Choices made in `prepare` (all flags):
- `traffic light` / `traffic sign` are dropped (Lyne reads signs with its own classifier).
- `vehicle fallback` -> `car` by default (`--fallback drop` to discard): for a collision warning an
  unclassified vehicle should still be a vehicle. In `eval` such benchmark boxes are ignore regions.
- Pseudo-labels below `--min-score 0.5` are unsure. Dropping them would teach the model that those
  objects are background, keeping them teaches mistakes, so by default (`--low-conf mask`) they are
  painted grey (neither positive nor background); an unsure box overlapping a confident one is just
  dropped so the confident object is never painted over. `--low-conf drop|skip-frame` are the alternatives.
- Train/val split is by clip (drive), deterministic, 10 % val; settings are pinned per output dir so
  a resumed `prepare` can never mix two splits.

`eval` scores on the benchmark the way the official scorer does (one-pass boxes = ignore regions;
a COCO model's person boxes also count for `rider`; COCO animal classes map to `animal`) and also
runs the benchmark's own `evaluate.py` for the headline number. The published reference for
stock YOLOv8n at its default 640 input is 19.3 mAP / 33.0 AP50 on dev; the app runs at 320/256, so the
report also scores stock YOLOv8n at 320. `export` never overwrites app assets - it prints the `cp`
commands. Swapping the labels file changes class order, so ship the `.tflite` files and
`object_labels.txt` together.

## Conversion

Install deps (a Python 3.10+ venv, off-device):

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r models/convert/requirements.txt
```

Then:

```bash
# Object detector (COCO YOLOv8n, INT8) - produces object_yolov8n_int8.tflite
python models/convert/convert_yolov8.py --imgsz 320 --out app/src/main/assets/models/object_yolov8n_int8.tflite
python models/convert/convert_yolov8.py --imgsz 256 --out app/src/main/assets/models/object_yolov8n_nano_int8.tflite

# Traffic-sign classifier (your trained Keras model) - INT8 with a representative dataset
python models/convert/convert_sign.py --keras my_sign_model --calib calib_signs/ \
       --out app/src/main/assets/models/sign_mobilenetv3_int8.tflite

# Lane model (ONNX -> TFLite INT8 via onnx2tf)
python models/convert/convert_lane.py --onnx ufld.onnx --calib calib_lane/ \
       --out app/src/main/assets/models/lane_ufld_int8.tflite
```

INT8 quantization needs a **representative dataset** (a few hundred real driving frames) so the
activations quantize well; without it accuracy drops sharply. See each script's `--calib` flag.

## Verifying a dropped-in model

1. Build + install, open the app. The status strip shows **STUB** when no weights are loaded.
2. After dropping a real model and reinstalling, STUB disappears for that detector and the backend
   tag (NNAPI/GPU/XNNPACK) reflects the measured delegate.
3. If a model fails to load, logcat (`adb logcat -s Lyne`) shows the fallback to stub with the cause.
