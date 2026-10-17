#!/bin/bash
# v7b: wait for its pairs, write the config (512 buckets, 8000 steps), train.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
while ! grep -q '== done' /root/pairs_v7b.log; do sleep 10; done
cd /root
/root/autodl-tmp/aitk-venv/bin/python /root/v7_config.py split_v7b /root/autodl-tmp/l2d/data_v7b /root/autodl-tmp/l2d/split_v7b.yaml \
  --rank 64 --lr 5e-5 --steps 8000 --res "256, 384, 512"
cd /root/autodl-tmp/ai-toolkit
/root/autodl-tmp/aitk-venv/bin/python run.py /root/autodl-tmp/l2d/split_v7b.yaml
