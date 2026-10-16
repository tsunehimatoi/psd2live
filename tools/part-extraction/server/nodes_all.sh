#!/bin/bash
# Training nodes of every collection from the uploaded labels (/root/l2d_scene).
export LANG=C.UTF-8 LC_ALL=C.UTF-8
cd /root/psd2live
for spec in "nodes:/root/l2d_models/live2d:m:scene" "nodes_eik:/root/l2d_models/Eikanya-Live2d-model:e:scene_eik" \
            "nodes_kis:/root/l2d_models/KISGP-model:k:scene_kis" "nodes_col:/root/l2d_models/collected/live2d_models:c:scene_col"; do
  IFS=: read name root prefix scene <<< "$spec"
  echo "== $name $(date +%T)"
  rm -rf build/tools/nodes/$name
  PSD2LIVE_TOOLS=1 PARTS_ROOT=$root PARTS_NAME=$name PARTS_PREFIX=$prefix SCENE_DIR=/root/l2d_scene/$scene PARTS_SIZE=768 \
    PARTS_VARIANTS=2 NODES_PER_MODEL=30 PARTS_THREADS=48 \
    ./gradlew test --tests '*NodeDatasetTool*' --rerun -Ppsd2live.testHeap=160g -Ppsd2live.testForks=1 -q 2>&1 | tail -5
  echo "$name: $(wc -l < build/tools/nodes/$name/samples.jsonl) samples, $(grep -c 'no labels' build/tools/nodes/$name/index.tsv) unlabelled, $(grep -c error build/tools/nodes/$name/index.tsv) errors"
done
echo "== done $(date +%T)"
