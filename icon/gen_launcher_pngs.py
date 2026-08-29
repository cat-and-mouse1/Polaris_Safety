#!/usr/bin/env python3
"""Generate Polaris legacy launcher icons (mdpi..xxxhdpi) from the confirmed
four-pointed star bezier geometry. Renders at 4x supersample, downscales with LANCZOS.
"""
import os
from PIL import Image, ImageDraw

BASE = r"C:\Users\Administrator\WorkBuddy\2026-08-26-15-10-56\Polaris\app\src\main\res"
BG = (207, 229, 255, 255)      # #CFE5FF cool launcher background
STROKE = (17, 17, 17, 255)     # #111111 brand star

# Confirmed geometry: 4 edges, each (P0, C1, C2, P3) in 200-space
EDGES = [
    ((100, 12), (100, 76), (124, 100), (188, 100)),
    ((188, 100), (126, 100), (100, 124), (100, 188)),
    ((100, 188), (100, 124), (76, 100), (12, 100)),
    ((12, 100), (74, 100), (100, 76), (100, 12)),
]

def cubic(p0, c1, c2, p3, t):
    mt = 1 - t
    a, b, c, d = mt**3, 3 * mt**2 * t, 3 * mt * t**2, t**3
    return (
        a * p0[0] + b * c1[0] + c * c2[0] + d * p3[0],
        a * p0[1] + b * c1[1] + c * c2[1] + d * p3[1],
    )

def star_points(samples=48):
    pts = []
    for p0, c1, c2, p3 in EDGES:
        for i in range(samples):
            t = i / samples
            pts.append(cubic(p0, c1, c2, p3, t))
    return pts

def render(size, supersample=4):
    big = size * supersample
    img = Image.new("RGBA", (big, big), BG)
    d = ImageDraw.Draw(img)
    # star spans 176 units in 200-space; occupy 66% of the canvas
    span = 176
    scale = big * 0.66 / span
    off = (big - span * scale) / 2
    pts = [(x * scale + off, y * scale + off) for x, y in star_points()]
    stroke = max(1, int(round(9 * scale / supersample))) * supersample
    d.line(pts, fill=STROKE, width=stroke, joint="curve")
    return img.resize((size, size), Image.LANCZOS)

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

for folder, size in DENSITIES.items():
    out_dir = os.path.join(BASE, folder)
    os.makedirs(out_dir, exist_ok=True)
    img = render(size)
    for name in ("ic_launcher", "ic_launcher_round"):
        img.save(os.path.join(out_dir, name + ".png"))
        print(f"{folder}/{name}.png  {size}x{size}")

print("DONE")
