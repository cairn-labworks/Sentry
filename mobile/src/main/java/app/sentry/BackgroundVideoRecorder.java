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
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.view.OrientationEventListener;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
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

    // Live preview: an always-bound Preview use case whose SurfaceProvider is attached only while
    // the Live view screen is open (same process). Lets the user watch the ongoing recording.
    private Preview mPreview;
    private static volatile Preview.SurfaceProvider sPreviewSurfaceProvider;
    private static volatile BackgroundVideoRecorder sInstance;

    // Device physical orientation (Surface.ROTATION_*), tracked so recordings and the burned-in
    // overlay stay upright whether the phone is mounted portrait or portrait-inverted (180).
    private OrientationEventListener mOrientationListener;
    private volatile int mTargetRotation = Surface.ROTATION_0;

    // Experimental offline camera-placement (bonnet) check.
    private ImageAnalysis mImageAnalysis;
    private BonnetMountChecker mBonnetChecker;
    private ExecutorService mAnalysisExecutor;
    private volatile boolean mMountWarned = false;

    // Tracks the currently-applied night-mode state so we only re-apply/log on transitions.
    private Boolean mNightAppliedState = null;
    // Log the stabilization decision only once per recording session.
    private boolean mStabilizationLogged = false;
    // Log the overlay rotation once per bind, for verification.
    private boolean mOverlayRotationLogged = false;

    private HandlerThread mGlThread;
    private Handler mGlHandler;

    private volatile boolean mStopping = false;

    // Cached location for the burned-in overlay (updated by the location listener)
    private LocationManager mLocationManager;
    private volatile boolean mHasLocation = false;
    private volatile double mLat = 0, mLng = 0;
    private volatile float mSpeedKmh = 0;
    private volatile Location mLastLocation = null;

    // Auto-pause when parked (offline motion detection via the accelerometer + GPS speed).
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private volatile long mLastMotionAt = 0;
    private volatile boolean mAutoPaused = false;
    private float mAccelEma = 0f;
    private boolean mAccelEmaInit = false;
    // Motion is detected when accelerometer jitter exceeds this (m/s^2) or GPS speed exceeds
    // SPEED_MOTION_KMH. Engine vibration/driving comfortably exceed these; a truly parked (engine
    // off) phone sits well below.
    private static final float ACCEL_MOTION_THRESHOLD = 0.30f;
    private static final float SPEED_MOTION_KMH = 3f;

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        startMotionDetection();
        startOrientationTracking();

        sInstance = this;

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
        // Record upright regardless of how the phone is mounted (portrait or portrait-inverted).
        mVideoCapture.setTargetRotation(mTargetRotation);

        // Live preview use case. Its SurfaceProvider is only attached while the Live view screen
        // is open; when null, Preview simply renders nothing.
        mPreview = new Preview.Builder()
                .setTargetRotation(mTargetRotation)
                .build();
        mPreview.setSurfaceProvider(sPreviewSurfaceProvider);

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
                .addUseCase(mPreview)
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
            // added. Retry with just recording + preview + overlay so the dashcam keeps working.
            Log.w(TAG, "Bind with ImageAnalysis failed; retrying without placement check", e);
            teardownMountCheck();
            mCameraProvider.unbindAll();
            UseCaseGroup fallback = new UseCaseGroup.Builder()
                    .addUseCase(mVideoCapture)
                    .addUseCase(mPreview)
                    .addEffect(mOverlayEffect)
                    .build();
            mCamera = mCameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, fallback);
        }
        // Reset so the first segment always applies the current night-mode state.
        mNightAppliedState = null;
        mStabilizationLogged = false;
        mOverlayRotationLogged = false;
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
        if (mStopping || mRecorder == null || mAutoPaused) return;

        // Re-evaluate the night-mode schedule at each segment boundary.
        applyNightMode();

        // Lock in the current device orientation for this clip so it records upright. Updating only
        // at segment boundaries keeps the burned-in overlay (which reads the frame rotation) in sync
        // with the recorded video for the whole clip.
        if (mVideoCapture != null) {
            mVideoCapture.setTargetRotation(mTargetRotation);
        }

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

        // Don't chain the next clip while stopping or while auto-paused (parked).
        if (!mStopping && !mAutoPaused) {
            startNextSegment();
        }
    }

    // --- Burned-in overlay ---

    private void setupOverlayPaints() {
        // White fill with a soft shadow for a bit of extra separation from the scene.
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        // Black outline drawn behind the text so it stays legible over any background without
        // needing a solid/semi-transparent bar.
        mStrokePaint.setColor(Color.BLACK);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * Draws the timestamp and GPS location onto the frame, always upright and anchored to the
     * visual top-left of the final (upright) video.
     *
     * <p>The overlay is drawn in <b>buffer coordinates</b> (the canvas is backed by a surface of
     * {@link Frame#getSize()}), because {@link Frame#getCropRect()} and
     * {@link Frame#getRotationDegrees()} are expressed there. The pipeline crops to the crop rect
     * and then rotates clockwise by {@code rotationDegrees} to produce the upright output. We move
     * the origin to whichever buffer corner becomes the visual top-left after that rotation and
     * pre-rotate the canvas by {@code -rotationDegrees}, so both the text position and its glyphs
     * come out correct whether the phone is mounted portrait or portrait-inverted.
     */
    private void drawOverlay(Frame frame) {
        Canvas canvas = frame.getOverlayCanvas();
        // Clear the whole canvas first (CLEAR ignores the current matrix)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        // Work in buffer coordinates.
        canvas.setMatrix(new Matrix());

        Rect crop = frame.getCropRect();
        int rot = ((frame.getRotationDegrees() % 360) + 360) % 360;

        if (!mOverlayRotationLogged) {
            Log.i(TAG, "Overlay rotationDegrees=" + rot + " crop=" + crop
                    + " targetRotation=" + mTargetRotation);
            mOverlayRotationLogged = true;
        }

        // Buffer corner that becomes the visual top-left after the pipeline rotates CW by `rot`.
        float originX, originY;
        switch (rot) {
            case 90:  originX = crop.left;  originY = crop.bottom; break;
            case 180: originX = crop.right; originY = crop.bottom; break;
            case 270: originX = crop.right; originY = crop.top;    break;
            default:  originX = crop.left;  originY = crop.top;    break; // 0
        }
        canvas.translate(originX, originY);
        canvas.rotate(-rot);

        // Upright output dimensions (width/height swap for 90/270).
        boolean swap = (rot == 90 || rot == 270);
        float outW = swap ? crop.height() : crop.width();
        float outH = swap ? crop.width() : crop.height();

        String line1 = DateFormat.format("yyyy-MM-dd  HH:mm:ss", new Date()).toString();
        String line2 = mHasLocation
                ? String.format(Locale.US, "%.5f, %.5f   %.0f km/h", mLat, mLng, mSpeedKmh)
                : "GPS: acquiring\u2026";

        float textSize = Math.max(24f, outH * 0.03f);
        mTextPaint.setTextSize(textSize);
        mStrokePaint.setTextSize(textSize);
        mStrokePaint.setStrokeWidth(Math.max(2f, textSize * 0.12f));

        float pad = textSize * 0.5f;
        float lineGap = textSize * 0.35f;

        float x = pad;
        float baseline1 = pad + textSize;
        float baseline2 = baseline1 + textSize + lineGap;
        // Outline first, then white fill on top — text is burned directly into the frame with no
        // background bar.
        canvas.drawText(line1, x, baseline1, mStrokePaint);
        canvas.drawText(line2, x, baseline2, mStrokePaint);
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

    // --- Device orientation (keeps video + overlay upright) ---

    /**
     * Tracks the phone's physical orientation via the accelerometer/gyroscope and maps it to a
     * {@link Surface} rotation. Applied to the {@link VideoCapture} (and Preview) so recordings and
     * the burned-in overlay stay upright whether the phone is mounted portrait or portrait-inverted.
     */
    private void startOrientationTracking() {
        try {
            mOrientationListener = new OrientationEventListener(this) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                        return;
                    }
                    int rotation;
                    if (orientation >= 45 && orientation < 135) {
                        rotation = Surface.ROTATION_270;
                    } else if (orientation >= 135 && orientation < 225) {
                        rotation = Surface.ROTATION_180;
                    } else if (orientation >= 225 && orientation < 315) {
                        rotation = Surface.ROTATION_90;
                    } else {
                        rotation = Surface.ROTATION_0;
                    }
                    mTargetRotation = rotation;
                }
            };
            if (mOrientationListener.canDetectOrientation()) {
                mOrientationListener.enable();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start orientation tracking", e);
        }
    }

    private void stopOrientationTracking() {
        try {
            if (mOrientationListener != null) mOrientationListener.disable();
        } catch (Exception ignored) {
        }
    }

    // --- Live preview attach/detach (called by LiveViewActivity, same process) ---

    /**
     * Attaches a preview surface so the Live view screen can show the ongoing recording. Safe to
     * call whether or not the recorder is currently running; the provider is remembered and applied
     * on the next bind.
     */
    static void attachPreview(Preview.SurfaceProvider provider) {
        sPreviewSurfaceProvider = provider;
        final BackgroundVideoRecorder inst = sInstance;
        if (inst != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (inst.mPreview != null) inst.mPreview.setSurfaceProvider(provider);
                } catch (Exception e) {
                    Log.w(TAG, "attachPreview failed", e);
                }
            });
        }
    }

    /** Detaches the preview surface when the Live view screen closes. */
    static void detachPreview() {
        sPreviewSurfaceProvider = null;
        final BackgroundVideoRecorder inst = sInstance;
        if (inst != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (inst.mPreview != null) inst.mPreview.setSurfaceProvider(null);
                } catch (Exception e) {
                    Log.w(TAG, "detachPreview failed", e);
                }
            });
        }
    }

    // --- Auto-pause when parked (offline motion detection) ---

    /**
     * Registers a low-rate accelerometer listener used, together with GPS speed, to detect whether
     * the vehicle is moving. Runs whenever recording is active; it only acts on the "stationary"
     * signal when the user setting is enabled, but always resumes a previously auto-paused session
     * as soon as motion returns. Fully offline (no network).
     */
    private void startMotionDetection() {
        try {
            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager == null) return;
            mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (mAccelerometer == null) {
                Log.w(TAG, "No accelerometer; auto-pause disabled");
                return;
            }
            mLastMotionAt = System.currentTimeMillis();
            mSensorManager.registerListener(mMotionListener, mAccelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            Log.e(TAG, "Failed to start motion detection", e);
        }
    }

    private void stopMotionDetection() {
        try {
            if (mSensorManager != null) mSensorManager.unregisterListener(mMotionListener);
        } catch (Exception ignored) {
        }
    }

    private final SensorEventListener mMotionListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mStopping) return;

            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            double magnitude = Math.sqrt(x * x + y * y + z * z);

            if (!mAccelEmaInit) {
                mAccelEma = (float) magnitude;
                mAccelEmaInit = true;
                return;
            }
            float jitter = Math.abs((float) magnitude - mAccelEma);
            // Exponential moving average tracks the (gravity) baseline so we measure deviation.
            mAccelEma += 0.2f * ((float) magnitude - mAccelEma);

            boolean moving = jitter > ACCEL_MOTION_THRESHOLD || mSpeedKmh > SPEED_MOTION_KMH;
            long now = System.currentTimeMillis();

            // Parking mode keeps recording through stillness: never auto-pause, and resume if we
            // were paused before parking mode was turned on.
            boolean parking = Util.isParkingModeEnabled();

            // If auto-pause was turned off (or parking mode turned on) while we were paused, resume
            // so we don't get stuck.
            if (mAutoPaused && (parking || !Util.isAutoPauseStationaryEnabled())) {
                resumeFromStationary();
                return;
            }

            if (moving) {
                mLastMotionAt = now;
                if (mAutoPaused) {
                    resumeFromStationary();
                }
                return;
            }

            // Stationary: pause after the configured timeout, if enabled, not parking, and recording.
            if (!mAutoPaused && !parking && Util.isAutoPauseStationaryEnabled()) {
                long timeoutMs = Util.getStationaryTimeoutMinutes() * 60_000L;
                if (now - mLastMotionAt >= timeoutMs) {
                    pauseForStationary();
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    /** Stops writing clips (camera stays bound) because the vehicle has been parked. */
    private void pauseForStationary() {
        if (mAutoPaused || mStopping) return;
        mAutoPaused = true;
        Log.i(TAG, "Auto-pause: stationary for " + Util.getStationaryTimeoutMinutes() + " min");
        Util.logEvent("Auto-pause: stationary for "
                + Util.getStationaryTimeoutMinutes() + " min");
        try {
            if (mActiveRecording != null) {
                // Finalize the in-progress clip; onSegmentFinalized won't chain while paused.
                mActiveRecording.stop();
                mActiveRecording = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error pausing recording", e);
        }
    }

    /** Resumes clip recording once motion is detected again. */
    private void resumeFromStationary() {
        if (!mAutoPaused) return;
        mAutoPaused = false;
        mLastMotionAt = System.currentTimeMillis();
        Log.i(TAG, "Auto-resume: motion detected");
        Util.logEvent("Auto-resume: motion detected");
        if (!mStopping && mActiveRecording == null) {
            startNextSegment();
        }
    }

    // --- Teardown ---

    @Override
    public void onDestroy() {
        mStopping = true;
        isRecording = false;
        Util.logEvent("Recording stopped");

        stopLocationUpdates();
        stopMotionDetection();
        stopOrientationTracking();
        if (sInstance == this) {
            sInstance = null;
        }

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
