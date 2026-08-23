package app.sentry;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * Offline heuristic that estimates whether the dashcam is aimed correctly, i.e. whether the car
 * bonnet is visible along the bottom of the frame.
 *
 * <p>The idea is purely temporal and needs no internet or ML model: while the car is moving, the
 * road/scene in the middle of the frame changes rapidly frame-to-frame, but a correctly-framed
 * bonnet is rigidly attached to the car and therefore stays almost perfectly still. So if the
 * bottom strip of the frame is <em>much</em> more static than the scene above it, the bonnet is
 * present. If the bottom strip moves just as much as everything else, the camera is likely aimed
 * too high and the bonnet is not in view.
 *
 * <p>Frames are sampled into a small grid in <em>display</em> orientation (so "bottom" always means
 * the bottom of the upright video regardless of how the buffer is rotated), the mean absolute
 * luma difference between consecutive samples is accumulated for a bottom band and a reference
 * band, and a verdict is produced once enough motion has been observed while driving.
 */
final class BonnetMountChecker {

    private static final String TAG = "BonnetMountChecker";

    // Grid resolution in display space (columns x rows). Small on purpose: this is a coarse
    // motion measure, not image recognition.
    private static final int GRID_W = 48;
    private static final int GRID_H = 32;

    // Display-space row bands (fractions of height, top=0 .. bottom=1).
    private static final float BOTTOM_BAND_TOP = 0.90f;   // bottom 10%
    private static final float REF_BAND_TOP = 0.40f;
    private static final float REF_BAND_BOTTOM = 0.70f;

    // Only meaningful while the car is actually moving.
    private static final float MOVING_THRESHOLD_KMH = 12f;

    // Process at most ~5 fps to keep it cheap.
    private static final long MIN_SAMPLE_INTERVAL_MS = 200;

    // How many qualifying (moving, textured) samples to gather before deciding.
    private static final int SAMPLES_FOR_VERDICT = 30;

    // The reference band must show at least this much average motion for a sample to count,
    // otherwise the scene isn't really changing and the comparison is meaningless.
    private static final float MIN_REFERENCE_MOTION = 3.0f;

    // If bottomMotion / referenceMotion exceeds this, the bottom strip is moving with the scene,
    // i.e. the bonnet is probably not in frame.
    private static final float BONNET_MISSING_RATIO = 0.55f;

    // Give up trying to reach a verdict after this much wall-clock time.
    private static final long MAX_RUN_MS = 3 * 60 * 1000L;

    interface Listener {
        /** Called once when the checker concludes the bonnet is not visible at the bottom. */
        void onBonnetNotDetected();
    }

    private final Listener mListener;

    private final float[] mPrev = new float[GRID_W * GRID_H];
    private final float[] mCur = new float[GRID_W * GRID_H];
    private boolean mHasPrev = false;

    private long mLastSampleAt = 0;
    private long mStartedAt = 0;

    private int mSamples = 0;
    private double mBottomMotionSum = 0;
    private double mReferenceMotionSum = 0;

    private boolean mDone = false;

    BonnetMountChecker(Listener listener) {
        mListener = listener;
    }

    boolean isDone() {
        return mDone;
    }

    /**
     * Feeds one analysis frame. Cheap and safe to call on the analyzer thread; internally throttled.
     * Must always {@link ImageProxy#close()} the image — the caller is expected to do that.
     */
    void analyze(ImageProxy image, float speedKmh) {
        if (mDone) return;

        long now = System.currentTimeMillis();
        if (mStartedAt == 0) mStartedAt = now;
        if (now - mStartedAt > MAX_RUN_MS) {
            // Inconclusive within the time budget; stop quietly.
            mDone = true;
            return;
        }
        if (now - mLastSampleAt < MIN_SAMPLE_INTERVAL_MS) return;
        mLastSampleAt = now;

        try {
            sampleLuma(image);
        } catch (Exception e) {
            Log.w(TAG, "Frame sampling failed", e);
            return;
        }

        if (!mHasPrev) {
            System.arraycopy(mCur, 0, mPrev, 0, mCur.length);
            mHasPrev = true;
            return;
        }

        // Only count samples where the car is moving; a parked car makes the whole scene static.
        if (speedKmh >= MOVING_THRESHOLD_KMH) {
            float bottomMotion = bandMotion(BOTTOM_BAND_TOP, 1.0f);
            float refMotion = bandMotion(REF_BAND_TOP, REF_BAND_BOTTOM);

            if (refMotion >= MIN_REFERENCE_MOTION) {
                mBottomMotionSum += bottomMotion;
                mReferenceMotionSum += refMotion;
                mSamples++;

                if (mSamples >= SAMPLES_FOR_VERDICT) {
                    decide();
                }
            }
        }

        System.arraycopy(mCur, 0, mPrev, 0, mCur.length);
    }

