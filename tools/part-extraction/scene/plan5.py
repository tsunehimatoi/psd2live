"""Planner v5 on a whole picture: tiles at three zooms, each planned by train_planner5.py, their layers joined into one
plan. split_image.py --planner5 and plan4_eval.py --v5 use plan_layers().

Tiles (on a grid of the picture at most GRID pixels on its longer side): the whole figure (level 0); squares of half
its side, overlapping by half, over the figure (level 1); squares of a quarter, over the figure (FINE_ALL) or the head
(level 2). A tile's layers are its kept queries (train_planner5.layers_of: OVERLAP drops a query that wins too little
of its own mask). Layers of two overlapping tiles are one layer when, inside the
tiles' overlap, their shown pixels match (IoU over JOIN); covered layers (no shown pixels) when their whole shapes do.
Matches are taken best first, and none that would put two layers of one tile into one.
Each pixel goes to the layer the finest tile over it gives it, unless the pixel is near that tile's edge (the likelier
and further inside its tile where tiles of one zoom disagree); a weak layer's pixels (KEEP_SCORE, KEEP_PIXELS) go to the
nearest kept layer. Draw
order, named groups and parts are only known within a tile: every tile votes on its pairs of layers (which is drawn
later, whether two share a group or part), and the votes order and group the joined layers.
"""
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).parent))
import train_planner4 as p4  # noqa: E402
import train_planner5 as p5  # noqa: E402

GRID = 2048
JOIN = 0.5
KEEP_SCORE = 0.0  # a shown layer whose likeliest piece is less likely than this is no layer of its own
KEEP_PIXELS = 0  # nor one with fewer shown pixels (on the picture)
OVERLAP = 0.8  # a tile drops a query winning less than this share of its own shown mask (train_planner5.layers_of)
HIDDEN = 0.0  # unless it shows less than this share of its whole shape: a covered layer
FINE_ALL = True  # level 2 (quarter tiles) over the whole figure, not just the head
COVERED_SCORE = 0.95  # v5.1: a query its visible head calls covered is kept only if likelier than this
FACE = {"face", "ears", "eyewear", "irides", "eyewhite", "eyelash", "eyebrow", "mouth", "mouth_inside", "nose", "facedetail", "earwear"}
PAIRED = {"irides", "eyewhite", "eyelash", "eyebrow", "ears", "earwear", "arm", "hand", "leg", "handwear", "legwear", "footwear"}
ROLE_NAMES = ("shadow", "highlight", "line", "detail")


class Planner5Run:
    """Planner v5 on one tile: its layers on the SHOWN grid and the pixel tags."""

    def __init__(self, checkpoint: Path):
        state = torch.load(checkpoint, map_location="cpu")
        self.classes = state["classes"]
        self.model = p5.Planner5(len(self.classes), visible=p5.has_visible(state["state"]))
        self.model.load_state_dict(p4.fit_state(self.model, state["state"]))
        self.model = self.model.cuda().eval()

    @torch.no_grad()
    def __call__(self, crop: Image.Image) -> dict:
        x = torch.from_numpy(np.asarray(crop.convert("RGB").resize((p5.SIZE, p5.SIZE), Image.BILINEAR)).copy()).permute(2, 0, 1).float() / 255
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = self.model(((x - p4.MEAN) / p4.STD)[None].cuda())
        labels, q = p5.layers_of(out, 0, p5.SHOWN, len(self.classes), overlap=OVERLAP, hidden=HIDDEN, covered_score=COVERED_SCORE)
        small = lambda t: F.interpolate(t.float(), size=(p5.SHOWN, p5.SHOWN), mode="bilinear", align_corners=False)[0]
        return {"labels": labels.cpu().numpy(), "tag": q["tag"].cpu().numpy(), "score": q["score"].float().cpu().numpy(),
                "whole": q["whole"].cpu().numpy(), "order": q["order"].cpu().numpy(), "group": q["group"].cpu().numpy(),
                "part": q["part"].cpu().numpy(), "classes": small(out["classes"]).argmax(0).cpu().numpy(),
                "roles": (small(out["roles"]).sigmoid() > 0.5).cpu().numpy()}


def crop_padded(picture: Image.Image, x0: float, y0: float, side: float, fill=(225, 225, 225)) -> Image.Image:
    """The square x0, y0, side of picture, light grey past its edges."""
    canvas = Image.new("RGB", (max(1, round(side)), max(1, round(side))), fill)
    canvas.paste(picture.crop((round(x0), round(y0), round(x0 + side), round(y0 + side))), (0, 0))
    return canvas


