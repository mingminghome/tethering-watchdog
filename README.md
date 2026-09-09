# Tether Watchdog

**Aim:** optimize and stabilize **USB and ethernet tethering** — keep the link up, prefer a clean mobile uplink, apply root-side network tweaks, and show when radio quality (e.g. 4G+ → 4G) changes while you tether.

- **Package:** `com.mmhw.tetherwatchdog`
- **Min SDK:** 26 · **Target SDK:** 35
- **Version:** 1.3
- **License:** [MIT](LICENSE)

## Tethering optimization (root)

These run when you press **RESET** or when **Auto-recover** heals the link (`RootUtil.performResetSequence` / `forceMobileDataPriority`). Tether mode is auto-detected: a USB hub / ethernet adapter uses **ethernet tethering**; a direct cable to a PC uses **USB (RNDIS)** tethering. Reset uses the same detection — it will **not** force RNDIS while a hub is attached (that would drop the hub).

Shared (both modes):

| Optimization | What it does |
|--------------|----------------|
| **Mobile data bounce** | `svc data` off → on so the radio reattaches cleanly |
| **DNS flush** | Clears resolver state that can stick after a bad tether |
| **TCP tuning** | Window scaling, larger `rmem`/`wmem`, **BBR**, MTU probing, no slow-start after idle |
| **IP forwarding** | Ensures the phone can forward tether traffic |
| **TTL fix** | `iptables` mangle TTL 64 (reduces some carrier tether detection quirks) |
| **Mobile default route** | Drops Wi‑Fi / ethernet default when needed; prefers cellular (`rmnet*` / similar) as uplink |
| **Auto-recover** | Background heal on USB reconnect, hub unplug/replug, or a dead ethernet path. If the ethernet link drops, Android turns ethernet tethering off — Auto-recover waits for the link and turns it back on (no mobile-data bounce while the link is down). |
| **Route priority on start** | Auto-recover service prefers mobile as default route without a full bounce |

USB gadget (direct cable to a PC):

| Optimization | What it does |
|--------------|----------------|
| **Force RNDIS** | `svc usb setFunctions rndis,adb` |
| **USB iface MTU 1440** | Sets MTU on `rndis0` / `usb0` / `ncm0` (avoids RNDIS / tunnel fragmentation) |

Ethernet (USB hub / USB-C dock) — different link-layer path; **does not** use the USB 1440 MTU:

| Optimization | What it does |
|--------------|----------------|
| **Ethernet tethering** | Enable ethernet tethering, leave USB in host mode (RNDIS would drop the hub) |
| **Auto-enable hub** | When a hub/ethernet adapter appears, ethernet tethering is turned on automatically |
| **MTU 1500** | Full ethernet frames on `eth*` |
| **MSS 1400** | `TCPMSS --set-mss 1400` so 1500 LAN packets fit typical cellular tunnels (`clamp-to-pmtu` often no-ops on FORWARD) |
| **rp_filter off** | Strict reverse-path filter otherwise drops NAT’d tether packets |
| **USB autosuspend off** | Keeps the ethernet adapter awake (autosuspend shows up as jitter) |
| **EEE off** | Stops many USB adapters falling back to 10 Mbps |
| **GRO off, TSO/GSO on** | GRO on USB-ethernet NAT hurts upload; TSO/GSO keep download (phone→LAN) from being software-segmented |
| **10 Mbps kick** | If the PHY is 10 Mbps, re-negotiate then try 100/full |
| **fq_codel** | AQM on the **cellular** hop only (USB ethernet usually lacks BQL; fq_codel there sits on the download path) |

Without root, the app still **monitors** tether and radio; it cannot apply the kernel / `svc` optimisations above.

## Monitoring

| Area | What it does |
|------|----------------|
| **Tether link** | USB gadget (RNDIS) or ethernet hub, link tier when available |
| **Mobile radio** | 4G / 4G+ / 5G (incl. NSA), RSRP, step-down events |
| **Drop context** | `tethered` · `idle (no USB)` · `after reset` · `cable only` |
| **Live metrics** | Optional rates + light internet probe (app open) |

### Root vs non-root

| Capability | Non-root | Root (Magisk) |
|------------|----------|----------------|
| Radio monitoring (4G / 4G+) | Yes | Yes |
| Basic USB / tether status | Yes | Yes |
| Detailed iface rates / speed | Limited | Yes |
| Optimisations, Auto-recover & Reset | No | Yes |

## Install

1. Download the APK from [Releases](https://github.com/mingminghome/tethering-watchdog/releases).
2. Allow install from unknown sources if prompted.
3. Grant **Phone** (for radio type). Location is optional (helps some signal metrics).
4. If rooted: approve the app in **Magisk** on first install.

## Privacy

- No analytics SDKs.
- Root is only used for tether heal, routes, and device network stats.
- Signing keys and local machine config are not part of this repository.

## Support the Project

If you find this app useful, consider supporting its development by buying me a pint!

<a href="https://buymeacoffee.com/mingminghomework"><img src="https://img.buymeacoffee.com/button-api/?text=Buy%20me%20a%20pint&emoji=%F0%9F%8D%BA&slug=mingminghomework&button_colour=5F7FFF&font_colour=ffffff&font_family=Cookie&outline_colour=000000&coffee_colour=FFDD00" alt="Buy me a pint"></a>

## License

MIT — see [LICENSE](LICENSE).
