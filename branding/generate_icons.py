"""Generates every platform's app icon from branding/icon-master-1024.png.

The master is the full-bleed square (gradient to the edges, no rounded corners), cropped
from the designer's 2048px export just inside its pre-rounded corners. Each platform
applies its own mask, or none, so they get different variants:

  iOS, Play Store       full-bleed square - the OS / store rounds the corners
  Android launcher      adaptive icon: artwork shrunk into the circle-safe zone, its border
                        extended outward to fill the layer
  macOS / Windows /     macOS-style rounded square with a soft shadow on transparent,
  Linux, web favicon    since desktop OSes and browser tabs don't mask icons
  Play feature graphic  1024x500 store banner: icon + name + tagline (uses macOS's
                        Avenir Next font)

Run from the repo root (needs Pillow; macOS's iconutil for the .icns):
    python3 -m venv .venv && .venv/bin/pip install pillow
    .venv/bin/python branding/generate_icons.py
"""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
MASTER = Image.open(ROOT / "branding/icon-master-1024.png").convert("RGB")

# Android shows at most the central 72dp of a 108dp adaptive layer, and circle masks keep
# only a 66dp circle. At 53% the magnifier handle and play button stay inside it.
ANDROID_ARTWORK_SCALE = 0.53


def android_layer(size: int) -> Image.Image:
    """Full 108dp adaptive-icon layer: the master, shrunk, with its own border pixels
    extended outward to fill the rest. The fill continues the gradient exactly where the
    master ends, so there's no seam; a blur softens the stretched streaks."""
    inner = round(size * ANDROID_ARTWORK_SCALE)
    offset = (size - inner) // 2
    art = MASTER.resize((inner, inner), Image.LANCZOS)
    src = art.load()
    layer = Image.new("RGB", (size, size))
    px = layer.load()
    for y in range(size):
        sy = min(max(y - offset, 0), inner - 1)
        for x in range(size):
            px[x, y] = src[min(max(x - offset, 0), inner - 1), sy]
    layer = layer.filter(ImageFilter.GaussianBlur(size / 40))
    layer.paste(art, (offset, offset))
    return layer


def rounded(size: int) -> Image.Image:
    """macOS Big Sur-style icon: an 824/1024 rounded square with a soft drop shadow."""
    scale = 4  # draw the mask large and downsample, for smooth anti-aliased corners
    body, radius, offset = round(size * 824 / 1024), size * 185.4 / 1024, round(size * 100 / 1024)
    big = Image.new("L", (body * scale, body * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle((0, 0, body * scale - 1, body * scale - 1), radius=radius * scale, fill=255)
    mask = big.resize((body, body), Image.LANCZOS)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    shadow_alpha = Image.new("L", (size, size), 0)
    shadow_alpha.paste(mask.point(lambda a: a * 0.35), (offset, offset + max(1, size // 100)))
    shadow.putalpha(shadow_alpha.filter(ImageFilter.GaussianBlur(max(1, size / 60))))
    canvas.alpha_composite(shadow)
    face = MASTER.resize((body, body), Image.LANCZOS).convert("RGBA")
    face.putalpha(mask)
    canvas.alpha_composite(face, (offset, offset))
    return canvas


def feature_graphic() -> Image.Image:
    """Google Play feature graphic: 1024x500, no transparency. The icon on the left, the
    name and a tagline on the right, over a gradient sampled from the icon's own colors."""
    width, height = 1024, 500
    src = MASTER.load()
    top_left, bottom_right = src[8, 8], src[MASTER.width - 9, MASTER.height - 9]
    banner = Image.new("RGB", (width, height))
    px = banner.load()
    for y in range(height):
        for x in range(width):
            t = min(1.0, max(0.0, (x / width) * 0.75 + (y / height) * 0.25))
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(top_left, bottom_right))

    icon = rounded(1024).resize((340, 340), Image.LANCZOS)
    banner = banner.convert("RGBA")
    banner.alpha_composite(icon, (50, (height - 340) // 2))

    avenir = "/System/Library/Fonts/Avenir Next.ttc"
    title = ImageFont.truetype(avenir, 92, index=0)      # Bold
    tagline = ImageFont.truetype(avenir, 34, index=5)    # Medium
    draw = ImageDraw.Draw(banner)
    x = 420
    draw.text((x, 150), "Reel Scout", font=title, fill="white")
    for i, line in enumerate(["Find any movie or show you can", "watch free \u2014 legally."]):
        draw.text((x + 4, 275 + i * 46), line, font=tagline, fill=(255, 255, 255, 230))
    return banner.convert("RGB")


def write(img: Image.Image, path: str, size: int | None = None) -> None:
    out = ROOT / path
    out.parent.mkdir(parents=True, exist_ok=True)
    (img.resize((size, size), Image.LANCZOS) if size else img).save(out)
    print("wrote", path)


def main() -> None:
    # iOS: a single universal 1024 (no alpha allowed); Xcode derives the rest.
    write(MASTER, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png")

    # Android adaptive icon background layer, per density (108dp at 1x, 1.5x, 2x, 3x, 4x).
    big = android_layer(432)
    for density, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        write(big, f"androidApp/src/main/res/mipmap-{density}/ic_launcher_background.png", px)

    # Google Play listing icon (Play rounds the corners itself).
    write(MASTER, "branding/play-store-icon-512.png", 512)
    write(feature_graphic(), "branding/play-feature-graphic.png")

    # Desktop installers.
    icon = rounded(1024)
    write(icon, "composeApp/icons/icon.png", 512)
    ico = ROOT / "composeApp/icons/icon.ico"
    icon.save(ico, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("wrote composeApp/icons/icon.ico")
    with tempfile.TemporaryDirectory() as tmp:
        iconset = Path(tmp) / "icon.iconset"
        iconset.mkdir()
        for pt in (16, 32, 128, 256, 512):
            icon.resize((pt, pt), Image.LANCZOS).save(iconset / f"icon_{pt}x{pt}.png")
            icon.resize((pt * 2, pt * 2), Image.LANCZOS).save(iconset / f"icon_{pt}x{pt}@2x.png")
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(ROOT / "composeApp/icons/icon.icns")], check=True)
        print("wrote composeApp/icons/icon.icns")

    # Web: tab favicon (rounded, transparent corners) and home-screen icon (full-bleed).
    icon.save(ROOT / "composeApp/src/wasmJsMain/resources/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
    print("wrote composeApp/src/wasmJsMain/resources/favicon.ico")
    write(icon, "composeApp/src/wasmJsMain/resources/icon-192.png", 192)
    write(MASTER, "composeApp/src/wasmJsMain/resources/apple-touch-icon.png", 180)

    # README header.
    write(icon, "docs/icon.png", 256)


if __name__ == "__main__":
    main()
