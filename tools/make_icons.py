"""Renders Stremli's launcher assets from the in-app wordmark.

The wordmark is "stremlı" (with a dotless i), set in Google Sans Flex (extra bold, fully rounded)
with -0.7/29 em tracking, in the theme's primary colour on its surface colour: the same as
StremliWordmark in MainActivity.kt. Keep the two in step.

Writes:
  app/src/main/res/drawable-xhdpi/tv_banner.png   320x180  Android TV / Fire TV launcher banner
  app/src/main/res/drawable-xxhdpi/tv_banner.png  480x270
  app/src/main/res/mipmap-*/ic_launcher.png       48..192  app icon (Fire TV app library, Settings)
  docs/stremli-icon-512.png                       512x512  large icon for listings
  app/src/main/res/drawable-xhdpi/launch_logo.png 480 wide wordmark for the launch screen

Usage: python tools/make_icons.py   (needs Pillow)
"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
FONT = os.path.join(RES, "font", "google_sans_flex.ttf")
PRIMARY = (0xB1, 0xC5, 0xFF, 255)
SURFACE = (0x12, 0x13, 0x18, 255)
GLOW = (0x2B, 0x3A, 0x66)
SS = 4  # supersampling for smooth edges


def font(size):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([100, 800, 100])  # width, weight (extra bold), roundness
    except Exception:
        pass
    return f


def wordmark(width_px):
    """The wordmark on a transparent canvas, tightly cropped, about width_px wide."""
    size = 400 * SS
    f = font(size)
    text = "stremlı"  # dotless i, as in the app
    tracking = size * -0.7 / 29  # letterSpacing -0.7.sp at 29.sp
    canvas = Image.new("RGBA", (int(f.getlength(text) + size), int(size * 1.6)), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    x, y0 = size * 0.2, size * 0.3
    for ch in text:
        d.text((x, y0), ch, font=f, fill=PRIMARY)
        x += f.getlength(ch) + tracking
    canvas = canvas.crop(canvas.getbbox())
    scale = width_px * SS / canvas.width
    return canvas.resize((round(canvas.width * scale), round(canvas.height * scale)), Image.LANCZOS)


def tile(w, h, mark_width, glow=True):
    img = Image.new("RGBA", (w * SS, h * SS), SURFACE)
    if glow:
        # A faint primary glow behind the mark, like the app's backdrops.
        g = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(g).ellipse([w * SS * 0.15, h * SS * 0.1, w * SS * 0.85, h * SS * 0.9], fill=GLOW + (150,))
        img = Image.alpha_composite(img, g.filter(ImageFilter.GaussianBlur(w * SS * 0.12)))
    mark = wordmark(mark_width)
    img.alpha_composite(mark, ((img.width - mark.width) // 2, (img.height - mark.height) // 2))
    return img.resize((w, h), Image.LANCZOS).convert("RGB")


def save(img, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print(os.path.relpath(path, ROOT), img.size)


if __name__ == "__main__":
    save(tile(320, 180, 220), RES, "drawable-xhdpi", "tv_banner.png")
    save(tile(480, 270, 330), RES, "drawable-xxhdpi", "tv_banner.png")
    for folder, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        save(tile(px, px, round(px * 0.8)), RES, "mipmap-" + folder, "ic_launcher.png")
    save(tile(512, 512, 420), ROOT, "docs", "stremli-icon-512.png")
    # Launch logo: the wordmark alone on transparent, 240 dp wide at xhdpi. The window background
    # (drawable/launch_background.xml) and the in-app launch screen both show it, so they match.
    mark = wordmark(480)
    save(mark.resize((mark.width // SS, mark.height // SS), Image.LANCZOS), RES, "drawable-xhdpi", "launch_logo.png")
