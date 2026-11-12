#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/deb"
VERSION="${MESHSDR_VERSION_NAME:-0.5.1}"; VERSION="${VERSION#v}"
mkdir -p "$OUT"
DESKTOP="$(find "$ROOT/desktop/build/compose/binaries/main/deb" -maxdepth 1 -name '*.deb' -print -quit)"
RECEIVER="$(find "$OUT" -maxdepth 1 -name 'meshsdr-receiver_*.deb' -print -quit)"
test -n "$DESKTOP" && test -n "$RECEIVER"
DESKTOP_PKG="$(dpkg-deb -f "$DESKTOP" Package)"
RECEIVER_PKG="$(dpkg-deb -f "$RECEIVER" Package)"
WORK="$OUT/meshsdr-monitor"
rm -rf "$WORK"
mkdir -p "$WORK/DEBIAN" "$WORK/lib/systemd/system"
cat > "$WORK/DEBIAN/control" <<EOF
Package: meshsdr-monitor
Version: $VERSION
Section: net
Priority: optional
Architecture: arm64
Depends: $DESKTOP_PKG, $RECEIVER_PKG, rtl-sdr, openjdk-17-jre
Maintainer: MeshSDR
Description: Complete MeshSDR Raspberry Pi RTL-SDR receiver and web monitor
Installs the radio decoder service and web dashboard together.
EOF
cat > "$WORK/lib/systemd/system/meshsdr-radio.service" <<'EOF'
[Unit]
Description=MeshSDR RTL-SDR MeshCore radio decoder
Wants=network-online.target meshsdr-receiver.service
After=network-online.target meshsdr-receiver.service
[Service]
Type=simple
User=meshsdr
Group=meshsdr
SupplementaryGroups=plugdev
Environment=HOME=/var/lib/meshsdr-receiver
Environment=MESHSDR_PROFILE=meshcore-nl
Environment=MESHSDR_ENDPOINT=http://127.0.0.1:9090/api/messages
EnvironmentFile=-/etc/default/meshsdr-radio
ExecStart=/opt/MeshSDR/bin/MeshSDR --server
Restart=always
RestartSec=5
UMask=0077
NoNewPrivileges=true
[Install]
WantedBy=multi-user.target
EOF
cat > "$WORK/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if [ -f /etc/meshsdr-receiver/config.yaml ]; then
 TOKEN="$(sed -n 's/^token: *//p' /etc/meshsdr-receiver/config.yaml | tr -d '"')"
 printf 'MESHSDR_TOKEN=%s\\n' "$TOKEN" > /etc/default/meshsdr-radio
 chmod 600 /etc/default/meshsdr-radio
fi
install -d -o meshsdr -g meshsdr -m 0750 /var/lib/meshsdr-receiver
usermod -a -G plugdev meshsdr || true
systemctl daemon-reload
systemctl enable meshsdr-receiver.service meshsdr-radio.service
systemctl restart meshsdr-receiver.service
systemctl restart meshsdr-radio.service
EOF
chmod 755 "$WORK/DEBIAN/postinst"
cat > "$WORK/DEBIAN/prerm" <<'EOF'
#!/bin/sh
if [ "$1" = remove ]; then
 systemctl stop meshsdr-radio.service || true
 systemctl disable meshsdr-radio.service || true
fi
EOF
chmod 755 "$WORK/DEBIAN/prerm"
cat > "$WORK/DEBIAN/postrm" <<'EOF'
#!/bin/sh
systemctl daemon-reload || true
EOF
chmod 755 "$WORK/DEBIAN/postrm"
dpkg-deb --root-owner-group --build "$WORK" "$OUT/meshsdr-monitor_${VERSION}_arm64.deb"
echo "Built $OUT/meshsdr-monitor_${VERSION}_arm64.deb"
