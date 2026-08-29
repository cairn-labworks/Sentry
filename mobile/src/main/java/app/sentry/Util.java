package app.sentry;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Environment;
import android.content.SharedPreferences;
import android.media.CamcorderProfile;
import android.preference.PreferenceManager;
import androidx.core.app.NotificationCompat;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.core.os.EnvironmentCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import app.sentry.models.Recording;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Global utility methods
 */

public final class Util {
    public static final String ACTION_UPDATE_RECORDINGS_LIST = "update.recordings.list";
    public static final int FOREGROUND_NOTIFICATION_ID = 51288;

    private static final String NOTIFICATIONS_CHANNEL_ID_MAIN_NOTIFICATIONS = "1001";
    private static final String NOTIFICATIONS_CHANNEL_NAME_MAIN_NOTIFICATIONS = "Main notifications";

    private static final String WARNING_CHANNEL_ID = "1002";
    private static final int WARNING_NOTIFICATION_ID = 51299;

    // Default values (used if the corresponding preference is not set)
    private static final int DEFAULT_QUOTA_MB = 1000;
    private static final int DEFAULT_CLIP_DURATION_SEC = 300;
    private static final int DEFAULT_OVERHEAT_THRESHOLD_C = 45;
    private static final int DEFAULT_LOW_BATTERY_THRESHOLD_PCT = 15;
    private static final int DEFAULT_OVERCHARGE_LIMIT_PCT = 80;
    private static final int DEFAULT_STATIONARY_TIMEOUT_MIN = 20;

