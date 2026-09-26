package app.sentry;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import app.sentry.models.Recording;

/**
 * Table definitions and row-level helpers for the clip catalogue.
 *
 * <p>Two tables are involved: one row per captured clip, and a companion table
 * that simply lists the file names the user has starred. Keeping stars in their
 * own table means toggling a star never rewrites the clip row itself.
 */
final class RecordingSchema {

    private static final String TAG = "RecordingSchema";

    private RecordingSchema() {
    }

    // --- clip table -----------------------------------------------------
    private static final String CLIPS = "recording";
    private static final String CLIP_ID = "_id";
    private static final String CLIP_PATH = "file_path";
    private static final String CLIP_NAME = "file_name";

    // --- star table -----------------------------------------------------
    private static final String STARS = "starred_recording";
    private static final String STAR_ID = "_id";
    private static final String STAR_NAME = "file";

    private static final String[] CLIP_COLUMNS = {CLIP_ID, CLIP_PATH, CLIP_NAME};

    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + CLIPS + " ("
                + CLIP_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + CLIP_PATH + " TEXT, "
                + CLIP_NAME + " TEXT);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + STARS + " ("
                + STAR_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + STAR_NAME + " TEXT);");
    }

    static void recreate(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + CLIPS);
        db.execSQL("DROP TABLE IF EXISTS " + STARS);
        create(db);
    }

    static Cursor selectAll(SQLiteDatabase db) {
        return db.query(CLIPS, CLIP_COLUMNS, null, null, null, null, CLIP_ID + " DESC");
    }

    static Recording fromRow(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        int id = cursor.getInt(cursor.getColumnIndexOrThrow(CLIP_ID));
        String path = cursor.getString(cursor.getColumnIndexOrThrow(CLIP_PATH));
        return new Recording(id, path);
    }

    static boolean exists(SQLiteDatabase db, Recording clip) {
        if (clip == null) {
            return false;
        }
        Cursor cursor = db.query(CLIPS, new String[]{CLIP_ID},
                "CAST(" + CLIP_ID + " AS TEXT) = ?",
                new String[]{String.valueOf(clip.getId())}, null, null, null);
        return countAndClose(cursor) > 0;
    }

    static boolean insert(SQLiteDatabase db, Recording clip) {
        if (clip == null) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put(CLIP_PATH, clip.getFilePath());
        values.put(CLIP_NAME, clip.getFileName());
        return runInsert(db, CLIPS, values, "insert clip");
    }

    static boolean delete(SQLiteDatabase db, Recording clip) {
        int removed = db.delete(CLIPS, CLIP_NAME + " LIKE ?", new String[]{clip.getFileName()});
        if (removed > 0) {
            db.delete(STARS, STAR_NAME + " LIKE ?", new String[]{clip.getFileName()});
        }
        return removed > 0;
    }

    static boolean deleteAll(SQLiteDatabase db) {
        int removed = db.delete(CLIPS, null, null);
        if (removed > 0) {
            db.delete(STARS, null, null);
        }
        return removed > 0;
    }

    static boolean isStarred(SQLiteDatabase db, Recording clip) {
        if (clip == null) {
            return false;
        }
        Cursor cursor = db.query(STARS, new String[]{STAR_ID},
                STAR_NAME + " LIKE ?", new String[]{clip.getFileName()}, null, null, null);
        return countAndClose(cursor) > 0;
    }

    static boolean addStar(SQLiteDatabase db, Recording clip) {
        if (clip == null) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put(STAR_NAME, clip.getFileName());
        return runInsert(db, STARS, values, "add star");
    }

    static boolean removeStar(SQLiteDatabase db, Recording clip) {
        int removed = 0;
        db.beginTransaction();
        try {
            removed = db.delete(STARS, STAR_NAME + " LIKE ?", new String[]{clip.getFileName()});
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "remove star failed", e);
        } finally {
            db.endTransaction();
        }
        return removed > 0;
    }

    private static boolean runInsert(SQLiteDatabase db, String table, ContentValues values, String what) {
        long rowId = -1;
        db.beginTransaction();
        try {
            rowId = db.insert(table, null, values);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, what + " failed", e);
        } finally {
            db.endTransaction();
        }
        return rowId > -1;
    }

    private static int countAndClose(Cursor cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            return cursor.getCount();
        } catch (Exception e) {
            Log.e(TAG, "count failed", e);
            return 0;
        } finally {
            cursor.close();
        }
    }
}