    private void decide() {
        mDone = true;
        double avgBottom = mBottomMotionSum / mSamples;
        double avgRef = mReferenceMotionSum / mSamples;
        double ratio = avgRef > 0 ? avgBottom / avgRef : 1.0;
        boolean bonnetMissing = ratio > BONNET_MISSING_RATIO;

        Log.i(TAG, "Mount verdict: avgBottom=" + String.format("%.2f", avgBottom)
                + " avgRef=" + String.format("%.2f", avgRef)
                + " ratio=" + String.format("%.2f", ratio)
                + " -> bonnetMissing=" + bonnetMissing);

        if (bonnetMissing && mListener != null) {
            mListener.onBonnetNotDetected();
        }
    }

    /** Average absolute luma difference (current vs previous) across the given display-row band. */
    private float bandMotion(float topFrac, float bottomFrac) {
        int rowStart = Math.max(0, Math.round(topFrac * GRID_H));
        int rowEnd = Math.min(GRID_H, Math.round(bottomFrac * GRID_H));
        if (rowEnd <= rowStart) rowEnd = Math.min(GRID_H, rowStart + 1);

        double sum = 0;
        int count = 0;
        for (int gy = rowStart; gy < rowEnd; gy++) {
            int base = gy * GRID_W;
            for (int gx = 0; gx < GRID_W; gx++) {
                sum += Math.abs(mCur[base + gx] - mPrev[base + gx]);
                count++;
            }
        }
        return count > 0 ? (float) (sum / count) : 0f;
    }

    /**
     * Samples the Y (luma) plane of the image into {@link #mCur} as a GRID_W x GRID_H grid in
     * <em>display</em> orientation, using the frame's rotation so that grid row 0 is the top of the
     * upright video and the last row is the bottom (where the bonnet should be).
     */
    private void sampleLuma(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return;

        ByteBuffer y = planes[0].getBuffer();
        int rowStride = planes[0].getRowStride();
        int pixelStride = planes[0].getPixelStride();
        int bufW = image.getWidth();
        int bufH = image.getHeight();

        int rotation = normalizeRotation(image.getImageInfo().getRotationDegrees());

        for (int gy = 0; gy < GRID_H; gy++) {
            // Center of the display cell, normalised [0,1)
            float dv = (gy + 0.5f) / GRID_H;
            for (int gx = 0; gx < GRID_W; gx++) {
                float du = (gx + 0.5f) / GRID_W;

                // Map display-normalised (du,dv) back to buffer-normalised (bx,by).
                float bx, by;
                switch (rotation) {
                    case 90:
                        bx = dv;
                        by = 1f - du;
                        break;
                    case 180:
                        bx = 1f - du;
                        by = 1f - dv;
                        break;
                    case 270:
                        bx = 1f - dv;
                        by = du;
                        break;
                    case 0:
                    default:
                        bx = du;
                        by = dv;
                        break;
                }

                int px = clamp((int) (bx * bufW), 0, bufW - 1);
                int py = clamp((int) (by * bufH), 0, bufH - 1);
                int idx = py * rowStride + px * pixelStride;
                int luma = y.get(idx) & 0xFF;
                mCur[gy * GRID_W + gx] = luma;
            }
        }
    }

    private static int normalizeRotation(int deg) {
        int r = deg % 360;
        if (r < 0) r += 360;
        // Snap to the nearest 90.
        return (Math.round(r / 90f) * 90) % 360;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
