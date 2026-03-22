package com.kavachai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class CallAudioService extends Service {

    public static final String ACTION_NEW_SCAM_ALERT   = "com.kavachai.NEW_SCAM_ALERT";
    public static final String ACTION_LIVE_UPDATE      = "com.kavachai.LIVE_UPDATE";
    public static final String ACTION_MONITORING_STATE = "com.kavachai.MONITORING_STATE";
    public static final String ACTION_BACKEND_STATUS   = "com.kavachai.BACKEND_STATUS";

    public static final String EXTRA_RISK_SCORE        = "risk_score";
    public static final String EXTRA_TRANSCRIPT        = "transcript";
    public static final String EXTRA_KEYWORDS          = "keywords";
    public static final String EXTRA_ALERT_LEVEL       = "alert_level";
    public static final String EXTRA_MONITORING_ACTIVE = "monitoring_active";
    public static final String EXTRA_CALL_START_MS     = "call_start_ms";
    public static final String EXTRA_PHONE_NUMBER      = "phone_number";
    public static final String EXTRA_BACKEND_CONNECTED = "backend_connected";

    private static final String TAG               = "CallAudioService";
    private static final int NOTIFICATION_ID      = 101;
    private static final int SCAM_ALERT_ID        = 102;
    private static final int SUSPICIOUS_ALERT_ID  = 103;
    private static final String CHANNEL_ID        = "kavachai_active_call";
    private static final String ALERT_CHANNEL_ID  = "kavachai_alerts";
    private static final String WARNING_CHANNEL_ID = "kavachai_warnings";

    private static final int SAMPLE_RATE          = 16000;

    // Reconnect config
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long[] RECONNECT_DELAYS_MS = {2000, 4000, 8000, 16000, 30000};

    private AudioRecord audioRecord;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private Thread recordingThread;

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private final Object wsLock = new Object();  // guards webSocket reference
    private boolean wsReady = false;             // true when onOpen fires

    private long sessionStartMs = 0L;
    private String callerNumber = "";
    private boolean scamAlertSent = false;
    private boolean suspiciousAlertSent = false;
    private int reconnectAttempts = 0;
    private String backendUrl = "ws://127.0.0.1:8765";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        httpClient = new OkHttpClient();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            // Service restarted by system — clean up
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if ("START_CAPTURE".equals(action)) {
            sessionStartMs = System.currentTimeMillis();
            scamAlertSent = false;
            suspiciousAlertSent = false;
            reconnectAttempts = 0;
            callerNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER);
            if (callerNumber == null) callerNumber = "";

            startForeground(NOTIFICATION_ID, buildActiveNotification());
            startAudioCaptureAndStreaming();
            incrementCallsMonitored();
            broadcastMonitoringState(true);

        } else if ("STOP_CAPTURE".equals(action)) {
            stopAudioCaptureAndStreaming();
            broadcastMonitoringState(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        // START_REDELIVER_INTENT: if killed, restart with the original intent
        return START_REDELIVER_INTENT;
    }

    // ── Audio capture ─────────────────────────────────────────────────────────

    private void startAudioCaptureAndStreaming() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            return;
        }

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_IN_CALL);
            am.setSpeakerphoneOn(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        am.setCommunicationDevice(d);
                        break;
                    }
                }
            }
        }

        // Read backend URL from prefs, fall back to BuildConfig then hardcoded default
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        String savedUrl = prefs.getString("backend_ws_url", null);
        if (savedUrl != null && !savedUrl.trim().isEmpty()) {
            backendUrl = savedUrl.trim();
        } else if (BuildConfig.BACKEND_WS_URL != null && !BuildConfig.BACKEND_WS_URL.isEmpty()) {
            backendUrl = BuildConfig.BACKEND_WS_URL;
        }

        connectWebSocket();

        // Wait 800ms for speakerphone routing to settle
        mainHandler.postDelayed(this::initAndStartRecording, 800);
    }

    private void connectWebSocket() {
        Request request = new Request.Builder()
                .url(backendUrl)
                .build();

        synchronized (wsLock) {
            wsReady = false;
            webSocket = httpClient.newWebSocket(request, new KavachWebSocketListener());
        }
        Log.d(TAG, "WebSocket connecting to: " + backendUrl);
        broadcastBackendStatus(false);
    }

    @SuppressWarnings("MissingPermission")
    private void initAndStartRecording() {
        // Re-check permission in case it was revoked
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO revoked mid-call");
            stopSelf();
            return;
        }

        final int CHUNK_BYTES = SAMPLE_RATE / 4 * 2; // 250ms
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, CHUNK_BYTES * 2);

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord init failed: " + e.getMessage());
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            audioRecord.release();
            audioRecord = null;
            return;
        }

        audioRecord.startRecording();
        isRecording.set(true);
        Log.d(TAG, "Recording started @ " + SAMPLE_RATE + "Hz");

        recordingThread = new Thread(() -> {
            byte[] buf = new byte[CHUNK_BYTES];
            long chunksSent = 0;

            while (isRecording.get()) {
                int bytesRead = audioRecord.read(buf, 0, buf.length);
                if (bytesRead <= 0) continue;

                // Validate chunk size
                if (bytesRead > CHUNK_BYTES) {
                    Log.w(TAG, "Unexpected chunk size: " + bytesRead);
                    continue;
                }

                synchronized (wsLock) {
                    if (webSocket == null || !wsReady) continue;
                    try {
                        String b64 = Base64.encodeToString(buf, 0, bytesRead, Base64.NO_WRAP);
                        JSONObject payload = new JSONObject();
                        payload.put("audio", b64);
                        webSocket.send(payload.toString());
                        chunksSent++;
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON build failed", e);
                    }
                }
            }
            Log.d(TAG, "Recording stopped. Chunks sent: " + chunksSent);
        });
        recordingThread.setName("KavachAudioStream");
        recordingThread.start();
    }

    private void stopAudioCaptureAndStreaming() {
        isRecording.set(false);

        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (IllegalStateException ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        synchronized (wsLock) {
            if (webSocket != null) {
                webSocket.close(1000, "Call Ended");
                webSocket = null;
            }
            wsReady = false;
        }

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_NORMAL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice();
            am.setSpeakerphoneOn(false);
        }
    }

    // ── Call ending ───────────────────────────────────────────────────────────

    private void endActiveCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED) {
                TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    tm.endCall();
                    Log.d(TAG, "Call terminated via TelecomManager.");
                    return;
                }
            }
        }
        Log.w(TAG, "Cannot auto-end call: ANSWER_PHONE_CALLS missing or API < 28");
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private void createNotificationChannels() {
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr == null) return;

        mgr.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "KavachAI Protection Service", NotificationManager.IMPORTANCE_LOW));

        NotificationChannel alertCh = new NotificationChannel(
                ALERT_CHANNEL_ID, "Scam Alerts", NotificationManager.IMPORTANCE_HIGH);
        alertCh.enableVibration(true);
        alertCh.enableLights(true);
        mgr.createNotificationChannel(alertCh);

        NotificationChannel warnCh = new NotificationChannel(
                WARNING_CHANNEL_ID, "Suspicious Call Warnings", NotificationManager.IMPORTANCE_DEFAULT);
        warnCh.enableVibration(true);
        mgr.createNotificationChannel(warnCh);
    }

    private Notification buildActiveNotification() {
        String subtitle = callerNumber.isEmpty()
                ? "Monitoring call for scam patterns"
                : "Monitoring call from " + callerNumber;

        PendingIntent pi = PendingIntent.getActivity(this, 10,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("KavachAI Active")
                .setContentText(subtitle)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void triggerSuspiciousAlert(String transcript, String keywords, String level) {
        PendingIntent pi = PendingIntent.getActivity(this, 20,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        new NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("KavachAI Warning: " + level)
                .setContentText("Stay alert — do not share personal details on this call.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Suspicious keywords: " + keywords + "\n" + transcript))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        NotificationManager mgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mgr != null) {
            mgr.notify(SUSPICIOUS_ALERT_ID,
                    new NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("KavachAI Warning: " + level)
                            .setContentText("Stay alert — do not share personal details on this call.")
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText("Keywords: " + keywords + "\n" + transcript))
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .build());
        }
    }

    private void triggerScamAlert(String transcript, String keywords, String level, int riskScore) {
        PendingIntent pi = PendingIntent.getActivity(this, 30,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        NotificationManager mgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mgr != null) {
            mgr.notify(SCAM_ALERT_ID,
                    new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("KavachAI: " + level)
                            .setContentText("Do NOT share OTP, PIN, or personal details on this call.")
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText("Keywords: " + keywords + "\n" + transcript))
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setDefaults(Notification.DEFAULT_ALL)
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .setFullScreenIntent(pi, true)
                            .build());
        }

        saveAlertHistory(level, transcript, keywords, riskScore);

        // Auto hang-up if pref enabled and score confirmed scam (>=10)
        if (riskScore >= 10) {
            SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
            if (prefs.getBoolean("auto_hangup_enabled", true)) {
                endActiveCall();
            }
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private void saveAlertHistory(String level, String transcript, String keywords, int riskScore) {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        String history = prefs.getString("scam_alerts_history", "[]");

        try {
            JSONArray current = new JSONArray(history);
            JSONObject alert = new JSONObject();
            alert.put("timestamp", System.currentTimeMillis());
            alert.put("level", level);
            alert.put("transcript", transcript);
            alert.put("keywords", keywords);
            alert.put("phone_number", callerNumber);
            alert.put("risk_score", Math.max(0, Math.min(riskScore, 10)));
            alert.put("duration_ms", System.currentTimeMillis() - sessionStartMs);

            JSONArray updated = new JSONArray();
            updated.put(alert);
            for (int i = 0; i < Math.min(current.length(), 49); i++) {
                updated.put(current.getJSONObject(i));
            }
            prefs.edit().putString("scam_alerts_history", updated.toString()).apply();

            Intent dashIntent = new Intent(ACTION_NEW_SCAM_ALERT);
            dashIntent.setPackage(getPackageName());
            sendBroadcast(dashIntent);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save alert history", e);
        }
    }

    private void incrementCallsMonitored() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        prefs.edit().putInt("total_calls_monitored",
                prefs.getInt("total_calls_monitored", 0) + 1).apply();
    }

    // ── Broadcasts ────────────────────────────────────────────────────────────

    private void broadcastMonitoringState(boolean active) {
        Intent i = new Intent(ACTION_MONITORING_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_MONITORING_ACTIVE, active);
        i.putExtra(EXTRA_CALL_START_MS, active ? sessionStartMs : 0L);
        i.putExtra(EXTRA_PHONE_NUMBER, callerNumber);
        sendBroadcast(i);
    }

    private void broadcastLiveUpdate(int score, String transcript, String keywords, String level) {
        Intent i = new Intent(ACTION_LIVE_UPDATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_RISK_SCORE, Math.max(0, score));
        i.putExtra(EXTRA_TRANSCRIPT, transcript);
        i.putExtra(EXTRA_KEYWORDS, keywords);
        i.putExtra(EXTRA_ALERT_LEVEL, level);
        sendBroadcast(i);
    }

    private void broadcastBackendStatus(boolean connected) {
        Intent i = new Intent(ACTION_BACKEND_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_BACKEND_CONNECTED, connected);
        sendBroadcast(i);
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private class KavachWebSocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            Log.d(TAG, "WebSocket connected to backend");
            synchronized (wsLock) {
                wsReady = true;
            }
            reconnectAttempts = 0;
            broadcastBackendStatus(true);
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                JSONObject json = new JSONObject(text);

                // Backend error passthrough
                if (json.has("error")) {
                    Log.w(TAG, "Backend error: " + json.optString("error"));
                    broadcastBackendStatus(false);
                    return;
                }

                if (!json.has("risk_score")) return;

                int score = json.getInt("risk_score");

                // Reject out-of-range scores from backend
                if (score < 0 || score > 100) {
                    Log.w(TAG, "Invalid risk score from backend: " + score);
                    return;
                }

                String transcript = json.optString("transcript", "");
                String keywords   = json.optString("detected_keywords", "");
                String level      = json.optString("level",
                        json.optString("alert_level", "SAFE"));

                broadcastLiveUpdate(score, transcript, keywords, level);

                // SUSPICIOUS/HIGH RISK — one-time notification (score 4–9)
                if (score >= 4 && !suspiciousAlertSent && score < 10) {
                    suspiciousAlertSent = true;
                    triggerSuspiciousAlert(transcript, keywords, level);
                }

                // SCAM DETECTED — full alert (score >= 7)
                if (score >= 7 && !scamAlertSent) {
                    scamAlertSent = true;
                    triggerScamAlert(transcript, keywords, level, score);
                }

            } catch (JSONException e) {
                Log.e(TAG, "Invalid backend payload", e);
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            Log.e(TAG, "WebSocket failure: " + t.getMessage());
            synchronized (wsLock) {
                wsReady = false;
            }
            broadcastBackendStatus(false);
            scheduleReconnect();
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            Log.d(TAG, "WebSocket closed: " + reason);
            synchronized (wsLock) {
                wsReady = false;
            }
        }
    }

    /** Exponential backoff reconnection — up to MAX_RECONNECT_ATTEMPTS. */
    private void scheduleReconnect() {
        if (!isRecording.get()) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Backend unreachable after " + MAX_RECONNECT_ATTEMPTS + " attempts. Giving up.");
            broadcastMonitoringState(false);
            return;
        }

        long delay = RECONNECT_DELAYS_MS[Math.min(reconnectAttempts, RECONNECT_DELAYS_MS.length - 1)];
        reconnectAttempts++;
        Log.d(TAG, "Reconnecting in " + delay + "ms (attempt " + reconnectAttempts + ")");

        mainHandler.postDelayed(() -> {
            if (isRecording.get()) {
                connectWebSocket();
            }
        }, delay);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopAudioCaptureAndStreaming();
        broadcastMonitoringState(false);
        super.onDestroy();
    }
}
