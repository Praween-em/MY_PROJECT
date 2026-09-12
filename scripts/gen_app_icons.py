from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

root = Path(r"D:\OLA-TAPPER\assets")

BACKGROUND = "#041109"
SURFACE = "#0A1C11"
PRIMARY = "#24B968"
BRIGHT = "#62E6A0"
TEXT = "#F0F8F3"
MUTED = "#A8C2B1"


def font(size, bold=True):
    names = [
        Path(r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf"),
        Path(r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf"),
    ]
    for name in names:
        if name.exists():
            return ImageFont.truetype(str(name), size)
    return ImageFont.load_default()


def save(img, path, size=None):
    out = img.resize((size, size), Image.Resampling.LANCZOS) if size else img
    path.parent.mkdir(parents=True, exist_ok=True)
    out.save(path, "PNG", optimize=True)
    print(f"{path} {out.size} {path.stat().st_size}")


def make_icon():
    im = Image.new("RGB", (1024, 1024), BACKGROUND)
    draw = ImageDraw.Draw(im)
    draw.rounded_rectangle((72, 72, 952, 952), radius=238, fill=SURFACE, outline=PRIMARY, width=24)
    draw.rounded_rectangle((112, 112, 912, 912), radius=210, outline=BRIGHT, width=4)
    draw.text(
        (512, 465),
        "AG",
        font=font(330),
        fill=TEXT,
        anchor="mm",
        stroke_width=3,
        stroke_fill=PRIMARY,
    )
    draw.rounded_rectangle((298, 694, 726, 710), radius=8, fill=PRIMARY)
    draw.text((512, 774), "RIDER", font=font(76), fill=BRIGHT, anchor="mm")
    return im


def make_splash():
    im = Image.new("RGB", (1024, 1024), BACKGROUND)
    draw = ImageDraw.Draw(im)
    draw.rounded_rectangle((356, 248, 668, 560), radius=92, fill=SURFACE, outline=PRIMARY, width=12)
    draw.text((512, 397), "AG", font=font(142), fill=TEXT, anchor="mm")
    draw.text((512, 651), "AG RIDER", font=font(94), fill=BRIGHT, anchor="mm")
    draw.rounded_rectangle((344, 724, 680, 734), radius=5, fill=PRIMARY)
    draw.text((512, 783), "DRIVER ASSIST", font=font(28), fill=MUTED, anchor="mm")
    return im


icon = make_icon()
save(icon, root / "app_icon.png", 1024)
save(make_splash(), root / "splash-ag-rider.png", 1024)

android = root / "AppIcons" / "android"
save(icon, android / "adaptive-foreground.png", 432)

densities = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in densities.items():
    save(icon, android / folder / "ic_launcher.png", size)

print("done")
