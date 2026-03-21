package com.kavachai;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PermissionOnboardingActivity extends AppCompatActivity {

    private static final int TOTAL_STEPS = 4;

    private int currentStep = 0;
    private ImageView ivIcon;
    private TextView tvStepCounter;
    private TextView tvTitle;
    private TextView tvDescription;
    private Button btnPrimary;
    private View[] stepSegments;

    private final int[] stepIcons = {
            android.R.drawable.ic_menu_call,
            android.R.drawable.ic_btn_speak_now,
            android.R.drawable.ic_dialog_info,
            android.R.drawable.ic_menu_close_clear_cancel
    };

    private final String[] stepTitles = {
            "Detect Calls",
            "Listen to Caller",
            "Send Alerts",
            "Block Scam Calls"
    };

    private final String[] stepDescriptions = {
            "Allow call state access so KavachAI can auto-start protection the moment a call begins.",
            "Allow microphone access during calls to analyze scam patterns in real time.",
            "Allow notifications so KavachAI can warn you instantly when risk is detected.",
            "Allow KavachAI to end the call automatically when a confirmed scam is detected. You can disable this in Settings."
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::handlePermissionResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_onboarding);

        ivIcon = findViewById(R.id.iv_icon);
        tvStepCounter = findViewById(R.id.tv_step_counter);
        tvTitle = findViewById(R.id.tv_title);
        tvDescription = findViewById(R.id.tv_description);
        btnPrimary = findViewById(R.id.btn_primary);
        Button btnSecondary = findViewById(R.id.btn_secondary);

        stepSegments = new View[]{
                findViewById(R.id.step_segment_1),
                findViewById(R.id.step_segment_2),
                findViewById(R.id.step_segment_3),
                findViewById(R.id.step_segment_4)
        };

        updateStepUI();

        btnPrimary.setOnClickListener(v -> {
            if (currentStep < TOTAL_STEPS - 1) {
                currentStep++;
                updateStepUI();
            } else {
                requestPermissions();
            }
        });

        btnSecondary.setOnClickListener(v -> showExplanationDialog());
    }

    private void updateStepUI() {
        View rootView = findViewById(R.id.root_layout);
        rootView.animate().alpha(0.75f).setDuration(120).withEndAction(() -> {
            ivIcon.setImageResource(stepIcons[currentStep]);
            tvStepCounter.setText("Step " + (currentStep + 1) + " of " + TOTAL_STEPS);
            tvTitle.setText(stepTitles[currentStep]);
            tvDescription.setText(stepDescriptions[currentStep]);

            for (int i = 0; i < stepSegments.length; i++) {
                stepSegments[i].setBackgroundResource(i <= currentStep
                        ? R.drawable.bg_segment_active
                        : R.drawable.bg_segment_inactive);
            }

            btnPrimary.setText(currentStep == TOTAL_STEPS - 1 ? "Grant All Permissions" : "Next");
            rootView.animate().alpha(1f).setDuration(120).start();
        }).start();
    }

    private void handlePermissionResult(Map<String, Boolean> result) {
        boolean allGranted = true;
        for (Map.Entry<String, Boolean> entry : result.entrySet()) {
            if (!entry.getValue()) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            completeOnboarding();
        } else {
            showPermissionDeniedDialog();
        }
    }

    private void requestPermissions() {
        permissionLauncher.launch(getRequiredPermissions());
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS);
        }
        return permissions.toArray(new String[0]);
    }

    private void completeOnboarding() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        prefs.edit()
                .putBoolean("onboarding_done", true)
                .putBoolean("auto_hangup_enabled", true)
                .apply();

        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void showExplanationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Why is this needed?")
                .setMessage("KavachAI only analyzes call audio in real time to detect scam patterns. No recordings are stored on your device or our servers. The call-ending permission is only used when a confirmed scam is detected — and only if you enable auto-hangup in Settings.")
                .setPositiveButton("I Understand", null)
                .show();
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("KavachAI needs these permissions to protect you. Please open app settings and grant all permissions.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
