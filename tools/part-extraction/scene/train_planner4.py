"""Planner v4: per pixel of a close-up, its tag, the roles drawn over it, which layer it belongs to at three grains
(the single layer, its named group, its part) and how far back that layer is drawn. Trained on plan4_data.py export.

  python train_planner4.py DATA OUT [--steps 30000] [--batch 2] [--accum 2] [--init planner_v3/planner.pt]

The backbone, neck and tag head are planner v3's (DINOv2 from Depth Anything V2 Base, DPT), started from --init. The
new heads: roles (shadow, highlight, line, detail; one sigmoid each), an embedding of 3 x EMB dimensions, and a draw
order score. Each third of the embedding learns one grain with the discriminative loss (De Brabandere et al. 2017):
pixels of one layer (group, part) pulled within DELTA_V of their mean, means of different ones pushed 2 DELTA_D apart.
Inference clusters them in turn (cluster()): parts, the groups in a part, the layers in a group, so the grain is
chosen by where one stops. The order score is trained on every pair of layers in a close-up to rank as their draw
order does. Held-out close-ups (plan4_data.py held) are the validation set; OUT gets planner4.pt and log.txt.
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
SIZE = 728  # 52 patches
EMB = 8
GRAINS = ("unit", "group", "part")
DELTA_V, DELTA_D = 0.5, 1.5
ROLES = 4
NONE, IGNORE = 0, 65535


def head(width: int, out: int) -> nn.Module:
    return nn.Sequential(nn.Conv2d(width, 128, 3, padding=1), nn.GELU(), nn.Conv2d(128, out, 1))


class Planner4(nn.Module):
    def __init__(self, classes: int):
        super().__init__()
        base = DepthAnythingForDepthEstimation.from_pretrained(BACKBONE, local_files_only=True)
        self.backbone, self.neck, self.patch = base.backbone, base.neck, base.config.patch_size
        width = base.config.fusion_hidden_size
        self.head = head(width, classes)  # planner v3's name, so its weights load
        self.roles = head(width, ROLES)
        self.embed = head(width, EMB * len(GRAINS))
        self.order = head(width, 1)

    def forward(self, pixels: torch.Tensor) -> dict:
        h, w = pixels.shape[-2:]
        maps = self.backbone.forward_with_filtered_kwargs(pixels).feature_maps
        fused = self.neck(maps, h // self.patch, w // self.patch)[-1]
        up = lambda x, s=1: F.interpolate(x, size=(h // s, w // s), mode="bilinear", align_corners=False)
        return {"classes": up(self.head(fused)), "roles": up(self.roles(fused)), "embed": up(self.embed(fused), 2),
                "order": up(self.order(fused), 2)}


class Closeups(torch.utils.data.Dataset):
    def __init__(self, stems, train: bool):
        self.stems, self.train = stems, train

    def __len__(self):
        return len(self.stems)

    def __getitem__(self, i):
        stem = self.stems[i]
        meta = json.loads(Path(stem + ".json").read_text(encoding="utf-8"))
        image = Image.open(stem + ".jpg").convert("RGB")
        maps = [Image.open(f"{stem}_{k}.png") for k in ("cls", "role", "inst")]
        if self.train:
            # A random square of 75-100% of the close-up, flipped half the time.
            side = int(image.width * random.uniform(0.75, 1.0))
            x0, y0 = random.randint(0, image.width - side), random.randint(0, image.height - side)
            box = (x0, y0, x0 + side, y0 + side)
            image, maps = image.crop(box), [m.crop(box) for m in maps]
            if random.random() < 0.5:
                image, maps = image.transpose(Image.FLIP_LEFT_RIGHT), [m.transpose(Image.FLIP_LEFT_RIGHT) for m in maps]
        image = image.resize((SIZE, SIZE), Image.BILINEAR)
        cls, role = (np.asarray(m.resize((SIZE, SIZE), Image.NEAREST)) for m in maps[:2])
        inst = np.asarray(maps[2].resize((SIZE // 2, SIZE // 2), Image.NEAREST)).astype(np.int64)
        # Per instance its group, part and draw order; the maps carry them per pixel (half size).
        instances = meta["instances"]
        names = {g: {} for g in ("group", "part")}
        lut = {g: np.zeros(IGNORE + 1, np.int64) for g in ("group", "part", "render")}
        for k, d in enumerate(instances, 1):
            for g in ("group", "part"):
                lut[g][k] = names[g].setdefault(d[g], len(names[g]) + 1)
            lut["render"][k] = d["render"] + 1
        x = torch.from_numpy(np.asarray(image).copy()).permute(2, 0, 1).float() / 255
        if self.train:
            x = (x * random.uniform(0.8, 1.2) + random.uniform(-0.08, 0.08)).clamp(0, 1)
            x = x[torch.randperm(3)] if random.random() < 0.2 else x
        inst_t = torch.from_numpy(np.where(inst == IGNORE, -1, inst))
        return ((x - MEAN) / STD, torch.from_numpy(cls.astype(np.int64)), torch.from_numpy(role.astype(np.int64)), inst_t,
                torch.from_numpy(np.where(inst == IGNORE, 0, lut["group"][inst])), torch.from_numpy(np.where(inst == IGNORE, 0, lut["part"][inst])),
                torch.from_numpy(np.where(inst == IGNORE, 0, lut["render"][inst])), meta["level"])


def discriminative(emb: torch.Tensor, ids: torch.Tensor) -> torch.Tensor:
    """emb (D, N) over pixels with ids (N) above 0: pull to the id's mean within DELTA_V, means 2 DELTA_D apart."""
    keep = ids > 0
    if keep.sum() < 2:
        return emb.sum() * 0
    e, ids = emb[:, keep], ids[keep]
    uniq, idx = torch.unique(ids, return_inverse=True)
    k = len(uniq)
    means = torch.zeros(emb.shape[0], k, device=emb.device).index_add_(1, idx, e)
    counts = torch.bincount(idx, minlength=k).float()
    means = means / counts
    dist = (e - means[:, idx]).norm(dim=0)
    pull = torch.zeros(k, device=emb.device).index_add_(0, idx, F.relu(dist - DELTA_V) ** 2) / counts
    loss = pull.mean()
    if k > 1:
        d = torch.cdist(means.T, means.T)
        push = F.relu(2 * DELTA_D - d) ** 2
        loss = loss + push[~torch.eye(k, dtype=torch.bool, device=emb.device)].mean()
    return loss + 0.001 * means.norm(dim=0).mean()


