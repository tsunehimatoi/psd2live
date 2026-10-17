"""Write an ai-toolkit config for a general-split LoRA: two controls (close-up, hint), samples from held-out nodes.

  python v7_config.py NAME DATA OUT.yaml [--rank 64] [--lr 5e-5] [--steps 12000] [--samples 12] [--resume LORA] [--res "256, 512"]
"""
import argparse
import json
import random
from pathlib import Path

from PIL import Image

TEMPLATE = """# General-split LoRA on FLUX.2 klein base 4B: close-up and hint as controls, the node on gray the target.
job: extension
config:
  name: "{name}"
  process:
    - type: "sd_trainer"
      training_folder: "/root/autodl-tmp/l2d/aitk_output"
      device: cuda:0
      network:
        type: "lora"
        linear: {rank}
        linear_alpha: {rank}
{resume}      save:
        dtype: bf16
        save_every: 1000
        max_step_saves_to_keep: 20
      datasets:
        - folder_path: "{data}/train_tgt"
          control_path:
            - "{data}/train_in"
            - "{data}/train_hint"
          caption_ext: "txt"
          caption_dropout_rate: 0.0
          shuffle_tokens: false
          cache_latents_to_disk: true
          resolution: [{res}]
      train:
        batch_size: 1
        steps: {steps}
        gradient_accumulation_steps: 1
        train_unet: true
        train_text_encoder: false
        gradient_checkpointing: true
        noise_scheduler: "flowmatch"
        optimizer: "adamw8bit"
        lr: {lr}
        timestep_type: "weighted"
        dtype: bf16
      model:
        name_or_path: "/root/autodl-tmp/comfyui/models/diffusion_models"
        arch: "flux2_klein_4b"
        quantize: false
      sample:
        sampler: "flowmatch"
        sample_every: 1000
        sample_start_step: 0
        width: 768
        height: 768
        samples:
{samples}        neg: ""
        seed: 42
        walk_seed: false
        guidance_scale: 4
        sample_steps: 24
meta:
  name: "[name]"
  version: "1.0"
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name")
    ap.add_argument("data")
    ap.add_argument("out")
    ap.add_argument("--rank", type=int, default=64)
    ap.add_argument("--lr", default="5e-5")
    ap.add_argument("--steps", type=int, default=12000)
    ap.add_argument("--samples", type=int, default=12)
    ap.add_argument("--resume")
    ap.add_argument("--res", default="256, 512, 768", help="training buckets")
    a = ap.parse_args()
    test = Path(a.data) / "test"
    rows = [json.loads(l) for l in (test / "prompts.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    rng = random.Random(7)
    rng.shuffle(rows)
    # One per tag first, rest point-of-view: variant 0 close-ups of whole tags read best.
    chosen, seen = [], set()
    for r in sorted(rows, key=lambda r: (not r["name"].endswith("_0"), "_p" in r["name"])):
        if r["tag"] not in seen and len(chosen) < a.samples:
            chosen.append(r)
            seen.add(r["tag"])
    lines = []
    for r in chosen:
        side = Image.open(test / f"{r['name']}_input.png").width
        side = max(256, min(int(a.res.split(",")[-1]), side // 16 * 16))
        lines.append(f'          - prompt: "{r["prompt"]}"\n'
                     f'            ctrl_img_1: "{test / (r["name"] + "_input.png")}"\n'
                     f'            ctrl_img_2: "{test / (r["name"] + "_hint.png")}"\n'
                     f'            width: {side}\n            height: {side}\n')
    resume = f'        pretrained_lora_path: "{a.resume}"\n' if a.resume else ""
    Path(a.out).write_text(TEMPLATE.format(name=a.name, data=a.data, rank=a.rank, lr=a.lr, steps=a.steps,
                                           samples="".join(lines), resume=resume, res=a.res), encoding="utf-8")
    print("samples:", [r["name"] for r in chosen])


if __name__ == "__main__":
    main()
