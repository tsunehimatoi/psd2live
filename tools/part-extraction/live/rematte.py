"""Takes the gray background off a split's generated pictures again with BiRefNet's general (salient object) weights:
the matting weights keep the soft gray shadow the LoRA draws around a part as half-transparent paint, which shows
as smudges over light parts. Writes <candidate>_g.png beside each <candidate>_raw.png.

usage: python rematte.py SPLIT_DIR [LAYER ...]
"""
import os
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image
from transformers import AutoModelForImageSegmentation

model = AutoModelForImageSegmentation.from_pretrained(os.environ.get("BIREFNET_GENERAL", "ZhengPeng7/BiRefNet"), trust_remote_code=True).cuda().eval().half()
mean = torch.tensor([0.485, 0.456, 0.406], device="cuda").view(1, 3, 1, 1)
std = torch.tensor([0.229, 0.224, 0.225], device="cuda").view(1, 3, 1, 1)
d = Path(sys.argv[1])
layers = sys.argv[2:] or [p.name for p in d.iterdir() if p.is_dir()]
n = 0
with torch.no_grad():
    for layer in layers:
        for raw in sorted((d / layer).glob("*_raw.png")):
            out = raw.with_name(raw.name.replace("_raw.png", "_g.png"))
            if out.exists():
                continue
            rgb = Image.open(raw).convert("RGB")
            x = torch.from_numpy(np.asarray(rgb).copy()).permute(2, 0, 1)[None].float().cuda() / 255
            s = F.interpolate(x, (1024, 1024), mode="bilinear", align_corners=False)
            a = model(((s - mean) / std).half())[-1].sigmoid().float()
            a = F.interpolate(a, rgb.size[::-1], mode="bilinear", align_corners=False)[0, 0].cpu().numpy()
            rgba = np.dstack([np.asarray(rgb), (a * 255).round().astype(np.uint8)])
            Image.fromarray(rgba, "RGBA").save(out)
            n += 1
print(n, "pictures matted again")
