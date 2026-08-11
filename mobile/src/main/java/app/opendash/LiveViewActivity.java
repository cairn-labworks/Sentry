package app.opendash;

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
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Live status HUD for the ongoing recording. Shows clock/date, GPS + speed, battery %,
 * battery temperature, resolution/FPS, storage usage, recording state, and a rolling event log.
 * (The camera image itself is owned by the background recorder and is not mirrored here.)
 */
public class LiveViewActivity extends AppCompatActivity implements LocationListener {

    private static final int REQ_LOCATION = 20001;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private LocationManager mLocationManager;

    private TextView mClock, mDate, mBattery, mSpeed, mTemp, mResFps, mStorage, mGps, mRecStatus, mEventLog;

    private final SimpleDateFormat mClockFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final SimpleDateFormat mDateFmt = new SimpleDateFormat("EEE, d MMM yyyy", Locale.US);

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

        mClock = findViewById(R.id.txt_clock);
        mDate = findViewById(R.id.txt_date);
        mBattery = findViewById(R.id.txt_battery);
        mSpeed = findViewById(R.id.txt_speed);
        mTemp = findViewById(R.id.txt_temp);
        mResFps = findViewById(R.id.txt_resfps);
        mStorage = findViewById(R.id.txt_storage);
        mGps = findViewById(R.id.txt_gps);
        mRecStatus = findViewById(R.id.txt_recstatus);
        mEventLog = findViewById(R.id.txt_eventlog);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mTick);
        startLocationIfPermitted();
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTick);
        stopLocation();
        super.onPause();
    }

    // --- Telemetry refresh (once per second) ---

    private void refresh() {
        long now = System.currentTimeMillis();
        mClock.setText(mClockFmt.format(new Date(now)));
        mDate.setText(mDateFmt.format(new Date(now)));

        refreshBatteryAndTemp();
        refreshStorage();
        refreshResFps();
        refreshRecStatus(now);
        refreshEventLog();
    }

    private void refreshBatteryAndTemp() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int tempTenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);

        if (level >= 0 && scale > 0) {
            int pct = Math.round(level * 100f / scale);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            mBattery.setText(pct + "%" + (charging ? " \u26A1" : ""));
        }

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

    private void refreshStorage() {
        File root = Util.getVideosDirectoryPath();
        long usedMb = Util.getFolderSize(root);
        long quotaMb = Util.getQuota();
        mStorage.setText(String.format(Locale.US, "%.1f / %.1f GB", usedMb / 1024f, quotaMb / 1024f));
    }

    private void refreshResFps() {
        int res = Util.getVideoResolution();
        String label = (res >= 2160) ? "4K" : (res + "P");
        mResFps.setText(label + " \u00B7 30 FPS");
    }

    private void refreshRecStatus(long now) {
        if (BackgroundVideoRecorder.isRecording) {
            long elapsedMs = Math.max(0, now - BackgroundVideoRecorder.recordingStartedAt);
            long h = TimeUnit.MILLISECONDS.toHours(elapsedMs);
            long m = TimeUnit.MILLISECONDS.toMinutes(elapsedMs) % 60;
            long s = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60;

            String clip = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString(getString(R.string.current_recording_preferences_key), "");
            String clipName = TextUtils.isEmpty(clip) ? "" : "   \u00B7   " + new File(clip).getName();

            mRecStatus.setText(String.format(Locale.US, "\u25CF REC   %02d:%02d:%02d%s", h, m, s, clipName));
            mRecStatus.setTextColor(0xFFFF5252);
        } else {
            mRecStatus.setText("Not recording");
            mRecStatus.setTextColor(0xFF90A4AE);
        }
    }

    private void refreshEventLog() {
        List<String> events = Util.getEventLog();
        mEventLog.setText(events.isEmpty() ? "No events yet." : TextUtils.join("\n", events));
    }

    // --- Location (GPS + speed) ---

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationIfPermitted() {
        if (!hasLocationPermission()) {
            mGps.setText("Location permission needed for GPS & speed");
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
            mGps.setText("GPS unavailable");
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
        mGps.setText(String.format(Locale.US, "%.5f\u00B0, %.5f\u00B0",
                location.getLatitude(), location.getLongitude()));
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
