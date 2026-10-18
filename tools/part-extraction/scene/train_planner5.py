"""Planner v5: the layers of a close-up as a set, one query each (Mask2Former, Cheng et al. 2022). Trained on
plan4_data.py export --amodal (close-ups and tiles with each layer's whole shape).

  python train_planner5.py DATA OUT [--steps 30000] [--batch 2] [--accum 2] [--init planner_v4/planner4.pt]
  python train_planner5.py DATA OUT --eval OUT/planner5.pt

The backbone, DPT neck, tag head and role head are planner v4's (--init). Mask features are the neck's finest map
(416 for a 728 input) with a shallow branch of the picture itself, so a mask's border can sit on the art's edges. A
decoder of LAYERS layers turns QUERIES learned queries into layers, attending to the neck's 52 and 104 maps in turn,
each layer only where the previous one placed its query's mask (masked attention). Each query gives
- a tag (CLASSES, or none: no layer),
- the layer's shown pixels (no two layers share one: at inference each pixel goes to the likeliest query),
- its whole shape, hidden parts included (layers may overlap; the hint for the split LoRA),
- a draw order score (a later layer scores higher),
- a group and a part vector (VEC each): two layers of one named group (part) point the same way.
Training matches queries to the close-up's layers (Hungarian, on POINTS points sampled per close-up), every decoder
layer's predictions included. A layer whose tag is unsure is only kept from being none; a layer the close-up does not
show (covered) is matched and learnt by its whole shape. The pixel tag and role heads keep their v4 losses.
OUT gets log.txt (losses every 50 steps, an evaluation every --eval-every steps), planner5_<step>.pt at each evaluation
and planner5.pt, the last of them.
"""
import argparse
from collections import Counter
import json
import math
import random
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image
from scipy.optimize import linear_sum_assignment
from transformers import DepthAnythingForDepthEstimation

sys.path.insert(0, str(Path(__file__).parent))
import train_planner4 as p4  # noqa: E402
from plan4_data import load_amodal  # noqa: E402

SIZE = p4.SIZE  # 728: 52 patches
SHOWN = SIZE // 2  # the shown layers' map, as v4's instance map
WHOLE = SIZE // 4  # the whole shapes' grid
QUERIES, DIM, LAYERS, HEADS, MASK_DIM, VEC = 200, 256, 6, 8, 128, 16
POINTS = 6144
W_CLASS, W_BCE, W_DICE, W_WHOLE, W_NONE, W_ORDER, W_VEC, W_VISIBLE = 2.0, 5.0, 5.0, 2.0, 0.1, 0.5, 0.5, 1.0
FOCUS = 0.0  # share of the loss points on layer borders and small layers (sample_points); v5 and v5.1 sample evenly
ROLES, IGNORE = p4.ROLES, p4.IGNORE


