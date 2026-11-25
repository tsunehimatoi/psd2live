<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/TEXTURE_UPSCALE.md) · **English** · [한국어](../../ko/guide/TEXTURE_UPSCALE.md) · [日本語](../../ja/guide/TEXTURE_UPSCALE.md)

# Texture upscaling

[Documentation](../README.md) · [Quick reference](USER_GUIDE.md) · [Development and the command line](DEVELOPMENT.md)

This corresponds to lesson 19 of the in-app beginner route. Upscaling enlarges textures layer by layer before the atlas is packed. It is off by default. It does not change the original PSD, the canvas size, or the rig. Preview and export use the processed textures.

## Setup and use

Open **Model → Upscale textures…** (default `Ctrl+U`) and fill in:

| Field | What to enter |
| --- | --- |
| Python | The Python executable that has the nunif dependencies installed. It is not a command string with arguments |
| nunif directory | The local source directory that contains the `nunif` and `waifu2x` subdirectories |
| Model directory | The directory that actually stores the Art `.pth` weights. The application can detect the `swin_unet_v3/art` and `swin_unet/art` paths |
| Scale | 1 turns it off. 2× or 4× enlarges |
| Noise reduction | -1 enlarges only. 0–3 are the noise-reduction levels the model in use supports. The default is 1 |
| Neural alpha | On by default. When it is off, alpha is enlarged bilinearly |
| Input tile | 64–512, default 256. Reduce it when video memory is short |

Prepare a local Python, PyTorch, nunif, and matching weights before you apply. Installation requirements for a specific upstream version should be read from the notes shipped with the version you use. Release packages do not include a full inference environment. Applying rebuilds the preview, and the configuration is saved with the project. After you move to another computer, check the local paths again.

4× prefers native 4× weights. When they are missing, the current worker can run a supported 2× model twice. A missing matching model reports an error. Ordinary interpolation is not substituted for upscaling.

## CLI example

```powershell
.\gradlew.bat run --args="--input examples/tml/psd-input/tml.psd --output build/upscaled-model --upscale 2 --upscale-python C:/venv/Scripts/python.exe --nunif-dir C:/nunif --upscale-model C:/nunif/waifu2x/pretrained_models/swin_unet_v3/art --upscale-tile 256"
```

Turn off neural alpha with `--no-upscale-neural-alpha`. The other parameters are in the [CLI table](DEVELOPMENT.md). Replace the example paths with paths on this machine. When a path contains spaces, quote it by the launcher's rules.

## Result and resources

RGB and alpha are processed separately. Color in transparent areas is extended to reduce sampling-edge contamination. Neural alpha can still change translucent outlines. Check hair strands, eyelashes, lip lines, and mask edges in motion, on both dark and light backgrounds.

Inference runs serially per layer. The output and atlas packing also use system memory. The cache is in `.psd2live/cache/upscale` under the user directory, and it includes identifiers related to the artwork, the configuration, the code, and the weights. It can be cleared after the application exits. For a wrong model, a size past the limit, or a timeout, check the log. Do not only enlarge the atlas and retry.

Implementation: [configuration and process management](../../../src/main/kotlin/io/github/psd2live/core/TextureUpscale.kt), [nunif worker](../../../src/main/resources/upscale/nunif_worker.py).
