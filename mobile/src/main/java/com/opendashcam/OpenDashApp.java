package com.opendashcam;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Created by ashish on 8/23/17.
 */

public class OpenDashApp extends Application {

    private static OpenDashApp sApp;

    @Override
    public void onCreate() {
        super.onCreate();

        if (sApp == null) {
            sApp = this;
        }

        // Follow the system light/dark setting across all screens
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /**
     * Get app context
     *
     * @return Context
     */
    public static Context getAppContext() {
        return sApp.getApplicationContext();
    }
}
