# -*- coding: utf-8 -*-
"""生成「跳过广告」应用图标。仅依赖 Pillow，不联网。"""
import os
from PIL import Image, ImageDraw, ImageFont

BASE = r"D:\AI\DSH\_skipad\app\src\main\res"
PREVIEW = r"D:\AI\DSH\_skipad\icon_preview.png"

C1 = (0x5A, 0x97, 0xFF)
C2 = (0x2C, 0x62, 0xEE)
BADGE = (0xFF, 0x5A, 0x3C)
WHITE = (255, 255, 255, 255)

DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0),
             ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]
K = 2


def _font(size):
    for name in ("arialbd.ttf", "segoeuib.ttf", "ariblk.ttf", "calibrib.ttf"):
        p = os.path.join(r"C:\Windows\Fonts", name)
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()


def _center_text(d, cx, cy, text, f, fill):
    try:
        d.text((cx, cy), text, font=f, fill=fill, anchor="mm")
    except Exception:
        bb = d.textbbox((0, 0), text, font=f)
        d.text((cx - (bb[2] - bb[0]) / 2.0 - bb[0],
                cy - (bb[3] - bb[1]) / 2.0 - bb[1]), text, font=f, fill=fill)


def _gradient(size, c1, c2):
    g = Image.new("RGB", (1, size))
    px = g.load()
    for y in range(size):
        t = y / max(1, size - 1)
        px[0, y] = (int(c1[0] + (c2[0] - c1[0]) * t),
                    int(c1[1] + (c2[1] - c1[1]) * t),
                    int(c1[2] + (c2[2] - c1[2]) * t))
    return g.resize((size, size), Image.BILINEAR)


def composition(k=K):
    """在 1024*k 画布上画「跳过符号 + AD 标签」，返回按内容紧贴裁剪的 RGBA。"""
    S = 1024 * k
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    # ---- AD 标签（下层） ----
    bs = 230 * k
    pad = bs // 2
    bl = Image.new("RGBA", (bs + pad * 2, bs + pad * 2), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bl)
    bd.rounded_rectangle([pad, pad, pad + bs, pad + bs],
                         radius=int(0.27 * bs), fill=BADGE + (255,))
    _center_text(bd, pad + bs / 2.0, pad + bs / 2.0 - bs * 0.03,
                 "AD", _font(int(0.50 * bs)), WHITE)
    w = int(0.085 * bs)
    r = w / 2.0
    x0, y0 = pad + bs * 0.20, pad + bs * 0.80
    x1, y1 = pad + bs * 0.80, pad + bs * 0.20
    bd.line([x0, y0, x1, y1], fill=WHITE, width=w)
    for (px_, py_) in ((x0, y0), (x1, y1)):
        bd.ellipse([px_ - r, py_ - r, px_ + r, py_ + r], fill=WHITE)
    rot = bl.rotate(-15, resample=Image.BICUBIC, expand=True)
    cx, cy = int(800 * k), int(745 * k)
    layer.alpha_composite(rot, (cx - rot.width // 2, cy - rot.height // 2))

    # ---- 跳过符号（上层） ----
    d = ImageDraw.Draw(layer)
    gx0, gy0, gx1, gy1 = 180 * k, 150 * k, 660 * k, 630 * k
    gw = gx1 - gx0
    d.polygon([(gx0, gy0), (gx0, gy1),
               (gx0 + int(0.62 * gw), (gy0 + gy1) // 2)], fill=WHITE)
    d.rounded_rectangle([gx0 + int(0.74 * gw), gy0, gx1, gy1],
                        radius=int(0.105 * gw), fill=WHITE)

    return layer.crop(layer.getbbox())


def legacy(size, comp):
    ss = size * 4
    canvas = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    mask = Image.new("L", (ss, ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, ss - 1, ss - 1], radius=int(0.22 * ss), fill=255)
    canvas.paste(_gradient(ss, C1, C2).convert("RGBA"), (0, 0), mask)
    fw = int(ss * 0.74)
    fh = max(1, int(fw * comp.height / comp.width))
    canvas.alpha_composite(comp.resize((fw, fh), Image.LANCZOS),
                           ((ss - fw) // 2, (ss - fh) // 2))
    return canvas.resize((size, size), Image.LANCZOS)


def foreground(size, comp):
    """自适应图标前景：内容限制在中央 66.7% 安全区内。"""
    ss = size * 4
    box = int(ss * 0.667)
    bh = max(1, int(box * comp.height / comp.width))
    if bh > box:
        bh = box
        box = max(1, int(bh * comp.width / comp.height))
    canvas = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    canvas.alpha_composite(comp.resize((box, bh), Image.LANCZOS),
                           ((ss - box) // 2, (ss - bh) // 2))
    return canvas.resize((size, size), Image.LANCZOS)


def main():
    comp = composition()
    for name, scale in DENSITIES:
        dl = os.path.join(BASE, "mipmap-" + name)
        os.makedirs(dl, exist_ok=True)
        legacy(int(48 * scale), comp).save(os.path.join(dl, "ic_launcher.png"))
        foreground(int(108 * scale), comp).save(
            os.path.join(dl, "ic_launcher_foreground.png"))

    sheet = Image.new("RGB", (1150, 620), (0xF2, 0xF4, 0xF8))
    big = legacy(512, comp)
    sheet.paste(big, (44, 44), big)
    x = 620
    for s in (192, 144, 96, 72, 48):
        im = legacy(s, comp)
        sheet.paste(im, (x, 44 + (192 - s) // 2), im)
        x += s + 26
    fg = foreground(260, comp)
    sheet.paste(fg, (630, 300), fg)
    sheet.save(PREVIEW)
    print("icons written to", BASE)


if __name__ == "__main__":
    main()
