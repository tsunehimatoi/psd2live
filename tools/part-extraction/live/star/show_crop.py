"""Plan regions of a crop drawn numbered over it. python show_crop.py PLAN_NAME [TAGS,...] [--whole]"""
import json
import os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
L = os.environ.get('L2D_LIVE', '.') + '/'
name = sys.argv[1]
tags = sys.argv[2].split(',') if len(sys.argv) > 2 and not sys.argv[2].startswith('--') else None
whole = '--whole' in sys.argv
crop = name.replace('_fine', '')
art = Image.open(f'{L}art/{crop}.png').convert('RGBA')
bg = Image.new('RGBA', art.size, (255, 255, 255, 255)); bg.alpha_composite(art)
plan = json.load(open(f'{L}plan/{name}/plan.json'))
rows = [r for r in plan if not tags or r['tag'] in tags]
over = np.asarray(bg.convert('RGB')).astype(np.float32) * 0.5 + 120
rng = np.random.RandomState(5)
labels = []
for r in rows:
    m = np.asarray(Image.open(f"{L}plan/{name}/{r['whole'] if whole and r.get('whole') else r['region']}")) > 127
    if not m.any(): continue
    c = rng.randint(20, 235, 3)
    over[m] = over[m] * 0.3 + c * 0.7
    ys, xs = np.nonzero(m)
    k = np.argmin((ys - ys.mean()) ** 2 + (xs - xs.mean()) ** 2)
    labels.append((xs[k], ys[k], r['name'].split('_')[0] + ('*' if r.get('covered') else ''), tuple(int(v) // 3 for v in c)))
im = Image.fromarray(over.clip(0, 255).astype(np.uint8))
d = ImageDraw.Draw(im); f = ImageFont.truetype('arial.ttf', 15)
for x, y, t, c in labels:
    d.rectangle((x - 2, y - 9, x + 9 * len(t), y + 9), fill=(255, 255, 255)); d.text((x, y - 8), t, fill=c, font=f)
out = f"{L}star/p_{name}{'_' + '-'.join(tags) if tags else ''}{'_whole' if whole else ''}.png"
im.save(out); print(out, len(rows))
