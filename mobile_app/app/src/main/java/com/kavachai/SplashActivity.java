package com.kavachai;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DELAY_MS = 1800;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable navigationRunnable = this::checkNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        startIntroAnimations();
        handler.postDelayed(navigationRunnable, SPLASH_DELAY_MS);
    }

    private void startIntroAnimations() {
        MaterialCardView shieldGlow = findViewById(R.id.shield_glow);
        View shieldIcon = findViewById(R.id.textViewShield);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(shieldIcon, View.SCALE_X, 0.88f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(shieldIcon, View.SCALE_Y, 0.88f, 1f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(shieldIcon, View.ALPHA, 0.5f, 1f);

        AnimatorSet revealSet = new AnimatorSet();
        revealSet.playTogether(scaleX, scaleY, alpha);
        revealSet.setDuration(550);
        revealSet.start();

        ObjectAnimator pulse = ObjectAnimator.ofFloat(shieldGlow, View.ALPHA, 0.68f, 1f);
        pulse.setDuration(900);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    private void checkNavigation() {
        SharedPreferences prefs = getSharedPreferences("KavachAIPrefs", MODE_PRIVATE);
        boolean onboardingDone = prefs.getBoolean("onboarding_done", false);

        if (!onboardingDone || !hasAllPermissions()) {
            startActivity(new Intent(this, PermissionOnboardingActivity.class));
        } else {
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }

    private boolean hasAllPermissions() {
        String[] requiredPermissions = {
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS
        };

        for (String permission : requiredPermissions) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
                    && android.Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
                continue;
            }
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(navigationRunnable);
        super.onDestroy();
    }
}
