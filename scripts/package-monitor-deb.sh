#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/deb"
VERSION="${MESHSDR_VERSION_NAME:-0.5.1}"; VERSION="${VERSION#v}"
mkdir -p "$OUT"
DESKTOP="$(find "$ROOT/desktop/build/compose/binaries/main/deb" -maxdepth 1 -name '*.deb' -print -quit)"
RECEIVER="$(find "$OUT" -maxdepth 1 -name 'meshsdr-receiver_*.deb' -print -quit)"
test -n "$DESKTOP" && test -n "$RECEIVER"
DESKTOP_DEPS="$(dpkg-deb -f "$DESKTOP" Depends || true)"
RECEIVER_DEPS="$(dpkg-deb -f "$RECEIVER" Depends || true)"
# The desktop package is built on Ubuntu 24.04 (t64 transition). Keep its
# runtime SONAME dependencies compatible with Raspberry Pi OS Debian 12.
DESKTOP_DEPS="$(printf '%s' "$DESKTOP_DEPS" | sed -e 's/libasound2t64/libasound2/g' -e 's/libpng16-16t64/libpng16-16/g')"
WORK="$OUT/meshsdr-monitor"
rm -rf "$WORK"
mkdir -p "$WORK/DEBIAN" "$WORK/lib/systemd/system"
# This is a real combined package: both application payloads are inside this .deb.
dpkg-deb -x "$DESKTOP" "$WORK"
dpkg-deb -x "$RECEIVER" "$WORK"
cat > "$WORK/DEBIAN/control" <<EOF
Package: meshsdr-monitor
Version: $VERSION
Section: net
Priority: optional
Architecture: arm64
Conflicts: meshsdr-receiver
Replaces: meshsdr-receiver
Depends: $DESKTOP_DEPS, $RECEIVER_DEPS, rtl-sdr
Maintainer: MeshSDR
Description: MeshSDR Raspberry Pi RTL-SDR receiver and live web monitor
 Includes the native LoRa decoder, MeshCore message exporter, RTL-SDR service, FastAPI receiver and HTML dashboard.
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
getent passwd meshsdr >/dev/null || adduser --system --group --home /var/lib/meshsdr-receiver --no-create-home meshsdr
install -d -o meshsdr -g meshsdr -m 0750 /var/lib/meshsdr-receiver
install -d -o root -g meshsdr -m 0750 /etc/meshsdr-receiver
if [ ! -f /etc/meshsdr-receiver/config.yaml ]; then
 install -o root -g meshsdr -m 0640 /etc/meshsdr-receiver/config.example.yaml /etc/meshsdr-receiver/config.yaml
 TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
 sed -i "s/REPLACE_WITH_LONG_RANDOM_SHARED_TOKEN/$TOKEN/" /etc/meshsdr-receiver/config.yaml
fi
if [ ! -f /etc/meshsdr-receiver/channels.yaml ]; then
 install -o root -g meshsdr -m 0640 /etc/meshsdr-receiver/channels.example.yaml /etc/meshsdr-receiver/channels.yaml
fi
python3 -m venv /opt/meshsdr-receiver/.venv
/opt/meshsdr-receiver/.venv/bin/pip install --disable-pip-version-check -r /opt/meshsdr-receiver/requirements.txt
chown -R root:root /opt/meshsdr-receiver
chown meshsdr:meshsdr /etc/meshsdr-receiver/config.yaml /etc/meshsdr-receiver/channels.yaml
chmod 600 /etc/meshsdr-receiver/config.yaml /etc/meshsdr-receiver/channels.yaml
TOKEN="$(sed -n 's/^token: *//p' /etc/meshsdr-receiver/config.yaml | tr -d '"')"
printf 'MESHSDR_TOKEN=%s\n' "$TOKEN" > /etc/default/meshsdr-radio
chmod 600 /etc/default/meshsdr-radio
chown root:root /etc/default/meshsdr-radio
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
 systemctl stop meshsdr-radio.service meshsdr-receiver.service || true
 systemctl disable meshsdr-radio.service meshsdr-receiver.service || true
fi
EOF
chmod 755 "$WORK/DEBIAN/prerm"
cat > "$WORK/DEBIAN/postrm" <<'EOF'
#!/bin/sh
systemctl daemon-reload || true
EOF
chmod 755 "$WORK/DEBIAN/postrm"
dpkg-deb --root-owner-group --build "$WORK" "$OUT/meshsdr-monitor_${VERSION}_arm64.deb"
echo "Built self-contained $OUT/meshsdr-monitor_${VERSION}_arm64.deb"
