"""Draws the Mush home-screen icon (a pink brain) into public/. Run: python3 tools/make-icons.py"""
import pathlib
from PIL import Image, ImageDraw

OUT = pathlib.Path(__file__).resolve().parent.parent / "public"
LOBES = [(72, 92, 40), (112, 66, 44), (158, 74, 40), (186, 108, 32), (56, 128, 32),
         (120, 124, 50), (172, 138, 32), (90, 150, 30), (150, 152, 30)]
S = 4  # supersample

def draw(size):
    big = size * S
    img = Image.new("RGB", (big, big), "#17141f")
    d = ImageDraw.Draw(img)
    k = big / 300  # brain art lives in a 240x215 box; centre it in 300x300
    ox, oy = 30, 50
    P = lambda x, y: ((x + ox) * k, (y + oy) * k)
    def circ(x, y, r, fill):
        (cx, cy) = P(x, y)
        d.ellipse([cx - r * k, cy - r * k, cx + r * k, cy + r * k], fill=fill)
    for x, y, r in LOBES: circ(x, y, r + 4, "#a35b70")
    for x, y, r in LOBES: circ(x, y, r, "#ff93b6")
    w = int(4 * k)
    for pts in [[(118, 30), (112, 52), (124, 66), (116, 84), (122, 104), (118, 124)],
                [(70, 70), (80, 82), (90, 96)], [(150, 46), (156, 60), (166, 76)],
                [(182, 92), (178, 106), (176, 120)], [(72, 160), (86, 152), (100, 150)],
                [(160, 160), (172, 152), (186, 146)]]:
        d.line([P(*p) for p in pts], fill="#d97a96", width=w, joint="curve")
    for x in (96, 144):
        circ(x, 120, 12, "#ffffff"); circ(x + 1, 122, 6.5, "#231d2b"); circ(x + 3, 119, 2.2, "#ffffff")
    d.arc([*P(104, 136), *P(136, 160)], 20, 160, fill="#231d2b", width=w)
    img.resize((size, size), Image.LANCZOS).save(OUT / f"icon-{size}.png")

for s in (180, 512):
    draw(s)
print("icons written to", OUT)

# Android launcher icons
RES = OUT.parent / "android" / "app" / "src" / "main" / "res"
for folder, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    d = RES / f"mipmap-{folder}"
    d.mkdir(parents=True, exist_ok=True)
    draw(size)
    (OUT / f"icon-{size}.png").rename(d / "ic_launcher.png")
print("android icons written to", RES)
