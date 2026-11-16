"""从彩蛋动画帧生成 App 图标（mipmap + 自适应图标图层）。

用法:
    python tools/make_icon.py tools/icon/frame-swing.png app/src/main/res
    python tools/make_icon.py assets/source/mj-swing-dual-mask.mp4 app/src/main/res --frame 66

mp4 是「双蒙版」素材（左半 Alpha 蒙版 / 右半彩色），脚本会用 ffmpeg 合成带 alpha 的帧：
剪影来自左半、颜色来自右半。图标用的是第 66 帧（角色荡到最低点、爱心最全）。
"""
import math
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter

S = 1024  # 设计画布尺寸，最后再缩到各密度
FFMPEG = "tools/ffmpeg/ffmpeg-master-latest-win64-gpl/bin/ffmpeg.exe"


def extract_frame(mp4, index, out_path):
    """双蒙版 mp4 -> 带 alpha 的 PNG 帧。"""
    subprocess.run(
        [
            FFMPEG, "-y", "-v", "error", "-i", mp4,
            "-filter_complex",
            "[0:v]select=eq(n\\,%d),split[k][c0];" % index
            + "[k]crop=iw/2:ih:0:0,format=gray[a];"
            "[c0]crop=iw/2:ih:iw/2:0,format=rgba[c];"
            "[c][a]alphamerge[out]",
            "-map", "[out]",
            "-frames:v", "1", out_path,
        ],
        check=True,
    )
    return out_path


def radial_bg(size=S):
    """低分辨率算径向渐变，再放大，避免逐像素循环太慢。"""
    n = 200
    small = Image.new("RGB", (n, n))
    px = small.load()
    cx, cy = 0.5 * n, 0.5 * n
    rmax = 0.72 * n
    inner = (74, 12, 30)
    outer = (7, 6, 13)
    for y in range(n):
        for x in range(n):
            d = math.hypot(x - cx, y - cy) / rmax
            t = min(1.0, d) ** 1.25
            px[x, y] = tuple(int(inner[i] + (outer[i] - inner[i]) * t) for i in range(3))
    return small.resize((size, size), Image.LANCZOS)


def web_pattern(size=S):
    """从左上角放射的蛛网：放射线 + 同心弧。"""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    ox, oy = 0.06 * size, 0.02 * size
    white = (255, 255, 255, 52)
    reach = 1.45 * size
    # 放射线
    for ang in range(-4, 92, 11):
        a = math.radians(ang)
        d.line(
            [(ox, oy), (ox + reach * math.cos(a), oy + reach * math.sin(a))],
            fill=white,
            width=max(1, int(size * 0.0045)),
        )
    # 同心弧
    for i in range(1, 8):
        r = size * 0.16 * i
        d.arc(
            [ox - r, oy - r, ox + r, oy + r],
            start=-4,
            end=92,
            fill=white,
            width=max(1, int(size * 0.004)),
        )
    return layer.filter(ImageFilter.GaussianBlur(size * 0.0015))


def vignette(size=S):
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    steps = 60
    for i in range(steps):
        t = i / steps
        inset = -size * 0.35 * (1 - t)
        alpha = int(120 * (1 - t) ** 2.2)
        d.ellipse(
            [inset, inset, size - inset, size - inset],
            outline=(0, 0, 0, alpha),
            width=int(size * 0.02),
        )
    return layer.filter(ImageFilter.GaussianBlur(size * 0.03))


def glow(size=S):
    """角色身后的暖光，把角色从深色背景里托起来。"""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy, r = 0.5 * size, 0.6 * size, 0.46 * size
    for i in range(48, 0, -1):
        t = i / 48
        d.ellipse(
            [cx - r * t, cy - r * 0.94 * t, cx + r * t, cy + r * 0.94 * t],
            fill=(255, 92, 120, 7),
        )
    return layer.filter(ImageFilter.GaussianBlur(size * 0.045))


# 帧尺寸 1080x2336；以下坐标由连通域分析 + 人工确认得到
# 带上完整蛛丝（从画面左边缘一直拉到角色头上），这样它在图标里自然地从左上角穿出去
CHAR_BOX = (0, 250, 790, 1432)
HEART_BOXES = [(852, 660, 996, 788), (852, 660, 996, 788)]


