"""Klein 4B part-extraction LoRA on the user's ComfyUI: training graph, inference graph, server-side file moves."""
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from workflows import Graph

UNET = os.environ.get("L2D_UNET", "flux-2-klein-4b-fp8.safetensors")
TEXT = "qwen_3_4b.safetensors"
VAE = "flux2-vae.safetensors"
SZ20 = str(Path(__file__).parent.parent / "sz20.sh")


def ssh(command: str, stdin: bytes | None = None, timeout: float = 600) -> str:
    out = subprocess.run(["sh", SZ20, command], input=stdin, capture_output=True, timeout=timeout)
    if out.returncode != 0:
        raise RuntimeError(out.stderr.decode(errors="replace")[-2000:])
    return out.stdout.decode(errors="replace")


def upload_folder(local: Path, remote_name: str) -> None:
    """Copies a local folder into ComfyUI's input/<remote_name> (replacing it) as one tar stream."""
    tar = subprocess.run(["tar", "-C", str(local), "-cf", "-", "."], capture_output=True, check=True).stdout
    ssh(f"rm -rf /opt/comfyui/input/{remote_name} && mkdir -p /opt/comfyui/input/{remote_name} && "
        f"tar -C /opt/comfyui/input/{remote_name} -xf - && chmod -R a+rX /opt/comfyui/input/{remote_name} && "
        f"ls /opt/comfyui/input/{remote_name} | wc -l", stdin=tar, timeout=3600)


def train_graph(folder_in: str, folder_tgt: str, name: str, steps: int, rank: int = 32, lr: float = 1e-4,
                batch: int = 1, seed: int = 0) -> dict:
    g = Graph(prefix="l2d")
    images = g("LoadImageDataSetFromFolder", folder=folder_in)
    targets = g.add("LoadImageTextDataSetFromFolder", folder=folder_tgt)
    vae = g("VAELoader", vae_name=VAE)
    clip = g("CLIPLoader", clip_name=TEXT, type="flux2", device="default")
    model = g("UNETLoader", unet_name=UNET, weight_dtype="default")
    cond = g("CLIPTextEncode", clip=clip, text=[targets, 1])
    cond = g("ReferenceLatent", conditioning=cond, latent=g("VAEEncode", pixels=images, vae=vae))
    latents = g("VAEEncode", pixels=[targets, 0], vae=vae)
    train = g.add("TrainLoraNode", model=model, latents=latents, positive=cond, batch_size=batch, grad_accumulation_steps=1,
                  steps=steps, learning_rate=lr, rank=rank, optimizer="AdamW", loss_function="MSE", seed=seed,
                  training_dtype="bf16", lora_dtype="bf16", quantized_backward=False, algorithm="LoRA",
                  gradient_checkpointing=True, checkpoint_depth=1, offloading=False, existing_lora="[None]",
                  bucket_mode=False, bypass_mode=True)
    g.add("SaveLoRA", lora=[train, 0], prefix=f"loras/{name}", steps=steps)
    g.add("LossGraphNode", loss=[train, 1], filename_prefix=f"l2d/{name}_loss")
    return g.nodes


def edit_graph(image: str, prompt: str, width: int, height: int, lora: str | None = None, strength: float = 1.0,
               steps: int = 4, seed: int = 1, name: str = "l2d_edit", unet: str = UNET, cfg: float = 1.0,
               refs: tuple[str, ...] = (), matte: bool = False) -> dict:
    """The distilled model samples in 4 steps without guidance; the base model wants about 20 to 30 steps and cfg 3 to 5.
    refs are further reference images after [image] (the general split's hint: the part's visible area). matte saves
    the result with its background taken off by BiRefNet (the BiRefNetMatte node), as an RGBA PNG."""
    g = Graph(prefix="l2d")
    model = g("UNETLoader", unet_name=unet, weight_dtype="default")
    if lora:
        model = g("LoraLoaderModelOnly", model=model, lora_name=lora, strength_model=strength)
    clip = g("CLIPLoader", clip_name=TEXT, type="flux2", device="default")
    vae = g("VAELoader", vae_name=VAE)
    positive = g("CLIPTextEncode", clip=clip, text=prompt)
    negative = g("ConditioningZeroOut", conditioning=positive)
    for picture in (image, *refs):
        reference = g("VAEEncode", pixels=g.load(picture), vae=vae)
        positive = g("ReferenceLatent", conditioning=positive, latent=reference)
        if cfg != 1.0:
            negative = g("ReferenceLatent", conditioning=negative, latent=reference)
    sample = g.add("SamplerCustomAdvanced", noise=g("RandomNoise", noise_seed=seed),
                   guider=g("CFGGuider", model=model, positive=positive, negative=negative, cfg=cfg),
                   sampler=g("KSamplerSelect", sampler_name="euler"), sigmas=g("Flux2Scheduler", steps=steps, width=width, height=height),
                   latent_image=g("EmptyFlux2LatentImage", width=width, height=height, batch_size=1))
    decoded = g("VAEDecode", samples=[sample, 0], vae=vae)
    g.save(g("BiRefNetMatte", image=decoded) if matte else decoded, name)
    return g.nodes
