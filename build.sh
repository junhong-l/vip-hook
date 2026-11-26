#!/usr/bin/env bash
# 构建 viphook.apk（无资源 Xposed 模块）
# 依赖：JDK（javac/jar）、Android build-tools 36、platforms/android-34
set -euo pipefail
cd "$(dirname "$0")"

BT="${BT:-/opt/android-sdk/build-tools/36.0.0}"
AJ="${AJ:-/opt/android-sdk/platforms/android-34/android.jar}"
OUT="${1:-viphook.apk}"
KS="${KS:-viphook.keystore}"
KS_PASS="${KS_PASS:-viphook}"

echo "==> 1/5 编译 Java"
rm -rf build/classes build/dex build/base.apk build/aligned.apk
mkdir -p build/classes build/dex
javac --release 11 -encoding UTF-8 \
      -d build/classes \
      $(find src/stubs/java src/main/java -name '*.java')

echo "==> 2/5 生成 stub 引用 jar（仅编译期，不入包）"
jar cf build/stubs.jar -C build/classes de

echo "==> 3/5 汇编 dex（只含模块自身类）"
"$BT/d8" --min-api 21 --lib build/stubs.jar \
      --output build/dex \
      $(find build/classes/com -name '*.class')
cp build/dex/classes.dex build/classes.dex

echo "==> 4/5 打包（aapt2 link + zip）"
"$BT/aapt2" link -o build/base.apk \
      --manifest AndroidManifest.xml -I "$AJ" \
      --min-sdk-version 21 --target-sdk-version 34 \
      --version-code 2 --version-name 1.1
zip -q -X -j build/base.apk build/classes.dex
zip -q -X build/base.apk assets/xposed_init

echo "==> 5/5 对齐与签名"
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
          -alias viphook -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=viphook, O=personal" >/dev/null 2>&1
  echo "    已生成签名密钥 $KS（口令 $KS_PASS，务必保留以便覆盖升级）"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
      --v4-signing-enabled false \
      --out "$OUT" build/aligned.apk
"$BT/apksigner" verify --print-certs "$OUT" 2>/dev/null | head -2

echo "==> 完成：$OUT ($(du -h "$OUT" | cut -f1))"
echo "    dex md5: $(md5sum build/classes.dex | cut -d' ' -f1)"
