"""The planner: per-pixel part tags of a character illustration, a DINOv2 (Depth Anything V2 Base) backbone and DPT
neck with a new classification head, trained on plan_data.py's partial labels (255 is ignored).

  python train_planner.py DATA OUT [--epochs 40] [--batch 4] [--held a,b,...]

Held-out characters (by model path) are the validation set; OUT gets planner.pt, log.txt and per-class IoU.
"""
import argparse
import json
import math
import random
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image
from transformers import DepthAnythingForDepthEstimation

BACKBONE = "depth-anything/Depth-Anything-V2-Base-hf"
MEAN = torch.tensor([0.485, 0.456, 0.406]).view(3, 1, 1)
STD = torch.tensor([0.229, 0.224, 0.225]).view(3, 1, 1)
CROP = 518


class Planner(nn.Module):
    def __init__(self, classes: int):
        super().__init__()
        base = DepthAnythingForDepthEstimation.from_pretrained(BACKBONE, local_files_only=True)
        self.backbone, self.neck, self.patch = base.backbone, base.neck, base.config.patch_size
        width = base.config.fusion_hidden_size
        self.head = nn.Sequential(nn.Conv2d(width, 128, 3, padding=1), nn.GELU(), nn.Conv2d(128, classes, 1))

    def forward(self, pixels: torch.Tensor) -> torch.Tensor:
        h, w = pixels.shape[-2:]
        maps = self.backbone.forward_with_filtered_kwargs(pixels).feature_maps
        fused = self.neck(maps, h // self.patch, w // self.patch)
        return F.interpolate(self.head(fused[-1]), size=(h, w), mode="bilinear", align_corners=False)


class Pictures(torch.utils.data.Dataset):
    def __init__(self, stems, train: bool, parts: int = 255):
        self.stems, self.train, self.parts = stems, train, parts  # labels below parts are parts, not background

    def __len__(self):
        return len(self.stems) * (8 if self.train else 1)

    def __getitem__(self, i):
        stem = self.stems[i % len(self.stems)]
        image = Image.open(f"{stem}.jpg").convert("RGB")
        label = Image.open(f"{stem}.png")
        if self.train:
            # A random scale, then a crop that lands on a part most of the time.
            s = random.uniform(CROP / max(image.size), 1.6 * CROP / min(image.size))
            size = (max(CROP, int(image.width * s)), max(CROP, int(image.height * s)))
            image, label = image.resize(size, Image.BILINEAR), label.resize(size, Image.NEAREST)
            lab = np.asarray(label)
            ys, xs = np.nonzero(lab < self.parts)
            for _ in range(10):
                if len(xs) and random.random() < 0.8:
                    j = random.randrange(len(xs))
                    x0 = min(max(0, xs[j] - random.randrange(CROP)), size[0] - CROP)
                    y0 = min(max(0, ys[j] - random.randrange(CROP)), size[1] - CROP)
                else:
                    x0, y0 = random.randrange(size[0] - CROP + 1), random.randrange(size[1] - CROP + 1)
                if (lab[y0:y0 + CROP, x0:x0 + CROP] != 255).mean() > 0.05:
                    break
            image, label = image.crop((x0, y0, x0 + CROP, y0 + CROP)), label.crop((x0, y0, x0 + CROP, y0 + CROP))
            if random.random() < 0.5:
                image, label = image.transpose(Image.FLIP_LEFT_RIGHT), label.transpose(Image.FLIP_LEFT_RIGHT)
        else:
            s = 1022 / max(image.size)
            size = (max(14, int(image.width * s) // 14 * 14), max(14, int(image.height * s) // 14 * 14))
            image, label = image.resize(size, Image.BILINEAR), label.resize(size, Image.NEAREST)
        x = torch.from_numpy(np.asarray(image).copy()).permute(2, 0, 1).float() / 255
        if self.train:
            x = (x * random.uniform(0.8, 1.2) + random.uniform(-0.08, 0.08)).clamp(0, 1)
            x = x[torch.randperm(3)] if random.random() < 0.2 else x  # hue-ish: channel swap now and then
        return (x - MEAN) / STD, torch.from_numpy(np.asarray(label).astype(np.int64))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("out")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--held", default="caracal,mint,Ergosum/kiharu,灵魂潮汐国际服-倒闭二游/asuna,booth-6687553-weather-girl,booth-4499251-same-sister")
    a = ap.parse_args()
    data, out = Path(a.data), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    classes = json.loads((data / "classes.json").read_text(encoding="utf-8"))
    parts = classes.index("background") if "background" in classes else 255  # labels below are parts
    held = [h for h in a.held.split(",") if h]
    stems = sorted(str(p.with_suffix("")) for p in data.glob("*.jpg"))
    val = [s for s in stems if any(h in Path(s + ".txt").read_text(encoding="utf-8") for h in held)]
    train = [s for s in stems if s not in set(val)]
    # Class weights: inverse square root of pixel frequency, so small parts count.
    counts = np.zeros(len(classes))
    for s in train[::3]:
        lab = np.asarray(Image.open(s + ".png"))
        counts += np.bincount(lab[lab != 255].ravel(), minlength=len(classes))[:len(classes)]
    weights = torch.tensor(1 / np.sqrt(np.maximum(counts, 1) / counts.sum()), dtype=torch.float32)
    weights = (weights / weights.mean()).clamp(0.2, 5).cuda()
    model = Planner(len(classes)).cuda()
    params = [{"params": model.backbone.parameters(), "lr": 2e-5},
              {"params": list(model.neck.parameters()) + list(model.head.parameters()), "lr": 2e-4}]
    opt = torch.optim.AdamW(params, weight_decay=0.01)
    loader = torch.utils.data.DataLoader(Pictures(train, True, parts), batch_size=a.batch, shuffle=True, num_workers=6, drop_last=True,
                                         persistent_workers=True)
    total = a.epochs * len(loader)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda step: min(1, step / 300) * 0.5 * (1 + math.cos(math.pi * step / total)))
    log = open(out / "log.txt", "a", encoding="utf-8")
    step = 0
    for epoch in range(a.epochs):
        model.train()
        for x, y in loader:
            x, y = x.cuda(non_blocking=True), y.cuda(non_blocking=True)
            with torch.autocast("cuda", dtype=torch.bfloat16):
                logits = model(x)
            loss = F.cross_entropy(logits.float(), y, weight=weights, ignore_index=255)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            step += 1
            if step % 50 == 0:
                print(f"epoch {epoch} step {step}/{total} loss {loss.item():.4f}", file=log, flush=True)
        if (epoch + 1) % 5 == 0 or epoch + 1 == a.epochs:
            torch.save({"state": model.state_dict(), "classes": classes}, out / "planner.pt")
            ious = evaluate(model, val, len(classes))
            line = {"epoch": epoch + 1, "miou": float(np.nanmean(ious)),
                    "per_class": {c: (None if np.isnan(v) else round(float(v), 3)) for c, v in zip(classes, ious)}}
            print(json.dumps(line), file=log, flush=True)


@torch.no_grad()
def evaluate(model, stems, n):
    model.eval()
    inter, union = np.zeros(n), np.zeros(n)
    pictures = Pictures(stems, False)
    for i in range(len(pictures)):  # a Dataset has no end of its own when iterated
        x, y = pictures[i]
        with torch.autocast("cuda", dtype=torch.bfloat16):
            pred = model(x[None].cuda()).argmax(1)[0].cpu().numpy()
        y = y.numpy()
        keep = y != 255
        for c in range(n):
            p, t = (pred == c) & keep, (y == c) & keep
            inter[c] += (p & t).sum()
            union[c] += (p | t).sum()
    return np.where(union > 0, inter / np.maximum(union, 1), np.nan)


if __name__ == "__main__":
    main()
