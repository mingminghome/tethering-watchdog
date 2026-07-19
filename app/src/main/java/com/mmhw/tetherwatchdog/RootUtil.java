package com.mmhw.tetherwatchdog;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Root helpers for network bounce + USB tethering optimisation.
 * Prefer one su session so steps run in order without re-auth races.
 * Caches root availability so UI polls do not spawn needless {@code su} probes.
 */
public class RootUtil {

    /** How long a positive root result is trusted. */
    private static final long ROOT_OK_TTL_MS = 60_000L;
    /** Retry su often when denied — Magisk grant for a new release signature is common. */
    private static final long ROOT_FAIL_RETRY_MS = 5_000L;
    private static final long SU_TIMEOUT_MS = 12_000L;

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
     * Bounce mobile data, re-apply tethering optimisations, force RNDIS.
     * Invokes {@code onComplete} on a background thread when finished.
     */
    public static void performResetSequence(Runnable onComplete) {
        new Thread(() -> {
            try {
                String script = ""
                        + "svc data disable\n"
                        + "ndc resolver flushdefaultiface 2>/dev/null || true\n"
                        + "ndc resolver flushif 2>/dev/null || true\n"
                        + "sysctl -w net.ipv4.tcp_window_scaling=1 2>/dev/null || true\n"
                        + "sysctl -w net.core.rmem_max=16777216 2>/dev/null || true\n"
                        + "sysctl -w net.core.wmem_max=16777216 2>/dev/null || true\n"
                        + "sysctl -w net.ipv4.tcp_rmem='4096 87380 16777216' 2>/dev/null || true\n"
                        + "sysctl -w net.ipv4.tcp_wmem='4096 65536 16777216' 2>/dev/null || true\n"
                        + "sysctl -w net.ipv4.tcp_congestion_control=bbr 2>/dev/null || true\n"
                        + "sysctl -w net.ipv4.ip_forward=1 2>/dev/null || true\n"
                        + "echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true\n"
                        + "sleep 2\n"
                        + "svc data enable\n"
                        + "sleep 4\n"
                        + "svc usb setFunctions rndis,adb\n"
                        + "sleep 2\n"
                        + "for IF in rndis0 usb0 ncm0; do\n"
                        + "  if [ -d /sys/class/net/$IF ]; then\n"
                        + "    ifconfig $IF mtu 1440 2>/dev/null || ip link set dev $IF mtu 1440 2>/dev/null || true\n"
                        + "    ifconfig $IF up 2>/dev/null || ip link set dev $IF up 2>/dev/null || true\n"
                        + "  fi\n"
                        + "done\n"
                        + "iptables -t mangle -D POSTROUTING -j TTL --ttl-set 64 2>/dev/null || true\n"
                        + "iptables -t mangle -A POSTROUTING -j TTL --ttl-set 64 2>/dev/null || true\n"
                        + "ip route del default dev wlan0 2>/dev/null || true\n"
                        + "MOBILE=$(ip -4 route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}')\n"
                        + "case \"$MOBILE\" in ''|lo|wlan*|rndis*|ncm*|usb*) MOBILE='' ;; esac\n"
                        + "if [ -z \"$MOBILE\" ]; then\n"
                        + "  MOBILE=$(ip -o link show 2>/dev/null | awk -F': ' '{print $2}' | "
                        + "grep -E '^(rmnet_data[0-9]+|rmnet[0-9]+|pdp_ip0|ccmni0)$' | head -n1)\n"
                        + "fi\n"
                        + "[ -n \"$MOBILE\" ] || MOBILE=rmnet_data0\n"
                        + "ip route replace default dev \"$MOBILE\" 2>/dev/null || "
                        + "ip route add default dev \"$MOBILE\" 2>/dev/null || true\n";

                String out = runAsRoot(script);
                if (out != null) markRoot(true);
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, "tether-reset").start();
    }

    public static void performResetSequence() {
        performResetSequence(null);
    }

    /** Prefer mobile data as default route (no full data bounce). */
    public static void forceMobileDataPriority() {
        new Thread(() -> {
            String out = runAsRoot(
                    "ip route del default dev wlan0 2>/dev/null || true\n"
                            + "MOBILE=$(ip -4 route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}')\n"
                            + "case \"$MOBILE\" in ''|lo|wlan*|rndis*|ncm*|usb*) MOBILE='' ;; esac\n"
                            + "if [ -z \"$MOBILE\" ]; then\n"
                            + "  MOBILE=$(ip -o link show 2>/dev/null | awk -F': ' '{print $2}' | "
                            + "grep -E '^(rmnet_data[0-9]+|rmnet[0-9]+|pdp_ip0|ccmni0)$' | head -n1)\n"
                            + "fi\n"
                            + "[ -n \"$MOBILE\" ] || MOBILE=rmnet_data0\n"
                            + "ip route replace default dev \"$MOBILE\" 2>/dev/null || "
                            + "ip route add default dev \"$MOBILE\" 2>/dev/null || true\n"
                            + "echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true\n"
            );
            if (out != null) markRoot(true);
        }, "tether-route").start();
    }

    /** @return stdout if process exits 0 or produced output, else null */
    private static String runAsRoot(String scriptBody) {
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
            boolean finished = p.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
