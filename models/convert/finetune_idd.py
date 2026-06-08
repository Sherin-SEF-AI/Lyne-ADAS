#!/usr/bin/env python3
"""Fine-tune the drivable-area model on the India Driving Dataset (IDD) for Indian-road accuracy.

This is a GPU step (run on a machine with a CUDA GPU); it is NOT run during the app build. IDD is
tens of GB. After fine-tuning, re-export with convert_twinlite.py to refresh
app/src/main/assets/models/drivable_twinlite.tflite.

Pipeline:
  1. Get IDD: register + download "IDD Segmentation (Part I)" from https://idd.insaan.iiit.ac.in/
     (~20 GB). It ships Cityscapes-style labels; the "drivable"/"road" classes are what we need.
  2. Build a binary drivable mask from the IDD label IDs (road, parking, drivable fallback).
  3. Fine-tune TwinLiteNet's DA head (freeze the encoder for a few epochs, then unfreeze).
  4. Save best.pth, then: python convert_twinlite.py

Usage:
  python finetune_idd.py --idd /data/idd --epochs 30 --bs 16 --lr 1e-3 --out idd_best.pth
"""
import argparse, os, glob
import numpy as np

# IDD level-3 label ids treated as drivable (road surface the ego vehicle can use).
IDD_DRIVABLE_IDS = {0, 1, 2}  # road, parking, drivable fallback (adjust to your label spec)


def build_mask(label_img: np.ndarray) -> np.ndarray:
    m = np.zeros(label_img.shape[:2], np.uint8)
    for i in IDD_DRIVABLE_IDS:
        m[label_img == i] = 1
    return m


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--idd", required=True, help="IDD root (with leftImg8bit/ and gtFine/ or idd-style dirs)")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--bs", type=int, default=16)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--repo", default="/home/joai/toolchain/twinlite")
    ap.add_argument("--weights", default=None, help="start from these weights (else repo pretrained)")
    ap.add_argument("--out", default="idd_best.pth")
    args = ap.parse_args()

    import torch, sys
    import torch.nn as nn
    from torch.utils.data import Dataset, DataLoader
    from PIL import Image
    sys.path.insert(0, args.repo)
    from model.TwinLite import TwinLiteNet  # noqa

    H, W = 360, 640
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    if dev == "cpu":
        print("WARNING: no CUDA GPU found - IDD fine-tuning on CPU is impractically slow.")

    imgs = sorted(glob.glob(os.path.join(args.idd, "**", "*_leftImg8bit.png"), recursive=True))
    print(f"found {len(imgs)} IDD images")

    class IDD(Dataset):
        def __init__(self, paths): self.paths = paths
        def __len__(self): return len(self.paths)
        def __getitem__(self, i):
            ip = self.paths[i]
            lp = ip.replace("leftImg8bit", "gtFine").replace("_gtFine.png", "_gtFine_labelids.png")
            im = np.asarray(Image.open(ip).convert("RGB").resize((W, H)), np.float32) / 255.0
            lb = np.asarray(Image.open(lp).resize((W, H), Image.NEAREST))
            x = torch.from_numpy(im[:, :, ::-1].copy().transpose(2, 0, 1))  # BGR like TwinLiteNet
            y = torch.from_numpy(build_mask(lb)).long()
            return x, y

    net = TwinLiteNet().to(dev)
    wpath = args.weights or (glob.glob(args.repo + "/**/*.pth", recursive=True) or [None])[0]
    if wpath:
        sd = torch.load(wpath, map_location=dev)
        sd = sd.get("state_dict", sd)
        net.load_state_dict({k.replace("module.", ""): v for k, v in sd.items()}, strict=False)
        print("loaded", wpath)

    dl = DataLoader(IDD(imgs), batch_size=args.bs, shuffle=True, num_workers=4, drop_last=True)
    opt = torch.optim.Adam(net.parameters(), lr=args.lr)
    ce = nn.CrossEntropyLoss()
    best = 1e9
    for ep in range(args.epochs):
        net.train(); tot = 0.0
        for x, y in dl:
            x, y = x.to(dev), y.to(dev)
            da = net(x)[0]                       # drivable head [B,2,H,W]
            loss = ce(da, y)
            opt.zero_grad(); loss.backward(); opt.step()
            tot += loss.item()
        avg = tot / max(1, len(dl))
        print(f"epoch {ep+1}/{args.epochs} loss {avg:.4f}")
        if avg < best:
            best = avg; torch.save(net.state_dict(), args.out); print("  saved", args.out)
    print("DONE. Now: python models/convert/convert_twinlite.py (point it at", args.out, ")")


if __name__ == "__main__":
    main()
