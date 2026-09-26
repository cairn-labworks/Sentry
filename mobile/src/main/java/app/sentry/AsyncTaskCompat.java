package app.sentry;

import android.os.AsyncTask;

/**
 * Tiny helper that launches an {@link AsyncTask} on the shared thread pool.
 *
 * <p>The platform default runs tasks serially; here we opt into parallel
 * execution explicitly so unrelated background jobs do not queue behind one
 * another. Kept as a small seam so the concurrency strategy lives in one place.
 */
final class AsyncTaskCompat {

    private AsyncTaskCompat() {
    }

    @SafeVarargs
    public static <P, Prog, R> AsyncTask<P, Prog, R> executeParallel(AsyncTask<P, Prog, R> task, P... params) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        return task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
    }
}