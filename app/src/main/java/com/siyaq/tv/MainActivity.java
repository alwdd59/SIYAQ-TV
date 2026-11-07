package com.siyaq.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

public class MainActivity extends Activity {

    private String roomCode;
    private TextView statusLabel;
    private TextView codeView;
    private WebView qrWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int num = 10000000 + new Random().nextInt(90000000);
        roomCode = String.valueOf(num);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenHeight = dm.heightPixels;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.parseColor("#060A14"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 32, 48, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, 24);

        TextView brand = new TextView(this);
        brand.setText("SIYAQ TV");
        brand.setTextColor(Color.parseColor("#00F2FE"));
        brand.setTextSize(28);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBadge = new TextView(this);
        tvBadge.setText("جاهز للاقتران بالآيفون");
        tvBadge.setTextColor(Color.parseColor("#00F2FE"));
        tvBadge.setBackgroundColor(Color.parseColor("#142238"));
        tvBadge.setPadding(20, 8, 20, 8);
        tvBadge.setTextSize(13);
        tvBadge.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(tvBadge);

        root.addView(headerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout centerRow = new LinearLayout(this);
        centerRow.setOrientation(LinearLayout.HORIZONTAL);
        centerRow.setGravity(Gravity.CENTER);
        centerRow.setPadding(0, 12, 0, 16);

        LinearLayout qrBox = new LinearLayout(this);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER);
        qrBox.setPadding(24, 16, 24, 16);

        qrWebView = new WebView(this);
        WebSettings ws = qrWebView.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=SIYAQ-TV-" + roomCode;
        qrWebView.loadDataWithBaseURL(null,
                "<html><body style='margin:0;padding:0;background:#fff;display:flex;align-items:center;justify-content:center;height:100vh;'>" +
                "<img src='" + qrUrl + "' style='width:90%;height:90%;object-fit:contain;'/></body></html>",
                "text/html", "UTF-8", null);

        int qrSizePx = Math.min(220, (int) (screenHeight * 0.38));
        qrBox.addView(qrWebView, new LinearLayout.LayoutParams(qrSizePx, qrSizePx));

        codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#00F2FE"));
        codeView.setTextSize(24);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setPadding(0, 12, 0, 4);
        codeView.setGravity(Gravity.CENTER);
        qrBox.addView(codeView);

        statusLabel = new TextView(this);
        statusLabel.setText("بانتظار مسح الكود من موقعك بالآيفون");
        statusLabel.setTextColor(Color.parseColor("#94A3B8"));
        statusLabel.setTextSize(12);
        statusLabel.setGravity(Gravity.CENTER);
        qrBox.addView(statusLabel);

        centerRow.addView(qrBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));

        LinearLayout menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        menuBox.setGravity(Gravity.CENTER_VERTICAL);
        menuBox.setPadding(20, 10, 20, 10);

        Button btnStartOverlay = createTvRemoteButton("1. بدء شريط الترجمة الشفاف والعودة للعبة (HDMI)", v -> {
            startTransparentOverlayService(true);
        });
        btnStartOverlay.requestFocus();
        menuBox.addView(btnStartOverlay);

        Button btnPermission = createTvRemoteButton("2. منح صلاحية الظهور فوق الألعاب (Display Over Apps)", v -> {
            requestOverlayPermission();
        });
        menuBox.addView(btnPermission);

        Button btnStopOverlay = createTvRemoteButton("3. إيقاف شريط الترجمة الشفاف", v -> {
            stopService(new Intent(this, OverlaySubtitleService.class));
            Toast.makeText(this, "تم إيقاف الشريط الشفاف ✓", Toast.LENGTH_SHORT).show();
        });
        menuBox.addView(btnStopOverlay);

        Button btnNewCode = createTvRemoteButton("4. تجديد رمز الاقتران (كود جديد)", v -> {
            regenerateNewRoomCode();
        });
        menuBox.addView(btnNewCode);

        centerRow.addView(menuBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        root.addView(centerRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        scrollView.addView(root);
        setContentView(scrollView);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startTransparentOverlayService(false);
        }
    }

    private Button createTvRemoteButton(String title, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(title);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        b.setPadding(28, 16, 28, 16);

        GradientDrawable defaultBg = new GradientDrawable();
        defaultBg.setColor(Color.parseColor("#141E34"));
        defaultBg.setCornerRadius(16f);
        defaultBg.setStroke(2, Color.parseColor("#243456"));

        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setColor(Color.parseColor("#00F2FE"));
        focusBg.setCornerRadius(16f);
        focusBg.setStroke(3, Color.WHITE);

        b.setBackground(defaultBg);
        b.setTextColor(Color.WHITE);

        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                b.setBackground(focusBg);
                b.setTextColor(Color.parseColor("#060A14"));
            } else {
                b.setBackground(defaultBg);
                b.setTextColor(Color.WHITE);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 6, 0, 6);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private void regenerateNewRoomCode() {
        int num = 10000000 + new Random().nextInt(90000000);
        roomCode = String.valueOf(num);
        codeView.setText(roomCode);
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=SIYAQ-TV-" + roomCode;
        qrWebView.loadDataWithBaseURL(null,
                "<html><body style='margin:0;padding:0;background:#fff;display:flex;align-items:center;justify-content:center;height:100vh;'>" +
                "<img src='" + qrUrl + "' style='width:90%;height:90%;object-fit:contain;'/></body></html>",
                "text/html", "UTF-8", null);
        startTransparentOverlayService(false);
        Toast.makeText(this, "تم تجديد رمز الاقتران ✓", Toast.LENGTH_SHORT).show();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 101);
        } else {
            Toast.makeText(this, "صلاحية الظهور فوق التطبيقات مفعلة بالفعل ✓", Toast.LENGTH_SHORT).show();
            startTransparentOverlayService(true);
        }
    }

    private void startTransparentOverlayService(boolean minimizeToGame) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        Intent svc = new Intent(this, OverlaySubtitleService.class);
        svc.putExtra("ROOM_CODE", roomCode);
        startService(svc);
        Toast.makeText(this, "شريط الترجمة الشفاف نشط الآن فوق التلفاز ✓", Toast.LENGTH_SHORT).show();
        if (minimizeToGame) {
            moveTaskToBack(true);
        }
    }
}
