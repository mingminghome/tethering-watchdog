package com.mmhw.tetherwatchdog;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * UI + foreground USB status polling.
 * Optional debug metrics (live rates + light ping) while the app is open.
 * Background auto-recover is {@link WatchdogService}.
 */
public class MainActivity extends AppCompatActivity {

    private static final String KEY_DEBUG_METRICS = "debug_metrics_enabled";
    private static final long USB_STATUS_INTERVAL_MS = 2_000L;
    private static final long DEBUG_PING_INTERVAL_MS = 30_000L;
    private static final int REQ_RADIO_PERMS = 4201;

    private MaterialButton manualBtn;
    private TextView statusText;
    private TextView usbStatusBadge;
    private TextView usbSpeedTier;
    private TextView usbDetailText;
    private TextView radioStatusBadge;
    private TextView radioDetailText;
    private TextView radioEventText;
    private TextView mobileRateText;
    private TextView tetherRateText;
    private TextView compareHintText;
    private TextView inetHealthText;
    private TextView debugMetricsOffHint;
    private LinearLayout debugMetricsBody;
    private MaterialSwitch watchdogSwitch;
    private MaterialSwitch debugMetricsSwitch;

    private boolean rootOk;
    private boolean suppressSwitchCallback;
    private boolean debugMetricsOn;
    private boolean activityResumed;

    private boolean usbConnected;
    private boolean usbConfigured;
    private boolean rndisFunction;

