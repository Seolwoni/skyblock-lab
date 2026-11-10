"""
Marceline inventories: the game's container backgrounds (chest, inventory, crafting, furnace...) with the same
pictures and layout, only the greys warmed - parchment panels, taupe slots, cream light and a deep wine outline.
Coloured pixels (flames, arrows, icons) are kept as they are.

The vanilla pictures come from a [probe] build (ci-log branch, probe/assets/.../textures/gui/container):

    python3 tools/gen_container_look.py <folder with the vanilla container/*.png>
"""
import os, sys
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'minecraft', 'textures', 'gui', 'container')

# grey level -> warm colour, between these the colours blend
STOPS = [
    (0, (34, 13, 19)),       # outline: deep wine
    (55, (66, 40, 42)),      # slot shade
    (85, (96, 70, 68)),
    (139, (142, 124, 116)),  # slot fill: taupe
    (198, (204, 189, 172)),  # panel: parchment
    (255, (252, 243, 230)),  # light edge: cream
]


def warm(v):
    for (a, ca), (b, cb) in zip(STOPS, STOPS[1:]):
        if a <= v <= b:
            t = (v - a) / (b - a)
            return tuple(round(x + (y - x) * t) for x, y in zip(ca, cb))
    return STOPS[-1][1]


LUT = [warm(v) for v in range(256)]


def convert(src, dst):
    im = Image.open(src).convert('RGBA')
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a and r == g == b:
                px[x, y] = LUT[r] + (a,)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    im.save(dst)


if __name__ == '__main__':
    root = sys.argv[1]
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith('.png'):
                rel = os.path.relpath(os.path.join(dirpath, f), root)
                convert(os.path.join(dirpath, f), os.path.join(OUT, rel))
                print(rel)
