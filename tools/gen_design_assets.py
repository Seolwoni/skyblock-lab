"""
Small Marceline design assets:

- textures/gui/grain.png       film grain tile (128 px, light and dark specks at low alpha) for cards and the menu
- textures/gui/notify/<k>.png  notification icons as white masks (drawn at 64, saved at 32 px), tinted when drawn
- sounds/pluck.ogg             the menu click: a short low guitar string pluck (Karplus-Strong)

    python3 tools/gen_design_assets.py
"""
import json, math, os, subprocess, tempfile, wave
import numpy as np
from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'marceline')
TEX = os.path.join(RES, 'textures', 'gui')
rng = np.random.default_rng(1337)


def grain():
    n = 128
    g = rng.normal(0.0, 1.0, (n, n))
    # a touch of blur so the specks are soft, then keep the tile seamless by wrapping
    g = (g + 0.35 * (np.roll(g, 1, 0) + np.roll(g, -1, 0) + np.roll(g, 1, 1) + np.roll(g, -1, 1))) / 2.4
    a = np.clip(np.abs(g) * 46, 0, 120).astype(np.uint8)
    light = (g > 0)
    rgb = np.where(light[..., None], np.array([255, 240, 226]), np.array([0, 0, 0])).astype(np.uint8)
    img = np.dstack([rgb, a])
    Image.fromarray(img, 'RGBA').save(os.path.join(TEX, 'grain.png'))


S = 64
SS = 8
OUT = 32


def mask():
    im = Image.new('L', (S * SS, S * SS), 0)
    return im, ImageDraw.Draw(im)


def p(*xy):
    return [v * SS for v in xy]


def save(im, name):
    d = os.path.join(TEX, 'notify')
    os.makedirs(d, exist_ok=True)
    a = im.resize((OUT, OUT), Image.LANCZOS)
    out = Image.new('RGBA', (OUT, OUT), (255, 255, 255, 0))
    out.putalpha(a)
    out.save(os.path.join(d, name + '.png'))
    with open(os.path.join(d, name + '.png.mcmeta'), 'w') as f:
        json.dump({"texture": {"blur": True}}, f)


def bat():
    im, d = mask()
    # body, ears and two scalloped wings
    d.ellipse(p(26, 22, 38, 42), fill=255)
    d.polygon(p(27, 25, 28, 15, 31, 22), fill=255)
    d.polygon(p(37, 25, 36, 15, 33, 22), fill=255)
    for side in (-1, 1):
        cx = 32
        pts = [(cx + side * 5, 26), (cx + side * 14, 17), (cx + side * 24, 15), (cx + side * 30, 22),
               (cx + side * 27, 30), (cx + side * 22, 28), (cx + side * 18, 34), (cx + side * 13, 31), (cx + side * 6, 38)]
        d.polygon([v * SS for xy in pts for v in xy], fill=255)
    save(im, 'bat')


def warn():
    im, d = mask()
    d.rounded_rectangle(p(6, 8, 58, 56), radius=0, fill=0)
    d.polygon(p(32, 7, 59, 55, 5, 55), fill=255)
    d.polygon(p(32, 15, 52, 50, 12, 50), fill=0)
    d.rounded_rectangle(p(29.5, 24, 34.5, 39), radius=2.5 * SS, fill=255)
    d.ellipse(p(29.5, 42, 34.5, 47), fill=255)
    save(im, 'warn')


def coin():
    im, d = mask()
    d.ellipse(p(7, 7, 57, 57), fill=255)
    d.ellipse(p(12, 12, 52, 52), fill=0)
    d.ellipse(p(16, 16, 48, 48), fill=255)
    # a little bat-wing "M" cut out of the face
    d.polygon(p(21, 40, 24, 24, 32, 33, 40, 24, 43, 40, 38, 40, 37, 32, 32, 39, 27, 32, 26, 40), fill=0)
    save(im, 'coin')


def check():
    im, d = mask()
    d.ellipse(p(7, 7, 57, 57), fill=255)
    d.ellipse(p(11.5, 11.5, 52.5, 52.5), fill=0)
    d.line(p(20, 33, 28.5, 41.5, 44, 24), fill=255, width=6 * SS, joint='curve')
    save(im, 'check')


def star():
    im, d = mask()
    pts = []
    for k in range(10):
        r = 27 if k % 2 == 0 else 11.5
        t = -math.pi / 2 + k * math.pi / 5
        pts += [32 + r * math.cos(t), 33.5 + r * math.sin(t)]
    d.polygon(p(*pts), fill=255)
    save(im, 'star')


def fish():
    im, d = mask()
    d.ellipse(p(9, 19, 47, 45), fill=255)
    d.polygon(p(42, 32, 58, 18, 54, 32, 58, 46), fill=255)
    d.ellipse(p(17, 27, 23, 33), fill=0)
    d.line(p(31, 22, 28, 32, 31, 42), fill=0, width=int(2.4 * SS))
    save(im, 'fish')


def pluck():
    sr = 44100
    f0 = 146.83          # D3: low but still a click, not a boom
    dur = 0.42
    n = int(sr * dur)
    period = int(round(sr / f0))
    buf = rng.uniform(-1, 1, period)
    # pick position: comb the burst so the tone is rounder, like a thumb near the neck
    buf = buf - np.roll(buf, period // 5)
    out = np.zeros(n)
    decay = 0.996
    for i in range(n):
        j = i % period
        out[i] = buf[j]
        buf[j] = decay * 0.5 * (buf[j] + buf[(j + 1) % period])
    t = np.arange(n) / sr
    out *= np.exp(-t * 7.5)
    # soft body thump under the string and a short fade in so it does not tick
    out += 0.35 * np.sin(2 * math.pi * 98 * t) * np.exp(-t * 28)
    out *= np.minimum(1, t / 0.002)
    out[-int(sr * 0.04):] *= np.linspace(1, 0, int(sr * 0.04))
    out = out / np.max(np.abs(out)) * 0.8
    pcm = (out * 32767).astype(np.int16)
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, 'p.wav')
        with wave.open(wav, 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm.tobytes())
        subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '5',
                        os.path.join(RES, 'sounds', 'pluck.ogg')], check=True)


if __name__ == '__main__':
    grain()
    for f in (bat, warn, coin, check, star, fish):
        f()
    pluck()
