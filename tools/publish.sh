#!/usr/bin/env bash
# =============================================================================
# 一键发布：build.sh 打包 → 生成 dist/（version.json + 两个 APK）→ 告诉你下一步
#
#   bash tools/publish.sh local     # 只生成 dist/，你自己把 dist/ 传到网盘/服务器
#   bash tools/publish.sh github    # 生成 dist/ 并打印 git 命令，推到 GitHub Pages/Release
#   bash tools/publish.sh gitee     # 按 Gitee raw 直链格式生成（国内最稳）
#
# 可选环境变量：
#   BASE_URL=https://xxx/xiaowo   # APK 所在目录的公网地址；不给就从 git remote 猜
#   NOTES="修了几个 bug"           # 更新说明，写进 version.json 的 notes
#   SKIP_BUILD=1                  # 跳过 build.sh，直接用 build/ 里已有的 APK
#   NO_COLOR=1                    # 关掉颜色
#
# 两个 APK 的 versionCode / versionName 一律由 tools/make_version_json.py 从
# APK 里读，脚本本身不写死任何版本号。
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist"
MJ="$ROOT/tools/make_version_json.py"

if [ -n "${NO_COLOR:-}" ]; then
  red()   { printf '%s\n' "$*"; }
  green() { printf '%s\n' "$*"; }
  blue()  { printf '%s\n' "$*"; }
  yell()  { printf '%s\n' "$*"; }
  bold()  { printf '%s\n' "$*"; }
else
  red()   { printf '\033[31m%s\033[0m\n' "$*"; }
  green() { printf '\033[32m%s\033[0m\n' "$*"; }
  blue()  { printf '\033[36m%s\033[0m\n' "$*"; }
  yell()  { printf '\033[33m%s\033[0m\n' "$*"; }
  bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
fi

usage() {
  cat <<'EOF'
用法： bash tools/publish.sh <local|github>

  local    只生成 dist/ 并打印下一步（你自己把 dist/ 传到网盘/服务器/对象存储）
  github   生成 dist/ 并打印 git 命令（推 GitHub Pages 或打 GitHub Release）
  gitee    按 Gitee raw 直链格式生成（用 GITEE_REPO=用户名/仓库 指定仓库）

可选环境变量：
  BASE_URL=https://xxx/xiaowo    APK 所在目录的公网地址（github 模式会自动猜）
  NOTES="修了几个 bug"            更新说明，写进 version.json
  SKIP_BUILD=1                   跳过 build.sh，直接用 build/ 里的 APK

说明文档： docs/auto-update.md
EOF
}

MODE="${1:-}"
case "$MODE" in
  local|github|gitee) ;;
  -h|--help|help|"") usage; exit 0 ;;
  *) red "未知参数：$MODE"; echo; usage; exit 1 ;;
esac

[ -f "$MJ" ] || { red "缺少 $MJ —— 发布工具不完整"; exit 1; }
command -v python3 >/dev/null || { red "缺 python3"; exit 1; }
command -v aapt >/dev/null || { red "缺 aapt：apt-get install aapt zipalign"; exit 1; }

# ---------------------------------------------------------------- 1. 打包

