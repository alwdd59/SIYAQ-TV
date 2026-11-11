package com.siyaq.tv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Random;

public class MainActivity extends Activity {

    private String roomCode;
    private TextView statusBadge;
    private TextView statusHintView;
    private TextView codeView;
    private WebView qrAndMqttWebView;
    private Button actionBtn;
    private boolean isPhonePaired = false;

    private final BroadcastReceiver unpairReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            isPhonePaired = false;
            updateUiForPairingState(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int num = 10000000 + new Random().nextInt(90000000);
        roomCode = String.valueOf(num);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;

        // الخلفية السوداء المطفية الجديدة (#050507) المتطابقة مع هوية الجوال الجديدة
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#050507"));
        root.setGravity(Gravity.CENTER);
        int padH = (int) (dm.widthPixels * 0.06f);
        int padV = (int) (screenH * 0.05f);
        root.setPadding(padH, padV, padH, padV);

        // 1. الشريط العلوي: شعار SIYAQ باللون البرتقالي الذهبي الجديد (#F5A623) + شارة الحالة
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 26);

        LinearLayout brandLogoWrap = new LinearLayout(this);
        brandLogoWrap.setOrientation(LinearLayout.HORIZONTAL);
        brandLogoWrap.setGravity(Gravity.CENTER_VERTICAL);

        TextView logoS = new TextView(this);
        logoS.setText("S");
        logoS.setTextColor(Color.WHITE);
        logoS.setTextSize(28);
        logoS.setTypeface(Typeface.DEFAULT_BOLD);
        brandLogoWrap.addView(logoS);

        TextView logoI = new TextView(this);
        logoI.setText("I");
        logoI.setTextColor(Color.parseColor("#F5A623"));
        logoI.setTextSize(28);
        logoI.setTypeface(Typeface.DEFAULT_BOLD);
        logoI.setPadding(3, 0, 3, 0);
        brandLogoWrap.addView(logoI);

        TextView logoYAQ = new TextView(this);
        logoYAQ.setText("YAQ");
        logoYAQ.setTextColor(Color.WHITE);
        logoYAQ.setTextSize(28);
        logoYAQ.setTypeface(Typeface.DEFAULT_BOLD);
        brandLogoWrap.addView(logoYAQ);

        header.addView(brandLogoWrap);

        TextView tvSubTag = new TextView(this);
        tvSubTag.setText("  TV");
        tvSubTag.setTextColor(Color.parseColor("#F5A623"));
        tvSubTag.setTextSize(16);
        tvSubTag.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvSubTag, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusBadge = new TextView(this);
        statusBadge.setText("بانتظار اقتران الجوال...");
        statusBadge.setTextColor(Color.parseColor("#F5A623"));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#0C0C10"));
        badgeBg.setCornerRadius(40f);
        badgeBg.setStroke(2, Color.parseColor("#1C1C24"));
        statusBadge.setBackground(badgeBg);
        statusBadge.setPadding(26, 12, 26, 12);
        statusBadge.setTextSize(13);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(statusBadge);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. بطاقة الاقتران الوسطى بتباعد مريح بين الرمز والكود والأزرار
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#0C0C10"));
        cardBg.setCornerRadius(32f);
        cardBg.setStroke(2, Color.parseColor("#1C1C24"));
        card.setBackground(cardBg);
        card.setPadding(44, 36, 44, 36);

        // حاوية الباركود البيضاء بحجم متناسق
        int qrBoxSize = Math.min(235, (int) (screenH * 0.38f));
        LinearLayout qrFrame = new LinearLayout(this);
        qrFrame.setGravity(Gravity.CENTER);
        GradientDrawable qrWhiteBg = new GradientDrawable();
        qrWhiteBg.setColor(Color.WHITE);
        qrWhiteBg.setCornerRadius(24f);
        qrFrame.setBackground(qrWhiteBg);
        qrFrame.setPadding(14, 14, 14, 14);

