package com.siyaq.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    private WindowManager windowManager;
    private FrameLayout overlayContainer;
    private TextView subtitleTextView;
    private GradientDrawable boxBackground;
    private WindowManager.LayoutParams windowParams;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int num = 10000000 + new Random().nextInt(90000000);
        roomCode = String.valueOf(num);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.parseColor("#090913"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 28, 48, 28);

        // 1. الشريط العلوي بهوية الهاتف
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 20);

        TextView brandTitle = new TextView(this);
        brandTitle.setText("SIYAQ TV");
        brandTitle.setTextColor(Color.parseColor("#00F2FE"));
        brandTitle.setTextSize(26);
        brandTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(brandTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusBadge = new TextView(this);
        statusBadge.setText("بانتظار اقتران الجوال...");
        statusBadge.setTextColor(Color.parseColor("#00F2FE"));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#131B2E"));
        badgeBg.setCornerRadius(30f);
        badgeBg.setStroke(2, Color.parseColor("#1E293B"));
        statusBadge.setBackground(badgeBg);
        statusBadge.setPadding(24, 10, 24, 10);
        statusBadge.setTextSize(13);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(statusBadge);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. البطاقة الوسطى البسيطة (الشعار + الكود الكامل + زر واحد سلس)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#111726"));
        cardBg.setCornerRadius(32f);
        cardBg.setStroke(2, Color.parseColor("#1E293B"));
        card.setBackground(cardBg);
        card.setPadding(36, 24, 36, 24);

        int qrBoxSize = Math.min(250, (int) (screenH * 0.44f));
        qrAndMqttWebView = new WebView(this);
        qrAndMqttWebView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings ws = qrAndMqttWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);

        qrAndMqttWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onSyncEvent(final String jsonStr) {
                if (codeView != null) {
                    codeView.post(() -> handleIncomingPhoneMessage(jsonStr));
                }
            }
        }, "SiyaqTvWaitBridge");

        card.addView(qrAndMqttWebView, new LinearLayout.LayoutParams(qrBoxSize, qrBoxSize));

        codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#00F2FE"));
        codeView.setTextSize(26);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 12, 0, 4);
        card.addView(codeView);

        statusHintView = new TextView(this);
        statusHintView.setText("امسح الرمز من موقع SIYAQ في جوالك — ستبدأ الطبقة الشفافة تلقائياً فور الاقتران");
        statusHintView.setTextColor(Color.parseColor("#94A3B8"));
        statusHintView.setTextSize(13);
        statusHintView.setGravity(Gravity.CENTER);
        statusHintView.setPadding(0, 0, 0, 14);
        card.addView(statusHintView);

        actionBtn = new Button(this);
        actionBtn.setFocusable(true);
        actionBtn.setFocusableInTouchMode(true);
        actionBtn.setBackgroundResource(R.drawable.tv_btn_focus_selector);
        actionBtn.setTextColor(getResources().getColorStateList(R.color.tv_btn_text_selector));
        actionBtn.setTextSize(14);
        actionBtn.setTypeface(Typeface.DEFAULT_BOLD);
        actionBtn.setPadding(36, 14, 36, 14);
        card.addView(actionBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int cardWidth = Math.min(720, (int) (dm.widthPixels * 0.78f));
        root.addView(card, new LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView.addView(root);
        setContentView(scrollView);

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
            actionBtn.setOnClickListener(v -> hideOverlayAndUnpair());
        } else {
            actionBtn.setText("تجديد رمز الاقتران");
            actionBtn.setOnClickListener(v -> {
                int num = 10000000 + new Random().nextInt(90000000);
                roomCode = String.valueOf(num);
                codeView.setText(roomCode);
                loadQrAndWaitBridge();
                Toast.makeText(this, "تم تجديد الرمز ✓", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void loadQrAndWaitBridge() {
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=240x240&margin=10&data=SIYAQ-TV-" + roomCode;
        String logoSvg = "<svg width='44' height='44' viewBox='0 0 100 100' style='margin-bottom:8px;'>" +
                "<path d='M27 10 H59 V20 H27 V40 H59 V56 H49 V49 H26 L17 40 V20 Z' fill='#FFFFFF'/>" +
                "<rect x='17' y='61' width='72' height='7.5' rx='2' fill='#00F2FE'/>" +
                "<path d='M17 75 H27 V85 H49 V75 H89 V84 H59 V87 L51 94 H25 L17 86 Z' fill='#FFFFFF'/>" +
                "</svg>";

        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'>" +
                "<style>" +
                "html,body{margin:0;padding:0;width:100%;height:100%;background:#111726;display:flex;flex-direction:column;align-items:center;justify-content:center;overflow:hidden;}" +
                ".qr-box{background:#FFFFFF;padding:10px;border-radius:18px;width:165px;height:165px;display:flex;align-items:center;justify-content:center;box-sizing:border-box;}" +
                "img{width:100%;height:100%;object-fit:contain;display:block;}" +
                "</style>" +
                "<script src='https://unpkg.com/mqtt/dist/mqtt.min.js'></script>" +
                "</head><body>" +
                logoSvg +
                "<div class='qr-box'><img src='" + qrUrl + "' alt='QR' /></div>" +
                "<script>" +
                "try {" +
                "  const client = mqtt.connect('wss://broker.emqx.io:8084/mqtt');" +
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

            if ("UNPAIRED".equals(type)) {
                hideOverlayAndUnpair();
                return;
            }

            if ("PAIRED".equals(type) || "SUB_UPDATE".equals(type)) {
                if (!isPhonePaired) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "يرجى تفعيل صلاحية الظهور فوق الألعاب أولاً", Toast.LENGTH_LONG).show();
                        updateActionButtonState();
                        return;
                    }
                    isPhonePaired = true;
                    showTransparentOverlayWindow();
                    updateUiForPairingState(true);
                    Toast.makeText(this, "تم اقتران الجوال وتفعيل شريط الترجمة الشفاف ✓", Toast.LENGTH_SHORT).show();
                    moveTaskToBack(true);
                }

                String text = obj.optString("text", "");
                if (!text.isEmpty() && subtitleTextView != null) {
                    subtitleTextView.setText(text);
                }
                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    applyFullSubtitleStyle(style);
                }
            }
        } catch (Exception ignored) {}
    }

    private void showTransparentOverlayWindow() {
        if (overlayContainer != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        overlayContainer = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        boxBackground.setColor(Color.argb(225, 18, 18, 26));
        boxBackground.setCornerRadius(24f);
        boxBackground.setStroke(2, Color.parseColor("#00F2FE"));
        overlayContainer.setBackground(boxBackground);
        overlayContainer.setPadding(36, 16, 36, 16);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText("SIYAQ TV — متصل بالجوال وجاهز للترجمة الفورية");
        subtitleTextView.setTextColor(Color.WHITE);
        subtitleTextView.setTextSize(24);
        subtitleTextView.setTypeface(Typeface.DEFAULT_BOLD);
        subtitleTextView.setGravity(Gravity.CENTER);

        overlayContainer.addView(subtitleTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int initialWidth = (int) (dm.widthPixels * 0.85f);
        windowParams = new WindowManager.LayoutParams(
                initialWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        windowParams.gravity = Gravity.TOP | Gravity.LEFT;
        windowParams.x = (dm.widthPixels - initialWidth) / 2;
        windowParams.y = (int) (dm.heightPixels * 0.78f);

        windowManager.addView(overlayContainer, windowParams);
    }

    private void applyFullSubtitleStyle(JSONObject style) {
        if (windowParams == null || overlayContainer == null || subtitleTextView == null) return;
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();

            String fontFamilyRaw = style.optString("fontFamily", "Tajawal");
            boolean isBold = style.optBoolean("isBold", true);
            int tfStyle = isBold ? Typeface.BOLD : Typeface.NORMAL;

            String androidFamily = "sans-serif";
            if (fontFamilyRaw.contains("Cairo")) {
                androidFamily = "sans-serif-medium";
            } else if (fontFamilyRaw.contains("Changa")) {
                androidFamily = "sans-serif-condensed";
            } else if (fontFamilyRaw.contains("Almarai")) {
                androidFamily = "serif";
            }
            subtitleTextView.setTypeface(Typeface.create(androidFamily, tfStyle));

            int fontSize = style.optInt("fontSize", 22);
            subtitleTextView.setTextSize(fontSize * 1.15f);

            String textColorStr = style.optString("textColor", "#FFFFFF");
            int textOpacity = style.optInt("textOpacity", 100);
            int parsedTextCol = Color.parseColor(textColorStr);
            int textAlpha = Math.max(30, Math.min(255, (int) (textOpacity * 2.55f)));
            subtitleTextView.setTextColor(Color.argb(
                    textAlpha,
                    Color.red(parsedTextCol),
                    Color.green(parsedTextCol),
                    Color.blue(parsedTextCol)
            ));

            String bgColorStr = style.optString("bgColor", "#12121A");
            int bgOpacity = style.optInt("bgOpacity", 90);
            int parsedBgCol = Color.parseColor(bgColorStr);
            int bgAlpha = Math.max(0, Math.min(255, (int) (bgOpacity * 2.55f)));
            boxBackground.setColor(Color.argb(
                    bgAlpha,
                    Color.red(parsedBgCol),
                    Color.green(parsedBgCol),
                    Color.blue(parsedBgCol)
            ));

            int bgWidthPct = Math.max(35, Math.min(100, style.optInt("bgWidth", 85)));
            int bgHeightPx = Math.max(4, Math.min(48, style.optInt("bgHeight", 14)));
            int newWidthPx = (int) (dm.widthPixels * (bgWidthPct / 100f));
            windowParams.width = newWidthPx;

            int padV = (int) (bgHeightPx * dm.density);
            int padH = (int) (24 * dm.density);
            overlayContainer.setPadding(padH, padV, padH, padV);

            int posX = Math.max(10, Math.min(90, style.optInt("posX", 50)));
            int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));

            int centerX = (int) (dm.widthPixels * (posX / 100f));
            int targetX = Math.max(0, Math.min(dm.widthPixels - newWidthPx, centerX - (newWidthPx / 2)));

            int estBoxHeight = (int) (72 * dm.density);
            int centerY = (int) (dm.heightPixels * (posY / 100f));
            int targetY = Math.max(0, Math.min(dm.heightPixels - estBoxHeight, centerY - (estBoxHeight / 2)));

            windowParams.gravity = Gravity.TOP | Gravity.LEFT;
            windowParams.x = targetX;
            windowParams.y = targetY;

            windowManager.updateViewLayout(overlayContainer, windowParams);
        } catch (Exception ignored) {}
    }

    private void hideOverlayAndUnpair() {
        isPhonePaired = false;
        if (overlayContainer != null && windowManager != null) {
            try {
                windowManager.removeView(overlayContainer);
            } catch (Exception ignored) {}
            overlayContainer = null;
        }
        updateUiForPairingState(false);
        Toast.makeText(this, "توقفت الطبقة الشفافة وتم إلغاء الاقتران ✓", Toast.LENGTH_SHORT).show();
    }

    private void updateUiForPairingState(boolean paired) {
        if (paired) {
            statusBadge.setText("● متصل بالجوال — الطبقة الشفافة نشطة");
            statusBadge.setTextColor(Color.parseColor("#34D399"));
            statusHintView.setText("تم الاقتران بنجاح! يمكنك الآن العودة لمنفذ اللعبة (HDMI)");
        } else {
            statusBadge.setText("بانتظار اقتران الجوال...");
            statusBadge.setTextColor(Color.parseColor("#00F2FE"));
            statusHintView.setText("امسح الرمز من موقع SIYAQ في جوالك — ستبدأ الطبقة الشفافة تلقائياً فور الاقتران");
        }
        updateActionButtonState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateActionButtonState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideOverlayAndUnpair();
        if (qrAndMqttWebView != null) {
            qrAndMqttWebView.destroy();
        }
    }
}
