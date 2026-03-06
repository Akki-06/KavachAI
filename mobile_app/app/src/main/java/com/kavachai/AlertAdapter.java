package com.kavachai;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlertAdapter extends RecyclerView.Adapter<AlertAdapter.AlertViewHolder> {

    private JSONArray alertsArray = new JSONArray();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault());

    public void setAlerts(JSONArray alerts) {
        this.alertsArray = alerts == null ? new JSONArray() : alerts;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AlertViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_alert, parent, false);
        return new AlertViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AlertViewHolder holder, int position) {
        try {
            JSONObject alert = alertsArray.getJSONObject(position);
            String level = sanitizeLevel(alert.optString("level", "SCAM DETECTED"));
            int levelColor = getLevelColor(level);
            int riskScore = normalizeRiskScore(alert.optInt("risk_score", inferScoreFromLevel(level)));

            holder.tvLevel.setText(level);
            holder.tvLevel.setTextColor(levelColor);
            holder.tvRiskScore.setText(riskScore + "/10");
            holder.tvRiskScore.setTextColor(levelColor);
            holder.riskStrip.setBackgroundColor(levelColor);

            String transcript = alert.optString("transcript", "No transcript available");
            holder.tvTranscript.setText(transcript);

            String words = alert.optString("keywords", "").trim();
            if (words.isEmpty()) {
                holder.tvKeywords.setVisibility(View.GONE);
            } else {
                holder.tvKeywords.setVisibility(View.VISIBLE);
                holder.tvKeywords.setText("Keywords: " + words);
            }

            long timestamp = alert.optLong("timestamp", System.currentTimeMillis());
            holder.tvTime.setText(dateFormat.format(new Date(timestamp)));

            ((MaterialCardView) holder.itemView).setStrokeColor(Color.parseColor("#1F1F30"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int getItemCount() {
        return alertsArray == null ? 0 : alertsArray.length();
    }

    private String sanitizeLevel(String rawLevel) {
        String level = rawLevel == null ? "" : rawLevel;
        level = level.replaceAll("\\u001B\\[[;\\d]*m", "").trim();
        if (level.isEmpty()) {
            return "SCAM DETECTED";
        }
        return level.toUpperCase(Locale.getDefault());
    }

    private int getLevelColor(String level) {
        if (level.contains("SAFE")) {
            return Color.parseColor("#00D68F");
        }
        if (level.contains("SUSPICIOUS") || level.contains("HIGH RISK") || level.contains("WARNING")) {
            return Color.parseColor("#FFB547");
        }
        return Color.parseColor("#FF2442");
    }

    private int inferScoreFromLevel(String level) {
        if (level.contains("SAFE")) {
            return 2;
        }
        if (level.contains("SUSPICIOUS")) {
            return 5;
        }
        if (level.contains("HIGH RISK")) {
            return 8;
        }
        return 10;
    }

    private int normalizeRiskScore(int score) {
        return Math.max(0, Math.min(score, 10));
    }

    static class AlertViewHolder extends RecyclerView.ViewHolder {
        View riskStrip;
        TextView tvLevel;
        TextView tvTime;
        TextView tvTranscript;
        TextView tvKeywords;
        TextView tvRiskScore;

        AlertViewHolder(@NonNull View itemView) {
            super(itemView);
            riskStrip = itemView.findViewById(R.id.view_risk_strip);
            tvLevel = itemView.findViewById(R.id.tv_alert_level);
            tvTime = itemView.findViewById(R.id.tv_alert_time);
            tvTranscript = itemView.findViewById(R.id.tv_transcript);
            tvKeywords = itemView.findViewById(R.id.tv_keywords);
            tvRiskScore = itemView.findViewById(R.id.tv_risk_score);
        }
    }
}
