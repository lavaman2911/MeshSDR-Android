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
sudo apt-get install -y openjdk-17-jdk cmake build-essential rtl-sdr curl unzip
export JAVA_HOME="\${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
GRADLE_VERSION=8.11.1
GRADLE_HOME="$HOME/.local/opt/gradle-$GRADLE_VERSION"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  mkdir -p "$HOME/.local/opt"
  TMP="$(mktemp -d)"
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TMP/gradle.zip"
  unzip -q "$TMP/gradle.zip" -d "$HOME/.local/opt"
  rm -rf "$TMP"
fi
cmake -S desktop/native -B desktop/build-native -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build-native --parallel 2
mkdir -p desktop/resources/linux-arm64
install -m 755 desktop/build-native/libmeshsdr_lora.so desktop/resources/linux-arm64/libmeshsdr_lora.so
(
  cd desktop
  "$GRADLE_HOME/bin/gradle" clean test packageDeb
)
echo "Debian package is in desktop/build/compose/binaries/main/deb/"
