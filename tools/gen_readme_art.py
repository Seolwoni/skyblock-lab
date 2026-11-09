"""
The README's pictures: the hero banner and the lookbook frames (docs/assets).

    python3 tools/gen_readme_art.py [screenshot folder]

The banner is drawn here; the lookbook frames are cut from in-game screenshots (the gametest shots on ci-log).
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os, sys

ROOT = os.path.join(os.path.dirname(__file__), '..')
OUT = os.path.join(ROOT, 'docs', 'assets')
FONT_B = os.path.join(ROOT, 'src/main/resources/assets/marceline/font/cormorant-bold.ttf')
FONT_S = os.path.join(ROOT, 'src/main/resources/assets/marceline/font/cormorant-semibold.ttf')
EMBLEM = os.path.join(ROOT, 'src/main/resources/assets/marceline/textures/gui/logo.png')
GOLD = (201, 164, 106)
IVORY = (240, 228, 214)
os.makedirs(OUT, exist_ok=True)


def spaced(draw, text, font, cx, y, spacing, fill):
    widths = [font.getlength(c) for c in text]
    total = sum(widths) + spacing * (len(text) - 1)
    x = cx - total / 2
    for c, w in zip(text, widths):
        if c != ' ':
            draw.text((x, y), c, font=font, fill=fill)
        x += w + spacing
    return total


def hairline(img, cx, y, half, alpha=200, thick=2):
    d = ImageDraw.Draw(img)
    for i in range(int(half * 2)):
        t = i / (half * 2)
        a = (1 - abs(t * 2 - 1)) ** 2
        d.line([(cx - half + i, y), (cx - half + i, y + thick - 1)], fill=GOLD + (int(alpha * a),))


def banner():
    S = 2  # drawn at 2x, then halved
    W, H = 1600 * S, 600 * S
    img = Image.new('RGBA', (W, H))
    px = ImageDraw.Draw(img)
    top, bottom = (30, 10, 16), (12, 6, 9)
    for y in range(H):
        t = y / H
        px.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    # a low wine glow behind the emblem, and a vignette
    glow = Image.new('L', (W, H), 0)
    ImageDraw.Draw(glow).ellipse([W / 2 - 520 * S, 20 * S, W / 2 + 520 * S, 470 * S], fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(120 * S))
    img = Image.composite(Image.new('RGBA', (W, H), (110, 22, 40, 255)), img, glow)
    vig = Image.new('L', (W, H), 0)
    ImageDraw.Draw(vig).rectangle([0, 0, W, H], fill=150)
    ImageDraw.Draw(vig).ellipse([-200 * S, -260 * S, W + 200 * S, H + 260 * S], fill=0)
    vig = vig.filter(ImageFilter.GaussianBlur(90 * S))
    img = Image.composite(Image.new('RGBA', (W, H), (4, 2, 3, 255)), img, vig)
    d = ImageDraw.Draw(img)
    cx = W / 2
    # emblem
    e = Image.open(EMBLEM).convert('RGBA').resize((190 * S, 190 * S), Image.LANCZOS)
    img.alpha_composite(e, (int(cx - e.width / 2), 70 * S))
    # wordmark
    f = ImageFont.truetype(FONT_B, 96 * S)
    spaced(d, 'MARCELINE', f, cx, 285 * S, 30 * S, IVORY + (255,))
    hairline(img, cx, 412 * S, 330 * S, 210, 2 * S)
    fs = ImageFont.truetype(FONT_S, 26 * S)
    spaced(d, 'SLOW DANCE WITH YOU', fs, cx, 432 * S, 14 * S, GOLD + (235,))
    ft = ImageFont.truetype(FONT_S, 17 * S)
    spaced(d, 'MAISON MARCELINE  ·  ATELIER PRIVÉ  ·  MMXXVI', ft, cx, 530 * S, 9 * S, (170, 140, 128, 210))
    # thin gold frame
    m = 22 * S
    d.rectangle([m, m, W - m, H - m], outline=GOLD + (70,), width=S)
    img.resize((W // S, H // S), Image.LANCZOS).convert('RGB').save(os.path.join(OUT, 'banner.png'), optimize=True)


def lookbook(shots):
    frames = [('0011_rw-08-loading.png', 'lookbook-1.png'), ('0007_rw-07-title.png', 'lookbook-2.png'),
              ('0006_rw-06-esc-menu.png', 'lookbook-3.png'), ('0001_rw-01-skyhanni.png', 'lookbook-4.png')]
    for src, name in frames:
        p = os.path.join(shots, src)
        if not os.path.exists(p):
            continue
        im = Image.open(p).convert('RGB').resize((800, 450), Image.LANCZOS)
        # a hairline gold mat around each frame
        mat = Image.new('RGB', (816, 466), (24, 10, 14))
        mat.paste(im, (8, 8))
        ImageDraw.Draw(mat).rectangle([3, 3, 812, 462], outline=(120, 98, 66), width=1)
        mat.save(os.path.join(OUT, name), optimize=True)


banner()
if len(sys.argv) > 1:
    lookbook(sys.argv[1])
print('written to', os.path.normpath(OUT))