class Closeups(torch.utils.data.Dataset):
    """A close-up: picture, pixel tags and roles (SIZE), shown layers (SHOWN; -1 left out, 0 none, k layer k) and per
    layer its whole shape (WHOLE), tag (-1 unsure), draw order, group and part."""

    def __init__(self, stems, train: bool, classes: list[str], size: int = SIZE):
        self.stems, self.train, self.classes = stems, train, classes
        self.size, self.shown, self.whole = size, size // 2, size // 4  # kept here: loader workers start afresh

    def __len__(self):
        return len(self.stems)

    def __getitem__(self, i):
        stem = self.stems[i]
        meta = json.loads(Path(stem + ".json").read_text(encoding="utf-8"))
        image = Image.open(stem + ".jpg").convert("RGB")
        maps = [Image.open(f"{stem}_{k}.png") for k in ("cls", "role", "inst")]
        n = image.width
        whole = torch.from_numpy(load_amodal(Path(stem + "_amodal.npz"), (n // 2, n // 2))).float()
        box, flip = (0, 0, n, n), False
        if self.train:
            side = int(n * random.uniform(0.75, 1.0)) // 2 * 2
            x0, y0 = random.randint(0, (n - side) // 2) * 2, random.randint(0, (n - side) // 2) * 2
            box, flip = (x0, y0, x0 + side, y0 + side), random.random() < 0.5
        image, maps = image.crop(box), [m.crop(box) for m in maps]
        whole = whole[:, box[1] // 2:box[3] // 2, box[0] // 2:box[2] // 2]
        if flip:
            image, maps, whole = image.transpose(Image.FLIP_LEFT_RIGHT), [m.transpose(Image.FLIP_LEFT_RIGHT) for m in maps], whole.flip(-1)
        image = image.resize((self.size, self.size), Image.BILINEAR)
        cls, role = (np.asarray(m.resize((self.size, self.size), Image.NEAREST)) for m in maps[:2])
        inst = np.asarray(maps[2].resize((self.shown, self.shown), Image.NEAREST)).astype(np.int64)
        whole = F.interpolate(whole[None], size=(self.whole, self.whole), mode="bilinear", align_corners=False)[0] > 0.5 \
            if len(whole) else torch.zeros(0, self.whole, self.whole, dtype=torch.bool)
        # Layers the crop left: shown here, or with some whole shape here.
        instances = meta["instances"]
        shown = np.bincount(inst[(inst > 0) & (inst != IGNORE)].ravel(), minlength=len(instances) + 1)[1:]
        keep = [k for k in range(len(instances)) if shown[k] > 0 or (k < len(whole) and whole[k].sum() >= 4)]
        renum = np.full(IGNORE + 1, -1, np.int64)
        renum[0] = 0
        for j, k in enumerate(keep, 1):
            renum[k + 1] = j
        inst = renum[inst]  # left out (IGNORE) and layers cropped away: -1
        groups, parts = {}, {}
        info = [instances[k] for k in keep]
        x = torch.from_numpy(np.asarray(image).copy()).permute(2, 0, 1).float() / 255
        if self.train:
            x = (x * random.uniform(0.8, 1.2) + random.uniform(-0.08, 0.08)).clamp(0, 1)
            x = x[torch.randperm(3)] if random.random() < 0.2 else x
        return {"x": (x - p4.MEAN) / p4.STD, "cls": torch.from_numpy(cls.astype(np.int64)), "role": torch.from_numpy(role.astype(np.int64)),
                "inst": torch.from_numpy(inst), "whole": whole[keep] if len(keep) else torch.zeros(0, self.whole, self.whole, dtype=torch.bool),
                "tag": torch.tensor([self.classes.index(d["tag"]) if d["tag"] in self.classes else -1 for d in info], dtype=torch.long),
                "render": torch.tensor([d["render"] for d in info], dtype=torch.long),
                "group": torch.tensor([groups.setdefault(d["group"], len(groups)) for d in info], dtype=torch.long),
                "part": torch.tensor([parts.setdefault(d["part"], len(parts)) for d in info], dtype=torch.long),
                "level": meta["level"]}


def rarity_weights(stems: list[str]) -> list[float]:
    """Per close-up, how rare its rarest shown tag is: (median tag count / that tag's count) ** 0.5, from 1 to 4. Sampling
    by it shows the model the lower body, accessories and other parts few models draw more often."""
    tags = []
    for stem in stems:
        meta = json.loads(Path(stem + ".json").read_text(encoding="utf-8"))
        tags.append({d["tag"] for d in meta["instances"] if d.get("shown", True) and d["tag"]})
    count = Counter(t for ts in tags for t in ts)
    median = float(np.median(list(count.values())))
    return [min(4.0, max([1.0] + [(median / count[t]) ** 0.5 for t in ts])) for ts in tags]


def collate(items: list[dict]) -> dict:
    out = {k: torch.stack([it[k] for it in items]) for k in ("x", "cls", "role", "inst")}
    for k in ("whole", "tag", "render", "group", "part", "level"):
        out[k] = [it[k] for it in items]
    return out


def sine(h: int, w: int, dim: int, device) -> torch.Tensor:
    """2D sine position encoding, (h * w, dim)."""
    y, x = torch.meshgrid(torch.arange(h, device=device, dtype=torch.float32), torch.arange(w, device=device, dtype=torch.float32), indexing="ij")
    freq = 10000 ** (torch.arange(dim // 4, device=device, dtype=torch.float32) / (dim // 4))
    enc = [f(c.flatten()[:, None] / h * 2 * math.pi / freq) for c in (y, x) for f in (torch.sin, torch.cos)]
    return torch.cat(enc, 1)


def mlp(a: int, b: int, c: int) -> nn.Module:
    return nn.Sequential(nn.Linear(a, b), nn.GELU(), nn.Linear(b, b), nn.GELU(), nn.Linear(b, c))


class Layer(nn.Module):
    """Masked cross-attention to the picture, self-attention among the queries, feed-forward."""

    def __init__(self):
        super().__init__()
        self.cross = nn.MultiheadAttention(DIM, HEADS, batch_first=True)
        self.self_ = nn.MultiheadAttention(DIM, HEADS, batch_first=True)
        self.ffn = nn.Sequential(nn.Linear(DIM, 4 * DIM), nn.GELU(), nn.Linear(4 * DIM, DIM))
        self.norms = nn.ModuleList(nn.LayerNorm(DIM) for _ in range(3))

    def forward(self, q, pos, memory, mpos, mask):
        q = self.norms[0](q + self.cross(q + pos, memory + mpos, memory, attn_mask=mask)[0])
        q = self.norms[1](q + self.self_(q + pos, q + pos, q)[0])
        return self.norms[2](q + self.ffn(q))


class Planner5(nn.Module):
    def __init__(self, classes: int, visible: bool = True):
        """visible: a head telling a query's layer shown in the close-up from covered (v5.1; v5 has none)."""
        super().__init__()
        base = DepthAnythingForDepthEstimation.from_pretrained(p4.BACKBONE, local_files_only=True)
        self.backbone, self.neck, self.patch = base.backbone, base.neck, base.config.patch_size
        width = base.config.fusion_hidden_size
        self.classes = classes
        self.head = p4.head(width, classes)  # v4's pixel tag and role heads, by name
        self.roles = p4.head(width, ROLES)
        self.proj = nn.ModuleList(nn.Conv2d(width, DIM, 1) for _ in range(2))
        self.level = nn.Parameter(torch.zeros(2, DIM))
        self.features = nn.Sequential(nn.Conv2d(width, MASK_DIM, 3, padding=1), nn.GELU())
        self.picture = nn.Sequential(nn.Conv2d(3, 32, 3, padding=1), nn.GELU(), nn.Conv2d(32, 64, 3, padding=1), nn.GELU())
        self.fuse = nn.Conv2d(MASK_DIM + 64, MASK_DIM, 1)
        self.query = nn.Embedding(QUERIES, DIM)
        self.query_pos = nn.Embedding(QUERIES, DIM)
        self.layers = nn.ModuleList(Layer() for _ in range(LAYERS))
        self.norm = nn.LayerNorm(DIM)
        self.tag = nn.Linear(DIM, classes + 1)  # the last: none
        self.shown = mlp(DIM, DIM, MASK_DIM)
        self.whole = mlp(DIM, DIM, MASK_DIM)
        self.order = nn.Linear(DIM, 1)
        self.group = nn.Linear(DIM, VEC)
        self.part = nn.Linear(DIM, VEC)
        self.visible = nn.Linear(DIM, 1) if visible else None

    def predict(self, q: torch.Tensor) -> dict:
        q = self.norm(q)
        out = {"tag": self.tag(q), "shown": self.shown(q), "whole": self.whole(q), "order": self.order(q)[..., 0],
               "group": F.normalize(self.group(q), dim=-1), "part": F.normalize(self.part(q), dim=-1)}
        if self.visible is not None:
            out["visible"] = self.visible(q)[..., 0]
        return out

    def forward(self, pixels: torch.Tensor) -> dict:
        B, _, h, w = pixels.shape
        maps = self.backbone.forward_with_filtered_kwargs(pixels).feature_maps
        fused = self.neck(maps, h // self.patch, w // self.patch)
        fine = fused[-1]
        up = lambda x: F.interpolate(x, size=(h, w), mode="bilinear", align_corners=False)
        out = {"classes": up(self.head(fine)), "roles": up(self.roles(fine))}
        side = fine.shape[-1]
        picture = self.picture(F.interpolate(pixels, size=fine.shape[-2:], mode="bilinear", align_corners=False))
        feats = self.fuse(torch.cat([self.features(fine), picture], 1))  # (B, MASK_DIM, side, side)
        out["features"] = feats
        memories = []
        for i, f in enumerate(fused[:2]):
            m = self.proj[i](f)
            memories.append((m.flatten(2).transpose(1, 2) + self.level[i], sine(m.shape[-2], m.shape[-1], DIM, m.device)[None], m.shape[-2:]))
        q = self.query.weight[None].expand(B, -1, -1)
        pos = self.query_pos.weight[None].expand(B, -1, -1)
        stages = [self.predict(q)]
        for i, layer in enumerate(self.layers):
            memory, mpos, size = memories[i % 2]
            # Masked attention: a query attends where its last mask is, everywhere if that is nowhere.
            small = F.interpolate(feats, size=size, mode="bilinear", align_corners=False).flatten(2)
            logits = torch.einsum("bqc,bcn->bqn", stages[-1]["shown"], small)
            mask = logits.sigmoid() < 0.5
            mask[mask.all(-1)] = False
            mask = mask.detach().repeat_interleave(HEADS, 0)
            q = layer(q, pos, memory, mpos, mask)
            stages.append(self.predict(q))
        out["stages"] = stages
        return out


def has_visible(state: dict) -> bool:
    """Whether a checkpoint's state is a v5.1 one, with the visible head."""
    return any(k.startswith("visible.") for k in state)


def at_points(feats: torch.Tensor, grid: torch.Tensor) -> torch.Tensor:
    """feats (C, H, W) at grid (P, 2) in [-1, 1]: (C, P)."""
    return F.grid_sample(feats[None].float(), grid[None, None], mode="bilinear", align_corners=False)[0, :, 0]


def sample_points(inst: torch.Tensor, n: int, focus: float) -> torch.Tensor:
    """n points (n, 2) in [-1, 1] on close-up inst's grid: share 1 - focus uniform, the rest half on the borders between
    layers and half spread evenly over the layers whatever their size, so small layers and borders get points too."""
    if focus <= 0:
        return torch.rand(n, 2, device=inst.device) * 2 - 1
    H, W = inst.shape
    lab = inst.clamp(min=0)
    edge = torch.zeros_like(lab, dtype=torch.bool)
    edge[:, 1:] |= lab[:, 1:] != lab[:, :-1]
    edge[:, :-1] |= lab[:, 1:] != lab[:, :-1]
    edge[1:] |= lab[1:] != lab[:-1]
    edge[:-1] |= lab[1:] != lab[:-1]
    area = torch.bincount(lab.flatten(), minlength=int(lab.max()) + 1).float()
    small = torch.where(lab > 0, 1 / area[lab].clamp(min=1), torch.zeros_like(area[lab]))
    prob = torch.full((H * W,), (1 - focus) / (H * W), device=inst.device)
    for w, m in ((focus / 2, edge.flatten().float()), (focus / 2, small.flatten())):
        prob += w * m / m.sum() if m.sum() > 0 else w / (H * W)
    idx = torch.multinomial(prob, n, replacement=True)
    xy = torch.stack([idx % W, idx // W], 1).float() + torch.rand(n, 2, device=inst.device)
    return xy / torch.tensor([W, H], device=inst.device) * 2 - 1


def nearest(labels: torch.Tensor, grid: torch.Tensor) -> torch.Tensor:
    """Integer or boolean maps (..., H, W) at grid (P, 2): (..., P)."""
    H, W = labels.shape[-2:]
    x = ((grid[:, 0] + 1) / 2 * W).long().clamp(0, W - 1)
    y = ((grid[:, 1] + 1) / 2 * H).long().clamp(0, H - 1)
    return labels[..., y, x]


def dice(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    """Pairwise dice loss: logits (Q, P), target (K, P) -> (Q, K)."""
    p = logits.sigmoid()
    return 1 - (2 * p @ target.T + 1) / (p.sum(-1)[:, None] + target.sum(-1)[None] + 1)


def bce(logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
    """Pairwise mean binary cross-entropy: (Q, P), (K, P) -> (Q, K)."""
    pos = F.binary_cross_entropy_with_logits(logits, torch.ones_like(logits), reduction="none")
    neg = F.binary_cross_entropy_with_logits(logits, torch.zeros_like(logits), reduction="none")
    return (pos @ target.T + neg @ (1 - target).T) / max(logits.shape[-1], 1)


def stage_loss(st: dict, b: int, feats_p, shown_t, whole_t, valid, tag, render, group, part, classes: int, seen=None) -> dict:
    """One decoder stage's losses on close-up b: queries matched to its layers at the sampled points."""
    shown_q = st["shown"][b].float() @ feats_p  # (Q, P)
    whole_q = st["whole"][b].float() @ feats_p
    logits = st["tag"][b].float()
    prob = logits.softmax(-1)
    K = len(tag)
    target = torch.full((logits.shape[0],), classes, dtype=torch.long, device=logits.device)  # none
    out = {}
    if K:
        with torch.no_grad():
            known = tag >= 0
            c_cls = torch.where(known[None], -prob[:, tag.clamp(min=0)], -(1 - prob[:, classes:classes + 1]))
            sv, wv = shown_q[:, valid], shown_t[:, valid]
            cost = W_CLASS * c_cls + W_BCE * bce(sv, wv) + W_DICE * dice(sv, wv) + W_WHOLE * (bce(whole_q, whole_t) + dice(whole_q, whole_t))
            qi, ki = linear_sum_assignment(cost.cpu().numpy())
        qi, ki = torch.as_tensor(qi, device=logits.device), torch.as_tensor(ki, device=logits.device)
        target[qi] = torch.where(tag[ki] >= 0, tag[ki], torch.full_like(ki, -1))
        sv, wv = shown_q[qi][:, valid], shown_t[ki][:, valid]
        out["shown"] = W_BCE * F.binary_cross_entropy_with_logits(sv, wv) + W_DICE * torch.diagonal(dice(sv, wv)).mean()
        out["whole"] = W_WHOLE * (F.binary_cross_entropy_with_logits(whole_q[qi], whole_t[ki]) + torch.diagonal(dice(whole_q[qi], whole_t[ki])).mean())
        if "visible" in st:  # shown anywhere in the close-up, or covered
            out["visible"] = W_VISIBLE * F.binary_cross_entropy_with_logits(st["visible"][b].float()[qi], seen[ki].float())
        # Order: every pair of matched layers ranked as drawn.
        o, r = st["order"][b].float()[qi], render[ki].float()
        sign = torch.sign(r[None] - r[:, None])
        pairs = sign != 0
        out["order"] = W_ORDER * (F.softplus(-(o[None] - o[:, None]) * sign)[pairs].mean() if pairs.any() else o.sum() * 0)
        for name, ids in (("group", group), ("part", part)):
            v = st[name][b].float()[qi]
            sim = (v @ v.T) * 5
            same = (ids[ki][:, None] == ids[ki][None]).float()
            off = ~torch.eye(len(qi), dtype=torch.bool, device=v.device)
            out[name] = W_VEC * (F.binary_cross_entropy_with_logits(sim[off], same[off]) if off.any() else v.sum() * 0)
        unsure = qi[tag[ki] < 0]
    else:
        unsure = torch.zeros(0, dtype=torch.long, device=logits.device)
    weight = torch.ones(classes + 1, device=logits.device)
    weight[classes] = W_NONE
    sure = target >= 0
    out["tag"] = W_CLASS * F.cross_entropy(logits[sure], target[sure], weight=weight)
    if len(unsure):  # an unsure layer: anything but none
        out["tag"] = out["tag"] + W_CLASS * -torch.log((1 - prob[unsure, classes]).clamp(min=1e-6)).mean()
    return out


def losses(out: dict, batch: dict, weights: torch.Tensor, classes: int) -> dict:
    cls, role = batch["cls"], batch["role"]
    l_cls = F.cross_entropy(out["classes"].float(), cls, weight=weights, ignore_index=255)
    valid = cls != 255
    bits = torch.stack([(role >> b) & 1 for b in range(ROLES)], 1).float()
    l_role = (F.binary_cross_entropy_with_logits(out["roles"].float(), bits, pos_weight=torch.full((ROLES, 1, 1), 3.0, device=bits.device),
                                                  reduction="none").mean(1) * valid).sum() / valid.sum().clamp(min=1)
    total = {"cls": l_cls, "role": l_role}
    feats = out["features"]
    for b in range(feats.shape[0]):
        grid = sample_points(batch["inst"][b], POINTS, FOCUS)
        feats_p = at_points(feats[b], grid)
        ids = nearest(batch["inst"][b], grid)
        K = len(batch["tag"][b])
        shown_t = (ids[None] == torch.arange(1, K + 1, device=ids.device)[:, None]).float()
        whole_t = nearest(batch["whole"][b], grid).float() if K else torch.zeros(0, POINTS, device=feats.device)
        inst = batch["inst"][b]
        seen = torch.bincount(inst[inst > 0].flatten(), minlength=K + 1)[1:K + 1] > 0
        args = (b, feats_p, shown_t, whole_t, ids >= 0, batch["tag"][b], batch["render"][b], batch["group"][b], batch["part"][b], classes)
        for st in out["stages"]:
            for k, v in stage_loss(st, *args, seen=seen).items():
                total[k] = total.get(k, 0) + v / (len(out["stages"]) * feats.shape[0])
    return total


def to_device(batch: dict, device) -> dict:
    return {k: (v.to(device, non_blocking=True) if torch.is_tensor(v) else [t.to(device) if torch.is_tensor(t) else t for t in v])
            for k, v in batch.items()}


@torch.no_grad()
def layers_of(out: dict, b: int, size: int, classes: int, score: float = 0.5, overlap: float = 0.0, hidden: float = 0.0,
              covered_score: float = 0.0):
    """The planned layers of close-up b on a size grid: the shown map (0 none, j + 1 kept query j), and per kept
    query its tag, score, whole shape (size grid, bool), order score, group and part vectors. A v5.1 query its visible head
    calls covered takes no pixels, and is kept only above covered_score too. With overlap, a query
    that wins less than that share of its own shown mask is dropped (as Mask2Former's panoptic inference does) and the
    pixels go to the others; a query showing next to nothing, or (hidden) less than that share of its whole shape, is
    kept: a covered layer, not a copy of a shown one."""
    st = out["stages"][-1]
    prob = st["tag"][b].float().softmax(-1)
    s, t = prob[:, :classes].max(-1)
    keep = torch.nonzero(s > score)[:, 0]
    feats = F.interpolate(out["features"][b:b + 1].float(), size=(size, size), mode="bilinear", align_corners=False)[0]
    shown = torch.einsum("qc,chw->qhw", st["shown"][b].float()[keep], feats).sigmoid()
    whole = torch.einsum("qc,chw->qhw", st["whole"][b].float()[keep], feats).sigmoid() > 0.5
    covered = st["visible"][b].float()[keep] < 0 if "visible" in st else None
    if covered is not None and covered_score > 0:
        ok = ~covered | (s[keep] > covered_score)
        keep, shown, whole, covered = keep[ok], shown[ok], whole[ok], covered[ok]
    if covered is not None:  # a covered layer takes no pixels
        shown = torch.where(covered[:, None, None], torch.zeros_like(shown), shown)

    def assign(shown, keep, where=None):
        """Each pixel to the likeliest query: where some query shows it (over 0.5), or everywhere in where."""
        if not len(keep):
            return torch.zeros(size, size, dtype=torch.long, device=feats.device)
        best, arg = (shown * s[keep][:, None, None]).max(0)
        return torch.where((shown.max(0).values > 0.5) if where is None else where, arg + 1, torch.zeros_like(arg))
    labels = assign(shown, keep)
    if overlap > 0 and len(keep):
        area = (shown > 0.5).flatten(1).sum(1)
        won = torch.bincount(labels.flatten(), minlength=len(keep) + 1)[1:]
        if covered is not None:
            ok = covered | (won >= overlap * area)
        else:
            ok = (area < 16) | (area < hidden * whole.flatten(1).sum(1)) | (won >= overlap * area)
        if not ok.all():
            keep, shown, whole = keep[ok], shown[ok], whole[ok]
            if covered is not None:
                covered = covered[ok]
            labels = assign(shown, keep)
    return labels, {"tag": t[keep], "score": s[keep], "whole": whole, "order": st["order"][b].float()[keep],
                    "group": st["group"][b].float()[keep], "part": st["part"][b].float()[keep],
                    "prob": shown * s[keep][:, None, None]}  # each kept query's shown probability times its score


@torch.no_grad()
def evaluate(model, stems, classes: list[str]) -> dict:
    model.eval()
    data = Closeups(stems, False, classes, SIZE)
    C = len(classes)
    counts = np.zeros(3)  # matched, planned, true (shown layers)
    hidden = np.zeros(2)  # found, true (layers the close-up does not show)
    covered_q = np.zeros(2)  # kept queries taking no pixels: those fitting a covered layer, all
    whole_iou, pairs, tag_ok = [], np.zeros(2), np.zeros(2)
    for i in range(len(data)):
        item = data[i]
        batch = to_device(collate([item]), "cuda")
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = model(batch["x"])
        labels, q = layers_of(out, 0, SHOWN, C)
        inst = batch["inst"][0]
        labels = torch.where(inst < 0, torch.zeros_like(labels), labels)
        truth = torch.where(inst < 0, torch.zeros_like(inst), inst)
        m, p, t = p4.instance_scores(labels.cpu(), truth.cpu())
        counts += (m, p, t)
        whole_t = batch["whole"][0]
        whole_q = F.interpolate(q["whole"][None].float(), size=(WHOLE, WHOLE), mode="nearest")[0] > 0.5 if len(q["whole"]) else q["whole"]
        used, matched = set(), []
        bare = set(range(len(q["tag"]))) - {c - 1 for c in labels.unique().tolist()}
        covered_q[1] += len(bare)
        for k in range(len(whole_t)):
            tm = truth == k + 1
            j = None
            if tm.any():
                for c in torch.unique(labels[tm]).tolist():
                    if c > 0 and ((labels == c) & tm).sum() * 2 > ((labels == c) | tm).sum():
                        j = c - 1
                        break
            else:  # covered: the query whose whole shape fits it best
                hidden[1] += 1
                if len(whole_q):
                    ious = ((whole_q & whole_t[k]).flatten(1).sum(1) / (whole_q | whole_t[k]).flatten(1).sum(1).clamp(min=1))
                    for jj in ious.argsort(descending=True).tolist():
                        if jj not in used and ious[jj] > 0.5:
                            j = jj
                            hidden[0] += 1
                            covered_q[0] += jj in bare
                        break
            if j is None or j in used:
                continue
            used.add(j)
            matched.append((k, j))
            inter = (whole_q[j] & whole_t[k]).sum().item()
            whole_iou.append(inter / max(1, (whole_q[j] | whole_t[k]).sum().item()))
            if batch["tag"][0][k] >= 0:
                tag_ok += (int(q["tag"][j] == batch["tag"][0][k]), 1)
        for a in range(len(matched)):
            for b in range(len(matched)):
                ra, rb = batch["render"][0][matched[a][0]].item(), batch["render"][0][matched[b][0]].item()
                if ra < rb:
                    pairs += (int(q["order"][matched[a][1]] < q["order"][matched[b][1]]), 1)
    model.train()
    m, p, t = counts
    return {"shown": {"precision": round(m / max(p, 1), 3), "recall": round(m / max(t, 1), 3), "planned": int(p), "true": int(t)},
            "hidden": {"recall": round(hidden[0] / max(hidden[1], 1), 3), "true": int(hidden[1]),
                       "planned": int(covered_q[1]), "precision": round(covered_q[0] / max(covered_q[1], 1), 3)},
            "whole_iou": round(float(np.mean(whole_iou)) if whole_iou else 0.0, 3),
            "tag": round(tag_ok[0] / max(tag_ok[1], 1), 3), "order": round(pairs[0] / max(pairs[1], 1), 3)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("out")
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--batch", type=int, default=2)
    ap.add_argument("--accum", type=int, default=2, help="batches per step")
    ap.add_argument("--init", default="")
    ap.add_argument("--start", type=int, default=0, help="resume a run from --init: the step it stopped at")
    ap.add_argument("--eval-every", type=int, default=5000)
    ap.add_argument("--val-limit", type=int, default=300)
    ap.add_argument("--eval", default="", help="score this checkpoint on the validation set instead of training")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--none", type=float, default=W_NONE, help="weight of the none tag for queries matching no layer")
    ap.add_argument("--focus", type=float, default=FOCUS, help="share of loss points on layer borders and small layers")
    ap.add_argument("--balance", action="store_true", help="sample close-ups by their rarest tag (rarity_weights)")
    ap.add_argument("--size", type=int, default=SIZE, help="network input side (a multiple of 14); v5 to v5.3 use 728")
    a = ap.parse_args()
    data, out = Path(a.data), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    classes = json.loads((data / "classes.json").read_text(encoding="utf-8"))
    stems = sorted(str(p.with_suffix("")) for p in data.glob("*.json") if p.name != "classes.json")
    held = {s for s in stems if json.loads(Path(s + ".json").read_text(encoding="utf-8"))["held"]}
    train = [s for s in stems if s not in held]
    # Validation: the held-out close-ups as framed (not the random variants) and their tiles, at most val-limit.
    val = sorted(s for s in held if s.endswith("_0") or "_tile_" in s)
    val = val[::max(1, len(val) // a.val_limit)][:a.val_limit]
    globals()["W_NONE"] = a.none
    globals()["FOCUS"] = a.focus
    size = torch.load(a.eval, map_location="cpu").get("size", SIZE) if a.eval else a.size
    globals().update(SIZE=size, SHOWN=size // 2, WHOLE=size // 4)
    model = Planner5(len(classes), visible=not a.eval or has_visible(torch.load(a.eval, map_location="cpu")["state"])).cuda()
    if a.eval:
        model.load_state_dict(p4.fit_state(model, torch.load(a.eval, map_location="cuda")["state"]))
        with open(out / "eval.jsonl", "a", encoding="utf-8") as f:
            print(json.dumps({"checkpoint": a.eval, **evaluate(model, val, classes)}), file=f)
        return
    counts = np.zeros(len(classes))
    for s in train[::20]:
        lab = np.asarray(Image.open(s + "_cls.png"))
        counts += np.bincount(lab[lab != 255].ravel(), minlength=len(classes))[:len(classes)]
    weights = torch.tensor(1 / np.sqrt(np.maximum(counts, 1) / counts.sum()), dtype=torch.float32)
    weights = (weights / weights.mean()).clamp(0.2, 5).cuda()
    log = open(out / "log.txt", "a", encoding="utf-8")
    if a.init:
        state = torch.load(a.init, map_location="cuda")["state"]
        missing, _ = model.load_state_dict(p4.fit_state(model, state), strict=False)
        print("init from", a.init, "new:", sorted({k.split(".")[0] for k in missing}), file=log, flush=True)
    old = {"backbone", "neck", "head", "roles"}
    groups = [{"params": model.backbone.parameters(), "lr": 1e-5},
              {"params": [p for n, p in model.named_parameters() if n.split(".")[0] in old - {"backbone"}], "lr": 5e-5},
              {"params": [p for n, p in model.named_parameters() if n.split(".")[0] not in old], "lr": 1e-4}]
    opt = torch.optim.AdamW(groups, weight_decay=0.05)
    sampler = torch.utils.data.WeightedRandomSampler(rarity_weights(train), len(train)) if a.balance else None
    loader = torch.utils.data.DataLoader(Closeups(train, True, classes, SIZE), batch_size=a.batch, shuffle=sampler is None, sampler=sampler,
                                         num_workers=a.workers, drop_last=True, persistent_workers=True, collate_fn=collate)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, s / 500) * 0.5 * (1 + math.cos(math.pi * (a.start + s) / a.steps)))
    print(f"{len(train)} training close-ups, {len(val)} validation", file=log, flush=True)
    step, n = a.start, 0
    while step < a.steps:
        for batch in loader:
            batch = to_device(batch, "cuda")
            with torch.autocast("cuda", dtype=torch.bfloat16):
                o = model(batch["x"])
            parts = losses(o, batch, weights, len(classes))
            loss = sum(parts.values())
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
                print(f"step {step}/{a.steps} loss {loss.item():.4f} " + " ".join(f"{k} {float(v.detach()):.4f}" for k, v in parts.items()), file=log, flush=True)
            if step % a.eval_every == 0 or step == a.steps:
                # Every evaluation's checkpoint is kept (planner5_<step>.pt, about 0.4 GB each); planner5.pt is the last.
                torch.save({"state": model.state_dict(), "classes": classes, "size": SIZE}, out / f"planner5_{step}.pt")
                (out / "planner5.pt").unlink(missing_ok=True)
                (out / "planner5.pt").hardlink_to(out / f"planner5_{step}.pt")
                print(json.dumps({"step": step, **evaluate(model, val, classes)}), file=log, flush=True)
            if step >= a.steps:
                break


if __name__ == "__main__":
    main()
