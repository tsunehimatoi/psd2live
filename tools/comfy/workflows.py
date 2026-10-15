"""ComfyUI workflows (API format) for splitting a part off an image by redrawing.

Each builder returns a workflow dict for Comfy.run. Inputs are names of images uploaded with Comfy.upload. Model file
names default to what MODELS lists; change MODELS to match the server. See README.md for which model does what and
how to word prompts for each.
"""

from __future__ import annotations

import json

MODELS = {
    "klein": "flux-2-klein-9b-Q6_K.gguf",
    "klein_base": "flux-2-klein-base-9b-Q6_K.gguf",
    "klein_encoder": "qwen_3_8b_fp8mixed.safetensors",
    "flux2_vae": "flux2-vae.safetensors",
    "sdxl": "waiIllustriousSDXL_v170.safetensors",
    "union": "controlnet-union-sdxl-promax.safetensors",
    "sam3": "sam3.1_multiplex_fp16.safetensors",
    "matte": "BiRefNet_HR-matting.safetensors",
    "depth": "depth_anything_3_mono_large.safetensors",
    "qwen_edit": "qwen-image-edit-2511-Q4_K_M.gguf",
    "qwen_encoder": "qwen_2.5_vl_7b_fp8_scaled.safetensors",
    "qwen_vae": "qwen_image_vae.safetensors",
    "upscale": "4x-UltraSharp.pth",
}

UNION_TYPES = ("tile", "canny/lineart/anime_lineart/mlsd", "depth", "hed/pidi/scribble/ted", "normal", "segment", "openpose")


class Graph:
    """Nodes numbered in the order they are added; calling it adds a node and returns its first output."""

    def __init__(self, prefix: str = "psd2live"):
        self.nodes: dict[str, dict] = {}
        self.prefix = prefix

    def add(self, cls: str, **inputs: object) -> str:
        key = str(len(self.nodes) + 1)
        self.nodes[key] = {"class_type": cls, "inputs": inputs}
        return key

    def __call__(self, cls: str, **inputs: object) -> list:
        return [self.add(cls, **inputs), 0]

    def load(self, image: str) -> list:
        return self("LoadImage", image=image)

    def save(self, image: list, name: str) -> None:
        self.add("SaveImage", images=image, filename_prefix=f"{self.prefix}/{name}")

    def save_mask(self, mask: list, name: str) -> None:
        self.save(self("MaskToImage", mask=mask), name)


def klein(image: str, prompt: str, width: int, height: int, seed: int = 1, steps: int = 4, refs: tuple[str, ...] = (),
          base: bool = False, cfg: float = 1.0, negative: str = "", name: str = "klein") -> dict:
    """Flux.2 Klein edit of [image]: an instruction in plain sentences. At the input's own size the output is pixel
    aligned with it, which is what a peel needs. refs are further images the prompt may refer to (parts already made).
    The distilled model runs at cfg 1 without a negative; base takes cfg and a negative, at about eight times the time."""
    g = Graph()
    model = g("UnetLoaderGGUF", unet_name=MODELS["klein_base" if base else "klein"])
    clip = g("CLIPLoader", clip_name=MODELS["klein_encoder"], type="flux2", device="default")
    vae = g("VAELoader", vae_name=MODELS["flux2_vae"])
    guided = base and cfg > 1
    positive = g("CLIPTextEncode", clip=clip, text=prompt)
    negative_c = g("CLIPTextEncode", clip=clip, text=negative) if guided else g("ConditioningZeroOut", conditioning=positive)
    for reference in (image, *refs):
        latent = g("VAEEncode", pixels=g.load(reference), vae=vae)
        positive = g("ReferenceLatent", conditioning=positive, latent=latent)
        if guided:
            negative_c = g("ReferenceLatent", conditioning=negative_c, latent=latent)
    sample = g.add("SamplerCustomAdvanced", noise=g("RandomNoise", noise_seed=seed),
                   guider=g("CFGGuider", model=model, positive=positive, negative=negative_c, cfg=cfg),
                   sampler=g("KSamplerSelect", sampler_name="euler"), sigmas=g("Flux2Scheduler", steps=steps, width=width, height=height),
                   latent_image=g("EmptyFlux2LatentImage", width=width, height=height, batch_size=1))
    g.save(g("VAEDecode", samples=[sample, 0], vae=vae), name)
    return g.nodes


