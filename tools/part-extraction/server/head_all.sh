#!/bin/bash
# P3: mouth and hair close-ups of every collection, once the eye renders have freed Gradle.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
while ! grep -q '== pairs' /root/render_all.log; do sleep 10; done
cd /root/psd2live
for spec in "head:/root/l2d_models/live2d:m" "head_eik:/root/l2d_models/Eikanya-Live2d-model:e" \
            "head_kis:/root/l2d_models/KISGP-model:k" "head_col:/root/l2d_models/collected/live2d_models:c"; do
  IFS=: read name root prefix <<< "$spec"
  echo "== $name $(date +%T)"
  rm -rf build/tools/part-dataset/$name
  PSD2LIVE_TOOLS=1 PARTS_ROOT=$root PARTS_NAME=$name PARTS_PREFIX=$prefix PARTS_VARIANTS=4 PARTS_SIZE=1024 PARTS_THREADS=48 \
    ./gradlew test --tests '*PartDatasetTool.head*' --rerun -Ppsd2live.testHeap=160g -Ppsd2live.testForks=1 -q 2>&1 | tail -5
  echo "$name: $(wc -l < build/tools/part-dataset/$name/samples.jsonl) close-ups, $(grep -c error build/tools/part-dataset/$name/probe.tsv) errors"
done
echo "== done $(date +%T)"
