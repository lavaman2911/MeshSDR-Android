# MeshSDR receiver + Homarr dashboard

Install the ARM64 Debian package produced by GitHub Actions:

```bash
sudo apt update
sudo apt install ./meshsdr-receiver_*_arm64.deb
```

The package creates a dedicated `meshsdr` account, installs a Python virtual environment and starts a systemd service on **TCP port 9090**. On first install it generates a random API token and creates protected channel configuration files.

- Dashboard: `http://PI-IP:9090/`
- Channel settings: `http://PI-IP:9090/settings`
- Health check: `http://PI-IP:9090/health`
- Message API: `POST http://PI-IP:9090/api/messages`
- API authentication header: `X-MeshSDR-Token: <token>`

The token is stored in `/etc/meshsdr-receiver/config.yaml`; the channel list is `/etc/meshsdr-receiver/channels.yaml`. Both are mode 600. The SQLite database lives under `/var/lib/meshsdr-receiver/`.

Read the shared token using:

```bash
sudo grep '^token:' /etc/meshsdr-receiver/config.yaml
```

For Homarr, add an iframe/custom website widget with URL `http://PI-IP:9090/`. If Homarr uses HTTPS, use an HTTPS reverse proxy for this service too to avoid mixed-content blocking. The app does not send X-Frame-Options; restrict access to your trusted LAN/Tailscale network because the dashboard is read-only and does not have a separate login.

## Browser alerts

Press **Enable sound** once in the dashboard. Browsers require a user gesture before playing audio. SOS messages flash until dismissed, play an alarm and speak "SOS MESSAGE INCOMING READ NOW"; private messages ping; public messages are silent.

Optional Pi-side alert mode is controlled by `server_alerts.enabled` in `/etc/meshsdr-receiver/config.yaml`. Install `espeak-ng` and `alsa-utils` and ensure the service account has audio device permissions before enabling it.

## Test messages

```bash
python3 test_messages.py --url http://PI-IP:9090/api/messages --token 'YOUR_SHARED_TOKEN'
```

The test script sends fake #public, #sos and private chat messages.
