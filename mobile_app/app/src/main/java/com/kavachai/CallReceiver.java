package com.kavachai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

public class CallReceiver extends BroadcastReceiver {

    private static int lastState = TelephonyManager.CALL_STATE_IDLE;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.intent.action.PHONE_STATE".equals(intent.getAction())) {
            return;
        }

        String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (stateStr == null) return;

        int state = 0;
        if (stateStr.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            state = TelephonyManager.CALL_STATE_IDLE;
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
            state = TelephonyManager.CALL_STATE_OFFHOOK;
        } else if (stateStr.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
            state = TelephonyManager.CALL_STATE_RINGING;
        }

        onCallStateChanged(context, state);
    }

    private void onCallStateChanged(Context context, int state) {
        if (lastState == state) return;

        Intent serviceIntent = new Intent(context, CallAudioService.class);

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                // Incoming call ringing
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                // Call answered or dialing out
                if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                    serviceIntent.setAction("START_CAPTURE");
                    context.startForegroundService(serviceIntent);
                } else {
                    // Outgoing call - we can start protecting here too if desired
                    serviceIntent.setAction("START_CAPTURE");
                    context.startForegroundService(serviceIntent);
                }
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                // Call ended
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    serviceIntent.setAction("STOP_CAPTURE");
                    context.startService(serviceIntent); // Can be startService for STOP intent
                }
                break;
        }

        lastState = state;
    }
}
