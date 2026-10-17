#!/bin/bash
# v7b pairs: reviewed nodes only (relabelled where the reviewer said so), plus eye close-ups from v6.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
N=/root/psd2live/build/tools/nodes
D=/root/autodl-tmp/l2d/data_v7b
rm -rf $D /root/autodl-tmp/l2d/data_v7a/train_* /root/autodl-tmp/l2d/data_v7a/_latent_cache
cd /root
/root/autodl-tmp/aitk-venv/bin/python /root/make_node_pairs.py $D $N/nodes $N/nodes_eik $N/nodes_kis $N/nodes_col \
  --verdicts /root/l2d_scene/verdicts.jsonl --reviewed-only --families /root/l2d_scene/families.json --styles /root/l2d_scene/styles.tsv \
  --pieces-once --min 192 --size 512 \
  --held "caracal,mint,Ergosum/kiharu,灵魂潮汐国际服-倒闭二游/asuna,booth-6687553-weather-girl,booth-4499251-same-sister"
/root/autodl-tmp/aitk-venv/bin/python /root/eye_pairs.py /root/autodl-tmp/l2d/data_v6 $D 900
ls $D/train_tgt/*.png | wc -l; du -sh $D; df -h /root/autodl-tmp | tail -1
echo "== done"