def qwen_edit(image: str, prompt: str, seed: int = 1, steps: int = 20, cfg: float = 2.5, negative: str = "",
              refs: tuple[str, ...] = (), name: str = "qwen") -> dict:
    """Qwen-Image-Edit: follows instructions closely and draws clean anime shapes (a hairline), but shifts the image by
    a pixel or two and touches what it was not asked to; use it for a part's look, not for a peel's aligned base."""
    g = Graph()
    model = g("ModelSamplingAuraFlow", model=g("UnetLoaderGGUF", unet_name=MODELS["qwen_edit"]), shift=3.0)
    clip = g("CLIPLoader", clip_name=MODELS["qwen_encoder"], type="qwen_image", device="default")
    vae = g("VAELoader", vae_name=MODELS["qwen_vae"])
    images = {f"image{i + 1}": g.load(reference) for i, reference in enumerate((image, *refs))}
    positive = g("TextEncodeQwenImageEditPlus", clip=clip, prompt=prompt, vae=vae, **images)
    negative_c = g("TextEncodeQwenImageEditPlus", clip=clip, prompt=negative, vae=vae, **images)
    sample = g("KSampler", model=model, seed=seed, steps=steps, cfg=cfg, sampler_name="euler", scheduler="simple",
               positive=positive, negative=negative_c, latent_image=g("VAEEncode", pixels=images["image1"], vae=vae), denoise=1.0)
    g.save(g("VAEDecode", samples=sample, vae=vae), name)
    return g.nodes


def sdxl_txt2img(positive: str, negative: str, width: int = 832, height: int = 1216, seed: int = 1, steps: int = 28,
                 cfg: float = 5.5, batch: int = 1, checkpoint: str | None = None, name: str = "txt2img",
                 sampler: str = "euler_ancestral") -> dict:
    """A new SDXL picture from danbooru tags, such as the reference a model starts from; enlarge it afterwards with
    upscale and an sdxl_inpaint tile pass rather than generating large."""
    g = Graph()
    loader = g.add("CheckpointLoaderSimple", ckpt_name=checkpoint or MODELS["sdxl"])
    model, clip, vae = [loader, 0], [loader, 1], [loader, 2]
    if "vpred" in (checkpoint or MODELS["sdxl"]).lower():
        model = g("ModelSamplingDiscrete", model=model, sampling="v_prediction", zsnr=True)
    sample = g("KSampler", model=model, seed=seed, steps=steps, cfg=cfg, sampler_name=sampler, scheduler="normal",
               positive=g("CLIPTextEncode", clip=clip, text=positive), negative=g("CLIPTextEncode", clip=clip, text=negative),
               latent_image=g("EmptyLatentImage", width=width, height=height, batch_size=batch), denoise=1.0)
    g.save(g("VAEDecode", samples=sample, vae=vae), name)
    return g.nodes


