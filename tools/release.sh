#!/usr/bin/env bash
# =============================================================================
# 一键发 Release 到 GitHub
#
#   bash tools/release.sh
#
# 做的事：
#   1. 读 AndroidManifest.xml.in 里的 versionCode
#   2. 建两个 release：order-v<vc> / recv-v<vc>
#   3. 把 build/ 下的两个 APK 作为 asset 上传
#
# 需要的环境变量：GITHUB_TOKEN（有 repo 权限的 PAT）
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${GH_REPO:-Grape-LSXG/xiaowo}"
: "${GITHUB_TOKEN:?需要设置 GITHUB_TOKEN}"

VC=$(grep -o 'android:versionCode="[0-9]*"' "$ROOT/AndroidManifest.xml.in" | grep -o '[0-9]*')
VN=$(grep -o 'android:versionName="[^"]*"' "$ROOT/AndroidManifest.xml.in" | sed 's/.*="//;s/"//')
echo "版本：v$VN  (versionCode=$VC)  仓库：$REPO"

ORDER_APK="$ROOT/build/xiaowo-order.apk"
RECV_APK="$ROOT/build/xiaowo-recv.apk"
[ -f "$ORDER_APK" ] || { echo "缺 $ORDER_APK，先跑 build.sh"; exit 1; }
[ -f "$RECV_APK" ]  || { echo "缺 $RECV_APK，先跑 build.sh"; exit 1; }

create_release() {
  local tag="$1" name="$2" body="$3"
  echo "== 创建 release $tag ==" >&2
  local resp
  resp=$(curl -s -X POST "https://api.github.com/repos/$REPO/releases" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -d "{\"tag_name\":\"$tag\",\"name\":\"$name\",\"body\":\"$body\"}")
  # tag 已存在（422）时，把已有 release 的 id 取回来复用
  echo "$resp" | python3 -c "
import json,sys
d=json.load(sys.stdin)
if 'id' in d:
    print(d['id'])
else:
    print('', end='')
"
}

upload_asset() {
  local rid="$1" file="$2" name="$3"
  echo "== 上传 $name =="
  curl -s --max-time 600 -X POST \
    "https://uploads.github.com/repos/$REPO/releases/$rid/assets?name=$name" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$file" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print('  ', d.get('name'), d.get('size'), 'bytes' if 'id' in d else d)"
}

# 找到 or 创建 release，返回 id（tag 已存在时复用）
ensure_release() {
  local tag="$1" name="$2" body="$3"
  local id
  id=$(create_release "$tag" "$name" "$body")
  if [ -z "$id" ]; then
    id=$(curl -s "https://api.github.com/repos/$REPO/releases/tags/$tag" \
      -H "Authorization: Bearer $GITHUB_TOKEN" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))")
    echo "== 复用已存在的 $tag (id=$id) ==" >&2
  fi
  echo "$id"
}

RID_ORDER=$(ensure_release "order-v$VC" "点单端 v$VN" "小窝 点单端 v$VN")
[ -n "$RID_ORDER" ] || { echo "order release 创建失败"; exit 1; }
upload_asset "$RID_ORDER" "$ORDER_APK" "xiaowo-order-v$VC.apk"

RID_RECV=$(ensure_release "recv-v$VC" "接单端 v$VN" "小窝 接单端 v$VN")
[ -n "$RID_RECV" ] || { echo "recv release 创建失败"; exit 1; }
upload_asset "$RID_RECV" "$RECV_APK" "xiaowo-recv-v$VC.apk"

echo
echo "完成：https://github.com/$REPO/releases"
