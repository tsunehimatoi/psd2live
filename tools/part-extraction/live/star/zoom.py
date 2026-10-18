"""Gridded zoom of star_lan: python zoom.py NAME x0 y0 x1 y1 SCALE [STEP]"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont
name, x0, y0, x1, y1, s = sys.argv[1], *map(int, sys.argv[2:7])
step = int(sys.argv[7]) if len(sys.argv) > 7 else 10
im = Image.open(os.environ.get('L2D_LIVE', '.') + '/art/star_lan.png').convert('RGBA')
bg = Image.new('RGBA', im.size, (255, 255, 255, 255)); bg.alpha_composite(im); im = bg.convert('RGB')
f = ImageFont.truetype('arial.ttf', 12)
c = im.crop((x0, y0, x1, y1)).resize(((x1 - x0) * s, (y1 - y0) * s), Image.LANCZOS)
d = ImageDraw.Draw(c)
for x in range((x0 // step + 1) * step, x1, step):
    X = (x - x0) * s; big = x % 50 == 0
    d.line([(X, 0), (X, c.height)], fill=(255, 0, 0) if big else (0, 170, 255), width=1)
    if big: d.text((X + 2, 2), str(x), fill=(255, 0, 0), font=f)
for y in range((y0 // step + 1) * step, y1, step):
    Y = (y - y0) * s; big = y % 50 == 0
    d.line([(0, Y), (c.width, Y)], fill=(255, 0, 0) if big else (0, 170, 255), width=1)
    if big: d.text((2, Y + 2), str(y), fill=(255, 0, 0), font=f)
c.save(os.environ.get('L2D_LIVE', '.') + f'/star/z_{name}.png')
