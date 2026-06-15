# ESPHome plugin — demo recording script

A choreographed shot list for recording an "install + typical usage" demo. Every
feature below actually triggers; the copy-paste files keep the recording smooth.
Target length ~3–4 min.

## A. Pre-flight (before recording)

1. **Build the install zip:** `./gradlew :plugin:buildPlugin -PpluginVersion=<v>`
   → `plugin/build/distributions/esphome-clion-plugin-<v>.zip` (or use the
   Marketplace build).
2. **Clean IDE state for the install shot:** Settings → Plugins → if ESPHome is
   installed, uninstall it and restart, so you can film a fresh install. (Skip if
   you'd rather film an update.)
3. **Make a demo folder** `esphome-demo/` with the two files below.
4. **Appearance:** editor font ~18–20pt, high-contrast theme, maximized window,
   Project tree hidden (⌘1) until needed, other tool windows closed.
5. Have a **real device** reachable for the device-window segment, or mark it
   optional.
6. Ensure `esphome` is on PATH (or the venv is set up) so live validation works.

**`esphome-demo/secrets.yaml`** (keep values fake — they're briefly visible when revealed):
```yaml
wifi_ssid: "MyNetwork"
wifi_password: "hunter2-super-secret"
api_key: "kZ8s9vQ2p1L0mN3xRtUvWxYz0123456789aBcDeF="
```

**`esphome-demo/demo.yaml`** (finished reference; type parts live during the demo):
```yaml
esphome:
  name: demo

esp32:
  board: esp32dev

wifi:
  ssid: !secret wifi_ssid
  password: !secret wifi_password

api:

logger:

font:
  - file: "gfonts://Roboto"
    id: font_body
    size: 20

switch:
  - platform: gpio
    id: relay_1
    pin: 4

binary_sensor:
  - platform: gpio
    id: button_1
    pin: 5
    on_press:
      - switch.toggle: relay_1
      - lambda: |-
          id(relay_1).turn_on();
```

## B. The recording

### Segment 1 — Install (~25s)
- Settings (⌘,) → Plugins → gear ⚙ → **Install Plugin from Disk…** → pick the zip → **OK**.
- The plugin appears as "ESPHome" with the blue logo. Click **Apply** (no restart for a fresh enable).
- Open `demo.yaml`.

### Segment 2 — Editor action toolbar (~15s)
- As soon as `demo.yaml` opens, point to the small toolbar pinned in the **top-right of the editor**: **▶ Run**, **Logs**, **Open Device Window**.
- Note it's always visible on a standalone ESPHome config (and only there). Hover a button to show its tooltip.
- Beat: "the common actions — run, view logs, connect to the device — are one click away, right where you're editing."
- (Used live later in Segments 9 and 10.)

### Segment 3 — Completion (~30s)
- On a new top-level line type `sens` → completes **`sensor`**; accept, add `\n  - platform: ` → platform list (`dht`, `adc`, …). Pick `dht`.
- On a new line inside the item type `te` → **`temperature`** / fields with type hints.
- Call out: "completion is driven by the real ESPHome component catalog, with types and required-field hints."
- ⌘Z back to the clean file.

### Segment 4 — Hover docs incl. domain hover (~20s)
- Hover (⌃J / F1) over `switch:` → quick-doc shows **"Platform domain"** + available platforms + docs link.
- Hover over `board:` or `pin:` → field doc with type + description.

### Segment 5 — Live validation (~20s)
- Change the switch `pin: 4` to `pin: 999` → red underline + message from `esphome config` on the offending line. Fix it back → error clears.
- Beat: "validation is the real `esphome config`, not an approximation."

### Segment 6 — Navigation (~35s) — the money shot
- **id reference:** Cmd-click `relay_1` in `switch.toggle: relay_1` → jumps to `id: relay_1`.
- **`!secret`:** Cmd-click `wifi_password` in `!secret wifi_password` → jumps into `secrets.yaml`.
- **lambda `id()`:** Cmd-click `relay_1` inside `id(relay_1)` → jumps to the declaration.

### Segment 7 — Lambda highlighting + checks (~30s)
- In the `lambda: |-` block, show the **C++ keyword/string/number coloring** of `id(relay_1).turn_on();`.
- Type a second line `id(relay_1).turn_of()` **without a semicolon** → **yellow "missing ;" warning**.
- Add a stray `{` → **red "Unclosed '{'"** error. Delete it; fix the `;`.
- Bonus: start typing `id(` on a new lambda line → id completion pops up.

### Segment 8 — secrets.yaml masking (~20s)
- Open `secrets.yaml`. Values show as `••••••`. Move the caret onto the `wifi_password` line → its value reveals; move away → re-masks.
- Beat: "so secrets don't show on a screen-share."

### Segment 9 — Device tool window (~35s) *(needs a live device; optional)*
- Click **Open Device Window** on the editor toolbar (Segment 2) — or right-click `demo.yaml` → **Open ESPHome Device Window**. It auto-connects.
- Entities grouped by type with icons; toggle a switch and watch the state flip; if the device has an **event** entity, point to the **"… ago"** live timestamp.
- Hide the window → it disconnects; show it → reconnects.

### Segment 10 — Run it (~20s) *(optional)*
- Click **▶ Run** on the editor toolbar (Segment 2) → compile + upload + logs in the Run console, ANSI-colored. (Or the **Logs** button for `esphome logs` only.)
- First use builds/reuses a Run configuration automatically — no manual setup needed.

### Closing card (~5s)
- Show the Marketplace listing / plugin page with the current version.

## C. Tips
- Record at native resolution; the editor font bump keeps text legible when scaled down.
- Pause ~1s after each action so popups fully render.
- Warm up completion once before recording (first use may index).
- For Segment 9 without a device: still show the window opening, host/key pre-fill, and the "connecting…" state — entities need a live device.