    private static SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(SentryApp.getAppContext());
    }

    /**
     * Reads an integer preference that is stored as a String (e.g. from a ListPreference),
     * falling back to a default if unset or malformed.
     */
    private static int getIntPref(String key, int defaultValue) {
        try {
            return Integer.parseInt(getPrefs().getString(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Storage limit for recordings, in megabytes. When exceeded, the oldest non-starred
     * clips are deleted (loop recording).
     */
    public static int getQuota() {
        return getIntPref("storage_quota_mb", DEFAULT_QUOTA_MB);
    }

    /**
     * Threshold (in MB) at which the user is warned that space is running low. Roughly 20%
     * of the configured quota, with a small floor.
     */
    public static int getQuotaWarningThreshold() {
        return Math.max(50, getQuota() / 5);
    }

    /**
     * Length of each loop recording clip, in milliseconds.
     */
    public static int getMaxDuration() {
        return getIntPref("clip_duration_sec", DEFAULT_CLIP_DURATION_SEC) * 1000;
    }

    /**
     * Whether the overheating alert is enabled.
     */
    public static boolean isOverheatAlertEnabled() {
        return getPrefs().getBoolean("enable_overheat_alert", true);
    }

    /**
     * Battery temperature (in Celsius) at or above which the overheating alert fires.
     */
    public static int getOverheatThreshold() {
        return getIntPref("overheat_threshold_c", DEFAULT_OVERHEAT_THRESHOLD_C);
    }

    /**
     * Whether the app should safely shut down when the battery gets low and is not charging.
     */
    public static boolean isLowBatteryShutdownEnabled() {
        return getPrefs().getBoolean("enable_low_battery_shutdown", false);
    }

    /**
     * Whether the app should stop recording when the charger is disconnected.
     */
    public static boolean isAutoStopOnDischargeEnabled() {
        return getPrefs().getBoolean("auto_stop_on_discharge", false);
    }

    /**
     * Battery level (percent) at or below which the app shuts down (when not charging).
     */
    public static int getLowBatteryThreshold() {
        return getIntPref("low_battery_threshold_pct", DEFAULT_LOW_BATTERY_THRESHOLD_PCT);
    }

    /**
     * Whether overcharge protection is enabled. When on, the app warns the user to unplug once
     * the battery reaches {@link #getOverchargeLimit()} while charging, and (on rooted/vendor-
     * supported devices only) attempts to actually pause charging. Charging is considered fine
     * again once the level falls to {@link #getOverchargeResumeThreshold()}. Defaults to off.
     */
    public static boolean isOverchargeProtectionEnabled() {
        return getPrefs().getBoolean("enable_overcharge_protection", false);
    }

    /**
     * Battery level (percent) at or above which charging should be stopped/warned. Default 80.
     */
    public static int getOverchargeLimit() {
        return getIntPref("overcharge_limit_pct", DEFAULT_OVERCHARGE_LIMIT_PCT);
    }

    /**
     * Battery level (percent) at or below which charging may resume: 10 points under the limit,
     * per the desired hysteresis (e.g. limit 80 -> resume at 70). Floored at 0.
     */
    public static int getOverchargeResumeThreshold() {
        return Math.max(0, getOverchargeLimit() - 10);
    }

    /**
     * Whether the recorder should automatically pause when the vehicle is detected (via the
     * phone's motion sensors, fully offline) to be stationary for {@link
     * #getStationaryTimeoutMinutes()} minutes, and resume when motion is detected again.
     * Defaults to off.
     */
    public static boolean isAutoPauseStationaryEnabled() {
        return getPrefs().getBoolean("enable_auto_pause_stationary", false);
    }

    /**
     * How long (minutes) the vehicle must be continuously stationary before recording auto-pauses.
     * Default 20.
     */
    public static int getStationaryTimeoutMinutes() {
        return getIntPref("stationary_timeout_min", DEFAULT_STATIONARY_TIMEOUT_MIN);
    }

    /**
     * Whether Parking Mode is enabled. In parking mode the dashcam keeps recording even when the
     * vehicle is stationary (overriding auto-pause), and keeps going until the battery falls to the
     * low-battery threshold ({@link #getLowBatteryThreshold()}) while unplugged, at which point it
     * safely stops to avoid draining the phone. Defaults to off. Surfaced as a home-screen toggle.
     */
    public static boolean isParkingModeEnabled() {
        return getPrefs().getBoolean("parking_mode", false);
    }

    /** Enables or disables Parking Mode. */
    public static void setParkingMode(boolean enabled) {
        getPrefs().edit().putBoolean("parking_mode", enabled).apply();
    }

    /**
     * Preferred vertical video resolution (720, 1080 or 2160). Defaults to 1080.
     */
    public static int getVideoResolution() {
        return getIntPref("video_resolution", 1080);
    }

    /**
     * Whether electronic video stabilization (EIS) should be requested on the recording, for
     * smoother footage on bumpy roads. Only takes effect when the camera advertises support;
     * otherwise it silently no-ops. Defaults to on.
     */
    public static boolean isVideoStabilizationEnabled() {
        return getPrefs().getBoolean("enable_video_stabilization", true);
    }

    /**
     * Whether the experimental camera-placement (bonnet) mount check is enabled. When on, the
     * app briefly analyses the video while driving and warns if the car bonnet does not appear
     * to be visible along the bottom of the frame (a sign the camera is aimed too high). Runs
     * fully on-device with no internet. Defaults to off (experimental / needs on-road tuning).
     */
    public static boolean isMountCheckEnabled() {
        return getPrefs().getBoolean("enable_mount_check", false);
    }

    // --- Theme (light/dark/system) ---

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    /**
     * The user's chosen theme mode: {@link #THEME_SYSTEM} (default), {@link #THEME_LIGHT} or
     * {@link #THEME_DARK}.
     */
    public static String getThemeMode() {
        return getPrefs().getString("theme_mode", THEME_SYSTEM);
    }

    /**
     * Persists the theme mode and applies it immediately (recreating visible activities).
     */
    public static void setThemeMode(String mode) {
        getPrefs().edit().putString("theme_mode", mode).apply();
        applyThemeMode(mode);
    }

    /** Applies the stored theme mode; call once at app startup. */
    public static void applyStoredTheme() {
        applyThemeMode(getThemeMode());
    }

    private static void applyThemeMode(String mode) {
        switch (mode) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    // --- Night mode (camera scene mode + optional schedule) ---

    /** Whether the scheduled night mode feature is enabled. */
    public static boolean isNightModeEnabled() {
        return getPrefs().getBoolean("enable_night_mode", false);
    }

    /** Minute-of-day (0-1439) at which night mode turns on. Defaults to 1080 (6:00 PM). */
    public static int getNightStartMinutes() {
        return getIntPref("night_start_min", 18 * 60);
    }

    /** Minute-of-day (0-1439) at which night mode turns off. Defaults to 360 (6:00 AM). */
    public static int getNightEndMinutes() {
        return getIntPref("night_end_min", 6 * 60);
    }

    /** Persists the minute-of-day (0-1439) at which night mode turns on. */
    public static void setNightStartMinutes(int minutes) {
        getPrefs().edit().putString("night_start_min", String.valueOf(minutes)).apply();
    }

    /** Persists the minute-of-day (0-1439) at which night mode turns off. */
    public static void setNightEndMinutes(int minutes) {
        getPrefs().edit().putString("night_end_min", String.valueOf(minutes)).apply();
    }

    /**
     * Whether night mode should be active right now, based on the enable flag and the
     * scheduled start/end times (with midnight wrap-around). When start == end the schedule
     * is treated as always-on.
     */
    public static boolean isNightModeActiveNow() {
        if (!isNightModeEnabled()) {
            return false;
        }
        int start = getNightStartMinutes();
        int end = getNightEndMinutes();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        if (start == end) {
            return true;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        // Range wraps past midnight, e.g. 18:00 -> 06:00
        return now >= start || now < end;
    }

    /**
     * Maps the resolution preference to a supported CamcorderProfile quality, degrading
     * gracefully if the requested profile is unavailable on the device.
     */
    public static int getVideoQuality() {
        int res = getVideoResolution();
        if (res >= 2160 && CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_2160P)) {
            return CamcorderProfile.QUALITY_2160P;
        }
        if (res >= 1080 && CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_1080P)) {
            return CamcorderProfile.QUALITY_1080P;
        }
        if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_720P)) {
            return CamcorderProfile.QUALITY_720P;
        }
        if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_480P)) {
            return CamcorderProfile.QUALITY_480P;
        }
        return CamcorderProfile.QUALITY_HIGH;
    }

    // --- Recording lifecycle (shared by the home screen and the shortcut) ---

    /**
     * Starts the dashcam: optionally launches navigation, then starts the recorder and the
     * overlay widget services. Assumes required permissions (camera, mic, overlay) are granted.
     */
    public static void startRecordingServices(Context context) {
        Context app = context.getApplicationContext();

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(app);
        if (settings.getBoolean("start_maps_in_background", true)) {
            launchNavigation(app);
        }

        app.startService(new Intent(app, BackgroundVideoRecorder.class));
        app.startService(new Intent(app, WidgetService.class));
    }

    /**
     * Stops the dashcam: stops the recorder (finalizing the current clip) and the widget service.
     */
    public static void stopRecordingServices(Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, BackgroundVideoRecorder.class));
        app.stopService(new Intent(app, WidgetService.class));
    }

    /**
     * Whether the dashcam is currently recording.
     */
    public static boolean isRecording() {
        return BackgroundVideoRecorder.isRecording;
    }

    /**
     * Whether the mandatory recording permissions (camera, mic, overlay) are granted.
     */
    public static boolean hasRecordingPermissions(Context context) {
        boolean cam = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean mic = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(context);
        return cam && mic && overlay;
    }

    /**
     * Launches Google Maps in driving-navigation mode, if installed.
     */
    private static void launchNavigation(Context app) {
        try {
            Intent intent = app.getPackageManager()
                    .getLaunchIntentForPackage("com.google.android.apps.maps");
            if (intent == null) return;
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("google.navigation:/?free=1&mode=d&entry=fnls"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } catch (Exception ignored) {
            // Maps not installed or cannot launch; ignore
        }
    }

    public static File getVideosDirectoryPath() {
        //remove an old directory if exists
        File oldDirectory = new File(Environment.getExternalStorageDirectory() + "/Sentry/");
        removeNonEmptyDirectory(oldDirectory);

        //New directory
        File appVideosFolder = getAppPrivateVideosFolder(SentryApp.getAppContext());

        if (appVideosFolder != null) {
            //create app-private folder if not exists
            if (!appVideosFolder.exists()) appVideosFolder.mkdir();
            return appVideosFolder;
        }

        return null;
    }

    /**
     * Displays toast message of LONG length
     *
     * @param context Application context
     * @param msg     Message to display
     */
    public static void showToast(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }

    /**
     * Display a 9-seconds-long toast.
     * Inspired by https://stackoverflow.com/a/7173248
     *
     * @param context Application context
     * @param msg     Message to display
     */
    public static void showToastLong(Context context, String msg) {
        final Toast tag = Toast.makeText(context, msg, Toast.LENGTH_SHORT);

        tag.show();

        new CountDownTimer(9000, 1000) {

            public void onTick(long millisUntilFinished) {
                tag.show();
            }

            public void onFinish() {
                tag.show();
            }

        }.start();
    }

    /**
     * Starts new activity to open speicified file
     *
     * @param file     File to open
     * @param mimeType Mime type of the file to open
     */
    public static void openFile(Context context, Uri file, String mimeType) {
        Intent openFile = new Intent(Intent.ACTION_VIEW);
        openFile.setDataAndType(file, mimeType);
        openFile.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        openFile.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(openFile);
        } catch (ActivityNotFoundException e) {
            Log.i("Sentry", "Cannot open file.");
        }
    }

    /**
     * Calculates the size of a directory in megabytes
     *
     * @param file The directory to calculate the size of
     * @return size of a directory in megabytes
     */
    public static long getFolderSize(File file) {
        return getFolderSizeBytes(file) / (1024 * 1024);
    }

    private static long getFolderSizeBytes(File file) {
        if (file == null || !file.exists()) return 0;
        long size = 0;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File fileInDirectory : files) {
                    size += getFolderSizeBytes(fileInDirectory);
                }
            }
        } else {
            size = file.length();
        }
        return size;
    }

    /**
     * Get available space on the device
     *
     * @return
     */
    public static long getFreeSpaceExternalStorage(File storagePath) {
        if (storagePath == null || !storagePath.isDirectory()) return 0;
        return storagePath.getFreeSpace() / 1024 / 1024;
    }

    /**
     * Delete all recordings from storage and sqlite
     * <p>
     * NOTE: called from UI settings (here uses asynctask for background operation)
     */
    public static void deleteRecordings() {
        AsyncTaskCompat.executeParallel(new DeleteRecordingsTask());
    }

    /**
     * Star/unstar recording
     * <p>
     * NOTE: called from UI (uses asynctasks)
     *
     * @param recording
     */
    public static void updateStar(Recording recording) {
        AsyncTaskCompat.executeParallel(new UpdateStarTask(recording));
    }

    /**
     * Delete single recording from storage and SQLite
     * <p>
     * NOTE: called from background thread (BackgroundVideoRecorder)
     *
     * @param recording Recording
     */
    public static void deleteSingleRecording(Recording recording) {
        if (recording == null) return;
        //delete from storage
        new File(recording.getFilePath()).delete();

        //delete from db
        DBHelper.getInstance(SentryApp.getAppContext()).deleteRecording(
                new Recording(recording.getFilePath())
        );

        //broadcast for updating videos list in UI
        LocalBroadcastManager.getInstance(SentryApp.getAppContext()).sendBroadcast(
                new Intent(ACTION_UPDATE_RECORDINGS_LIST)
        );
    }

    /**
     * Returns every recorded clip under the recordings directory (recursively across the
     * dated/hourly folders).
     */
    public static List<File> getAllRecordings() {
        List<File> out = new ArrayList<>();
        collectRecordings(getVideosDirectoryPath(), out);
        return out;
    }

    private static void collectRecordings(File dir, List<File> out) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectRecordings(f, out);
            } else if (f.getName().endsWith(".mp4")) {
                out.add(f);
            }
        }
    }

    /**
     * Broadcasts that the set of recordings changed so any open UI refreshes.
     */
    public static void broadcastRecordingsChanged() {
        LocalBroadcastManager.getInstance(SentryApp.getAppContext()).sendBroadcast(
                new Intent(ACTION_UPDATE_RECORDINGS_LIST)
        );
    }

    // --- In-memory event log for the Live view ---

    private static final int MAX_EVENTS = 100;
    private static final ArrayDeque<String> sEventLog = new ArrayDeque<>();
    private static final SimpleDateFormat EVENT_TIME_FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    /**
     * Appends a timestamped line to the rolling event log shown in the Live view.
     */
    public static synchronized void logEvent(String message) {
        sEventLog.addFirst(EVENT_TIME_FMT.format(new Date()) + "   " + message);
        while (sEventLog.size() > MAX_EVENTS) {
            sEventLog.removeLast();
        }
    }

    /**
     * Returns a snapshot of the event log, newest first.
     */
    public static synchronized List<String> getEventLog() {
        return new ArrayList<>(sEventLog);
    }

    /**
     * Deletes a recording file, or an entire folder of recordings (recursively), cleaning up the
     * database and star entries for each removed clip. Does not broadcast; the caller should
     * refresh the UI afterwards.
     *
     * @param file File or directory to delete
     */
    public static void deleteRecordingFileOrFolder(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecordingFileOrFolder(child);
                }
            }
            file.delete();
        } else {
            DBHelper.getInstance(SentryApp.getAppContext()).deleteRecording(
                    new Recording(file.getAbsolutePath())
            );
            file.delete();
        }
    }

    /**
     * Insert new recording to SQLite
     * <p>
     * NOTE: called from background thread (BackgroundVideoRecorder)
     *
     * @param recording Recording
     */
    public static void insertNewRecording(Recording recording) {
        if (recording == null) return;
        DBHelper.getInstance(SentryApp.getAppContext()).insertNewRecording(recording);

        //broadcast for updating videos list in UI
        LocalBroadcastManager.getInstance(SentryApp.getAppContext()).sendBroadcast(
                new Intent(ACTION_UPDATE_RECORDINGS_LIST)
        );
    }


    /**
     * Iterate over supported camera video sizes to see which one best fits the
     * dimensions of the given view while maintaining the aspect ratio. If none can,
     * be lenient with the aspect ratio.
     *
     * @param supportedVideoSizes Supported camera video sizes.
     * @param previewSizes        Supported camera preview sizes.
     * @param w                   The width of the view.
     * @param h                   The height of the view.
     * @return Best match camera video size to fit in the view.
     */
    public static Camera.Size getOptimalVideoSize(List<Camera.Size> supportedVideoSizes,
                                                  List<Camera.Size> previewSizes, int w, int h) {
        // Use a very small tolerance because we want an exact match.
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) 16 / 9;//(double) w / h;

        // Supported video sizes list might be null, it means that we are allowed to use the preview
        // sizes
        List<Camera.Size> videoSizes;
        if (supportedVideoSizes != null) {
            videoSizes = supportedVideoSizes;
        } else {
            videoSizes = previewSizes;
        }
        Camera.Size optimalSize = null;

        // Start with max value and refine as we iterate over available video sizes. This is the
        // minimum difference between view and camera height.
        double minDiff = Double.MAX_VALUE;

        // Target view height
        int targetHeight = h;

        // Try to find a video size that matches aspect ratio and the target view size.
        // Iterate over all available sizes and pick the largest size that can fit in the view and
        // still maintain the aspect ratio.
        for (Camera.Size size : videoSizes) {
            //we need max size 1280x720
            if (size.width == 1920) continue;

            double ratio = (double) size.width / size.height;

            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
                continue;

            if (Math.abs(size.height - targetHeight) < minDiff && previewSizes.contains(size)) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find video size that matches the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : videoSizes) {
                if (Math.abs(size.height - targetHeight) < minDiff && previewSizes.contains(size)) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }

        return optimalSize;
    }

    /**
     * Create notification for status bar
     *
     * @param context Context
     * @return Notification
     */
    public static Notification createStatusBarNotification(Context context) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(
                context,
                NOTIFICATIONS_CHANNEL_ID_MAIN_NOTIFICATIONS)
                .setContentTitle(context.getResources().getString(R.string.notification_title))
                .setContentText(context.getResources().getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_videocam_red_128dp)
                .setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATIONS_CHANNEL_ID_MAIN_NOTIFICATIONS,
                    NOTIFICATIONS_CHANNEL_NAME_MAIN_NOTIFICATIONS,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.enableVibration(false);
            channel.setVibrationPattern(null);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }

        return notificationBuilder.build();
    }

    /**
     * Show (or update) a high-priority warning notification, e.g. for overheating.
     *
     * @param context Context
     * @param title   Notification title
     * @param text    Notification body
     */
    public static void showWarningNotification(Context context, String title, String text) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    WARNING_CHANNEL_ID,
                    context.getResources().getString(R.string.warning_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context,
                WARNING_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_videocam_red_128dp)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        if (notificationManager != null) {
            notificationManager.notify(WARNING_NOTIFICATION_ID, builder.build());
        }
    }

    /**
     * Get path to app-private folder (Android/data/[app name]/files)
     *
     * @param context Context
     * @return Folder
     */
    private static File getAppPrivateVideosFolder(Context context) {
        try {
            File[] extAppFolders = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES);
            if (extAppFolders == null) return null;

            for (File file : extAppFolders) {
                if (file != null) {
                    //find external app-private folder (emulated - it's internal storage)
                    if (!file.getAbsolutePath().toLowerCase().contains("emulated") && isStorageMounted(file)) {
                        return file;
                    }
                }
            }

            //if external storage is not found
            if (extAppFolders.length > 0) {
                File appFolder;
                //get available app-private folder form the list
                for (int i = extAppFolders.length - 1, j = 0; i >= j; i--) {
                    appFolder = extAppFolders[i];
                    if (appFolder != null && isStorageMounted(appFolder)) {
                        return appFolder;
                    }
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            Log.e(Util.class.getSimpleName(), "getAppPrivateVideosFolder: Exception - " + e.getLocalizedMessage(), e);
        }
        return null;
    }

    /**
     * Check if storage mounted and has read/write access.
     *
     * @param storagePath Storage path
     * @return True - can write data
     */
    private static boolean isStorageMounted(File storagePath) {
        String storageState = EnvironmentCompat.getStorageState(storagePath);
        return storageState.equals(Environment.MEDIA_MOUNTED);
    }

    /**
     * Remove non-empty directory
     *
     * @param path Directory path
     * @return True - Removed
     */
    private static boolean removeNonEmptyDirectory(File path) {
        if (path.exists()) {
            for (File file : path.listFiles()) {
                if (file.isDirectory()) {
                    removeNonEmptyDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        return path.delete();
    }


    /**
     * AsyncTask for delete recordings from storage and SQLite
     */
    private static class DeleteRecordingsTask extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... voids) {
            DBHelper dbHelper = DBHelper.getInstance(SentryApp.getAppContext());

            //remove all items from the SQLite database (recordings + stars)
            dbHelper.deleteAllRecordings();

            // Recursively remove every file and dated/hourly folder under the recordings directory
            File videosDir = getVideosDirectoryPath();
            boolean anythingRemoved = false;
            if (videosDir != null && videosDir.isDirectory()) {
                File[] children = videosDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecordingFileOrFolder(child);
                        anythingRemoved = true;
                    }
                }
            }

            return anythingRemoved;
        }

        @Override
        protected void onPostExecute(Boolean aBoolean) {
            Context context = SentryApp.getAppContext();
            Resources res = context.getResources();
            Util.showToastLong(
                    context,
                    aBoolean
                            ? res.getString(R.string.pref_delete_recordings_confirmation)
                            : res.getString(R.string.recordings_list_empty_message_title)
            );
            // Refresh any open recordings screen
            broadcastRecordingsChanged();
        }
    }

    /**
     * AsyncTask for star/unstar
     */
    private static class UpdateStarTask extends AsyncTask<Void, Void, Void> {
        private Recording mRecording;

        UpdateStarTask(Recording recording) {
            mRecording = recording;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            DBHelper dbHelper = DBHelper.getInstance(SentryApp.getAppContext());
            //insert or delete star
            dbHelper.updateStar(mRecording);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            //broadcast for updating videos list in UI
            LocalBroadcastManager.getInstance(SentryApp.getAppContext()).sendBroadcast(
                    new Intent(Util.ACTION_UPDATE_RECORDINGS_LIST)
            );
        }
    }

}
