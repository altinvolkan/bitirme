"""iMirror mağaza görselleri. Çalıştır: python make_assets.py (Pillow gerekir)."""
from PIL import Image, ImageDraw, ImageFont
import os

BG = (14, 16, 20)
ACCENT = (124, 245, 200)
LINE2 = (58, 64, 76)
SS = 4  # süper örnekleme


def glyph(d, ox, oy, k, accent=ACCENT, bg=BG):
    """108'lik tasarım kutusundaki logo; (ox,oy) sol üst, k ölçek."""
    def P(x, y):
        return ox + x * k, oy + y * k

    def mix(a, t):  # accent'i bg ile karıştır (alfa yerine)
        return tuple(int(bg[i] + (a[i] - bg[i]) * t) for i in range(3))

    def circle(cx, cy, r, col):
        x0, y0 = P(cx - r, cy - r)
        x1, y1 = P(cx + r, cy + r)
        d.ellipse([x0, y0, x1, y1], fill=col)

    def rrect(x0, y0, x1, y1, r, col, corners):
        a = P(x0, y0)
        b = P(x1, y1)
        d.rounded_rectangle([a[0], a[1], b[0], b[1]], radius=r * k, fill=col, corners=corners)

    circle(54, 25.5, 5.5, accent)
    rrect(48.5, 40, 59.5, 52.2, 5.5, accent, (True, True, False, False))
    rrect(31, 53.2, 77, 54.8, 0.8, mix(accent, 0.55), (True, True, True, True))
    low = mix(accent, 0.42)
    rrect(48.5, 55.8, 59.5, 68, 5.5, low, (False, False, True, True))
    circle(54, 82.5, 5.5, low)


def icon(size, glyph_scale):
    im = Image.new("RGB", (size * SS, size * SS), BG)
    d = ImageDraw.Draw(im)
    k = size * SS / 108 * glyph_scale
    ox = size * SS / 2 - 54 * k
    oy = size * SS / 2 - 54 * k
    glyph(d, ox, oy, k)
    return im.resize((size, size), Image.LANCZOS)


def font(size, bold=True):
    for f in ("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(f):
            return ImageFont.truetype(f, size)
    return ImageFont.load_default()


# 1) Play Store uygulama simgesi (512×512, tam kare; Play köşeleri kendisi yuvarlar)
icon(512, 1.3).save("icon-512.png")

# 2) Öne çıkan grafik (1024×500)
W, H = 1024, 500
im = Image.new("RGB", (W * SS, H * SS), BG)
d = ImageDraw.Draw(im)
tile = 300 * SS
tx, ty = 130 * SS, (H * SS - tile) // 2
d.rounded_rectangle([tx, ty, tx + tile, ty + tile], radius=66 * SS, fill=(23, 26, 32), outline=LINE2, width=3 * SS)
k = tile / 108 * 1.25
glyph(d, tx + tile / 2 - 54 * k, ty + tile / 2 - 54 * k, k, bg=(23, 26, 32))
f = font(150 * SS)
d.text((tx + tile + 60 * SS, H * SS // 2 - 6 * SS), "iMirror", font=f, fill=(243, 245, 248), anchor="lm")
# adın altına ince bir ayna çizgisi + mint nokta
y = H * SS // 2 + 105 * SS
d.rounded_rectangle([tx + tile + 66 * SS, y, tx + tile + 66 * SS + 360 * SS, y + 4 * SS], radius=2 * SS, fill=LINE2)
d.ellipse([tx + tile + 66 * SS, y - 8 * SS, tx + tile + 66 * SS + 20 * SS, y + 12 * SS], fill=ACCENT)
im.resize((W, H), Image.LANCZOS).save("feature-graphic-1024x500.png")

# 3) Önizleme (uygulama simgesinin yuvarlak/kare maskeli hali)
pv = Image.new("RGB", (760, 280), (32, 34, 40))
for i, (shape, s) in enumerate((("circle", 200), ("squircle", 200))):
    ic = icon(s, 1.0).convert("RGBA")
    mask = Image.new("L", (s, s), 0)
    md = ImageDraw.Draw(mask)
    if shape == "circle":
        md.ellipse([0, 0, s - 1, s - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.28), fill=255)
    pv.paste(ic, (50 + i * 260, 40), mask)
sm = icon(72, 1.0)
m72 = Image.new("L", (72, 72), 0)
ImageDraw.Draw(m72).rounded_rectangle([0, 0, 71, 71], radius=20, fill=255)
pv.paste(sm, (600, 100), m72)
pv.save("icon-preview.png")
print("ok")
