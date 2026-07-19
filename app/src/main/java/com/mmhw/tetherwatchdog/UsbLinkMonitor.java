package com.mmhw.tetherwatchdog;

import android.net.TrafficStats;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Monitors USB tethering link quality from the phone (gadget/RNDIS) side,
 * plus live mobile vs USB traffic rates (passive — not a speed test).
 */
public class UsbLinkMonitor {

    public static final String IFACE_CANDIDATES = "rndis0,usb0,ncm0,rndis_data0";
    public static final String MOBILE_CANDIDATES =
            "rmnet_data0,rmnet0,rmnet_data1,rmnet_data2,rmnet_data3,"
                    + "rmnet_ipa0,rmnet_mhi0,pdp_ip0,ccmni0,ccmni1,wwan0,seth_lte0,v4-rmnet_data0";

    private static final String[] TETHER_IFACES = IFACE_CANDIDATES.split(",");
    private static final String[] MOBILE_IFACES = MOBILE_CANDIDATES.split(",");

    private static final long SPEED_CACHE_MS = 8_000L;
    /** Avoid spawning su every poll — re-use last root sample when fresh. */
    private static final long ROOT_MIN_INTERVAL_MS = 2_500L;
    /** Min combined rate (KB/s) before we call traffic "active" for gap hints. */
    private static final double ACTIVE_KB_S = 40.0;

    /**
     * null = not probed; true = /sys/class/net readable (rare on modern Android);
     * false = SELinux-blocked — never listFiles again (stops AVC log spam).
     */
    private static Boolean localNetSysfsOk;

    private long lastUsbRx = -1;
    private long lastUsbTx = -1;
    private long lastMobRx = -1;
    private long lastMobTx = -1;
    private long lastSampleMs = 0;

    private double lastUsbRxKBs = 0;
    private double lastUsbTxKBs = 0;
    private double lastMobRxKBs = 0;
    private double lastMobTxKBs = 0;

    /** How mobile counters were obtained (for UI / debug). */
    private String mobileSource = "—";
    private String lastMobileSourceKey = null;

    private int flapCount = 0;
    private long flapWindowStartMs = 0;
    private Boolean lastConnected = null;

    private String cachedSpeed = null;
    private long cachedSpeedAtMs = 0;
    private boolean forceSpeedRefresh = true;

    private RootSample lastRootSample;
    private long lastRootSampleMs;
    private boolean forceRootRefresh;

    /**
     * User-facing USB link state (prefer these over raw GOOD/FAIR/POOR).
     * <ul>
     *   <li>{@code UNPLUGGED} — no cable</li>
     *   <li>{@code TETHER_OFF} — cable in, tethering not on</li>
     *   <li>{@code STARTING} — tether on, iface not ready</li>
     *   <li>{@code LIMITED} — slow PHY (USB 1.1 full-speed) / bad contact</li>
     *   <li>{@code UNSTABLE} — frequent plug flaps</li>
     *   <li>{@code HEALTHY} — good link</li>
     *   <li>{@code CONNECTED} — plugged, still classifying</li>
     * </ul>
     */
    public static class Snapshot {
        public boolean usbConnected;
        public boolean usbConfigured;
        public boolean rndisFunction;
        public boolean ifaceUp;
        public String ifaceName;
        public String mobileIface;
        /** Kernel label: high-speed, super-speed, … */
        public String usbSpeed;
        /** Friendly tier: USB 2.0, USB 3.0, … */
        public String speedTier;
        /** Status key for colors / logic */
        public String quality;
        /** Short badge: Healthy, Limited, Unplugged, … */
        public String statusLabel;
        public String detail;
        /** Phone USB iface: from host / to host (KB/s) */
        public double usbRxKBs;
        public double usbTxKBs;
        /** Mobile data: from net / to net (KB/s) */
        public double mobileRxKBs;
        public double mobileTxKBs;
        /** e.g. TrafficStats / rmnet_data2 / sum:rmnet* */
        public String mobileSource;
        public int flapsInWindow;
        /** One-line compare: idle / in sync / gap … */
        public String compareHint;

        /** Compact notification / legacy line. */
        public String summaryLine() {
            return String.format(Locale.US, "%s · %s",
                    statusLabel != null ? statusLabel : "—",
                    speedTier != null ? speedTier : "USB");
        }

        /** Host download ≈ USB TX; host upload ≈ USB RX. */
        public String usbRateLine() {
            return String.format(Locale.US, "↓ %s  ↑ %s",
                    formatRate(usbTxKBs), formatRate(usbRxKBs));
        }

        public String mobileRateLine() {
            return String.format(Locale.US, "↓ %s  ↑ %s",
                    formatRate(mobileRxKBs), formatRate(mobileTxKBs));
        }
    }

    public void onUsbState(boolean connected, boolean configured, boolean rndis) {
        long now = System.currentTimeMillis();
        if (flapWindowStartMs == 0 || now - flapWindowStartMs > 5 * 60_000L) {
            flapWindowStartMs = now;
            flapCount = 0;
        }
        if (lastConnected != null && lastConnected != connected) {
            flapCount++;
            forceSpeedRefresh = true;
            forceRootRefresh = true;
            lastUsbRx = lastUsbTx = lastMobRx = lastMobTx = -1;
            lastSampleMs = 0;
            lastUsbRxKBs = lastUsbTxKBs = lastMobRxKBs = lastMobTxKBs = 0;
        }
        lastConnected = connected;
    }

    public int getFlapCount() {
        return flapCount;
    }

