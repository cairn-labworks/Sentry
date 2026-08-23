package app.sentry;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.effects.Frame;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background video recording service, built on CameraX.
 *
 * Uses {@link VideoCapture} + {@link Recorder} for loop recording (each segment is bounded by a
 * duration limit and the next one starts on finalize), and {@link OverlayEffect} to burn the
 * current date/time and GPS location directly into the recorded frames.
 */
public class BackgroundVideoRecorder extends LifecycleService {

    private static final String TAG = "DashCamRecorder";

    /** Whether the background recorder is currently active (read by the Live view). */
    public static volatile boolean isRecording = false;
    /** When the current recording session started (millis), for elapsed display. */
    public static volatile long recordingStartedAt = 0L;

    private SharedPreferences sharedPref;
    private SharedPreferences settings;
    private SharedPreferences.Editor editor;

    private File mRecordingsDirectory;
    private String currentVideoFile = "null";

    private PowerManager.WakeLock mWakeLock;

    private ProcessCameraProvider mCameraProvider;
    private androidx.camera.core.Camera mCamera;
    private Recorder mRecorder;
    private VideoCapture<Recorder> mVideoCapture;
    private OverlayEffect mOverlayEffect;
    private androidx.camera.video.Recording mActiveRecording;

    // Experimental offline camera-placement (bonnet) check.
    private ImageAnalysis mImageAnalysis;
    private BonnetMountChecker mBonnetChecker;
    private ExecutorService mAnalysisExecutor;
    private volatile boolean mMountWarned = false;

    // Tracks the currently-applied night-mode state so we only re-apply/log on transitions.
    private Boolean mNightAppliedState = null;
    // Log the stabilization decision only once per recording session.
    private boolean mStabilizationLogged = false;

    private HandlerThread mGlThread;
    private Handler mGlHandler;

    private volatile boolean mStopping = false;

    // Cached location for the burned-in overlay (updated by the location listener)
    private LocationManager mLocationManager;
    private volatile boolean mHasLocation = false;
    private volatile double mLat = 0, mLng = 0;
    private volatile float mSpeedKmh = 0;
    private volatile Location mLastLocation = null;

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            mLat = location.getLatitude();
            mLng = location.getLongitude();
            mSpeedKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
            mLastLocation = location;
            mHasLocation = true;
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Start in foreground to avoid unexpected kill
        startForeground(Util.FOREGROUND_NOTIFICATION_ID, Util.createStatusBarNotification(this));

