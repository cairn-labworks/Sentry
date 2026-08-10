package com.opendashcam;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.WindowManager;

import com.opendashcam.models.Widget;

public class WidgetService extends Service {

    private static final long OVERHEAT_WARN_INTERVAL_MS = 60 * 1000;

    private WindowManager windowManager;
    private Widget overlayWidget;
    private PowerManager.WakeLock mWakeLock;

    private long mLastOverheatWarnMs = 0;
    private boolean mIsShuttingDown = false;

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

            // Low-battery safe shutdown (only when not charging)
            if (Util.isLowBatteryShutdownEnabled() && !charging && level >= 0 && scale > 0) {
                int percent = Math.round(level * 100f / scale);
                if (percent <= Util.getLowBatteryThreshold()) {
                    safeShutdown(getString(R.string.low_battery_shutdown_message, percent));
                }
            }
        }
    };

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

        // Remove rootView views from display
        if (overlayWidget != null) {
            overlayWidget.hide();
        }

        // Close DB connection
        DBHelper dbHelper = DBHelper.getInstance(this);
        dbHelper.close();

        // Return to home screen
        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(startMain);

        //remove wakelock
        if (mWakeLock != null) {
            mWakeLock.release();
        }
        stopForeground(true);

    }
}