if [ "${SKIP_BUILD:-0}" = "1" ]; then
  yell "== 跳过 build.sh（SKIP_BUILD=1），直接用 build/ 里现有的 APK =="
  ls -lh "$ROOT"/build/*.apk 2>/dev/null || { red "build/ 里没有 APK，去掉 SKIP_BUILD 重跑"; exit 1; }
else
  blue "== 第 1 步：打包（bash build.sh）=="
  ( cd "$ROOT" && bash build.sh )
fi

# ---------------------------------------------------------------- 2. 猜 BASE_URL

ORDER_APK="$ROOT/build/xiaowo-order.apk"
RECV_APK="$ROOT/build/xiaowo-recv.apk"
for f in "$ORDER_APK" "$RECV_APK"; do
  [ -f "$f" ] || { red "找不到 $f，先跑 bash build.sh"; exit 1; }
done

BASE_URL="${BASE_URL:-}"

# gitee 模式：用 GITEE_REPO=用户名/仓库 + GITEE_BRANCH（默认 master）拼出 raw 直链基址
if [ "$MODE" = "gitee" ]; then
  GITEE_REPO="${GITEE_REPO:-}"
  GITEE_BRANCH="${GITEE_BRANCH:-master}"
  if [ -z "$GITEE_REPO" ]; then
    red "gitee 模式需要 GITEE_REPO=用户名/仓库"
    red "例： GITEE_REPO=Quriosity233/love bash tools/publish.sh gitee"
    exit 1
  fi
  if [ -z "$BASE_URL" ]; then
    BASE_URL="https://gitee.com/${GITEE_REPO}/raw/${GITEE_BRANCH}"
    yell "Gitee raw 直链基址： $BASE_URL"
  fi
fi

if [ -z "$BASE_URL" ]; then
  # 从 git remote 猜 GitHub Pages 地址：https://github.com/USER/REPO(.git) → https://USER.github.io/REPO/xiaowo
  guess=""
  if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    remote="$(git -C "$ROOT" remote get-url origin 2>/dev/null || true)"
    if [ -n "$remote" ]; then
      slug="$(printf '%s' "$remote" \
        | sed -e 's#^git@github\.com:##' -e 's#^https\?://github\.com/##' \
              -e 's#\.git$##' -e 's#/$##')"
      owner="${slug%%/*}"
      repo="${slug#*/}"
      if [ -n "$owner" ] && [ -n "$repo" ] && [ "$owner" != "$slug" ]; then
        guess="https://$owner.github.io/$repo/xiaowo"
      fi
    fi
  fi
  if [ -n "$guess" ]; then
    BASE_URL="$guess"
    yell "没给 BASE_URL，按 git remote 猜了一个："
    yell "  $BASE_URL"
    yell "（不对就用 BASE_URL=https://你的地址/xiaowo bash tools/publish.sh $MODE 重跑）"
  elif [ "$MODE" = "gitee" ]; then
  blue "== 第 3 步：把 dist/ 三个文件上传到 Gitee 仓库 =="
  cat <<EOF
Gitee 仓库： https://gitee.com/${GITEE_REPO}

上传方法（任选）：
A. 网页拖拽：浏览器打开仓库 → 点「上传文件」→ 把 dist/ 里这两个文件拖进去：
     xiaowo-order.apk
     xiaowo-recv.apk
     version.json
   （三个都在仓库根目录，文件名别改）

B. git 命令：
$(green "    cd "$ROOT"")
$(green "    cp dist/version.json dist/xiaowo-order.apk dist/xiaowo-recv.apk .")
$(green "    git init -b $GITEE_BRANCH")
$(green "    git remote add origin https://gitee.com/${GITEE_REPO}.git")
$(green "    git add version.json xiaowo-order.apk xiaowo-recv.apk")
$(green "    git commit -m "release $TAG"")
$(green "    git push -u origin $GITEE_BRANCH")

上传完，App 的「我」→ 自动更新 → ⚙ 设置更新地址 里填：

    $(green "$MANIFEST_URL")

说明：
  · Gitee raw 直链会自动 302 到 raw.giteeusercontent.com，App 会自己跟着跳，不用管
  · 公开仓库、单文件 <10MB 无需登录即可下载（我们点单端 0.7MB / 接单端 5.3MB，都达标）
  · 缓存 60~300 秒：刚上传完最多等几分钟，App 才能拉到最新
  · 每次发新版，记得两端 versionCode 一起加（build.sh 统一管，你不用手改）

EOF
elif [ "$MODE" = "local" ]; then
    BASE_URL="https://换成你的域名或直链目录/xiaowo"
    yell "没给 BASE_URL —— 先用占位地址生成 dist/，上传完记得改 dist/version.json 里的 url。"
    yell "推荐： BASE_URL=https://你的地址/xiaowo bash tools/publish.sh local"
  else
    red "github 模式需要 BASE_URL（要推到哪个地址不确定，我不猜）。"
    red "例： BASE_URL=https://USER.github.io/REPO/xiaowo bash tools/publish.sh github"
    exit 1
  fi