    public Snapshot sample(boolean usbConnected, boolean usbConfigured, boolean rndisFunction) {
        Snapshot s = new Snapshot();
        s.usbConnected = usbConnected;
        s.usbConfigured = usbConfigured;
        s.rndisFunction = rndisFunction;
        s.flapsInWindow = flapCount;

        // 1) Local sysfs (blocked on modern untrusted_app — probe once only)
        String usbIface = canUseLocalNetSysfs() ? findTetherIfaceLocal() : null;
        // 2) Java NetworkInterface — works without root on many devices when sysfs is denied
        if (usbIface == null) {
            usbIface = findTetherIfaceViaNetworkInterface();
        }

        long usbRx = -1;
        long usbTx = -1;
        if (usbIface != null) {
            if (canUseLocalNetSysfs()) {
                usbRx = readLongSysfsLocal(statPath(usbIface, "rx_bytes"));
                usbTx = readLongSysfsLocal(statPath(usbIface, "tx_bytes"));
            }
            // 3) TrafficStats per-iface (no SELinux on /sys/class/net)
            if (usbRx < 0 || usbTx < 0) {
                long[] ts = trafficStatsForIface(usbIface);
                if (ts[0] >= 0) usbRx = ts[0];
                if (ts[1] >= 0) usbTx = ts[1];
            }
        }

        // Cheap platform counters first (no shell, no SELinux).
        MobileCounters mob = pickBestMobileCounters(null);

        GadgetHints hints = readGadgetHintsLocal();
        // getprop / SystemProperties when sysfs gadget nodes are denied.
        if (hints.functions == null) {
            String cfg = systemProperty("sys.usb.config");
            if (cfg != null && !cfg.isEmpty()) hints.functions = cfg.toLowerCase(Locale.US);
        }

        boolean needFreshRoot = forceRootRefresh
                || lastRootSample == null
                || usbIface == null
                || usbRx < 0 || usbTx < 0
                || needSpeedRefresh()
                || (!usbConnected && !hints.connectedKnown)
                || ((mob.rx <= 0 && mob.tx <= 0) && (usbRx > 0 || usbTx > 0));

        RootSample root = needFreshRoot ? sampleViaRootThrottled() : lastRootSample;
        if (root != null) {
            if (root.usbIface != null && !root.usbIface.isEmpty()) usbIface = root.usbIface;
            if (root.usbRx >= 0) usbRx = root.usbRx;
            if (root.usbTx >= 0) usbTx = root.usbTx;
            if (root.speedRaw != null && !root.speedRaw.isEmpty()) {
                cachedSpeed = normalizeSpeed(root.speedRaw);
                cachedSpeedAtMs = System.currentTimeMillis();
                forceSpeedRefresh = false;
            }
            if (root.gadgetConnected != null) {
                hints.connected = root.gadgetConnected;
                hints.connectedKnown = true;
            }
            if (root.gadgetFunctions != null) {
                hints.functions = root.gadgetFunctions;
            }
            mob = pickBestMobileCounters(root);
            // Re-read TrafficStats if root named an iface we didn't have
            if (usbIface != null && (usbRx < 0 || usbTx < 0)) {
                long[] ts = trafficStatsForIface(usbIface);
                if (usbRx < 0 && ts[0] >= 0) usbRx = ts[0];
                if (usbTx < 0 && ts[1] >= 0) usbTx = ts[1];
            }
        }

        if (needSpeedRefresh() || isUnknownSpeed(cachedSpeed)) {
            if (canUseLocalNetSysfs() || canTryGadgetSysfs()) {
                String localSpeed = readUsbGadgetSpeedLocal();
                if (!isUnknownSpeed(localSpeed)) {
                    cachedSpeed = localSpeed;
                    cachedSpeedAtMs = System.currentTimeMillis();
                    forceSpeedRefresh = false;
                }
            }
            // Don't spin forever on "checking…" — settle once tether is known
            if (isUnknownSpeed(cachedSpeed)) {
                if (s.rndisFunction || usbIface != null
                        || (hints.functions != null && hints.functions.contains("rndis"))) {
                    cachedSpeed = "high-speed"; // typical USB2 gadget tether
                    cachedSpeedAtMs = System.currentTimeMillis();
                    forceSpeedRefresh = false;
                } else {
                    cachedSpeed = "unknown";
                }
            }
        }

        s.usbSpeed = cachedSpeed != null ? cachedSpeed : "unknown";
        s.speedTier = speedTierLabel(s.usbSpeed);
        s.ifaceName = usbIface;
        s.mobileIface = mob.iface;
        s.mobileSource = mob.source != null ? mob.source : mobileSource;
        mobileSource = s.mobileSource;
        // Switching counter source (e.g. TrafficStats → rmnet sum) invalidates deltas.
        String srcKey = (mob.source != null ? mob.source : "") + "|" + (mob.iface != null ? mob.iface : "");
        if (lastMobileSourceKey != null && !lastMobileSourceKey.equals(srcKey)) {
            lastMobRx = -1;
            lastMobTx = -1;
            lastMobRxKBs = 0;
            lastMobTxKBs = 0;
        }
        lastMobileSourceKey = srcKey;

        boolean ifaceUpJava = usbIface != null && isNetworkInterfaceUp(usbIface);
        s.ifaceUp = (usbIface != null && isIfaceUp(usbIface, root)) || ifaceUpJava
                || (usbIface != null && usbRx >= 0); // counters visible ⇒ usable path

        // Reconcile plug / tether flags with sysfs/getprop (more reliable than USB_STATE alone).
        if (hints.connectedKnown) {
            s.usbConnected = hints.connected || s.usbConnected;
        }
        if (usbIface != null) {
            s.usbConnected = true;
        }
        boolean funcsTether = hints.functions != null
                && (hints.functions.contains("rndis")
                || hints.functions.contains("ncm")
                || hints.functions.contains("eem")
                || hints.functions.contains("usbnet"));
        if (funcsTether || s.ifaceUp || usbIface != null) {
            s.rndisFunction = true;
        }

        long now = System.currentTimeMillis();
        long mobRx = mob.rx;
        long mobTx = mob.tx;
        if (lastSampleMs > 0) {
            double dt = Math.max(0.001, (now - lastSampleMs) / 1000.0);
            lastUsbRxKBs = deltaRateKBs(usbRx, lastUsbRx, dt, lastUsbRxKBs);
            lastUsbTxKBs = deltaRateKBs(usbTx, lastUsbTx, dt, lastUsbTxKBs);
            lastMobRxKBs = deltaRateKBs(mobRx, lastMobRx, dt, lastMobRxKBs);
            lastMobTxKBs = deltaRateKBs(mobTx, lastMobTx, dt, lastMobTxKBs);
        }
        if (usbRx >= 0) lastUsbRx = usbRx;
        if (usbTx >= 0) lastUsbTx = usbTx;
        if (mobRx >= 0) lastMobRx = mobRx;
        if (mobTx >= 0) lastMobTx = mobTx;
        lastSampleMs = now;

        s.usbRxKBs = lastUsbRxKBs;
        s.usbTxKBs = lastUsbTxKBs;
        s.mobileRxKBs = lastMobRxKBs;
        s.mobileTxKBs = lastMobTxKBs;
        s.compareHint = buildCompareHint(s);

        grade(s);
        return s;
    }