def ranking(order: torch.Tensor, inst: torch.Tensor, render: torch.Tensor) -> torch.Tensor:
    """Each pair of layers' mean scores ranked as their draw order (a later layer scores higher)."""
    keep = inst > 0
    if keep.sum() < 2:
        return order.sum() * 0
    uniq, idx = torch.unique(inst[keep], return_inverse=True)
    k = len(uniq)
    if k < 2:
        return order.sum() * 0
    counts = torch.bincount(idx, minlength=k).float()
    score = torch.zeros(k, device=order.device).index_add_(0, idx, order[keep]) / counts
    r = torch.zeros(k, device=order.device).index_add_(0, idx, render[keep].float()) / counts
    sign = torch.sign(r[:, None] - r[None, :])
    pairs = sign != 0
    return F.softplus(-(score[:, None] - score[None, :]) * sign)[pairs].mean()


def losses(out: dict, cls, role, inst, group, part, render, weights) -> dict:
    l_cls = F.cross_entropy(out["classes"].float(), cls, weight=weights, ignore_index=255)
    valid = cls != 255
    bits = torch.stack([(role >> b) & 1 for b in range(ROLES)], 1).float()
    l_role = (F.binary_cross_entropy_with_logits(out["roles"].float(), bits, pos_weight=torch.full((ROLES, 1, 1), 3.0, device=bits.device),
                                                  reduction="none").mean(1) * valid).sum() / valid.sum().clamp(min=1)
    emb, order = out["embed"].float(), out["order"].float()
    l_emb, l_ord = [], []
    for b in range(emb.shape[0]):
        flat = emb[b].flatten(1)
        for g, ids in enumerate((inst[b], group[b], part[b])):
            l_emb.append(discriminative(flat[g * EMB:(g + 1) * EMB], ids.flatten()))
        l_ord.append(ranking(order[b, 0].flatten(), inst[b].flatten(), render[b].flatten()))
    return {"cls": l_cls, "role": l_role, "emb": torch.stack(l_emb).mean() * len(GRAINS), "order": torch.stack(l_ord).mean()}


