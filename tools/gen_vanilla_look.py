"""
Marceline look for the game's own widgets and menu backgrounds (resource overrides in the minecraft namespace).

Same sprite names and logical sizes as vanilla, so every screen keeps its layout; only shape and colour change:
dark glass with a warm hairline rim and rounded corners, gold for focus and selection, wine for hover.
Pictures are drawn 4x (supersampled 4x again) and scaled by the game through the nine-slice data.

    python3 tools/gen_vanilla_look.py
"""
from PIL import Image, ImageDraw
import json, os

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'minecraft', 'textures', 'gui')
SPR = os.path.join(ROOT, 'sprites')
K = 4    # texture pixels per GUI pixel
SS = 4   # supersampling while drawing

RIM = (255, 236, 220)
ROSE = (232, 80, 106)
WINE = (142, 34, 54)
GOLD = (201, 164, 106)
IVORY = (243, 234, 222)


def canvas(w, h):
    return Image.new('RGBA', (w * K * SS, h * K * SS), (0, 0, 0, 0))


def finish(im, w, h):
    return im.resize((w * K, h * K), Image.LANCZOS)


def u(v):  # GUI units -> drawing pixels
    return v * K * SS


def rrect(im, box, r, fill):
    layer = Image.new('RGBA', im.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle([u(box[0]), u(box[1]), u(box[2]) - 1, u(box[3]) - 1], radius=u(r), fill=fill)
    im.alpha_composite(layer)


def hline(im, x0, x1, y, t, color):
    layer = Image.new('RGBA', im.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rectangle([u(x0), u(y), u(x1) - 1, u(y + t) - 1], fill=color)
    im.alpha_composite(layer)


def save(rel, im, w, h, border=None, extra=None):
    path = os.path.join(SPR if not rel.startswith('/') else ROOT, rel.lstrip('/') + '.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    finish(im, w, h).save(path)
    if border is not None:
        scaling = {'type': 'nine_slice', 'width': w, 'height': h, 'border': border}
        if extra: scaling.update(extra)
        with open(path + '.mcmeta', 'w') as f:
            json.dump({'gui': {'scaling': scaling}}, f)


def glass(w, h, r, rim_a, rim_c, glass_c, under=None, wash=None):
    im = canvas(w, h)
    rrect(im, (0, 0, w, h), r, rim_c + (rim_a,))
    rrect(im, (0.5, 0.5, w - 0.5, h - 0.5), r - 0.5, glass_c)
    if wash: rrect(im, (0.5, 0.5, w - 0.5, h - 0.5), r - 0.5, wash)
    if under: hline(im, r + 2, w - r - 2, h - 2.5, 0.5, under)
    return im


def buttons():
    w, h, r = 200, 20, 3.5
    save('widget/button', glass(w, h, r, 62, RIM, (16, 11, 13, 178)), w, h, 4)
    save('widget/button_highlighted', glass(w, h, r, 150, ROSE, (22, 12, 15, 190), under=GOLD + (220,), wash=WINE + (32,)), w, h, 4)
    save('widget/button_disabled', glass(w, h, r, 26, RIM, (14, 10, 12, 110)), w, h, 4)
    # slider track and handle
    save('widget/slider', glass(w, h, r, 44, RIM, (11, 8, 9, 190)), w, h, 4)
    save('widget/slider_highlighted', glass(w, h, r, 96, RIM, (14, 9, 11, 196)), w, h, 4)
    for name, top, bottom, rim in (('slider_handle', (236, 222, 204), GOLD, (90, 70, 50, 255)),
                                   ('slider_handle_highlighted', (250, 238, 222), (222, 186, 128), ROSE + (255,))):
        im = canvas(8, 20)
        rrect(im, (0, 0, 8, 20), 2.5, rim)
        grad = Image.new('RGBA', im.size)
        gd = ImageDraw.Draw(grad)
        for y in range(im.size[1]):
            t = y / (im.size[1] - 1)
            gd.line([(0, y), (im.size[0], y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
        mask = Image.new('L', im.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle([u(0.75), u(0.75), u(7.25) - 1, u(19.25) - 1], radius=u(2), fill=255)
        im.paste(grad, (0, 0), mask)
        save('widget/' + name, im, 8, 20, {'left': 3, 'top': 3, 'right': 3, 'bottom': 3})
    # text fields
    save('widget/text_field', glass(w, h, 3, 58, RIM, (10, 8, 9, 232)), w, h, 3)
    save('widget/text_field_highlighted', glass(w, h, 3, 210, GOLD, (12, 9, 10, 236)), w, h, 3)
    # scroll bar
    im = canvas(6, 32); rrect(im, (1, 0, 5, 32), 2, GOLD + (225,)); save('widget/scroller', im, 6, 32, {'left': 2, 'top': 3, 'right': 2, 'bottom': 3})
    im = canvas(6, 32); rrect(im, (1, 0, 5, 32), 2, (12, 8, 10, 170)); save('widget/scroller_background', im, 6, 32, {'left': 2, 'top': 3, 'right': 2, 'bottom': 3})
    # tabs (options screens, world creation)
    tw, th = 130, 24
    for name, rim_a, rim_c, glass_c, under in (
            ('tab', 40, RIM, (14, 10, 12, 150), None),
            ('tab_highlighted', 110, ROSE, (22, 12, 15, 175), None),
            ('tab_selected', 80, RIM, (24, 15, 18, 200), GOLD + (230,)),
            ('tab_selected_highlighted', 120, ROSE, (28, 16, 20, 205), GOLD + (240,))):
        im = canvas(tw, th)
        rrect(im, (0, 0, tw, th + 4), 3, rim_c + (rim_a,))
        rrect(im, (0.5, 0.5, tw - 0.5, th + 4), 2.5, glass_c)
        if under: hline(im, 4, tw - 4, th - 1.5, 0.75, under)
        save('widget/' + name, im, tw, th, {'left': 3, 'top': 3, 'right': 3, 'bottom': 2})
    # check boxes
    for name, rim_a, rim_c, check in (('checkbox', 70, RIM, False), ('checkbox_highlighted', 160, ROSE, False),
                                      ('checkbox_selected', 90, GOLD, True), ('checkbox_selected_highlighted', 170, ROSE, True)):
        im = glass(20, 20, 3.5, rim_a, rim_c, (14, 10, 12, 200))
        if check:
            layer = Image.new('RGBA', im.size, (0, 0, 0, 0))
            ImageDraw.Draw(layer).line([(u(5.5), u(10.5)), (u(8.8), u(13.8)), (u(14.8), u(6.5))], fill=GOLD + (255,), width=int(u(1.7)), joint='curve')
            im.alpha_composite(layer)
        save('widget/' + name, im, 20, 20)


def tooltip():
    n = 100
    im = canvas(n, n)
    rrect(im, (8, 8, 92, 92), 3, (18, 9, 12, 244))
    save('tooltip/background', im, n, n, 9)
    im = canvas(n, n)
    # gold at the top fading into wine at the bottom, 3/4 of a GUI pixel wide
    outline = Image.new('RGBA', im.size, (0, 0, 0, 0))
    ImageDraw.Draw(outline).rounded_rectangle([u(9), u(9), u(91) - 1, u(91) - 1], radius=u(2.5), outline=(255, 255, 255, 255), width=int(u(0.75)))
    grad = Image.new('RGBA', im.size)
    gd = ImageDraw.Draw(grad)
    for y in range(im.size[1]):
        t = min(1, max(0, (y - u(9)) / u(82)))
        c = tuple(int(GOLD[i] + (WINE[i] - GOLD[i]) * t) for i in range(3))
        gd.line([(0, y), (im.size[0], y)], fill=c + (int(190 - 40 * t),))
    im.paste(grad, (0, 0), outline.split()[3])
    save('tooltip/frame', im, n, n, 10, {'stretch_inner': True})


def backgrounds():
    def tile(name, rgba):
        Image.new('RGBA', (16, 16), rgba).save(os.path.join(ROOT, name + '.png'))
    tile('menu_background', (34, 8, 14, 96))
    tile('inworld_menu_background', (34, 8, 14, 96))
    tile('menu_list_background', (20, 6, 10, 132))
    tile('inworld_menu_list_background', (20, 6, 10, 132))

    def sep(name, rows):
        im = Image.new('RGBA', (32, 2))
        for y, c in enumerate(rows):
            for x in range(32): im.putpixel((x, y), c)
        im.save(os.path.join(ROOT, name + '.png'))
    gold = GOLD + (80,)
    dark = (16, 5, 9, 191)
    for prefix in ('', 'inworld_'):
        sep(prefix + 'header_separator', [gold, dark])
        sep(prefix + 'footer_separator', [dark, gold])


buttons()
tooltip()
backgrounds()
print('written to', os.path.normpath(ROOT))
