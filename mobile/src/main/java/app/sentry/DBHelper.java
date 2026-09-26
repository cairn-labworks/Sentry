package app.sentry;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import app.sentry.models.Recording;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed catalogue of captured clips and the "starred" flags that keep
 * important footage from being recycled.
 *
 * <p>A single shared instance is used across the app (bound to the application
 * context) so every component reads and writes the same connection pool.
 */
public final class DBHelper extends SQLiteOpenHelper implements IDBHelper {

    private static final String TAG = "DBHelper";
    private static final String DB_FILE = "Sentry.db";
    private static final int DB_VERSION = 2;

    private static volatile DBHelper instance;

    private DBHelper(Context context) {
        super(context, DB_FILE, null, DB_VERSION);
    }

    public static DBHelper getInstance(Context context) {
        DBHelper local = instance;
        if (local == null) {
            synchronized (DBHelper.class) {
                local = instance;
                if (local == null) {
                    local = new DBHelper(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        RecordingSchema.create(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        RecordingSchema.recreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        RecordingSchema.recreate(db);
    }

    @Override
    public List<Recording> selectAllRecordingsList() {
        List<Recording> clips = new ArrayList<>();
        Cursor cursor = RecordingSchema.selectAll(getReadableDatabase());
        if (cursor == null) {
            return clips;
        }
        try {
            while (cursor.moveToNext()) {
                Recording clip = RecordingSchema.fromRow(cursor);
                if (clip != null) {
                    clips.add(clip);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to read clip list", e);
        } finally {
            cursor.close();
        }
        return clips;
    }

    @Override
    public boolean insertNewRecording(Recording recording) {
        if (RecordingSchema.exists(getReadableDatabase(), recording)) {
            return false;
        }
        return RecordingSchema.insert(getWritableDatabase(), recording);
    }

    @Override
    public boolean deleteRecording(Recording recording) {
        return RecordingSchema.delete(getWritableDatabase(), recording);
    }

    @Override
    public boolean deleteAllRecordings() {
        return RecordingSchema.deleteAll(getWritableDatabase());
    }

    @Override
    public boolean isRecordingStarred(Recording recording) {
        return RecordingSchema.isStarred(getReadableDatabase(), recording);
    }

    @Override
    public boolean updateStar(Recording recording) {
        SQLiteDatabase db = getWritableDatabase();
        if (recording.isStarred()) {
            return RecordingSchema.removeStar(db, recording);
        }
        return RecordingSchema.addStar(db, recording);
    }
}