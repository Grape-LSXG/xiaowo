#!/usr/bin/env bash
# =============================================================================
# 不用 Gradle / Android Studio 的 APK 构建脚本
#
#   Ubuntu 源里的 arm64 原生 aapt + zipalign，
#   配合 Android SDK 里的纯 Java 工具 d8.jar / apksigner.jar，
#   所以在这台手机上的 Ubuntu 容器里也能直接编出 APK。
#
#   用法： bash build.sh
#   产物： build/xiaowo-order.apk   build/xiaowo-recv.apk
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="$ROOT/tools/sdk"
BT="$SDK/android-14"          # build-tools 34（只用里面的 jar）
AJAR="$SDK/android-34/android.jar"
OUT="$ROOT/build"
MIN_SDK=24
TARGET_SDK=34

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
blue()  { printf '\033[36m%s\033[0m\n' "$*"; }

command -v aapt >/dev/null     || { red "缺 aapt：apt-get install aapt zipalign"; exit 1; }
command -v zipalign >/dev/null || { red "缺 zipalign：apt-get install aapt zipalign"; exit 1; }
[ -f "$AJAR" ]                 || { red "缺 android.jar: $AJAR"; exit 1; }
[ -f "$BT/lib/d8.jar" ]        || { red "缺 d8.jar: $BT/lib/d8.jar"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
  blue "== 生成调试签名 =="
  keytool -genkeypair -v -keystore "$KS" -alias xiaowo \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass xiaowo123 -keypass xiaowo123 \
    -dname "CN=XiaoWo, OU=Love, O=Home, L=Here, C=CN" >/dev/null 2>&1
fi

build_app() {
  local role="$1" pkg="$2" label="$3" isorder="$4"

  blue "== 编译 $label  ($pkg) =="
  local wd="$OUT/$role"
  mkdir -p "$wd/gen" "$wd/classes" "$wd/dex"

  # ---- 构建期常量：同一份源码，编出两个角色 ----
  cat > "$wd/gen/BuildInfo.java" <<EOF
package com.couple.xiaowo;

/** 构建期生成：同一个源码树，编出两个角色不同的 App。 */
public final class BuildInfo {
    public static final boolean IS_ORDER = $isorder;
    public static final String ROLE = "$role";
    public static final String APP_NAME = "$label";
    private BuildInfo() {}
}
EOF

  # ---- 无障碍截屏服务：只有点单端需要 ----
  # 截图方向是「接单端请求看 → 点单端的屏幕」，被截的那端才需要无障碍。
  # 接单端只负责显示收到的图，不该多背一项敏感权限。
  # 这里用 python 做多行替换（sed 的 s/// 处理多行会失控）。
  python3 - "$ROOT/AndroidManifest.xml.in" "$wd/AndroidManifest.xml" \
           "$label" "$pkg" "$role" <<'PYEOF'
import sys

src, dst, label, pkg, role = sys.argv[1:6]
text = open(src, encoding="utf-8").read()

shot_service = ""
if role == "order":
    shot_service = ('<service\n'
                    '            android:name="com.couple.xiaowo.core.ShotService"\n'
                    '            android:exported="true"\n'
                    '            android:label="' + label + '"\n'
                    '            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">\n'
                    '            <intent-filter>\n'
                    '                <action android:name="android.accessibilityservice.AccessibilityService" />\n'
                    '            </intent-filter>\n'
                    '            <meta-data\n'
                    '                android:name="android.accessibilityservice"\n'
                    '                android:resource="@xml/shot_service" />\n'
                    '        </service>')

text = (text
        .replace("@APP_LABEL@", label)
        .replace('package="com.couple.xiaowo.order"', 'package="' + pkg + '"')
        .replace("@PKG@", pkg)
        .replace("@SHOT_SERVICE@", shot_service))
open(dst, "w", encoding="utf-8").write(text)
PYEOF


  # ---- 资源 + 清单 → APK 骨架 ----
  # 注意：assets/apk/ 里的点单端 APK 只应该进【接单端】，
  # 否则点单端会把自己那份打包进自己，体积翻倍。
  local assetsDir="$ROOT/assets"
  if [ "$role" = "order" ] && [ -d "$ROOT/assets/apk" ]; then
    assetsDir="$wd/assets-role"
    mkdir -p "$assetsDir"
    # 只带 cat 图，不带 apk 目录
    if [ -d "$ROOT/assets/cats" ]; then
      cp -r "$ROOT/assets/cats" "$assetsDir/"
    fi
  fi

  # ---- 资源目录：接单端不需要无障碍配置，按角色剔掉 ----
  # res/xml/shot_service.xml 只服务于点单端的 AccessibilityService；
  # 接单端清单里没有那个 <service>，把它一起打进包只是徒增困惑。
  local resDir="$ROOT/res"
  if [ "$role" != "order" ] && [ -d "$ROOT/res/xml" ]; then
    resDir="$wd/res-role"
    mkdir -p "$resDir"
    cp -r "$ROOT/res/mipmap-"* "$resDir/" 2>/dev/null || true
  fi

  aapt package -f \
    -M "$wd/AndroidManifest.xml" \
    -S "$resDir" \
    -A "$assetsDir" \
    -I "$AJAR" \
    -F "$wd/base.apk" \
    --min-sdk-version $MIN_SDK \
    --target-sdk-version $TARGET_SDK

  # ---- javac：android.jar 当 bootclasspath，防止误用桌面 JDK 的 API ----
  find "$ROOT/src" "$wd/gen" -name '*.java' > "$wd/sources.txt"
  javac -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$AJAR" \
    -Xlint:-options -nowarn \
    -d "$wd/classes" @"$wd/sources.txt"

  # ---- class → dex ----
  find "$wd/classes" -name '*.class' > "$wd/classes.txt"
  bash "$BT/d8" --release --min-api $MIN_SDK --lib "$AJAR" \
    --output "$wd/dex" $(cat "$wd/classes.txt")

  # ---- dex 塞进 APK（aapt add 用相对路径）----
  ( cd "$wd/dex" && aapt add "$wd/base.apk" classes.dex >/dev/null )

  # ---- 接单端：把官方 frpc（arm64）打进 lib/arm64-v8a/ ----
  #   Android 只允许执行 nativeLibraryDir 里的文件，所以必须放在这里，
  #   并且清单里要开 extractNativeLibs=true 让它被解压出来。
  if [ "$role" = "recv" ]; then
    if [ ! -f "$ROOT/tools/prebuilt/frpc_arm64" ]; then
      red "缺少 tools/prebuilt/frpc_arm64（官方 frpc 0.51.0-sakura-14 arm64）"
      exit 1
    fi
    mkdir -p "$wd/libs/lib/arm64-v8a"
    cp "$ROOT/tools/prebuilt/frpc_arm64" "$wd/libs/lib/arm64-v8a/libfrpc.so"
    ( cd "$wd/libs" && aapt add "$wd/base.apk" lib/arm64-v8a/libfrpc.so >/dev/null )
  fi

  # ---- 对齐 + 签名 ----
  zipalign -f -p 4 "$wd/base.apk" "$wd/aligned.apk"
  local final="$OUT/xiaowo-$role.apk"
  rm -f "$final"
  bash "$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias xiaowo \
    --ks-pass pass:xiaowo123 --key-pass pass:xiaowo123 \
    --min-sdk-version $MIN_SDK \
    --out "$final" "$wd/aligned.apk"
  bash "$BT/apksigner" verify --min-sdk-version $MIN_SDK "$final"
  green "   ✓ $final  ($(du -h "$final" | cut -f1))"
}

build_app order com.couple.xiaowo.order "小窝·点单" true

# =============================================================================
# 把刚编好的【点单端 APK】内置进【接单端】，用于「直连通道推更新」
#
# 这样每次发版你只需要装/更新接单端，点单端的新包会跟着它走：
#   接单端启动后暴露 /apk/info 和 /apk/order，
#   点单端打开时问一句「有没有比我新的」，有就自己下载安装。
# 不依赖 Gitee、不限速、没缓存延迟。
# =============================================================================
ORDER_APK="$OUT/xiaowo-order.apk"
if [ -f "$ORDER_APK" ]; then
  blue "== 内置点单端 APK 到接单端（直连推更新）=="
  mkdir -p "$ROOT/assets/apk"
  cp "$ORDER_APK" "$ROOT/assets/apk/xiaowo-order.apk"

  # 版本信息随包附带，运行时不解析 APK 二进制
  _vc=$(aapt dump badging "$ORDER_APK" 2>/dev/null | head -1 | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
  _vn=$(aapt dump badging "$ORDER_APK" 2>/dev/null | head -1 | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
  _sz=$(stat -c%s "$ORDER_APK" 2>/dev/null || echo 0)
  printf 'versionCode=%s\nversionName=%s\nsize=%s\n' "$_vc" "$_vn" "$_sz" \
      > "$ROOT/assets/apk/order-version.properties"
  green "   内置点单端 v$_vn (vc$_vc)  $((_sz/1024))KB"
fi

build_app recv  com.couple.xiaowo.recv  "小窝·接单" false

blue "== 完成 =="
ls -lh "$OUT"/*.apk
