package com.mmhw.tetherwatchdog;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Root helpers for network bounce + tether optimisation.
 * USB gadget (RNDIS) and ethernet-hub paths share TCP/uplink tweaks but use
 * different link-layer tunings (see {@link #ENABLE_USB} vs {@link #TUNE_ETHERNET}).
 * Prefer one su session so steps run in order without re-auth races.
 * Caches root availability so UI polls do not spawn needless {@code su} probes.
 */
public class RootUtil {

    /** How long a positive root result is trusted. */
    private static final long ROOT_OK_TTL_MS = 60_000L;
    /** Retry su often when denied — Magisk grant for a new release signature is common. */
    private static final long ROOT_FAIL_RETRY_MS = 5_000L;
    private static final long SU_TIMEOUT_MS = 12_000L;
    /** Reset/enable scripts sleep for radio + autoneg; a 12s kill leaves tethering half-applied. */
    private static final long SU_RESET_TIMEOUT_MS = 30_000L;

    private static final AtomicReference<Boolean> rootAvailable = new AtomicReference<>(null);
    private static final AtomicLong rootProbedAtMs = new AtomicLong(0);
    private static final AtomicBoolean rootProbeInFlight = new AtomicBoolean(false);

    public static boolean execute(String command) {
        String out = runAsRoot(command + "\n");
        if (out != null) {
            markRoot(true);
            return true;
        }
        return false;
    }

    /**
     * Run a command via su and return stdout (null on failure).
     * Falls back to plain shell when su is unavailable (limited without root).
     * Re-tries su every few seconds after a denial so Magisk can grant the new APK.
     */
    public static String readCommandOutput(String command) {
        if (shouldTrySu()) {
            String viaSu = runAsRoot(command + "\n");
            if (viaSu != null) {
                markRoot(true);
                return viaSu;
            }
            markRoot(false);
        }
        return runShell(command);
    }

    private static boolean shouldTrySu() {
        Boolean v = rootAvailable.get();
        if (v == null || v) return true; // unknown or previously OK — always try su
        long age = System.currentTimeMillis() - rootProbedAtMs.get();
        return age >= ROOT_FAIL_RETRY_MS; // denied: retry every few seconds
    }

    /** Cached root check — safe to call often from UI threads' workers. */
    public static boolean hasRootCached() {
        Boolean v = rootAvailable.get();
        long age = System.currentTimeMillis() - rootProbedAtMs.get();
        if (v != null && v && age < ROOT_OK_TTL_MS) return true;
        if (v != null && !v && age < ROOT_FAIL_RETRY_MS) return false;
        return probeRoot();
    }

    /** Call after Magisk grant / app resume to force a new su check. */
    public static void invalidateRootCache() {
        rootAvailable.set(null);
        rootProbedAtMs.set(0);
    }

    public static boolean probeRoot() {
        if (!rootProbeInFlight.compareAndSet(false, true)) {
            Boolean v = rootAvailable.get();
            return v != null && v;
        }
        try {
            String out = runAsRoot("echo root_ok\n");
            boolean ok = out != null && out.contains("root_ok");
            markRoot(ok);
            return ok;
        } finally {
            rootProbeInFlight.set(false);
        }
    }

    private static void markRoot(boolean ok) {
        rootAvailable.set(ok);
        rootProbedAtMs.set(System.currentTimeMillis());
    }

    /**
     * Bounce mobile data, re-apply tethering optimisations, enable the detected
     * tether path (ethernet hub vs USB RNDIS). Invokes {@code onComplete} on a
     * background thread when finished.
     * <p>
     * Ethernet tethering is left running: stopping it releases EthernetManager's
     * tethered-iface request, the NIC can drop, Settings greys out the toggle,
     * and a follow-up start often cannot claim the iface again.
     */
    public static void performResetSequence(Runnable onComplete) {
        performResetSequence(null, onComplete);
    }

    public static void performResetSequence(Context context, Runnable onComplete) {
        new Thread(() -> {
            try {
                String mode = probeTetherMode();
                Context app = context != null ? context.getApplicationContext() : null;
                String out = runAsRoot(buildResetScript(mode), SU_RESET_TIMEOUT_MS);
                if (out != null) markRoot(true);
                if (app != null) {
                    // Idempotent if still up; restores if the data bounce dropped it.
                    startFrameworkTethering(app, mode);
                    sleepQuietly(800);
                    startFrameworkTethering(app, mode);
                }
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, "tether-reset").start();
    }

    public static void performResetSequence() {
        performResetSequence(null, null);
    }

    /**
     * Enable tethering for the current attachment without bouncing mobile data.
     * Used when a USB hub/ethernet adapter appears (RNDIS would drop the hub).
     * Never stop first — that is what greys out Settings ethernet tethering.
     */
    public static void enableDetectedTethering(Context context, String mode) {
        new Thread(() -> {
            Context app = context != null ? context.getApplicationContext() : null;
            String out = runAsRoot(buildEnableTetherScript(mode), SU_RESET_TIMEOUT_MS);
            if (out != null) markRoot(true);
            if (app != null) {
                startFrameworkTethering(app, mode);
            }
        }, "tether-enable").start();
    }

    /**
     * Hub/ethernet adapter present → ethernet tethering; otherwise USB gadget.
     * Prefers a local {@link NetworkInterface} scan (no su).
     */
    public static String probeTetherMode() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en != null) {
                for (NetworkInterface ni : Collections.list(en)) {
                    if (ni != null && UsbLinkMonitor.isEthernetIfaceName(ni.getName())) {
                        return UsbLinkMonitor.MODE_ETHERNET;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String viaRoot = readCommandOutput(
                "for n in eth0 eth1 eth2 usbeth0 lan0 lan1; do "
                        + "[ -d /sys/class/net/$n ] && echo ethernet && exit 0; done; "
                        + "ls /sys/class/net 2>/dev/null | grep -E '^(eth[0-9]+|usbeth|lan[0-9]+|enx)' "
                        + "| head -n1 | grep -q . && echo ethernet && exit 0; "
                        + "echo usb");
        if (viaRoot != null && viaRoot.contains("ethernet")) {
            return UsbLinkMonitor.MODE_ETHERNET;
        }
        return UsbLinkMonitor.MODE_USB;
    }

    /** ConnectivityManager.TETHERING_USB / TetheringManager.TETHERING_ETHERNET. */
    private static final int TETHERING_USB = 1;
    private static final int TETHERING_ETHERNET = 5;

    /**
     * Ask the framework tethering stack (DHCP + NAT) to start. Same path as
     * Settings ethernet tethering. Hidden APIs may fail without
     * {@code TETHER_PRIVILEGED}; the root script also calls {@code cmd tethering}
     * as uid 0. Start is idempotent if tethering is already on.
     */
    static boolean startFrameworkTethering(Context context, String mode) {
        if (context == null) return false;
        int type = tetherType(mode);
        if (startTetheringManager(context, type)) return true;
        return startConnectivityTethering(context, type);
    }

    private static int tetherType(String mode) {
        return UsbLinkMonitor.MODE_ETHERNET.equals(mode) ? TETHERING_ETHERNET : TETHERING_USB;
    }

    private static boolean startTetheringManager(Context context, int type) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            Object tm = context.getSystemService("tethering");
            if (tm == null) return false;
            Class<?> builderClz = Class.forName(
                    "android.net.TetheringManager$TetheringRequest$Builder");
            Object builder = builderClz.getConstructor(int.class).newInstance(type);
            try {
                builderClz.getMethod("setExemptFromEntitlementCheck", boolean.class)
                        .invoke(builder, true);
            } catch (Exception ignored) {
            }
            Object request = builderClz.getMethod("build").invoke(builder);
            Class<?> reqClz = Class.forName("android.net.TetheringManager$TetheringRequest");
            Class<?> cbClz = Class.forName("android.net.TetheringManager$StartTetheringCallback");
            Object cb = silentCallback(cbClz);
            for (Method m : tm.getClass().getMethods()) {
                if (!"startTethering".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && reqClz.isAssignableFrom(ps[0])) {
                    m.invoke(tm, request, (Executor) Runnable::run, cb);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean startConnectivityTethering(Context context, int type) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Class<?> cbClz = Class.forName(
                    "android.net.ConnectivityManager$OnStartTetheringCallback");
            Object cb = silentCallback(cbClz);
            Method start = cm.getClass().getMethod(
                    "startTethering", int.class, boolean.class, cbClz);
            start.invoke(cm, type, false, cb);
            return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Avoid NPEs if the framework stores the proxy in a map (equals/hashCode). */
    private static Object silentCallback(Class<?> cbClz) {
        return Proxy.newProxyInstance(cbClz.getClassLoader(), new Class<?>[]{cbClz}, (p, m, a) -> {
            String name = m.getName();
            if ("hashCode".equals(name)) return System.identityHashCode(p);
            if ("equals".equals(name)) {
                return a != null && a.length > 0 && p == a[0];
            }
            if ("toString".equals(name)) return "TetherWatchdogCb";
            Class<?> ret = m.getReturnType();
            if (ret == boolean.class) return Boolean.FALSE;
            if (ret == int.class) return 0;
            if (ret == long.class) return 0L;
            return null;
        });
    }

    /**
     * Shared uplink / TCP tweaks (both USB and ethernet).
     * Ethernet then adds link-layer tunings (MSS 1400, EEE off, GRO off);
     * USB keeps the RNDIS 1440 MTU workaround.
     */
    private static final String TCP_AND_FORWARD =
            "sysctl -w net.ipv4.tcp_window_scaling=1 2>/dev/null || true\n"
                    + "sysctl -w net.core.rmem_max=16777216 2>/dev/null || true\n"
                    + "sysctl -w net.core.wmem_max=16777216 2>/dev/null || true\n"
                    + "sysctl -w net.ipv4.tcp_rmem='4096 87380 16777216' 2>/dev/null || true\n"
                    + "sysctl -w net.ipv4.tcp_wmem='4096 65536 16777216' 2>/dev/null || true\n"
                    + "sysctl -w net.ipv4.tcp_congestion_control=bbr 2>/dev/null "
                    + "|| sysctl -w net.ipv4.tcp_congestion_control=cubic 2>/dev/null || true\n"
                    + "sysctl -w net.ipv4.tcp_mtu_probing=1 2>/dev/null || true\n"
                    + "sysctl -w net.ipv4.tcp_slow_start_after_idle=0 2>/dev/null || true\n"
                    + "sysctl -w net.core.netdev_max_backlog=5000 2>/dev/null || true\n"
                    + "sysctl -w net.netfilter.nf_conntrack_max=262144 2>/dev/null || true\n"
                    + "sysctl -w net.ipv4.ip_forward=1 2>/dev/null || true\n"
                    + "echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true\n";

    /** Shared prefix: data bounce, resolver flush, TCP/BBR, IP forwarding. */
    private static final String RESET_COMMON =
            "svc data disable\n"
                    + "ndc resolver flushdefaultiface 2>/dev/null || true\n"
                    + "ndc resolver flushif 2>/dev/null || true\n"
                    + TCP_AND_FORWARD
                    + "sleep 2\n"
                    + "svc data enable\n"
                    + "sleep 4\n";

    /**
     * Pick ethernet iface if a hub/adapter is attached; empty otherwise.
     * Must not match rndis/ncm gadget names.
     */
    private static final String DETECT_ETH =
            "ETH=''\n"
                    + "for n in eth0 eth1 eth2 usbeth0 lan0 lan1; do\n"
                    + "  [ -d /sys/class/net/$n ] && ETH=$n && break\n"
                    + "done\n"
                    + "if [ -z \"$ETH\" ]; then\n"
                    + "  ETH=$(ls /sys/class/net 2>/dev/null | grep -E '^(eth[0-9]+|usbeth|lan[0-9]+|enx)' | head -n1)\n"
                    + "fi\n";

    /**
     * Drop Wi‑Fi / ethernet defaults so tether uplink is cellular. Keep the
     * radio's existing default (gateway + iface). Do not replace it with a
     * gateway-less route or a hardcoded {@code rmnet_data0} — that pins traffic
     * to the first PDN (often LTE/IMS) while 5G is on another rmnet.
     */
    private static final String PICK_MOBILE =
            "ip -4 route show default 2>/dev/null | while read -r line; do\n"
                    + "  d=$(echo \"$line\" | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}')\n"
                    + "  case \"$d\" in rmnet*|ccmni*|pdp*|wwan*|seth*|v4-rmnet*|cdc*) ;; "
                    + "*) ip route del default dev \"$d\" 2>/dev/null || true ;; esac\n"
                    + "done\n"
                    + "MOBILE=$(ip -4 route show default 2>/dev/null | awk "
                    + "'{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}')\n"
                    + "case \"$MOBILE\" in ''|lo|wlan*|rndis*|ncm*|usb*|eth*|lan*|enx*) MOBILE='' ;; esac\n"
                    + "if [ -z \"$MOBILE\" ]; then\n"
                    + "  MOBILE=$(ip -4 route show table all default 2>/dev/null | awk "
                    + "'{for(i=1;i<=NF;i++) if($i==\"dev\") d=$(i+1)} "
                    + "d ~ /^(rmnet|ccmni|pdp|wwan|seth|v4-rmnet|cdc)/ {print d; exit}')\n"
                    + "fi\n"
                    + "if [ -z \"$MOBILE\" ]; then\n"
                    + "  MOBILE=$(for n in /sys/class/net/*; do\n"
                    + "    b=$(basename \"$n\")\n"
                    + "    case \"$b\" in rmnet*|ccmni*|pdp*|wwan*|seth*|v4-rmnet*|cdc*)\n"
                    + "      st=$(cat \"$n/operstate\" 2>/dev/null || echo down)\n"
                    + "      [ \"$st\" = up ] || [ \"$st\" = unknown ] || continue\n"
                    + "      ip -4 -o addr show dev \"$b\" 2>/dev/null | grep -q inet || continue\n"
                    + "      rx=$(cat \"$n/statistics/rx_bytes\" 2>/dev/null || echo 0)\n"
                    + "      echo \"$rx $b\"\n"
                    + "    ;; esac\n"
                    + "  done | sort -nr | awk '{print $2; exit}')\n"
                    + "fi\n"
                    + "if [ -n \"$MOBILE\" ] && [ -d /sys/class/net/$MOBILE ]; then\n"
                    + "  if ! ip -4 route show default 2>/dev/null | grep -q .; then\n"
                    + "    ip route add default dev \"$MOBILE\" 2>/dev/null || true\n"
                    + "  fi\n"
                    + "fi\n";

    /**
     * Same control plane as Settings; uid 0 via su. Unknown cmds are ignored.
     * WRITE_SETTINGS appop lets the in-process TetheringManager.startTethering call
     * succeed if the shell cmd is missing on this build.
     */
    private static final String GRANT_TETHER_APPOPS =
            "cmd appops set com.mmhw.tetherwatchdog WRITE_SETTINGS allow 2>/dev/null || true\n"
                    + "appops set com.mmhw.tetherwatchdog WRITE_SETTINGS allow 2>/dev/null || true\n";

    private static final String START_ETHERNET_TETHER_CMDS =
            GRANT_TETHER_APPOPS
                    + "cmd tethering start ethernet 2>/dev/null || true\n"
                    + "cmd tethering start-tethering ethernet 2>/dev/null || true\n"
                    + "cmd tethering start-tethering 5 2>/dev/null || true\n";

    private static final String START_USB_TETHER_CMDS =
            GRANT_TETHER_APPOPS
                    + "cmd tethering start usb 2>/dev/null || true\n"
                    + "cmd tethering start-tethering usb 2>/dev/null || true\n";

    /**
     * Ethernet hub/dock tunings only — do not take over DHCP/IP/NAT and do not
     * stop framework tethering. {@code ndc tether start} / static 192.168.42.129
     * bricks Settings ethernet tethering. Do not force {@code ethtool speed 100}:
     * that caps gigabit and can fall back to 10BASE-T. Unknown/-1 speed is not
     * 10 Mbps. Skip unconditional EEE-off: toggling EEE flaps many USB NICs,
     * Android then turns ethernet tethering off, and Settings greys it out.
     */
    private static final String TUNE_ETHERNET =
            "if [ -n \"$ETH\" ]; then\n"
                    + "  DEV=$(readlink -f /sys/class/net/$ETH/device 2>/dev/null)\n"
                    + "  n=0\n"
                    + "  while [ -n \"$DEV\" ] && [ \"$DEV\" != / ] && [ \"$n\" -lt 8 ]; do\n"
                    + "    [ -f \"$DEV/power/control\" ] && echo on > \"$DEV/power/control\" 2>/dev/null || true\n"
                    + "    [ -f \"$DEV/power/autosuspend\" ] && echo -1 > \"$DEV/power/autosuspend\" 2>/dev/null || true\n"
                    + "    [ -f \"$DEV/power/autosuspend_delay_ms\" ] && echo -1 > \"$DEV/power/autosuspend_delay_ms\" 2>/dev/null || true\n"
                    + "    DEV=$(dirname \"$DEV\")\n"
                    + "    n=$((n+1))\n"
                    + "  done\n"
                    + "  ip link set \"$ETH\" up 2>/dev/null || ifconfig \"$ETH\" up 2>/dev/null || true\n"
                    + "  ip link set dev \"$ETH\" mtu 1500 2>/dev/null || ifconfig \"$ETH\" mtu 1500 2>/dev/null || true\n"
                    + "  ip link set dev \"$ETH\" txqueuelen 1000 2>/dev/null || true\n"
                    + "  ethtool -K \"$ETH\" gro off gso on tso on ufo off 2>/dev/null || true\n"
                    + "  CARRIER=$(cat /sys/class/net/$ETH/carrier 2>/dev/null || echo 0)\n"
                    + "  ETHSPEED=$(cat /sys/class/net/$ETH/speed 2>/dev/null || echo 0)\n"
                    + "  if [ \"$CARRIER\" = 1 ] && [ \"$ETHSPEED\" = 10 ]; then\n"
                    + "    ethtool --set-eee \"$ETH\" eee off 2>/dev/null || true\n"
                    + "    ethtool -s \"$ETH\" autoneg on 2>/dev/null || true\n"
                    + "    ethtool -r \"$ETH\" 2>/dev/null || true\n"
                    + "    sleep 2\n"
                    + "  fi\n"
                    + "  echo 1 > /proc/sys/net/ipv4/conf/all/forwarding 2>/dev/null || true\n"
                    + "  echo 1 > /proc/sys/net/ipv4/conf/$ETH/forwarding 2>/dev/null || true\n"
                    + "  [ -n \"$MOBILE\" ] && echo 1 > /proc/sys/net/ipv4/conf/$MOBILE/forwarding 2>/dev/null || true\n"
                    + "  echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true\n"
                    + "  echo 0 > /proc/sys/net/ipv4/conf/$ETH/rp_filter 2>/dev/null || true\n"
                    + "  [ -n \"$MOBILE\" ] && echo 2 > /proc/sys/net/ipv4/conf/$MOBILE/rp_filter 2>/dev/null || true\n"
                    + "  tc qdisc del dev \"$ETH\" root 2>/dev/null || true\n"
                    + "  [ -f /sys/class/net/$ETH/queues/rx-0/rps_cpus ] && "
                    + "echo f > /sys/class/net/$ETH/queues/rx-0/rps_cpus 2>/dev/null || true\n"
                    + "  ndc ipfwd enable tethering 2>/dev/null || true\n"
                    + "  iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true\n"
                    + "  iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1400 2>/dev/null || true\n"
                    + "  iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1400 2>/dev/null || true\n"
                    + "fi\n";

    /** AQM on the cellular hop — that is where tether bufferbloat lives. */
    private static final String WAN_AQM =
            "[ -n \"$MOBILE\" ] && tc qdisc replace dev \"$MOBILE\" root fq_codel 2>/dev/null || true\n";

    /** USB gadget tethering (direct cable to a host). */
    private static final String ENABLE_USB =
            "svc usb setFunctions rndis,adb\n"
                    + "sleep 2\n"
                    + "for IF in rndis0 usb0 ncm0; do\n"
                    + "  if [ -d /sys/class/net/$IF ]; then\n"
                    + "    ifconfig $IF mtu 1440 2>/dev/null || ip link set dev $IF mtu 1440 2>/dev/null || true\n"
                    + "    ifconfig $IF up 2>/dev/null || ip link set dev $IF up 2>/dev/null || true\n"
                    + "  fi\n"
                    + "done\n";

    private static final String TTL_FIX =
            "iptables -t mangle -D POSTROUTING -j TTL --ttl-set 64 2>/dev/null || true\n"
                    + "iptables -t mangle -A POSTROUTING -j TTL --ttl-set 64 2>/dev/null || true\n";

    static String buildResetScript() {
        return buildResetScript(null);
    }

    /**
     * @param mode {@link UsbLinkMonitor#MODE_ETHERNET} pins the ethernet branch so a
     *             momentary iface blip cannot fall through to RNDIS (that drops the hub).
     */
    static String buildResetScript(String mode) {
        return forceEthPrefix(mode)
                + DETECT_ETH
                + RESET_COMMON
                + DETECT_ETH
                + PICK_MOBILE
                + ethernetOrUsbBranch();
    }

    static String buildEnableTetherScript(String mode) {
        // Shell re-detects: never force RNDIS while an ethernet iface exists
        // (that would switch the phone to gadget mode and drop the hub).
        String tag = mode != null ? mode : "auto";
        return "echo MODE:" + tag + "\n"
                + forceEthPrefix(mode)
                + TCP_AND_FORWARD
                + DETECT_ETH
                + PICK_MOBILE
                + ethernetOrUsbBranch();
    }

    private static String forceEthPrefix(String mode) {
        return UsbLinkMonitor.MODE_ETHERNET.equals(mode) ? "FORCE_ETH=1\n" : "FORCE_ETH=\n";
    }

    /**
     * Ethernet when the NIC is present <em>or</em> the Java probe already saw a
     * hub. Never {@code svc usb setFunctions rndis} in that case.
     */
    private static String ethernetOrUsbBranch() {
        return "if [ -n \"$ETH\" ] || [ -n \"$FORCE_ETH\" ]; then\n"
                + TUNE_ETHERNET
                + START_ETHERNET_TETHER_CMDS
                + "else\n"
                + ENABLE_USB
                + START_USB_TETHER_CMDS
                + "fi\n"
                + WAN_AQM
                + TTL_FIX;
    }

    /** Prefer mobile data as default route (no full data bounce). */
    public static void forceMobileDataPriority() {
        new Thread(() -> {
            String out = runAsRoot(
                    PICK_MOBILE
                            + "echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true\n"
            );
            if (out != null) markRoot(true);
        }, "tether-route").start();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return stdout if process exits 0 or produced output, else null */
    private static String runAsRoot(String scriptBody) {
        return runAsRoot(scriptBody, SU_TIMEOUT_MS);
    }

    private static String runAsRoot(String scriptBody, long timeoutMs) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su"});
            // Drain stderr so a full pipe cannot deadlock su.
            final Process proc = p;
            Thread errDrain = new Thread(() -> drain(proc.getErrorStream()), "su-err");
            errDrain.setDaemon(true);
            errDrain.start();

            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(scriptBody);
            if (!scriptBody.endsWith("\n")) {
                os.writeBytes("\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            String out = readAll(p.getInputStream());
            long waitMs = timeoutMs > 0 ? timeoutMs : SU_TIMEOUT_MS;
            boolean finished = p.waitFor(waitMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            int code = p.exitValue();
            if (code == 0) return out != null ? out : "";
            return out != null && !out.isEmpty() ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static String runShell(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            final Process proc = p;
            Thread errDrain = new Thread(() -> drain(proc.getErrorStream()), "sh-err");
            errDrain.setDaemon(true);
            errDrain.start();
            String out = readAll(p.getInputStream());
            boolean finished = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void drain(java.io.InputStream in) {
        try {
            byte[] buf = new byte[512];
            while (in.read(buf) >= 0) {
                // discard
            }
        } catch (Exception ignored) {
        }
    }

    private static String readAll(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