    /** Prefer fresh root sample, but never more often than ROOT_MIN_INTERVAL_MS unless forced. */
    private RootSample sampleViaRootThrottled() {
        long now = System.currentTimeMillis();
        if (!forceRootRefresh && lastRootSample != null
                && now - lastRootSampleMs < ROOT_MIN_INTERVAL_MS) {
            return lastRootSample;
        }
        RootSample r = sampleViaRoot();
        forceRootRefresh = false;
        if (r != null) {
            lastRootSample = r;
            lastRootSampleMs = now;
            return r;
        }
        return lastRootSample;
    }

    /** Probe once: listing /sys/class/net is SELinux-denied on modern devices. */
    private static boolean canUseLocalNetSysfs() {
        if (localNetSysfsOk != null) return localNetSysfsOk;
        File net = new File("/sys/class/net");
        File[] kids = net.listFiles();
        if (kids != null) {
            localNetSysfsOk = true;
            return true;
        }
        // Candidate exists() also triggers AVC — only try one known path once.
        boolean one = new File("/sys/class/net/lo").exists();
        localNetSysfsOk = one;
        return one;
    }

    private static Boolean localGadgetSysfsOk;

    private static boolean canTryGadgetSysfs() {
        if (localGadgetSysfsOk != null) return localGadgetSysfsOk;
        // One probe; power_supply/usb/online is sometimes world-readable.
        String online = readFirstLineLocalUncached("/sys/class/power_supply/usb/online");
        localGadgetSysfsOk = online != null;
        return localGadgetSysfsOk;
    }

    private static String systemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            if (v == null) return null;
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class MobileCounters {
        long rx = -1;
        long tx = -1;
        String iface;
        String source;
    }

    /**
     * Pick the best mobile byte counters among:
     * <ul>
     *   <li>sum of cellular sysfs ifaces (best for multi-rmnet OEMs)</li>
     *   <li>{@link TrafficStats#getMobileRxBytes()} (platform aggregate)</li>
     *   <li>root default-route / sum sample</li>
     * </ul>
     * TrafficStats alone often stays 0 for tethered traffic on some devices.
     */
    private static MobileCounters pickBestMobileCounters(RootSample root) {
        MobileCounters sum = sumCellularIfacesLocal();
        MobileCounters ts = mobileFromTrafficStats();
        MobileCounters fromRoot = mobileFromRoot(root);

        MobileCounters best = null;
        long bestTotal = -1;
        for (MobileCounters c : new MobileCounters[]{sum, fromRoot, ts}) {
            if (c == null || c.rx < 0 || c.tx < 0) continue;
            long tot = c.rx + c.tx;
            // Prefer sources that actually have cumulative traffic
            if (tot > bestTotal) {
                bestTotal = tot;
                best = c;
            }
        }
        if (best != null) return best;

        // Last resort: any valid counters even if zero
        if (ts.rx >= 0) return ts;
        if (sum.rx >= 0) return sum;
        if (fromRoot.rx >= 0) return fromRoot;
        return new MobileCounters();
    }

    private static MobileCounters mobileFromTrafficStats() {
        MobileCounters c = new MobileCounters();
        try {
            long rx = TrafficStats.getMobileRxBytes();
            long tx = TrafficStats.getMobileTxBytes();
            if (rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED
                    && rx >= 0 && tx >= 0) {
                c.rx = rx;
                c.tx = tx;
                c.source = "TrafficStats";
                c.iface = "mobile";
            }
        } catch (Throwable ignored) {}
        return c;
    }

    private static MobileCounters mobileFromRoot(RootSample root) {
        MobileCounters c = new MobileCounters();
        if (root == null) return c;
        if (root.mobRx >= 0 && root.mobTx >= 0) {
            c.rx = root.mobRx;
            c.tx = root.mobTx;
            c.iface = root.mobileIface;
            c.source = root.mobileIface != null
                    ? (root.mobileFromRoute ? "route:" + root.mobileIface : "sum:" + root.mobileIface)
                    : "sysfs-root";
        }
        return c;
    }