        // Keep the CPU running so recording continues while the screen is off
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "Sentry:RecorderWakeLock");
            mWakeLock.acquire();
        }

        sharedPref = getApplicationContext().getSharedPreferences(
                getString(R.string.current_recordings_preferences_key), Context.MODE_PRIVATE);
        editor = sharedPref.edit();
        settings = PreferenceManager.getDefaultSharedPreferences(this);

        // Prepare recordings directory
        mRecordingsDirectory = Util.getVideosDirectoryPath();
        if (mRecordingsDirectory != null
                && (!mRecordingsDirectory.isDirectory() || !mRecordingsDirectory.exists())) {
            mRecordingsDirectory.mkdirs();
        }

        disableSound(editor);
        setupOverlayPaints();
        startLocationUpdates();

        mGlThread = new HandlerThread("overlay_gl_thread");
        mGlThread.start();
        mGlHandler = new Handler(mGlThread.getLooper());

        isRecording = true;
        recordingStartedAt = System.currentTimeMillis();
        Util.logEvent("Recording started");

        startCamera();
    }

    // --- CameraX setup ---

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    mCameraProvider = future.get();
                    bindUseCases();
                    startNextSegment();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start CameraX", e);
                    Util.logEvent("Camera failed to start: " + e.getMessage());
                    stopSelf();
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        int res = Util.getVideoResolution();
        Quality quality;
        if (res >= 2160) {
            quality = Quality.UHD;
        } else if (res >= 1080) {
            quality = Quality.FHD;
        } else {
            quality = Quality.HD;
        }
        QualitySelector qualitySelector = QualitySelector.from(
                quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));

        mRecorder = new Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build();
        mVideoCapture = VideoCapture.withOutput(mRecorder);

        mOverlayEffect = new OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                0,
                mGlHandler,
                throwable -> Log.e(TAG, "OverlayEffect error", throwable));
        mOverlayEffect.setOnDrawListener(new Function<Frame, Boolean>() {
            @Override
            public Boolean apply(Frame frame) {
                drawOverlay(frame);
                return true;
            }
        });

        UseCaseGroup.Builder groupBuilder = new UseCaseGroup.Builder()
                .addUseCase(mVideoCapture)
                .addEffect(mOverlayEffect);

        // Optional experimental placement check: attach a lightweight ImageAnalysis use case that
        // feeds the bonnet-mount heuristic. It's additive and never required for recording, so if
        // the device can't support this stream combination we fall back to recording without it.
        boolean mountCheck = Util.isMountCheckEnabled();
        if (mountCheck) {
            setupMountCheck();
            if (mImageAnalysis != null) {
                groupBuilder.addUseCase(mImageAnalysis);
            }
        }

        mCameraProvider.unbindAll();
        try {
            mCamera = mCameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, groupBuilder.build());
        } catch (Exception e) {
            // Most likely an unsupported use-case/stream combination when the analysis use case is
            // added. Retry with just recording + overlay so the dashcam keeps working.
            Log.w(TAG, "Bind with ImageAnalysis failed; retrying without placement check", e);
            teardownMountCheck();
            mCameraProvider.unbindAll();
            UseCaseGroup fallback = new UseCaseGroup.Builder()
                    .addUseCase(mVideoCapture)
                    .addEffect(mOverlayEffect)
                    .build();
            mCamera = mCameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, fallback);
        }
        // Reset so the first segment always applies the current night-mode state.
        mNightAppliedState = null;
        mStabilizationLogged = false;
    }

    // --- Experimental camera-placement (bonnet) check ---

    private void setupMountCheck() {
        try {
            if (mAnalysisExecutor == null) {
                mAnalysisExecutor = Executors.newSingleThreadExecutor();
            }
            mBonnetChecker = new BonnetMountChecker(this::onBonnetNotDetected);
            mImageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build();
            mImageAnalysis.setAnalyzer(mAnalysisExecutor, image -> {
                try {
                    BonnetMountChecker checker = mBonnetChecker;
                    if (checker != null && !checker.isDone()) {
                        checker.analyze(image, mSpeedKmh);
                        if (checker.isDone() && mImageAnalysis != null) {
                            // Stop analysing once a verdict is reached, to save power.
                            mImageAnalysis.clearAnalyzer();
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Bonnet analysis error", e);
                } finally {
                    image.close();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to set up placement check", e);
            mImageAnalysis = null;
            mBonnetChecker = null;
        }
    }

    private void teardownMountCheck() {
        try {
            if (mImageAnalysis != null) {
                mImageAnalysis.clearAnalyzer();
            }
        } catch (Exception ignored) {
        }
        mImageAnalysis = null;
        mBonnetChecker = null;
    }

    /** Invoked (off the main thread) when the heuristic concludes the bonnet isn't in frame. */
    private void onBonnetNotDetected() {
        if (mMountWarned) return;
        mMountWarned = true;
        Util.logEvent("Camera placement warning: bonnet not detected at bottom of frame");
        try {
            Util.showWarningNotification(this,
                    "Check camera placement",
                    "The car bonnet doesn't appear at the bottom of the video. Aim the camera a "
                            + "little lower so the bonnet is visible in the bottom of the frame.");
        } catch (Exception ignored) {
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.widget.Toast.makeText(getApplicationContext(),
                        "Check dashcam placement: bonnet not visible at bottom",
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Applies (or clears) the camera night scene mode based on the user's schedule. Re-evaluated
     * at each segment boundary so the schedule takes effect without restarting recording. Only
     * re-applies and logs when the desired state changes. Night scene mode is gated on the camera
     * actually advertising CONTROL_SCENE_MODE_NIGHT.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private void applyNightMode() {
        if (mCamera == null) {
            return;
        }
        boolean night = Util.isNightModeActiveNow();
        if (mNightAppliedState != null && mNightAppliedState == night) {
            return;
        }
        try {
            androidx.camera.camera2.interop.Camera2CameraInfo camInfo =
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(mCamera.getCameraInfo());
            int[] scenes = camInfo.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
            boolean nightSupported = false;
            if (scenes != null) {
                for (int s : scenes) {
                    if (s == android.hardware.camera2.CameraMetadata.CONTROL_SCENE_MODE_NIGHT) {
                        nightSupported = true;
                        break;
                    }
                }
            }

            androidx.camera.camera2.interop.Camera2CameraControl control =
                    androidx.camera.camera2.interop.Camera2CameraControl.from(mCamera.getCameraControl());
            androidx.camera.camera2.interop.CaptureRequestOptions.Builder builder =
                    new androidx.camera.camera2.interop.CaptureRequestOptions.Builder();

            if (night && nightSupported) {
                builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
                builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            } else {
                builder.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO);
            }

            // Electronic video stabilization (EIS), applied in the same capture-request set so it
            // persists alongside the night-mode options (setCaptureRequestOptions replaces the
            // whole set). Gated on the camera advertising a supported stabilization mode.
            applyVideoStabilization(camInfo, builder);

            control.setCaptureRequestOptions(builder.build());

            Log.i(TAG, "Night mode -> " + night + " (cameraSupportsNightScene=" + nightSupported + ")");
            if (night) {
                Util.logEvent(nightSupported
                        ? "Night mode engaged"
                        : "Night mode scheduled (camera does not support night scene)");
            } else if (mNightAppliedState != null) {
                Util.logEvent("Night mode disengaged");
            }
            mNightAppliedState = night;
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply night mode", e);
        }
    }

    /**
     * Requests electronic video stabilization (EIS) on the given capture-request builder when the
     * user setting is on and the camera advertises support. EIS smooths out road bumps at the cost
     * of a slightly tighter field of view. Unsupported cameras are left untouched (no-op).
     */
    @SuppressLint("UnsafeOptInUsageError")
    private void applyVideoStabilization(
            androidx.camera.camera2.interop.Camera2CameraInfo camInfo,
            androidx.camera.camera2.interop.CaptureRequestOptions.Builder builder) {
        try {
            boolean want = Util.isVideoStabilizationEnabled();
            int[] modes = camInfo.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics
                            .CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            boolean supported = false;
            if (modes != null) {
                for (int m : modes) {
                    if (m == android.hardware.camera2.CameraMetadata
                            .CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                        supported = true;
                        break;
                    }
                }
            }
            int value = (want && supported)
                    ? android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    : android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
            builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, value);

            if (!mStabilizationLogged) {
                Log.i(TAG, "Video stabilization -> " + (want && supported)
                        + " (requested=" + want + ", cameraSupportsEIS=" + supported + ")");
                Util.logEvent(want
                        ? (supported ? "Video stabilization engaged"
                                     : "Video stabilization requested (camera does not support EIS)")
                        : "Video stabilization off");
                mStabilizationLogged = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply video stabilization", e);
        }
    }

    // --- Loop recording (segment chaining) ---

    @SuppressLint("MissingPermission")
    private void startNextSegment() {
        if (mStopping || mRecorder == null) return;

        // Re-evaluate the night-mode schedule at each segment boundary.
        applyNightMode();

        // Housekeeping before each segment
        rotateRecordings(BackgroundVideoRecorder.this, Util.getQuota());
        organizeRecordings();

        // Track previous/current filenames for the "Save recording" feature
        editor.putString(getString(R.string.previous_recording_preferences_key), currentVideoFile);
        editor.apply();

        currentVideoFile = mRecordingsDirectory.getAbsolutePath() + File.separator
                + DateFormat.format("yyyy-MM-dd_kk-mm-ss", new Date().getTime()) + ".mp4";

        editor.putString(getString(R.string.current_recording_preferences_key), currentVideoFile);
        editor.apply();

        File outFile = new File(currentVideoFile);
        FileOutputOptions.Builder outputOptionsBuilder = new FileOutputOptions.Builder(outFile)
                .setDurationLimitMillis(Util.getMaxDuration());
        // Embed the last known GPS fix as clip metadata so players/details views can show
        // where the clip was recorded (in addition to the burned-in overlay).
        Location lastLocation = mLastLocation;
        if (lastLocation != null) {
            outputOptionsBuilder.setLocation(lastLocation);
        }
        FileOutputOptions outputOptions = outputOptionsBuilder.build();

        PendingRecording pending = mRecorder.prepareRecording(this, outputOptions);
        boolean audio = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (audio) {
            try {
                pending = pending.withAudioEnabled();
            } catch (Exception ignored) {
                // Fall back to video-only if audio can't be enabled
            }
        }

        Util.logEvent("New clip: " + outFile.getName());

        mActiveRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Finalize) {
                onSegmentFinalized((VideoRecordEvent.Finalize) event);
            }
        });
    }

    private void onSegmentFinalized(VideoRecordEvent.Finalize event) {
        // A duration-limit finalize still produces a valid file
        Util.insertNewRecording(new app.sentry.models.Recording(currentVideoFile));

        if (!mStopping) {
            startNextSegment();
        }
    }

    // --- Burned-in overlay ---

    private void setupOverlayPaints() {
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        mBgPaint.setColor(Color.argb(120, 0, 0, 0));
    }

    /**
     * Draws the timestamp and GPS location onto the frame.
     *
     * The overlay must be drawn in the camera **sensor coordinate system** and mapped to the
     * output buffer with {@link Frame#getSensorToBufferTransform()}. That transform (plus the
     * video's rotation metadata) is what keeps both the scene and the overlay upright in the final
     * video, regardless of device/mount orientation. (Manually rotating the canvas double-applies
     * the rotation and makes the text upside-down.)
     */
    private void drawOverlay(Frame frame) {
        Canvas canvas = frame.getOverlayCanvas();
        // Clear the whole canvas first (CLEAR ignores the current matrix)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        Matrix sensorToBuffer = frame.getSensorToBufferTransform();

        // Recover the sensor-space bounds by inverse-mapping the buffer rectangle, so we can
        // position the text along the top edge of the (upright) sensor image.
        RectF sensorRect = new RectF(0, 0, frame.getSize().getWidth(), frame.getSize().getHeight());
        Matrix bufferToSensor = new Matrix();
        if (sensorToBuffer.invert(bufferToSensor)) {
            bufferToSensor.mapRect(sensorRect);
        }

        // Draw in sensor coordinates
        canvas.setMatrix(sensorToBuffer);

        String line1 = DateFormat.format("yyyy-MM-dd  HH:mm:ss", new Date()).toString();
        String line2 = mHasLocation
                ? String.format(Locale.US, "%.5f, %.5f   %.0f km/h", mLat, mLng, mSpeedKmh)
                : "GPS: acquiring\u2026";

        float textSize = Math.max(24f, sensorRect.height() * 0.03f);
        mTextPaint.setTextSize(textSize);

        float pad = textSize * 0.5f;
        float lineGap = textSize * 0.35f;
        float stripHeight = textSize * 2 + lineGap + pad * 2;

        // Background strip along the top of the upright sensor image
        canvas.drawRect(sensorRect.left, sensorRect.top,
                sensorRect.right, sensorRect.top + stripHeight, mBgPaint);

        float x = sensorRect.left + pad;
        float baseline1 = sensorRect.top + pad + textSize;
        float baseline2 = baseline1 + textSize + lineGap;
        canvas.drawText(line1, x, baseline1, mTextPaint);
        canvas.drawText(line2, x, baseline2, mTextPaint);
    }

    // --- Location for the overlay ---

    // Poll location roughly once a minute (GPS primary; NETWORK as a fallback which uses
    // wifi/mobile data transparently via the OS).
    private static final long LOCATION_POLL_MS = 60_000L;

    private void startLocationUpdates() {
        try {
            boolean fine = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean coarse = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) return;

            mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (mLocationManager == null) return;

            // Seed with the last known location so the overlay isn't blank initially
            for (String provider : mLocationManager.getProviders(true)) {
                Location last = mLocationManager.getLastKnownLocation(provider);
                if (last != null) {
                    mLocationListener.onLocationChanged(last);
                }
            }

            // GPS is the primary source; NETWORK (wifi/mobile) is a fallback for when GPS has no fix.
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (mLocationManager.isProviderEnabled(provider)) {
                        mLocationManager.requestLocationUpdates(
                                provider, LOCATION_POLL_MS, 0, mLocationListener, Looper.getMainLooper());
                    }
                } catch (Exception ignored) {
                    // Provider may be unavailable; ignore
                }
            }
        } catch (Exception e) {
            // Location entirely unavailable; overlay will show "acquiring"
        }
    }

    private void stopLocationUpdates() {
        try {
            if (mLocationManager != null) mLocationManager.removeUpdates(mLocationListener);
        } catch (Exception ignored) {
        }
    }

    // --- Teardown ---

    @Override
    public void onDestroy() {
        mStopping = true;
        isRecording = false;
        Util.logEvent("Recording stopped");

        stopLocationUpdates();

        try {
            if (mActiveRecording != null) {
                mActiveRecording.stop();
                mActiveRecording = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
        }

        try {
            if (mCameraProvider != null) mCameraProvider.unbindAll();
        } catch (Exception ignored) {
        }

        if (mOverlayEffect != null) {
            try {
                mOverlayEffect.close();
            } catch (Exception ignored) {
            }
            mOverlayEffect = null;
        }

        teardownMountCheck();
        if (mAnalysisExecutor != null) {
            mAnalysisExecutor.shutdown();
            mAnalysisExecutor = null;
        }

        if (mGlThread != null) {
            mGlThread.quitSafely();
            mGlThread = null;
            mGlHandler = null;
        }

        reEnableSound();

        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    // --- Storage housekeeping (unchanged behavior) ---

    private void rotateRecordings(Context context, int quota) {
        long startTime = System.currentTimeMillis();
        while (Util.getFolderSize(mRecordingsDirectory) >= quota) {
            List<File> videoFiles = new ArrayList<>();
            collectVideoFiles(mRecordingsDirectory, videoFiles);

            File oldestFile = null;
            int starred_videos_total_size = 0;

            for (File fileInDirectory : videoFiles) {
                app.sentry.models.Recording recording =
                        new app.sentry.models.Recording(fileInDirectory.getAbsolutePath());
                if (recording.isStarred()) {
                    starred_videos_total_size += fileInDirectory.length() / (1024 * 1024);
                    continue;
                }
                if (oldestFile == null || oldestFile.lastModified() > fileInDirectory.lastModified()) {
                    oldestFile = fileInDirectory;
                }
            }

            if ((quota - starred_videos_total_size) < Util.getQuotaWarningThreshold()) {
                Util.showToastLong(context.getApplicationContext(),
                        "WARNING: Low on space quota.\nUn-star videos to free up space.");
            }

            if (oldestFile == null) break;

            Util.deleteSingleRecording(new app.sentry.models.Recording(oldestFile.getAbsolutePath()));
        }

        pruneEmptyDirectories(mRecordingsDirectory);

        long elapsedTime = System.currentTimeMillis() - startTime;
        Log.d("DEBUG", "rotateRecordings Time: "
                + TimeUnit.MILLISECONDS.convert(elapsedTime, TimeUnit.MILLISECONDS) + " milliseconds");
    }

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
            if (f.isDirectory()) continue;
            if (!f.getName().endsWith(".mp4")) continue;
            if (f.getAbsolutePath().equals(currentVideoFile)) continue;

            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(f.lastModified());

            boolean sameHour = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
                    && c.get(Calendar.HOUR_OF_DAY) == now.get(Calendar.HOUR_OF_DAY);
            if (sameHour) continue;

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

    // --- System sound muting (unchanged behavior) ---

    private void disableSound(SharedPreferences.Editor editor) {
        if (settings.getBoolean("disable_sound", true)) {
            AudioManager audio = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            int volume = audio.getStreamVolume(AudioManager.STREAM_SYSTEM);
            editor.putInt(getString(R.string.pre_start_volume), volume);
            editor.apply();
            if (volume > 0) {
                audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
            }
        }
    }

    private void reEnableSound() {
        AudioManager audio = (AudioManager) getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        int volume = sharedPref.getInt(getString(R.string.pre_start_volume), 0);
        if (volume > 0) {
            audio.setStreamVolume(AudioManager.STREAM_SYSTEM, volume, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
        }
    }
}
