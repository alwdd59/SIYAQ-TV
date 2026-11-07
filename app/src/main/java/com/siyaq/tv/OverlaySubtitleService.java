package com.siyaq.tv;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
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
    private String currentRoomCode = "84920174";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("ROOM_CODE")) {
            currentRoomCode = intent.getStringExtra("ROOM_CODE");
        }
        setupFloatingSubtitleOverlay();
        setupRealtimeSyncBridge(currentRoomCode);
        return START_STICKY;
    }

    private void setupFloatingSubtitleOverlay() {
        if (overlayContainer != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayContainer = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        boxBackground.setColor(Color.argb(225, 18, 18, 26));
        boxBackground.setCornerRadius(28f);
        overlayContainer.setBackground(boxBackground);
        overlayContainer.setPadding(36, 22, 36, 22);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText("اختبار ترجمة التلفاز العربية: ضوء، رؤية، حرية، أمل");
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

        // نافذة شفافة غير قابلة للمس (FLAG_NOT_TOUCHABLE & FLAG_NOT_FOCUSable) حتى لا تعطل ريموت التلفاز أو يد البلايستيشن إطلاقاً!
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.82),
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 95;

        windowManager.addView(overlayContainer, params);
    }

    // جسر استقبال الترجمة والتخصيص الحي من موقع SIYAQ في جوال الآيفون عبر نفس قناة MQTT المشفرة
    private void setupRealtimeSyncBridge(String roomCode) {
        if (hiddenMqttBridge != null) {
            hiddenMqttBridge.destroy();
        }
        hiddenMqttBridge = new WebView(this);
        hiddenMqttBridge.getSettings().setJavaScriptEnabled(true);
        hiddenMqttBridge.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onPayload(String jsonStr) {
                try {
                    JSONObject obj = new JSONObject(jsonStr);
                    String type = obj.optString("type", "");
                    if ("SUB_UPDATE".equals(type)) {
                        final String text = obj.optString("text", "");
                        final JSONObject style = obj.optJSONObject("style");
                        subtitleTextView.post(() -> {
                            if (!text.isEmpty()) {
                                subtitleTextView.setText(text);
                            }
                            if (style != null) {
                                int fontSize = style.optInt("fontSize", 24);
                                String textColor = style.optString("textColor", "#FFFFFF");
                                String bgColor = style.optString("bgColor", "#12121A");
                                int bgOpacity = style.optInt("bgOpacity", 90);
                                subtitleTextView.setTextSize(fontSize * 1.15f);
                                try {
                                    subtitleTextView.setTextColor(Color.parseColor(textColor));
                                    int parsedBg = Color.parseColor(bgColor);
                                    int alpha = Math.max(0, Math.min(255, (int) (bgOpacity * 2.55f)));
                                    boxBackground.setColor(Color.argb(alpha, Color.red(parsedBg), Color.green(parsedBg), Color.blue(parsedBg)));
                                } catch (Exception ignored) {}
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }
        }, "AndroidTvBridge");

        String bridgeHtml = "<!DOCTYPE html><html><head>" +
                "<script src='https://unpkg.com/mqtt/dist/mqtt.min.js'></script>" +
                "</head><body><script>" +
                "const client = mqtt.connect('wss://broker.emqx.io:8084/mqtt');" +
                "client.on('connect', () => { client.subscribe('siyaq/tv/sec/" + roomCode + "'); });" +
                "client.on('message', (t, p) => { AndroidTvBridge.onPayload(p.toString()); });" +
                "</script></body></html>";

        hiddenMqttBridge.loadDataWithBaseURL("https://siyaq.app", bridgeHtml, "text/html", "UTF-8", null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayContainer != null && windowManager != null) {
            windowManager.removeView(overlayContainer);
            overlayContainer = null;
        }
        if (hiddenMqttBridge != null) {
            hiddenMqttBridge.destroy();
        }
    }
}
