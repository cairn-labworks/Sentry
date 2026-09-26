package app.sentry.models;

import android.text.TextUtils;

import app.sentry.DBHelper;
import app.sentry.SentryApp;
import app.sentry.Util;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One captured dash-cam clip on disk, plus the small amount of catalogue state
 * (row id, starred flag) that the app tracks alongside the file.
 *
 * <p>Human-readable date and time are derived lazily from the file's
 * last-modified timestamp so the list can show when each clip was recorded.
 */
public class Recording {

    private static final SimpleDateFormat DAY_LABEL = new SimpleDateFormat("EEE MMM d", Locale.getDefault());
    private static final SimpleDateFormat CLOCK_LABEL = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final int id;
    private final String filePath;
    private final String fileName;
    private String dayLabel;
    private String clockLabel;

    /** Builds a clip loaded from a catalogue row. */
    public Recording(int id, String filePath) {
        this.id = id;
        this.filePath = filePath;
        this.fileName = filePath != null ? new File(filePath).getName() : "";
        computeLabels();
    }

    /** Builds a brand-new clip that has not been catalogued yet. */
    public Recording(String filePath) {
        this(-1, filePath);
    }

    public int getId() {
        return id;
    }

    public String getFilePath() {
        return TextUtils.isEmpty(filePath) ? "" : filePath;
    }

    public String getFileName() {
        return TextUtils.isEmpty(fileName) ? "" : fileName;
    }

    public String getDateSaved() {
        return dayLabel;
    }

    public String getTimeSaved() {
        return clockLabel;
    }

    /** Looks up the starred state in the catalogue. */
    public boolean isStarred() {
        return DBHelper.getInstance(SentryApp.getAppContext()).isRecordingStarred(this);
    }

    /**
     * Requests a star toggle. The catalogue write happens off the UI thread;
     * the row is refreshed once it completes.
     *
     * @param isChecked desired checkbox state (unused: the store toggles the
     *                  current value)
     * @return {@code true} — the request was accepted
     */
    public boolean toggleStar(boolean isChecked) {
        Util.updateStar(this);
        return true;
    }

    private void computeLabels() {
        if (!TextUtils.isEmpty(filePath)) {
            Date modified = new Date(new File(filePath).lastModified());
            dayLabel = DAY_LABEL.format(modified);
            clockLabel = CLOCK_LABEL.format(modified);
        } else {
            dayLabel = "Video " + id;
            clockLabel = "";
        }
    }
}