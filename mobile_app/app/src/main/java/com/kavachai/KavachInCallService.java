package com.kavachai;

import android.content.Intent;
import android.os.Build;
import android.telecom.Call;
import android.telecom.InCallService;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Companion InCallService — registered with IN_CALL_SERVICE_UI=false so it runs
 * alongside the user's existing phone app without replacing it.
 *
 * Why this is better than the PHONE_STATE broadcast approach:
 *  - Android binds to this service directly; OEM battery savers cannot block it.
 *  - Call state changes are delivered synchronously via callbacks — no polling,
 *    no race conditions, no missed transitions.
 *  - On devices where the user sets KavachAI as the default dialer this service
 *    also gets privileged audio routing access that may enable VOICE_CALL capture.
 *
 * Requires: android.permission.MANAGE_ONGOING_CALLS (API 31+, normal protection level)
 * Falls back gracefully: on API 26-30 the PHONE_STATE receiver still works.
 */
public class KavachInCallService extends InCallService {

    private static final String TAG = "KavachInCallService";

    // Per-call callbacks so we can unregister them cleanly on removal
    private final Map<Call, Call.Callback> callbackMap = new HashMap<>();

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        Log.d(TAG, "onCallAdded state=" + call.getState());

        Call.Callback cb = new Call.Callback() {
            @Override
            public void onStateChanged(Call c, int state) {
                Log.d(TAG, "Call state changed -> " + state);
                handleCallState(c, state);
            }
        };

        callbackMap.put(call, cb);
        call.registerCallback(cb);

        // Handle calls that are already ACTIVE when the service first binds
        handleCallState(call, call.getState());
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Log.d(TAG, "onCallRemoved");

        Call.Callback cb = callbackMap.remove(call);
        if (cb != null) {
            call.unregisterCallback(cb);
        }

        stopAudioService();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void handleCallState(Call call, int state) {
        switch (state) {
            case Call.STATE_ACTIVE:
                startAudioService(getPhoneNumber(call));
                break;

            case Call.STATE_DISCONNECTED:
            case Call.STATE_DISCONNECTING:
                stopAudioService();
                break;

            default:
                // RINGING, DIALING, HOLDING etc — no action needed
                break;
        }
    }

    private void startAudioService(String phoneNumber) {
        Intent intent = new Intent(this, CallAudioService.class);
        intent.setAction("START_CAPTURE");
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            intent.putExtra(CallAudioService.EXTRA_PHONE_NUMBER, phoneNumber);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Log.d(TAG, "CallAudioService started via InCallService for: " + phoneNumber);
    }

    private void stopAudioService() {
        Intent intent = new Intent(this, CallAudioService.class);
        intent.setAction("STOP_CAPTURE");
        startService(intent);
        Log.d(TAG, "CallAudioService stopped via InCallService");
    }

    private String getPhoneNumber(Call call) {
        try {
            Call.Details details = call.getDetails();
            if (details != null && details.getHandle() != null) {
                String number = details.getHandle().getSchemeSpecificPart();
                return number != null ? number : "";
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not extract phone number: " + e.getMessage());
        }
        return "";
    }
}
