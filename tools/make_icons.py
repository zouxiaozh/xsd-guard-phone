# -*- coding: utf-8 -*-
"""生成「学时兑守护」图标 / TV banner，视觉语言与学时兑正式包完全一致。

素材直接取自 XsdAnser-v30-ui.apk：
  - res/drawable-*/ic_launcher.png   青绿渐变圆角方块 + 白色「兑」
  - res/drawable-xhdpi/tv_banner.png 青绿渐变横幅 + 「学时兑 / 电视学习·学时可兑」

守护版沿用同一渐变、圆角、白字，只把主字换成「守」；
banner 同版式改为「学时兑守护」。不叠加角标，保证 48dp 小尺寸下依旧干净。
输出：phone-guard/res/drawable-*/ic_launcher.png、ic_stat_guard.png、tv_banner.png
     （ic_stat_guard 为纯白透明的 notification smallIcon）
"""
import os
import sys
import zipfile
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PHONE = os.path.dirname(HERE)
BASE = os.path.dirname(PHONE)
SRC_APK = os.path.join(BASE, "XsdAnser-v30-ui.apk")
RES = os.path.join(PHONE, "res")

FONT_CANDIDATES = [
    "C:/Windows/Fonts/msyhbd.ttc",     # 微软雅黑 Bold（与原图粗黑体最接近）
    "C:/Windows/Fonts/simhei.ttf",     # 黑体
    "C:/Windows/Fonts/msyh.ttc",
]
DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]


def font(size):
    for p in FONT_CANDIDATES:
        if os.path.isfile(p):
            return ImageFont.truetype(p, size)
    raise RuntimeError("未找到中文字体，无法生成图标")


def bilinear(c00, c10, c01, c11, w, h):
    """用四个无字区域的采样色还原原图的青绿渐变。"""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        fy = float(y) / max(1, h - 1)
        for x in range(w):
            fx = float(x) / max(1, w - 1)
            r = (c00[0] * (1 - fx) + c10[0] * fx) * (1 - fy) + \
                (c01[0] * (1 - fx) + c11[0] * fx) * fy
            g = (c00[1] * (1 - fx) + c10[1] * fx) * (1 - fy) + \
                (c01[1] * (1 - fx) + c11[1] * fx) * fy
            b = (c00[2] * (1 - fx) + c10[2] * fx) * (1 - fy) + \
                (c01[2] * (1 - fx) + c11[2] * fx) * fy
            px[x, y] = (int(r), int(g), int(b), 255)
    return img


def white_bbox(im):
    px = im.load()
    w, h = im.size
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a > 180 and r > 195 and g > 195 and b > 195:
                xs.append(x)
                ys.append(y)
    if not xs:
        return (int(w * .2), int(h * .2), int(w * .8), int(h * .8))
    return (min(xs), min(ys), max(xs), max(ys))


def draw_char_centered(im, ch, box, color=(255, 255, 255, 255), scale=0.92):
    """把单个汉字按原始「兑」字的 bbox 等比居中绘制（略缩一点保留留白）。"""
    x0, y0, x1, y1 = box
    bw, bh = (x1 - x0) * scale, (y1 - y0) * scale
    size = max(8, int(bh * 1.35))
    f = font(size)
    d = ImageDraw.Draw(im)
    bb = d.textbbox((0, 0), ch, font=f)
    th = bb[3] - bb[1]
    if th > 0:
        size = max(8, int(size * bh / float(th)))
        f = font(size)
        d = ImageDraw.Draw(im)
        bb = d.textbbox((0, 0), ch, font=f)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    d.text((cx - tw / 2.0 - bb[0], cy - th / 2.0 - bb[1]), ch,
           font=f, fill=color)
    return im


def make_launcher(src):
    im = src.convert("RGBA")
    w, h = im.size
    p = im.load()
    c00 = p[int(w * .15), int(h * .15)]
    c10 = p[int(w * .85), int(h * .15)]
    c01 = p[int(w * .15), int(h * .85)]
    c11 = p[int(w * .85), int(h * .85)]
    bg = bilinear(c00, c10, c01, c11, w, h)
    alpha = im.getchannel("A")
    bg.putalpha(alpha)
    box = white_bbox(im)
    draw_char_centered(bg, "\u5b88", box)   # 守
    return bg


