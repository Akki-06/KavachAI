package com.kavachai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

public class CallReceiver extends BroadcastReceiver {

    private static final String TAG = "KavachCallReceiver";

    // Track last known state to detect transitions
    private static int lastState = TelephonyManager.CALL_STATE_IDLE;
    private static String lastIncomingNumber = "";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.intent.action.PHONE_STATE".equals(intent.getAction())) {
            return;
        }

        String stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (stateStr == null) return;

        int state;
        switch (stateStr) {
            case TelephonyManager.EXTRA_STATE_RINGING:
                state = TelephonyManager.CALL_STATE_RINGING;
                break;
            case TelephonyManager.EXTRA_STATE_OFFHOOK:
                state = TelephonyManager.CALL_STATE_OFFHOOK;
                break;
            default:
                state = TelephonyManager.CALL_STATE_IDLE;
                break;
        }

        // Capture incoming phone number (available on RINGING state)
        String incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (incomingNumber != null && !incomingNumber.isEmpty()) {
            lastIncomingNumber = incomingNumber;
        }

        onCallStateChanged(context, state, lastIncomingNumber);
    }

    private void onCallStateChanged(Context context, int state, String phoneNumber) {
        if (lastState == state) return;

        int previousState = lastState;
        lastState = state;

        Intent serviceIntent = new Intent(context, CallAudioService.class);

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                // Store number; monitoring starts when call is answered (OFFHOOK)
                Log.d(TAG, "Incoming call ringing");
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                // Start monitoring regardless of incoming or outgoing
                Log.d(TAG, "Call answered/dialed. Starting capture.");
                serviceIntent.setAction("START_CAPTURE");
                if (phoneNumber != null && !phoneNumber.isEmpty()) {
                    serviceIntent.putExtra(CallAudioService.EXTRA_PHONE_NUMBER, phoneNumber);
                }
                serviceIntent.putExtra("is_incoming",
                        previousState == TelephonyManager.CALL_STATE_RINGING);
                context.startForegroundService(serviceIntent);
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                // Call ended
                if (previousState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    Log.d(TAG, "Call ended. Stopping capture.");
                    serviceIntent.setAction("STOP_CAPTURE");
                    context.startService(serviceIntent);
                }
                lastIncomingNumber = "";
                break;
        }
    }
}