fi

# ---------------------------------------------------------------- 3. 生成 dist/

blue "== 第 2 步：生成 dist/（version.json + 两个 APK）=="
rm -rf "$OUT"
notes_args=()
if [ -n "${NOTES:-}" ]; then
  notes_args=(--notes "$NOTES")
fi

python3 "$MJ" \
  --order "$ORDER_APK" \
  --recv  "$RECV_APK" \
  --base-url "$BASE_URL" \
  --out "$OUT" \
  "${notes_args[@]+"${notes_args[@]}"}"

# ---------------------------------------------------------------- 4. 读回版本号做提示

read_ver() {  # $1=role  $2=fmt : pretty → "1.6(7)"；code → "7"
  python3 - "$OUT/version.json" "$1" "$2" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    d = json.load(f)[sys.argv[2]]
print(("%s(%s)" % (d["versionName"], d["versionCode"]))
      if sys.argv[3] == "pretty" else str(d["versionCode"]))
PY
}
VC_ORDER="$(read_ver order code)"     # 纯数字，用于 git tag / Release 路径
VC_RECV="$(read_ver recv code)"
VC_ORDER_PRETTY="$(read_ver order pretty)"
VC_RECV_PRETTY="$(read_ver recv pretty)"
TAG="v$VC_ORDER"                      # tag 里不能有括号/点，只用 versionCode 最安全
MANIFEST_URL="${BASE_URL%/}/version.json"

# ---------------------------------------------------------------- 4b. 更新说明

NOTES_DISPLAY="${NOTES:-（默认：问题修复与体验优化）}"

echo
green "================================================================"
green " 发布产物已就绪： $OUT/"
green "================================================================"
ls -lh "$OUT" | sed '1d'
echo
bold "两端版本：点单端 $VC_ORDER_PRETTY    接单端 $VC_RECV_PRETTY"
bold "更新说明：$NOTES_DISPLAY"
echo

# ---------------------------------------------------------------- 5. 按模式给下一步

if [ "$MODE" = "gitee" ]; then
  blue "== 第 3 步：把 dist/ 三个文件上传到 Gitee 仓库 =="
  cat <<EOF
Gitee 仓库： https://gitee.com/${GITEE_REPO}

上传方法（任选）：
A. 网页拖拽：浏览器打开仓库 → 点「上传文件」→ 把 dist/ 里这两个文件拖进去：
     xiaowo-order.apk
     xiaowo-recv.apk
     version.json
   （三个都在仓库根目录，文件名别改）

B. git 命令：
$(green "    cd "$ROOT"")
$(green "    cp dist/version.json dist/xiaowo-order.apk dist/xiaowo-recv.apk .")
$(green "    git init -b $GITEE_BRANCH")
$(green "    git remote add origin https://gitee.com/${GITEE_REPO}.git")
$(green "    git add version.json xiaowo-order.apk xiaowo-recv.apk")
$(green "    git commit -m "release $TAG"")
$(green "    git push -u origin $GITEE_BRANCH")

上传完，App 的「我」→ 自动更新 → ⚙ 设置更新地址 里填：

    $(green "$MANIFEST_URL")

说明：
  · Gitee raw 直链会自动 302 到 raw.giteeusercontent.com，App 会自己跟着跳，不用管
  · 公开仓库、单文件 <10MB 无需登录即可下载（我们点单端 0.7MB / 接单端 5.3MB，都达标）
  · 缓存 60~300 秒：刚上传完最多等几分钟，App 才能拉到最新
  · 每次发新版，记得两端 versionCode 一起加（build.sh 统一管，你不用手改）

EOF
elif [ "$MODE" = "local" ]; then
  blue "== 第 3 步：把 dist/ 传上去 =="
  cat <<EOF
dist/ 里有 3 个文件，三个都要传，并且放在同一个目录下、文件名不要改：

    version.json
    xiaowo-order.apk
    xiaowo-recv.apk

能用的地方（任选一个，详细步骤见 docs/auto-update.md）：
  · GitHub Pages      最简单，*.github.io 直链（本机实测最稳）
  · GitHub Releases   有版本记录，但资产会 302 跳到另一个 CDN 域名
  · 对象存储/自己的服务器   Cloudflare R2、阿里云 OSS、腾讯云 COS 都行（国内最稳）