def sdxl_inpaint(image: str, mask: str, positive: str, negative: str, seed: int = 1, denoise: float = 0.75, steps: int = 28,
                 cfg: float = 5.5, grow: int = 8, feather: int = 12, union: str | None = None, union_strength: float = 0.6,
                 union_end: float = 1.0, control: str | None = None, checkpoint: str | None = None, name: str = "sdxl",
                 sampler: str = "euler_ancestral") -> dict:
    """SDXL repaint of [mask] (red channel, white where to paint) in [image] with Differential Diffusion, composited
    back so unmasked pixels stay the input's own. Prompts are danbooru tags. A whole-image mask with union "tile" and
    denoise 0.3-0.45 sharpens an upscaled close-up in its own style. A v-prediction checkpoint (name holding "Vpred")
    gets its sampling set."""
    if union is not None and union not in UNION_TYPES:
        raise ValueError(f"union is one of {UNION_TYPES}")
    g = Graph()
    loader = g.add("CheckpointLoaderSimple", ckpt_name=checkpoint or MODELS["sdxl"])
    model, clip, vae = [loader, 0], [loader, 1], [loader, 2]
    if "vpred" in (checkpoint or MODELS["sdxl"]).lower():
        model = g("ModelSamplingDiscrete", model=model, sampling="v_prediction", zsnr=True)
    model = g("DifferentialDiffusion", model=model)
    source = g.load(image)
    region = g("ImageToMask", image=g.load(mask), channel="red")
    if grow:
        region = g("GrowMask", mask=region, expand=grow, tapered_corners=True)
    if feather:
        region = g("FeatherMask", mask=region, left=feather, top=feather, right=feather, bottom=feather)
    positive_c = g("CLIPTextEncode", clip=clip, text=positive)
    negative_c = g("CLIPTextEncode", clip=clip, text=negative)
    if union:
        net = g("SetUnionControlNetType", control_net=g("ControlNetLoader", control_net_name=MODELS["union"]), type=union)
        applied = g.add("ControlNetApplyAdvanced", positive=positive_c, negative=negative_c, control_net=net,
                        image=g.load(control) if control else source, strength=union_strength, start_percent=0.0,
                        end_percent=union_end, vae=vae)
        positive_c, negative_c = [applied, 0], [applied, 1]
    latent = g("SetLatentNoiseMask", samples=g("VAEEncode", pixels=source, vae=vae), mask=region)
    sample = g("KSampler", model=model, seed=seed, steps=steps, cfg=cfg, sampler_name=sampler, scheduler="normal",
               positive=positive_c, negative=negative_c, latent_image=latent, denoise=denoise)
    painted = g("VAEDecode", samples=sample, vae=vae)
    g.save(g("ImageCompositeMasked", destination=source, source=painted, x=0, y=0, resize_source=False, mask=region), name)
    return g.nodes


def sam3(image: str, prompts: list[str], threshold: float = 0.4, separate: bool = False,
         positive_points: list[dict] | None = None, negative_points: list[dict] | None = None, name: str = "sam") -> dict:
    """SAM 3 masks, one image per prompt (<name>_<index>): a short English noun ("bangs", "eyebrows", "mouth"), or ""
    with points ({"x", "y"} in pixels) alone. separate gives each instance its own mask instead of their union."""
    g = Graph()
    loader = g.add("CheckpointLoaderSimple", ckpt_name=MODELS["sam3"])
    source = g.load(image)
    for index, prompt in enumerate(prompts):
        extra: dict[str, object] = {}
        if prompt:
            extra["conditioning"] = g("CLIPTextEncode", clip=[loader, 1], text=prompt)
        if positive_points:
            extra["positive_coords"] = json.dumps(positive_points)
        if negative_points:
            extra["negative_coords"] = json.dumps(negative_points)
        detected = g("SAM3_Detect", model=[loader, 0], image=source, threshold=threshold, refine_iterations=2,
                     individual_masks=separate, **extra)
        g.save_mask(detected, f"{name}_{index}")
    return g.nodes


def matte(image: str, name: str = "matte") -> dict:
    """An alpha matte of the figure (BiRefNet HR matting): soft edges along hair strands."""
    g = Graph()
    remover = g("LoadBackgroundRemovalModel", bg_removal_name=MODELS["matte"])
    g.save_mask(g("RemoveBackground", bg_removal_model=remover, image=g.load(image)), name)
    return g.nodes


def depth(image: str, resolution: int = 1008, name: str = "depth") -> dict:
    """Relative depth (Depth Anything 3), stretched to the image's range. The background dominates the range; read
    depth inside the figure's matte to order parts."""
    g = Graph()
    model = g("LoadDA3Model", model_name=MODELS["depth"], weight_dtype="default")
    geometry = g("DA3Inference", da3_model=model, image=g.load(image), resolution=resolution,
                 resize_method="upper_bound_resize", mode="mono")
    g.save(g("DA3Render", da3_geometry=geometry, output="depth", **{"output.normalization": "min_max", "output.apply_sky_clip": False}), name)
    return g.nodes


def upscale(image: str, name: str = "upscale") -> dict:
    """A 4x model upscale, the first step before an SDXL tile pass."""
    g = Graph()
    g.save(g("ImageUpscaleWithModel", upscale_model=g("UpscaleModelLoader", model_name=MODELS["upscale"]), image=g.load(image)), name)
    return g.nodes
