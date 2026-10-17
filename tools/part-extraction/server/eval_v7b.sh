#!/bin/bash
# After v7b: start ComfyUI, link the checkpoints as LoRAs, score held-out nodes for a few of them.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
O=/root/autodl-tmp/l2d/aitk_output/split_v7b
while [ ! -f $O/split_v7b.safetensors ]; do sleep 30; done
sleep 20
L=/root/autodl-tmp/comfyui/models/loras/aitk
for f in $O/*.safetensors; do ln -sf $f $L/$(basename $f); done
cd /root/autodl-tmp/comfyui
setsid nohup /root/miniconda3/bin/python main.py --listen 127.0.0.1 --port 8188 > /root/autodl-tmp/l2d/comfy.log 2>&1 < /dev/null &
until curl -s -m 3 127.0.0.1:8188/system_stats > /dev/null; do sleep 5; done
cd /root/l2d_eval/comfy
export L2D_COMFY_URL=http://127.0.0.1:8188 L2D_UNET=flux-2-klein-4b.safetensors
for ck in split_v7b_000004000 split_v7b_000006000 split_v7b; do
  echo "== $ck"
  /root/autodl-tmp/aitk-venv/bin/python node_metrics.py aitk/$ck.safetensors /root/autodl-tmp/l2d/eval_v7b/$ck /root/autodl-tmp/l2d/data_v7b/test 4 --sheet | tail -35
done
echo "== none"
/root/autodl-tmp/aitk-venv/bin/python node_metrics.py none /root/autodl-tmp/l2d/eval_v7b/none /root/autodl-tmp/l2d/data_v7b/test 4 | tail -35
echo "== done"