    /** Sum rx/tx across all cellular-looking interfaces (local, no root). */
    private static MobileCounters sumCellularIfacesLocal() {
        MobileCounters c = new MobileCounters();
        if (!canUseLocalNetSysfs()) return c;

        long rxSum = 0;
        long txSum = 0;
        int count = 0;
        String best = null;
        long bestTotal = -1;

        File net = new File("/sys/class/net");
        File[] kids = net.listFiles();
        if (kids != null) {
            for (File d : kids) {
                String n = d.getName();
                if (!isCellularIfaceName(n)) continue;
                long rx = readLongSysfsLocal(statPath(n, "rx_bytes"));
                long tx = readLongSysfsLocal(statPath(n, "tx_bytes"));
                if (rx < 0 || tx < 0) continue;
                rxSum += rx;
                txSum += tx;
                count++;
                long tot = rx + tx;
                if (tot > bestTotal) {
                    bestTotal = tot;
                    best = n;
                }
            }
        }
        // Known candidate list when listFiles is empty but individual nodes work
        if (count == 0) {
            for (String n : MOBILE_IFACES) {
                long rx = readLongSysfsLocal(statPath(n, "rx_bytes"));
                long tx = readLongSysfsLocal(statPath(n, "tx_bytes"));
                if (rx < 0 || tx < 0) continue;
                rxSum += rx;
                txSum += tx;
                count++;
                long tot = rx + tx;
                if (tot > bestTotal) {
                    bestTotal = tot;
                    best = n;
                }
            }
        }

        if (count > 0) {
            c.rx = rxSum;
            c.tx = txSum;
            c.iface = best;
            c.source = count > 1 ? ("sum:" + count + " ifaces") : best;
        }
        return c;
    }

    static boolean isCellularIfaceName(String n) {
        if (n == null || n.isEmpty()) return false;
        // Skip obvious non-cellular
        if (n.equals("lo") || n.startsWith("wlan") || n.startsWith("rndis")
                || n.startsWith("ncm") || n.matches("usb\\d+") || n.startsWith("ifb")
                || n.startsWith("dummy") || n.startsWith("tun") || n.startsWith("tap")
                || n.startsWith("veth") || n.startsWith("ap") || n.startsWith("softap")) {
            return false;
        }
        return n.startsWith("rmnet")
                || n.startsWith("ccmni")
                || n.startsWith("pdp")
                || n.startsWith("wwan")
                || n.startsWith("seth")
                || n.startsWith("v4-rmnet")
                || n.contains("rmnet");
    }

    private static double deltaRateKBs(long cur, long prev, double dtSec, double fallback) {
        if (cur < 0 || prev < 0) return 0;
        if (cur < prev) return 0;
        double rate = (cur - prev) / 1000.0 / dtSec;
        if (rate > 2_000_000) return fallback;
        return rate;
    }

    private boolean needSpeedRefresh() {
        return forceSpeedRefresh
                || cachedSpeed == null
                || System.currentTimeMillis() - cachedSpeedAtMs > SPEED_CACHE_MS;
    }

    private static boolean isUnknownSpeed(String speed) {
        return speed == null || speed.isEmpty()
                || "unknown".equalsIgnoreCase(speed) || "?".equals(speed);
    }

    private void grade(Snapshot s) {
        if (!s.usbConnected && s.ifaceName == null) {
            s.quality = "UNPLUGGED";
            s.statusLabel = "Unplugged";
            s.detail = "No USB cable detected";
            return;
        }

        String speed = s.usbSpeed != null ? s.usbSpeed.toLowerCase(Locale.US) : "";
        boolean slowPhy = speed.contains("full") || speed.contains("low");
        boolean flappy = s.flapsInWindow >= 4;
        boolean rootOk = RootUtil.hasRootCached();

        if (flappy) {
            s.quality = "UNSTABLE";
            s.statusLabel = "Unstable";
            s.detail = "Link keeps dropping — reseat cable, ease port stress";
        } else if (slowPhy && s.usbConnected) {
            s.quality = "LIMITED";
            s.statusLabel = "Limited";
            s.detail = s.speedTier + " only — bad contact, cable, or port angle";
        } else if (s.ifaceUp) {
            s.quality = "HEALTHY";
            s.statusLabel = "Healthy";
            String ifn = s.ifaceName != null ? s.ifaceName : "usb";
            s.detail = s.speedTier + " · " + ifn + " up";
        } else if (s.rndisFunction && s.usbConnected) {
            // System reports tether (USB_STATE / functions) — don't stick on Starting forever
            // when sysfs is blocked and Magisk hasn't granted the release APK yet.
            if (s.ifaceName != null) {
                s.quality = "STARTING";
                s.statusLabel = "Starting…";
                s.detail = "Iface " + s.ifaceName + " present but not up yet";
            } else if (rootOk) {
                s.quality = "STARTING";
                s.statusLabel = "Starting…";
                s.detail = "Tether on — waiting for USB network iface";
            } else {
                s.quality = "HEALTHY";
                s.statusLabel = "Tether on";
                s.detail = "USB tethering active · grant Magisk/root for rates & speed detail";
                if (isUnknownSpeed(s.usbSpeed)) {
                    s.usbSpeed = "high-speed";
                    s.speedTier = speedTierLabel(s.usbSpeed);
                }
            }
        } else if (s.rndisFunction || s.ifaceName != null) {
            s.quality = "STARTING";
            s.statusLabel = "Starting…";
            s.detail = s.ifaceName != null
                    ? ("Iface " + s.ifaceName + " present but not up")
                    : "Tether on — waiting for USB network";
        } else if (s.usbConnected) {
            s.quality = "TETHER_OFF";
            s.statusLabel = "Tether off";
            s.detail = "Cable connected · enable USB tethering";
        } else {
            s.quality = "CONNECTED";
            s.statusLabel = "Connected";
            s.detail = "Cable in · checking link…";
        }
    }

