package com.kavachai;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAB_HOME = "home";
    private static final String TAB_HISTORY = "history";
    private static final String TAB_STATS = "stats";
    private static final String TAB_SETTINGS = "settings";

    // ── Home tab views ──
    private TextView tvStatusTitle, tvStatusSubtitle;
    private TextView tvCallsMonitored, tvScamsBlocked, tvSafeCalls;
    private TextView tvLiveHeader, tvCallTimer, tvRiskBadge, tvAlertLevelBadge;
    private TextView tvLiveTranscript, tvLiveKeywords;
    private TextView tvHistoryLabel, tvHistoryHint, tvEmptyState;
    private MaterialCardView statusCard, liveCard, scamAlertCard, warningCard;
    private TextView tvWarningTitle;
    private View statsRow;
    private View btnActivate;
    private ProgressBar progressRisk;
    private RecyclerView recyclerView;
    private AlertAdapter alertAdapter;
    private View historyLabelRow;
    private boolean backendConnected = false;

    // ── Stats tab views ──
    private View statsContent;
    private TextView tvProtectionRate, tvStatTotal, tvStatThreats, tvStatSafe, tvLastDetection;

    // ── Settings tab views ──
    private View settingsContent;
    private TextInputEditText etBackendUrl;
    private SwitchCompat switchAutoHangup;

    // ── Bottom nav ──
    private MaterialButton navHome, navHistory, navStats, navSettings;

    private String currentTab = TAB_HOME;
    private int currentAlertCount = 0;
    private boolean receiversRegistered = false;
    private boolean isMonitoringActive = false;
    private long callStartMs = 0L;
    private int latestScore = 0;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isMonitoringActive || callStartMs <= 0L) return;
            long elapsed = (System.currentTimeMillis() - callStartMs) / 1000L;
            tvCallTimer.setText(formatElapsed(elapsed));
            timerHandler.postDelayed(this, 1000L);
        }
    };

    private final BroadcastReceiver newAlertReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadAlertHistory();
        }
    };

    private final BroadcastReceiver liveUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (CallAudioService.ACTION_MONITORING_STATE.equals(action)) {
                boolean active = intent.getBooleanExtra(CallAudioService.EXTRA_MONITORING_ACTIVE, false);
                long startMs = intent.getLongExtra(CallAudioService.EXTRA_CALL_START_MS, System.currentTimeMillis());
                String phone = intent.getStringExtra(CallAudioService.EXTRA_PHONE_NUMBER);
                updateMonitoringState(active, startMs, phone == null ? "" : phone);
            } else if (CallAudioService.ACTION_LIVE_UPDATE.equals(action)) {
                int score = intent.getIntExtra(CallAudioService.EXTRA_RISK_SCORE, 0);
                String level = intent.getStringExtra(CallAudioService.EXTRA_ALERT_LEVEL);
                String transcript = intent.getStringExtra(CallAudioService.EXTRA_TRANSCRIPT);
                String keywords = intent.getStringExtra(CallAudioService.EXTRA_KEYWORDS);
                updateLiveCard(score,
                        level == null ? "SAFE" : level,
                        transcript == null ? "" : transcript,
                        keywords == null ? "" : keywords);
            } else if (CallAudioService.ACTION_BACKEND_STATUS.equals(action)) {
                backendConnected = intent.getBooleanExtra(CallAudioService.EXTRA_BACKEND_CONNECTED, false);
                updateBackendIndicator();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        wireClicks();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        alertAdapter = new AlertAdapter();
        recyclerView.setAdapter(alertAdapter);

        selectTab(TAB_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkPermissionsAndStatus();
        loadAlertHistory();
        registerAppReceivers();
        loadSettings();
        startWatchdog();
    }

    /** Starts the persistent watchdog service that keeps the process alive between calls. */
    private void startWatchdog() {
        Intent watchdog = new Intent(this, KavachWatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(watchdog);
        } else {
            startService(watchdog);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterAppReceivers();
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void bindViews() {
        // Home tab
        statusCard = findViewById(R.id.status_card);
        liveCard = findViewById(R.id.live_card);
        scamAlertCard = findViewById(R.id.scam_alert_card);
        warningCard = findViewById(R.id.warning_card);
        tvWarningTitle = findViewById(R.id.tv_warning_title);
        statsRow = findViewById(R.id.stats_row);
        btnActivate = findViewById(R.id.btn_activate);
        progressRisk = findViewById(R.id.progress_risk);
        recyclerView = findViewById(R.id.recycler_view);
        historyLabelRow = findViewById(R.id.history_label_row);

        tvStatusTitle = findViewById(R.id.tv_status_title);
        tvStatusSubtitle = findViewById(R.id.tv_status_subtitle);
        tvCallsMonitored = findViewById(R.id.tv_calls_monitored);
        tvScamsBlocked = findViewById(R.id.tv_scams_blocked);
        tvSafeCalls = findViewById(R.id.tv_safe_calls);
        tvHistoryLabel = findViewById(R.id.tv_history_label);
        tvHistoryHint = findViewById(R.id.tv_history_hint);
        tvEmptyState = findViewById(R.id.tv_empty_state);
        tvLiveHeader = findViewById(R.id.tv_live_header);
        tvCallTimer = findViewById(R.id.tv_call_timer);
        tvRiskBadge = findViewById(R.id.tv_risk_badge);
        tvAlertLevelBadge = findViewById(R.id.tv_alert_level_badge);
        tvLiveTranscript = findViewById(R.id.tv_live_transcript);
        tvLiveKeywords = findViewById(R.id.tv_live_keywords);

        // Stats tab
        statsContent = findViewById(R.id.stats_content);
        tvProtectionRate = findViewById(R.id.tv_protection_rate);
        tvStatTotal = findViewById(R.id.tv_stat_total);
        tvStatThreats = findViewById(R.id.tv_stat_threats);
        tvStatSafe = findViewById(R.id.tv_stat_safe);
        tvLastDetection = findViewById(R.id.tv_last_detection);

        // Settings tab
        settingsContent = findViewById(R.id.settings_content);
        etBackendUrl = findViewById(R.id.et_backend_url);
        switchAutoHangup = findViewById(R.id.switch_auto_hangup);

        // Bottom nav
        navHome = findViewById(R.id.nav_home);
        navHistory = findViewById(R.id.nav_history);
        navStats = findViewById(R.id.nav_stats);
        navSettings = findViewById(R.id.nav_settings);
    }

    private void wireClicks() {
        // Top bar
        ImageButton btnBell = findViewById(R.id.btn_bell);
        ImageButton btnSettingsIcon = findViewById(R.id.btn_settings);
        btnBell.setOnClickListener(v -> selectTab(TAB_HISTORY));
        btnSettingsIcon.setOnClickListener(v -> selectTab(TAB_SETTINGS));

        // Status card
        btnActivate.setOnClickListener(v -> openPermissionScreen());

        // Scam alert card buttons
        MaterialButton btnEndCall = findViewById(R.id.btn_end_call);
        MaterialButton btnImSafe = findViewById(R.id.btn_im_safe);
        MaterialButton btnEndCallWarning = findViewById(R.id.btn_end_call_warning);
        MaterialButton btnDismissWarning = findViewById(R.id.btn_dismiss_warning);

        btnEndCall.setOnClickListener(v -> performEndCall());
        btnEndCallWarning.setOnClickListener(v -> performEndCall());
        btnImSafe.setOnClickListener(v -> {
            scamAlertCard.setVisibility(View.GONE);
            warningCard.setVisibility(View.GONE);
        });
        btnDismissWarning.setOnClickListener(v -> warningCard.setVisibility(View.GONE));

        // Settings tab
        MaterialButton btnSaveUrl = findViewById(R.id.btn_save_url);
        MaterialButton btnManagePerms = findViewById(R.id.btn_manage_permissions);
        MaterialButton btnClearHistory = findViewById(R.id.btn_clear_history);

        btnSaveUrl.setOnClickListener(v -> saveBackendUrl());
        btnManagePerms.setOnClickListener(v -> openPermissionScreen());
        btnClearHistory.setOnClickListener(v -> confirmClearHistory());

        switchAutoHangup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences("KavachAIPrefs", MODE_PRIVATE)
                    .edit().putBoolean("auto_hangup_enabled", isChecked).apply();
            Toast.makeText(this,
                    isChecked ? "Auto hang-up enabled" : "Auto hang-up disabled",
                    Toast.LENGTH_SHORT).show();
        });

        // Bottom nav
        navHome.setOnClickListener(v -> selectTab(TAB_HOME));
        navHistory.setOnClickListener(v -> selectTab(TAB_HISTORY));
        navStats.setOnClickListener(v -> selectTab(TAB_STATS));
        navSettings.setOnClickListener(v -> selectTab(TAB_SETTINGS));
    }

    private void selectTab(String tab) {
        currentTab = tab;
        styleBottomNav(tab);

        // Reset all sections
        statusCard.setVisibility(View.GONE);
        statsRow.setVisibility(View.GONE);
        liveCard.setVisibility(View.GONE);
        historyLabelRow.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
        tvEmptyState.setVisibility(View.GONE);
        statsContent.setVisibility(View.GONE);
        settingsContent.setVisibility(View.GONE);

        switch (tab) {
            case TAB_HOME:
                statusCard.setVisibility(View.VISIBLE);
                statsRow.setVisibility(View.VISIBLE);
                liveCard.setVisibility(View.VISIBLE);
                tvHistoryLabel.setText("Recent Alerts");
                tvHistoryHint.setText("Latest");
                showHistorySection();
                break;

            case TAB_HISTORY:
                tvHistoryLabel.setText("Detection History");
                tvHistoryHint.setText("All Alerts");
                showHistorySection();
                break;

            case TAB_STATS:
                statusCard.setVisibility(View.VISIBLE);
                statsRow.setVisibility(View.VISIBLE);
                statsContent.setVisibility(View.VISIBLE);
                refreshStatsContent();
                break;

            case TAB_SETTINGS:
                settingsContent.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void showHistorySection() {
        historyLabelRow.setVisibility(View.VISIBLE);
        if (currentAlertCount > 0) {
            recyclerView.setVisibility(View.VISIBLE);
        } else {
            tvEmptyState.setVisibility(View.VISIBLE);
        }
    }

    private void refreshStatsContent() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        int total = prefs.getInt("total_calls_monitored", 0);
        String alertsJson = prefs.getString("scam_alerts_history", "[]");
        int threats = 0;
        String lastInfo = "No threats detected yet.";
        try {
            JSONArray arr = new JSONArray(alertsJson);
            threats = arr.length();
            if (threats > 0) {
                JSONObject last = arr.getJSONObject(0);
                String level = last.optString("level", "?");
                long ts = last.optLong("timestamp", 0L);
                String timeStr = ts > 0
                        ? new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(new Date(ts))
                        : "Unknown time";
                lastInfo = level + " — " + timeStr + "\n" + last.optString("transcript", "");
            }
        } catch (JSONException ignored) {
        }

        int safe = Math.max(0, total - threats);
        int rate = total > 0 ? (safe * 100 / total) : 100;

        tvProtectionRate.setText(rate + "%");
        tvStatTotal.setText(String.valueOf(total));
        tvStatThreats.setText(String.valueOf(threats));
        tvStatSafe.setText(String.valueOf(safe));
        tvLastDetection.setText(lastInfo);
    }

    private void styleBottomNav(String tab) {
        setNavState(navHome, TAB_HOME.equals(tab));
        setNavState(navHistory, TAB_HISTORY.equals(tab));
        setNavState(navStats, TAB_STATS.equals(tab));
        setNavState(navSettings, TAB_SETTINGS.equals(tab));
    }

    private void setNavState(MaterialButton button, boolean selected) {
        int color = ContextCompat.getColor(this, selected ? R.color.kavach_text : R.color.kavach_muted);
        button.setBackgroundResource(selected ? R.drawable.bg_nav_pill_selected : R.drawable.bg_nav_pill_unselected);
        button.setTextColor(color);
        button.setIconTint(android.content.res.ColorStateList.valueOf(color));
    }

    private void registerAppReceivers() {
        if (receiversRegistered) return;

        ContextCompat.registerReceiver(this, newAlertReceiver,
                new IntentFilter(CallAudioService.ACTION_NEW_SCAM_ALERT),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        IntentFilter liveFilter = new IntentFilter();
        liveFilter.addAction(CallAudioService.ACTION_LIVE_UPDATE);
        liveFilter.addAction(CallAudioService.ACTION_MONITORING_STATE);
        liveFilter.addAction(CallAudioService.ACTION_BACKEND_STATUS);
        ContextCompat.registerReceiver(this, liveUpdateReceiver, liveFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        receiversRegistered = true;
    }

    private void unregisterAppReceivers() {
        if (!receiversRegistered) return;
        unregisterReceiver(newAlertReceiver);
        unregisterReceiver(liveUpdateReceiver);
        receiversRegistered = false;
    }

    private void checkPermissionsAndStatus() {
        boolean hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasNotif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;

        if (hasAudio && hasPhone && hasNotif) {
            tvStatusTitle.setText("PROTECTED");
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.kavach_safe));
            tvStatusSubtitle.setText("Monitoring all incoming & outgoing calls");
            statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.kavach_card_safe_tint));
            statusCard.setStrokeColor(ContextCompat.getColor(this, R.color.kavach_safe));
            btnActivate.setVisibility(View.GONE);
            requestBatteryOptimizationExemption();
        } else {
            tvStatusTitle.setText("NOT PROTECTED");
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.kavach_scam));
            tvStatusSubtitle.setText("Permissions missing. Tap to activate protection.");
            statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.kavach_card_danger_tint));
            statusCard.setStrokeColor(ContextCompat.getColor(this, R.color.kavach_scam));
            btnActivate.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Asks Android to exempt KavachAI from battery optimization.
     * Without this, OEM battery savers (Xiaomi MIUI, Samsung, Oppo, Vivo) kill the
     * background process and PHONE_STATE broadcasts are never delivered.
     * Only prompts once per install — stored in SharedPreferences.
     */
    private void requestBatteryOptimizationExemption() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        if (prefs.getBoolean("battery_opt_requested", false)) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        new AlertDialog.Builder(this)
                .setTitle("Allow Background Monitoring")
                .setMessage("KavachAI needs to run in the background to detect scam calls even " +
                        "when you are not using the app.\n\n" +
                        "On the next screen, select \"All apps\" → find KavachAI → " +
                        "set to \"Don't optimize\".\n\n" +
                        "Some phones (Xiaomi, Samsung, Oppo, Vivo) also have a separate " +
                        "\"Autostart\" or \"Background Activity\" setting — please enable it too.")
                .setPositiveButton("Open Settings", (d, w) -> {
                    prefs.edit().putBoolean("battery_opt_requested", true).apply();
                    try {
                        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        // Fallback to general battery settings if direct intent is blocked
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                })
                .setNegativeButton("Later", (d, w) ->
                        prefs.edit().putBoolean("battery_opt_requested", true).apply())
                .show();
    }

    private void loadAlertHistory() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        int callsMonitored = prefs.getInt("total_calls_monitored", 0);
        String alertsJson = prefs.getString("scam_alerts_history", "[]");

        try {
            JSONArray array = new JSONArray(alertsJson);
            currentAlertCount = array.length();
            alertAdapter.setAlerts(array);
        } catch (JSONException e) {
            currentAlertCount = 0;
            alertAdapter.setAlerts(new JSONArray());
        }

        int safeCalls = Math.max(0, callsMonitored - currentAlertCount);
        tvCallsMonitored.setText(String.valueOf(callsMonitored));
        tvScamsBlocked.setText(String.valueOf(currentAlertCount));
        tvSafeCalls.setText(String.valueOf(safeCalls));

        if (TAB_HOME.equals(currentTab) || TAB_HISTORY.equals(currentTab)) {
            if (currentAlertCount > 0) {
                recyclerView.setVisibility(View.VISIBLE);
                tvEmptyState.setVisibility(View.GONE);
            } else {
                recyclerView.setVisibility(View.GONE);
                tvEmptyState.setVisibility(View.VISIBLE);
            }
        }
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        String url = prefs.getString("backend_ws_url", "ws://127.0.0.1:8765");
        boolean autoHangup = prefs.getBoolean("auto_hangup_enabled", true);

        if (etBackendUrl != null) etBackendUrl.setText(url);
        if (switchAutoHangup != null) switchAutoHangup.setChecked(autoHangup);
    }

    private void saveBackendUrl() {
        String url = etBackendUrl.getText() != null ? etBackendUrl.getText().toString().trim() : "";
        if (url.isEmpty() || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
            Toast.makeText(this, "Enter a valid WebSocket URL (ws:// or wss://)", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences("KavachAIPrefs", MODE_PRIVATE)
                .edit().putString("backend_ws_url", url).apply();
        Toast.makeText(this, "URL saved. Will apply on next call.", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Clear Alert History")
                .setMessage("This will delete all recorded scam alerts. This action cannot be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    getSharedPreferences("KavachAIPrefs", MODE_PRIVATE)
                            .edit().remove("scam_alerts_history").apply();
                    loadAlertHistory();
                    Toast.makeText(this, "History cleared.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateMonitoringState(boolean active, long startMs, String phoneNumber) {
        isMonitoringActive = active;
        if (active) {
            callStartMs = startMs > 0L ? startMs : System.currentTimeMillis();
            tvLiveHeader.setText("LIVE  Monitoring Call");
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler.post(timerRunnable);
        } else {
            callStartMs = 0L;
            tvLiveHeader.setText("LIVE  Idle");
            tvCallTimer.setText("00:00");
            timerHandler.removeCallbacks(timerRunnable);
            warningCard.setVisibility(View.GONE);
            scamAlertCard.setVisibility(View.GONE);
            updateLiveCard(0, "SAFE", "", "");
            latestScore = 0;
        }
    }

    private void updateLiveCard(int score, String level, String transcript, String keywords) {
        latestScore = score;
        int clamped = Math.max(0, Math.min(score, 10));
        int levelColor = resolveRiskColor(clamped, level);

        tvRiskBadge.setText(String.format(Locale.getDefault(), "Risk: %d/10", clamped));
        tvRiskBadge.setTextColor(levelColor);
        tvAlertLevelBadge.setText(level.isEmpty() ? "SAFE" : level.toUpperCase(Locale.getDefault()));
        tvAlertLevelBadge.setTextColor(levelColor);

        progressRisk.setProgress(clamped * 10, true);
        progressRisk.setProgressTintList(android.content.res.ColorStateList.valueOf(levelColor));

        tvLiveTranscript.setText(transcript.isEmpty()
                ? "Transcript will appear once a live call starts."
                : transcript);

        if (keywords.trim().isEmpty()) {
            tvLiveKeywords.setText("Keywords: none");
            tvLiveKeywords.setTextColor(ContextCompat.getColor(this, R.color.kavach_muted));
        } else {
            tvLiveKeywords.setText("Keywords: " + keywords);
            tvLiveKeywords.setTextColor(levelColor);
        }

        String lvlUpper = level.toUpperCase(Locale.getDefault());

        // Show scam card at 10+, warning card at 7-9
        if (clamped >= 10 || lvlUpper.contains("SCAM")) {
            scamAlertCard.setVisibility(View.VISIBLE);
            warningCard.setVisibility(View.GONE);
        } else if (clamped >= 7 || lvlUpper.contains("HIGH RISK")) {
            warningCard.setVisibility(View.VISIBLE);
            tvWarningTitle.setText("HIGH RISK DETECTED");
            scamAlertCard.setVisibility(View.GONE);
        } else if (clamped >= 4 || lvlUpper.contains("SUSPICIOUS")) {
            warningCard.setVisibility(View.VISIBLE);
            tvWarningTitle.setText("SUSPICIOUS ACTIVITY");
            scamAlertCard.setVisibility(View.GONE);
        }
        // Don't hide cards if score drops — user must dismiss manually
    }

    private int resolveRiskColor(int score, String level) {
        String lvl = level == null ? "" : level.toUpperCase(Locale.getDefault());
        if (score >= 10 || lvl.contains("SCAM")) {
            return ContextCompat.getColor(this, R.color.kavach_scam);
        }
        if (score >= 7 || lvl.contains("HIGH RISK") || lvl.contains("SUSPICIOUS")) {
            return ContextCompat.getColor(this, R.color.kavach_warning);
        }
        return ContextCompat.getColor(this, R.color.kavach_safe);
    }

    private void performEndCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED) {
                TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    tm.endCall();
                    Toast.makeText(this, "Call ended.", Toast.LENGTH_SHORT).show();
                    scamAlertCard.setVisibility(View.GONE);
                    warningCard.setVisibility(View.GONE);
                    return;
                }
            }
        }
        // Fallback for older API or missing permission
        Toast.makeText(this, "Please end the call manually from your phone app.", Toast.LENGTH_LONG).show();
    }

    private void openPermissionScreen() {
        startActivity(new Intent(this, PermissionOnboardingActivity.class));
    }

    private String formatElapsed(long totalSeconds) {
        long m = totalSeconds / 60L;
        long s = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%02d:%02d", m, s);
    }
}
