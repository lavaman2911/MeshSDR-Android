#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${MESHSDR_VERSION_NAME:-0.5.0}"
VERSION="${VERSION#v}"
WORK="$ROOT/build/deb/meshsdr-receiver"
OUT="$ROOT/build/deb"
rm -rf "$WORK"
mkdir -p "$WORK/DEBIAN" "$WORK/opt/meshsdr-receiver" "$WORK/etc/meshsdr-receiver" "$WORK/var/lib/meshsdr-receiver" "$WORK/lib/systemd/system"
install -m 644 "$ROOT/receiver/app.py" "$WORK/opt/meshsdr-receiver/app.py"
install -m 644 "$ROOT/receiver/requirements.txt" "$WORK/opt/meshsdr-receiver/requirements.txt"
install -m 644 "$ROOT/receiver/channels.example.yaml" "$WORK/etc/meshsdr-receiver/channels.example.yaml"
install -m 644 "$ROOT/receiver/config.example.yaml" "$WORK/etc/meshsdr-receiver/config.example.yaml"
install -m 644 "$ROOT/receiver/meshsdr-receiver.service" "$WORK/lib/systemd/system/meshsdr-receiver.service"
cat > "$WORK/DEBIAN/control" <<EOF
Package: meshsdr-receiver
Version: $VERSION
Section: net
Priority: optional
Architecture: arm64
Depends: python3 (>= 3.11), python3-venv, python3-pip, adduser
Maintainer: MeshSDR
Description: MeshSDR message receiver and Homarr dashboard
 FastAPI receiver, SQLite storage, live dashboard and channel settings.
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
systemctl daemon-reload
systemctl enable meshsdr-receiver.service
systemctl restart meshsdr-receiver.service || true
EOF
chmod 755 "$WORK/DEBIAN/postinst"
cat > "$WORK/DEBIAN/prerm" <<'EOF'
#!/bin/sh
set -e
if [ "$1" = remove ]; then
 systemctl stop meshsdr-receiver.service || true
 systemctl disable meshsdr-receiver.service || true
fi
EOF
chmod 755 "$WORK/DEBIAN/prerm"
cat > "$WORK/DEBIAN/postrm" <<'EOF'
#!/bin/sh
systemctl daemon-reload || true
EOF
chmod 755 "$WORK/DEBIAN/postrm"
dpkg-deb --root-owner-group --build "$WORK" "$OUT/meshsdr-receiver_${VERSION}_arm64.deb"
echo "Built $OUT/meshsdr-receiver_${VERSION}_arm64.deb"
