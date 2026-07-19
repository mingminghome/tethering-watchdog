package com.mmhw.tetherwatchdog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;

/**
 * Background auto-recover only: USB link quality + heal on disconnect / dead iface.
 * Live rates and light ping run in the UI as optional debug (not here).
 */
public class WatchdogService extends Service {

    public static final String PREFS = "TetherPrefs";
    public static final String KEY_WATCHDOG = "watchdog_enabled";
    public static final String ACTION_STATUS = "com.mmhw.tetherwatchdog.STATUS";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_STATUS_LABEL = "status_label";
    public static final String EXTRA_SPEED_TIER = "speed_tier";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_RADIO_LABEL = "radio_label";
    public static final String EXTRA_RADIO_EVENT = "radio_event";
    public static final String EXTRA_RADIO_CONTEXT = "radio_context";

    private static final String CHANNEL_ID = "TetherWatchdogChannel";
    private static final long RESET_COOLDOWN_MS = 45_000L;
    /** Keep ≥ root throttle so we do not pile su sessions while UI also polls. */
    private static final long SAMPLE_INTERVAL_MS = 4_000L;
    private static final long NOTIFY_MIN_INTERVAL_MS = 5_000L;

    private final UsbLinkMonitor linkMonitor = new UsbLinkMonitor();
    private final RadioMonitor radioMonitor = new RadioMonitor();
    private HandlerThread workerThread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private boolean running;

    private boolean usbConnected;
    private boolean usbConfigured;
    private boolean rndisFunction;
    private boolean usbStateInitialized;
    private long lastResetMs;
    private boolean resetInFlight;
    private long lastResetElapsedRealtime;

    private String lastStatusLine = "Starting…";
    private String lastQuality = "—";
    private String lastStatusLabel = "—";
    private String lastSpeedTier = "USB · —";
    private String lastDetail = "";
    private String lastRadioLabel = "—";
    private String lastRadioEvent = "";
    private String lastRadioContext = "";
    private String lastNotifyText = "";
    private long lastNotifyMs;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startForeground(1, buildNotification("Auto-recover on"));

            workerThread = new HandlerThread("tether-watchdog",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            workerThread.start();
            worker = new Handler(workerThread.getLooper());

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TetherWatchdog::Lock");
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) wakeLock.acquire();

