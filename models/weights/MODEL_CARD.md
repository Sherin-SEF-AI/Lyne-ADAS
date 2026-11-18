# Lyne Indian-roads detector (YOLOv8n)

Object detector used by Lyne ADAS v2.1: YOLOv8n fine-tuned for Indian traffic.

| File | Where | Use |
|---|---|---|
| `lyne-indian-yolov8n.pt` | this folder | PyTorch weights (Ultralytics 8.4): retraining, evaluation |
| `lyne-indian-yolov8n.onnx` | [release v2.1.0](https://github.com/Sherin-SEF-AI/Lyne-ADAS/releases/tag/v2.1.0) | ONNX, 320×320 input |
| `object_yolov8n_int8.tflite` | `app/src/main/assets/models/` | INT8 TFLite, 320×320 (tiers A/B) |
| `object_yolov8n_nano_int8.tflite` | `app/src/main/assets/models/` | INT8 TFLite, 256×256 (tier C) |
| `object_labels.txt` | `app/src/main/assets/models/` | Class names in output order |

## Classes

`person, rider, bicycle, motorcycle, autorickshaw, car, truck, bus, animal`

A "rider" box is a person on a two-wheeler, box covering both. Traffic lights and signs aren't detected
(stop signs included).

## Training

1. **Base:** COCO-pretrained `yolov8n.pt`.
2. **Main run:** 60 epochs at 320 px on the [ThirdEye Indian road dataset](https://huggingface.co/datasets/thirdeyelabs/indian-road-dataset)
   (15 shards, 13,496 train / 1,504 val frames, CC BY 4.0). Machine labels below 0.5 confidence are masked.
3. **Label cleaning:** the dataset's machine labels for rare classes were mostly wrong. A YOLOv8m teacher
   confirmed 0% of animal, 5% of bicycle, 8% of bus and 13% of truck boxes. [`clean_labels.py`](../train/clean_labels.py)
   drops clips that disagree with the teacher, removes unconfirmed rare-class boxes and adds confident
   teacher boxes. It then fine-tunes 15 epochs.
4. **Rare classes:** [`coco_extra.py`](../train/coco_extra.py) adds 3,700 human-labelled COCO train2017
   images of cows, dogs, horses, sheep, elephants, cats and buses, skipping images with motorcycles or
   bicycles (COCO labels riders "person"). It then fine-tunes 12 epochs.

Scripts: [`models/train/`](../train/). Training ran on CPU.

## Evaluation

[Indian Road Detection Benchmark](https://huggingface.co/datasets/thirdeyelabs/indian-road-benchmark) dev split
(450 frames, human labels, official `evaluate.py`), 320 px input. Lyne is measured as the shipped INT8 TFLite file.

| | Stock YOLOv8n (COCO) | Lyne Indian-roads |
|---|---|---|
| Score, mAP 0.50–0.95 | 9.0 | **15.0** |
| AP50 | 16.8 | **25.3** |
| Day / dusk / night / rain | 10.4 / 6.5 / 9.4 / 6.2 | **15.7 / 14.7 / 14.1 / 15.7** |
| Auto-rickshaws found | 30% | **43%** |

Per class, AP50-95 (%):

| person | rider | bicycle | motorcycle | autorickshaw | car | truck | bus | animal |
|---|---|---|---|---|---|---|---|---|
| 11.8 | 23.1 | 13.7 | 22.0 | 24.3 | 40.4 | 15.9 | 11.2 | 0.2 |

The stock model scores 6.0 on animal. The benchmark has only 12 animal boxes, all small and distant.
On held-out COCO animal photos the two models find a similar share (48% vs 53% at 0.25 confidence).

## Limitations

- Distant street animals are detected poorly.
- No traffic lights or signs.
- 320 px input: small and far objects are hard.
- Training labels are partly machine-generated. Errors remain after cleaning.

## License

- **Weights:** fine-tuned from Ultralytics YOLOv8n, which is licensed **AGPL-3.0**. These weights carry the
  same terms. The rest of the Lyne repository is MIT.
- **Data:** ThirdEye Labs datasets, CC BY 4.0. COCO annotations, CC BY 4.0.