        qrAndMqttWebView = new WebView(this);
        qrAndMqttWebView.setVerticalScrollBarEnabled(false);
        qrAndMqttWebView.setHorizontalScrollBarEnabled(false);
        WebSettings ws = qrAndMqttWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);

        qrAndMqttWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onSyncEvent(String jsonStr) {
                runOnUiThread(() -> handleIncomingPhoneMessage(jsonStr));
            }
        }, "SiyaqTvWaitBridge");

        int innerQrSize = qrBoxSize - 28;
        qrFrame.addView(qrAndMqttWebView, new LinearLayout.LayoutParams(innerQrSize, innerQrSize));
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrBoxSize, qrBoxSize);
        qrLp.setMargins(0, 0, 0, 24);
        card.addView(qrFrame, qrLp);

        // رقم الكود السري باللون البرتقالي الذهبي (#F5A623) مع مسافة واضحة ومريحة فوقه وتحته
        codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#F5A623"));
        codeView.setTextSize(28);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 8, 0, 14);
        card.addView(codeView);

        statusHintView = new TextView(this);
        statusHintView.setText("امسح الرمز من موقع SIYAQ في جوالك — ستبدأ الطبقة الشفافة تلقائياً فور الاقتران");
        statusHintView.setTextColor(Color.parseColor("#94A3B8"));
        statusHintView.setTextSize(13);
        statusHintView.setGravity(Gravity.CENTER);
        statusHintView.setPadding(0, 0, 0, 26);
        card.addView(statusHintView);

        // زر التحكم بالريموت مبرمج بالكامل داخل الكود (بدون الحاجة لأي ملفات XML إضافية)
        actionBtn = new Button(this);
        actionBtn.setFocusable(true);
        actionBtn.setFocusableInTouchMode(true);

        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setShape(GradientDrawable.RECTANGLE);
        focusedBg.setColor(Color.parseColor("#F5A623"));
        focusedBg.setCornerRadius(28f);
        focusedBg.setStroke(3, Color.WHITE);

        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setShape(GradientDrawable.RECTANGLE);
        pressedBg.setColor(Color.parseColor("#D98200"));
        pressedBg.setCornerRadius(28f);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setShape(GradientDrawable.RECTANGLE);
        normalBg.setColor(Color.parseColor("#14141C"));
        normalBg.setCornerRadius(28f);
        normalBg.setStroke(2, Color.parseColor("#282836"));

        StateListDrawable btnStates = new StateListDrawable();
        btnStates.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        btnStates.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        btnStates.addState(new int[]{}, normalBg);
        actionBtn.setBackground(btnStates);

        ColorStateList textStates = new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{
                        Color.parseColor("#050507"),
                        Color.parseColor("#050507"),
                        Color.WHITE
                }
        );
        actionBtn.setTextColor(textStates);
        actionBtn.setTextSize(14);
        actionBtn.setTypeface(Typeface.DEFAULT_BOLD);
        actionBtn.setPadding(42, 16, 42, 16);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(0, 6, 0, 0);
        card.addView(actionBtn, btnLp);

        int cardWidth = Math.min(760, (int) (dm.widthPixels * 0.78f));
        root.addView(card, new LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unpairReceiver, new IntentFilter("com.siyaq.tv.ACTION_UNPAIRED"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(unpairReceiver, new IntentFilter("com.siyaq.tv.ACTION_UNPAIRED"));
        }

        updateActionButtonState();
        actionBtn.requestFocus();
        loadQrAndWaitBridge();
    }

    private void updateActionButtonState() {
        boolean hasOverlayPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        if (!hasOverlayPerm) {
            actionBtn.setText("تفعيل صلاحية الظهور فوق الألعاب (لمرة واحدة)");
            actionBtn.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 101);
            });
        } else if (isPhonePaired) {
            actionBtn.setText("إيقاف الطبقة الشفافة وإلغاء الاقتران");
            actionBtn.setOnClickListener(v -> stopOverlayAndUnpair());
        } else {
            actionBtn.setText("تجديد رمز الاقتران");
            actionBtn.setOnClickListener(v -> {
                int num = 10000000 + new Random().nextInt(90000000);
                roomCode = String.valueOf(num);
                codeView.setText(roomCode);
                loadQrAndWaitBridge();
                Toast.makeText(this, "تم تجديد الرمز", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void loadQrAndWaitBridge() {
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=280x280&margin=10&data=SIYAQ-TV-" + roomCode;
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'>" +
                "<style>" +
                "html,body{margin:0;padding:0;width:100%;height:100%;background:#FFFFFF;display:flex;align-items:center;justify-content:center;overflow:hidden;}" +
                "img{width:92%;height:92%;object-fit:contain;display:block;}" +
                "</style>" +
                "<script src='https://unpkg.com/mqtt/dist/mqtt.min.js'></script>" +
                "</head><body>" +
                "<img src='" + qrUrl + "' alt='QR' />" +
                "<script>" +
                "try {" +
                "  const client = mqtt.connect('wss://broker.emqx.io:8084/mqtt', { keepalive: 20 });" +
                "  client.on('connect', () => { client.subscribe('siyaq/tv/sec/" + roomCode + "'); });" +
                "  client.on('message', (t, p) => { SiyaqTvWaitBridge.onSyncEvent(p.toString()); });" +
                "} catch(e) {}" +
                "</script></body></html>";
        qrAndMqttWebView.loadDataWithBaseURL("https://siyaq.app", html, "text/html", "UTF-8", null);
    }

    private void handleIncomingPhoneMessage(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String type = obj.optString("type", "");

            if ("PAIRED".equals(type) || "SUB_UPDATE".equals(type)) {
                if (!isPhonePaired) {
                    isPhonePaired = true;
                    updateUiForPairingState(true);
                    startOverlayServiceOnPair(jsonStr);
                }
            } else if ("UNPAIRED".equals(type)) {
                stopOverlayAndUnpair();
            }
        } catch (Exception ignored) {}
    }

    private void updateUiForPairingState(boolean paired) {
        if (paired) {
            statusBadge.setText("● متصل بالجوال — الطبقة الشفافة نشطة");
            statusBadge.setTextColor(Color.parseColor("#34D399"));
            statusHintView.setText("تم الاقتران بنجاح! يمكنك الآن العودة لمنفذ اللعبة (HDMI)");
        } else {
            statusBadge.setText("بانتظار اقتران الجوال...");
            statusBadge.setTextColor(Color.parseColor("#F5A623"));
            statusHintView.setText("امسح الرمز من موقع SIYAQ في جوالك — ستبدأ الطبقة الشفافة تلقائياً فور الاقتران");
        }
        updateActionButtonState();
    }

    private void startOverlayServiceOnPair(String initialPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "يرجى الموافقة على صلاحية الظهور فوق التطبيقات لتشغيل شريط الترجمة", Toast.LENGTH_LONG).show();
            updateActionButtonState();
            return;
        }
        Intent svc = new Intent(this, OverlaySubtitleService.class);
        svc.putExtra("ROOM_CODE", roomCode);
        svc.putExtra("INITIAL_PAYLOAD", initialPayload);
        startService(svc);
        Toast.makeText(this, "تم اقتران الجوال وتفعيل شريط الترجمة الشفاف", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void stopOverlayAndUnpair() {
        isPhonePaired = false;
        stopService(new Intent(this, OverlaySubtitleService.class));
        updateUiForPairingState(false);
        Toast.makeText(this, "توقفت الطبقة الشفافة وتم إلغاء الاقتران", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateActionButtonState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(unpairReceiver);
        } catch (Exception ignored) {}
        if (qrAndMqttWebView != null) {
            qrAndMqttWebView.destroy();
        }
    }
}
