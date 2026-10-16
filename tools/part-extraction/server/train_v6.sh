#!/bin/bash
# Once data_v6 is assembled: refresh the sample control images from its test set, then train v6.
while ! grep -q '== done' /root/render_all.log; do sleep 10; done
T=/root/autodl-tmp/l2d/test
for k in c089_l_0 c201_l_0 m031_r_0 m003_l_0 m033_l_0; do
  [ -f $T/$k.png ] && cp -n $T/$k.png $T/${k}_v5.png
  cp /root/autodl-tmp/l2d/data_v6/test/${k}_input.png $T/$k.png
done
cd /root/autodl-tmp/ai-toolkit
/root/autodl-tmp/aitk-venv/bin/python run.py /root/autodl-tmp/l2d/eyes_aitk_v6.yaml
