#!/bin/sh
# 对 build/app-aligned-unsigned.apk 签名，输出 out/XiaoHengTiao-<version>.apk
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV=${ENV_ROOT:-/data/local/aitools/env}
BT=$ENV/android-sdk/build-tools/36.0.0
VER=$(sed -n 's/^version=//p' $ROOT/app/src/main/xposed/module.prop)
SRC_APK=${1:-$ROOT/build/app-aligned-unsigned.apk}
mkdir -p $ROOT/out
APK=$ROOT/out/XiaoHengTiao-$VER.apk
rm -f $APK $APK.idsig
java -jar $BT/lib/apksigner.jar sign \
    --ks $ROOT/release.keystore --ks-pass pass:sideindex --key-pass pass:sideindex \
    --ks-key-alias sideindex --out $APK "$SRC_APK"
java -jar $BT/lib/apksigner.jar verify $APK >/dev/null && echo "签名校验通过：$APK"
