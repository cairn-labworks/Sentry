package app.sentry;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen live view of the ongoing recording. Mirrors the recorder's camera feed (via a shared
 * CameraX Preview use case in the same process) and overlays the current speed, resolution/FPS and
 * battery temperature, plus a REC indicator and a close button.
 */
public class LiveViewActivity extends AppCompatActivity implements LocationListener {

    private static final int REQ_LOCATION = 20001;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private LocationManager mLocationManager;

    private TextView mSpeed, mTemp, mDateTime;
    private PreviewView mPreview;
    private TextView mPreviewHint;
    private View mRecDot;
    private float mAppliedPreviewRotation = Float.NaN;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_live_view);
        enterImmersive();

        mSpeed = findViewById(R.id.txt_speed);
        mTemp = findViewById(R.id.txt_temp);
        mDateTime = findViewById(R.id.txt_datetime);
        mPreview = findViewById(R.id.preview);
        mPreviewHint = findViewById(R.id.txt_preview_hint);
        mRecDot = findViewById(R.id.rec_dot);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    /** Hides the status and navigation bars for a full-screen camera feed. */
    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
        startLocationIfPermitted();
        // Feed the ongoing recording's camera into our PreviewView (same process as the recorder).
        if (mPreview != null) {
            mPreview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
            BackgroundVideoRecorder.attachPreview(mPreview.getSurfaceProvider());
            mAppliedPreviewRotation = Float.NaN;
            mPreview.post(this::applyPreviewOrientation);
        }
    }

    /**
     * Keeps the live feed upright by telling the shared Preview use case which screen orientation it
     * is being displayed on. CameraX + PreviewView then apply the correct transform (accounting for
     * the camera sensor mounting) automatically. Re-evaluated each tick so it follows the display if
     * the activity is re-laid out. No manual view rotation is used.
     */
    private void applyPreviewOrientation() {
        if (mPreview == null) return;
        int dispRotation = (mPreview.getDisplay() != null
                ? mPreview.getDisplay().getRotation()
                : getWindowManager().getDefaultDisplay().getRotation());
        if (dispRotation != mAppliedPreviewRotation) {
            mAppliedPreviewRotation = dispRotation;
            BackgroundVideoRecorder.setPreviewTargetRotation(dispRotation);
        }
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTick);
        stopLocation();
        if (mPreview != null) {
            BackgroundVideoRecorder.detachPreview();
        }
        super.onPause();
    }

    // --- Telemetry refresh (once per second) ---

    private void refresh() {
        refreshTemp();
        refreshDateTime();
        refreshRecIndicator();
        applyPreviewOrientation();
    }

    private void refreshTemp() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;

        int tempTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (tempTenths != Integer.MIN_VALUE) {
            int tempC = Math.round(tempTenths / 10f);
            String label;
            int color;
            if (tempC < 35) {
                label = "Cool";
                color = 0xFF4CAF50;
            } else if (tempC < 42) {
                label = "Warm";
                color = 0xFFFFB300;
            } else {
                label = "Hot";
                color = 0xFFFF5252;
            }
            mTemp.setText(tempC + "\u00B0C \u00B7 " + label);
            mTemp.setTextColor(color);
        }
    }

    private final SimpleDateFormat mDateTimeFmt =
            new SimpleDateFormat("dd MMM \u00B7 HH:mm:ss", Locale.getDefault());

    private void refreshDateTime() {
        mDateTime.setText(mDateTimeFmt.format(new Date()));
    }

    private void refreshRecIndicator() {
        boolean recording = BackgroundVideoRecorder.isRecording;
        if (mPreviewHint != null) {
            mPreviewHint.setVisibility(recording ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        if (mRecDot != null) {
            mRecDot.setVisibility(recording ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    // --- Location (GPS + speed) ---

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationIfPermitted() {
        if (!hasLocationPermission()) {
            mSpeed.setText("-- km/h");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            if (mLocationManager != null) {
                mLocationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000, 0, this);
                mLocationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 1000, 0, this);
                Location last = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) onLocationChanged(last);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            mSpeed.setText("-- km/h");
        }
    }

    private void stopLocation() {
        try {
            if (mLocationManager != null) mLocationManager.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationIfPermitted();
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        float speedKmh = location.hasSpeed() ? location.getSpeed() * 3.6f : 0f;
        mSpeed.setText(String.format(Locale.US, "%.0f km/h", speedKmh));
    }

    // Required by LocationListener on older APIs; no-ops here.
    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }
}
