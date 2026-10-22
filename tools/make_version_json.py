#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成自动更新清单 version.json（并把两个 APK 一起整理进输出目录）。

App 端 src/com/couple/xiaowo/core/Updater.java 是硬编码读这个格式的，字段名不能改：
    {
      "order": { "versionCode": 7, "versionName": "1.6", "url": ".../xiaowo-order.apk", "notes": "..." },
      "recv":  { "versionCode": 7, "versionName": "1.6", "url": ".../xiaowo-recv.apk",  "notes": "..." }
    }

版本号一律从 APK 里用 `aapt dump badging` 读，绝不写死 —— 免得清单和安装包对不上，
App 会以为「已经是最新版」而永远不更新（或者反复提示更新）。

用法：
    python3 tools/make_version_json.py \
        --order build/xiaowo-order.apk \
        --recv  build/xiaowo-recv.apk \
        --base-url https://raw.githubusercontent.com/USER/REPO/main/xiaowo \
        --out dist

只校验已生成的清单（不读 APK，publish.sh 会用）：
    python3 tools/make_version_json.py --verify dist

退出码：0 成功，1 出错。
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

# ---------------------------------------------------------------- 常量

# role -> 放进输出目录后的标准文件名（version.json 里的 url 也按这个拼）
APK_NAME = {
    "order": "xiaowo-order.apk",
    "recv": "xiaowo-recv.apk",
}

# 用来做一次安全校验：传错包（比如把接单端当点单端传）要能立刻发现
EXPECT_PKG = {
    "order": "com.couple.xiaowo.order",
    "recv": "com.couple.xiaowo.recv",
}

LABEL = {
    "order": "点单端",
    "recv": "接单端",
}

DEFAULT_NOTES = "问题修复与体验优化"

# ---------------------------------------------------------------- 输出小工具

_NO_COLOR = bool(os.environ.get("NO_COLOR")) or not sys.stdout.isatty()


def _c(code, text):
    return text if _NO_COLOR else "\033[%sm%s\033[0m" % (code, text)


def info(text):
    print(_c("36", text))


def ok(text):
    print(_c("32", text))


def warn(text):
    print(_c("33", "⚠ " + text))


def die(text):
    print(_c("31", "✗ " + text), file=sys.stderr)
    sys.exit(1)


# ---------------------------------------------------------------- 读 APK


