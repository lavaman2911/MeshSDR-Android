# MeshSDR message export branch

This branch extends the existing Kotlin/Compose Desktop target; it does not replace the Android app or create a second LoRa decoder.

## Desktop exporter

On a MeshCore profile, CRC-verified GRP_TXT frames that the existing MeshCoreChannels decoder can authenticate are exported to `~/.config/meshsdr/messages.jsonl` and POSTed to `http://localhost:8080/api/messages`.

The exporter creates `~/.config/meshsdr/exporter.properties` on first message. Set its `token` to the same token in the receiver's config.yaml. Set `endpoint` if the receiver is elsewhere, and `hashtags` to the hashtag channels that the existing decoder should attempt (for example `#sos`). Restrict the file with `chmod 600 ~/.config/meshsdr/exporter.properties`.

**Important protocol limitation:** the current desktop code only decrypts MeshCore Public and hashtag channels (hashtag keys are derived from the channel name). Arbitrary private-channel keys/direct messages are not currently decoded by the shared implementation. The receiver can store and display a private message only after the desktop decoder has actually decoded it; it cannot bypass MeshCore encryption. Configure matching hashtag names in the app/exporter and receiver channel mapping.

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
endpoint=http://localhost:8080/api/messages
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

## Receiver

Follow [receiver/README.md](receiver/README.md). It installs a FastAPI + SQLite service on port 8080. Homarr iframe URL: `http://PI-IP:8080/`.

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
