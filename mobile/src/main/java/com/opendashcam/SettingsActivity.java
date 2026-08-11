package com.opendashcam;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.DynamicRange;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.Quality;
import androidx.camera.video.Recorder;
import androidx.core.content.ContextCompat;

import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.Locale;

/**
 * Card-based settings screen. Reads/writes the default SharedPreferences (the same file that
 * {@link Util} reads), so numeric options are stored as strings to match Util's parsing.
 * Segmented option rows are built as simple clickable views for reliable selection.
 */
public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    /** Callback for a segmented control selection. */
    private interface OnSegmentSelected {
        void onSelected(int value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("Dashcam Settings");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        setupClipLength();
        setupResolution();
        setupNightMode();
        setupStorage();
        setupSafety();
        setupGeneral();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupClipLength() {
        buildSegmented(findViewById(R.id.group_clip),
                new String[]{"1 min", "3 min", "5 min", "10 min"},
                new int[]{60, 180, 300, 600},
                Util.getMaxDuration() / 1000,
                value -> prefs.edit().putString("clip_duration_sec", String.valueOf(value)).apply());
    }

    private void setupResolution() {
        // Build with the always-available options first, then asynchronously upgrade to include
        // 4K if the back camera actually supports UHD video (queried through CameraX, which is
        // more reliable than the legacy CamcorderProfile API on some vendor ROMs).
        buildResolutionRow(false);

        final ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                CameraInfo info = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA);
                if (info != null && Recorder.getVideoCapabilities(info)
                        .getSupportedQualities(DynamicRange.SDR).contains(Quality.UHD)) {
                    buildResolutionRow(true);
                }
            } catch (Exception e) {
                // Leave the 720p/1080p row as-is if detection fails.
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void buildResolutionRow(boolean includeUhd) {
        String[] labels = includeUhd
                ? new String[]{"720p", "1080p", "4K"}
                : new String[]{"720p", "1080p"};
        int[] values = includeUhd
                ? new int[]{720, 1080, 2160}
                : new int[]{720, 1080};
        buildSegmented(findViewById(R.id.group_res), labels, values,
                Util.getVideoResolution(),
                value -> prefs.edit().putString("video_resolution", String.valueOf(value)).apply());
    }

    private void setupNightMode() {
        SwitchMaterial night = findViewById(R.id.switch_night);
        night.setChecked(Util.isNightModeEnabled());
        night.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("enable_night_mode", checked).apply());

        final Slider startSlider = findViewById(R.id.night_start_slider);
        final TextView startValue = findViewById(R.id.night_start_value);
        startSlider.setValue(clampHour(Util.getNightStartHour()));
        startValue.setText(formatHour(Util.getNightStartHour()));
        startSlider.addOnChangeListener((s, v, fromUser) -> {
            int hour = (int) v;
            startValue.setText(formatHour(hour));
            prefs.edit().putString("night_start_hour", String.valueOf(hour)).apply();
        });

        final Slider endSlider = findViewById(R.id.night_end_slider);
        final TextView endValue = findViewById(R.id.night_end_value);
        endSlider.setValue(clampHour(Util.getNightEndHour()));
        endValue.setText(formatHour(Util.getNightEndHour()));
        endSlider.addOnChangeListener((s, v, fromUser) -> {
            int hour = (int) v;
            endValue.setText(formatHour(hour));
            prefs.edit().putString("night_end_hour", String.valueOf(hour)).apply();
        });
    }

    private static int clampHour(int hour) {
        return Math.max(0, Math.min(23, hour));
    }

    private static String formatHour(int hour) {
        return String.format(Locale.US, "%02d:00", clampHour(hour));
    }

    private void setupStorage() {
        final Slider slider = findViewById(R.id.storage_slider);
        final TextView value = findViewById(R.id.storage_value);
        final TextView free = findViewById(R.id.storage_free);

        int gb = Math.max(1, Math.min(32, Math.round(Util.getQuota() / 1024f)));
        slider.setValue(gb);
        value.setText(gb + " GB");

        File dir = Util.getVideosDirectoryPath();
        long freeMb = Util.getFreeSpaceExternalStorage(dir);
        free.setText(String.format(Locale.US,
                "Maximum space for the rolling buffer \u00B7 Device free: %.1f GB", freeMb / 1024f));

        slider.addOnChangeListener((s, v, fromUser) -> {
            int selectedGb = (int) v;
            value.setText(selectedGb + " GB");
            prefs.edit().putString("storage_quota_mb", String.valueOf(selectedGb * 1024)).apply();
        });
    }

    private void setupSafety() {
        SwitchMaterial overheat = findViewById(R.id.switch_overheat);
        overheat.setChecked(Util.isOverheatAlertEnabled());
        overheat.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("enable_overheat_alert", checked).apply());

        buildSegmented(findViewById(R.id.group_overheat),
                new String[]{"40\u00B0C", "45\u00B0C", "50\u00B0C", "55\u00B0C"},
                new int[]{40, 45, 50, 55},
                Util.getOverheatThreshold(),
                value -> prefs.edit().putString("overheat_threshold_c", String.valueOf(value)).apply());

        SwitchMaterial lowbatt = findViewById(R.id.switch_lowbatt);
        lowbatt.setChecked(Util.isLowBatteryShutdownEnabled());
        lowbatt.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("enable_low_battery_shutdown", checked).apply());

        buildSegmented(findViewById(R.id.group_lowbatt),
                new String[]{"5%", "10%", "15%", "20%", "25%"},
                new int[]{5, 10, 15, 20, 25},
                Util.getLowBatteryThreshold(),
                value -> prefs.edit().putString("low_battery_threshold_pct", String.valueOf(value)).apply());
    }

    private void setupGeneral() {
        SwitchMaterial autostart = findViewById(R.id.switch_autostart);
        autostart.setChecked(prefs.getBoolean("auto_start_on_charge", false));
        autostart.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("auto_start_on_charge", checked).apply());

        SwitchMaterial autostop = findViewById(R.id.switch_autostop);
        autostop.setChecked(prefs.getBoolean("auto_stop_on_discharge", false));
        autostop.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("auto_stop_on_discharge", checked).apply());

        SwitchMaterial silent = findViewById(R.id.switch_silent);
        silent.setChecked(prefs.getBoolean("disable_sound", true));
        silent.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("disable_sound", checked).apply());

        SwitchMaterial maps = findViewById(R.id.switch_maps);
        maps.setChecked(prefs.getBoolean("start_maps_in_background", true));
        maps.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean("start_maps_in_background", checked).apply());

        TextView deleteAll = findViewById(R.id.btn_delete_all);
        deleteAll.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete all recordings")
                .setMessage("Delete all recordings? This cannot be undone.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete all", (d, w) -> Util.deleteRecordings())
                .show());
    }

    /**
     * Builds a segmented single-select control inside the given horizontal container. The selected
     * segment is highlighted; tapping a segment selects it and invokes the callback.
     */
    private void buildSegmented(LinearLayout container, String[] labels, int[] values,
                                int currentValue, final OnSegmentSelected callback) {
        container.removeAllViews();
        final int spacing = dp(4);
        final int height = dp(42);

        for (int i = 0; i < labels.length; i++) {
            final int value = values[i];
            TextView seg = new TextView(this);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
            lp.leftMargin = (i == 0) ? 0 : spacing;
            seg.setLayoutParams(lp);
            seg.setGravity(Gravity.CENTER);
            seg.setText(labels[i]);
            seg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            seg.setClickable(true);
            seg.setFocusable(true);

            styleSegment(seg, value == currentValue);

            seg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LinearLayout parent = (LinearLayout) v.getParent();
                    for (int j = 0; j < parent.getChildCount(); j++) {
                        View child = parent.getChildAt(j);
                        styleSegment((TextView) child, child == v);
                    }
                    callback.onSelected(value);
                }
            });

            container.addView(seg);
        }
    }

    private void styleSegment(TextView seg, boolean selected) {
        seg.setBackgroundResource(selected ? R.drawable.seg_selected : R.drawable.seg_unselected);
        seg.setTextColor(selected
                ? 0xFFFFFFFF
                : getResources().getColor(R.color.segUnselText));
        seg.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
