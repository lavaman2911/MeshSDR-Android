#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ARCH="$(uname -m)"
if [[ "$ARCH" != "aarch64" ]]; then
  echo "Run this script on 64-bit Raspberry Pi OS (aarch64), got: $ARCH" >&2
  exit 1
fi
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk gradle cmake build-essential rtl-sdr
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
cmake -S desktop/native -B desktop/build-native -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build-native --parallel
mkdir -p desktop/resources/linux-arm64
install -m 755 desktop/build-native/libmeshsdr_lora.so desktop/resources/linux-arm64/libmeshsdr_lora.so
(
  cd desktop
  gradle clean test packageDeb
)
echo "Debian package is in desktop/build/compose/binaries/main/deb/"
