#!/usr/bin/env python3
"""Convert a trained Keras MobileNetV3-Small sign classifier to INT8 .tflite.

The label order of your training set must match models/sign_labels.txt
(none, speed_limit_*, stop). Provide a folder of representative 224x224 RGB images via --calib.

Usage:
  python convert_sign.py --keras my_sign_model --calib calib_signs/ \
         --out app/src/main/assets/models/sign_mobilenetv3_int8.tflite
"""
import argparse
from pathlib import Path

import numpy as np
import tensorflow as tf
from PIL import Image


def rep_dataset(calib_dir: str, size: int = 224):
    paths = list(Path(calib_dir).glob("**/*"))
    paths = [p for p in paths if p.suffix.lower() in (".jpg", ".jpeg", ".png")]
    def gen():
        for p in paths[:300]:
            img = Image.open(p).convert("RGB").resize((size, size))
            arr = (np.asarray(img, dtype=np.float32) / 255.0)[None, ...]
            yield [arr]
    return gen


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keras", required=True, help="SavedModel dir or .keras file")
    ap.add_argument("--calib", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    model = tf.keras.models.load_model(args.keras)
    conv = tf.lite.TFLiteConverter.from_keras_model(model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.representative_dataset = rep_dataset(args.calib)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    conv.inference_input_type = tf.int8
    conv.inference_output_type = tf.float32

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(conv.convert())
    print(f"wrote {out} ({out.stat().st_size/1e6:.1f} MB)")


if __name__ == "__main__":
    main()
