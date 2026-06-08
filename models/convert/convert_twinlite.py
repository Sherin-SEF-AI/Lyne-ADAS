#!/usr/bin/env python3
"""Convert pretrained TwinLiteNet (drivable-area + lane segmentation) to a float32 TFLite for Lyne.

Bakes RGB[0,1] -> BGR channel order into the graph (TwinLiteNet trains on cv2/BGR, /255), and
exports ONLY the drivable-area head (auto-detected) so the app reads output[0]. Tiny net (~0.4M
params) so float32 is fast even on CPU/XNNPACK and avoids INT8 mask degradation.

Output: app/src/main/assets/models/drivable_twinlite.tflite  (input NHWC [1,360,640,3] RGB [0,1])
"""
import os, sys, glob, subprocess, shutil
import numpy as np

REPO = "/home/joai/toolchain/twinlite"
OUT = "/home/joai/Desktop/Lyne/app/src/main/assets/models/drivable_twinlite.tflite"
# Smaller input than the 640x360 default -> far less compute for real-time on phones. Stride-32 friendly.
H, W = 192, 320

if not os.path.isdir(REPO):
    subprocess.run(["git", "clone", "--depth", "1", "https://github.com/chequanghuy/TwinLiteNet", REPO], check=True)
sys.path.insert(0, REPO)

import torch, torch.nn as nn
from PIL import Image

# locate model class
TwinLiteNet = None
for modpath in ("model.TwinLite", "model.model", "TwinLite"):
    try:
        TwinLiteNet = __import__(modpath, fromlist=["TwinLiteNet"]).TwinLiteNet
        print("imported", modpath); break
    except Exception as e:
        print("import", modpath, "failed:", e)
if TwinLiteNet is None:
    print("model files:", glob.glob(REPO + "/**/*.py", recursive=True)); sys.exit(1)

net = TwinLiteNet()
# find weights
wpath = None
for c in glob.glob(REPO + "/**/*.pth", recursive=True):
    wpath = c; break
print("weights:", wpath)
sd = torch.load(wpath, map_location="cpu")
if isinstance(sd, dict) and "state_dict" in sd: sd = sd["state_dict"]
sd = { (k[7:] if k.startswith("module.") else k): v for k, v in sd.items() }
net.load_state_dict(sd, strict=False)
net.eval()

class Wrap(nn.Module):
    def __init__(self, net, head):
        super().__init__(); self.net = net; self.head = head
    def forward(self, x):              # x: [1,H,W,3] RGB in [0,1]  (NHWC, matches the app)
        x = x.permute(0, 3, 1, 2)      # -> NCHW
        x = x.flip(1)                  # RGB -> BGR (TwinLiteNet expects BGR/255)
        outs = self.net(x)
        o = outs[self.head] if isinstance(outs, (list, tuple)) else outs
        return o.permute(0, 2, 3, 1)   # [1,H,W,2] NHWC (class0=bg, class1=drivable)

# decide which head is drivable using a road image (bus.jpg has road at bottom)
img_path = "/home/joai/toolchain/yolo_export/bus.jpg"
if not os.path.exists(img_path):
    import urllib.request; urllib.request.urlretrieve("https://ultralytics.com/images/bus.jpg", img_path)
im = np.asarray(Image.open(img_path).convert("RGB").resize((W, H)), np.float32) / 255.0
xt = torch.from_numpy(im.transpose(2, 0, 1))[None]
with torch.no_grad():
    outs = net(xt.flip(1))
outs = outs if isinstance(outs, (list, tuple)) else [outs]
print("num heads", len(outs), "shapes", [tuple(o.shape) for o in outs])
def bottom_cov(o):
    m = o[0].argmax(0).numpy()        # HxW class ids
    b = m[int(H*0.6):, int(W*0.3):int(W*0.7)]
    return float((b == 1).mean())
covs = [bottom_cov(o) for o in outs]
da_head = int(np.argmax(covs))
print("bottom-centre drivable coverage per head:", covs, "-> DA head =", da_head)

# Direct PyTorch -> TFLite via litert-torch (ex ai-edge-torch): handles ConvTranspose, keeps NHWC I/O.
wrap = Wrap(net, da_head).eval()
import litert_torch
sample = (torch.zeros(1, H, W, 3),)             # NHWC RGB [0,1]

def reps():
    paths = glob.glob("/home/joai/toolchain/yolo_export/datasets/coco128/images/train2017/*.jpg")[:32]
    if not paths: paths = ["/home/joai/toolchain/yolo_export/bus.jpg"]
    for pth in paths:
        a = np.asarray(Image.open(pth).convert("RGB").resize((W, H)), np.float32) / 255.0
        yield (torch.from_numpy(a)[None],)

done = False
try:  # INT8 (full integer) - fast on NNAPI, no GPU needed
    try:
        from torchao.quantization.pt2e.quantize_pt2e import prepare_pt2e, convert_pt2e
    except Exception:
        from torch.ao.quantization.quantize_pt2e import prepare_pt2e, convert_pt2e
    from litert_torch.quantize.pt2e_quantizer import PT2EQuantizer, get_symmetric_quantization_config
    from litert_torch.quantize.quant_config import QuantConfig
    quantizer = PT2EQuantizer().set_global(get_symmetric_quantization_config(is_per_channel=True))
    gm = (getattr(torch.export, "export_for_training", torch.export.export)(wrap, sample)).module()
    gm = prepare_pt2e(gm, quantizer)
    n = 0
    for r in reps(): gm(*r); n += 1
    gm = convert_pt2e(gm)
    edge = litert_torch.convert(gm, sample, quant_config=QuantConfig(pt2e_quantizer=quantizer))
    edge.export(OUT)
    print(f"WROTE INT8 (calib {n}) {OUT} {os.path.getsize(OUT)/1e6:.2f} MB")
    done = True
except Exception as e:
    import traceback; traceback.print_exc()
    print("INT8 path failed, falling back to float32:", e)

if not done:
    edge = litert_torch.convert(wrap, sample)
    edge.export(OUT)
    print(f"WROTE FLOAT32 {OUT} {os.path.getsize(OUT)/1e6:.2f} MB")

print("TWINLITE_DONE")
