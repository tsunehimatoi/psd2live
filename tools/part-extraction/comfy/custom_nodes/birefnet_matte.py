"""BiRefNetMatte: an image with its background taken off by BiRefNet (matting weights), returned as RGBA."""
import torch
import torch.nn.functional as F

PATH = "/root/autodl-tmp/models/BiRefNet-matting"
_model = None


def _load():
    global _model
    if _model is None:
        from transformers import AutoModelForImageSegmentation
        _model = AutoModelForImageSegmentation.from_pretrained(PATH, trust_remote_code=True).cuda().eval().half()
    return _model


class BiRefNetMatte:
    @classmethod
    def INPUT_TYPES(cls):
        return {"required": {"image": ("IMAGE",)}}

    RETURN_TYPES = ("IMAGE",)
    FUNCTION = "run"
    CATEGORY = "image"

    @torch.no_grad()
    def run(self, image):
        model = _load()
        mean = torch.tensor([0.485, 0.456, 0.406], device="cuda").view(1, 3, 1, 1)
        std = torch.tensor([0.229, 0.224, 0.225], device="cuda").view(1, 3, 1, 1)
        out = []
        for picture in image:  # H, W, 3 in 0..1
            x = picture.permute(2, 0, 1)[None].cuda()
            h, w = x.shape[2:]
            s = F.interpolate(x, (1024, 1024), mode="bilinear", align_corners=False)
            alpha = model(((s - mean) / std).half())[-1].sigmoid().float()
            alpha = F.interpolate(alpha, (h, w), mode="bilinear", align_corners=False)[0, 0].cpu()
            out.append(torch.cat([picture, alpha[..., None]], -1))
        return (torch.stack(out),)


NODE_CLASS_MAPPINGS = {"BiRefNetMatte": BiRefNetMatte}
NODE_DISPLAY_NAME_MAPPINGS = {"BiRefNetMatte": "BiRefNet matte (RGBA)"}