def make_stat_guard(size):
    """通知栏 smallIcon：系统只取 alpha，所以纯白图形 + 透明底。"""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    w = max(2, int(size * .085))
    pad = int(size * .10)
    d.ellipse((pad, pad, size - pad, size - pad),
              outline=(255, 255, 255, 255), width=w)
    d.line([(size * .34, size * .51), (size * .45, size * .62),
            (size * .67, size * .38)],
           fill=(255, 255, 255, 255), width=w, joint="curve")
    return im


def make_banner(src):
    """沿用原 banner 渐变，重画左方块与主标题。"""
    im = src.convert("RGBA")
    w, h = im.size
    p = im.load()
    # 四角采样（原 banner 无字区域）
    c00 = p[2, 2]
    c10 = p[w - 3, 2]
    c01 = p[2, h - 3]
    c11 = p[w - 3, h - 3]
    out = bilinear(c00, c10, c01, c11, w, h)
    d = ImageDraw.Draw(out)

    # 左侧圆角方块（与原图版式一致）
    bx0, by0 = int(w * .055), int(h * .21)
    side = int(h * .58)
    bx1, by1 = bx0 + side, by0 + side
    d.rounded_rectangle((bx0, by0, bx1, by1), radius=int(side * .23),
                        fill=(42, 170, 156, 255),
                        outline=(230, 255, 250, 120),
                        width=max(1, int(h * .005)))
    draw_char_centered(out, "\u5b88",
                       (bx0 + side * .12, by0 + side * .12,
                        bx1 - side * .12, by1 - side * .12))

    # 主标题：5 个字，字号按可用宽度收敛，避免右侧被裁
    tx, ty = int(w * .42), int(h * .27)
    f = font(int(h * .195))
    d.text((tx, ty), "\u5b66\u65f6\u5151\u5b88\u62a4", font=f,
           fill=(255, 255, 255, 255))          # 学时兑守护
    f2 = font(int(h * .072))
    d.text((tx + int(h * .012), ty + int(h * .30)),
           "\u5b88\u62a4\u8fdb\u7a0b \u00b7 \u81ea\u52a8\u62c9\u8d77",
           font=f2, fill=(230, 255, 250, 255))  # 守护进程 · 自动拉起
    return out


def main():
    if not os.path.isfile(SRC_APK):
        print("[FAIL] 找不到原包 %s" % SRC_APK)
        return 2
    z = zipfile.ZipFile(SRC_APK)
    made = []
    stat_sizes = {"mdpi": 24, "hdpi": 36, "xhdpi": 48,
                  "xxhdpi": 72, "xxxhdpi": 96}
    for dpi in DENSITIES:
        dst_dir = os.path.join(RES, "drawable-%s" % dpi)
        os.makedirs(dst_dir, exist_ok=True)
        name = "res/drawable-%s/ic_launcher.png" % dpi
        if name in z.namelist():
            import io
            src = Image.open(io.BytesIO(z.read(name)))
            out = make_launcher(src)
            dst = os.path.join(dst_dir, "ic_launcher.png")
            out.save(dst, optimize=True)
            made.append((dst, out.size))
        dst = os.path.join(dst_dir, "ic_stat_guard.png")
        make_stat_guard(stat_sizes[dpi]).save(dst, optimize=True)
        made.append((dst, (stat_sizes[dpi], stat_sizes[dpi])))
    banner = "res/drawable-xhdpi/tv_banner.png"
    if banner in z.namelist():
        import io
        src = Image.open(io.BytesIO(z.read(banner)))
        out = make_banner(src)
        dst_dir = os.path.join(RES, "drawable-xhdpi")
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, "tv_banner.png")
        out.save(dst, optimize=True)
        made.append((dst, out.size))
    for p, sz in made:
        print("[OK] %s %s" % (os.path.relpath(p, PHONE), sz))
    print("[OK] 共生成 %d 个资源" % len(made))
    return 0 if made else 1


if __name__ == "__main__":
    sys.exit(main())
