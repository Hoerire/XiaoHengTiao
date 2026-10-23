#!/bin/sh
# 小横条增强 —— 一体化构建脚本
#   aapt2(资源) -> javac(R) -> kotlinc -> R8(压缩/ dex) -> 打包(package.py) -> apksigner 签名
# 依赖：Android SDK build-tools 36 / android-36 platform / kotlin-compiler-embeddable 2.2.10 /
#       kotlin-stdlib / annotations / kotlinx-coroutines-core-jvm / python3
# 环境变量：
#   ENV_ROOT  工具链根目录（默认 /data/local/aitools/env，含 android-sdk 与 gradle-home 缓存）
#   PYTHON    python3 可执行文件（默认 python3）
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=$ROOT/app/src/main
OUT=$ROOT/build
ENV=${ENV_ROOT:-/data/local/aitools/env}
LIBS=$ROOT/libs
PYTHON=${PYTHON:-}
if [ -z "$PYTHON" ]; then
    for c in python3 python; do
        if command -v $c >/dev/null 2>&1; then PYTHON=$c; break; fi
    done
fi
BT=$ENV/android-sdk/build-tools/36.0.0
AAPT2=${AAPT2:-aapt2}   # chroot 里 SDK 版 aapt2 不可执行，用 PATH 里的 aapt2
AJ=$ENV/android-sdk/platforms/android-36/android.jar
KM=$ENV/gradle-home/caches/modules-2/files-2.1
KC=$(ls $KM/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.10/*/kotlin-compiler-embeddable-2.2.10.jar)
KR=$(ls $KM/org.jetbrains.kotlin/kotlin-reflect/2.2.21/*/kotlin-reflect-2.2.21.jar)
KSR=$(ls $KM/org.jetbrains.kotlin/kotlin-script-runtime/2.2.10/*/kotlin-script-runtime-2.2.10.jar)
KDE=$(ls $KM/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.10/*/kotlin-daemon-embeddable-2.2.10.jar)
STD=$(ls $KM/org.jetbrains.kotlin/kotlin-stdlib/2.2.10/*/kotlin-stdlib-2.2.10.jar)
ANN=$(ls $KM/org.jetbrains/annotations/23.0.0/*/annotations-23.0.0.jar)
COR=$(ls $KM/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/*/kotlinx-coroutines-core-jvm-1.9.0.jar)
LX=$LIBS/libxposed-api-102.jar
SVC=$LIBS/libxposed-service-102.jar
XP82=$LIBS/xposed-82.jar

# 版本号唯一来源：app/src/main/xposed/module.prop
VER=$(sed -n 's/^version=//p' $SRC/xposed/module.prop)
VC=$(sed -n 's/^versionCode=//p' $SRC/xposed/module.prop)
echo "== 版本 $VER (versionCode $VC)"

rm -rf $OUT; mkdir -p $OUT/gen $OUT/classes $OUT/dex
sed 's|<manifest |<manifest package="io.github.hoerire.sideindexx" |' $SRC/AndroidManifest.xml > $OUT/AndroidManifest.xml

echo "== aapt2 compile"
$AAPT2 compile --dir $SRC/res -o $OUT/res.zip
echo "== aapt2 link"
$AAPT2 link -o $OUT/app-res.apk -I $AJ --manifest $OUT/AndroidManifest.xml \
    --min-sdk-version 31 --target-sdk-version 36 \
    --version-code $VC --version-name $VER \
    --java $OUT/gen -R $OUT/res.zip --auto-add-overlay

echo "== javac"
javac -nowarn -encoding UTF-8 --release 17 -cp "$AJ:$STD" -d $OUT/classes $(find $OUT/gen -name '*.java')

echo "== kotlinc"
java -Xmx760m -cp "$KC:$STD:$KSR:$KR:$KDE:$ANN:$COR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -jvm-target 17 -nowarn \
    -classpath "$AJ:$STD:$LX:$SVC:$XP82:$OUT/classes" \
    -d $OUT/classes "$SRC/java" 2>&1 | grep -Ev '^warning:|deprecat' | head -60

echo "== R8"
(cd $OUT/classes && jar cf $OUT/code.jar .)
java -Xmx900m -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 31 --lib "$AJ" \
    --output $OUT/dex --pg-conf $ROOT/tools/pg.txt $OUT/code.jar "$STD" "$SVC"

if [ -z "$PYTHON" ]; then
    echo "== 没有 python3，编译产物已就绪：$OUT/dex/classes.dex 与 app-res.apk"
    echo "   请在有 python3 的环境执行打包："
    echo "     BUILD_DIR=$OUT XPOSED_DIR=$SRC/xposed python3 $ROOT/tools/package.py"
    echo "   然后执行签名：sh $ROOT/tools/sign.sh"
    exit 0
fi

echo "== 打包"
BUILD_DIR=$OUT XPOSED_DIR=$SRC/xposed $PYTHON $ROOT/tools/package.py

echo "== 签名"
sh $ROOT/tools/sign.sh
echo "== 完成：$ROOT/out/XiaoHengTiao-$VER.apk"
