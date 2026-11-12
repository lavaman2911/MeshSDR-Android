# MeshSDR receiver + Homarr dashboard

Runs on the same Raspberry Pi as the desktop MeshSDR receiver. The API accepts one JSON object per POST, persists it in SQLite and broadcasts it to browsers using Server-Sent Events.

## Install on Raspberry Pi OS 64-bit

```bash
sudo apt update
sudo apt install -y python3 python3-venv python3-pip espeak-ng alsa-utils
sudo mkdir -p /opt/meshsdr-receiver
sudo cp -a receiver/. /opt/meshsdr-receiver/
sudo useradd --system --home /opt/meshsdr-receiver --shell /usr/sbin/nologin meshsdr || true
sudo chown -R meshsdr:meshsdr /opt/meshsdr-receiver
cd /opt/meshsdr-receiver
sudo -u meshsdr python3 -m venv .venv
sudo -u meshsdr .venv/bin/pip install -r requirements.txt
sudo -u meshsdr cp config.example.yaml config.yaml
sudo -u meshsdr cp channels.example.yaml channels.yaml
TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
sudo sed -i "s/REPLACE_WITH_LONG_RANDOM_SHARED_TOKEN/$TOKEN/" config.yaml
sudo chmod 600 config.yaml channels.yaml
sudo chown meshsdr:meshsdr config.yaml channels.yaml
sudo cp meshsdr-receiver.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now meshsdr-receiver
```

Save the token printed by the command in the desktop exporter config. Do not commit either config file.

Dashboard: `http://PI-IP:8080/`; channel settings: `http://PI-IP:8080/settings`.

## Homarr iframe

1. Open your Homarr dashboard and enter edit mode.
2. Add a **Custom HTML / iframe** widget (the widget name depends on your Homarr version).
3. Set its URL/source to `http://PI-IP:8080/`.
4. Set a useful width and height (start with 3 columns × 2 rows); resize as desired.
5. If Homarr uses HTTPS, use an HTTPS reverse proxy for this service too; browsers may block mixed-content HTTP iframes.

The dashboard deliberately does not send X-Frame-Options or a restrictive frame-ancestors policy, so it can be embedded. Keep the service on a trusted LAN/Tailscale network; the dashboard is read-only but is not separately login-protected.

## Alerts

Browsers require a user gesture before sound/speech can play. Press **Enable sound** once after loading the iframe. SOS flashes until dismissed, and speaks the requested phrase using browser speech synthesis. Private messages ping; public messages are silent.

Optional Pi-side alerts: install `espeak-ng` and `alsa-utils`, edit `config.yaml` and set `server_alerts.enabled: true`. The service uses `aplay` for a generated alarm and `espeak-ng` for speech. Ensure the service account can access the Pi audio device; if not, run the receiver service as your normal desktop user or configure audio group/device permissions.

## API / test

Header: `X-MeshSDR-Token: <shared token>`. Endpoint: `POST /api/messages`. Run:

```bash
python3 test_messages.py --token 'YOUR_SHARED_TOKEN'
```

SQLite database is `messages.sqlite3`. Channel keys are never returned by the settings API. Blank key on edit preserves the existing key. Both config files are chmod 600.

## Security notes

- Bind only to a trusted LAN or reverse proxy; the dashboard currently has no user login.
- Keep the shared token and channel keys out of Git.
- A channel key in the receiver config is metadata for managing the matching MeshCore setup; the receiver cannot decrypt radio ciphertext by itself. Decryption happens in the desktop MeshSDR protocol decoder before POSTing.
