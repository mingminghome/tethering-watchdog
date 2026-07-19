# Tether Watchdog

**Aim:** optimize and stabilize **USB tethering** — keep the link up, prefer a clean mobile uplink, apply root-side network tweaks, and show when radio quality (e.g. 4G+ → 4G) changes while you tether.

- **Package:** `com.mmhw.tetherwatchdog`
- **Min SDK:** 26 · **Target SDK:** 35
- **Version:** 1.0
- **License:** [MIT](LICENSE)

## Tethering optimization (root)

These run when you press **RESET** or when **Auto-recover** heals the link (`RootUtil.performResetSequence` / `forceMobileDataPriority`):

| Optimization | What it does |
|--------------|----------------|
| **Mobile data bounce** | `svc data` off → on so the radio reattaches cleanly |
| **DNS flush** | Clears resolver state that can stick after a bad tether |
| **TCP tuning** | Window scaling, larger `rmem`/`wmem`, try **BBR** congestion control |
| **IP forwarding** | Ensures the phone can forward tether traffic |
| **Force RNDIS** | `svc usb setFunctions rndis,adb` |
| **USB iface MTU 1440** | Sets MTU on `rndis0` / `usb0` / `ncm0` (helps some hosts / tunnels) |
| **TTL fix** | `iptables` mangle TTL 64 (reduces some carrier tether detection quirks) |
| **Mobile default route** | Drops Wi‑Fi default when needed; prefers cellular (`rmnet*` / similar) as uplink |
| **Auto-recover** | Background heal on USB reconnect or dead RNDIS iface |
| **Route priority on start** | Auto-recover service prefers mobile as default route without a full bounce |

Without root, the app still **monitors** tether and radio; it cannot apply the kernel / `svc` optimisations above.

## Monitoring

| Area | What it does |
|------|----------------|
| **USB connection** | Plug / tether state, link tier when available |
| **Mobile radio** | 4G / 4G+ / 5G, RSRP, step-down events |
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
