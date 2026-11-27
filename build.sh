#!/usr/bin/env bash
# 构建 imespoof 的 Xposed 模块 APK
#
#   ./build.sh                                        # release：无日志，伪装成 Gboard
#   LOG_ENABLED=true ./build.sh imespoof-debug.apk    # debug：写日志到 Download/IMESpoof
#   SPOOF_AS="" ./build.sh out.apk                    # 关掉伪装，只留策略兜底
#
# 依赖：JDK 21、Android build-tools 36、platforms/android-34
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BT="${BT:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
AJ="${AJ:-$(ls -d "$SDK"/platforms/android-3[0-9] 2>/dev/null | sort -V | tail -1)/android.jar}"
JDK="${JDK:-/opt/jdk21}"
OUT="${1:-imespoof-release.apk}"
KS="${KS:-imespoof.keystore}"
KSP="${KSP:-imespoof}"
SPOOF_AS="${SPOOF_AS-com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME}"
LOG_ENABLED="${LOG_ENABLED:-false}"
VERSION_NAME="${VERSION_NAME:-1.0}"

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

# JDK 路径不存在时退回 PATH 里的 javac
if [ ! -x "$JDK/bin/javac" ]; then
  JDK="$(dirname "$(dirname "$(command -v javac || true)")")"
  export JAVA_HOME="$JDK"
  export PATH="$JDK/bin:$PATH"
fi
[ -x "$JDK/bin/javac" ] || { echo "找不到 javac，请设置 JDK=/path/to/jdk"; exit 1; }
[ -n "${BT:-}" ] && [ -x "$BT/d8" ] || { echo "找不到 build-tools，请设置 BT=/path/to/build-tools"; exit 1; }
[ -f "$AJ" ] || { echo "找不到 android.jar，请设置 AJ=/path/to/android.jar"; exit 1; }

echo "==> 0/6 生成 Config（spoofAs='${SPOOF_AS}' log=${LOG_ENABLED}）"
rm -rf build
mkdir -p build/classes build/dex build/gen/com/teams/imespoof
if [ -n "$SPOOF_AS" ]; then VAL="\"$SPOOF_AS\""; else VAL="null"; fi
cat > build/gen/com/teams/imespoof/Config.java <<EOF
package com.teams.imespoof;

final class Config {
    static final String SPOOF_AS = $VAL;
    static final boolean LOG_ENABLED = $LOG_ENABLED;
}
EOF

echo "==> 1/6 编译 Java"
"$JDK/bin/javac" --release 11 -encoding UTF-8 -Xlint:-options \
      -classpath "$AJ" \
      -d build/classes \
      $(find src/stubs/java src/main/java build/gen -name '*.java')

echo "==> 2/6 生成 stub jar（仅编译期用）"
"$JDK/bin/jar" cf build/stubs.jar -C build/classes de

echo "==> 3/6 汇编 dex（只含模块自身类）"
"$BT/d8" --min-api 21 --lib "$AJ" --lib build/stubs.jar \
      --output build/dex \
      $(find build/classes/com -name '*.class')
cp build/dex/classes.dex build/classes.dex

echo "==> 4/6 编译资源（xposedscope 作用域数组 + app_name）"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "==> 5/6 打包（aapt2 link + zip）"
"$BT/aapt2" link -o build/base.apk \
      --manifest AndroidManifest.xml -I "$AJ" \
      build/res.zip \
      --min-sdk-version 21 --target-sdk-version 34 \
      --version-code 10 --version-name "$VERSION_NAME"
zip -q -X -j build/base.apk build/classes.dex
zip -q -X build/base.apk assets/xposed_init

echo "==> 6/6 对齐与签名"
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storepass "$KSP" -keypass "$KSP" \
          -alias imespoof -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=imespoof, O=personal" >/dev/null 2>&1
  echo "    已生成签名密钥 $KS（口令 $KSP）"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSP" --key-pass "pass:$KSP" \
      --v4-signing-enabled false \
      --out "$OUT" build/aligned.apk
"$BT/apksigner" verify --print-certs "$OUT" 2>/dev/null | head -2

echo "==> 完成：$OUT ($(du -h "$OUT" | cut -f1))  version=$VERSION_NAME  log=$LOG_ENABLED  spoofAs=${SPOOF_AS:-<none>}"
echo "    dex md5: $(md5sum build/classes.dex | cut -d' ' -f1)"
