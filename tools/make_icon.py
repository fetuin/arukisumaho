import sys, os
from PIL import Image, ImageDraw
logo_path, res_dir, preview = sys.argv[1], sys.argv[2], sys.argv[3]
K = 4                      # 4倍で描いて縮小（なめらかに）
N = 432 * K                # 108dp @ xxxhdpi
RED = (229, 57, 53, 255); WHITE = (255, 255, 255, 255)
def s(v): return int(v * K)

fg = Image.new("RGBA", (N, N), (0, 0, 0, 0))
d = ImageDraw.Draw(fg)
# スマホ
d.rounded_rectangle([s(176), s(104), s(256), s(246)], radius=s(12), fill=WHITE)
d.rectangle([s(184), s(118), s(248), s(226)], fill=RED)
d.rounded_rectangle([s(206), s(232), s(226), s(238)], radius=s(3), fill=RED)
# びっくりマーク
d.polygon([(s(209), s(134)), (s(223), s(134)), (s(220), s(190)), (s(212), s(190))], fill=WHITE)
d.ellipse([s(208), s(198), s(224), s(214)], fill=WHITE)
# usePocket ロゴの白い帯
d.rounded_rectangle([s(106), s(258), s(326), s(308)], radius=s(25), fill=WHITE)
logo = Image.open(logo_path).convert("RGBA").crop((4, 0, 490, 90))
lw = s(196); lh = round(logo.height * lw / logo.width)
logo = logo.resize((lw, lh), Image.LANCZOS)
fg.alpha_composite(logo, (s(216) - lw // 2, s(283) - lh // 2))

for name, px in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]:
    os.makedirs(f"{res_dir}/mipmap-{name}", exist_ok=True)
    fg.resize((px, px), Image.LANCZOS).save(f"{res_dir}/mipmap-{name}/ic_launcher_foreground.png")

# 確認用：丸形と角丸のマスクをかけた見た目（実寸に近い大きさも並べる）
full = Image.new("RGBA", (N, N), RED); full.alpha_composite(fg)
vis = full.crop((s(72), s(72), s(360), s(360)))  # 表示される72dp部分
def masked(shape):
    m = Image.new("L", vis.size, 0); md = ImageDraw.Draw(m)
    if shape == "circle": md.ellipse([0, 0, vis.width, vis.height], fill=255)
    else: md.rounded_rectangle([0, 0, vis.width, vis.height], radius=vis.width // 4, fill=255)
    out = Image.new("RGBA", vis.size, (0, 0, 0, 0)); out.paste(vis, (0, 0), m); return out
big = 360; small = 144
canvas = Image.new("RGBA", (big * 2 + small * 2 + 100, big + 40), (240, 240, 240, 255))
x = 20
for shp in ["circle", "square"]:
    canvas.alpha_composite(masked(shp).resize((big, big), Image.LANCZOS), (x, 20)); x += big + 20
for shp in ["circle", "square"]:
    canvas.alpha_composite(masked(shp).resize((small, small), Image.LANCZOS), (x, 20 + big - small)); x += small + 20
canvas.convert("RGB").save(preview)
