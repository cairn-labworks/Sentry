package app.sentry;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.WindowManager;

import app.sentry.models.Widget;

public class WidgetService extends Service {

    private static final long OVERHEAT_WARN_INTERVAL_MS = 60 * 1000;

    private WindowManager windowManager;
    private Widget overlayWidget;
    private PowerManager.WakeLock mWakeLock;

    private long mLastOverheatWarnMs = 0;
    private boolean mIsShuttingDown = false;

    // Tracks charger-plugged state across battery broadcasts so we can detect the
    // plugged -> unplugged transition and auto-stop recording. We rely on the
    // reliably-delivered ACTION_BATTERY_CHANGED broadcast (registered while this
    // foreground service runs) rather than the manifest PowerConnectionReceiver,
    // which aggressive OEM battery managers (e.g. ColorOS) block in the background.
    private boolean mPluggedStateKnown = false;
    private boolean mWasPluggedIn = false;

    // Overcharge protection: tracks whether we've already acted at/above the limit, so we alert
    // (and pause charging where supported) once per crossing and re-arm only after the level drops
    // back to the resume threshold.
    private boolean mChargeLimitReached = false;
    private long mLastOverchargeWarnMs = 0;

    /**
     * Monitors battery temperature and level to protect battery health:
     * warns on overheating and can safely shut the app down on low battery.
     */
    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            int tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;

            // Auto-stop on unplug. EXTRA_PLUGGED is 0 only when the cable is truly
            // disconnected (unlike "charging", which is false when the battery is FULL
            // but still plugged in). Detect the plugged -> unplugged transition here so
            // auto-stop works even when the manifest PowerConnectionReceiver is blocked.
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            if (plugged != -1) {
                boolean pluggedIn = plugged != 0;
                if (!mPluggedStateKnown) {
                    mPluggedStateKnown = true;
                    mWasPluggedIn = pluggedIn;
                } else if (mWasPluggedIn && !pluggedIn) {
                    mWasPluggedIn = false;
                    if (Util.isAutoStopOnDischargeEnabled()) {
                        Util.logEvent("Auto-stop: charger disconnected");
                        safeShutdown(getString(R.string.auto_stop_discharge_message));
                        return;
                    }
                } else {
                    mWasPluggedIn = pluggedIn;
                }
            }

            // Overheating alert (battery temperature is reported in tenths of a degree Celsius)
            if (tempTenths != Integer.MIN_VALUE && Util.isOverheatAlertEnabled()) {
                int tempC = Math.round(tempTenths / 10f);
                if (tempC >= Util.getOverheatThreshold()) {
                    long now = System.currentTimeMillis();
                    if (now - mLastOverheatWarnMs >= OVERHEAT_WARN_INTERVAL_MS) {
                        mLastOverheatWarnMs = now;
                        String text = getString(R.string.overheat_warning_text, tempC);
                        Util.showWarningNotification(
                                getApplicationContext(),
                                getString(R.string.overheat_warning_title),
                                text);
                        Util.showToastLong(getApplicationContext(), text);
                        Util.logEvent("Overheating: " + tempC + "\u00B0C");
                    }
                }
            }

            // Low-battery safe shutdown (only when not charging). Also triggered by Parking Mode,
            // which keeps recording while parked until the battery reaches this same threshold.
            boolean lowBattStopEnabled = Util.isLowBatteryShutdownEnabled() || Util.isParkingModeEnabled();
            if (lowBattStopEnabled && !charging && level >= 0 && scale > 0) {
                int percent = Math.round(level * 100f / scale);
                if (percent <= Util.getLowBatteryThreshold()) {
                    safeShutdown(getString(R.string.low_battery_shutdown_message, percent));
                }
            }

            // Overcharge protection
            if (Util.isOverchargeProtectionEnabled() && level >= 0 && scale > 0) {
                int percent = Math.round(level * 100f / scale);
                handleOverchargeProtection(percent, charging);
            }
        }
    };

    /**
     * Warns the user (and, on rooted/vendor-supported devices only, pauses charging) once the
     * battery reaches the configured limit while charging, then re-arms after the level falls back
     * to the resume threshold (limit - 10). On unsupported devices this is a notify-only reminder,
     * since stock Android has no public API to stop charging.
     */
    private void handleOverchargeProtection(int percent, boolean charging) {
        int limit = Util.getOverchargeLimit();
        int resume = Util.getOverchargeResumeThreshold();

        if (!mChargeLimitReached && charging && percent >= limit) {
            mChargeLimitReached = true;
            boolean stopped = ChargeController.setCharging(false);
            long now = System.currentTimeMillis();
            if (now - mLastOverchargeWarnMs >= OVERHEAT_WARN_INTERVAL_MS) {
                mLastOverchargeWarnMs = now;
                String text = stopped
                        ? "Battery reached " + percent + "%. Charging paused to protect battery health."
                        : "Battery reached " + percent + "%. Unplug the charger to protect battery health.";
                Util.showWarningNotification(getApplicationContext(),
                        "Overcharge protection", text);
                Util.showToastLong(getApplicationContext(), text);
            }
            Util.logEvent("Overcharge limit reached at " + percent + "% (hardwarePause=" + stopped + ")");
        } else if (mChargeLimitReached && percent <= resume) {
            mChargeLimitReached = false;
            boolean resumed = ChargeController.setCharging(true);
            Util.logEvent("Overcharge re-armed at " + percent + "% (hardwareResume=" + resumed + ")");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Return the communication channel to the service.
        // Not used
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayWidget = new Widget(this, windowManager);
        overlayWidget.show();

        // Start in foreground to avoid unexpected kill
        startForeground(
                Util.FOREGROUND_NOTIFICATION_ID,
                Util.createStatusBarNotification(this)
        );

        //Prevent going to sleep mode while service is working
        //https://developer.android.com/reference/android/os/PowerManager.html
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WidgetService.class.getSimpleName()
            );
            mWakeLock.acquire();
        }

        // Monitor battery temperature and level
        registerReceiver(mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    /**
     * Safely stops recording (flushing the current clip) and quits the app.
     *
     * @param message Message shown to the user explaining why the app is shutting down
     */
    private void safeShutdown(String message) {
        if (mIsShuttingDown) return;
        mIsShuttingDown = true;

        Util.logEvent(message);
        Util.showToastLong(getApplicationContext(), message);

        // Stopping the recorder triggers its onDestroy, which finalizes and saves the current clip
        stopService(new Intent(this, BackgroundVideoRecorder.class));
        // Stopping this service triggers its onDestroy, which removes the widget and returns home
        stopSelf();
    }

    @Override
    public void onDestroy() {

        // Stop monitoring battery
        try {
            unregisterReceiver(mBatteryReceiver);
        } catch (Exception ignored) {
            // Receiver may not be registered
        }

        // Safety: never leave charging paused after we stop. If overcharge protection had paused
        // charging on a supported device, re-enable it now.
        if (mChargeLimitReached) {
            ChargeController.setCharging(true);
        }

        // Remove rootView views from display
        if (overlayWidget != null) {
            overlayWidget.hide();
        }

        // Close DB connection
        DBHelper dbHelper = DBHelper.getInstance(this);
        dbHelper.close();

        // Note: we intentionally do NOT launch the launcher home screen here.
        // Stopping the recording should simply remove the overlay widget and leave
        // whatever the user was on (e.g. the app's home screen) visible.

        //remove wakelock
        if (mWakeLock != null) {
            mWakeLock.release();
        }
        stopForeground(true);

    }
}
