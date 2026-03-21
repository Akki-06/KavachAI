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
import android.os.IBinder;
import android.telecom.TelecomManager;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class CallAudioService extends Service {

    public static final String ACTION_NEW_SCAM_ALERT = "com.kavachai.NEW_SCAM_ALERT";
    public static final String ACTION_LIVE_UPDATE = "com.kavachai.LIVE_UPDATE";
    public static final String ACTION_MONITORING_STATE = "com.kavachai.MONITORING_STATE";

    public static final String EXTRA_RISK_SCORE = "risk_score";
    public static final String EXTRA_TRANSCRIPT = "transcript";
    public static final String EXTRA_KEYWORDS = "keywords";
    public static final String EXTRA_ALERT_LEVEL = "alert_level";
    public static final String EXTRA_MONITORING_ACTIVE = "monitoring_active";
    public static final String EXTRA_CALL_START_MS = "call_start_ms";

    private static final String TAG = "CallAudioService";
    private static final int NOTIFICATION_ID = 101;
    private static final int SCAM_ALERT_ID = 102;
    private static final int SUSPICIOUS_ALERT_ID = 103;
    private static final String CHANNEL_ID = "kavachai_active_call";
    private static final String ALERT_CHANNEL_ID = "kavachai_alerts";
    private static final String WARNING_CHANNEL_ID = "kavachai_warnings";

    private static final int SAMPLE_RATE = 16000;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;

    private OkHttpClient client;
    private WebSocket webSocket;
    private long sessionStartMs = 0L;
    private boolean scamAlertSent = false;
    private boolean suspiciousAlertSent = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        client = new OkHttpClient();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if ("START_CAPTURE".equals(action)) {
            sessionStartMs = System.currentTimeMillis();
            scamAlertSent = false;
            suspiciousAlertSent = false;
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

        return START_NOT_STICKY;
    }

    private void startAudioCaptureAndStreaming() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted");
            return;
        }

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_CALL);
            audioManager.setSpeakerphoneOn(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        audioManager.setCommunicationDevice(device);
                        break;
                    }
                }
            }
            Log.d(TAG, "Speakerphone forced ON, mode=MODE_IN_CALL");
        }

        // Read backend URL from SharedPreferences, fall back to BuildConfig
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        String websocketUrl = prefs.getString("backend_ws_url", null);
        if (websocketUrl == null || websocketUrl.trim().isEmpty()) {
            websocketUrl = BuildConfig.BACKEND_WS_URL;
        }
        if (websocketUrl == null || websocketUrl.trim().isEmpty()) {
            websocketUrl = "ws://127.0.0.1:8765";
        }

        Log.d(TAG, "Connecting to backend: " + websocketUrl);
        Request request = new Request.Builder().url(websocketUrl).build();
        webSocket = client.newWebSocket(request, new KavachWebSocketListener());

        // Wait 800ms for speakerphone routing to settle before starting AudioRecord
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                this::initAndStartRecording, 800);
    }

    @SuppressWarnings("MissingPermission")
    private void initAndStartRecording() {
        final int CHUNK_BYTES = SAMPLE_RATE / 4 * 2; // 250ms at 16kHz mono 16-bit
        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBufferSize, CHUNK_BYTES * 2);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
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

        Log.d(TAG, "AudioRecord started @ " + SAMPLE_RATE + "Hz");
        audioRecord.startRecording();
        isRecording = true;

        recordingThread = new Thread(() -> {
            byte[] audioBuffer = new byte[CHUNK_BYTES];
            long chunksSent = 0;
            while (isRecording) {
                int bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (bytesRead <= 0 || webSocket == null) {
                    continue;
                }
                try {
                    String base64Audio = Base64.encodeToString(audioBuffer, 0, bytesRead, Base64.NO_WRAP);
                    JSONObject payload = new JSONObject();
                    payload.put("audio", base64Audio);
                    webSocket.send(payload.toString());
                    chunksSent++;
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to send audio chunk", e);
                }
            }
            Log.d(TAG, "Recording stopped. Total chunks sent: " + chunksSent);
        });
        recordingThread.setName("KavachAudioStream");
        recordingThread.start();
    }

    private void stopAudioCaptureAndStreaming() {
        isRecording = false;

        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (IllegalStateException ignored) {}
            audioRecord.release();
            audioRecord = null;
        }

        if (webSocket != null) {
            webSocket.close(1000, "Call Ended");
            webSocket = null;
        }

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
            audioManager.setSpeakerphoneOn(false);
        }
    }

    /** End the active call using TelecomManager (API 28+ / ANSWER_PHONE_CALLS permission). */
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
        Log.w(TAG, "Cannot auto-end call: ANSWER_PHONE_CALLS permission missing or API < 28");
    }

    private void incrementCallsMonitored() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        int count = prefs.getInt("total_calls_monitored", 0);
        prefs.edit().putInt("total_calls_monitored", count + 1).apply();
    }

    private Notification buildActiveNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("KavachAI Active")
                .setContentText("Monitoring call for scam patterns")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;

        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "KavachAI Protection Service", NotificationManager.IMPORTANCE_LOW));

        NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID, "Scam Alerts", NotificationManager.IMPORTANCE_HIGH);
        alertChannel.enableVibration(true);
        alertChannel.enableLights(true);
        manager.createNotificationChannel(alertChannel);

        NotificationChannel warnChannel = new NotificationChannel(
                WARNING_CHANNEL_ID, "Suspicious Call Warnings", NotificationManager.IMPORTANCE_DEFAULT);
        warnChannel.enableVibration(true);
        manager.createNotificationChannel(warnChannel);
    }

    private void triggerSuspiciousAlert(String transcript, String keywords, String level) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, WARNING_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("KavachAI Warning: " + level)
                .setContentText("Stay alert — do not share personal details on this call.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Suspicious keywords: " + keywords + "\n" + transcript))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(SUSPICIOUS_ALERT_ID, builder.build());
    }

    private void triggerScamAlert(String transcript, String keywords, String level, int riskScore) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("KavachAI Warning: " + level)
                .setContentText("Do not share OTP, PIN, or personal details on this call.")
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Keywords: " + keywords + "\n" + transcript))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_ALL)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setFullScreenIntent(pi, true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(SCAM_ALERT_ID, builder.build());

        saveAlertHistory(level, transcript, keywords, riskScore);

        // Auto hang-up if enabled and confirmed scam
        if (riskScore >= 10) {
            SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
            if (prefs.getBoolean("auto_hangup_enabled", true)) {
                endActiveCall();
            }
        }
    }

    private void saveAlertHistory(String level, String transcript, String keywords, int riskScore) {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        String history = prefs.getString("scam_alerts_history", "[]");

        try {
            JSONArray current = new JSONArray(history);
            JSONObject newAlert = new JSONObject();
            newAlert.put("timestamp", System.currentTimeMillis());
            newAlert.put("level", level);
            newAlert.put("transcript", transcript);
            newAlert.put("keywords", keywords);
            newAlert.put("risk_score", Math.max(0, Math.min(riskScore, 10)));

            JSONArray updated = new JSONArray();
            updated.put(newAlert);
            for (int i = 0; i < Math.min(current.length(), 9); i++) {
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

    private void broadcastMonitoringState(boolean active) {
        Intent stateIntent = new Intent(ACTION_MONITORING_STATE);
        stateIntent.setPackage(getPackageName());
        stateIntent.putExtra(EXTRA_MONITORING_ACTIVE, active);
        stateIntent.putExtra(EXTRA_CALL_START_MS, active ? sessionStartMs : 0L);
        sendBroadcast(stateIntent);
    }

    private void broadcastLiveUpdate(int score, String transcript, String keywords, String level) {
        Intent liveIntent = new Intent(ACTION_LIVE_UPDATE);
        liveIntent.setPackage(getPackageName());
        liveIntent.putExtra(EXTRA_RISK_SCORE, Math.max(0, score));
        liveIntent.putExtra(EXTRA_TRANSCRIPT, transcript);
        liveIntent.putExtra(EXTRA_KEYWORDS, keywords);
        liveIntent.putExtra(EXTRA_ALERT_LEVEL, level);
        sendBroadcast(liveIntent);
    }

    private class KavachWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "WebSocket connected to backend");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject json = new JSONObject(text);
                if (!json.has("risk_score")) return;

                int score = json.getInt("risk_score");
                String transcript = json.optString("transcript", "");
                String keywords = json.optString("detected_keywords", "");
                String level = json.optString("level", json.optString("alert_level", "SAFE"));

                broadcastLiveUpdate(score, transcript, keywords, level);

                // SUSPICIOUS / HIGH RISK notification (one-time, score 4-9)
                if (score >= 4 && !suspiciousAlertSent && score < 10) {
                    suspiciousAlertSent = true;
                    triggerSuspiciousAlert(transcript, keywords, level);
                }

                // SCAM DETECTED full alert (one-time, score 10+)
                if (score >= 7 && !scamAlertSent) {
                    scamAlertSent = true;
                    triggerScamAlert(transcript, keywords, level, score);
                }

            } catch (JSONException e) {
                Log.e(TAG, "Invalid backend payload: " + text, e);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "WebSocket failure: " + t.getMessage());
            broadcastMonitoringState(false);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "WebSocket closed: " + reason);
        }
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