            // USB_STATE is a system broadcast — RECEIVER_NOT_EXPORTED blocks it on API 33+.
            IntentFilter usbFilter = new IntentFilter("android.hardware.usb.action.USB_STATE");
            Intent sticky;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sticky = registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED);
            } else {
                sticky = registerReceiver(usbReceiver, usbFilter);
            }
            applyUsbState(sticky);

            RootUtil.forceMobileDataPriority();

            worker.post(sampleLoop);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WATCHDOG, true).apply();
        }
        return START_STICKY;
    }

    /** USB quality + radio type; no rate counters for UI, no internet ping. */
    private final Runnable sampleLoop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            UsbLinkMonitor.Snapshot usb =
                    linkMonitor.sample(usbConnected, usbConfigured, rndisFunction);
            applySnapshot(usb, false);
            applyRadio(radioMonitor.sample(WatchdogService.this),
                    usb.usbConnected && (usb.rndisFunction || usb.ifaceName != null));
            publishStatus();
            updateNotification(shortNotifyText());

            if (usbConnected && rndisFunction
                    && "STARTING".equals(lastQuality) && !resetInFlight) {
                maybeAutoReset("rndis_iface_down");
            }

            if (worker != null) {
                worker.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            boolean wasConnected = usbConnected;
            applyUsbState(intent);

            if (running && worker != null && usbConnected && !wasConnected) {
                if (usbStateInitialized) {
                    maybeAutoReset("usb_reconnected");
                }
            }
            usbStateInitialized = true;

            if (worker != null) {
                worker.post(() -> {
                    UsbLinkMonitor.Snapshot usb =
                            linkMonitor.sample(usbConnected, usbConfigured, rndisFunction);
                    applySnapshot(usb, true);
                    applyRadio(radioMonitor.sample(WatchdogService.this),
                            usb.usbConnected && (usb.rndisFunction || usb.ifaceName != null));
                    publishStatus();
                });
            }
        }
    };

    private void applySnapshot(UsbLinkMonitor.Snapshot snap, boolean forceDetail) {
        lastQuality = snap.quality;
        lastStatusLabel = snap.statusLabel != null ? snap.statusLabel : "—";
        lastSpeedTier = snap.speedTier != null ? snap.speedTier : "USB · —";
        lastStatusLine = snap.summaryLine();

        boolean holdDetail = lastDetail.startsWith("Auto-reset:");
        if (forceDetail || (snap.detail != null && !snap.detail.isEmpty() && !holdDetail)) {
            lastDetail = snap.detail != null ? snap.detail : "";
        }
    }

    private void applyRadio(RadioMonitor.Snapshot radio, boolean tetherActive) {
        if (radio == null) return;
        if (radio.label != null && !radio.label.isEmpty()) {
            lastRadioLabel = radio.label;
        }
        if (radio.transitionLine == null || radio.transitionLine.isEmpty()) return;

        boolean cableIn = usbConnected || tetherActive;
        String ctx = RadioMonitor.classifyDropContext(
                cableIn,
                tetherActive,
                lastResetElapsedRealtime,
                SystemClock.elapsedRealtime());
        String line = RadioMonitor.formatDropEvent(
                radio.previousLabel,
                radio.label,
                radio.transitionTime,
                ctx,
                radio.stepDown);
        lastRadioEvent = line;
        lastRadioContext = ctx;
        radioMonitor.setLastAnnotatedEvent(line);

        if (radio.stepDown) {
            lastDetail = "Radio: " + line.replace('\n', ' ');
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(RadioMonitor.PREF_LAST_RADIO_EVENT, line)
                .putString(RadioMonitor.PREF_LAST_RADIO_LABEL, lastRadioLabel)
                .putString(RadioMonitor.PREF_LAST_RADIO_CONTEXT, ctx)
                .apply();
    }

    private void applyUsbState(Intent intent) {
        if (intent == null) return;
        boolean connected = intent.getBooleanExtra("connected", false);
        boolean configured = intent.getBooleanExtra("configured", false);
        boolean rndis = intent.getBooleanExtra("rndis", false)
                || intent.getBooleanExtra("ncm", false)
                || intent.getBooleanExtra("usb_function_rndis", false)
                || intent.getBooleanExtra("usb_function_ncm", false)
                || hasTruthyExtra(intent, "rndis")
                || hasTruthyExtra(intent, "ncm");

        usbConnected = connected;
        usbConfigured = configured;
        rndisFunction = rndis;
        linkMonitor.onUsbState(usbConnected, usbConfigured, rndisFunction);
    }

    private static boolean hasTruthyExtra(Intent intent, String key) {
        try {
            if (!intent.hasExtra(key)) return false;
            Object v = intent.getExtras() != null ? intent.getExtras().get(key) : null;
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) {
                String s = ((String) v).trim().toLowerCase();
                return s.equals("true") || s.equals("1") || s.equals("yes");
            }
            if (v instanceof Number) return ((Number) v).intValue() != 0;
        } catch (Exception ignored) {}
        return false;
    }

    private void maybeAutoReset(String reason) {
        long now = System.currentTimeMillis();
        if (resetInFlight) return;
        if (now - lastResetMs < RESET_COOLDOWN_MS) return;

        resetInFlight = true;
        lastResetMs = now;
        lastResetElapsedRealtime = SystemClock.elapsedRealtime();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(RadioMonitor.PREF_LAST_RESET_ELAPSED, lastResetElapsedRealtime)
                .apply();
        lastDetail = "Auto-reset: " + reason;
        publishStatus();
        updateNotification("Auto-reset: " + reason);

        RootUtil.performResetSequence(() -> {
            resetInFlight = false;
            lastDetail = "Auto-reset done (" + reason + ")";
            lastResetElapsedRealtime = SystemClock.elapsedRealtime();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(RadioMonitor.PREF_LAST_RESET_ELAPSED, lastResetElapsedRealtime)
                    .apply();
            publishStatus();
        });
    }

    private String shortNotifyText() {
        String base = lastStatusLabel + " · " + lastRadioLabel;
        if (lastRadioEvent != null && lastRadioEvent.contains("→")
                && RadioMonitor.CTX_TETHERED.equals(lastRadioContext)) {
            return lastRadioLabel + " drop · tethered";
        }
        if (lastRadioEvent != null && lastRadioEvent.contains("→")
                && RadioMonitor.CTX_AFTER_RESET.equals(lastRadioContext)) {
            return lastRadioLabel + " drop · after reset";
        }
        return base + " · auto-recover";
    }

    private void publishStatus() {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_LINE, lastStatusLine);
        i.putExtra(EXTRA_QUALITY, lastQuality);
        i.putExtra(EXTRA_STATUS_LABEL, lastStatusLabel);
        i.putExtra(EXTRA_SPEED_TIER, lastSpeedTier);
        i.putExtra(EXTRA_DETAIL, lastDetail);
        i.putExtra(EXTRA_RADIO_LABEL, lastRadioLabel);
        i.putExtra(EXTRA_RADIO_EVENT, lastRadioEvent);
        i.putExtra(EXTRA_RADIO_CONTEXT, lastRadioContext);
        i.putExtra(EXTRA_ACTIVE, true);
        sendBroadcast(i);

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_status_line", lastStatusLine)
                .putString("last_quality", lastQuality)
                .putString("last_status_label", lastStatusLabel)
                .putString("last_speed_tier", lastSpeedTier)
                .putString("last_detail", lastDetail)
                .putString(RadioMonitor.PREF_LAST_RADIO_LABEL, lastRadioLabel)
                .apply();
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Tether Watchdog", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("USB tether auto-recover");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Tether Watchdog")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        if (text == null) text = "";
        long now = System.currentTimeMillis();
        // Skip churn when nothing meaningful changed
        if (text.equals(lastNotifyText) && now - lastNotifyMs < NOTIFY_MIN_INTERVAL_MS) {
            return;
        }
        lastNotifyText = text;
        lastNotifyMs = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(1, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WATCHDOG, false).apply();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ACTIVE, false);
        i.putExtra(EXTRA_LINE, "Auto-recover off");
        i.putExtra(EXTRA_QUALITY, "—");
        i.putExtra(EXTRA_STATUS_LABEL, "—");
        i.putExtra(EXTRA_SPEED_TIER, "USB · —");
        i.putExtra(EXTRA_DETAIL, "Auto-recover stopped");
        sendBroadcast(i);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
