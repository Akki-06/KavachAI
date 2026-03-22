# KavachAI ProGuard / R8 Rules

# ── OkHttp & WebSocket ──────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── AndroidX & Material ─────────────────────────────────────────────────────
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }

# ── App classes used via reflection ─────────────────────────────────────────
-keep class com.kavachai.CallReceiver { *; }
-keep class com.kavachai.BootReceiver { *; }
-keep class com.kavachai.CallAudioService { *; }

# ── JSON (org.json is part of Android, no rules needed) ─────────────────────

# ── Keep BuildConfig ─────────────────────────────────────────────────────────
-keep class com.kavachai.BuildConfig { *; }

# ── Suppress common warnings ─────────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