def tiles(box: tuple[int, int, int, int], side: float, level: int, sil: np.ndarray, least: float = 0.03):
    """Squares of side, overlapping by half, over box (x0, y0, x1, y1) of the grid; those with too little figure left out."""
    x0, y0, x1, y1 = box
    out = []
    step = side / 2
    nx = max(1, int(np.ceil((x1 - x0 - side) / step)) + 1)
    ny = max(1, int(np.ceil((y1 - y0 - side) / step)) + 1)
    for j in range(ny):
        for i in range(nx):
            tx = x0 + (x1 - x0 - side) / 2 if nx == 1 else x0 + i * (x1 - x0 - side) / (nx - 1)
            ty = y0 + (y1 - y0 - side) / 2 if ny == 1 else y0 + j * (y1 - y0 - side) / (ny - 1)
            a, b = max(0, int(tx)), max(0, int(ty))
            area = sil[b:int(ty + side), a:int(tx + side)]
            if area.size and area.sum() >= least * side * side:
                out.append((tx, ty, side, level))
    return out


def square(mask: np.ndarray, grow: float) -> tuple[float, float, float]:
    ys, xs = np.nonzero(mask)
    side = max(xs.max() - xs.min(), ys.max() - ys.min()) * grow + 8
    return (xs.min() + xs.max()) / 2 - side / 2, (ys.min() + ys.max()) / 2 - side / 2, side


class Piece:
    """One tile's layer on the grid: its box, shown pixels and whole shape inside the box."""
    __slots__ = ("tile", "level", "box", "shown", "whole", "tag", "score", "order", "group", "part", "roles", "j")

    def __init__(self, **kw):
        for k, v in kw.items():
            setattr(self, k, v)


def place(grid_map: np.ndarray, tx: float, ty: float, side: float, H: int, W: int):
    """A tile's map (n, n) on the grid: (resized to the tile, clipped to the grid, the grid box x0, y0, x1, y1)."""
    s = max(1, round(side))
    m = np.asarray(Image.fromarray(grid_map.astype(np.int32)).resize((s, s), Image.NEAREST))
    x0, y0 = round(tx), round(ty)
    gx0, gy0, gx1, gy1 = max(0, x0), max(0, y0), min(W, x0 + s), min(H, y0 + s)
    return m[gy0 - y0:gy1 - y0, gx0 - x0:gx1 - x0], (gx0, gy0, gx1, gy1)


def iou_in(a: Piece, b: Piece, region, attr: str) -> float:
    """IoU of a's and b's attr (shown or whole) masks inside region (x0, y0, x1, y1) of the grid."""
    x0, y0, x1, y1 = region
    def cut(p):
        m = np.zeros((y1 - y0, x1 - x0), bool)
        bx0, by0, bx1, by1 = p.box
        ix0, iy0, ix1, iy1 = max(x0, bx0), max(y0, by0), min(x1, bx1), min(y1, by1)
        if ix1 > ix0 and iy1 > iy0:
            m[iy0 - y0:iy1 - y0, ix0 - x0:ix1 - x0] = getattr(p, attr)[iy0 - by0:iy1 - by0, ix0 - bx0:ix1 - bx0]
        return m
    ma, mb = cut(a), cut(b)
    union = (ma | mb).sum()
    return (ma & mb).sum() / union if union else 0.0


