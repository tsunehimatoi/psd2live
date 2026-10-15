import unittest

import workflows


def links(nodes: dict) -> list[tuple[str, str]]:
    found = []
    for key, node in nodes.items():
        for value in node["inputs"].values():
            if isinstance(value, list) and len(value) == 2 and isinstance(value[0], str):
                found.append((key, value[0]))
    return found


class WorkflowTest(unittest.TestCase):
    def check(self, nodes: dict) -> None:
        for source, target in links(nodes):
            self.assertIn(target, nodes, f"node {source} links to missing node {target}")
            self.assertLess(int(target), int(source), "a node links to a later node")
        self.assertTrue(any(node["class_type"] == "SaveImage" for node in nodes.values()))

    def test_every_builder_links_its_nodes(self):
        self.check(workflows.klein("a.png", "Remove the bangs.", 1024, 1024, refs=("b.png",)))
        self.check(workflows.klein("a.png", "Remove the bangs.", 1024, 1024, base=True, cfg=4, negative="bangs"))
        self.check(workflows.qwen_edit("a.png", "Remove the bangs."))
        self.check(workflows.sdxl_txt2img("1girl", "lowres", batch=2))
        self.check(workflows.kontext("a.png", "Remove the bangs.", lora="kontext/UndoMask_kontext_v2.safetensors"))
        self.check(workflows.sdxl_inpaint("a.png", "m.png", "1girl", "lowres", loras=(("sdxl/a.safetensors", 0.5),)))
        self.check(workflows.sdxl_inpaint("a.png", "m.png", "1girl", "lowres", union="tile"))
        self.check(workflows.sam3("a.png", ["bangs", ""], positive_points=[{"x": 1, "y": 2}]))
        self.check(workflows.matte("a.png"))
        self.check(workflows.depth("a.png"))
        self.check(workflows.upscale("a.png"))

    def test_guided_klein_has_a_negative_and_distilled_has_none(self):
        guided = workflows.klein("a.png", "x", 512, 512, base=True, cfg=4, negative="bangs")
        distilled = workflows.klein("a.png", "x", 512, 512)
        self.assertEqual(sum(n["class_type"] == "CLIPTextEncode" for n in guided.values()), 2)
        self.assertTrue(any(n["class_type"] == "ConditioningZeroOut" for n in distilled.values()))

    def test_sdxl_keeps_unmasked_pixels(self):
        nodes = workflows.sdxl_inpaint("a.png", "m.png", "1girl", "lowres")
        save = next(n for n in nodes.values() if n["class_type"] == "SaveImage")
        self.assertEqual(nodes[save["inputs"]["images"][0]]["class_type"], "ImageCompositeMasked")

    def test_unknown_union_type_is_refused(self):
        with self.assertRaises(ValueError):
            workflows.sdxl_inpaint("a.png", "m.png", "1girl", "lowres", union="repaint")


if __name__ == "__main__":
    unittest.main()