@torch.no_grad()
def cluster(emb: torch.Tensor, mask: torch.Tensor, threshold: float = DELTA_D, min_pixels: int = 12) -> torch.Tensor:
    """Greedy mean-shift clustering of emb (D, H, W) over mask: 0 outside, 1.. per cluster. A seed's mean is refined
    over the pixels within threshold of it; pixels within threshold of the mean take the cluster."""
    D, H, W = emb.shape
    e = emb.flatten(1).T
    todo = mask.flatten().clone()
    labels = torch.zeros(H * W, dtype=torch.long, device=emb.device)
    n = 0
    while todo.sum() >= min_pixels:
        idx = torch.nonzero(todo)[:, 0]
        centre = e[idx[torch.randint(len(idx), (1,), device=emb.device)]][0]
        for _ in range(10):
            near = idx[(e[idx] - centre).norm(dim=1) < threshold]
            if len(near) == 0:
                break
            new = e[near].mean(0)
            if (new - centre).norm() < 1e-3:
                break
            centre = new
        near = idx[(e[idx] - centre).norm(dim=1) < threshold]
        todo[near] = False
        if len(near) >= min_pixels:
            n += 1
            labels[near] = n
        elif len(near) == 0:
            break
    return labels.view(H, W)


def instance_scores(pred: torch.Tensor, truth: torch.Tensor) -> tuple[int, int, int]:
    """Matched (IoU >= 0.5), predicted and true instance counts."""
    p_ids = [i for i in torch.unique(pred).tolist() if i > 0]
    t_ids = [i for i in torch.unique(truth).tolist() if i > 0]
    matched = 0
    for t in t_ids:
        tm = truth == t
        cand = torch.unique(pred[tm])
        for p in cand.tolist():
            if p <= 0:
                continue
            pm = pred == p
            if (pm & tm).sum() * 2 > (pm | tm).sum():  # IoU > 0.5 is one at most per true instance
                matched += 1
                break
    return matched, len(p_ids), len(t_ids)


