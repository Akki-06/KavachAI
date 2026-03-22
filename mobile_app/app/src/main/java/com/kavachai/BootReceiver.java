package com.kavachai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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
            Log.d(TAG, "Device booted. Starting KavachAI watchdog service.");

            // Start the persistent watchdog service so the process stays alive and
            // CallReceiver (PHONE_STATE) is guaranteed to fire on the next call,
            // even on OEM Androids (Xiaomi/Samsung/Oppo) with aggressive app killers.
            Intent watchdog = new Intent(context, KavachWatchdogService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(watchdog);
            } else {
                context.startService(watchdog);
            }
        }
    }
}
