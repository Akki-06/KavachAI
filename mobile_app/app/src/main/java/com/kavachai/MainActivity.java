package com.kavachai;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAB_HOME = "home";
    private static final String TAB_HISTORY = "history";
    private static final String TAB_STATS = "stats";

    private TextView tvStatusTitle;
    private TextView tvStatusSubtitle;
    private TextView tvCallsMonitored;
    private TextView tvScamsBlocked;
    private TextView tvSafeCalls;
    private TextView tvHistoryLabel;
    private TextView tvHistoryHint;
    private TextView tvEmptyState;
    private TextView tvLiveHeader;
    private TextView tvCallTimer;
    private TextView tvRiskBadge;
    private TextView tvLiveTranscript;
    private TextView tvLiveKeywords;

    private MaterialCardView statusCard;
    private MaterialCardView liveCard;
    private MaterialCardView scamAlertCard;
    private View statsRow;
    private View btnActivate;
    private ProgressBar progressRisk;
    private RecyclerView recyclerView;
    private AlertAdapter alertAdapter;
    private MaterialButton navHome;
    private MaterialButton navHistory;
    private MaterialButton navStats;
    private MaterialButton navSettings;

    private String currentTab = TAB_HOME;
    private int currentAlertCount = 0;
    private boolean receiversRegistered = false;
    private boolean isMonitoringActive = false;
    private long callStartMs = 0L;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isMonitoringActive || callStartMs <= 0L) {
                return;
            }
            long elapsedSeconds = (System.currentTimeMillis() - callStartMs) / 1000L;
            tvCallTimer.setText(formatElapsed(elapsedSeconds));
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
                updateMonitoringState(active, startMs);
                return;
            }

            if (CallAudioService.ACTION_LIVE_UPDATE.equals(action)) {
                int score = intent.getIntExtra(CallAudioService.EXTRA_RISK_SCORE, 0);
                String level = intent.getStringExtra(CallAudioService.EXTRA_ALERT_LEVEL);
                String transcript = intent.getStringExtra(CallAudioService.EXTRA_TRANSCRIPT);
                String keywords = intent.getStringExtra(CallAudioService.EXTRA_KEYWORDS);
                updateLiveCard(score, level == null ? "" : level, transcript == null ? "" : transcript, keywords == null ? "" : keywords);
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterAppReceivers();
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void bindViews() {
        statusCard = findViewById(R.id.status_card);
        liveCard = findViewById(R.id.live_card);
        scamAlertCard = findViewById(R.id.scam_alert_card);
        statsRow = findViewById(R.id.stats_row);
        btnActivate = findViewById(R.id.btn_activate);
        progressRisk = findViewById(R.id.progress_risk);
        recyclerView = findViewById(R.id.recycler_view);

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
        tvLiveTranscript = findViewById(R.id.tv_live_transcript);
        tvLiveKeywords = findViewById(R.id.tv_live_keywords);

        navHome = findViewById(R.id.nav_home);
        navHistory = findViewById(R.id.nav_history);
        navStats = findViewById(R.id.nav_stats);
        navSettings = findViewById(R.id.nav_settings);
    }

    private void wireClicks() {
        ImageButton btnSettings = findViewById(R.id.btn_settings);
        ImageButton btnBell = findViewById(R.id.btn_bell);
        MaterialButton btnEndCall = findViewById(R.id.btn_end_call);
        MaterialButton btnImSafe = findViewById(R.id.btn_im_safe);

        btnSettings.setOnClickListener(v -> openPermissionScreen());
        btnBell.setOnClickListener(v -> selectTab(TAB_HISTORY));
        btnActivate.setOnClickListener(v -> openPermissionScreen());
        btnEndCall.setOnClickListener(v -> Toast.makeText(this, "End the call from your phone app.", Toast.LENGTH_SHORT).show());
        btnImSafe.setOnClickListener(v -> scamAlertCard.setVisibility(View.GONE));

        navHome.setOnClickListener(v -> selectTab(TAB_HOME));
        navHistory.setOnClickListener(v -> selectTab(TAB_HISTORY));
        navStats.setOnClickListener(v -> selectTab(TAB_STATS));
        navSettings.setOnClickListener(v -> openPermissionScreen());
    }

    private void openPermissionScreen() {
        startActivity(new Intent(this, PermissionOnboardingActivity.class));
    }

    private void registerAppReceivers() {
        if (receiversRegistered) {
            return;
        }

        ContextCompat.registerReceiver(
                this,
                newAlertReceiver,
                new IntentFilter(CallAudioService.ACTION_NEW_SCAM_ALERT),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        IntentFilter liveFilter = new IntentFilter();
        liveFilter.addAction(CallAudioService.ACTION_LIVE_UPDATE);
        liveFilter.addAction(CallAudioService.ACTION_MONITORING_STATE);
        ContextCompat.registerReceiver(this, liveUpdateReceiver, liveFilter, ContextCompat.RECEIVER_NOT_EXPORTED);

        receiversRegistered = true;
    }

    private void unregisterAppReceivers() {
        if (!receiversRegistered) {
            return;
        }
        unregisterReceiver(newAlertReceiver);
        unregisterReceiver(liveUpdateReceiver);
        receiversRegistered = false;
    }

    private void checkPermissionsAndStatus() {
        boolean hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        boolean hasNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;

        boolean hasPerms = hasAudio && hasPhone && hasNotification;

        if (hasPerms) {
            tvStatusTitle.setText("PROTECTED");
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.kavach_safe));
            tvStatusSubtitle.setText("Monitoring incoming calls in real-time");
            statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.kavach_card_safe_tint));
            statusCard.setStrokeColor(ContextCompat.getColor(this, R.color.kavach_safe));
            btnActivate.setVisibility(View.GONE);
        } else {
            tvStatusTitle.setText("NOT PROTECTED");
            tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.kavach_scam));
            tvStatusSubtitle.setText("Missing permissions. Tap to activate protection.");
            statusCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.kavach_card_danger_tint));
            statusCard.setStrokeColor(ContextCompat.getColor(this, R.color.kavach_scam));
            btnActivate.setVisibility(View.VISIBLE);
        }
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

        updateHistoryVisibility();
    }

    private void updateHistoryVisibility() {
        if (TAB_STATS.equals(currentTab)) {
            recyclerView.setVisibility(View.GONE);
            tvEmptyState.setVisibility(View.GONE);
            tvHistoryLabel.setVisibility(View.GONE);
            tvHistoryHint.setVisibility(View.GONE);
            return;
        }

        tvHistoryLabel.setVisibility(View.VISIBLE);
        tvHistoryHint.setVisibility(View.VISIBLE);

        if (currentAlertCount > 0) {
            recyclerView.setVisibility(View.VISIBLE);
            tvEmptyState.setVisibility(View.GONE);
        } else {
            recyclerView.setVisibility(View.GONE);
            tvEmptyState.setVisibility(View.VISIBLE);
        }
    }

    private void selectTab(String tab) {
        currentTab = tab;
        styleBottomNav(tab);

        if (TAB_HOME.equals(tab)) {
            statusCard.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE);
            liveCard.setVisibility(View.VISIBLE);
            tvHistoryLabel.setText("Recent Alerts");
            tvHistoryHint.setText("Latest");
        } else if (TAB_HISTORY.equals(tab)) {
            statusCard.setVisibility(View.GONE);
            statsRow.setVisibility(View.GONE);
            liveCard.setVisibility(View.GONE);
            tvHistoryLabel.setText("Detection History");
            tvHistoryHint.setText("All Alerts");
        } else {
            statusCard.setVisibility(View.VISIBLE);
            statsRow.setVisibility(View.VISIBLE);
            liveCard.setVisibility(View.GONE);
            tvHistoryLabel.setText("Stats");
            tvHistoryHint.setText("");
        }

        updateHistoryVisibility();
    }

    private void styleBottomNav(String tab) {
        setNavState(navHome, TAB_HOME.equals(tab));
        setNavState(navHistory, TAB_HISTORY.equals(tab));
        setNavState(navStats, TAB_STATS.equals(tab));
        setNavState(navSettings, false);
    }

    private void setNavState(MaterialButton button, boolean selected) {
        int textColor = ContextCompat.getColor(this, selected ? R.color.kavach_text : R.color.kavach_muted);
        button.setBackgroundResource(selected ? R.drawable.bg_nav_pill_selected : R.drawable.bg_nav_pill_unselected);
        button.setTextColor(textColor);
        button.setIconTint(ColorStateList.valueOf(textColor));
    }

    private void updateMonitoringState(boolean active, long startMs) {
        isMonitoringActive = active;
        if (active) {
            callStartMs = startMs > 0L ? startMs : System.currentTimeMillis();
            tvLiveHeader.setText("LIVE - Monitoring Call");
            timerHandler.removeCallbacks(timerRunnable);
            timerHandler.post(timerRunnable);
        } else {
            callStartMs = 0L;
            tvLiveHeader.setText("LIVE - Idle");
            tvCallTimer.setText("00:00");
            timerHandler.removeCallbacks(timerRunnable);
            updateLiveCard(0, "SAFE", "", "");
        }
    }

    private void updateLiveCard(int score, String level, String transcript, String keywords) {
        int normalizedScore = Math.max(0, Math.min(score, 10));
        int levelColor = resolveRiskColor(normalizedScore, level);

        tvRiskBadge.setText(String.format(Locale.getDefault(), "Risk Score: %d/10", normalizedScore));
        tvRiskBadge.setTextColor(levelColor);
        progressRisk.setProgress(normalizedScore * 10, true);
        progressRisk.setProgressTintList(ColorStateList.valueOf(levelColor));

        if (transcript == null || transcript.isEmpty()) {
            tvLiveTranscript.setText("Transcript will appear once a live call starts.");
        } else {
            tvLiveTranscript.setText(transcript);
        }

        if (keywords == null || keywords.trim().isEmpty()) {
            tvLiveKeywords.setText("Keywords: none");
            tvLiveKeywords.setTextColor(ContextCompat.getColor(this, R.color.kavach_muted));
        } else {
            tvLiveKeywords.setText("Keywords: " + keywords);
            tvLiveKeywords.setTextColor(levelColor);
        }

        scamAlertCard.setVisibility(normalizedScore >= 10 ? View.VISIBLE : View.GONE);
    }

    private int resolveRiskColor(int score, String level) {
        String levelUpper = level == null ? "" : level.toUpperCase(Locale.getDefault());
        if (score >= 10 || levelUpper.contains("SCAM")) {
            return ContextCompat.getColor(this, R.color.kavach_scam);
        }
        if (score >= 6 || levelUpper.contains("SUSPICIOUS") || levelUpper.contains("HIGH RISK")) {
            return ContextCompat.getColor(this, R.color.kavach_warning);
        }
        return ContextCompat.getColor(this, R.color.kavach_safe);
    }

    private String formatElapsed(long totalSeconds) {
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }
}
