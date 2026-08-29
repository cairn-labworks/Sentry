package app.sentry;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home screen. Shows a big REC button, storage usage, and shortcuts to Recordings and Settings.
 * Recording is NOT started automatically on launch; the user starts it from the REC button or a
 * pinned "Start Recording" home-screen shortcut.
 */
public class MainActivity extends AppCompatActivity {

    /** When present and true, the activity immediately attempts to start recording (used by the shortcut). */
    public static final String EXTRA_START_RECORDING = "app.sentry.START_RECORDING";

    public static final int MULTIPLE_PERMISSIONS_RESPONSE_CODE = 10;
    private static final int CODE_REQUEST_PERMISSION_TO_MUTE_SYSTEM_SOUND = 10001;
    private static final int CODE_REQUEST_PERMISSION_DRAW_OVER_APPS = 10002;

    /** True while a start-recording flow is waiting on a permission result. */
    private boolean mPendingStart = false;

    private TextView mStorageText, mStoragePercent, mRecSubtitle, mRecTimer;
    private TextView mParkingState;
    private View mParkingCard;
    private ImageView mParkingIcon;
    private android.animation.ObjectAnimator mParkingGlow;
    private ImageView mRecIcon;

    private final Handler mTimerHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTimerTick = new Runnable() {
        @Override
        public void run() {
            if (!Util.isRecording()) {
                // Recording was stopped elsewhere (e.g. the overlay widget's "Stop"
                // button). Revert the whole home screen to its idle state and stop ticking.
                updateUi();
                return;
            }
            updateRecTimer();
            mTimerHandler.postDelayed(this, 1000L);
        }
    };

    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return perms.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First launch => show the tutorial, then return here
        SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                getString(R.string.db_first_launch_complete_flag), Context.MODE_PRIVATE);
        String firstLaunchFlag = sharedPref.getString(
                getString(R.string.db_first_launch_complete_flag), "null");
        if (TextUtils.isEmpty(firstLaunchFlag)) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        bindViews();

        // If launched from the shortcut, start recording right away
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_START_RECORDING, false)) {
            attemptStartRecording();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_START_RECORDING, false)) {
            attemptStartRecording();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRecTimer();
        stopParkingGlow();
    }

    private void bindViews() {
        mStorageText = findViewById(R.id.storage_text);
        mStoragePercent = findViewById(R.id.storage_percent);
        mRecSubtitle = findViewById(R.id.rec_subtitle);
        mRecTimer = findViewById(R.id.rec_timer);
        mRecIcon = findViewById(R.id.rec_icon);

        findViewById(R.id.btn_rec).setOnClickListener(v -> onRecClicked());
        ImageView themeBtn = findViewById(R.id.btn_settings);
        themeBtn.setContentDescription("Switch theme");
        themeBtn.setOnClickListener(v -> cycleTheme());
        updateThemeIcon();
        findViewById(R.id.card_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.card_recordings).setOnClickListener(v ->
                startActivity(new Intent(this, ViewRecordingsActivity.class)));

        mParkingState = findViewById(R.id.txt_parking_state);
        mParkingCard = findViewById(R.id.card_parking);
        mParkingIcon = findViewById(R.id.img_parking);
        findViewById(R.id.card_live).setOnClickListener(v -> openLiveView());
        mParkingCard.setOnClickListener(v -> toggleParkingMode());
        updateParkingUi();
    }

    /** Opens the live camera view. If not recording, prompts the user to start first. */
    private void openLiveView() {
        if (!Util.isRecording()) {
            Toast.makeText(this, "Start recording to see the live view", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, LiveViewActivity.class));
    }

    /** Toggles Parking Mode and reflects the new state in the card. */
    private void toggleParkingMode() {
        boolean enabled = !Util.isParkingModeEnabled();
        Util.setParkingMode(enabled);
        Util.logEvent("Parking mode " + (enabled ? "enabled" : "disabled"));
        Toast.makeText(this, "Parking mode " + (enabled ? "on" : "off"), Toast.LENGTH_SHORT).show();
        updateParkingUi();
    }

    /** Reflects the current Parking Mode state on its home-screen card. */
    private void updateParkingUi() {
        if (mParkingState == null) return;
        boolean enabled = Util.isParkingModeEnabled();
        mParkingState.setText(enabled ? "On" : "Off");
        mParkingState.setTextColor(getColor(enabled
                ? R.color.colorParkingActive : R.color.colorTextSecondary));

        if (mParkingCard != null) {
            mParkingCard.setBackgroundResource(enabled
                    ? R.drawable.bg_parking_active : R.drawable.bg_storage_card);
        }
        if (mParkingIcon != null) {
            if (enabled) {
                mParkingIcon.setColorFilter(getColor(R.color.colorParkingActive));
            } else {
                mParkingIcon.clearColorFilter();
            }
        }
        if (enabled) {
            startParkingGlow();
        } else {
            stopParkingGlow();
        }
    }

    /** Starts a slow yellow blink/glow pulse on the Parking card. */
    private void startParkingGlow() {
        if (mParkingCard == null || mParkingGlow != null) return;
        mParkingGlow = android.animation.ObjectAnimator.ofFloat(mParkingCard, "alpha", 1f, 0.4f);
        mParkingGlow.setDuration(700);
        mParkingGlow.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        mParkingGlow.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mParkingGlow.start();
    }

    /** Stops the Parking card glow pulse and restores full opacity. */
    private void stopParkingGlow() {
        if (mParkingGlow != null) {
            mParkingGlow.cancel();
            mParkingGlow = null;
        }
        if (mParkingCard != null) {
            mParkingCard.setAlpha(1f);
        }
    }

    /** Cycles the app theme System -> Light -> Dark -> System and applies it immediately. */
    private void cycleTheme() {
        String current = Util.getThemeMode();
        String next;
        String label;
        switch (current) {
            case Util.THEME_SYSTEM:
                next = Util.THEME_LIGHT;
                label = "Light";
                break;
            case Util.THEME_LIGHT:
                next = Util.THEME_DARK;
                label = "Dark";
                break;
            default:
                next = Util.THEME_SYSTEM;
                label = "System default";
                break;
        }
        Toast.makeText(this, "Theme: " + label, Toast.LENGTH_SHORT).show();
        // Applies the mode; if it changes the effective night setting the activity recreates.
        Util.setThemeMode(next);
        updateThemeIcon();
    }

    /** Sets the top-right icon to reflect the current theme mode. */
    private void updateThemeIcon() {
        ImageView themeBtn = findViewById(R.id.btn_settings);
        int icon;
        switch (Util.getThemeMode()) {
            case Util.THEME_LIGHT:
                icon = R.drawable.ic_theme_light;
                break;
            case Util.THEME_DARK:
                icon = R.drawable.ic_theme_dark;
                break;
            default:
                icon = R.drawable.ic_theme_system;
                break;
        }
        themeBtn.setImageResource(icon);
    }

    private void onRecClicked() {
        if (Util.isRecording()) {
            Util.stopRecordingServices(this);
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
            // Reflect the change after services wind down
            findViewById(R.id.rec_subtitle).postDelayed(this::updateUi, 600);
        } else {
            attemptStartRecording();
        }
    }

    private void updateUi() {
        // Storage bar
        File dir = Util.getVideosDirectoryPath();
        long usedMb = Util.getFolderSize(dir);
        long quotaMb = Util.getQuota();
        mStorageText.setText(String.format(Locale.US, "%.1f GB / %.0f GB",
                usedMb / 1024f, quotaMb / 1024f));
        int pct = quotaMb > 0 ? (int) Math.min(100, usedMb * 100 / quotaMb) : 0;
        mStoragePercent.setText(pct + "%");

        // REC state
        boolean recording = Util.isRecording();
        mRecSubtitle.setText(recording
                ? "Recording in progress. Tap to stop."
                : "Mount securely. Tap to start.");
        mRecIcon.setImageResource(recording
                ? R.drawable.ic_rec_active
                : R.drawable.ic_rec_camera);
        mRecIcon.setContentDescription(recording ? "Stop recording" : "Start recording");

        if (recording) {
            mRecTimer.setVisibility(View.VISIBLE);
            startRecTimer();
        } else {
            stopRecTimer();
            mRecTimer.setVisibility(View.GONE);
        }

        updateParkingUi();
    }

    /** Formats the elapsed recording time and starts the once-a-second ticker. */
    private void startRecTimer() {
        updateRecTimer();
        mTimerHandler.removeCallbacks(mTimerTick);
        mTimerHandler.postDelayed(mTimerTick, 1000L);
    }

    private void stopRecTimer() {
        mTimerHandler.removeCallbacks(mTimerTick);
    }

    /** Updates the elapsed-time label from the recorder's start timestamp. */
    private void updateRecTimer() {
        if (!Util.isRecording()) {
            return;
        }
        long startedAt = BackgroundVideoRecorder.recordingStartedAt;
        long elapsedMs = startedAt > 0 ? Math.max(0, System.currentTimeMillis() - startedAt) : 0;
        long totalSec = elapsedMs / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        mRecTimer.setText(String.format(Locale.US, "%02d:%02d:%02d", h, m, s));
    }

    // --- Start-recording flow (with permission gating) ---

    private void attemptStartRecording() {
        if (Util.isRecording()) {
            Toast.makeText(this, "Already recording", Toast.LENGTH_SHORT).show();
            return;
        }
        mPendingStart = true;

        if (!checkDrawPermission()) return;            // async -> onActivityResult
        if (!checkPermissionToMuteSystemSound()) return; // async -> onActivityResult
        if (!checkPermissions()) return;               // async -> onRequestPermissionsResult

        doStartRecording();
    }

    private void doStartRecording() {
        mPendingStart = false;

        if (!isEnoughStorage()) {
            Util.showToastLong(getApplicationContext(),
                    "Not enough storage to run the app (Need " + Util.getQuota()
                            + "MB). Clean up space for recordings.");
            return;
        }

        Util.startRecordingServices(this);
        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        // Keep the home screen open and reflect the recording state (icon + timer). The overlay
        // widget still launches on top; the user can return to this screen anytime.
        mRecSubtitle.postDelayed(this::updateUi, 300);
    }

    // --- Permission helpers ---

    private boolean checkDrawPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, CODE_REQUEST_PERMISSION_DRAW_OVER_APPS);
                Toast.makeText(this, "Please allow \"Display over other apps\", then come back and tap Start.",
                        Toast.LENGTH_LONG).show();
                return false;
            }
        }
        return true;
    }

    private boolean checkPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : getRequiredPermissions()) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }
        if (!listPermissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    listPermissionsNeeded.toArray(new String[0]),
                    MULTIPLE_PERMISSIONS_RESPONSE_CODE);
            return false;
        }
        return true;
    }

    private boolean checkPermissionToMuteSystemSound() {
        if (!isPermissionToMuteSystemSoundGranted()) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivityForResult(intent, CODE_REQUEST_PERMISSION_TO_MUTE_SYSTEM_SOUND);
            return false;
        }
        return true;
    }

    private boolean isPermissionToMuteSystemSoundGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true;
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return true;
        return notificationManager.isNotificationPolicyAccessGranted();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case CODE_REQUEST_PERMISSION_TO_MUTE_SYSTEM_SOUND:
            case CODE_REQUEST_PERMISSION_DRAW_OVER_APPS:
                // Continue the start flow if the user was in the middle of starting
                if (mPendingStart) attemptStartRecording();
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == MULTIPLE_PERMISSIONS_RESPONSE_CODE) {
            boolean essentialGranted = true;
            for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                if (!granted
                        && (Manifest.permission.CAMERA.equals(permissions[i])
                        || Manifest.permission.RECORD_AUDIO.equals(permissions[i]))) {
                    essentialGranted = false;
                }
            }
            if (essentialGranted) {
                if (mPendingStart) attemptStartRecording();
            } else {
                mPendingStart = false;
                Toast.makeText(this,
                        "Camera and microphone permissions are required to record.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isEnoughStorage() {
        File videosFolder = Util.getVideosDirectoryPath();
        if (videosFolder == null) return false;
        long appVideosFolderSize = Util.getFolderSize(videosFolder);
        long storageFreeSize = Util.getFreeSpaceExternalStorage(videosFolder);
        return storageFreeSize + appVideosFolderSize >= Util.getQuota();
    }
}
