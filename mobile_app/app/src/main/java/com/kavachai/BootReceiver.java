package com.kavachai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Ensures the app is ready to monitor calls after device reboot.
 * On Android, BroadcastReceivers for PHONE_STATE are re-enabled automatically
 * for apps that are already installed. This receiver logs the reboot event and
 * performs any startup initialization needed.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "KavachBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            Log.d(TAG, "Device booted. KavachAI monitoring ready.");
            // CallReceiver is registered statically in the manifest and will
            // automatically activate when the next PHONE_STATE broadcast arrives.
            // No explicit re-registration is needed.
        }
    }
}
