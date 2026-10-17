#!/bin/bash
# The LoRA training data built on this machine, so a rented GPU only trains: every collection's nodes and
# reviewer-named finer nodes rendered at 1024, the reviews rekeyed to this render, then v7d's pairs (up to 512) and
# v7e's (up to 1024, a third of the large close-ups softened), each packed as a tar for upload.
#
#   bash tools/part-extraction/scene/local_data.sh        (from the repository root, after planner training)
set -e
export PYTHONIOENCODING=utf-8
REPO=$(pwd)
T=$REPO/build/tools
SCENE=$REPO/tools/part-extraction/scene
H=$T/hd
mkdir -p "$H"
for spec in "nodes|D:/模型收藏/live2d|m|scene" "nodes_kis|D:/模型收藏/live2d_github/KISGP-model|k|scene_kis" \
            "nodes_col|D:/模型收藏/live2d_collected/live2d_models|c|scene_col"; do
  IFS='|' read name root prefix scene <<< "$spec"
  if [ -f "$H/$name/samples.jsonl" ]; then echo "== $name already rendered"; continue; fi
  echo "== $name $(date +%T)"
  rm -rf "$T/nodes/hd_$name"
  PSD2LIVE_TOOLS=1 PARTS_ROOT="$root" PARTS_NAME=hd_$name PARTS_PREFIX=$prefix SCENE_DIR="$T/scene/$scene" PARTS_SIZE=1024 \
    PARTS_VARIANTS=2 NODES_PER_MODEL=30 SUBNODES="$T/describe/subnodes_$scene.jsonl" SUBNODES_PER_MODEL=20 PARTS_THREADS=12 \
    ./gradlew.bat test --tests '*NodeDatasetTool*' --rerun -Ppsd2live.testHeap=40g -Ppsd2live.testForks=1 -q 2>&1 | tail -5
  rm -rf "$H/$name" && mv "$T/nodes/hd_$name" "$H/$name"
  echo "$name: $(wc -l < "$H/$name/samples.jsonl") samples, $(grep -c error "$H/$name/index.tsv" || true) errors"
done
echo "== rekeying reviews $(date +%T)"
python "$SCENE/remap_keys.py" "$T/nodes_index" "$H" "$T/review/verdicts.jsonl" "$H/verdicts.jsonl"
python "$SCENE/remap_keys.py" "$T/nodes_index" "$H" "$T/review_skin/verdicts.jsonl" "$H/verdicts_skin.jsonl"
python "$SCENE/remap_keys.py" "$T/nodes_index" "$H" "$T/review_base/verdicts.jsonl" "$H/verdicts_base.jsonl"
python "$SCENE/remap_keys.py" "$T/nodes_index" "$H" "$T/review_wear/wear.jsonl" "$H/wear.jsonl"
HELD="caracal,mint,Ergosum/kiharu,灵魂潮汐国际服-倒闭二游/asuna,booth-6687553-weather-girl,booth-4499251-same-sister"
pairs() {  # OUT SIZE [extra make_node_pairs flags]
  local out=$1 size=$2; shift 2
  rm -rf "$out"
  python "$SCENE/make_node_pairs.py" "$out" "$H/nodes" "$H/nodes_kis" "$H/nodes_col" \
    --verdicts "$H/verdicts.jsonl" --verdicts "$H/verdicts_skin.jsonl" --verdicts "$H/verdicts_base.jsonl" --wear "$H/wear.jsonl" \
    --reviewed-only --families "$T/scene/families.json" --styles "$T/scene/styles.tsv" --rough-hints --min 192 --size "$size" \
    --held "$HELD" "$@"
  echo "$(basename "$out"): $(ls "$out/train_tgt" | grep -c png) pairs, $(du -sh "$out" | cut -f1)"
}
echo "== v7d pairs $(date +%T)"
pairs "$T/data_v7d" 512
echo "== v7e pairs $(date +%T)"
pairs "$T/data_v7e" 1024 --soften 0.35
echo "== packing $(date +%T)"
(cd "$T" && tar -cf data_v7d.tar data_v7d && tar -cf data_v7e.tar data_v7e && ls -la data_v7d.tar data_v7e.tar)
echo "== done $(date +%T)"
