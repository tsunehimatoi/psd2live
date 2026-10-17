#!/bin/bash
# v7c: reviewed nodes with posed pieces and rough hints, plus eyes; resumed from v7b for 20000 steps; then scored.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
N=/root/psd2live/build/tools/nodes
D=/root/autodl-tmp/l2d/data_v7c
pkill -f "main.py --listen 127.0.0.1 --port 8188"
rm -rf $D /root/autodl-tmp/l2d/data_v7b/_latent_cache /root/autodl-tmp/l2d/data_v7b/train_*
cd /root
/root/autodl-tmp/aitk-venv/bin/python /root/make_node_pairs.py $D $N/nodes $N/nodes_eik $N/nodes_kis $N/nodes_col \
  --verdicts /root/l2d_scene/verdicts.jsonl --reviewed-only --families /root/l2d_scene/families.json --styles /root/l2d_scene/styles.tsv \
  --rough-hints --min 192 --size 512 \
  --held "caracal,mint,Ergosum/kiharu,灵魂潮汐国际服-倒闭二游/asuna,booth-6687553-weather-girl,booth-4499251-same-sister"
/root/autodl-tmp/aitk-venv/bin/python /root/eye_pairs.py /root/autodl-tmp/l2d/data_v6 $D 900
echo "pairs: $(ls $D/train_tgt/*.png | wc -l)"; df -h /root/autodl-tmp | tail -1
/root/autodl-tmp/aitk-venv/bin/python /root/v7_config.py split_v7c $D /root/autodl-tmp/l2d/split_v7c.yaml \
  --rank 64 --lr 5e-5 --steps 20000 --res "256, 384, 512" --resume /root/autodl-tmp/l2d/aitk_output/split_v7b/split_v7b.safetensors
sed -i 's/save_every: 1000/save_every: 2500/; s/sample_every: 1000/sample_every: 2500/' /root/autodl-tmp/l2d/split_v7c.yaml
cd /root/autodl-tmp/ai-toolkit
/root/autodl-tmp/aitk-venv/bin/python run.py /root/autodl-tmp/l2d/split_v7c.yaml
echo "== trained"
L=/root/autodl-tmp/comfyui/models/loras/aitk
for f in /root/autodl-tmp/l2d/aitk_output/split_v7c/*.safetensors; do ln -sf $f $L/$(basename $f); done
cd /root/autodl-tmp/comfyui
setsid nohup /root/miniconda3/bin/python main.py --listen 127.0.0.1 --port 8188 > /root/autodl-tmp/l2d/comfy.log 2>&1 < /dev/null &
until curl -s -m 3 127.0.0.1:8188/system_stats > /dev/null; do sleep 5; done
cd /root/l2d_eval/comfy
export L2D_COMFY_URL=http://127.0.0.1:8188 L2D_UNET=flux-2-klein-4b.safetensors
for ck in split_v7c_000010000 split_v7c; do
  echo "== $ck"
  /root/autodl-tmp/aitk-venv/bin/python node_metrics.py aitk/$ck.safetensors /root/autodl-tmp/l2d/eval_v7c/$ck $D/test 4 --sheet | tail -35
done
echo "== done"
