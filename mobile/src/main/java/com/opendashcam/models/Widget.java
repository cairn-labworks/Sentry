package com.opendashcam.models;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;

import com.opendashcam.BackgroundVideoRecorder;
import com.opendashcam.LiveViewActivity;
import com.opendashcam.R;
import com.opendashcam.SettingsActivity;
import com.opendashcam.Util;
import com.opendashcam.ViewRecordingsActivity;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * Abstract class for all specific rootView classes to extend from
 */

public class Widget {
    protected Service service;
    protected WindowManager windowManager;
    private WidgetViewHolder viewHolder;

    private WindowManager.LayoutParams layoutParams;
    private int gravity = Gravity.CENTER_VERTICAL | Gravity.START;
    private int x = 0;
    private int y = 0;

    public Widget(Service service, WindowManager windowManager) {
        this.service = service;
        this.windowManager = windowManager;
        this.viewHolder = new WidgetViewHolder(service);
    }

    public void setPosition(int gravity, int x, int y) {
        this.gravity = gravity;
        this.x = x;
        this.y = y;
    }

    /**
     * Displays the rootView on screen
     */
    public void show() {
        int type = WindowManager.LayoutParams.TYPE_PHONE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

//        rootView.setImageResource(widgetDrawableResource);

        // Set position on screen
        layoutParams.gravity = this.gravity;
        layoutParams.x = this.x;
        layoutParams.y = this.y;

        windowManager.addView(viewHolder.rootViewMenu, layoutParams);
        windowManager.addView(viewHolder.rootView, layoutParams);
    }

    /**
     * Removes the rootView from screen
     */
    public void hide() {
        //widget for "rec" button
        windowManager.removeView(viewHolder.rootView);
        //widget for menu
        windowManager.removeView(viewHolder.rootViewMenu);
    }

    /**
     * Toggles the visibility of the rootView on screen
     */
    public void toggle() {
        viewHolder.toggleSecondaryWidgets();
    }

    private class WidgetViewHolder implements View.OnClickListener {
        View rootView;
        View rootViewMenu;
        View viewRecView;
        View saveRecView;
        View liveViewView;
        View recView;
        View settingsView;
        View stopAndQuitView;
        View layoutMenu;
        boolean areSecondaryWidgetsShown = false;

