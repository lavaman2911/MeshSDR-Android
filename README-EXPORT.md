# MeshSDR message export branch

This branch extends the existing Kotlin/Compose Desktop target; it does not replace the Android app or create a second LoRa decoder.

## Desktop exporter

On a MeshCore profile, CRC-verified GRP_TXT frames that the existing MeshCoreChannels decoder can authenticate are exported to `~/.config/meshsdr/messages.jsonl` and POSTed to `http://localhost:9090/api/messages`.

The exporter creates `~/.config/meshsdr/exporter.properties` on first message. Set its `token` to the same token in the receiver's config.yaml. Set `endpoint` if the receiver is elsewhere, and `hashtags` to the hashtag channels that the existing decoder should attempt (for example `#sos`). Restrict the file with `chmod 600 ~/.config/meshsdr/exporter.properties`.

**Important protocol limitation:** the current desktop code only decrypts MeshCore Public and hashtag channels (hashtag keys are derived from the channel name). Arbitrary private-channel keys/direct messages are not currently decoded by the shared implementation. The receiver can store and display a private message only after the desktop decoder has actually decoded it; it cannot bypass MeshCore encryption. Configure matching hashtag names in the app/exporter and receiver channel mapping.

## Install the GitHub Actions Debian packages

Every push to this branch builds two ARM64 Debian packages on a GitHub-hosted ARM64 runner:

- `meshsdr-receiver_*_arm64.deb`: FastAPI + SQLite receiver and web dashboard on TCP 9090.
- `meshsdr_*_arm64.deb` (exact filename is shown in the Actions artifact): MeshSDR desktop app with the native ARM64 decoder.

1. Open [GitHub Actions](https://github.com/lavaman2911/MeshSDR-Android/actions/workflows/export-messages-arm64.yml).
2. Open the newest successful **MeshSDR ARM64 Debian packages** run.
3. Download its `meshsdr-arm64-deb` artifact and extract the ZIP.
4. Copy both `.deb` files to the Raspberry Pi and install:

```bash
sudo apt update
sudo apt install ./meshsdr-receiver_*_arm64.deb ./meshsdr_*_arm64.deb
```

The receiver package starts its systemd service automatically. Open `http://PI-IP:9090/` from a browser. Its first-install API token is generated automatically; read it with:

```bash
sudo grep '^token:' /etc/meshsdr-receiver/config.yaml
```

Paste that token into `~/.config/meshsdr/exporter.properties` as `token=...`, then set `endpoint=http://localhost:9090/api/messages`. Restart the desktop exporter after changing the file.

The desktop Debian package is the GUI app. For unattended headless radio capture, install the service template:

```bash
sudo cp desktop/meshsdr-exporter.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now meshsdr-exporter@$USER
```

The systemd unit runs `/opt/MeshSDR/bin/MeshSDR --server`; verify that path against the installed package if your Compose packaging version changes its install prefix.

## Build for Raspberry Pi 5

Run on Raspberry Pi OS 64-bit, where `uname -m` is `aarch64`:

```bash
git clone https://github.com/lavaman2911/MeshSDR-Android.git
cd MeshSDR-Android
git fetch origin
git switch export-messages
chmod +x desktop/build-arm64.sh
./desktop/build-arm64.sh
```

Install the generated `.deb` from `desktop/build/compose/binaries/main/deb/`:

```bash
sudo apt install ./desktop/build/compose/binaries/main/deb/*.deb
sudo apt install rtl-sdr
```

For headless startup, copy the service and enable it for your Linux user:

```bash
sudo cp desktop/meshsdr-exporter.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now meshsdr-exporter@$USER
journalctl -u meshsdr-exporter@$USER -f
```

The desktop app uses RTL-SDR via USB and starts local `rtl_tcp` in its default configuration. This is not a direct USB serial/Bluetooth MeshCore modem connection. Choose a remote RTL-SDR TCP endpoint through the existing desktop receiver settings if required.

## Radio and exporter configuration

On first launch, the desktop app creates `~/.config/meshsdr/exporter.properties` with owner-only read/write permissions. Stop the desktop service before editing it:

```properties
endpoint=http://localhost:9090/api/messages
token=PASTE_THE_RECEIVER_TOKEN_HERE
hashtags=#sos
rtl_tcp_host=127.0.0.1
rtl_tcp_port=1234
rtl_tcp_device=0
auto_start_rtl_tcp=true
```

- `rtl_tcp_device`: local RTL-SDR device index passed to `rtl_tcp -d`.
- `rtl_tcp_port`: local rtl_tcp listen port, or remote port when auto-start is false.
- `rtl_tcp_host`: remote rtl_tcp host when `auto_start_rtl_tcp=false`.
- `hashtags`: comma-separated MeshCore hashtag channels, e.g. `#sos,#alerts`.
- `endpoint` and `token`: receiver POST destination and shared token.

After editing, run `chmod 600 ~/.config/meshsdr/exporter.properties` and restart the service. For a remote rtl_tcp server, set `auto_start_rtl_tcp=false` and set host/port accordingly.

## Receiver Debian package

GitHub Actions builds a separate `meshsdr-receiver_*_arm64.deb` package. Install it on Raspberry Pi OS with `sudo apt install ./meshsdr-receiver_*_arm64.deb`. The dashboard is available at `http://PI-IP:9090/`; its API is `http://PI-IP:9090/api/messages`. The package generates a random token on first install; read it with `sudo grep '^token:' /etc/meshsdr-receiver/config.yaml` and copy it into the desktop exporter configuration.

## Receiver

Follow [receiver/README.md](receiver/README.md). It installs a FastAPI + SQLite service on port 9090. Homarr iframe URL: `http://PI-IP:9090/`.

## Smoke test

```bash
python3 receiver/test_messages.py --token 'YOUR_SHARED_TOKEN'
```

This sends public, SOS and private-chat demo events. Press **Enable sound** in the browser first to allow audio and speech.

## Assumptions

- The radio is an RTL-SDR compatible dongle, as required by the existing MeshSDR receiver.
- `#sos` is configured as a MeshCore hashtag channel so the existing decoder can derive its key.
- The receiver's private chat panel is a display route, not a mechanism for decrypting private radio traffic.
- The receiver is initially intended for a trusted local network; add authentication/reverse proxy before exposing it outside that network.
