#!/bin/bash
# v7d: v7c's data plus the skin and base-body re-reviews, base-body prompts with skin cover and painted-on wear;
# resumed from v7c for 12000 steps; then scored against v7c.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
while ! grep -q '== done' /root/v7c.log; do sleep 30; done
# The end-to-end test uses v7c's ComfyUI first; touch /root/go_v7d when it is done.
while [ ! -f /root/go_v7d ]; do sleep 30; done
pkill -f "main.py --listen 127.0.0.1 --port 8188"; sleep 10
N=/root/psd2live/build/tools/nodes
D=/root/autodl-tmp/l2d/data_v7d
S=/root/l2d_scene
rm -rf $D /root/autodl-tmp/l2d/data_v7c/_latent_cache /root/autodl-tmp/l2d/data_v7c/train_*
cd /root
/root/autodl-tmp/aitk-venv/bin/python /root/make_node_pairs.py $D $N/nodes $N/nodes_eik $N/nodes_kis $N/nodes_col \
  --verdicts $S/verdicts.jsonl --verdicts $S/verdicts_skin.jsonl --verdicts $S/verdicts_base.jsonl --wear $S/wear.jsonl \
  --reviewed-only --families $S/families.json --styles $S/styles.tsv --rough-hints --min 192 --size 512 \
  --held "caracal,mint,Ergosum/kiharu,灵魂潮汐国际服-倒闭二游/asuna,booth-6687553-weather-girl,booth-4499251-same-sister"
/root/autodl-tmp/aitk-venv/bin/python /root/eye_pairs.py /root/autodl-tmp/l2d/data_v6 $D 900
echo "pairs: $(ls $D/train_tgt/*.png | wc -l)"; df -h /root/autodl-tmp | tail -1
/root/autodl-tmp/aitk-venv/bin/python /root/v7_config.py split_v7d $D /root/autodl-tmp/l2d/split_v7d.yaml \
  --rank 64 --lr 4e-5 --steps 12000 --res "256, 384, 512" --resume /root/autodl-tmp/l2d/aitk_output/split_v7c/split_v7c.safetensors
sed -i 's/save_every: 1000/save_every: 3000/; s/sample_every: 1000/sample_every: 3000/' /root/autodl-tmp/l2d/split_v7d.yaml
cd /root/autodl-tmp/ai-toolkit
/root/autodl-tmp/aitk-venv/bin/python run.py /root/autodl-tmp/l2d/split_v7d.yaml
echo "== trained"
L=/root/autodl-tmp/comfyui/models/loras/aitk
for f in /root/autodl-tmp/l2d/aitk_output/split_v7d/*.safetensors; do ln -sf $f $L/$(basename $f); done
cd /root/autodl-tmp/comfyui
setsid nohup /root/miniconda3/bin/python main.py --listen 127.0.0.1 --port 8188 > /root/autodl-tmp/l2d/comfy.log 2>&1 < /dev/null &
until curl -s -m 3 127.0.0.1:8188/system_stats > /dev/null; do sleep 5; done
cd /root/l2d_eval/comfy
export L2D_COMFY_URL=http://127.0.0.1:8188 L2D_UNET=flux-2-klein-4b.safetensors
for ck in split_v7d; do
  echo "== $ck on v7d test"
  /root/autodl-tmp/aitk-venv/bin/python node_metrics.py aitk/$ck.safetensors /root/autodl-tmp/l2d/eval_v7d/$ck $D/test 4 --sheet | tail -35
done
echo "== v7c on v7d test"
/root/autodl-tmp/aitk-venv/bin/python node_metrics.py aitk/split_v7c.safetensors /root/autodl-tmp/l2d/eval_v7d/v7c $D/test 4 | tail -35
echo "== done"