    /**
     * Passive mobile vs USB compare (host download path ≈ mobile↓ vs USB→host).
     * Not a synthetic speed test — only live interface counters.
     */
    private static String buildCompareHint(Snapshot s) {
        // Host download path: cellular RX feeds USB TX to the PC
        double cellDown = s.mobileRxKBs;
        double usbToHost = s.usbTxKBs;
        // Host upload path: USB RX from PC feeds cellular TX
        double cellUp = s.mobileTxKBs;
        double usbFromHost = s.usbRxKBs;

        double downPeak = Math.max(cellDown, usbToHost);
        double upPeak = Math.max(cellUp, usbFromHost);
        double peak = Math.max(downPeak, upPeak);

        if (peak < ACTIVE_KB_S) {
            String src = s.mobileSource != null ? s.mobileSource : "";
            if (s.mobileRxKBs < 0.5 && s.mobileTxKBs < 0.5
                    && (s.usbRxKBs >= ACTIVE_KB_S || s.usbTxKBs >= ACTIVE_KB_S)) {
                return "USB busy but mobile=0 — wrong radio iface? (" + src + ")";
            }
            return "Idle — rates show when the PC uses the tether";
        }

        // Prefer download path for bottleneck (most visible for users)
        if (downPeak >= ACTIVE_KB_S) {
            double gap = cellDown - usbToHost;
            double ratio = usbToHost > 1 ? cellDown / usbToHost : 99;
            if (gap > 80 && ratio > 1.6) {
                return "Gap: mobile faster than USB out — USB may be the limit";
            }
            if (usbToHost - cellDown > 80 && usbToHost / Math.max(cellDown, 1) > 1.6) {
                return "USB out ahead of mobile — buffering or mixed traffic";
            }
            return "In sync — mobile and USB traffic track each other";
        }

        if (upPeak >= ACTIVE_KB_S) {
            double gap = cellUp - usbFromHost;
            if (gap > 80 && cellUp / Math.max(usbFromHost, 1) > 1.6) {
                return "Upload gap — check USB or host send rate";
            }
            return "Upload path active · mobile ↔ USB";
        }

        return "Traffic active";
    }

    public static String formatRate(double kbPerSec) {
        if (kbPerSec < 0.5) return "0 KB/s";
        if (kbPerSec >= 1000.0) {
            return String.format(Locale.US, "%.1f MB/s", kbPerSec / 1000.0);
        }
        if (kbPerSec >= 100.0) {
            return String.format(Locale.US, "%.0f KB/s", kbPerSec);
        }
        return String.format(Locale.US, "%.1f KB/s", kbPerSec);
    }

    /** Map kernel speed → everyday USB generation label. */
    public static String speedTierLabel(String usbSpeed) {
        if (usbSpeed == null) return "USB · —";
        String s = usbSpeed.toLowerCase(Locale.US);
        if (s.contains("super-speed+") || s.contains("super+")) return "USB 3.1+";
        if (s.contains("super")) return "USB 3.0";
        if (s.contains("high")) return "USB 2.0";
        if (s.contains("full")) return "USB 1.1";
        if (s.contains("low")) return "USB 1.0";
        if (s.contains("wireless")) return "USB wireless";
        if (isUnknownSpeed(s)) return "USB · —";
        return "USB · " + usbSpeed;
    }

