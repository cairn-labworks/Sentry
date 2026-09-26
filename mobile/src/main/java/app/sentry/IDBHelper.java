package app.sentry;

import app.sentry.models.Recording;

import java.util.List;

/**
 * Persistence contract for the clip catalogue.
 *
 * <p>Implementations own the mapping between {@link Recording} instances and
 * whatever on-device store backs them. Callers work purely through this
 * abstraction so the storage engine can be swapped without touching the UI.
 */
interface IDBHelper {

    /** Returns every known clip, newest first. */
    List<Recording> selectAllRecordingsList();

    /** Records a freshly captured clip. Returns {@code false} if already present. */
    boolean insertNewRecording(Recording recording);

    /** Forgets a single clip (and any star it carries). */
    boolean deleteRecording(Recording recording);

    /** Empties the entire catalogue. */
    boolean deleteAllRecordings();

    /** Flips the starred flag for a clip. */
    boolean updateStar(Recording recording);

    /** Reports whether a clip is currently starred. */
    boolean isRecordingStarred(Recording recording);
}