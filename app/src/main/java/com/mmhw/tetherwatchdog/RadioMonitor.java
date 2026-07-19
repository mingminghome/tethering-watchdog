package com.mmhw.tetherwatchdog;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Detects mobile radio display type (4G / 4G+ / 5G) and optional LTE signal metrics.
 * Used to surface step-downs like 4G+ → 4G while USB tethering is active.
 *
 * <p>Public APIs only for targetSdk 30+:
 * {@code TelephonyDisplayInfo} override (LTE_CA → 4G+), multi-entry
 * {@link ServiceState#getCellBandwidths()}, and {@link TelephonyManager#getSignalStrength()}.
 * Do <em>not</em> reflect {@code isUsingCarrierAggregation()} — hidden/blocked and logspam.
 */
public class RadioMonitor {

    public static final String LABEL_UNKNOWN = "—";
    public static final String LABEL_NONE = "No data";
    public static final String LABEL_4G = "4G";
    public static final String LABEL_4G_PLUS = "4G+";
    public static final String LABEL_5G = "5G";
    public static final String LABEL_5G_PLUS = "5G+";
    public static final String LABEL_3G = "3G";
    public static final String LABEL_2G = "2G";

    /** Context tags for radio step-downs (UI + prefs). */
    public static final String CTX_AFTER_RESET = "after reset";
    public static final String CTX_TETHERED = "tethered";
    public static final String CTX_CABLE = "cable only";
    public static final String CTX_IDLE = "idle (no USB)";

    /** Prefer treating drops within this window as reset-related. */
    public static final long RESET_GRACE_MS = 90_000L;

    public static final String PREF_LAST_RESET_ELAPSED = "last_reset_elapsed";
    public static final String PREF_LAST_RADIO_EVENT = "last_radio_event";
    public static final String PREF_LAST_RADIO_LABEL = "last_radio_label";
    public static final String PREF_LAST_RADIO_CONTEXT = "last_radio_context";

    /** Higher = better / more aggregated radio mode. */
    public static int rank(String label) {
        if (label == null) return 0;
        switch (label) {
            case LABEL_5G_PLUS:
                return 6;
            case LABEL_5G:
                return 5;
            case LABEL_4G_PLUS:
                return 4;
            case LABEL_4G:
                return 3;
            case LABEL_3G:
                return 2;
            case LABEL_2G:
                return 1;
            default:
                return 0;
        }
    }

    public static boolean isStepDown(String from, String to) {
        int a = rank(from);
        int b = rank(to);
        return a > 0 && b > 0 && b < a;
    }

    /**
     * Classify what was going on when a radio change happened.
     *
     * @param usbConnected cable present (broadcast / monitor)
     * @param tetherActive USB tethering path active (rndis/ncm/iface)
     * @param lastResetElapsedRealtime {@link android.os.SystemClock#elapsedRealtime()} at last reset, or 0
     * @param nowElapsedRealtime current elapsedRealtime
     */
    public static String classifyDropContext(
            boolean usbConnected,
            boolean tetherActive,
            long lastResetElapsedRealtime,
            long nowElapsedRealtime) {
        if (lastResetElapsedRealtime > 0
                && nowElapsedRealtime >= lastResetElapsedRealtime
                && nowElapsedRealtime - lastResetElapsedRealtime <= RESET_GRACE_MS) {
            return CTX_AFTER_RESET;
        }
        if (tetherActive) return CTX_TETHERED;
        if (usbConnected) return CTX_CABLE;
        return CTX_IDLE;
    }

    /** Human-readable hint for the context tag. */
    public static String contextHint(String context) {
        if (context == null) return "";
        switch (context) {
            case CTX_AFTER_RESET:
                return "Likely from data/USB reset, not the cable alone";
            case CTX_TETHERED:
                return "During tether — heat/load/EMI possible; not proof USB caused it";
            case CTX_CABLE:
                return "Cable in, tether off — compare with unplugged baseline";
            case CTX_IDLE:
                return "No USB — tower/modem/thermal, not the USB port";
            default:
                return "";
        }
    }

    /** Build a full event line for UI / prefs / toast. */
    public static String formatDropEvent(
            String from,
            String to,
            String timeHms,
            String context,
            boolean stepDown) {
        String base = (from != null ? from : "?") + " → " + (to != null ? to : "?");
        if (timeHms != null && !timeHms.isEmpty()) {
            base = base + "  ·  " + timeHms;
        }
        if (context != null && !context.isEmpty()) {
            base = base + "  ·  while: " + context;
        }
        if (stepDown) {
            String hint = contextHint(context);
            if (!hint.isEmpty()) {
                base = base + "\n" + hint;
            }
        }
        return base;
    }

    /**
     * Merge base network label with display override + CA evidence.
     * Used by tests and {@link #fillNetworkLabel}.
     */
    public static String mergeLabel(String baseLabel, String overrideLabel, boolean carrierAgg) {
        String best = baseLabel != null ? baseLabel : LABEL_UNKNOWN;
        if (overrideLabel != null && rank(overrideLabel) > rank(best)) {
            best = overrideLabel;
        }
        // Modem CA often true while override still NONE — match status bar 4G+
        if (carrierAgg && rank(best) == rank(LABEL_4G)) {
            best = LABEL_4G_PLUS;
        }
        return best;
    }

    public static class Snapshot {
        /** User-facing: 4G, 4G+, 5G, … */
        public String label = LABEL_UNKNOWN;
        /** Raw network type name for debug. */
        public String networkTypeName = "unknown";
        /** Override type name when API 30+ (LTE_CA, NR_NSA, …). */
        public String overrideName;
        /** True when ServiceState / NRI reports carrier aggregation. */
        public boolean carrierAggregation;
        /** Count of valid cell bandwidths (2+ ≈ multi-carrier). */
        public int bandwidthCount;
        /** RSRP dBm when available (LTE/NR), else null. */
        public Integer rsrpDbm;
        /** RSRQ dB when available, else null. */
        public Integer rsrqDb;
        /** RSSNR ×0.1 dB style integer from API, or null. */
        public Integer rssnr;
        public boolean phoneStateGranted;
        public boolean locationGranted;
        public boolean available;
        /** True when label dropped vs previous sample (e.g. 4G+ → 4G). */
        public boolean stepDown;
        public String previousLabel;
        /** One-line event if a transition occurred this sample (before context). */
        public String transitionLine;
        /** Time token only (HH:mm:ss) when a transition fired. */
        public String transitionTime;
        /** Short secondary line for the card. */
        public String detailLine;
        /** Debug: how the label was chosen. */
        public String sourceHint;
    }

    private String lastLabel = null;
    private String lastTransitionLine = null;
    private String lastAnnotatedEvent = null;
    /** Debounce CA flap: require N matching samples before committing a new label. */
    private String pendingLabel = null;
    private int pendingCount = 0;
    private static final int STABLE_SAMPLES = 2;

    public String getLastTransitionLine() {
        return lastTransitionLine;
    }

    public String getLastAnnotatedEvent() {
        return lastAnnotatedEvent;
    }

    public void setLastAnnotatedEvent(String event) {
        lastAnnotatedEvent = event;
        if (event != null) lastTransitionLine = event;
    }

    public Snapshot sample(Context context) {
        Snapshot s = new Snapshot();
        if (context == null) {
            s.detailLine = "No context";
            return s;
        }

        s.phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        s.locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!s.phoneStateGranted) {
            s.label = LABEL_UNKNOWN;
            s.detailLine = "Allow Phone permission to watch 4G / 4G+";
            s.available = false;
            return s;
        }

        TelephonyManager tm = telephonyForData(context);
        if (tm == null) {
            s.detailLine = "Telephony unavailable";
            return s;
        }

        try {
            fillNetworkLabel(tm, s);
            // SignalStrength is available with READ_PHONE_STATE; location only helps CellInfo fallback.
            fillSignal(tm, s);
            s.available = true;

            // Stabilize against modem CA / override flapping every second
            String raw = s.label;
            if (raw != null && !LABEL_UNKNOWN.equals(raw) && !LABEL_NONE.equals(raw)) {
                if (lastLabel == null) {
                    lastLabel = raw;
                    pendingLabel = null;
                    pendingCount = 0;
                } else if (raw.equals(lastLabel)) {
                    pendingLabel = null;
                    pendingCount = 0;
                } else if (raw.equals(pendingLabel)) {
                    pendingCount++;
                    if (pendingCount >= STABLE_SAMPLES) {
                        s.previousLabel = lastLabel;
                        s.stepDown = isStepDown(lastLabel, raw);
                        String when = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                .format(new Date());
                        s.transitionTime = when;
                        // Context is attached by UI/service (needs USB + reset state).
                        s.transitionLine = lastLabel + " → " + raw + "  ·  " + when;
                        lastTransitionLine = s.transitionLine;
                        lastLabel = raw;
                        pendingLabel = null;
                        pendingCount = 0;
                    } else {
                        // Keep showing stable label until confirmed
                        s.label = lastLabel;
                    }
                } else {
                    pendingLabel = raw;
                    pendingCount = 1;
                    s.label = lastLabel;
                }
            }

            s.detailLine = buildDetail(s);
        } catch (SecurityException se) {
            s.phoneStateGranted = false;
            s.detailLine = "Phone permission denied";
        } catch (Exception e) {
            s.detailLine = "Radio read failed";
        }
        return s;
    }

    /** Prefer default data subscription so multi-SIM / subId quirks match the status bar. */
    @SuppressLint("MissingPermission")
    private static TelephonyManager telephonyForData(Context context) {
        TelephonyManager base = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (base == null) return null;
        try {
            int subId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TelephonyManager forSub = base.createForSubscriptionId(subId);
                if (forSub != null) return forSub;
            }
        } catch (Exception ignored) {
        }
        return base;
    }

    @SuppressLint("MissingPermission")
    private static void fillNetworkLabel(TelephonyManager tm, Snapshot s) {
        int dataType = safeDataNetworkType(tm);
        s.networkTypeName = networkTypeName(dataType);
        String base = labelFromNetworkType(dataType);
        String fromOverride = null;
        String source = "dataType";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Some SDK stubs omit getTelephonyDisplayInfo(); use reflection for portability.
            try {
                Object di = tm.getClass().getMethod("getTelephonyDisplayInfo").invoke(tm);
                if (di != null) {
                    int nt = (Integer) di.getClass().getMethod("getNetworkType").invoke(di);
                    if (nt != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                        s.networkTypeName = networkTypeName(nt);
                        String fromNt = labelFromNetworkType(nt);
                        if (rank(fromNt) > rank(base)) {
                            base = fromNt;
                            source = "display.network";
                        }
                    }
                    int override = (Integer) di.getClass()
                            .getMethod("getOverrideNetworkType").invoke(di);
                    s.overrideName = overrideName(override);
                    fromOverride = labelFromOverride(override);
                    if (fromOverride != null) {
                        source = "display." + s.overrideName;
                    }
                }
            } catch (Exception e) {
                s.overrideName = "err";
            }
        }

        // Public proxy for CA: multiple component carriers in ServiceState bandwidths.
        // Do NOT call isUsingCarrierAggregation() — hidden API, blocked, logspam every poll.
        int bwCount = 0;
        try {
            ServiceState ss = tm.getServiceState();
            if (ss != null) {
                bwCount = countValidBandwidths(ss);
            }
        } catch (Exception ignored) {
        }
        boolean ca = bwCount >= 2;
        s.carrierAggregation = ca;
        s.bandwidthCount = bwCount;

        s.label = mergeLabel(base, fromOverride, ca);
        if (ca && LABEL_4G_PLUS.equals(s.label) && fromOverride == null) {
            source = "service.bw×" + bwCount;
            if (s.overrideName == null || "NONE".equals(s.overrideName)) {
                s.overrideName = "CA(bw)";
            }
        }
        s.sourceHint = source;

        if (s.label == null || s.label.isEmpty()) {
            s.label = LABEL_UNKNOWN;
        }
    }

    /**
     * Count valid component-carrier bandwidths (public {@link ServiceState#getCellBandwidths()}).
     * 2+ entries is a good public proxy for LTE CA when display override is NONE.
     */
    static int countValidBandwidths(ServiceState ss) {
        if (ss == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0;
        try {
            int[] bws = ss.getCellBandwidths();
            if (bws == null) return 0;
            int n = 0;
            for (int b : bws) {
                // Skip empty / INT_MAX placeholders seen on some radios
                if (b > 0 && b < 1_000_000) n++;
            }
            return n;
        } catch (Throwable e) {
            // Some OEM stubs; try once via reflection only for this public method name
            try {
                int[] bws = (int[]) ss.getClass().getMethod("getCellBandwidths").invoke(ss);
                if (bws == null) return 0;
                int n = 0;
                for (int b : bws) {
                    if (b > 0 && b < 1_000_000) n++;
                }
                return n;
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private static int safeDataNetworkType(TelephonyManager tm) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return tm.getDataNetworkType();
            }
            //noinspection deprecation
            return tm.getNetworkType();
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
    }

    @SuppressLint("MissingPermission")
    private static void fillSignal(TelephonyManager tm, Snapshot s) {
        // Prefer TelephonyManager.getSignalStrength() — works without location on many devices
        // (matches dumpsys telephony.registry mSignalStrength).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                SignalStrength sig = tm.getSignalStrength();
                if (sig != null && applyFromSignalStrength(sig, s)) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // Fallback: CellInfo (needs location; often empty without location mode on).
        try {
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells == null || cells.isEmpty()) return;

            Integer bestRsrp = null;
            Integer bestRsrq = null;
            Integer bestRssnr = null;

            for (CellInfo info : cells) {
                if (!info.isRegistered()) continue;
                if (info instanceof CellInfoLte) {
                    CellSignalStrengthLte ss = ((CellInfoLte) info).getCellSignalStrength();
                    int rsrp = ss.getRsrp();
                    int rsrq = ss.getRsrq();
                    int rssnr = ss.getRssnr();
                    if (isPowerMetricUnavailable(rsrp)) continue;
                    if (bestRsrp == null || rsrp > bestRsrp) {
                        bestRsrp = rsrp;
                        bestRsrq = isPowerMetricUnavailable(rsrq) ? null : rsrq;
                        bestRssnr = isPlaceholderMetric(rssnr) ? null : rssnr;
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info instanceof CellInfoNr) {
                    CellSignalStrengthNr ss = (CellSignalStrengthNr)
                            ((CellInfoNr) info).getCellSignalStrength();
                    int rsrp = ss.getSsRsrp();
                    int rsrq = ss.getSsRsrq();
                    int sinr = ss.getSsSinr();
                    if (isPowerMetricUnavailable(rsrp)) continue;
                    if (bestRsrp == null || rsrp > bestRsrp) {
                        bestRsrp = rsrp;
                        bestRsrq = isPowerMetricUnavailable(rsrq) ? null : rsrq;
                        bestRssnr = isPlaceholderMetric(sinr) ? null : sinr;
                    }
                }
            }
            s.rsrpDbm = bestRsrp;
            s.rsrqDb = bestRsrq;
            s.rssnr = bestRssnr;
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    /** @return true if any usable LTE/NR metric was written */
    private static boolean applyFromSignalStrength(SignalStrength sig, Snapshot s) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                List<CellSignalStrength> list = sig.getCellSignalStrengths();
                if (list != null && !list.isEmpty()) {
                    Integer bestRsrp = null;
                    Integer bestRsrq = null;
                    Integer bestRssnr = null;
                    for (CellSignalStrength c : list) {
                        if (c instanceof CellSignalStrengthLte) {
                            CellSignalStrengthLte lte = (CellSignalStrengthLte) c;
                            int rsrp = lte.getRsrp();
                            int rsrq = lte.getRsrq();
                            int rssnr = lte.getRssnr();
                            if (isPowerMetricUnavailable(rsrp)) continue;
                            if (bestRsrp == null || rsrp > bestRsrp) {
                                bestRsrp = rsrp;
                                bestRsrq = isPowerMetricUnavailable(rsrq) ? null : rsrq;
                                bestRssnr = isPlaceholderMetric(rssnr) ? null : rssnr;
                            }
                        } else if (c instanceof CellSignalStrengthNr) {
                            CellSignalStrengthNr nr = (CellSignalStrengthNr) c;
                            int rsrp = nr.getSsRsrp();
                            int rsrq = nr.getSsRsrq();
                            int sinr = nr.getSsSinr();
                            if (isPowerMetricUnavailable(rsrp)) continue;
                            if (bestRsrp == null || rsrp > bestRsrp) {
                                bestRsrp = rsrp;
                                bestRsrq = isPowerMetricUnavailable(rsrq) ? null : rsrq;
                                bestRssnr = isPlaceholderMetric(sinr) ? null : sinr;
                            }
                        }
                    }
                    if (bestRsrp != null) {
                        s.rsrpDbm = bestRsrp;
                        s.rsrqDb = bestRsrq;
                        s.rssnr = bestRssnr;
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String buildDetail(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        if (s.rsrpDbm != null) {
            sb.append("RSRP ").append(s.rsrpDbm).append(" dBm");
            String grade = rsrpGrade(s.rsrpDbm);
            if (grade != null) sb.append(" · ").append(grade);
            if (s.rsrqDb != null) {
                sb.append(" · RSRQ ").append(s.rsrqDb);
            }
        } else {
            sb.append("RSRP n/a");
        }
        if (s.overrideName != null && !s.overrideName.isEmpty()
                && !"UNKNOWN".equals(s.overrideName)) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s.overrideName);
        } else if (s.networkTypeName != null) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s.networkTypeName);
        }
        if (s.carrierAggregation && (s.overrideName == null || "NONE".equals(s.overrideName))) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append("CA");
        }
        if (s.bandwidthCount >= 2) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(s.bandwidthCount).append(" CC");
        }
        return sb.toString();
    }

    /** True for missing / nonsense RSRP or RSRQ (normally negative dB-ish values). */
    private static boolean isPowerMetricUnavailable(int v) {
        if (isPlaceholderMetric(v)) return true;
        return v > 0 || v < -140;
    }

    private static boolean isPlaceholderMetric(int v) {
        if (v == Integer.MAX_VALUE || v == Integer.MIN_VALUE) return true;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && v == CellInfo.UNAVAILABLE;
    }

    /** Rough RSRP quality for UI (not carrier-accurate). */
    public static String rsrpGrade(int rsrpDbm) {
        if (rsrpDbm >= -80) return "excellent";
        if (rsrpDbm >= -90) return "good";
        if (rsrpDbm >= -100) return "fair";
        if (rsrpDbm >= -110) return "weak";
        return "poor";
    }

    public static String labelFromNetworkType(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return LABEL_5G;
            case TelephonyManager.NETWORK_TYPE_LTE:
                return LABEL_4G;
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return LABEL_3G;
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
            case TelephonyManager.NETWORK_TYPE_GSM:
                return LABEL_2G;
            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                return LABEL_NONE;
            default:
                return LABEL_UNKNOWN;
        }
    }

    /**
     * Maps TelephonyDisplayInfo override constants (numeric — avoids missing stubs).
     * NONE=0, LTE_CA=1, LTE_ADVANCED_PRO=2, NR_NSA=3, NR_NSA_MMWAVE=4, NR_ADVANCED=5.
     */
    public static String labelFromOverride(int override) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        switch (override) {
            case 0: // NONE
                return null;
            case 1: // LTE_CA
            case 2: // LTE_ADVANCED_PRO
                return LABEL_4G_PLUS;
            case 3: // NR_NSA
            case 4: // NR_NSA_MMWAVE
                return LABEL_5G;
            case 5: // NR_ADVANCED
                return LABEL_5G_PLUS;
            default:
                return null;
        }
    }

    public static String networkTypeName(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return "NR";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "HSPA+";
            case TelephonyManager.NETWORK_TYPE_HSPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
                return "UNKNOWN";
            default:
                return "TYPE_" + type;
        }
    }

    public static String overrideName(int override) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "N/A";
        switch (override) {
            case 0:
                return "NONE";
            case 1:
                return "LTE_CA";
            case 2:
                return "LTE_ADV_PRO";
            case 3:
                return "NR_NSA";
            case 4:
                return "NR_NSA_MMWAVE";
            case 5:
                return "NR_ADVANCED";
            default:
                return "OVR_" + override;
        }
    }

    public static int colorForLabel(String label) {
        if (label == null) return 0xFF9E9E9E;
        switch (label) {
            case LABEL_5G_PLUS:
            case LABEL_5G:
                return 0xFF6A1B9A;
            case LABEL_4G_PLUS:
                return 0xFF1565C0;
            case LABEL_4G:
                return 0xFF2E7D32;
            case LABEL_3G:
                return 0xFFEF6C00;
            case LABEL_2G:
                return 0xFFC62828;
            case LABEL_NONE:
                return 0xFF757575;
            default:
                return 0xFF9E9E9E;
        }
    }
}
