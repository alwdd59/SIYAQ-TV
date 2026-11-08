package com.siyaq.tv;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

public class OverlaySubtitleService extends Service {

    private WindowManager windowManager;
    private FrameLayout overlayContainer;
    private TextView subtitleTextView;
    private GradientDrawable boxBackground;
    private WebView hiddenMqttBridge;
    private WindowManager.LayoutParams windowParams;
    private String currentRoomCode = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra("ROOM_CODE")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        currentRoomCode = intent.getStringExtra("ROOM_CODE");
        setupFloatingSubtitleOverlay();
        setupRealtimeSyncBridge(currentRoomCode);

        if (intent.hasExtra("INITIAL_PAYLOAD")) {
            String initJson = intent.getStringExtra("INITIAL_PAYLOAD");
            if (initJson != null && !initJson.isEmpty()) {
                handlePayloadOnUiThread(initJson);
            }
        }
        return START_NOT_STICKY;
    }

    private void setupFloatingSubtitleOverlay() {
        if (overlayContainer != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        overlayContainer = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        boxBackground.setColor(Color.argb(225, 18, 18, 26));
        boxBackground.setCornerRadius(24f);
        boxBackground.setStroke(2, Color.argb(90, 0, 242, 254));
        overlayContainer.setBackground(boxBackground);

        int initPadV = (int) (14 * dm.density);
        int initPadH = (int) (24 * dm.density);
        overlayContainer.setPadding(initPadH, initPadV, initPadH, initPadV);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText("SIYAQ TV — متصل بالجوال وجاهز للترجمة الفورية");
        subtitleTextView.setTextColor(Color.WHITE);
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
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

    private void handlePayloadOnUiThread(String jsonStr) {
        if (subtitleTextView == null) return;
        subtitleTextView.post(() -> {
            try {
                JSONObject obj = new JSONObject(jsonStr);
                String type = obj.optString("type", "");

                // إغلاق الطبقة الشفافة فوراً عند إلغاء الاقتران أو حذف/إغلاق الصفحة في الجوال
                if ("UNPAIRED".equals(type)) {
                    sendBroadcast(new Intent("com.siyaq.tv.ACTION_UNPAIRED"));
                    stopSelf();
                    return;
                }

                if ("PAIRED".equals(type) || "SUB_UPDATE".equals(type)) {
                    String text = obj.optString("text", "");
                    if (!text.isEmpty()) {
                        subtitleTextView.setText(text);
                    }
                    JSONObject style = obj.optJSONObject("style");
                    if (style != null) {
                        applyFullSubtitleStyle(style);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private void applyFullSubtitleStyle(JSONObject style) {
        if (windowParams == null || overlayContainer == null || subtitleTextView == null) return;
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();

            // 1. نوع الخط وعرض الخط (عريض / عادي)
            String fontFamilyRaw = style.optString("fontFamily", "Tajawal");
            boolean isBold = style.optBoolean("isBold", true);
            int tfStyle = isBold ? Typeface.BOLD : Typeface.NORMAL;

            String androidFamily = "sans-serif";
            float letterSpacing = 0f;
            if (fontFamilyRaw.contains("Cairo")) {
                androidFamily = "sans-serif-medium";
                letterSpacing = 0.02f;
            } else if (fontFamilyRaw.contains("Changa")) {
                androidFamily = "sans-serif-condensed";
                letterSpacing = 0.04f;
            } else if (fontFamilyRaw.contains("Almarai")) {
                androidFamily = "serif";
                letterSpacing = 0.01f;
            }
            subtitleTextView.setTypeface(Typeface.create(androidFamily, tfStyle));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                subtitleTextView.setLetterSpacing(letterSpacing);
            }

            // 2. حجم الخط
            int fontSize = style.optInt("fontSize", 22);
            subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize * 1.15f);

            // 3. لون النص وشفافية النص
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

            // 4. لون الخلفية ودرجة التعتيم
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
            boxBackground.setStroke(bgAlpha > 15 ? 2 : 0, Color.argb(Math.min(110, bgAlpha), 0, 242, 254));

            // 5. عرض وارتفاع الشريط
            int bgWidthPct = Math.max(35, Math.min(100, style.optInt("bgWidth", 85)));
            int bgHeightPx = Math.max(4, Math.min(48, style.optInt("bgHeight", 14)));
            int newWidthPx = (int) (dm.widthPixels * (bgWidthPct / 100f));
            windowParams.width = newWidthPx;

            int padV = (int) (bgHeightPx * dm.density);
            int padH = (int) (24 * dm.density);
            overlayContainer.setPadding(padH, padV, padH, padV);

            // 6. تحريك موقع الترجمة أفقياً وعمودياً في الشاشة (posX و posY)
            int posX = Math.max(10, Math.min(90, style.optInt("posX", 50)));
            int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));

            int centerX = (int) (dm.widthPixels * (posX / 100f));
            int targetX = centerX - (newWidthPx / 2);
            targetX = Math.max(0, Math.min(dm.widthPixels - newWidthPx, targetX));

            int estBoxHeight = overlayContainer.getHeight() > 0 ? overlayContainer.getHeight() : (int) (72 * dm.density);
            int centerY = (int) (dm.heightPixels * (posY / 100f));
            int targetY = centerY - (estBoxHeight / 2);
            targetY = Math.max(0, Math.min(dm.heightPixels - estBoxHeight, targetY));

            windowParams.gravity = Gravity.TOP | Gravity.LEFT;
            windowParams.x = targetX;
            windowParams.y = targetY;

            windowManager.updateViewLayout(overlayContainer, windowParams);
        } catch (Exception ignored) {}
    }

    private void setupRealtimeSyncBridge(String roomCode) {
        if (hiddenMqttBridge != null) {
            hiddenMqttBridge.destroy();
        }
        hiddenMqttBridge = new WebView(this);
        hiddenMqttBridge.getSettings().setJavaScriptEnabled(true);
        hiddenMqttBridge.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onPayload(String jsonStr) {
                handlePayloadOnUiThread(jsonStr);
            }
        }, "AndroidTvBridge");

        String bridgeHtml = "<!DOCTYPE html><html><head>" +
                "<script src='https://unpkg.com/mqtt/dist/mqtt.min.js'></script>" +
                "</head><body><script>" +
                "const client = mqtt.connect('wss://broker.emqx.io:8084/mqtt', { keepalive: 20 });" +
                "client.on('connect', () => { client.subscribe('siyaq/tv/sec/" + roomCode + "'); });" +
                "client.on('message', (t, p) => { AndroidTvBridge.onPayload(p.toString()); });" +
                "</script></body></html>";

        hiddenMqttBridge.loadDataWithBaseURL("https://siyaq.app", bridgeHtml, "text/html", "UTF-8", null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayContainer != null && windowManager != null) {
            try {
                windowManager.removeView(overlayContainer);
            } catch (Exception ignored) {}
            overlayContainer = null;
        }
        if (hiddenMqttBridge != null) {
            hiddenMqttBridge.destroy();
            hiddenMqttBridge = null;
        }
    }
}
