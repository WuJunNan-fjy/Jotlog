# -*- coding: utf-8 -*-
"""生成 PWA 图标。

用法：python scripts/gen-icons.py
产物写在 public/ 下，被 manifest.webmanifest 引用。

maskable 版本把图案缩到安全区内（四周留 20% 余量），
否则 Android 会把图标裁成圆形时把边角切掉。
"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'public')
BG = (251, 250, 247, 255)      # paper
INK = (31, 79, 70, 255)        # accent

FONT_CANDIDATES = [
    'C:/Windows/Fonts/georgiab.ttf',
    'C:/Windows/Fonts/georgia.ttf',
    'C:/Windows/Fonts/timesbd.ttf',
    'C:/Windows/Fonts/times.ttf',
]


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def make(size: int, name: str, maskable: bool = False) -> None:
    img = Image.new('RGBA', (size, size), BG)
    draw = ImageDraw.Draw(img)

    pad = size * (0.22 if maskable else 0.15)
    radius = size * 0.2
    draw.rounded_rectangle(
        [pad, pad, size - pad, size - pad], radius=radius, fill=INK
    )

    font = load_font(int(size * 0.44))
    text = 'J'
    bbox = draw.textbbox((0, 0), text, font=font)
    w = bbox[2] - bbox[0]
    h = bbox[3] - bbox[1]
    # 衬线体的 J 视觉重心偏下，往上提一点更居中
    x = (size - w) / 2 - bbox[0]
    y = (size - h) / 2 - bbox[1] - size * 0.03
    draw.text((x, y), text, font=font, fill=(255, 255, 255, 255))

    path = os.path.join(OUT_DIR, name)
    img.save(path, 'PNG')
    print('生成', path)


if __name__ == '__main__':
    os.makedirs(OUT_DIR, exist_ok=True)
    make(192, 'icon-192.png')
    make(512, 'icon-512.png')
    make(512, 'icon-maskable-512.png', maskable=True)