上传方式随你：网页拖拽、scp、rclone、网盘客户端都行。

$(yell "!! 注意：必须是「点开链接就直接开始下载 APK」的直链，")
$(yell "   不能是网盘那种需要点「下载」按钮的跳转页面，App 下载不了。")

传完之后，App 的「我」→ 自动更新 → ⚙ 设置更新地址 里填：

    $(green "$MANIFEST_URL")

EOF
  if [ "$BASE_URL" = "https://换成你的域名或直链目录/xiaowo" ]; then
    yell "⚠ 上面这个地址现在是占位符。真上传完，用真实地址重跑一次："
    yell "   BASE_URL=https://你的真实地址/xiaowo bash tools/publish.sh local"
    yell "   （或者直接手改 dist/version.json 里的两个 url 字段）"
  fi
else
  blue "== 第 3 步：推到 GitHub =="
  cat <<EOF
本仓库当前状态：
EOF
  if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    yell "  ✓ 已经是一个 git 仓库"
    git -C "$ROOT" remote -v 2>/dev/null | sed 's/^/    /' || true
  else
    yell "  ! 这还不是 git 仓库，下面的命令里带了 git init"
  fi

  cat <<EOF

--- 方案 A：GitHub Pages（最简单，传完就能用）-------------------
  假设仓库是 https://github.com/USER/REPO ，把这个仓库的 Pages 打开
  （Settings → Pages → Source 选 main 分支 / root），然后：

$(green "    cd \"$ROOT\"")
$(green "    git init -b main                                   # 已是仓库就跳过")
$(green "    git remote add origin https://github.com/USER/REPO.git   # 已有就跳过")
$(green "    mkdir -p xiaowo && cp dist/* xiaowo/                # 清单和 APK 一起放")
$(green "    git add xiaowo && git commit -m \"release $VC_ORDER\"")
$(green "    git push origin main")

  等 Pages 构建完（约 1 分钟），地址就是：
    $(green "    https://USER.github.io/REPO/xiaowo/version.json")

  在 App 里填上面这个 version.json 地址即可。

--- 方案 B：GitHub Releases（推荐，有版本记录）--------------------
  在网页上 New release，Tag 填 $TAG ，把 dist/ 里两个 APK 拖进去，
  然后把 version.json 单独提交到仓库里（Release 的地址不好拼，所以清单里
  直接写死两个资产直链，用 --order-url / --recv-url 生成）：

$(green "    python3 tools/make_version_json.py --no-copy \\")
$(green "        --order-url https://github.com/USER/REPO/releases/download/$TAG/xiaowo-order.apk \\")
$(green "        --recv-url  https://github.com/USER/REPO/releases/download/$TAG/xiaowo-recv.apk \\")
$(green "        --out dist")

  然后把 dist/version.json 放到一个稳定的直链上（推荐还是走 Pages 目录，
  不要用 raw），App 里填那份 version.json 的地址。

--- 两个方案共同的坑 --------------------------------------------
$(yell "  · raw.githubusercontent.com 在国内基本连不上（本机连续 5 次探测全失败），")
$(yell "    优先用 Pages 的 *.github.io 域名")
$(yell "  · Release 资产会 302 跳到 release-assets.githubusercontent.com，")
$(yell "    这个域名国内也不太稳；网络差就直接用对象存储/自己的服务器")
$(yell "  · 一定要 https，明文 http 下载 APK 容易被拦")
$(yell "  · App 首次安装会要「安装未知应用」权限，弹窗里点去开启，回来再点检查更新")

EOF
fi

# ---------------------------------------------------------------- 6. 收尾校验

blue "== 校验 =="
python3 -m json.tool "$OUT/version.json" >/dev/null && green " ✓ version.json 是合法 JSON"
python3 "$MJ" --verify "$OUT"
echo
green "完成。要填进 App 的清单地址："
green "  $MANIFEST_URL"
echo
echo "文档： docs/auto-update.md"
