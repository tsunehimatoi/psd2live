"""Takes the background off an illustration with BiRefNet (ComfyUI BiRefNetMatte): an RGBA source for the planner.

usage: python matte_art.py IN.png OUT.png
"""
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "comfy"))
from comfy import Comfy
from workflows import Graph
from env import client

src, dst = Path(sys.argv[1]), Path(sys.argv[2])
c = client(timeout=900)
name = c.upload(src, f"live_{src.name}")
g = Graph(prefix="matte")
g.save(g("BiRefNetMatte", image=g.load(name)), dst.stem)
files = c.run(g.nodes, dst.parent / "tmp_matte", dst.stem)
shutil.move(str(files[0]), dst)
print(dst)
