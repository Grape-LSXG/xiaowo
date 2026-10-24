#!/usr/bin/env python3
#
# 从 art/icon-order.png、art/icon-recv.png 生成两端的启动图标。
#
#   python3 tools/make_icons.py
#
# 产物：
#   res/mipmap-*/ic_launcher.png         接单端（张老师）—— 从墙后探头的那只
#   res-order/mipmap-*/ic_launcher.png   点单端（猫猫）—— 戴月桂冠的那只
#
# 为什么要先裁掉四周的白边：
#   原图是正方形画布，猫只占中间一块。直接缩到 48px 时猫小得认不出来，
#   裁到"内容外接框 + 一点留白"再缩，48px 下轮廓依然清楚。
#
# 老式图标（没有自适应图标），因为工程里连 res/values 都没有，
# 加 adaptive-icon 得连带引入前景层安全区，收益不值当。

import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DENSITIES = [
    ("mdpi", 48),
    ("hdpi", 72),
    ("xhdpi", 96),
    ("xxhdpi", 144),
    ("xxxhdpi", 192),
]

# 内容留白：裁完之后四周各留 7%，太贴边会被启动器的圆角切到
PAD_RATIO = 0.07
# 判定"这是背景"的阈值：三个通道都 >= 这个值就算白底
BG_THRESHOLD = 244


def flatten_to_white(im):
    """带 alpha 的图铺到白底上（原图是 RGBA，直接缩会缩出灰边）"""
    if im.mode == "RGBA":
        bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
        return Image.alpha_composite(bg, im).convert("RGB")
    return im.convert("RGB")


def content_bbox(im):
    """内容外接框：不依赖 PIL 的 getbbox（它只看 alpha，这里底是白的）"""
    w, h = im.size
    px = im.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    # 隔行采样就够了，288px 的图没必要逐像素扫
    step = max(1, w // 200)
    for y in range(0, h, step):
        for x in range(0, w, step):
            r, g, b = px[x, y]
            if r < BG_THRESHOLD or g < BG_THRESHOLD or b < BG_THRESHOLD:
                if x < minx:
                    minx = x
                if y < miny:
                    miny = y
                if x > maxx:
                    maxx = x
                if y > maxy:
                    maxy = y
    if maxx < 0:
        return None
    return (minx, miny, maxx + step, maxy + step)


def square_crop(im):
    """裁到内容 + 居中留白，补成正方形"""
    box = content_bbox(im)
    if box is None:
        return im
    x0, y0, x1, y1 = box
    side = max(x1 - x0, y1 - y0)
    side = int(side * (1 + PAD_RATIO * 2))
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    left, top = cx - side // 2, cy - side // 2
    # 用白底接管越界部分
    canvas = Image.new("RGB", (side, side), (255, 255, 255))
    src = im.crop((max(0, left), max(0, top),
                   min(im.width, left + side), min(im.height, top + side)))
    canvas.paste(src, (max(0, -left), max(0, -top)))
    return canvas


def build(src, out_root):
    im = square_crop(flatten_to_white(Image.open(src)))
    for bucket, size in DENSITIES:
        d = os.path.join(ROOT, out_root, "mipmap-" + bucket)
        os.makedirs(d, exist_ok=True)
        out = os.path.join(d, "ic_launcher.png")
        im.resize((size, size), Image.LANCZOS).save(out, optimize=True)
        print("  %-24s %3dpx" % (os.path.relpath(out, ROOT), size))


def main():
    print("接单端图标（res/，从墙后探头）")
    build(os.path.join(ROOT, "art", "icon-recv.png"), "res")
    print("点单端图标（res-order/，戴月桂冠）")
    build(os.path.join(ROOT, "art", "icon-order.png"), "res-order")


if __name__ == "__main__":
    main()
