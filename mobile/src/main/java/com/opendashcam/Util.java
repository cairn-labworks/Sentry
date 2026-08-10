package com.opendashcam;

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
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.core.os.EnvironmentCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.opendashcam.models.Recording;

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

    private static SharedPreferences getPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(OpenDashApp.getAppContext());
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
     * Battery level (percent) at or below which the app shuts down (when not charging).
     */
    public static int getLowBatteryThreshold() {
        return getIntPref("low_battery_threshold_pct", DEFAULT_LOW_BATTERY_THRESHOLD_PCT);
    }

    /**
     * Preferred vertical video resolution (720 or 1080). Defaults to 1080.
     */
    public static int getVideoResolution() {
        return getIntPref("video_resolution", 1080);
    }

    /**
     * Maps the resolution preference to a supported CamcorderProfile quality, degrading
     * gracefully if the requested profile is unavailable on the device.
     */
    public static int getVideoQuality() {
        int res = getVideoResolution();
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

    public static File getVideosDirectoryPath() {
        //remove an old directory if exists
        File oldDirectory = new File(Environment.getExternalStorageDirectory() + "/OpenDashCam/");
        removeNonEmptyDirectory(oldDirectory);

        //New directory
        File appVideosFolder = getAppPrivateVideosFolder(OpenDashApp.getAppContext());

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
            Log.i("OpenDashCam", "Cannot open file.");
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
        DBHelper.getInstance(OpenDashApp.getAppContext()).deleteRecording(
                new Recording(recording.getFilePath())
        );

        //broadcast for updating videos list in UI
        LocalBroadcastManager.getInstance(OpenDashApp.getAppContext()).sendBroadcast(
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
        LocalBroadcastManager.getInstance(OpenDashApp.getAppContext()).sendBroadcast(
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
            DBHelper.getInstance(OpenDashApp.getAppContext()).deleteRecording(
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
        DBHelper.getInstance(OpenDashApp.getAppContext()).insertNewRecording(recording);

        //broadcast for updating videos list in UI
        LocalBroadcastManager.getInstance(OpenDashApp.getAppContext()).sendBroadcast(
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
            DBHelper dbHelper = DBHelper.getInstance(OpenDashApp.getAppContext());

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
            Context context = OpenDashApp.getAppContext();
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
            DBHelper dbHelper = DBHelper.getInstance(OpenDashApp.getAppContext());
            //insert or delete star
            dbHelper.updateStar(mRecording);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            //broadcast for updating videos list in UI
            LocalBroadcastManager.getInstance(OpenDashApp.getAppContext()).sendBroadcast(
                    new Intent(Util.ACTION_UPDATE_RECORDINGS_LIST)
            );
        }
    }

}
