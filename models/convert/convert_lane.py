#!/usr/bin/env python3
"""Convert a lane model from ONNX to INT8 .tflite via onnx2tf.

Works for UFLD or any ultralight lane net exported to ONNX. onnx2tf rewrites NCHW->NHWC and can
emit a full-integer INT8 model given a representative dataset (npy of shape [N,H,W,3], float [0,1]).

Usage:
  python convert_lane.py --onnx ufld.onnx --calib calib_lane_nhwc.npy \
         --out app/src/main/assets/models/lane_ufld_int8.tflite
"""
import argparse
import shutil
import subprocess
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--calib", required=True, help=".npy representative data, NHWC float [0,1]")
    ap.add_argument("--out", required=True)
    ap.add_argument("--workdir", default="onnx2tf_out")
    args = ap.parse_args()

    # onnx2tf produces several variants; *_full_integer_quant.tflite is the INT8 one.
    cmd = [
        "onnx2tf", "-i", args.onnx, "-o", args.workdir,
        "-oiqt",                      # output integer-quantized tflite
        "-cind", "input", args.calib, "[[[[0.0,0.0,0.0]]]]", "[[[[1.0,1.0,1.0]]]]",
    ]
    print("running:", " ".join(cmd))
    subprocess.run(cmd, check=True)

    cand = sorted(Path(args.workdir).glob("*full_integer_quant.tflite"))
    if not cand:
        raise SystemExit("no *full_integer_quant.tflite produced; check onnx2tf output")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(cand[0], out)
    print(f"wrote {out} ({out.stat().st_size/1e6:.1f} MB) from {cand[0]}")


if __name__ == "__main__":
    main()
