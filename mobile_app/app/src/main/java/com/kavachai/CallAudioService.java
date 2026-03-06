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
    private static final String CHANNEL_ID = "kavachai_active_call";
    private static final String ALERT_CHANNEL_ID = "kavachai_alerts";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_SIZE_MS = 500;
    private static final int BUFFER_SIZE = SAMPLE_RATE * 2 * (CHUNK_SIZE_MS / 1000);

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;

    private OkHttpClient client;
    private WebSocket webSocket;
    private long sessionStartMs = 0L;
    private boolean scamAlertSent = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
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

        // Android 10+ blocks direct call audio capture for regular apps.
        // Workaround: force speakerphone ON so caller audio plays through speaker,
        // then use VOICE_RECOGNITION (microphone) to pick it up acoustically.
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            // Use MODE_IN_CALL to correctly route audio
            audioManager.setMode(AudioManager.MODE_IN_CALL);
            // Force speakerphone on all API levels
            audioManager.setSpeakerphoneOn(true);
            // Also try the new setCommunicationDevice API on Android 12+
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

        String websocketUrl = BuildConfig.BACKEND_WS_URL;
        if (websocketUrl == null || websocketUrl.trim().isEmpty()) {
            websocketUrl = "ws://127.0.0.1:8765";
        }

        Request request = new Request.Builder().url(websocketUrl).build();
        webSocket = client.newWebSocket(request, new KavachWebSocketListener());

        // Wait 800ms for speakerphone routing to fully settle before starting
        // AudioRecord
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                this::initAndStartRecording, 800);
    }

    @SuppressWarnings("MissingPermission")
    private void initAndStartRecording() {
        // Chunk size: 250ms of audio at 16kHz mono 16-bit = 8000 bytes
        final int CHUNK_BYTES = SAMPLE_RATE / 4 * 2; // 250ms
        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBufferSize, CHUNK_BYTES * 2);

        // VOICE_RECOGNITION: captures raw microphone input (picks up speaker
        // acoustically)
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

        Log.d(TAG, "AudioRecord started with VOICE_RECOGNITION @ " + SAMPLE_RATE + "Hz");
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
                // Send all audio — let Sarvam's VAD decide what's speech
                try {
                    String base64Audio = Base64.encodeToString(audioBuffer, 0, bytesRead, Base64.NO_WRAP);
                    JSONObject payload = new JSONObject();
                    payload.put("audio", base64Audio);
                    webSocket.send(payload.toString());
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
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
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

    private void incrementCallsMonitored() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        int count = prefs.getInt("total_calls_monitored", 0);
        prefs.edit().putInt("total_calls_monitored", count + 1).apply();
    }

    private Notification buildActiveNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("KavachAI Active")
                .setContentText("Monitoring call for scam patterns")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "KavachAI Protection Service",
                NotificationManager.IMPORTANCE_LOW);

        NotificationChannel alertChannel = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "Scam Alerts",
                NotificationManager.IMPORTANCE_HIGH);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
            manager.createNotificationChannel(alertChannel);
        }
    }

    private void triggerScamAlert(String transcript, String keywords, String level, int riskScore) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("SCAM DETECTED")
                .setContentText("Do not share OTP or PIN on this call.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText("Keywords: " + keywords + "\n" + transcript))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(Notification.DEFAULT_ALL)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(SCAM_ALERT_ID, builder.build());
        }

        saveAlertHistory(level, transcript, keywords, riskScore);
    }

    private void saveAlertHistory(String level, String transcript, String keywords, int riskScore) {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        String history = prefs.getString("scam_alerts_history", "[]");

        try {
            JSONArray currentArray = new JSONArray(history);
            JSONObject newAlert = new JSONObject();
            newAlert.put("timestamp", System.currentTimeMillis());
            newAlert.put("level", level);
            newAlert.put("transcript", transcript);
            newAlert.put("keywords", keywords);
            newAlert.put("risk_score", Math.max(0, Math.min(riskScore, 10)));

            JSONArray updatedArray = new JSONArray();
            updatedArray.put(newAlert);
            for (int i = 0; i < Math.min(currentArray.length(), 9); i++) {
                updatedArray.put(currentArray.getJSONObject(i));
            }

            prefs.edit().putString("scam_alerts_history", updatedArray.toString()).apply();

            Intent dashboardIntent = new Intent(ACTION_NEW_SCAM_ALERT);
            dashboardIntent.setPackage(getPackageName());
            sendBroadcast(dashboardIntent);
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
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject json = new JSONObject(text);
                if (!json.has("risk_score")) {
                    return;
                }

                int score = json.getInt("risk_score");
                String transcript = json.optString("transcript", "");
                String keywords = json.optString("detected_keywords", "");
                String level = json.optString("level", json.optString("alert_level", "SAFE"));

                broadcastLiveUpdate(score, transcript, keywords, level);

                if (score >= 10 && !scamAlertSent) {
                    scamAlertSent = true;
                    triggerScamAlert(transcript, keywords, level, score);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Invalid backend payload: " + text, e);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "WebSocket failure", t);
            broadcastMonitoringState(false);
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
