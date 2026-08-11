package app.opendash;

import android.app.Application;
import android.content.Context;

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

        // Apply the user's saved theme preference (defaults to following the system setting)
        Util.applyStoredTheme();
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
