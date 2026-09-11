"""Adapter regression tests run without torch/nunif or downloading any model."""
import importlib.util
from pathlib import Path
import unittest
import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location("worker", Path(__file__).resolve().parents[1] / "src/main/resources/upscale/nunif_worker.py")
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)


class WorkerTest(unittest.TestCase):
    def test_hidden_rgb_does_not_contaminate_visible_edge(self):
        rgb = np.zeros((12, 12, 3), dtype=np.float32)
        rgb[..., 1] = 1  # Hidden green matte
        rgb[4:8, 4:8] = [1, 0, 0]
        alpha = np.zeros((12, 12), dtype=np.float32)
        alpha[4:8, 4:8] = 0.4
        filled = worker.extend_rgb(rgb, alpha)
        np.testing.assert_allclose(filled[..., 0], 1)
        np.testing.assert_allclose(filled[..., 1:], 0)
        np.testing.assert_array_equal(filled[4:8, 4:8], rgb[4:8, 4:8])

    def test_tiled_output_matches_whole_image_and_preserves_soft_alpha(self):
        rng = np.random.default_rng(123)
        data = rng.integers(1, 256, (85, 97, 4), dtype=np.uint8)
        source = Image.fromarray(data)
        nearest = lambda rgb: rgb.repeat(2, axis=0).repeat(2, axis=1)
        actual = worker.upscale_image(source, 2, 32, nearest)
        np.testing.assert_array_equal(np.asarray(actual)[..., :3], data[..., :3].repeat(2, axis=0).repeat(2, axis=1))
        np.testing.assert_array_equal(np.asarray(actual)[..., 3], np.asarray(source.getchannel("A").resize(actual.size, Image.Resampling.BILINEAR)))

    def test_empty_layer_skips_inference_and_stays_transparent(self):
        def fail(_):
            self.fail("Empty layer must not invoke inference")
        result = worker.upscale_image(Image.new("RGBA", (9, 7), (0, 255, 0, 0)), 4, 64, fail)
        self.assertEqual((36, 28), result.size)
        self.assertFalse(np.asarray(result)[..., 3].any())

    def test_neural_alpha_is_explicit(self):
        data = np.full((7, 9, 4), 80, dtype=np.uint8)
        infer = lambda rgb: rgb.repeat(2, axis=0).repeat(2, axis=1)
        result = worker.upscale_image(Image.fromarray(data), 2, 64, infer, neural_alpha=True)
        np.testing.assert_array_equal(np.asarray(result), data.repeat(2, axis=0).repeat(2, axis=1))

    def test_wrong_model_scale_is_rejected(self):
        with self.assertRaises(ValueError):
            worker.upscale_image(Image.new("RGBA", (8, 8), "red"), 2, 64, lambda rgb: rgb)


if __name__ == "__main__":
    unittest.main()