def crop_char(frame_path):
    im = Image.open(frame_path).convert("RGBA")
    c = im.crop(CHAR_BOX)
    # 右边缘会带进来两小块爱心碎片，按帧坐标算好的矩形直接清掉（矩形都在角色轮廓外）
    px = c.load()
    for fx0, fy0, fx1, fy1 in ((690, 470, 790, 595), (768, 785, 790, 900)):
        for y in range(max(0, fy0 - CHAR_BOX[1]), min(c.height, fy1 - CHAR_BOX[1])):
            for x in range(max(0, fx0 - CHAR_BOX[0]), min(c.width, fx1 - CHAR_BOX[0])):
                px[x, y] = (0, 0, 0, 0)
    return c


def crop_hearts(frame_path):
    im = Image.open(frame_path).convert("RGBA")
    return [im.crop(b) for b in HEART_BOXES]


def compose_fg(size, char, hearts):
    """透明前景层：角色（带投影）+ 两颗爱心。"""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # 角色：等比放大到几乎满高，左边缘贴齐，让蛛丝从左边缘穿出去
    ch_h = size * 0.95
    ch_w = int(ch_h * char.width / char.height)
    c = char.resize((ch_w, int(ch_h)), Image.LANCZOS)
    shadow = Image.new("RGBA", c.size, (0, 0, 0, 0))
    shadow.putalpha(c.getchannel("A").point(lambda v: int(v * 0.7)))
    shadow = shadow.filter(ImageFilter.GaussianBlur(size * 0.018))
    cx = int(size * 0.025)
    cy = int(size * 0.972) - c.height
    layer.alpha_composite(shadow, (cx, cy + int(size * 0.012)))
    layer.alpha_composite(c, (cx, cy))

    # 爱心点缀
    placements = [(0.72, 0.045, 0.19), (0.80, 0.30, 0.115)]
    for heart, (fx, fy, fw) in zip(hearts, placements):
        w = int(size * fw)
        h = int(w * heart.height / heart.width)
        layer.alpha_composite(
            heart.resize((w, h), Image.LANCZOS), (int(size * fx), int(size * fy))
        )
    return layer


def compose(size, char, hearts):
    """背景 + 柔光 + 蛛网 + 角色 + 两颗爱心 + 暗角。"""
    icon = radial_bg(size)
    icon = Image.alpha_composite(icon.convert("RGBA"), glow(size))
    icon = Image.alpha_composite(icon, web_pattern(size))
    icon = Image.alpha_composite(icon, compose_fg(size, char, hearts))

    icon = Image.alpha_composite(icon, vignette(size))
    return icon


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    frame_path, res_dir = args[0], args[1]
    if frame_path.lower().endswith(".mp4"):
        index = 66
        for i, a in enumerate(sys.argv):
            if a == "--frame":
                index = int(sys.argv[i + 1])
        os.makedirs("tools/icon", exist_ok=True)
        frame_path = extract_frame(frame_path, index, "tools/icon/_frame.png")
    char = crop_char(frame_path)
    hearts = crop_hearts(frame_path)

    # 完整方形图标（含圆角遮罩，用于 legacy 图标）
    icon = compose(S, char, hearts)
    icon.putalpha(rounded_mask(S, int(S * 0.225)))

    # 自适应图标：背景层（渐变+蛛网）、前景层（角色+爱心，收进安全区）
    bg = Image.alpha_composite(radial_bg().convert("RGBA"), glow())
    bg = Image.alpha_composite(bg, web_pattern())
    bg = Image.alpha_composite(bg, vignette())
    fg_canvas = compose_fg(S, char, hearts)
    fg_canvas = fg_canvas.resize((int(S * 0.90), int(S * 0.90)), Image.LANCZOS)
    fg_full = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    fg_full.alpha_composite(fg_canvas, ((S - fg_canvas.width) // 2, (S - fg_canvas.height) // 2))
    fg_canvas = fg_full

    layers = {
        "ic_launcher": icon,
        "ic_launcher_foreground": fg_canvas,
        "ic_launcher_background": bg,
    }
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, img in layers.items():
        for density, dp in densities.items():
            out_dir = os.path.join(res_dir, "mipmap-" + density)
            os.makedirs(out_dir, exist_ok=True)
            px = dp if name == "ic_launcher" else int(dp * 108 / 48)
            img.resize((px, px), Image.LANCZOS).save(os.path.join(out_dir, name + ".png"))

    os.makedirs(os.path.join(res_dir, "mipmap-anydpi-v26"), exist_ok=True)
    with open(os.path.join(res_dir, "mipmap-anydpi-v26", "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
            '</adaptive-icon>\n'
        )

    # 预览图（便于人工检查）
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join("tools", "icon", "preview-icon.png"))
    print("done")


if __name__ == "__main__":
    main()
