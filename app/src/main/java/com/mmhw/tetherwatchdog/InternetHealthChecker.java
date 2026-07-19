package com.mmhw.tetherwatchdog;

import android.os.SystemClock;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * Lightweight uplink health probe — designed not to steal tethering bandwidth.
 * <p>
 * Uses a single TCP connect (handshake only, no HTTP body) about once per minute.
 * That is on the order of a few hundred bytes per minute — negligible next to
 * normal tether traffic, and far lighter than speed tests or continuous ping floods.
 */
public class InternetHealthChecker {

    /** How often to probe while watchdog is running. */
    public static final long INTERVAL_MS = 60_000L;

    /** Ignore probes for a while after a reset (radio is still settling). */
    public static final long GRACE_AFTER_RESET_MS = 60_000L;

    /** Require several consecutive failures before declaring DOWN / auto-reset. */
    public static final int FAIL_THRESHOLD = 3;

    /** Short timeout so a dead path fails fast without blocking the worker long. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    // Stable anycast endpoints; connect only (no payload).
    private static final String[][] PROBES = {
            {"1.1.1.1", "443"},
            {"8.8.8.8", "53"},
    };

    private int consecutiveFails = 0;
    private long lastRttMs = -1;
    private String lastTarget = "—";
    private boolean lastOk = true;
    private long lastProbeMs = 0;

    public static class Result {
        public final boolean ok;
        public final int consecutiveFails;
        public final long rttMs;
        public final String target;
        public final String label; // OK / DEGRADED / DOWN / SKIP
        public final String detail;

        Result(boolean ok, int consecutiveFails, long rttMs, String target, String label, String detail) {
            this.ok = ok;
            this.consecutiveFails = consecutiveFails;
            this.rttMs = rttMs;
            this.target = target;
            this.label = label;
            this.detail = detail;
        }

        public String summaryLine() {
            if ("SKIP".equals(label)) {
                return "Checking later…";
            }
            if (ok) {
                return String.format(Locale.US, "Online · %d ms", rttMs);
            }
            if ("DOWN".equals(label)) {
                return String.format(Locale.US, "Offline · %d fails", consecutiveFails);
            }
            return String.format(Locale.US, "Unstable · %d/%d fails",
                    consecutiveFails, FAIL_THRESHOLD);
        }

        /** Short badge for UI: Online / Unstable / Offline / — */
        public String statusBadge() {
            if ("SKIP".equals(label)) return "—";
            if (ok) return "Online";
            if ("DOWN".equals(label)) return "Offline";
            return "Unstable";
        }
    }

    /** Latest cached status without probing (for UI combine). */
    public Result lastStatus() {
        if (lastProbeMs == 0) {
            return new Result(true, 0, -1, "—", "SKIP", "not probed yet");
        }
        String label = lastOk ? "OK" : (consecutiveFails >= FAIL_THRESHOLD ? "DOWN" : "DEGRADED");
        String detail = lastOk
                ? String.format(Locale.US, "%dms via %s", lastRttMs, lastTarget)
                : consecutiveFails + " consecutive fail(s)";
        return new Result(lastOk, consecutiveFails, lastRttMs, lastTarget, label, detail);
    }

    /**
     * One light probe. Call from a background thread.
     *
     * @param skip if true (reset in flight / grace), do not touch the network
     */
    public Result probe(boolean skip) {
        if (skip) {
            return new Result(lastOk, consecutiveFails, lastRttMs, lastTarget, "SKIP", "grace/reset");
        }

        lastProbeMs = SystemClock.elapsedRealtime();

        for (String[] p : PROBES) {
            String host = p[0];
            int port = Integer.parseInt(p[1]);
            long start = SystemClock.elapsedRealtime();
            if (tcpConnect(host, port)) {
                lastRttMs = SystemClock.elapsedRealtime() - start;
                lastTarget = host + ":" + port;
                consecutiveFails = 0;
                lastOk = true;
                return new Result(true, 0, lastRttMs, lastTarget, "OK",
                        lastRttMs + "ms");
            }
        }

        consecutiveFails++;
        lastOk = false;
        String label = consecutiveFails >= FAIL_THRESHOLD ? "DOWN" : "DEGRADED";
        return new Result(false, consecutiveFails, -1, lastTarget, label,
                consecutiveFails + " consecutive fail(s)");
    }

    /** TCP handshake only — no HTTP, no bulk data. */
    private static boolean tcpConnect(String host, int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            // tiny buffers; we only need SYN/SYN-ACK
            socket.setTcpNoDelay(true);
            socket.setSoLinger(true, 0);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public void resetFailCount() {
        consecutiveFails = 0;
        lastOk = true;
    }
}