    private final UsbLinkMonitor linkMonitor = new UsbLinkMonitor();
    private final RadioMonitor radioMonitor = new RadioMonitor();
    private final InternetHealthChecker debugNetHealth = new InternetHealthChecker();
    private HandlerThread workerThread;
    private Handler worker;
    private String lastNotifiedRadioTransition;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            // Auto-recover service only drives the header + detail hints during resets.
            boolean active = intent.getBooleanExtra(WatchdogService.EXTRA_ACTIVE, false);
            if (rootOk) {
                statusText.setText(active ? "Auto-recover on" : "System ready");
                statusText.setTextColor(active ? 0xFF1565C0 : 0xFF2E7D32);
            }
            String detail = intent.getStringExtra(WatchdogService.EXTRA_DETAIL);
            if (detail != null && (detail.startsWith("Auto-reset")
                    || detail.startsWith("Internet")
                    || detail.startsWith("Radio:"))) {
                usbDetailText.setText(detail);
            }
            String radioEvent = intent.getStringExtra(WatchdogService.EXTRA_RADIO_EVENT);
            if (radioEvent != null && !radioEvent.isEmpty() && radioEventText != null) {
                radioEventText.setText(radioEvent);
                String ctx = intent.getStringExtra(WatchdogService.EXTRA_RADIO_CONTEXT);
                if (ctx == null) {
                    if (radioEvent.contains(RadioMonitor.CTX_AFTER_RESET)) {
                        ctx = RadioMonitor.CTX_AFTER_RESET;
                    } else if (radioEvent.contains(RadioMonitor.CTX_TETHERED)) {
                        ctx = RadioMonitor.CTX_TETHERED;
                    } else if (radioEvent.contains(RadioMonitor.CTX_IDLE)) {
                        ctx = RadioMonitor.CTX_IDLE;
                    }
                }
                radioEventText.setTextColor(
                        radioEvent.contains("→") ? colorForRadioContext(ctx) : 0xFF5F6B76);
            }
            String radioLabel = intent.getStringExtra(WatchdogService.EXTRA_RADIO_LABEL);
            if (radioLabel != null && !radioLabel.isEmpty() && radioStatusBadge != null) {
                setRadioBadge(radioLabel, RadioMonitor.colorForLabel(radioLabel));
            }
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            applyUsbExtras(intent);
            // Immediate refresh on plug/unplug — don't wait for the poll timer.
            if (worker != null && activityResumed) {
                worker.post(usbStatusOnce);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Classic layout: system keeps content below the status bar (avoids a tall empty
        // white band from edge-to-edge + manual inset padding on emulators / small phones).
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_main);

        manualBtn = findViewById(R.id.manualBtn);
        statusText = findViewById(R.id.statusText);
        usbStatusBadge = findViewById(R.id.usbStatusBadge);
        usbSpeedTier = findViewById(R.id.usbSpeedTier);
        usbDetailText = findViewById(R.id.usbDetailText);
        radioStatusBadge = findViewById(R.id.radioStatusBadge);
        radioDetailText = findViewById(R.id.radioDetailText);
        radioEventText = findViewById(R.id.radioEventText);
        mobileRateText = findViewById(R.id.mobileRateText);
        tetherRateText = findViewById(R.id.tetherRateText);
        compareHintText = findViewById(R.id.compareHintText);
        inetHealthText = findViewById(R.id.inetHealthText);
        debugMetricsOffHint = findViewById(R.id.debugMetricsOffHint);
        debugMetricsBody = findViewById(R.id.debugMetricsBody);
        watchdogSwitch = findViewById(R.id.watchdogSwitch);
        debugMetricsSwitch = findViewById(R.id.debugMetricsSwitch);

        checkRootAccess();
        restoreSwitches();
        ensureRadioPermissions();
        // Re-layout header if the switch would collide on tiny widths
        View header = findViewById(R.id.headerRow);
        if (header != null) {
            header.post(this::maybeStackHeader);
        }

        watchdogSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            if (suppressSwitchCallback) return;
            if (!rootOk) {
                suppressSwitchCallback = true;
                watchdogSwitch.setChecked(false);
                suppressSwitchCallback = false;
                Toast.makeText(this, "Root required", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent serviceIntent = new Intent(this, WatchdogService.class);
            if (isChecked) {
                askForBatteryExemption();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                statusText.setText("Auto-recover on");
                statusText.setTextColor(0xFF1565C0);
            } else {
                stopService(serviceIntent);
                statusText.setText("System ready");
                statusText.setTextColor(0xFF2E7D32);
            }
        });

        debugMetricsSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            if (suppressSwitchCallback) return;
            // Rates use TrafficStats + root when available; ping needs only network.
            debugMetricsOn = isChecked;
            getSharedPreferences(WatchdogService.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_DEBUG_METRICS, isChecked).apply();
            updateDebugUiVisibility();
            if (isChecked) {
                startPingLoop();
            } else {
                stopPingLoop();
                resetDebugReadouts();
            }
        });

        manualBtn.setOnClickListener(v -> {
            manualBtn.setEnabled(false);
            manualBtn.setText("RESETTING...");
            statusText.setText("Resetting network…");
            statusText.setTextColor(0xFFEF6C00);
            markResetNow();

            RootUtil.performResetSequence(() -> runOnUiThread(() -> {
                manualBtn.setEnabled(rootOk);
                manualBtn.setText("RESET");
                markResetNow();
                if (rootOk) {
                    boolean on = watchdogSwitch.isChecked();
                    statusText.setText(on ? "Auto-recover on" : "System ready");
                    statusText.setTextColor(on ? 0xFF1565C0 : 0xFF2E7D32);
                }
                Toast.makeText(this, "Network reset complete", Toast.LENGTH_SHORT).show();
                // Refresh USB after reset
                if (worker != null) worker.post(usbStatusOnce);
            }));
        });
    }

    /** Record reset time so radio drops soon after are tagged "after reset". */
    private void markResetNow() {
        getSharedPreferences(WatchdogService.PREFS, MODE_PRIVATE).edit()
                .putLong(RadioMonitor.PREF_LAST_RESET_ELAPSED, SystemClock.elapsedRealtime())
                .apply();
    }

    private long lastResetElapsed() {
        return getSharedPreferences(WatchdogService.PREFS, MODE_PRIVATE)
                .getLong(RadioMonitor.PREF_LAST_RESET_ELAPSED, 0L);
    }

    /**
     * On very narrow screens, put Auto-recover under the title so it isn't crushed
     * against the edge.
     */
    private void maybeStackHeader() {
        LinearLayout header = findViewById(R.id.headerRow);
        if (header == null || watchdogSwitch == null) return;
        int widthDp = (int) (header.getWidth()
                / getResources().getDisplayMetrics().density);
        if (widthDp > 0 && widthDp < 340) {
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) watchdogSwitch.getLayoutParams();
            if (lp != null) {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
                lp.topMargin = Math.round(8 * getResources().getDisplayMetrics().density);
                lp.setMarginStart(0);
                watchdogSwitch.setLayoutParams(lp);
            }
        }
    }

    private void updateDebugUiVisibility() {
        if (debugMetricsOn) {
            debugMetricsBody.setVisibility(View.VISIBLE);
            debugMetricsOffHint.setVisibility(View.GONE);
        } else {
            debugMetricsBody.setVisibility(View.GONE);
            debugMetricsOffHint.setVisibility(View.VISIBLE);
        }
    }

    private void resetDebugReadouts() {
        mobileRateText.setText("↓ 0 KB/s\n↑ 0 KB/s");
        tetherRateText.setText("↓ 0 KB/s\n↑ 0 KB/s");
        compareHintText.setText("Debug off");
        inetHealthText.setText("—");
        inetHealthText.setTextColor(0xFF5F6B76);
    }

    private void ensureWorker() {
        if (workerThread == null) {
            workerThread = new HandlerThread("tether-ui");
            workerThread.start();
            worker = new Handler(workerThread.getLooper());
        }
    }

    /** USB badge/status always while the app is open (not background). */
    private void startUsbStatusPolling() {
        ensureWorker();
        worker.removeCallbacks(usbStatusLoop);
        worker.removeCallbacks(usbStatusOnce);
        worker.post(usbStatusOnce);
        worker.post(usbStatusLoop);
    }

    private void stopUsbStatusPolling() {
        if (worker != null) {
            worker.removeCallbacks(usbStatusLoop);
            worker.removeCallbacks(usbStatusOnce);
            worker.removeCallbacks(debugPingLoop);
        }
    }

    private void startPingLoop() {
        if (!debugMetricsOn || !activityResumed) return;
        ensureWorker();
        worker.removeCallbacks(debugPingLoop);
        worker.post(debugPingLoop);
    }

    private void stopPingLoop() {
        if (worker != null) worker.removeCallbacks(debugPingLoop);
    }

    private final Runnable usbStatusOnce = () -> {
        if (!activityResumed) return;
        final UsbLinkMonitor.Snapshot snap =
                linkMonitor.sample(usbConnected, usbConfigured, rndisFunction);
        final RadioMonitor.Snapshot radio = radioMonitor.sample(MainActivity.this);
        // Keep local flags in sync with reconciled sample
        usbConnected = snap.usbConnected;
        rndisFunction = snap.rndisFunction;
        runOnUiThread(() -> {
            applyUsbSnapshot(snap, debugMetricsOn);
            applyRadioSnapshot(radio, snap);
        });
    };

    private final Runnable usbStatusLoop = new Runnable() {
        @Override
        public void run() {
            if (!activityResumed) return;
            final UsbLinkMonitor.Snapshot snap =
                    linkMonitor.sample(usbConnected, usbConfigured, rndisFunction);
            final RadioMonitor.Snapshot radio = radioMonitor.sample(MainActivity.this);
            usbConnected = snap.usbConnected;
            rndisFunction = snap.rndisFunction;
            runOnUiThread(() -> {
                applyUsbSnapshot(snap, debugMetricsOn);
                applyRadioSnapshot(radio, snap);
            });
            if (worker != null && activityResumed) {
                worker.postDelayed(this, USB_STATUS_INTERVAL_MS);
            }
        }
    };

    private final Runnable debugPingLoop = new Runnable() {
        @Override
        public void run() {
            if (!debugMetricsOn || !activityResumed) return;
            InternetHealthChecker.Result r = debugNetHealth.probe(false);
            runOnUiThread(() -> {
                if (!debugMetricsOn) return;
                String line = r.summaryLine();
                inetHealthText.setText(line);
                int c = 0xFF5F6B76;
                if (line.startsWith("Online")) c = 0xFF2E7D32;
                else if (line.startsWith("Unstable") || line.contains("later")) c = 0xFFEF6C00;
                else if (line.startsWith("Offline")) c = 0xFFC62828;
                inetHealthText.setTextColor(c);
            });
            if (worker != null && debugMetricsOn && activityResumed) {
                worker.postDelayed(this, DEBUG_PING_INTERVAL_MS);
            }
        }
    };

    private void applyUsbSnapshot(UsbLinkMonitor.Snapshot snap, boolean withRates) {
        if (snap.statusLabel != null) {
            setUsbBadge(snap.statusLabel, colorForQuality(snap.quality));
        }
        if (snap.speedTier != null) usbSpeedTier.setText(snap.speedTier);
        if (snap.detail != null && !snap.detail.isEmpty()) {
            usbDetailText.setText(snap.detail);
        }
        if (withRates) {
            tetherRateText.setText("↓ " + UsbLinkMonitor.formatRate(snap.usbTxKBs)
                    + "\n↑ " + UsbLinkMonitor.formatRate(snap.usbRxKBs));
            mobileRateText.setText("↓ " + UsbLinkMonitor.formatRate(snap.mobileRxKBs)
                    + "\n↑ " + UsbLinkMonitor.formatRate(snap.mobileTxKBs));
            String hint = snap.compareHint != null ? snap.compareHint : "Sampling…";
            if (snap.mobileSource != null && !snap.mobileSource.isEmpty()
                    && !"—".equals(snap.mobileSource)) {
                hint = hint + "\n4G src: " + snap.mobileSource
                        + (snap.mobileIface != null ? " (" + snap.mobileIface + ")" : "");
            }
            compareHintText.setText(hint);
        }
    }

    private void applyRadioSnapshot(RadioMonitor.Snapshot radio, UsbLinkMonitor.Snapshot usb) {
        if (radio == null) return;
        String label = radio.label != null ? radio.label : RadioMonitor.LABEL_UNKNOWN;
        setRadioBadge(label, RadioMonitor.colorForLabel(label));
        if (radio.detailLine != null && !radio.detailLine.isEmpty()) {
            radioDetailText.setText(radio.detailLine);
        }

        boolean tetherActive = usb != null
                && usb.usbConnected
                && (usb.rndisFunction || usb.ifaceName != null);
        boolean cableIn = usb != null && usb.usbConnected;
        String liveCtx = RadioMonitor.classifyDropContext(
                cableIn, tetherActive, lastResetElapsed(), SystemClock.elapsedRealtime());

        if (radio.transitionLine != null && radio.stepDown) {
            String ctx = liveCtx;
            String line = RadioMonitor.formatDropEvent(
                    radio.previousLabel,
                    radio.label,
                    radio.transitionTime,
                    ctx,
                    true);
            radioMonitor.setLastAnnotatedEvent(line);
            radioEventText.setText(line);
            radioEventText.setTextColor(colorForRadioContext(ctx));

            if (!radio.transitionLine.equals(lastNotifiedRadioTransition)) {
                lastNotifiedRadioTransition = radio.transitionLine;
                Toast.makeText(this,
                        "Radio: " + radio.previousLabel + " → " + radio.label
                                + " · " + ctx,
                        Toast.LENGTH_LONG).show();
                getSharedPreferences(WatchdogService.PREFS, MODE_PRIVATE).edit()
                        .putString(RadioMonitor.PREF_LAST_RADIO_EVENT, line)
                        .putString(RadioMonitor.PREF_LAST_RADIO_LABEL, radio.label)
                        .putString(RadioMonitor.PREF_LAST_RADIO_CONTEXT, ctx)
                        .apply();
            }
        } else if (radio.transitionLine != null) {
            // Step-up or lateral change — still show, tag context lightly
            String line = RadioMonitor.formatDropEvent(
                    radio.previousLabel,
                    radio.label,
                    radio.transitionTime,
                    liveCtx,
                    false);
            radioMonitor.setLastAnnotatedEvent(line);
            radioEventText.setText(line);
            radioEventText.setTextColor(0xFF5F6B76);
        } else {
            String saved = radioMonitor.getLastAnnotatedEvent();
            if (saved == null || saved.isEmpty()) {
                saved = getSharedPreferences(WatchdogService.PREFS, MODE_PRIVATE)
                        .getString(RadioMonitor.PREF_LAST_RADIO_EVENT, null);
            }
            if (saved != null && !saved.isEmpty()) {
                radioEventText.setText(saved);
                radioEventText.setTextColor(0xFF5F6B76);
            } else if (!radio.phoneStateGranted) {
                radioEventText.setText("Grant Phone permission to detect 4G+ → 4G drops.");
                radioEventText.setTextColor(0xFFEF6C00);
            } else {
                radioEventText.setText(watchHint(liveCtx));
                radioEventText.setTextColor(0xFF8A9199);
            }
        }
    }

    private static String watchHint(String liveCtx) {
        if (RadioMonitor.CTX_IDLE.equals(liveCtx)) {
            return "Baseline (unplugged) · drops here = tower/modem, not USB";
        }
        if (RadioMonitor.CTX_TETHERED.equals(liveCtx)) {
            return "Tether on · watching drops (tag: tethered / after reset)";
        }
        if (RadioMonitor.CTX_CABLE.equals(liveCtx)) {
            return "Cable in, tether off · compare with unplugged baseline";
        }
        if (RadioMonitor.CTX_AFTER_RESET.equals(liveCtx)) {
            return "Just reset · radio often falls to plain 4G while reattaching";
        }
        return "Watching for 4G+ → 4G · tags: tethered / idle / after reset";
    }

    private static int colorForRadioContext(String ctx) {
        if (RadioMonitor.CTX_AFTER_RESET.equals(ctx)) return 0xFFEF6C00;
        if (RadioMonitor.CTX_TETHERED.equals(ctx)) return 0xFFC62828;
        if (RadioMonitor.CTX_IDLE.equals(ctx)) return 0xFF1565C0;
        return 0xFF5F6B76;
    }

    private void setRadioBadge(String label, int color) {
        radioStatusBadge.setText(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(color);
        radioStatusBadge.setBackground(bg);
        radioStatusBadge.setTextColor(0xFFFFFFFF);
    }

    private void ensureRadioPermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_PHONE_STATE);
        }
        // Location unlocks RSRP/RSRQ; optional but useful for “signal broke” correlation.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_RADIO_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RADIO_PERMS) return;
        if (activityResumed && worker != null) {
            worker.post(usbStatusOnce);
        }
    }

    private void applyUsbExtras(Intent intent) {
        // Parse all known OEM variants of USB_STATE
        usbConnected = intent.getBooleanExtra("connected", false)
                || truthy(intent, "connected");
        usbConfigured = intent.getBooleanExtra("configured", false)
                || truthy(intent, "configured");
        rndisFunction = intent.getBooleanExtra("rndis", false)
                || intent.getBooleanExtra("ncm", false)
                || intent.getBooleanExtra("usb_function_rndis", false)
                || intent.getBooleanExtra("usb_function_ncm", false)
                || truthy(intent, "rndis")
                || truthy(intent, "ncm");
        linkMonitor.onUsbState(usbConnected, usbConfigured, rndisFunction);
    }

    private static boolean truthy(Intent intent, String key) {
        try {
            if (!intent.hasExtra(key)) return false;
            Object v = intent.getExtras() != null ? intent.getExtras().get(key) : null;
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) {
                String s = ((String) v).trim().toLowerCase();
                return "true".equals(s) || "1".equals(s) || "yes".equals(s);
            }
            if (v instanceof Number) return ((Number) v).intValue() != 0;
        } catch (Exception ignored) {}
        return false;
    }

    private void setUsbBadge(String label, int color) {
        usbStatusBadge.setText(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(color);
        usbStatusBadge.setBackground(bg);
        usbStatusBadge.setTextColor(0xFFFFFFFF);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int colorForQuality(String quality) {
        if (quality == null) return 0xFF9E9E9E;
        switch (quality) {
            case "HEALTHY":
            case "GOOD":
                return 0xFF2E7D32;
            case "LIMITED":
            case "UNSTABLE":
            case "POOR":
                return 0xFFC62828;
            case "STARTING":
            case "TETHER_OFF":
            case "CONNECTED":
            case "FAIR":
                return 0xFFEF6C00;
            case "UNPLUGGED":
            case "DISCONNECTED":
                return 0xFF757575;
            default:
                return 0xFF9E9E9E;
        }
    }

    private void restoreSwitches() {
        SharedPreferences prefs = getSharedPreferences(WatchdogService.PREFS, MODE_PRIVATE);
        boolean autoRecover = prefs.getBoolean(WatchdogService.KEY_WATCHDOG, false);
        debugMetricsOn = prefs.getBoolean(KEY_DEBUG_METRICS, false);

        suppressSwitchCallback = true;
        watchdogSwitch.setChecked(autoRecover);
        debugMetricsSwitch.setChecked(debugMetricsOn);
        suppressSwitchCallback = false;
        updateDebugUiVisibility();
        if (!debugMetricsOn) resetDebugReadouts();

        // Placeholder until first sample
        setUsbBadge("…", 0xFF9E9E9E);
        usbSpeedTier.setText("USB · checking…");
        usbDetailText.setText("Reading USB state…");
        setRadioBadge("…", 0xFF9E9E9E);
        radioDetailText.setText("Checking radio…");
        String savedEvent = prefs.getString(RadioMonitor.PREF_LAST_RADIO_EVENT, null);
        if (savedEvent != null && !savedEvent.isEmpty()) {
            radioEventText.setText(savedEvent.startsWith("4G") || savedEvent.contains("→")
                    ? savedEvent
                    : "Last: " + savedEvent);
            String ctx = prefs.getString(RadioMonitor.PREF_LAST_RADIO_CONTEXT, null);
            radioEventText.setTextColor(colorForRadioContext(ctx));
        }
    }

    private void checkRootAccess() {
        new Thread(() -> {
            RootUtil.invalidateRootCache();
            boolean hasRoot = RootUtil.probeRoot();
            runOnUiThread(() -> {
                rootOk = hasRoot;
                // Radio + debug metrics work without root; auto-recover/reset need it.
                debugMetricsSwitch.setEnabled(true);
                if (!hasRoot) {
                    statusText.setText("No root · grant Magisk for full USB stats");
                    statusText.setTextColor(0xFFEF6C00);
                    manualBtn.setEnabled(false);
                    watchdogSwitch.setEnabled(false);
                } else {
                    manualBtn.setEnabled(true);
                    watchdogSwitch.setEnabled(true);
                    statusText.setText(watchdogSwitch.isChecked() ? "Auto-recover on" : "System ready");
                    statusText.setTextColor(watchdogSwitch.isChecked() ? 0xFF1565C0 : 0xFF2E7D32);
                }
                if (activityResumed && worker != null) {
                    worker.post(usbStatusOnce);
                }
            });
        }, "root-probe").start();
    }

    private void askForBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    /**
     * USB_STATE is a system broadcast — must use RECEIVER_EXPORTED on API 33+,
     * otherwise sticky/current state and plug events never arrive.
     */
    private static int systemReceiverFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Context.RECEIVER_EXPORTED;
        }
        return 0;
    }

    private static int appReceiverFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Context.RECEIVER_NOT_EXPORTED;
        }
        return 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        // Release reinstall often needs a fresh Magisk grant — re-probe on each open.
        if (!rootOk) {
            checkRootAccess();
        }

        IntentFilter statusFilter = new IntentFilter(WatchdogService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, statusFilter, appReceiverFlags());
        } else {
            registerReceiver(statusReceiver, statusFilter);
        }

        IntentFilter usbFilter = new IntentFilter("android.hardware.usb.action.USB_STATE");
        Intent sticky;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sticky = registerReceiver(usbReceiver, usbFilter, systemReceiverFlags());
        } else {
            sticky = registerReceiver(usbReceiver, usbFilter);
        }
        if (sticky != null) {
            applyUsbExtras(sticky);
        }

        startUsbStatusPolling();
        if (debugMetricsOn) startPingLoop();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        stopUsbStatusPolling();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopUsbStatusPolling();
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            worker = null;
        }
        super.onDestroy();
    }
}
