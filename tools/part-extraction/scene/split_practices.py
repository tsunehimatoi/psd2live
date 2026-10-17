"""What the collected Live2D models teach about splitting: which parts are split off, how finely, in what order, and
why (motion), counted one model per family so a game with forty costumes counts once.

  python split_practices.py SCENE_ROOT OUT.md [WEAR.jsonl NODES_INDEX]

Writes a markdown report and OUT.json (the back-to-front tag order and the per-tag numbers) for the tools to use.
"""
import json
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from label import own_parameters, reaches  # noqa: E402

TAGS_ORDERED = None


def main(root: Path, out: Path, wear_path: Path | None = None, index_dir: Path | None = None) -> None:
    families = json.loads((root / "families.json").read_text(encoding="utf-8"))
    reps = [root / f["members"][0] for f in families]
    position = defaultdict(list)          # tag -> normalized draw position of its meshes' median
    own_motion = defaultdict(lambda: [0, 0])  # tag -> [nodes with own motion, nodes]
    physics_driven = defaultdict(lambda: [0, 0])
    pieces = defaultdict(list)            # tag -> deformer groups per model
    shading = defaultdict(lambda: [0, 0])  # tag -> [models with separate shadow/highlight meshes, models with tag]
    present = Counter()
    for folder in reps:
        if not (folder / "labels.json").exists():
            continue
        scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
        labels = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"]
        meshes = [m for m in scene["meshes"] if m.get("opacity", 1) >= 0.05 and labels[m["id"]]["confidence"] >= 0.3
                  and labels[m["id"]]["tag"] not in ("unknown", "background", "effect")]
        if not meshes:
            continue
        n = max(m["render"] for m in scene["meshes"]) or 1
        reach = reaches(scene["meshes"])
        physics_params = {p for g in scene["physics"] for p in g["outputs"]}
        by_tag = defaultdict(list)
        for m in meshes:
            by_tag[labels[m["id"]]["tag"]].append(m)
        for tag, ms in by_tag.items():
            present[tag] += 1
            position[tag].append(statistics.median(m["render"] for m in ms) / n)
            groups = {m.get("deformer") or m["id"] for m in ms}
            pieces[tag].append(len(groups))
            roles = {labels[m["id"]]["role"] for m in ms}
            shading[tag][1] += 1
            shading[tag][0] += bool(roles & {"shadow", "highlight"})
            own = set().union(*(own_parameters(m, reach, len(scene["meshes"])) for m in ms))
            own_motion[tag][1] += 1
            own_motion[tag][0] += bool(own)
            physics_driven[tag][1] += 1
            physics_driven[tag][0] += bool(own & physics_params)
    order = sorted(position, key=lambda t: statistics.median(position[t]))
    wear_stats = Counter()
    if wear_path and wear_path.exists():
        for line in wear_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                w = json.loads(line)
                if w.get("wear"):
                    wear_stats[(w["tag"], w["wear"])] += 1
    lines = ["# 从收集的模型学到的拆分做法", "",
             f"按模型家族各取一个代表，共 {len(reps)} 个家族；只统计置信度 0.3 以上的网格。", "",
             "## 绘制顺序（从后到前）", "",
             "各部件网格绘制位置的中位数（0 最底，1 最顶），工具按这个顺序叠图层。", "",
             "| 部件 | 位置 | 出现的家族数 |", "|---|---|---|"]
    for t in order:
        lines.append(f"| {t} | {statistics.median(position[t]):.2f} | {present[t]} |")
    lines += ["", "## 拆分的理由：运动", "",
              "有自己参数（只驱动少数网格的参数）的比例，说明这个部件要单独动所以必须拆；由物理输出驱动的比例，说明它是摆动件。", "",
              "| 部件 | 有自己的运动 | 物理驱动 | 每个模型的块数（中位数） | 有单独阴影/高光层 |", "|---|---|---|---|---|"]
    for t in sorted(present, key=lambda t: -present[t]):
        a, b = own_motion[t]
        p, q = physics_driven[t]
        s0, s1 = shading[t]
        lines.append(f"| {t} | {a / b:.0%} | {p / q:.0%} | {statistics.median(pieces[t]):.0f} | {s0 / s1:.0%} |")
    if wear_stats:
        lines += ["", "## 贴身衣物：单独拆还是画在素体上", "",
                  "已审核的素体部件里，画在上面的是什么（不确定的不计）。", "",
                  "| 部件 | 裸肤 | 贴身衣物 | 内衣 |", "|---|---|---|---|"]
        for t in ("leg", "arm", "hand", "body"):
            lines.append(f"| {t} | {wear_stats[(t, 'bare')]} | {wear_stats[(t, 'tight')]} | {wear_stats[(t, 'underwear')]} |")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    out.with_suffix(".json").write_text(json.dumps({
        "order": order,
        "own_motion": {t: own_motion[t][0] / own_motion[t][1] for t in present},
        "physics": {t: physics_driven[t][0] / physics_driven[t][1] for t in present},
        "pieces": {t: statistics.median(pieces[t]) for t in present},
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), *(Path(a) for a in sys.argv[3:5]))