def read_badging(path):
    """用 aapt 读 APK 的包名 / versionCode / versionName。返回 dict。"""
    if not os.path.isfile(path):
        die("找不到 APK：%s" % path)
    size = os.path.getsize(path)
    if size < 1024:
        die("APK 太小（%d 字节），看起来不是有效安装包：%s" % (size, path))

    try:
        proc = subprocess.run(
            ["aapt", "dump", "badging", path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=180,
        )
    except FileNotFoundError:
        die("找不到 aapt，先装：apt-get install aapt zipalign")
    except subprocess.TimeoutExpired:
        die("aapt 读取超时：%s" % path)

    out = proc.stdout.decode("utf-8", "replace")
    err = proc.stderr.decode("utf-8", "replace")
    if proc.returncode != 0:
        tail = (err or out).strip().splitlines()[-3:]
        die("aapt 读不出 %s\n    %s" % (path, "\n    ".join(tail)))

    line = ""
    for l in out.splitlines():
        if l.startswith("package:"):
            line = l
            break
    if not line:
        die("aapt 输出里没有 package: 行，APK 可能损坏：%s" % path)

    def grab(key):
        m = re.search(r"\b%s='([^']*)'" % key, line)
        return m.group(1) if m else ""

    vcode_raw = grab("versionCode")
    if not vcode_raw.isdigit():
        die("读不到 versionCode（拿到 %r）：%s" % (vcode_raw, path))
    vname = grab("versionName")
    if not vname:
        die("读不到 versionName：%s" % path)

    return {
        "package": grab("name"),
        "versionCode": int(vcode_raw),
        "versionName": vname,
        "size": size,
    }


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def human(size):
    if size >= 1024 * 1024:
        return "%.1f MB" % (size / 1024.0 / 1024.0)
    if size >= 1024:
        return "%.1f KB" % (size / 1024.0)
    return "%d B" % size


# ---------------------------------------------------------------- 参数

def build_parser():
    p = argparse.ArgumentParser(
        description="生成自动更新清单 version.json（版本号从 APK 里读，不写死）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python3 tools/make_version_json.py \\\n"
            "      --order build/xiaowo-order.apk \\\n"
            "      --recv  build/xiaowo-recv.apk \\\n"
            "      --base-url https://raw.githubusercontent.com/USER/REPO/main/xiaowo \\\n"
            "      --out dist\n"
            "\n"
            "发布流程见 docs/auto-update.md\n"
        ),
    )
    p.add_argument("--order", default="build/xiaowo-order.apk",
                   help="点单端 APK 路径（默认 build/xiaowo-order.apk）")
    p.add_argument("--recv", default="build/xiaowo-recv.apk",
                   help="接单端 APK 路径（默认 build/xiaowo-recv.apk）")
    p.add_argument("--base-url", default="",
                   help="APK 所在目录的公网地址（不带结尾斜杠），"
                        "url 字段 = <base-url>/xiaowo-order.apk")
    p.add_argument("--order-url", default="",
                   help="直接指定点单端 APK 的完整直链（优先于 --base-url，"
                        "适合 GitHub Release 这种路径不规则的地方）")
    p.add_argument("--recv-url", default="",
                   help="直接指定接单端 APK 的完整直链（同上）")
    p.add_argument("--out", default="dist",
                   help="输出目录（默认 dist）：version.json + 两个 APK 都放这里")
    p.add_argument("--notes", default=None,
                   help='更新说明，两端共用（默认「%s」；传空字符串则不写说明）' % DEFAULT_NOTES)
    p.add_argument("--order-notes", default=None,
                   help="点单端单独的更新说明（覆盖 --notes）")
    p.add_argument("--recv-notes", default=None,
                   help="接单端单独的更新说明（覆盖 --notes）")
    p.add_argument("--no-copy", action="store_true",
                   help="只写 version.json，不把 APK 复制进输出目录"
                        "（APK 已经用别的方式上传时用这个）")
    p.add_argument("--verify", metavar="DIR", default="",
                   help="只校验 DIR 里已有的 version.json，不读 APK、不写文件")
    return p


# ---------------------------------------------------------------- 校验已生成的清单


def verify_dir(out_dir):
    path = os.path.join(out_dir, "version.json")
    problems = []

    if not os.path.isfile(path):
        die("找不到 %s（先跑一次生成）" % path)
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        die("%s 不是合法 JSON：%s" % (path, e))

    if not isinstance(data, dict):
        die("%s 顶层必须是对象" % path)

    for role in ("order", "recv"):
        node = data.get(role)
        if not isinstance(node, dict):
            problems.append("缺少顶层 key「%s」或它不是对象" % role)
            continue
        vc = node.get("versionCode")
        if not isinstance(vc, int) or isinstance(vc, bool) or vc <= 0:
            problems.append("%s.versionCode 必须是正整数，实际是 %r" % (role, vc))
        vn = node.get("versionName")
        if not isinstance(vn, str) or not vn.strip():
            problems.append("%s.versionName 必须是非空字符串，实际是 %r" % (role, vn))
        url = node.get("url")
        if not isinstance(url, str) or not re.match(r"^https?://", url or ""):
            problems.append("%s.url 必须是 http(s) 直链，实际是 %r" % (role, url))
        elif not url.lower().endswith(".apk"):
            problems.append("%s.url 结尾不是 .apk（是不是填成了网盘页面？）：%s"
                            % (role, url))
        notes = node.get("notes")
        if notes is not None and not isinstance(notes, str):
            problems.append("%s.notes 必须是字符串（可省略），实际是 %r" % (role, notes))
        leftover = [k for k in node if k not in
                    ("versionCode", "versionName", "url", "notes")]
        if leftover:
            problems.append("%s 里有 Updater 不认识的字段：%s"
                            % (role, ", ".join(sorted(leftover))))

    # 两端 versionCode 不同步很常见地导致「一个端永远收不到更新」
    oc = data.get("order", {}).get("versionCode") if isinstance(data.get("order"), dict) else None
    rc = data.get("recv", {}).get("versionCode") if isinstance(data.get("recv"), dict) else None
    if isinstance(oc, int) and isinstance(rc, int) and oc != rc:
        warn("两端 versionCode 不一致（order=%d / recv=%d）。"
             "通常应该同步递增，否则一端可能永远收不到更新。" % (oc, rc))

    # 输出目录里的 APK 是否真的在（只是提示：APK 也可能是在别处上传的）
    for role, fname in APK_NAME.items():
        local = os.path.join(out_dir, fname)
        if not os.path.isfile(local):
            warn("输出目录里没有 %s（如果 APK 是在别处上传的，忽略这条）" % fname)
        elif os.path.getsize(local) < 1024:
            problems.append("输出目录里的 %s 太小，像是空文件" % fname)

    if problems:
        for p in problems:
            print(_c("31", "✗ " + p), file=sys.stderr)
        die("%s 校验不通过（%d 个问题）" % (path, len(problems)))

    ok("✓ %s 校验通过（order %s / vc%d，recv %s / vc%d）"
       % (path,
          data["order"]["versionName"], data["order"]["versionCode"],
          data["recv"]["versionName"], data["recv"]["versionCode"]))
    return 0


