"""PSD2Live's optional nunif adapter. No downloads; RGB SR and straight alpha stay separate.

Outer CPU tiles bound GPU input/output storage as well as model activations. Each tile
has context, and only its center is retained. nunif supplies its own inner seam blending.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile

import numpy as np
from PIL import Image


def extend_rgb(rgb, alpha, steps=32):
    """Extend visible colors into zero-alpha pixels without modifying visible pixels."""
    known = alpha > 0
    rgb = np.where(known[..., None], rgb, 0).astype(np.float32)
    for _ in range(steps):
        if known.all():
            break
        padded = np.pad(rgb, ((1, 1), (1, 1), (0, 0)))
        mask = np.pad(known.astype(np.float32), 1)
        colors = sum((padded[1:-1, :-2], padded[1:-1, 2:], padded[:-2, 1:-1], padded[2:, 1:-1]))
        count = mask[1:-1, :-2] + mask[1:-1, 2:] + mask[:-2, 1:-1] + mask[2:, 1:-1]
        fill = ~known & (count > 0)
        if not fill.any():
            break
        rgb[fill] = colors[fill] / count[fill, None]
        known |= fill
    return rgb


def upscale_image(source, scale, tile, infer, neural_alpha=False):
    """infer accepts/returns HWC float RGB in [0,1]; output assembly remains on CPU."""
    source = source.convert("RGBA")
    width, height = source.size
    output = Image.new("RGBA", (width * scale, height * scale))
    # Bilinear coverage has no overshoot/ringing and retains soft semi-transparent layers.
    output_alpha = source.getchannel("A").resize(output.size, Image.Resampling.BILINEAR)
    halo = 32
    overlap = 16
    for y in range(0, height, tile):
        for x in range(0, width, tile):
            right, bottom = min(x + tile, width), min(y + tile, height)
            start_x, start_y = max(0, x - overlap), max(0, y - overlap)
            left, top = max(0, start_x - halo), max(0, start_y - halo)
            box = (left, top, min(width, right + halo), min(height, bottom + halo))
            patch = np.asarray(source.crop(box), dtype=np.float32) / 255.0
            if not patch[..., 3].any():
                continue
            rgb = infer(extend_rgb(patch[..., :3], patch[..., 3], halo))
            expected = (patch.shape[0] * scale, patch.shape[1] * scale, 3)
            if rgb.shape != expected or not np.isfinite(rgb).all():
                raise ValueError(f"Invalid model output: {rgb.shape}, expected {expected}")
            crop = ((start_x - left) * scale, (start_y - top) * scale, (right - left) * scale, (bottom - top) * scale)
            image = Image.fromarray(np.rint(np.clip(rgb, 0, 1) * 255).astype(np.uint8)).crop(crop)
            image.putalpha(output_alpha.crop((start_x * scale, start_y * scale, right * scale, bottom * scale)))
            if neural_alpha:
                alpha = infer(np.repeat(patch[..., 3:4], 3, axis=2)).mean(axis=2)
                alpha_image = Image.fromarray(np.rint(np.clip(alpha, 0, 1) * 255).astype(np.uint8)).crop(crop)
                image.putalpha(alpha_image)
            # Crossfade overlapping tile predictions without retaining a full float GPU/CPU canvas.
            wx = np.ones(image.width, dtype=np.float32)
            wy = np.ones(image.height, dtype=np.float32)
            nx, ny = (x - start_x) * scale, (y - start_y) * scale
            if nx:
                wx[:nx] = np.linspace(0, 1, nx)
            if ny:
                wy[:ny] = np.linspace(0, 1, ny)
            mask = Image.fromarray(np.rint(wy[:, None] * wx[None, :] * 255).astype(np.uint8))
            output.paste(image, (start_x * scale, start_y * scale), mask)
    return output


def process_layer(source, scale, tile, model, method, noise_level, cascade_2x, neural_alpha, gpu):
    """Upscales a single layer using nunif's built-in GPU tiled rendering, alpha padding and neural alpha."""
    source = source.convert("RGBA")
    width, height = source.size
    extrema = source.getextrema()
    if extrema[3][1] == 0:
        return Image.new("RGBA", (width * scale, height * scale))

    arr = np.asarray(source, dtype=np.float32) / 255.0
    import torch
    rgb = torch.from_numpy(np.ascontiguousarray(arr[..., :3].transpose(2, 0, 1)))
    alpha = torch.from_numpy(np.ascontiguousarray(arr[..., 3:4].transpose(2, 0, 1))) if neural_alpha else None

    device = "cuda:0" if gpu else "cpu"
    with torch.inference_mode():
        if cascade_2x:
            out_rgb1, out_alpha1 = model.convert(
                rgb, alpha, method, noise_level,
                tile_size=tile, batch_size=1, tta=False, enable_amp=gpu, output_device=device
            )
            out_rgb2, out_alpha2 = model.convert(
                out_rgb1, out_alpha1, "scale", -1,
                tile_size=tile, batch_size=1, tta=False, enable_amp=gpu, output_device="cpu"
            )
            res_rgb, res_alpha = out_rgb2, out_alpha2
        else:
            res_rgb, res_alpha = model.convert(
                rgb, alpha, method, noise_level,
                tile_size=tile, batch_size=1, tta=False, enable_amp=gpu, output_device="cpu"
            )

    output = Image.fromarray(np.rint(np.clip(res_rgb.numpy().transpose(1, 2, 0), 0, 1) * 255).astype(np.uint8))
    if neural_alpha and res_alpha is not None:
        out_alpha_img = Image.fromarray(np.rint(np.clip(res_alpha[0].numpy(), 0, 1) * 255).astype(np.uint8))
        output.putalpha(out_alpha_img)
    else:
        output.putalpha(source.getchannel("A").resize(output.size, Image.Resampling.BILINEAR))
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--models", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--scale", type=int, choices=(2, 4), required=True)
    parser.add_argument("--noise-level", type=int, default=1, choices=(-1, 0, 1, 2, 3))
    parser.add_argument("--tile", type=int, default=256)
    parser.add_argument("--neural-alpha", action="store_true", default=True)
    parser.add_argument("--no-neural-alpha", dest="neural_alpha", action="store_false")
    args = parser.parse_args()
    sys.path.insert(0, args.repo)
    import torch
    import waifu2x.models  # noqa: F401 -- registers model architectures
    from waifu2x.utils import Waifu2x

    gpu = torch.cuda.is_available()
    cascade_2x = False
    model_dir = Path(args.models)

    if args.scale == 4:
        if args.noise_level >= 0 and (model_dir / f"noise{args.noise_level}_scale4x.pth").is_file():
            method = "noise_scale4x"
            model = Waifu2x(args.models, [0] if gpu else [-1])
            model.load_model(method, args.noise_level)
        elif (model_dir / "scale4x.pth").is_file():
            method = "scale4x"
            model = Waifu2x(args.models, [0] if gpu else [-1])
            model.load_model("scale4x", -1)
            args.noise_level = -1
        elif (model_dir / "scale2x.pth").is_file():
            cascade_2x = True
            model = Waifu2x(args.models, [0] if gpu else [-1])
            if args.noise_level >= 0 and (model_dir / f"noise{args.noise_level}_scale2x.pth").is_file():
                method = "noise_scale"
                model.load_model("noise_scale", args.noise_level)
                model.load_model("scale", -1)
            else:
                method = "scale"
                args.noise_level = -1
                model.load_model("scale", -1)
            print(f"Note: scale4x.pth not found; automatically cascading 2x model ({method}, noise_level={args.noise_level}) twice for 4x upscale.", flush=True)
        else:
            raise FileNotFoundError(f"Neither scale4x.pth nor scale2x.pth found in {args.models}")
    else:
        model = Waifu2x(args.models, [0] if gpu else [-1])
        if args.noise_level >= 0 and (model_dir / f"noise{args.noise_level}_scale2x.pth").is_file():
            method = "noise_scale"
            model.load_model("noise_scale", args.noise_level)
        else:
            method = "scale"
            args.noise_level = -1
            model.load_model("scale", -1)

    tasks = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    for i, task in enumerate(tasks):
        print(f"Upscaling layer {i + 1}/{len(tasks)}", flush=True)
        with Image.open(task["input"]) as source:
            output = process_layer(source, args.scale, args.tile, model, method, args.noise_level, cascade_2x, args.neural_alpha, gpu)
        destination = Path(task["output"])
        fd, temporary = tempfile.mkstemp(suffix=".png", dir=destination.parent)
        os.close(fd)
        try:
            output.save(temporary, format="PNG")
            os.replace(temporary, destination)
        finally:
            output.close()
            if os.path.exists(temporary):
                os.unlink(temporary)
    if gpu:
        print(f"Peak allocated GPU memory: {torch.cuda.max_memory_allocated() / 2**20:.0f} MiB", flush=True)


if __name__ == "__main__":
    main()