@torch.no_grad()
def evaluate(model, stems, n_classes, classes) -> dict:
    model.eval()
    inter, union = np.zeros((3, n_classes)), np.zeros((3, n_classes))
    counts = {g: np.zeros(3) for g in GRAINS}
    pairs = np.zeros(2)
    data = Closeups(stems, False)
    for i in range(len(data)):
        x, cls, role, inst, group, part, render, lv = data[i]
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = model(x[None].cuda())
        pred = out["classes"].argmax(1)[0].cpu().numpy()
        y = cls.numpy()
        keep = y != 255
        for c in range(n_classes):
            p, t = (pred == c) & keep, (y == c) & keep
            inter[lv, c] += (p & t).sum()
            union[lv, c] += (p | t).sum()
        emb = out["embed"][0].float()
        mask = inst.cuda() > 0  # the layers' own pixels, so instance scores do not depend on the tag head
        for g, truth in zip(GRAINS, (inst, group, part)):
            k = GRAINS.index(g)
            labels = cluster(emb[k * EMB:(k + 1) * EMB], mask)
            counts[g] += instance_scores(labels.cpu(), truth)
        # Order: share of layer pairs ranked as drawn.
        o = out["order"][0, 0].float().cpu()
        ids = [j for j in torch.unique(inst).tolist() if j > 0]
        score = {j: o[inst == j].mean().item() for j in ids}
        rank = {j: render[inst == j][0].item() for j in ids}
        for a in ids:
            for b in ids:
                if rank[a] < rank[b]:
                    pairs += (score[a] < score[b], 1)
    model.train()
    iou = np.where(union > 0, inter / np.maximum(union, 1), np.nan)
    result = {"miou": {lv: float(np.nanmean(iou[k])) for k, lv in enumerate(("whole", "part", "small"))}}
    result["per_class_part"] = {c: (None if np.isnan(v) else round(float(v), 3)) for c, v in zip(classes, iou[1])}
    result["per_class_whole"] = {c: (None if np.isnan(v) else round(float(v), 3)) for c, v in zip(classes, iou[0])}
    for g in GRAINS:
        m, p, t = counts[g]
        result[g] = {"precision": round(m / max(p, 1), 3), "recall": round(m / max(t, 1), 3), "predicted": int(p), "true": int(t)}
    result["order"] = round(pairs[0] / max(pairs[1], 1), 3)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("out")
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--batch", type=int, default=2)
    ap.add_argument("--accum", type=int, default=2, help="batches per step")
    ap.add_argument("--init", default="")
    ap.add_argument("--eval-every", type=int, default=5000)
    ap.add_argument("--val-limit", type=int, default=300)
    a = ap.parse_args()
    data, out = Path(a.data), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    classes = json.loads((data / "classes.json").read_text(encoding="utf-8"))
    stems = sorted(str(p.with_suffix("")) for p in data.glob("*.json") if p.name != "classes.json")
    held = {s for s in stems if json.loads(Path(s + ".json").read_text(encoding="utf-8"))["held"]}
    train = [s for s in stems if s not in held]
    # Validation: the held-out close-ups as framed (not the random variants), at most val-limit of them.
    val = sorted(s for s in held if s.endswith("_0"))
    val = val[::max(1, len(val) // a.val_limit)][:a.val_limit]
    counts = np.zeros(len(classes))
    for s in train[::20]:
        lab = np.asarray(Image.open(s + "_cls.png"))
        counts += np.bincount(lab[lab != 255].ravel(), minlength=len(classes))[:len(classes)]
    weights = torch.tensor(1 / np.sqrt(np.maximum(counts, 1) / counts.sum()), dtype=torch.float32)
    weights = (weights / weights.mean()).clamp(0.2, 5).cuda()
    model = Planner4(len(classes)).cuda()
    if a.init:
        state = torch.load(a.init, map_location="cuda")["state"]
        missing, unexpected = model.load_state_dict(state, strict=False)
        print("init from", a.init, "new:", sorted({k.split(".")[0] for k in missing}), file=open(out / "log.txt", "a", encoding="utf-8"))
    new = [p for n, p in model.named_parameters() if not n.startswith("backbone")]
    opt = torch.optim.AdamW([{"params": model.backbone.parameters(), "lr": 2e-5}, {"params": new, "lr": 2e-4}], weight_decay=0.01)
    loader = torch.utils.data.DataLoader(Closeups(train, True), batch_size=a.batch, shuffle=True, num_workers=6, drop_last=True,
                                         persistent_workers=True)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda step: min(1, step / 300) * 0.5 * (1 + math.cos(math.pi * step / a.steps)))
    log = open(out / "log.txt", "a", encoding="utf-8")
    print(f"{len(train)} training close-ups, {len(val)} validation", file=log, flush=True)
    step, n = 0, 0
    while step < a.steps:
        for batch in loader:
            x, cls, role, inst, group, part, render, _ = (t.cuda(non_blocking=True) for t in batch)
            with torch.autocast("cuda", dtype=torch.bfloat16):
                o = model(x)
            parts = losses(o, cls, role, inst, group, part, render, weights)
            loss = parts["cls"] + parts["role"] + parts["emb"] + 0.5 * parts["order"]
            (loss / a.accum).backward()
            n += 1
            if n % a.accum:
                continue
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            opt.zero_grad(set_to_none=True)
            sched.step()
            step += 1
            if step % 50 == 0:
                print(f"step {step}/{a.steps} loss {loss.item():.4f} " + " ".join(f"{k} {v.item():.4f}" for k, v in parts.items()), file=log, flush=True)
            if step % a.eval_every == 0 or step == a.steps:
                torch.save({"state": model.state_dict(), "classes": classes}, out / "planner4.pt")
                print(json.dumps({"step": step, **evaluate(model, val, len(classes), classes)}), file=log, flush=True)
            if step >= a.steps:
                break


if __name__ == "__main__":
    main()
