package com.kavachai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * KavachWatchdogService — Persistent minimal foreground service.
 *
 * WHY THIS EXISTS:
 * Android's CallReceiver (PHONE_STATE) is statically declared in the manifest and
 * should survive app kills. However, OEM Android variants (Xiaomi MIUI, Samsung One UI,
 * Oppo ColorOS, Vivo FuntouchOS) kill the app process aggressively in battery-saver
 * mode, which prevents PHONE_STATE broadcasts from being delivered to a dead process.
 *
 * By keeping this lightweight foreground service running, the app process stays alive
 * between calls, guaranteeing that CallReceiver fires the moment a call comes in —
 * with no delay and no missed calls.
 *
 * IMPACT ON USER:
 * - Shows a minimal, low-priority persistent notification (importance=MIN, no sound/vibration)
 * - Uses negligible CPU (service does nothing — just keeps the process alive)
 * - User can dismiss it if they wish; the watchdog restarts on next device reboot
 */
public class KavachWatchdogService extends Service {

    private static final String CHANNEL_ID  = "kavachai_watchdog";
    private static final int    NOTIF_ID    = 999;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if the system kills this service due to memory pressure,
        // Android will automatically restart it as soon as resources are available.
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "KavachAI Background Guard",
                NotificationManager.IMPORTANCE_MIN);   // no heads-up, no sound
        ch.setShowBadge(false);
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) mgr.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("KavachAI")
                .setContentText("Scam call guard is active")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();
    }
}