    /** Find rndis/ncm/usb gadget iface without reading /sys/class/net. */
    private static String findTetherIfaceViaNetworkInterface() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return null;
            String fallback = null;
            for (NetworkInterface ni : Collections.list(en)) {
                if (ni == null) continue;
                String n = ni.getName();
                if (n == null) continue;
                if (!(n.startsWith("rndis") || n.startsWith("ncm") || n.matches("usb\\d+")
                        || n.startsWith("rndis_data"))) {
                    continue;
                }
                try {
                    if (ni.isUp()) return n;
                } catch (Exception ignored) {
                }
                if (fallback == null) fallback = n;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNetworkInterfaceUp(String name) {
        if (name == null) return false;
        try {
            NetworkInterface ni = NetworkInterface.getByName(name);
            return ni != null && ni.isUp();
        } catch (Exception e) {
            return false;
        }
    }

    /** @return [rx, tx] or -1s if unsupported */
    private static long[] trafficStatsForIface(String iface) {
        long[] out = new long[]{-1, -1};
        if (iface == null) return out;
        try {
            long rx = TrafficStats.getRxBytes(iface);
            long tx = TrafficStats.getTxBytes(iface);
            if (rx != TrafficStats.UNSUPPORTED && rx >= 0) out[0] = rx;
            if (tx != TrafficStats.UNSUPPORTED && tx >= 0) out[1] = tx;
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ── Root batched sample ──────────────────────────────────────────────

    private static final class GadgetHints {
        boolean connected;
        boolean connectedKnown;
        String functions;
    }

    private static final class RootSample {
        String speedRaw;
        String usbIface;
        String mobileIface;
        boolean mobileFromRoute;
        long usbRx = -1, usbTx = -1, mobRx = -1, mobTx = -1;
        String operstate;
        String flags;
        Boolean gadgetConnected;
        String gadgetFunctions;
    }

    /** Best-effort plug + function hints without root (sysfs and/or SystemProperties). */
    private static GadgetHints readGadgetHintsLocal() {
        GadgetHints h = new GadgetHints();
        if (canTryGadgetSysfs()) {
            String state = firstNonEmpty(
                    readFirstLineLocal("/sys/class/android_usb/android0/state"),
                    readFirstLineLocal("/sys/devices/virtual/android_usb/android0/state"));
            if (state != null) {
                String st = state.toUpperCase(Locale.US);
                h.connectedKnown = true;
                h.connected = st.contains("CONFIGURED") || st.contains("CONNECTED")
                        || st.equals("ONLINE");
            }
            String online = firstNonEmpty(
                    readFirstLineLocal("/sys/class/power_supply/usb/online"),
                    readFirstLineLocal("/sys/class/power_supply/usb/present"));
            if (online != null) {
                h.connectedKnown = true;
                if ("1".equals(online.trim())) h.connected = true;
            }
            h.functions = firstNonEmpty(
                    readFirstLineLocal("/sys/class/android_usb/android0/functions"),
                    readFirstLineLocal("/sys/devices/virtual/android_usb/android0/functions"));
        }
        if (h.functions == null) {
            String cfg = systemProperty("sys.usb.config");
            if (cfg != null) h.functions = cfg.toLowerCase(Locale.US);
        }
        return h;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return null;
    }

    private static RootSample sampleViaRoot() {
        String script = ""
                + "SPEED=''; "
                + "for f in "
                + "/sys/class/udc/*/current_speed "
                + "/sys/class/power_supply/usb/speed "
                + "/sys/class/power_supply/usb/usb_speed "
                + "/sys/class/android_usb/android0/speed "
                + "/sys/devices/virtual/android_usb/android0/speed "
                + "/sys/devices/virtual/android_usb/android0/f_rndis/speed "
                + "; do "
                + "  for g in $f; do "
                + "    [ -r \"$g\" ] || continue; "
                + "    v=$(cat \"$g\" 2>/dev/null | tr -d '\\r'); "
                + "    [ -n \"$v\" ] && [ \"$v\" != \"0\" ] && SPEED=\"$v\" && break 2; "
                + "  done; "
                + "done; "
                + "echo \"SPEED:$SPEED\"; "
                + "STATE=$(cat /sys/class/android_usb/android0/state 2>/dev/null "
                + "  || cat /sys/devices/virtual/android_usb/android0/state 2>/dev/null); "
                + "echo \"STATE:$STATE\"; "
                + "FUNCS=$(cat /sys/class/android_usb/android0/functions 2>/dev/null "
                + "  || cat /sys/devices/virtual/android_usb/android0/functions 2>/dev/null "
                + "  || getprop sys.usb.config 2>/dev/null); "
                + "echo \"FUNCS:$FUNCS\"; "
                + "ONLINE=$(cat /sys/class/power_supply/usb/online 2>/dev/null "
                + "  || cat /sys/class/power_supply/usb/present 2>/dev/null); "
                + "echo \"ONLINE:$ONLINE\"; "
                + "UIF=''; "
                + "for n in rndis0 usb0 ncm0 rndis_data0; do "
                + "  [ -d \"/sys/class/net/$n\" ] && UIF=$n && break; "
                + "done; "
                + "if [ -z \"$UIF\" ]; then "
                + "  UIF=$(ls /sys/class/net 2>/dev/null | grep -E '^(rndis|usb|ncm)' | head -n1); "
                + "fi; "
                + "echo \"UIF:$UIF\"; "
                + "if [ -n \"$UIF\" ]; then "
                + "  echo \"URX:$(cat /sys/class/net/$UIF/statistics/rx_bytes 2>/dev/null)\"; "
                + "  echo \"UTX:$(cat /sys/class/net/$UIF/statistics/tx_bytes 2>/dev/null)\"; "
                + "  echo \"OPER:$(cat /sys/class/net/$UIF/operstate 2>/dev/null)\"; "
                + "  echo \"FLAGS:$(cat /sys/class/net/$UIF/flags 2>/dev/null)\"; "
                + "fi; "
                // Prefer default-route device (real internet path), then busiest cellular iface
                + "MIF=''; MROUTE=0; "
                + "MIF=$(ip -4 route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}'); "
                + "case \"$MIF\" in "
                + "  ''|lo|wlan*|rndis*|ncm*|usb*|ifb*|dummy*|tun*|ap*) MIF='' ;; "
                + "  *) MROUTE=1 ;; "
                + "esac; "
                + "if [ -z \"$MIF\" ]; then "
                + "  MIF=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}'); "
                + "  case \"$MIF\" in "
                + "    ''|lo|wlan*|rndis*|ncm*|usb*|ifb*|dummy*|tun*|ap*) MIF='' ;; "
                + "    *) MROUTE=1 ;; "
                + "  esac; "
                + "fi; "
                + "if [ -z \"$MIF\" ]; then "
                + "  BEST=''; BESTOT=-1; "
                + "  for n in /sys/class/net/*; do "
                + "    bn=$(basename \"$n\"); "
                + "    case \"$bn\" in "
                + "      rmnet*|ccmni*|pdp*|wwan*|seth*|v4-rmnet*) "
                + "        rx=$(cat \"$n/statistics/rx_bytes\" 2>/dev/null || echo 0); "
                + "        tx=$(cat \"$n/statistics/tx_bytes\" 2>/dev/null || echo 0); "
                + "        tot=$((rx+tx)); "
                + "        if [ \"$tot\" -gt \"$BESTOT\" ] 2>/dev/null; then BESTOT=$tot; BEST=$bn; fi; "
                + "        ;; "
                + "    esac; "
                + "  done; "
                + "  MIF=$BEST; "
                + "fi; "
                + "echo \"MIF:$MIF\"; "
                + "echo \"MROUTE:$MROUTE\"; "
                // Also emit SUM of all cellular ifaces (more reliable than one idle rmnet_data0)
                + "SRX=0; STX=0; "
                + "for n in /sys/class/net/*; do "
                + "  bn=$(basename \"$n\"); "
                + "  case \"$bn\" in "
                + "    rmnet*|ccmni*|pdp*|wwan*|seth*|v4-rmnet*) "
                + "      rx=$(cat \"$n/statistics/rx_bytes\" 2>/dev/null || echo 0); "
                + "      tx=$(cat \"$n/statistics/tx_bytes\" 2>/dev/null || echo 0); "
                + "      SRX=$((SRX+rx)); STX=$((STX+tx)); "
                + "      ;; "
                + "  esac; "
                + "done; "
                + "echo \"MSUMRX:$SRX\"; "
                + "echo \"MSUMTX:$STX\"; "
                + "if [ -n \"$MIF\" ] && [ -d \"/sys/class/net/$MIF\" ]; then "
                + "  echo \"MRX:$(cat /sys/class/net/$MIF/statistics/rx_bytes 2>/dev/null)\"; "
                + "  echo \"MTX:$(cat /sys/class/net/$MIF/statistics/tx_bytes 2>/dev/null)\"; "
                + "else "
                + "  echo \"MRX:$SRX\"; "
                + "  echo \"MTX:$STX\"; "
                + "fi\n";

        String out = RootUtil.readCommandOutput(script);
        if (out == null || out.trim().isEmpty()) return null;

        RootSample r = new RootSample();
        long mSumRx = -1, mSumTx = -1;
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("SPEED:")) {
                String v = line.substring(6).trim();
                if (!v.isEmpty() && !v.equals("0")) r.speedRaw = v;
            } else if (line.startsWith("STATE:")) {
                String st = line.substring(6).trim().toUpperCase(Locale.US);
                if (!st.isEmpty()) {
                    r.gadgetConnected = st.contains("CONFIGURED") || st.contains("CONNECTED")
                            || st.equals("ONLINE");
                }
            } else if (line.startsWith("FUNCS:")) {
                String v = line.substring(6).trim().toLowerCase(Locale.US);
                if (!v.isEmpty()) r.gadgetFunctions = v;
            } else if (line.startsWith("ONLINE:")) {
                String v = line.substring(7).trim();
                if ("1".equals(v)) r.gadgetConnected = true;
            } else if (line.startsWith("UIF:")) {
                String v = line.substring(4).trim();
                if (!v.isEmpty()) r.usbIface = v;
            } else if (line.startsWith("MIF:")) {
                String v = line.substring(4).trim();
                if (!v.isEmpty()) r.mobileIface = v;
            } else if (line.startsWith("MROUTE:")) {
                r.mobileFromRoute = "1".equals(line.substring(7).trim());
            } else if (line.startsWith("MSUMRX:")) {
                mSumRx = parseLongSafe(line.substring(7).trim());
            } else if (line.startsWith("MSUMTX:")) {
                mSumTx = parseLongSafe(line.substring(7).trim());
            } else if (line.startsWith("URX:")) {
                r.usbRx = parseLongSafe(line.substring(4).trim());
            } else if (line.startsWith("UTX:")) {
                r.usbTx = parseLongSafe(line.substring(4).trim());
            } else if (line.startsWith("MRX:")) {
                r.mobRx = parseLongSafe(line.substring(4).trim());
            } else if (line.startsWith("MTX:")) {
                r.mobTx = parseLongSafe(line.substring(4).trim());
            } else if (line.startsWith("OPER:")) {
                r.operstate = line.substring(5).trim();
            } else if (line.startsWith("FLAGS:")) {
                r.flags = line.substring(6).trim();
            }
        }
        // Prefer cellular sum when single-iface counters look empty but sum has data
        if (mSumRx > 0 || mSumTx > 0) {
            if (r.mobRx < 0 || r.mobTx < 0
                    || (r.mobRx == 0 && r.mobTx == 0 && (mSumRx > 0 || mSumTx > 0))) {
                r.mobRx = mSumRx;
                r.mobTx = mSumTx;
                if (r.mobileIface == null) r.mobileIface = "cellular";
                r.mobileFromRoute = false;
            }
        }
        return r;
    }

    // ── Local helpers ────────────────────────────────────────────────────

    public static String readUsbGadgetSpeed() {
        String local = readUsbGadgetSpeedLocal();
        if (!isUnknownSpeed(local)) return local;
        String viaRoot = RootUtil.readCommandOutput(
                "for f in /sys/class/udc/*/current_speed "
                        + "/sys/class/power_supply/usb/speed "
                        + "/sys/class/android_usb/android0/speed; do "
                        + "  for g in $f; do [ -r \"$g\" ] || continue; "
                        + "    v=$(cat \"$g\" 2>/dev/null | tr -d '\\r'); "
                        + "    [ -n \"$v\" ] && [ \"$v\" != \"0\" ] && echo \"$v\" && exit 0; "
                        + "  done; done; exit 0");
        if (viaRoot != null && !viaRoot.trim().isEmpty()) {
            return normalizeSpeed(viaRoot.trim().split("\n")[0]);
        }
        return "unknown";
    }

    private static String readUsbGadgetSpeedLocal() {
        if (!canTryGadgetSysfs()) return "unknown";
        String[] paths = {
                "/sys/class/power_supply/usb/speed",
                "/sys/class/power_supply/usb/usb_speed",
                "/sys/class/android_usb/android0/speed",
                "/sys/devices/virtual/android_usb/android0/speed",
                "/sys/devices/virtual/android_usb/android0/f_rndis/speed",
        };
        for (String path : paths) {
            String v = readFirstLineLocal(path);
            if (v != null && !v.isEmpty() && !v.equals("0")) return normalizeSpeed(v);
        }
        File udc = new File("/sys/class/udc");
        if (udc.isDirectory()) {
            File[] kids = udc.listFiles();
            if (kids != null) {
                for (File d : kids) {
                    String v = readFirstLineLocal(new File(d, "current_speed").getPath());
                    if (v != null && !v.isEmpty() && !v.equals("0")) return normalizeSpeed(v);
                }
            }
        }
        return "unknown";
    }

    static String normalizeSpeed(String raw) {
        if (raw == null) return "unknown";
        String r = raw.trim().toLowerCase(Locale.US);
        if (r.isEmpty() || r.equals("0") || r.equals("unknown") || r.equals("usb_speed_unknown")) {
            return "unknown";
        }

        if (r.contains("super-speed-plus") || r.contains("super_speed_plus")
                || r.contains("super+") || r.contains("ssp") || r.contains("gen2")) {
            return "super-speed+";
        }
        if (r.contains("super") || r.equals("ss") || r.contains("super_speed")) {
            return "super-speed";
        }
        if (r.contains("high") || r.equals("hs") || r.contains("high_speed")) {
            return "high-speed";
        }
        if (r.contains("full") || r.equals("fs") || r.contains("full_speed")) return "full-speed";
        if (r.contains("low") || r.equals("ls") || r.contains("low_speed")) return "low-speed";
        if (r.contains("wireless")) return "wireless";

        if (r.matches("\\d+")) {
            try {
                int n = Integer.parseInt(r);
                switch (n) {
                    case 1: return "low-speed";
                    case 2: return "full-speed";
                    case 3: return "high-speed";
                    case 4: return "wireless";
                    case 5: return "super-speed";
                    case 6:
                    case 7: return "super-speed+";
                    default: break;
                }
                if (n == 12) return "full-speed";
                if (n == 480) return "high-speed";
                if (n >= 10000) return "super-speed+";
                if (n >= 5000) return "super-speed";
                if (n >= 480) return "high-speed";
                if (n >= 12) return "full-speed";
                if (n >= 1) return "low-speed";
            } catch (NumberFormatException ignored) {}
        }
        if (r.equals("1.5") || r.startsWith("1.5")) return "low-speed";
        return r;
    }

    public static String findTetherIface() {
        String local = findTetherIfaceLocal();
        if (local != null) return local;
        String out = RootUtil.readCommandOutput(
                "for n in rndis0 usb0 ncm0 rndis_data0; do "
                        + "[ -d /sys/class/net/$n ] && echo $n && exit 0; done; "
                        + "ls /sys/class/net 2>/dev/null | grep -E '^(rndis|usb|ncm)' | head -n1");
        if (out != null) {
            String n = out.trim().split("\n")[0].trim();
            if (!n.isEmpty()) return n;
        }
        return null;
    }

    private static String findTetherIfaceLocal() {
        if (!canUseLocalNetSysfs()) return null;
        for (String name : TETHER_IFACES) {
            if (new File("/sys/class/net/" + name).exists()) return name;
        }
        File net = new File("/sys/class/net");
        File[] kids = net.listFiles();
        if (kids != null) {
            for (File d : kids) {
                String n = d.getName();
                if (n.startsWith("rndis") || n.startsWith("ncm") || n.matches("usb\\d+")) {
                    return n;
                }
            }
        }
        return null;
    }

    private static String findMobileIfaceLocal() {
        if (!canUseLocalNetSysfs()) return null;
        for (String name : MOBILE_IFACES) {
            if (new File("/sys/class/net/" + name).exists()) return name;
        }
        File net = new File("/sys/class/net");
        File[] kids = net.listFiles();
        if (kids != null) {
            for (File d : kids) {
                String n = d.getName();
                if (n.startsWith("rmnet") || n.startsWith("pdp")
                        || n.startsWith("ccmni") || n.startsWith("wwan")) {
                    return n;
                }
            }
        }
        return null;
    }

    private static String statPath(String iface, String counter) {
        return "/sys/class/net/" + iface + "/statistics/" + counter;
    }

    private static boolean isIfaceUp(String iface, RootSample root) {
        if (root != null && iface != null && iface.equals(root.usbIface)) {
            if (root.operstate != null) {
                String o = root.operstate.toLowerCase(Locale.US);
                if (o.equals("up") || o.equals("unknown")) return true;
                if (o.equals("down")) return false;
            }
            if (root.flags != null) {
                try {
                    return (Long.decode(root.flags.trim()) & 0x1) != 0;
                } catch (Exception ignored) {}
            }
            // Counters present via root usually means iface is usable
            if (root.usbRx >= 0) return true;
        }
        if (canUseLocalNetSysfs()) {
            String oper = readFirstLineLocal("/sys/class/net/" + iface + "/operstate");
            if (oper != null && (oper.equals("up") || oper.equals("unknown"))) return true;
            String flags = readFirstLineLocal("/sys/class/net/" + iface + "/flags");
            try {
                if (flags != null) return (Long.decode(flags.trim()) & 0x1) != 0;
            } catch (Exception ignored) {}
        }
        if (root != null && root.usbRx >= 0) return true;
        return false;
    }

    private static long readLongSysfsLocal(String path) {
        return parseLongSafe(readFirstLineLocal(path));
    }

    private static long parseLongSafe(String line) {
        if (line == null) return -1;
        try {
            return Long.parseLong(line.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readFirstLineLocal(String path) {
        return readFirstLineLocalUncached(path);
    }

    private static String readFirstLineLocalUncached(String path) {
        File f = new File(path);
        try {
            if (!f.exists()) return null;
        } catch (SecurityException e) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine();
            return line != null ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