# ---------------------------------------------------------------- 主流程


def normalize_base_url(raw):
    url = (raw or "").strip().rstrip("/")
    if not url:
        return ""
    if not re.match(r"^https?://", url):
        die("--base-url 必须以 http:// 或 https:// 开头：%s" % raw)
    if url.lower().startswith("http://"):
        warn("用了 http:// —— 建议换 https://。部分网络/系统策略会拦明文 HTTP 下载 APK。")
    return url


def main(argv=None):
    args = build_parser().parse_args(argv)

    if args.verify:
        return verify_dir(args.verify)

    base = normalize_base_url(args.base_url)
    order_url = (args.order_url or "").strip()
    recv_url = (args.recv_url or "").strip()
    if not base and not (order_url and recv_url):
        die("必须给 --base-url（推荐），或者 --order-url 和 --recv-url 两个完整直链")

    notes_common = DEFAULT_NOTES if args.notes is None else args.notes.strip()
    notes = {
        "order": (args.order_notes if args.order_notes is not None else notes_common).strip(),
        "recv": (args.recv_notes if args.recv_notes is not None else notes_common).strip(),
    }

    out_dir = args.out
    try:
        os.makedirs(out_dir, exist_ok=True)
    except Exception as e:
        die("建不了输出目录 %s：%s" % (out_dir, e))

    sources = {"order": args.order, "recv": args.recv}
    urls = {"order": order_url, "recv": recv_url}

    # ---- 1. 读两个 APK 的真实版本，顺手校验包名没传反 ----
    info("== 读取 APK 版本 ==")
    meta = {}
    for role in ("order", "recv"):
        m = read_badging(sources[role])
        expect = EXPECT_PKG[role]
        if m["package"] and m["package"] != expect:
            if m["package"] in EXPECT_PKG.values():
                die("--%s 传错包了：这个 APK 的包名是 %s，应该是 %s"
                    % (role, m["package"], expect))
            warn("--%s 的包名是 %s，和预期的 %s 不一样（继续，但请确认没传错）"
                 % (role, m["package"], expect))
        meta[role] = m
        info("   %s %s  versionCode=%d  versionName=%s  包名=%s  %s"
             % (LABEL[role], os.path.basename(sources[role]),
                m["versionCode"], m["versionName"],
                m["package"] or "?", human(m["size"])))

    if meta["order"]["versionCode"] != meta["recv"]["versionCode"]:
        warn("两端 versionCode 不一致（order=%d / recv=%d）。建议同步递增，"
             "否则一端可能永远收不到更新。"
             % (meta["order"]["versionCode"], meta["recv"]["versionCode"]))

    if not 0 < meta["order"]["versionCode"] <= 2147483647:
        die("versionCode 超出 Android 允许范围：%d" % meta["order"]["versionCode"])

    # ---- 2. 复制 APK 进输出目录 ----
    if not args.no_copy:
        info("== 整理输出目录 %s/ ==" % out_dir.rstrip("/"))
        for role in ("order", "recv"):
            dst = os.path.join(out_dir, APK_NAME[role])
            src = sources[role]
            if os.path.abspath(src) == os.path.abspath(dst):
                info("   %s 已在目标位置，跳过复制" % APK_NAME[role])
            else:
                try:
                    shutil.copyfile(src, dst)
                except Exception as e:
                    die("复制 %s → %s 失败：%s" % (src, dst, e))
                info("   ✓ %s  (%s)" % (dst, human(os.path.getsize(dst))))
            # build.sh 会顺手生成 .idsig，清单用不上，别让它混进 dist 被上传
            for junk in (dst + ".idsig", dst + ".idsig.tmp"):
                if os.path.exists(junk):
                    try:
                        os.remove(junk)
                    except Exception:
                        pass
        # 复制后核对字节数，防止复制中断
        for role in ("order", "recv"):
            dst = os.path.join(out_dir, APK_NAME[role])
            if os.path.getsize(dst) != meta[role]["size"]:
                die("%s 复制后大小不一致（%d → %d），重试一次"
                    % (dst, meta[role]["size"], os.path.getsize(dst)))

    # ---- 3. 拼 url ----
    for role in ("order", "recv"):
        if not urls[role]:
            urls[role] = "%s/%s" % (base, APK_NAME[role])

    manifest = {}
    for role in ("order", "recv"):
        node = {
            "versionCode": meta[role]["versionCode"],
            "versionName": meta[role]["versionName"],
            "url": urls[role],
        }
        if notes[role]:
            node["notes"] = notes[role]
        manifest[role] = node

    # ---- 4. 写 version.json ----
    json_path = os.path.join(out_dir, "version.json")
    try:
        with open(json_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
            f.write("\n")
    except Exception as e:
        die("写 %s 失败：%s" % (json_path, e))

    info("== 写出 %s ==" % json_path)
    ok("   ✓ %s" % json_path)

    # ---- 5. 自校验：刚写的清单必须能过 --verify ----
    verify_dir(out_dir)

    # ---- 6. 给点摘要，方便复制 ----
    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    print()
    info("== 本次清单摘要（%s）==" % stamp)
    for role in ("order", "recv"):
        m = meta[role]
        print("   %s  %-14s %-20s %s"
              % (LABEL[role], "%s(%d)" % (m["versionName"], m["versionCode"]),
                 human(m["size"]), urls[role]))
        if not args.no_copy:
            print("       sha256 %s" % sha256_of(os.path.join(out_dir, APK_NAME[role])))

    if notes["order"] or notes["recv"]:
        print("   更新说明：order=「%s」 recv=「%s」" % (notes["order"], notes["recv"]))

    print()
    info("== 下一步 ==")
    if args.no_copy:
        print("   1. APK 已经用你自己的方式上传好了（--no-copy），"
              "只需要把 %s 传到某个直链" % json_path)
        print("      —— 清单里的 url 指向哪里，就把它放在哪都行。")
        print("   2. 在 App 的「我」→ 自动更新 → 设置更新地址 里填 version.json 的地址：")
        ok("      %s/version.json" % (base or "<你放 version.json 的目录>"))
    else:
        first_url = manifest["order"]["url"]
        manifest_url = first_url.rsplit("/", 1)[0] + "/version.json" if base else None
        print("   1. 把 %s/ 整个目录传上去（version.json + 两个 APK 必须同目录、"
              "文件名不要改）" % out_dir.rstrip("/"))
        print("   2. 在 App 的「我」→ 自动更新 → 设置更新地址 里填：")
        if manifest_url:
            ok("      %s" % manifest_url)
        else:
            ok("      %s/version.json" % first_url.rsplit("/", 1)[0])
    print("   3. 详细流程见 docs/auto-update.md")
    print()
    print("   校验命令： python3 -m json.tool %s" % json_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