@torch.no_grad()
def plan_layers(image: Image.Image, silhouette: np.ndarray, run: Planner5Run, grain: str = "unit", min_pixels: int = 400):
    """The picture's layers back to front, as split_image.plan_layers gives them (tag, side, big, path, mask, order,
    roles), each with its whole shape ("whole", picture size) too, and the whole tile's pixel tags."""
    classes = run.classes
    W0, H0 = image.size
    scale = min(1.0, GRID / max(W0, H0))
    W, H = max(1, round(W0 * scale)), max(1, round(H0 * scale))
    flat = Image.new("RGBA", image.size, (225, 225, 225, 255))
    flat.alpha_composite(image)
    flat = flat.convert("RGB")
    sil = np.asarray(Image.fromarray(silhouette.astype(np.uint8) * 255).resize((W, H), Image.NEAREST)) > 127
    least = max(4, min_pixels * scale * scale / 4)
    ys, xs = np.nonzero(sil)
    box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
    tx, ty, side0 = square(sil, 1.05)
    plan = [(tx, ty, side0, 0)]
    results = []

    def run_tile(t):
        x, y, s, lv = t
        r = run(crop_padded(flat, x / scale, y / scale, s / scale))
        results.append((t, r))
        return r

    whole_r = run_tile(plan[0])
    tags_grid, _ = place(whole_r["classes"], tx, ty, side0, H, W)
    for t in tiles(box, side0 / 2, 1, sil):
        run_tile(t)
    # The head, from the whole tile's tags.
    face_ids = [classes.index(c) for c in FACE if c in classes]
    tag_full = np.full((H, W), 255, np.int64)
    m, (a, b, c, d) = place(whole_r["classes"], tx, ty, side0, H, W)
    tag_full[b:d, a:c] = m
    head = np.isin(tag_full, face_ids) & sil
    centre = float(np.nonzero(tag_full == classes.index("face"))[1].mean()) if (tag_full == classes.index("face")).any() else W / 2
    if FINE_ALL:
        for t in tiles(box, max(side0 / 4, 16), 2, sil):
            run_tile(t)
    elif head.sum() > least:
        hy, hx = np.nonzero(head)
        hbox = (int(hx.min()), int(hy.min()), int(hx.max()) + 1, int(hy.max()) + 1)
        hs = max(side0 / 4, 16)
        for t in tiles(hbox, hs, 2, head):
            run_tile(t)
    # Pieces.
    pieces = []
    for ti, ((x, y, s, lv), r) in enumerate(results):
        lab, gbox = place(r["labels"], x, y, s, H, W)
        if lab.size == 0:
            continue
        inside = sil[gbox[1]:gbox[3], gbox[0]:gbox[2]]
        for j in range(len(r["tag"])):
            shown = (lab == j + 1) & inside
            whole, wbox = place(r["whole"][j].astype(np.int32), x, y, s, H, W)
            whole = whole.astype(bool)
            if shown.sum() < least and whole.sum() < 4 * least:
                continue
            # Box: the shown pixels' or, for a covered layer, the whole shape's.
            src, (ox, oy) = (shown, gbox[:2]) if shown.sum() >= least else (whole, wbox[:2])
            yy, xx = np.nonzero(src)
            bx0, by0, bx1, by1 = ox + xx.min(), oy + yy.min(), ox + xx.max() + 1, oy + yy.max() + 1
            sm = np.zeros((by1 - by0, bx1 - bx0), bool)
            if shown.sum() >= least:
                sm = shown[by0 - gbox[1]:by1 - gbox[1], bx0 - gbox[0]:bx1 - gbox[0]]
            wm = np.zeros((by1 - by0, bx1 - bx0), bool)
            ix0, iy0, ix1, iy1 = max(bx0, wbox[0]), max(by0, wbox[1]), min(bx1, wbox[2]), min(by1, wbox[3])
            if ix1 > ix0 and iy1 > iy0:
                wm[iy0 - by0:iy1 - by0, ix0 - bx0:ix1 - bx0] = whole[iy0 - wbox[1]:iy1 - wbox[1], ix0 - wbox[0]:ix1 - wbox[0]]
            roles = r["roles"][:, r["labels"] == j + 1].mean(1) if (r["labels"] == j + 1).any() else np.zeros(4)
            pieces.append(Piece(tile=ti, level=lv, box=(bx0, by0, bx1, by1), shown=sm, whole=wm, tag=int(r["tag"][j]),
                                score=float(r["score"][j]), order=float(r["order"][j]), group=r["group"][j], part=r["part"][j], roles=roles, j=j))
    # Join pieces of overlapping tiles.
    parent = list(range(len(pieces)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    tbox = [(round(x), round(y), round(x + s), round(y + s)) for (x, y, s, _), _ in results]
    by_tile = defaultdict(list)
    for i, p in enumerate(pieces):
        by_tile[p.tile].append(i)
    pairs = []
    for t in range(len(results)):
        for u in range(t + 1, len(results)):
            region = (max(tbox[t][0], tbox[u][0], 0), max(tbox[t][1], tbox[u][1], 0), min(tbox[t][2], tbox[u][2], W), min(tbox[t][3], tbox[u][3], H))
            if region[2] <= region[0] or region[3] <= region[1]:
                continue
            for i in by_tile[t]:
                a = pieces[i]
                for k in by_tile[u]:
                    b = pieces[k]
                    if a.box[0] >= b.box[2] or b.box[0] >= a.box[2] or a.box[1] >= b.box[3] or b.box[1] >= a.box[3]:
                        continue
                    covered = not a.shown.any() or not b.shown.any()
                    iou = iou_in(a, b, region, "whole" if covered else "shown")
                    if iou > JOIN:
                        pairs.append((not covered, iou, i, k))
    # Shown matches before whole-shape ones, best first; two layers of one tile are never one layer, so neither are
    # the joins holding them.
    tiles_of = [{p.tile} for p in pieces]
    for _, _, i, k in sorted(pairs, reverse=True):
        a, b = find(i), find(k)
        if a != b and not tiles_of[a] & tiles_of[b]:
            parent[b] = a
            tiles_of[a] |= tiles_of[b]
    # Pixels: within a zoom the piece likeliest and furthest inside its tile (a tile's edge cuts the figure where the figure
    # goes on past it); a finer tile takes a pixel from a coarser one only away from its own cut edges.
    def cuts(t):
        """Which of tile t's edges (left, top, right, bottom) cut the figure: it goes on past them."""
        x, y, s, _ = results[t][0]
        a, b, c, d = max(0, round(x)), max(0, round(y)), min(W, round(x + s)), min(H, round(y + s))
        band = max(2, round(s / 16))
        return (a > 0 and sil[b:d, max(0, a - band):a].any(), b > 0 and sil[max(0, b - band):b, a:c].any(),
                c < W and sil[b:d, c:c + band].any(), d < H and sil[d:d + band, a:c].any())
    cut = [cuts(t) for t in range(len(results))]

    def inside_tile(t, box):
        """Per pixel of box (x0, y0, x1, y1): distance to tile t's nearest edge cutting the figure over a quarter of its
        side, at most 1."""
        x, y, s, _ = results[t][0]
        left, top, right, bottom = cut[t]
        x0, y0, x1, y1 = box
        xs, ys = np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5
        big = np.full_like(xs, np.inf)
        dx = np.minimum(xs - x if left else big, x + s - xs if right else big)
        big = np.full_like(ys, np.inf)
        dy = np.minimum(ys - y if top else big, y + s - ys if bottom else big)
        return np.minimum(1.0, np.minimum(dy[:, None], dx[None, :]) / (s / 4))

    owner = np.zeros((H, W), np.int64)  # piece index + 1
    for lv in (0, 1, 2):
        best = np.zeros((H, W), np.float32)
        claim = np.zeros((H, W), np.int64)
        firm = np.zeros((H, W), bool)
        for i, p in enumerate(pieces):
            if p.level != lv or not p.shown.any():
                continue
            x0, y0, x1, y1 = p.box
            keep = inside_tile(p.tile, p.box)
            weight = (p.score * keep).astype(np.float32)
            region = best[y0:y1, x0:x1]
            win = p.shown & (weight > region)
            region[win] = weight[win]
            claim[y0:y1, x0:x1][win] = i + 1
            firm[y0:y1, x0:x1][win] = keep[win] >= 0.5
        owner = np.where((claim > 0) & (firm | (owner == 0)), claim, owner)
    root_of = np.array([0] + [find(i) + 1 for i in range(len(pieces))])
    label = root_of[owner]  # joined layer (root piece + 1) per pixel
    members = defaultdict(list)
    for i in range(len(pieces)):
        members[find(i)].append(i)
    # Weak layers (unlikely or small: mostly a query that took a bit of a bigger layer) give their pixels to the
    # nearest kept layer.
    counts = np.bincount(label.ravel(), minlength=len(pieces) + 1)
    weak = np.zeros(len(pieces) + 1, bool)
    for r, ms in members.items():
        n = counts[r + 1]
        weak[r + 1] = n > 0 and (max(pieces[i].score for i in ms) < KEEP_SCORE or n < KEEP_PIXELS * scale * scale)
    if weak.any():
        bad = weak[label]
        _, (iy, ix) = ndimage.distance_transform_edt(bad | (label == 0), return_indices=True)
        label = np.where(bad, label[iy, ix], label)
    layers = []
    for r, ms in members.items():
        shown = label == r + 1
        n = int(shown.sum())
        covered = n < least
        if covered and not all(not pieces[i].shown.any() for i in ms):
            continue  # its pixels all went to finer layers
        whole = np.zeros((H, W), bool)
        for i in ms:
            x0, y0, x1, y1 = pieces[i].box
            whole[y0:y1, x0:x1] |= pieces[i].whole
        if covered and whole.sum() < 4 * least:
            continue
        votes = defaultdict(float)
        for i in ms:
            votes[pieces[i].tag] += pieces[i].score
        tag = classes[max(votes, key=votes.get)]
        w = np.array([pieces[i].score for i in ms])
        roles = (np.stack([pieces[i].roles for i in ms]) * w[:, None]).sum(0) / w.sum()
        layers.append({"root": r, "tag": tag, "shown": shown, "whole": whole | shown, "covered": covered,
                       "roles": [n_ for n_, v in zip(ROLE_NAMES, roles) if v > 0.15], "members": ms})
    index = {l["root"]: k for k, l in enumerate(layers)}
    # Votes within tiles: draw order and shared groups and parts.
    later = np.zeros((len(layers), len(layers)))
    same = {g: np.zeros((len(layers), len(layers))) for g in ("group", "part")}
    seen = np.zeros((len(layers), len(layers)))
    for t, ids in by_tile.items():
        ks = [(index[find(i)], pieces[i]) for i in ids if find(i) in index]
        for a in range(len(ks)):
            for b in range(len(ks)):
                (ka, pa), (kb, pb) = ks[a], ks[b]
                if ka == kb:
                    continue
                seen[ka, kb] += 1
                later[ka, kb] += pa.order > pb.order
                for g in ("group", "part"):
                    same[g][ka, kb] += float(np.dot(getattr(pa, g), getattr(pb, g))) > 0
    rank = (later - (seen - later)).sum(1) / np.maximum(seen.sum(1), 1)  # wins minus losses per vote
    ids = {}
    for g in ("group", "part"):
        par = list(range(len(layers)))

        def f(i):
            while par[i] != i:
                par[i] = par[par[i]]
                i = par[i]
            return i
        for a in range(len(layers)):
            for b in range(a + 1, len(layers)):
                if seen[a, b] and same[g][a, b] * 2 > seen[a, b]:
                    par[f(b)] = f(a)
        roots = {}
        ids[g] = [roots.setdefault(f(i), len(roots) + 1) for i in range(len(layers))]
    # Grain: layers of one group (part) as one.
    if grain != "unit":
        merged = defaultdict(list)
        for k in range(len(layers)):
            merged[ids[grain][k]].append(k)
        new = []
        for key, ks in merged.items():
            lead = max(ks, key=lambda k: layers[k]["shown"].sum())
            l = dict(layers[lead])
            l["shown"] = np.logical_or.reduce([layers[k]["shown"] for k in ks])
            l["whole"] = np.logical_or.reduce([layers[k]["whole"] for k in ks])
            new.append((l, float(np.mean([rank[k] for k in ks])), ids["part"][lead], ids["group"][lead]))
        entries = new
    else:
        entries = [(l, rank[k], ids["part"][k], ids["group"][k]) for k, l in enumerate(layers)]
    up = lambda m: np.asarray(Image.fromarray(m.astype(np.uint8) * 255).resize((W0, H0), Image.NEAREST)) > 127 if scale < 1 else m
    leaves = []
    for n, (l, r, part, group) in enumerate(entries):
        xs_ = np.nonzero(l["shown"] if l["shown"].any() else l["whole"])[1]
        mid = xs_.mean() if len(xs_) else centre
        side = ("l" if mid < centre else "r") if l["tag"] in PAIRED else "c"
        big = "head" if l["tag"] in FACE else l["tag"] if side == "c" else f"{l['tag']}_{side}"
        leaves.append({"tag": l["tag"], "side": side, "big": big, "path": [part, group, n + 1], "mask": up(l["shown"]) & silhouette,
                       "whole": up(l["whole"]), "covered": l["covered"], "order": (r,), "roles": l["roles"]})
    tags = np.asarray(Image.fromarray(tag_full.astype(np.uint8)).resize((W0, H0), Image.NEAREST)) if scale < 1 else tag_full.astype(np.uint8)
    return sorted(leaves, key=lambda leaf: leaf["order"]), tags
