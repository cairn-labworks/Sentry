package com.opendashcam;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import androidx.core.content.ContextCompat;

import com.opendashcam.models.Recording;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Background video recording service.
 * Inspired by
 * https://stackoverflow.com/questions/15049041/background-video-recording-in-android-4-0
 * https://stackoverflow.com/questions/21264592/android-split-video-during-capture
 * Parts contributed by Toshio Azuma
 */
public class BackgroundVideoRecorder extends Service implements SurfaceHolder.Callback {
    private WindowManager windowManager;
    private SurfaceView surfaceView;
    private volatile Camera camera = null;
    private volatile MediaRecorder mediaRecorder = null;
    private String currentVideoFile = "null";
    private SharedPreferences sharedPref;
    private HandlerThread thread;
    private Handler backgroundThread;
    private SharedPreferences settings;
    private SharedPreferences.Editor editor;
    private Handler mainThread = new Handler(Looper.getMainLooper());
    private File mRecordingsDirectory;
    private PowerManager.WakeLock mWakeLock;

    /** Whether the background recorder is currently active (read by the Live view). */
    public static volatile boolean isRecording = false;
    /** When the current recording session started (millis), for elapsed display. */
    public static volatile long recordingStartedAt = 0L;

    @Override
    public void onCreate() {
        //long startTime = System.currentTimeMillis();
        thread = new HandlerThread("io_processor_thread");
        thread.start();
        backgroundThread = new Handler(thread.getLooper());

        // Start in foreground to avoid unexpected kill
        startForeground(
                Util.FOREGROUND_NOTIFICATION_ID,
                Util.createStatusBarNotification(this)
        );

        // Keep the CPU running so recording continues while the screen is off.
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OpenDashCam:RecorderWakeLock"
            );
            mWakeLock.acquire();
        }

        isRecording = true;
        recordingStartedAt = System.currentTimeMillis();
        Util.logEvent("Recording started");

        sharedPref = this.getApplicationContext().getSharedPreferences(
                getString(R.string.current_recordings_preferences_key),
                Context.MODE_PRIVATE);
        editor = sharedPref.edit();
        settings = PreferenceManager.getDefaultSharedPreferences(this);

        // Create new SurfaceView, set its size to 1x1, move it to the top left corner and set this service as a callback
        windowManager = (WindowManager) this.getSystemService(Context.WINDOW_SERVICE);
        surfaceView = new SurfaceView(this);

        int type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                1, 1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.LEFT | Gravity.TOP;
        windowManager.addView(surfaceView, layoutParams);
        surfaceView.getHolder().addCallback(this);

        // Set shutter sound based on preferences
        disableSound(editor);

        // Create directory for recordings if not exists
        mRecordingsDirectory = Util.getVideosDirectoryPath();
        if (!mRecordingsDirectory.isDirectory() || !mRecordingsDirectory.exists()) {
            mRecordingsDirectory.mkdir();
        }

        //long elapsedTime = System.currentTimeMillis() - startTime;
        //Log.i("DEBUG", "onCreate Time: " + (TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.MILLISECONDS)) + " milliseconds");
    }

    // Method called right after Surface created (initializing and starting MediaRecorder)
    @Override
    public void surfaceCreated(final SurfaceHolder surfaceHolder) {
        backgroundThread.post(new Runnable() {
            @Override
            public void run() {
                // Initialize Media Recorder
                initMediaRecorder(surfaceHolder);

                // Prepare
                try {
                    mediaRecorder.prepare();
                    mediaRecorder.start();
                    Log.d("VIDEOCAPTURE", "BackgroundVideoRecorder.run(): start recording");
                } catch (Exception e) {
                    Log.e("VIDEOCAPTURE", "mediaRecorder.prepare() threw exception for some reason!", e);
                }
            }
        });

    }

    private void initMediaRecorder(final SurfaceHolder surfaceHolder) {
        rotateRecordings(BackgroundVideoRecorder.this, Util.getQuota());
        // Low-priority housekeeping: move clips from finished hours into dated folders
        organizeRecordings();
        camera = Camera.open();
        if (camera != null) {
            // Silence the per-clip shutter / record-start sound where the device allows it
            camera.enableShutterSound(false);
        }
        Camera.Parameters cameraParams = camera != null ? camera.getParameters() : null;
        if (camera != null) camera.unlock();

        //define video quality based on the user's resolution preference
        int videoQuality = Util.getVideoQuality();

        // Log.d("VIDEOCAPTURE", "BackgroundVideoRecorder.initMediaRecorder(): quality " + videoQuality);

        //create camcorder profile and set optimal video size
        CamcorderProfile camcorderProfile = CamcorderProfile.get(videoQuality);
        // For 1080p keep the profile's native 1920x1080; only fit-to-view for lower resolutions
        if (cameraParams != null && videoQuality != CamcorderProfile.QUALITY_1080P) {
            List<Camera.Size> previewSizes = cameraParams.getSupportedPreviewSizes();
            List<Camera.Size> videoSizes = cameraParams.getSupportedVideoSizes();
            WindowManager window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

            if (window != null && previewSizes != null && videoSizes != null) {
                DisplayMetrics displayMetrics = new DisplayMetrics();
                window.getDefaultDisplay().getMetrics(displayMetrics);

                //get and set optimal video size
                Camera.Size videoSize = Util.getOptimalVideoSize(videoSizes, previewSizes, displayMetrics.widthPixels, displayMetrics.heightPixels);
                //  Log.d("VIDEOCAPTURE", "BackgroundVideoRecorder.initMediaRecorder(): optimal video size - " + videoSize.width + "x" + videoSize.height);

                camcorderProfile.videoFrameWidth = videoSize.width;
                camcorderProfile.videoFrameHeight = videoSize.height;
            }
        }

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setCamera(camera); // TODO See if we can remove this line. We can't, because media recorder should know what camera object will be used
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        mediaRecorder.setProfile(camcorderProfile);
        mediaRecorder.setVideoEncodingBitRate(3000000);
        mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());
        // Store previous and current recording filenames, so that they may be retrieved by the
        // SaveRecording button

        // previous recording = currentVideoFile
        editor.putString(
                getString(R.string.previous_recording_preferences_key),
                currentVideoFile);
        editor.apply();

        // Path to the file with the recording to be created
        currentVideoFile = mRecordingsDirectory.getAbsolutePath() + File.separator +
                DateFormat.format("yyyy-MM-dd_kk-mm-ss", new Date().getTime()) +
                ".mp4";

        // // current recording = currentVideoFile (after updated)
        editor.putString(
                getString(R.string.current_recording_preferences_key),
                currentVideoFile);
        editor.apply();

        mediaRecorder.setOutputFile(currentVideoFile);

        // Embed GPS location into the clip's metadata when available (safe if location is off/denied)
        Location loc = getLastKnownLocationSafe();
        if (loc != null) {
            try {
                mediaRecorder.setLocation((float) loc.getLatitude(), (float) loc.getLongitude());
            } catch (Exception ignored) {
                // setLocation is best-effort
            }
        }

        Util.logEvent("New clip: " + new File(currentVideoFile).getName());
        mediaRecorder.setMaxDuration(Util.getMaxDuration());

        // When maximum video length reached
        mediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(MediaRecorder mr, int what, int extra) {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && null != mediaRecorder) {
                    mediaRecorder.setOnInfoListener(null);
                    Log.d("VIDEOCAPTURE", "Maximum Duration Reached. Stop recording.");
                    mediaRecorder.stop();
                    mediaRecorder.reset();
                    mediaRecorder.release();
                    mediaRecorder = null;
                    if (null != camera) {
                        camera.lock();
                        camera.release();
                        camera = null;
                    }

                    //insert new entry to SQLite
                    Util.insertNewRecording(
                            new Recording(currentVideoFile)
                    );

                    surfaceCreated(surfaceHolder);
                }
            }
        });
    }

    /**
     * Returns the most recent known location, or null if location is off, unavailable, or the
     * permission has not been granted. Fully guarded so it never throws.
     */
    private Location getLastKnownLocationSafe() {
        try {
            boolean fine = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) return null;

            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;

            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    // Stop recording and remove SurfaceView
    @Override
    public void onDestroy() {
        isRecording = false;
        Util.logEvent("Recording stopped");
        backgroundThread.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mediaRecorder != null) {
                        mediaRecorder.stop();
                        mediaRecorder.reset();
                        mediaRecorder.release();
                        mediaRecorder.setOnInfoListener(null);
                        mediaRecorder = null;
                    }
                    if (null != camera) {
                        camera.lock();
                        camera.release();
                        camera = null;
                    }
                } catch (RuntimeException e) {
                    Log.e("DashCam", "BackgroundVideoRecorder.run: RuntimeException - " + e.getLocalizedMessage(), e);
                }
                backgroundThread.removeCallbacksAndMessages(null);
                mainThread.removeCallbacksAndMessages(null);
                thread.quit();
                thread = null;
                backgroundThread = null;
                mainThread = null;

                //insert new entry to SQLite
                Util.insertNewRecording(
                        new Recording(currentVideoFile)
                );

                reEnableSound();
            }
        });

        windowManager.removeView(surfaceView);
        stopForeground(true);

        // Release the recording wake lock
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    /**
     * Removes old recordings to create space for the new ones in order to stay within the
     * set app quota. Works recursively across the dated/hourly folder structure.
     *
     * @param quota Maximum size the recordings directory may reach in megabytes
     */
    private void rotateRecordings(Context context, int quota) {
        long startTime = System.currentTimeMillis();
        // Quota exceeded?
        while (Util.getFolderSize(mRecordingsDirectory) >= quota) {
            // Collect every clip, including those already moved into dated/hourly folders
            List<File> videoFiles = new ArrayList<>();
            collectVideoFiles(mRecordingsDirectory, videoFiles);

            File oldestFile = null;
            int starred_videos_total_size = 0;

            for (File fileInDirectory : videoFiles) {
                // Skip starred recordings, we don't want to rotate those
                Recording recording = new Recording(fileInDirectory.getAbsolutePath());
                if (recording.isStarred()) {
                    starred_videos_total_size += fileInDirectory.length() / (1024 * 1024);
                    continue;
                }

                // Track the oldest non-starred clip
                if (oldestFile == null
                        || oldestFile.lastModified() > fileInDirectory.lastModified()) {
                    oldestFile = fileInDirectory;
                }
            }

            if ((quota - starred_videos_total_size) < Util.getQuotaWarningThreshold()) {
                Util.showToastLong(
                        context.getApplicationContext(),
                        "WARNING: Low on space quota.\n" +
                                "Un-star videos to free up space.");
            }

            if (oldestFile == null) {
                break;
            }

            //delete recording from storage and sqlite
            Util.deleteSingleRecording(
                    new Recording(oldestFile.getAbsolutePath())
            );
        }

        // Remove any now-empty dated/hourly folders
        pruneEmptyDirectories(mRecordingsDirectory);

        long elapsedTime = System.currentTimeMillis() - startTime;
        Log.d("DEBUG", "rotateRecordings Time: " + (TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.MILLISECONDS)) + " milliseconds");
    }

    /**
     * Moves completed clips out of the "live" root folder into a dated/hourly structure
     * (yyyy-MM-dd/HH) once their hour has ended. Clips from the current hour are left in the
     * root so the in-progress recording is never touched. This is low-priority housekeeping
     * that runs on the recorder's background thread each time a new clip starts.
     */
    private void organizeRecordings() {
        File root = mRecordingsDirectory;
        if (root == null) return;
        File[] files = root.listFiles();
        if (files == null) return;

        Calendar now = Calendar.getInstance();
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat hourFmt = new SimpleDateFormat("HH", Locale.US);
        boolean movedAny = false;

        for (File f : files) {
            // Only loose clips directly in the root are candidates; organized clips are in subfolders
            if (f.isDirectory()) continue;
            if (!f.getName().endsWith(".mp4")) continue;
            // Never move the clip currently being written
            if (f.getAbsolutePath().equals(currentVideoFile)) continue;

            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(f.lastModified());

            boolean sameHour = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
                    && c.get(Calendar.HOUR_OF_DAY) == now.get(Calendar.HOUR_OF_DAY);
            if (sameHour) continue; // current hour still in progress; keep clip in the live area

            File targetDir = new File(new File(root, dateFmt.format(c.getTime())), hourFmt.format(c.getTime()));
            if (!targetDir.exists()) targetDir.mkdirs();

            File target = new File(targetDir, f.getName());
            if (!target.exists() && f.renameTo(target)) {
                movedAny = true;
            }
        }

        if (movedAny) {
            Util.broadcastRecordingsChanged();
            Util.logEvent("Filed older clips into dated folders");
        }
    }

    /**
     * Recursively collects all .mp4 clips under the given directory.
     */
    private void collectVideoFiles(File dir, List<File> out) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectVideoFiles(f, out);
            } else if (f.getName().endsWith(".mp4")) {
                out.add(f);
            }
        }
    }

    /**
     * Removes empty directories (e.g. dated/hourly folders left behind after rotation), but
     * never removes the root recordings directory itself.
     */
    private void pruneEmptyDirectories(File root) {
        if (root == null) return;
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                pruneEmptyDirectories(child);
                File[] inner = child.listFiles();
                if (inner == null || inner.length == 0) {
                    child.delete();
                }
            }
        }
    }

    /**
     * Disable system sounds if set in preferences
     *
     * NOTE: From N onward, volume adjustments that would toggle Do Not Disturb are not allowed unless
     *              the app has been granted Do Not Disturb Access.
     *
     * @param editor Editor for current recordings preference
     */
    private void disableSound(SharedPreferences.Editor editor) {
//        long startTime = System.currentTimeMillis();
        if (settings.getBoolean("disable_sound", true)) {
            // Record system volume before app was started
            AudioManager audio = (AudioManager) this.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            int volume = audio.getStreamVolume(AudioManager.STREAM_SYSTEM);
            editor.putInt(
                    getString(R.string.pre_start_volume),
                    volume);
            editor.apply();
            // Only make change if not in silent
            if (volume > 0) {
                // Set to silent & vibrate
                audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            }
        }
//        long elapsedTime = System.currentTimeMillis() - startTime;
//        Log.i("DEBUG", "disableSound Time: " + (TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.MILLISECONDS)) + " milliseconds");
    }

    private void reEnableSound() {
//        long startTime = System.currentTimeMillis();
        // Record system volume before app was started
        AudioManager audio = (AudioManager) this.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        int volume = sharedPref.getInt(this.getString(R.string.pre_start_volume), 0);
        // Only make change if not in silent
        if (volume > 0) {
            // Set to silent & vibrate
            audio.setStreamVolume(AudioManager.STREAM_SYSTEM, volume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
        }
//        long elapsedTime = System.currentTimeMillis() - startTime;
//        Log.i("DEBUG", "reEnableSound Time: " + (TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.MILLISECONDS)) + " milliseconds");
    }


    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}