        // Drag state for moving the REC widget up/down
        private float mInitialTouchY;
        private int mInitialParamsY;
        private boolean mIsDragging;
        private boolean mLongPressReady;
        private int mTouchSlop;
        private final Handler mDragHandler = new Handler(Looper.getMainLooper());
        private final Runnable mLongPressRunnable = new Runnable() {
            @Override
            public void run() {
                mLongPressReady = true;
                recView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        };

        WidgetViewHolder(Context context) {

            rootView = LayoutInflater.from(context).inflate(R.layout.layout_widgets, null);
            recView = rootView.findViewById(R.id.rec_button);

            rootViewMenu = LayoutInflater.from(context).inflate(R.layout.layout_widget_menu, null);
            viewRecView = rootViewMenu.findViewById(R.id.view_recordings_button);
            saveRecView = rootViewMenu.findViewById(R.id.save_recording_button);
            liveViewView = rootViewMenu.findViewById(R.id.live_view_button);
            settingsView = rootViewMenu.findViewById(R.id.settings_button);
            stopAndQuitView = rootViewMenu.findViewById(R.id.stop_and_quit_button);
            layoutMenu = rootViewMenu.findViewById(R.id.layout_menu);

            viewRecView.setOnClickListener(this);
            saveRecView.setOnClickListener(this);
            liveViewView.setOnClickListener(this);
            settingsView.setOnClickListener(this);
            stopAndQuitView.setOnClickListener(this);

            // The REC button supports both tap (toggle menu) and long-press-drag (reposition)
            setupDragOnRecButton(context);

            hideSecondaryWidgets();
        }

        /**
         * Enables repositioning the REC widget vertically: long-press to pick it up (with a
         * haptic cue), then drag up/down. A simple tap (no drag) still toggles the menu.
         */
        private void setupDragOnRecButton(final Context context) {
            mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            final int screenHeight = context.getResources().getDisplayMetrics().heightPixels;

            recView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            mInitialTouchY = event.getRawY();
                            mInitialParamsY = layoutParams.y;
                            mIsDragging = false;
                            mLongPressReady = false;
                            mDragHandler.postDelayed(mLongPressRunnable, 350);
                            return true;
                        case MotionEvent.ACTION_MOVE: {
                            float dy = event.getRawY() - mInitialTouchY;
                            if (!mIsDragging) {
                                if (mLongPressReady && Math.abs(dy) > mTouchSlop) {
                                    mIsDragging = true;
                                } else if (!mLongPressReady && Math.abs(dy) > mTouchSlop) {
                                    // Moved before the long-press fired: treat as a scroll, not a drag
                                    mDragHandler.removeCallbacks(mLongPressRunnable);
                                }
                            }
                            if (mIsDragging) {
                                int newY = mInitialParamsY + (int) dy;
                                int limit = screenHeight / 2;
                                if (newY > limit) newY = limit;
                                if (newY < -limit) newY = -limit;
                                layoutParams.y = newY;
                                try {
                                    windowManager.updateViewLayout(rootView, layoutParams);
                                    windowManager.updateViewLayout(rootViewMenu, layoutParams);
                                } catch (IllegalArgumentException ignored) {
                                    // A view may not be attached; ignore
                                }
                            }
                            return true;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            mDragHandler.removeCallbacks(mLongPressRunnable);
                            boolean wasDragging = mIsDragging;
                            mIsDragging = false;
                            mLongPressReady = false;
                            if (!wasDragging && event.getActionMasked() == MotionEvent.ACTION_UP) {
                                // A tap (not a drag): toggle the menu
                                toggleSecondaryWidgets();
                            }
                            return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public void onClick(View v) {
            int id = v.getId();
            if (id == R.id.view_recordings_button) {
                Intent viewRecordingsIntent = new Intent(service, ViewRecordingsActivity.class);
                viewRecordingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(viewRecordingsIntent);
                hideSecondaryWidgets();
            } else if (id == R.id.save_recording_button) {
                // Access shared references file
                SharedPreferences sharedPref = service.getApplicationContext().getSharedPreferences(
                        service.getString(R.string.current_recordings_preferences_key),
                        Context.MODE_PRIVATE);

                // Save video that is being recorded now
                String currentVideoRecording = sharedPref.
                        getString(service.getString(R.string.current_recording_preferences_key),
                                "null");

                if (currentVideoRecording != "null") {
                    // star current recording
                    Recording recording = new Recording(currentVideoRecording);
                    recording.toggleStar(true);
                }

                // Save the oldest (previous) recording
                String previousVideoRecording = sharedPref.
                        getString(service.getString(R.string.previous_recording_preferences_key),
                                "null");

                if (previousVideoRecording != "null") {
                    // star previous recording
                    Recording recording = new Recording( 0, previousVideoRecording);
                    recording.toggleStar(true);
                }

                // Show success message
                Util.showToastLong(service, service.getString(R.string.save_recording_success_msg));
            } else if (id == R.id.rec_button) {
                toggleSecondaryWidgets();
            } else if (id == R.id.live_view_button) {
                Intent liveIntent = new Intent(service, LiveViewActivity.class);
                liveIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(liveIntent);
                hideSecondaryWidgets();
            } else if (id == R.id.settings_button) {
                Intent settingsIntent = new Intent(service, SettingsActivity.class);
                settingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
                service.startActivity(settingsIntent);
                // hide secondary widgets
                hideSecondaryWidgets();
            } else if (id == R.id.stop_and_quit_button) {
                // Stop video recording service
                service.stopService(new Intent(service, BackgroundVideoRecorder.class));
                // Stop the rootView service
                service.stopSelf();
            }
        }

        private void toggleSecondaryWidgets() {
            if (areSecondaryWidgetsShown) {
                hideSecondaryWidgets();
            } else {
                showSecondaryWidgets();
            }
        }

        private void showSecondaryWidgets() {
            rootViewMenu.setVisibility(View.VISIBLE);

            //show menu layout with animation
            Animation animation = new ScaleAnimation(
                    0f, 1f,
                    0f, 1f,
                    Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0.5f
            );
            animation.setFillAfter(true);
            animation.setDuration(200);
            layoutMenu.startAnimation(animation);

            areSecondaryWidgetsShown = true;
        }

        private void hideSecondaryWidgets() {
            //hide menu layout with animation
            Animation animation = new ScaleAnimation(
                    1f, 0f,
                    1f, 0f,
                    Animation.RELATIVE_TO_SELF, 0f,
                    Animation.RELATIVE_TO_SELF, 0.5f
            );
            //on the first start no need to show animation, set 0
            animation.setDuration(areSecondaryWidgetsShown ? 200 : 0);
            animation.setFillAfter(true);
            animation.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                    //do nothing
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    rootViewMenu.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                    //do nothing
                }
            });
            layoutMenu.startAnimation(animation);

            areSecondaryWidgetsShown = false;
        }
    }
}
