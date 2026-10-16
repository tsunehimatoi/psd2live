#!/bin/bash
# Re-render every collection with the current PartDatasetTool, then assemble pairs into /root/autodl-tmp/l2d/data_v6.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
cd /root/psd2live
./gradlew --stop -q
for spec in "eyes:/root/l2d_models/live2d:m" "eyes_eik:/root/l2d_models/Eikanya-Live2d-model:e" \
            "eyes_kis:/root/l2d_models/KISGP-model:k" "eyes_col:/root/l2d_models/collected/live2d_models:c"; do
  IFS=: read name root prefix <<< "$spec"
  echo "== $name $(date +%T)"
  rm -rf build/tools/part-dataset/$name
  PSD2LIVE_TOOLS=1 PARTS_ROOT=$root PARTS_NAME=$name PARTS_PREFIX=$prefix PARTS_VARIANTS=4 PARTS_SIZE=1024 PARTS_THREADS=48 \
    ./gradlew test --tests '*PartDatasetTool.eyes*' --rerun -Ppsd2live.testHeap=160g -Ppsd2live.testForks=1 -q 2>&1 | tail -5
  echo "$name: $(wc -l < build/tools/part-dataset/$name/samples.jsonl) close-ups, $(grep -c error build/tools/part-dataset/$name/probe.tsv) errors"
done
echo "== pairs $(date +%T)"
D=/root/psd2live/build/tools/part-dataset
rm -rf /root/autodl-tmp/l2d/data_v6
/root/autodl-tmp/aitk-venv/bin/python /root/make_pairs.py $D/eyes,$D/eyes_eik,$D/eyes_kis,$D/eyes_col /root/autodl-tmp/l2d/data_v6 768 128 \
  "caracal,mint,Ergosum/kiharu,灵魂潮汐国际服-倒闭二游/asuna,booth-6687553-weather-girl,booth-4499251-same-sister"
echo "== done $(date +%T)"
