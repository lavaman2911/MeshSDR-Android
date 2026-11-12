# MeshSDR MeshCore Message Exporter & Web Monitor

> Documentation for the `export-messages` branch of [lavaman2911/MeshSDR-Android](https://github.com/lavaman2911/MeshSDR-Android).
>
> This document describes the intended data flow, components, installation, configuration, API, operation, security model, and current limitations. The exporter reuses the project's existing radio and MeshCore decoding code; it is not a replacement decoder.

## 1. What this project does

This branch adds a message-export path to the existing MeshSDR desktop application and a separate web service that receives, stores, and displays decoded messages.

In practical terms, it is intended to:

1. Receive LoRa radio frames through an RTL-SDR-compatible receiver supported by MeshSDR.
2. Pass frames through the existing MeshSDR/MeshCore packet parsing and channel-decryption implementation.
3. Export messages only when the existing decoder successfully parses and decrypts a supported message.
4. Append each exported message as one JSON record to a local JSON Lines file.
5. Send the same message to a receiver service over HTTP, authenticated with a shared token.
6. Store received messages in an SQLite database.
7. Show messages in a browser dashboard, separated into **Public**, **SOS**, and **Private Chat** panels.
8. Stream new messages to connected browser dashboards without requiring a page refresh.
9. Play browser-side alert sounds and speech after a user enables audio in the browser.

The receiver can also be embedded in a Homarr iframe/custom website tile.

### What it does not do

- It does not magically decode encrypted traffic for which the desktop decoder has no key or implementation.
- It does not turn an RTL-SDR into a MeshCore radio modem. The radio path is still RF reception through the existing SDR stack.
- It does not guarantee message delivery when the receiver is offline. The desktop writes a local JSONL copy, but automatic replay/retry of old JSONL records is not implemented by this exporter.
- It is not a full MeshCore node, repeater, transmitter, or message-sending client.
- It is not a secure public-facing hosted service by default. The dashboard and some configuration endpoints are intended for a trusted LAN/Tailscale environment.

## 2. High-level architecture

```text
MeshCore / LoRa radio traffic
          |
          v
RTL-SDR compatible USB receiver
          |
          v
Existing MeshSDR radio + packet decoder
          |
          v
MeshCore packet validation / channel decryption
          |
          | decoded supported message only
          v
Desktop MessageExporter
          |
          +----> ~/.config/meshsdr/messages.jsonl
          |
          +---- HTTP POST /api/messages
                    X-MeshSDR-Token
                    |
                    v
             FastAPI receiver (TCP 9090)
                    |
                    +----> SQLite message database
                    |
                    +----> Server-Sent Events stream
                    |             |
                    |             v
                    |       Browser dashboard
                    |       Public / SOS / Private
                    |
                    +----> Optional server-side SOS alert
```

There are two separately installable components:

| Component | Purpose | Typical host |
|---|---|---|
| MeshSDR desktop app/exporter | Radio reception, decoding, local export, HTTP forwarding | Raspberry Pi 5 or another supported Linux desktop |
| MeshSDR receiver/dashboard | HTTP API, message storage, browser UI and alerts | Raspberry Pi 5, home server, or another Linux ARM64 host |

They can run on the same Raspberry Pi. In that case, the desktop exporter normally sends to `http://localhost:9090/api/messages`.

## 3. Message decoding and channel support

The exporter hooks into the existing MeshCore decoding implementation. It currently looks for parsed packets with the `GRP_TXT` payload type, then asks the existing `MeshCoreChannels` implementation to decrypt the payload.

A message is exported only if those steps succeed and the decoded body is non-empty. The exporter does not attempt to guess plaintext or bypass encryption.

### Current channel support and limitations

- **Public:** supported when the existing decoder recognises and decrypts the public channel.
- **Hashtag channels:** supported only for channels the shared implementation can derive/decrypt. Configure the channel names in the desktop `hashtags` setting; examples are `#sos` and `#alerts`.
- **Arbitrary private channels and direct messages:** not generally decoded by this implementation. The receiver's Private Chat panel is only a display category for a message that has already arrived decoded. It does not decrypt private radio traffic itself.
- **SOS:** SOS is a channel/display classification and alert behaviour. It is not a separate radio protocol or a guarantee that every emergency message will be detected.

The desktop exporter currently constructs the sender/body from the decoded text using the first colon as the separator. If the decoded payload does not follow that format, the displayed sender/body split may not match the sender's intended formatting.

The receiver's channel list controls how an already received `channel_name` is classified for display and alerts. It does not supply cryptographic keys to the desktop decoder.

## 4. Desktop exporter

### Files and responsibilities

- `desktop/src/main/kotlin/com/engperini/meshsdr/desktop/MessageExporter.kt`: parses supported decoded packets, builds message records, writes JSONL, and posts records to the receiver.
- `desktop/src/main/kotlin/com/engperini/meshsdr/desktop/RadioConfig.kt`: reads radio/exporter settings from the user's configuration directory.
- `desktop/build-arm64.sh`: Raspberry Pi/Linux ARM64 build helper.
- `desktop/meshsdr-exporter.service`: systemd service template for a headless/server-style launch, where supported by the packaged application.

### Local files

The desktop application uses:

| Path | Purpose |
|---|---|
| `~/.config/meshsdr/exporter.properties` | Receiver URL, shared token, hashtag names and RTL-TCP settings |
| `~/.config/meshsdr/messages.jsonl` | Append-only local JSON Lines export |

The configuration file is created on first launch if it does not exist. The application attempts to restrict its permissions to the current user. You should verify the permissions yourself with `chmod 600`.

### Example desktop configuration

```properties
endpoint=http://localhost:9090/api/messages
token=PASTE_THE_RECEIVER_TOKEN_HERE
hashtags=#sos
rtl_tcp_host=127.0.0.1
rtl_tcp_port=1234
rtl_tcp_device=0
auto_start_rtl_tcp=true
```

| Setting | Meaning |
|---|---|
| `endpoint` | Full HTTP URL to the receiver's message POST endpoint |
| `token` | Shared secret; must match the receiver configuration |
| `hashtags` | Comma-separated hashtag channel names the decoder should attempt, e.g. `#sos,#alerts` |
| `rtl_tcp_host` | RTL-TCP host when connecting to an already running RTL-TCP server |
| `rtl_tcp_port` | RTL-TCP TCP port; default is 1234 |
| `rtl_tcp_device` | Local RTL-SDR device index passed to `rtl_tcp -d` |
| `auto_start_rtl_tcp` | Whether the application should start its local RTL-TCP process |

Protect the file after editing:

```bash
chmod 600 ~/.config/meshsdr/exporter.properties
```

If the receiver runs on another machine, change `endpoint` to that machine's reachable address, for example `http://192.168.2.30:9090/api/messages`. Do not use `localhost` for a remote receiver: localhost always refers to the machine running the desktop exporter.

### RTL-SDR connection model

The radio path uses the existing MeshSDR receiver implementation and RTL-SDR-compatible hardware. The documented default uses a local `rtl_tcp` process. With `auto_start_rtl_tcp=false`, the host and port refer to an already running RTL-TCP endpoint.

This is not a USB serial, Bluetooth, or direct MeshCore modem connection. The exact SDR tuning, sample-rate, gain, and device compatibility remain subject to the existing MeshSDR receiver implementation and its configuration.

## 5. Receiver and web dashboard

The receiver is a Python FastAPI application backed by SQLite.

### Main features

- Receives JSON messages over an authenticated HTTP POST endpoint.
- Validates required fields and ISO-8601 timestamps.
- Uses the message ID as a database primary key; duplicate IDs are ignored by SQLite.
- Stores the message timestamp, channel, channel index, sender, text, optional RSSI/SNR, and receiver timestamp.
- Provides a recent-message API.
- Streams new messages to browser clients using Server-Sent Events (SSE).
- Displays Public, SOS, and Private Chat panels.
- Supports a channel classification/settings page.
- Provides browser-side sound and speech alerts after the user presses **Enable sound**.
- Can optionally run a server-side SOS sound/TTS alert when configured.

### Web pages and endpoints

| URL / method | Purpose |
|---|---|
| `GET /` | Main live message dashboard |
| `GET /settings` | Channel display/alert settings page |
| `GET /health` | Health check; returns an `ok` status |
| `POST /api/messages` | Receive one decoded message; requires token header |
| `GET /api/messages` | Retrieve recent messages; optional `limit` query parameter |
| `GET /api/events` | Live Server-Sent Events stream |
| `GET /api/channels` | List configured channel display mappings |
| `POST /api/channels` | Replace channel display mappings |

Default base URL: `http://PI-IP:9090/`

Replace `PI-IP` with the receiver's LAN or Tailscale IP address.

### Dashboard behaviour

- **Public:** ordinary public-channel messages.
- **SOS:** messages classified as SOS. The panel flashes until the alert is dismissed.
- **Private Chat:** messages classified as private chat by the receiver's channel mapping.
- **Enable sound:** must be pressed by a user in each browser session where audio is required. Modern browsers block automatic audio until the user interacts with the page.
- **Dismiss SOS alert:** stops the visual flashing state for the current alert display.
- **Channels:** opens the channel settings page.

Browser speech/audio is generated by the dashboard in the browser. Optional server-side alerts are separate and require audio tools and device permissions on the Linux host.

### Homarr

Add a website/iframe/custom tile in Homarr with:

```text
http://PI-IP:9090/
```

If Homarr itself is served over HTTPS, browsers may block an HTTP iframe as mixed content. In that case, put the receiver behind an HTTPS reverse proxy and use the HTTPS URL in Homarr.

## 6. Receiver configuration and storage

The Debian package installs the receiver under `/opt/meshsdr-receiver` and uses these configuration/data paths:

| Path | Purpose |
|---|---|
| `/etc/meshsdr-receiver/config.yaml` | Receiver settings and shared API token |
| `/etc/meshsdr-receiver/channels.yaml` | Channel names and display/alert mappings |
| `/var/lib/meshsdr-receiver/messages.sqlite3` | SQLite message database |
| `/opt/meshsdr-receiver` | Receiver application files and Python virtual environment |
| `/etc/systemd/system/` (installed unit path may be package-managed) | Service definition |

The package is designed to create a dedicated Linux service account named `meshsdr`, create its Python virtual environment, install Python dependencies, and start the systemd service.

### Example receiver configuration

```yaml
token: REPLACE_WITH_LONG_RANDOM_SHARED_TOKEN
database: /var/lib/meshsdr-receiver/messages.sqlite3
channels_file: /etc/meshsdr-receiver/channels.yaml
host: 0.0.0.0
port: 9090
server_alerts:
  enabled: false
  command: aplay
  tts_command: espeak-ng
  tts_text: SOS MESSAGE INCOMING READ NOW
```

The service definition currently starts Uvicorn on port 9090. Treat that service command as authoritative for the packaged service; changing only the YAML `host` or `port` values may not change the actual listening address/port.

### Channel mapping example

```yaml
channels:
  - name: "#public"
    key: ""
    display_panel: PUBLIC
    alert_type: none
  - name: "#sos"
    key: ""
    display_panel: SOS
    alert_type: sos-alarm
  - name: "private chat"
    key: ""
    display_panel: "PRIVATE CHAT"
    alert_type: ping
```

- `name`: channel name to match against the incoming message's `channel_name`.
- `display_panel`: one of `PUBLIC`, `SOS`, or `PRIVATE CHAT`.
- `alert_type`: one of `none`, `ping`, or `sos-alarm`.
- `key`: currently retained as configuration data, but the receiver does not use it to decrypt radio messages.

The receiver also recognises simple aliases such as `Public`, `SOS`, and `private` for display classification. A custom channel mapping is matched by channel name.

## 7. Message API format

The desktop sends one JSON object per decoded message to:

```http
POST /api/messages
Content-Type: application/json
X-MeshSDR-Token: YOUR_SHARED_TOKEN
```

Example request body:

```json
{
  "id": "unique-message-id",
  "timestamp": "2026-11-12T20:15:00Z",
  "channel_name": "#sos",
  "channel_index": 1,
  "sender": "ExampleNode",
  "text": "Example decoded message",
  "rssi": null,
  "snr": 8.5
}
```

Required fields:

- `id`: stable message identifier.
- `timestamp`: ISO-8601 timestamp.
- `channel_name`: decoded channel name.
- `sender`: sender label.
- `text`: decoded message body.

Optional fields:

- `channel_index`: numeric channel index; defaults to 0.
- `rssi`: received signal strength; may be null.
- `snr`: signal-to-noise ratio; may be null.

The receiver requires a valid shared token in the `X-MeshSDR-Token` header. Missing or incorrect tokens receive HTTP 401. Missing required message fields or an invalid timestamp receive HTTP 422. A successful insert (or duplicate-ID no-op) returns an `ok` response with the message ID.

The recent-message endpoint returns stored messages, with a default limit of 100 and a maximum limit of 500. The SSE endpoint sends newly received message events to connected browsers.

## 8. Install on Raspberry Pi 5 (64-bit)

The prebuilt packages are intended for ARM64 Linux. On the Pi, confirm the architecture:

```bash
uname -m
dpkg --print-architecture
```

Expected results are `aarch64` and `arm64`.

### Option A — install the GitHub Actions packages

1. Open the [ARM64 package workflow](https://github.com/lavaman2911/MeshSDR-Android/actions/workflows/export-messages-arm64.yml).
2. Open the newest run with a successful conclusion.
3. Download the `meshsdr-arm64-deb` artifact and extract the ZIP.
4. Copy the relevant `.deb` files to the Pi.
5. Install the receiver package and, if you want the desktop radio/exporter on this Pi, the desktop package too.

Example (run from the directory containing the downloaded packages):

```bash
sudo apt update
sudo apt install ./meshsdr-receiver_*_arm64.deb
```

Install the desktop package separately using its exact filename from the artifact:

```bash
sudo apt install ./EXACT-DESKTOP-PACKAGE-NAME.deb
```

Do not install a package built for a different architecture. The workflow artifact is a ZIP containing the Debian packages; extract it before running `apt install`.

### First-run token setup

The receiver package generates a random token on first install. Read it on the Pi:

```bash
sudo grep '^token:' /etc/meshsdr-receiver/config.yaml
```

Copy the value (without surrounding YAML quotes, if any) into the desktop user's configuration:

```bash
nano ~/.config/meshsdr/exporter.properties
```

Set:

```properties
endpoint=http://localhost:9090/api/messages
token=PASTE_THE_RECEIVER_TOKEN_HERE
```

If desktop and receiver run on different machines, replace `localhost` with the receiver's LAN/Tailscale address. Protect the file and restart the desktop application/service after changing it.

### Verify the receiver

Open:

```text
http://PI-IP:9090/health
http://PI-IP:9090/
```

The health endpoint should return a small JSON object indicating the service is healthy. The dashboard should load in a browser.

### Option B — build directly on Raspberry Pi OS 64-bit

The repository contains a build helper:

```bash
sudo apt update
sudo apt install -y git build-essential cmake pkg-config python3 python3-venv python3-pip rtl-sdr
git clone https://github.com/lavaman2911/MeshSDR-Android.git
cd MeshSDR-Android
git fetch origin
git switch export-messages
chmod +x desktop/build-arm64.sh
./desktop/build-arm64.sh
```

Install the generated desktop package from the build output directory indicated by the script. The receiver package can be built separately with:

```bash
bash receiver/package-deb.sh
```

Build dependencies and exact package output paths can change as the build scripts evolve; use the output reported by the scripts rather than assuming a filename.

## 9. Start, stop, and inspect services

The receiver systemd service is named `meshsdr-receiver`:

```bash
sudo systemctl status meshsdr-receiver
sudo systemctl restart meshsdr-receiver
sudo systemctl stop meshsdr-receiver
sudo systemctl start meshsdr-receiver
sudo journalctl -u meshsdr-receiver -n 100 --no-pager
sudo journalctl -u meshsdr-receiver -f
```

For the optional headless desktop exporter service, the repository includes a systemd unit template. Its instance name and executable path must match the installed desktop package and Linux user. Inspect the unit before enabling it:

```bash
cat desktop/meshsdr-exporter.service
```

The documented template uses a per-user instance. If using it, install the unit, reload systemd, enable the instance for the intended user, and inspect its logs:

```bash
sudo cp desktop/meshsdr-exporter.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now meshsdr-exporter@YOUR_LINUX_USERNAME
sudo journalctl -u meshsdr-exporter@YOUR_LINUX_USERNAME -f
```

Replace `YOUR_LINUX_USERNAME` with the account that owns the desktop configuration and RTL-SDR access. Do not blindly run the service as root: the exporter reads that user's configuration file and needs access to the radio device.

## 10. Test with sample messages

A test helper sends demonstration messages to the receiver. It does not transmit anything over LoRa.

From a repository checkout:

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install requests
python3 receiver/test_messages.py --url http://PI-IP:9090/api/messages --token 'YOUR_SHARED_TOKEN'
```

Then open the dashboard and press **Enable sound**. You should see sample Public, SOS, and Private Chat entries. These are synthetic test messages and must not be mistaken for actual radio traffic.

## 11. Alerts and text-to-speech

### Browser alerts

Browser audio is disabled until a user presses **Enable sound**. This is a browser autoplay restriction, not a receiver failure.

The dashboard classifies incoming messages and applies the configured alert behaviour:

- Public messages: no alert by default.
- SOS messages: visual flashing and SOS sound/speech behaviour.
- Private Chat messages: short notification/ping behaviour.

Browser audio and speech are generated on the client. The browser must remain open, and the page must be allowed to play audio.

### Optional server-side alert

The receiver can optionally run a sound command and a TTS command on the Linux host when an SOS event is received. This is disabled by default.

To enable it, set `server_alerts.enabled: true` in `/etc/meshsdr-receiver/config.yaml`. Install the relevant utilities (for example `alsa-utils` for `aplay` and `espeak-ng` for speech), and ensure the `meshsdr` service account has access to the intended audio output. Restart the receiver service after changing the config.

Server-side audio depends on the Pi's audio hardware, active audio session, permissions, and output routing. It is separate from audio played in the web browser.

## 12. Security, privacy, and network exposure

Treat the receiver as a trusted-network service.

- The message POST endpoint is protected by the shared `X-MeshSDR-Token`.
- The dashboard and read endpoints do not have a separate user login.
- The channel settings endpoints currently do not have separate authentication. Anyone who can reach the service may be able to view messages and alter channel display mappings.
- The dashboard may contain message content and sender identifiers. Do not expose it directly to the public internet.
- Keep the receiver behind a firewall and restrict access to your LAN or private overlay network such as Tailscale.
- If remote access is required, use a properly configured HTTPS reverse proxy with its own access control. Do not rely on the shared ingestion token as dashboard login.
- Keep the desktop `exporter.properties` and receiver `config.yaml` private. Do not commit real tokens, private channel keys, or message databases to Git.
- HTTP on a local trusted network is not encrypted. Use HTTPS when sending messages across networks you do not trust.
- The SQLite database and JSONL file contain message content. Protect backups and filesystem permissions accordingly.

The receiver's `channels.yaml` `key` field is not a substitute for cryptographic key management in the desktop decoder.

## 13. Troubleshooting

### Dashboard does not open

On the Pi:

```bash
sudo systemctl status meshsdr-receiver
sudo journalctl -u meshsdr-receiver -n 100 --no-pager
sudo ss -ltnp | grep 9090
curl -i http://127.0.0.1:9090/health
```

Check that TCP port 9090 is listening and that the client can reach the Pi's IP address. Check host firewall rules and Wi-Fi/LAN isolation if local curl works but another device cannot connect.

### Desktop messages are not appearing

1. Confirm the receiver health endpoint works.
2. Confirm the desktop `endpoint` points to the receiver's correct address.
3. Confirm the desktop token exactly matches the receiver token.
4. Check the desktop application's logs for `MeshSDR exporter POST failed`.
5. Check that the radio is receiving frames and the existing decoder is actually decoding supported MeshCore messages.
6. Check that the channel name maps to the panel you expect.

### HTTP 401

The shared token is missing or does not match. Re-read the receiver token and update the desktop configuration. Keep the token private.

### HTTP 422

The submitted JSON is missing a required field or has a timestamp that is not ISO-8601. Check the desktop/receiver versions and logs.

### No messages in a private channel

The receiver cannot decrypt radio traffic. Confirm that the existing desktop MeshCore decoder supports the channel and has the necessary key material. The current exporter does not implement arbitrary private-channel decryption.

### No browser sound or speech

Open the dashboard and press **Enable sound**. Check the browser tab is not muted and the device's output is correct. Browser audio will not play before the user gesture.

### Receiver-side audio does not work

Check that `server_alerts.enabled` is true, the required commands are installed, and the service account can access the audio device. Inspect:

```bash
sudo journalctl -u meshsdr-receiver -n 100 --no-pager
```

### RTL-SDR is not found

Check the USB device and RTL-SDR tools:

```bash
lsusb
rtl_test -t
```

Confirm the correct device index in `rtl_tcp_device`, that no other process is holding the dongle, and that the service user has the required USB device permissions.

### Message appears in JSONL but not in the dashboard

The local JSONL append occurs before the HTTP POST. This means the message can be present in `~/.config/meshsdr/messages.jsonl` even if the receiver POST failed. Check the endpoint, token, network connection, and desktop logs. The current exporter does not automatically replay old JSONL entries.

## 14. Build and CI workflow

The GitHub Actions workflow is:

`.github/workflows/export-messages-arm64.yml`

It runs on pushes and pull requests targeting `export-messages`, and can also be started manually from the Actions page.

The workflow is configured to:

1. Check out the branch.
2. Set up Java and Gradle.
3. Install native build and Debian packaging dependencies.
4. Compile the ARM64 native LoRa PHY library.
5. Run the desktop Gradle tests and create the desktop Debian package.
6. Build the receiver Debian package.
7. Run Python bytecode compilation checks on the receiver files.
8. Inspect Debian package metadata and collect the packages.
9. Upload an artifact named `meshsdr-arm64-deb`.

A successful workflow run means the configured build/package steps completed. It does not by itself prove that the packages have been tested against every RTL-SDR model, every MeshCore channel type, every Raspberry Pi OS image, or every browser.

Always use the artifact from the newest successful run for the branch. Do not use an artifact from a failed, cancelled, or still-running workflow.

## 15. Repository layout

Relevant files in this branch:

```text
.github/workflows/
  export-messages-arm64.yml     ARM64 package workflow

desktop/
  build-arm64.sh                Local ARM64 build helper
  meshsdr-exporter.service      Headless systemd unit template
  native/                       Native LoRa PHY source
  src/main/kotlin/.../
    MessageExporter.kt          Decoded-message export and HTTP forwarding
    RadioConfig.kt              Desktop radio/exporter settings
  resources/linux-arm64/        ARM64 native library output/resource location

receiver/
  app.py                        FastAPI API, SQLite, SSE and dashboard
  requirements.txt              Python dependencies
  config.example.yaml           Receiver configuration example
  channels.example.yaml         Channel display mapping example
  meshsdr-receiver.service      Receiver systemd service
  package-deb.sh                Receiver Debian package builder
  test_messages.py              Synthetic message test client
  README.md                     Receiver-specific documentation

README-EXPORT.md                This document
```

## 16. Current implementation status and known caveats

This README documents the code currently present on the `export-messages` branch. Some integration details depend on the exact desktop packaging configuration and target Pi installation.

Important caveats:

1. **Private-channel decoding is limited.** The receiver cannot decrypt messages. Arbitrary private channels/direct messages are not supported unless the existing desktop decoder can already decode them.
2. **No guaranteed offline forwarding.** The JSONL file is a local record, not a persistent retry queue.
3. **No dashboard login yet.** Protect the receiver at the network layer or with an authenticated reverse proxy.
4. **Channel settings are display rules.** The receiver's channel mappings do not configure radio decryption.
5. **Browser audio needs interaction.** Press Enable sound after opening the page.
6. **Hardware support depends on the existing SDR stack.** This branch does not independently validate every RTL-SDR device or RF configuration.
7. **CI is not a hardware test.** A green build verifies the workflow's build/package checks, not end-to-end reception over the air.

For bugs, include the branch/commit, Pi OS version, `uname -m`, relevant systemd logs, and whether the failure is in radio reception, decoding, HTTP forwarding, database storage, or browser display. Remove API tokens and private message content before sharing logs.

---

**Project goal:** provide a straightforward bridge from MeshSDR's existing MeshCore message decoding to a locally hosted, searchable/live web monitor, while keeping the radio decoder and message decryption inside the existing MeshSDR application.


## 17. Export diagnostics and logs

The desktop exporter now writes diagnostic entries to the normal MeshSDR **Logs** tab (and to standard output in server mode). Look for the `EXPORT` tag.

Examples include:

- `Exporter initialized`: endpoint and hashtag settings loaded; the log reports whether a usable token is configured, but never prints the token.
- `Decoded message`: a supported MeshCore message was decoded and appended to the local JSONL file.
- `Receiver accepted message (HTTP 200)`: the receiver accepted the POST.
- `Receiver rejected message (HTTP 401/422/...)`: the receiver returned an HTTP error; check token, payload, and receiver logs.
- `POST failed`: network, connection, or endpoint failure. The local JSONL copy remains available.
- `Frame diagnostic`: every 25 verified MeshCore frames, a summary shows packet parse rejects, non-group-text frames, decryption rejects, empty bodies, and successful exports.

The receiver writes startup, authentication rejection, message storage/duplicate, and dashboard event-stream connection logs to systemd's journal. On the Pi:

```bash
sudo journalctl -u meshsdr-receiver -f
```

To inspect recent receiver logs:

```bash
sudo journalctl -u meshsdr-receiver -n 200 --no-pager
```

The desktop local export file is:

```bash
tail -f ~/.config/meshsdr/messages.jsonl
```

Interpretation:

- If MeshSDR shows LoRa detections but no verified frames, investigate RF frequency, bandwidth, spreading factor, signal quality, and the radio profile first.
- If verified MeshCore frames appear but `Frame diagnostic` shows `decryptRejected` increasing, the decoder is receiving frames but cannot decrypt those payloads with the currently supported channel/key configuration.
- If `Decoded message` appears and the JSONL file grows but the dashboard stays empty, check the POST result and receiver journal. HTTP 401 usually means the shared token does not match; connection errors usually mean the endpoint/port/network is wrong.
- If the receiver journal says `Stored message` but the browser does not update, reload the dashboard and check its browser connection to `/api/events`.

Diagnostics deliberately avoid logging message text or the shared API token.


## 18. Raspberry Pi 5: install the desktop SDR receiver

The ARM64 workflow produces **two separate Debian packages**:

- `meshsdr-receiver_*.deb`: web dashboard/API only.
- `meshsdr_*.deb` (desktop package name may be capitalized by Compose): the MeshSDR application containing the RTL-TCP receive loop, native LoRa PHY, MeshCore decoder, and message exporter.

Installing the receiver package alone does not start an SDR or read an RTL-SDR dongle. For a Pi that should receive radio directly, install the desktop package as well. Connect the RTL-SDR to the Pi, launch MeshSDR, select **MeshCore NL**, and press **Connect**. The application starts local `rtl_tcp` by default when the setting “Start rtl_tcp with the dongle on this computer” is enabled.

The ARM64 packages are built by the `MeshSDR ARM64 Debian packages` GitHub Actions workflow. Download the `meshsdr-arm64-deb` artifact from a successful run. Check the package architecture before installing:

```bash
dpkg-deb -f ./meshsdr_*.deb Package Version Architecture
sudo apt install ./meshsdr_*.deb
```

The desktop app requires a graphical Linux session for its window. To run it headlessly, use the packaged application's `--server` mode only after configuring its radio and exporter settings; check the installed desktop launcher/application files for the exact executable path. The web receiver remains a separate service and must also be installed/configured if you want the browser dashboard.
