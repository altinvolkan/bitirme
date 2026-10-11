"""iMirror mağaza görselleri. Çalıştır: python make_assets.py (Pillow gerekir)."""
from PIL import Image, ImageDraw, ImageFont
import os

BG = (14, 16, 20)
ACCENT = (124, 245, 200)
LINE2 = (58, 64, 76)
SS = 4  # süper örnekleme


import math

SC = 0.8  # tasarım kutusunda güvenli bölgeye sığdırmak için ölçek


def arc_pts(cx, cy, r, a0, a1, n=24):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def glyph(d, ox, oy, k, accent=ACCENT, bg=BG):
    """108'lik kutuda: büyük ekran + sol altta telefon (ekranın sol alt köşesini kapatır) + ekranda yayın üçgeni."""
    def P(x, y):
        x = 54 + (x - 54) * SC
        y = 54 + (y - 54) * SC
        return ox + x * k, oy + y * k

    def stroke(pts, w):
        pp = [P(*p) for p in pts]
        wk = max(1, int(w * SC * k))
        d.line(pp, fill=accent, width=wk, joint="curve")
        for p in (pp[0], pp[-1]):
            d.ellipse([p[0] - wk / 2, p[1] - wk / 2, p[0] + wk / 2, p[1] + wk / 2], fill=accent)

    # ekran: alt kenar 43'ten başlar, sol kenar 47'de biter (telefona yer açılır)
    r = 7
    pts = [(43, 63), (74, 63)]
    pts += arc_pts(74, 56, r, 90, 0)[1:]
    pts += [(81, 34)]
    pts += arc_pts(74, 34, r, 0, -90)[1:]
    pts += [(34, 27)]
    pts += arc_pts(34, 34, r, -90, -180)[1:]
    pts += [(27, 47)]
    stroke(pts, 5)
    # telefon
    r = 5
    ph = [(25.5, 52)]
    ph += [(33.5, 52)]
    ph += arc_pts(33.5, 57, r, -90, 0)[1:]
    ph += [(38.5, 79)]
    ph += arc_pts(33.5, 79, r, 0, 90)[1:]
    ph += [(25.5, 84)]
    ph += arc_pts(25.5, 79, r, 90, 180)[1:]
    ph += [(20.5, 57)]
    ph += arc_pts(25.5, 57, r, 180, 270)[1:]
    stroke(ph, 4)
    stroke([(27.5, 57.5), (31.5, 57.5)], 2.2)
    # yayın üçgeni
    tri = [P(54, 35), P(46, 51), P(62, 51)]
    d.polygon(tri, fill=accent)
    wk = int(3 * SC * k)
    d.line(tri + [tri[0]], fill=accent, width=wk, joint="curve")
    for p in tri:
        d.ellipse([p[0] - wk / 2, p[1] - wk / 2, p[0] + wk / 2, p[1] + wk / 2], fill=accent)


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
