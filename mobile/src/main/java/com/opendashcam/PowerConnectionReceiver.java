package com.opendashcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Reacts to the charger being connected/disconnected to auto-start or auto-stop recording,
 * so the dashcam can begin when the car powers on and stop when it powers off.
 *
 * Notes:
 * - These power broadcasts are delivered to manifest-registered receivers (they are exempt from
 *   the implicit-broadcast restrictions).
 * - Auto-start relies on the app holding the "display over other apps" permission, which exempts
 *   it from background foreground-service start restrictions on Android 12+.
 */
public class PowerConnectionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());

        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            if (prefs.getBoolean("auto_start_on_charge", false)
                    && !Util.isRecording()
                    && Util.hasRecordingPermissions(context)) {
                try {
                    Util.startRecordingServices(context);
                    Util.logEvent("Auto-start: charger connected");
                } catch (Exception e) {
                    Util.logEvent("Auto-start failed: " + e.getMessage());
                }
            }
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            if (prefs.getBoolean("auto_stop_on_discharge", false) && Util.isRecording()) {
                try {
                    Util.stopRecordingServices(context);
                    Util.logEvent("Auto-stop: charger disconnected");
                } catch (Exception e) {
                    Util.logEvent("Auto-stop failed: " + e.getMessage());
                }
            }
        }
    }
}
