package com.siyaq.tv;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlaySubtitleService extends Service {

    private WindowManager windowManager;
    private FrameLayout overlayContainer;
    private TextView subtitleTextView;
    private GradientDrawable boxBackground;
    private WebView hiddenMqttBridge;
    private WindowManager.LayoutParams windowParams;
    private String currentRoomCode = "";
    private JSONObject lastStyleJson = null;

    // ذاكرة تخزين الخطوط العربية الحقيقية (Tajawal, Cairo, Changa, Almarai) لتعمل على التلفاز بنفس شكل الجوال 100%
    private final Map<String, Typeface> customFontCache = new HashMap<>();
    private final ExecutorService fontDownloadExecutor = Executors.newSingleThreadExecutor();

    private static final String[][] GOOGLE_ARABIC_FONTS = new String[][]{
            {"Tajawal-Regular", "https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal/Tajawal-Regular.ttf"},
            {"Tajawal-Bold", "https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal/Tajawal-Bold.ttf"},
            {"Cairo-Regular", "https://raw.githubusercontent.com/google/fonts/main/ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf"},
            {"Cairo-Bold", "https://raw.githubusercontent.com/google/fonts/main/ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf"},
            {"Changa-Regular", "https://raw.githubusercontent.com/google/fonts/main/ofl/changa/Changa%5Bwght%5D.ttf"},
            {"Changa-Bold", "https://raw.githubusercontent.com/google/fonts/main/ofl/changa/Changa%5Bwght%5D.ttf"},
            {"Almarai-Regular", "https://raw.githubusercontent.com/google/fonts/main/ofl/almarai/Almarai-Regular.ttf"},
            {"Almarai-Bold", "https://raw.githubusercontent.com/google/fonts/main/ofl/almarai/Almarai-Bold.ttf"}
    };

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
        preloadArabicGoogleFontsAsync();
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

    private void preloadArabicGoogleFontsAsync() {
        fontDownloadExecutor.execute(() -> {
            File fontDir = new File(getFilesDir(), "siyaq_fonts");
            if (!fontDir.exists()) {
                fontDir.mkdirs();
            }
            boolean anyNewLoaded = false;
            for (String[] entry : GOOGLE_ARABIC_FONTS) {
                String key = entry[0];
                String urlStr = entry[1];
                if (customFontCache.containsKey(key)) continue;

                File outFile = new File(fontDir, key + ".ttf");
                if (!outFile.exists() || outFile.length() < 1000) {
                    try {
                        // جلب رابط ملف TTF الثابت المباشر من خوادم Google Fonts لضمان التوافق 100% مع جميع شاشات Android TV
                        String famName = key.split("-")[0];
                        String weight = key.endsWith("Bold") ? "700" : "400";
                        String cssUrl = "https://fonts.googleapis.com/css?family=" + famName + ":" + weight;
                        HttpURLConnection cssConn = (HttpURLConnection) new URL(cssUrl).openConnection();
                        cssConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us)");
                        cssConn.setConnectTimeout(4000);
                        cssConn.setReadTimeout(5000);
                        String directTtfUrl = urlStr;
                        if (cssConn.getResponseCode() == 200) {
                            try (InputStream cis = cssConn.getInputStream()) {
                                byte[] cbuf = new byte[4096];
                                int cn = cis.read(cbuf);
                                if (cn > 0) {
                                    String cssText = new String(cbuf, 0, cn, "UTF-8");
                                    int idx = cssText.indexOf("https://fonts.gstatic.com/");
                                    if (idx != -1) {
                                        int endIdx = cssText.indexOf(")", idx);
                                        if (endIdx != -1) {
                                            directTtfUrl = cssText.substring(idx, endIdx).replace("\"", "").replace("'", "").trim();
                                        }
                                    }
                                }
                            }
                        }
                        cssConn.disconnect();

                        URL url = new URL(directTtfUrl);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(7000);
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            try (InputStream in = conn.getInputStream();
                                 FileOutputStream out = new FileOutputStream(outFile)) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                }
                            }
                        }
                        conn.disconnect();
                    } catch (Exception ignored) {}
                }

                if (outFile.exists() && outFile.length() > 1000) {
                    try {
                        Typeface tf = Typeface.createFromFile(outFile);
                        if (tf != null) {
                            synchronized (customFontCache) {
                                customFontCache.put(key, tf);
                            }
                            anyNewLoaded = true;
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (anyNewLoaded && subtitleTextView != null && lastStyleJson != null) {
                subtitleTextView.post(() -> applyFullSubtitleStyle(lastStyleJson));
            }
        });
    }

    private Typeface resolveRequestedArabicTypeface(String fontFamilyRaw, boolean isBold) {
        String familyBase = "Tajawal";
        if (fontFamilyRaw != null) {
            if (fontFamilyRaw.contains("Cairo")) familyBase = "Cairo";
            else if (fontFamilyRaw.contains("Changa")) familyBase = "Changa";
            else if (fontFamilyRaw.contains("Almarai")) familyBase = "Almarai";
        }
        String key = familyBase + (isBold ? "-Bold" : "-Regular");
        synchronized (customFontCache) {
            Typeface cached = customFontCache.get(key);
            if (cached != null) {
                return Typeface.create(cached, isBold ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        return Typeface.create("sans-serif", isBold ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void setupFloatingSubtitleOverlay() {
        if (overlayContainer != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        overlayContainer = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        // خلفية سوداء صافية (#000000) بحدود مربعة حادة (0f) بدون حواف مقوسة مثل ترجمة Red Dead
        boxBackground.setShape(GradientDrawable.RECTANGLE);
        boxBackground.setColor(Color.argb(217, 0, 0, 0));
        boxBackground.setCornerRadius(0f);
        boxBackground.setStroke(0, Color.TRANSPARENT);
        overlayContainer.setBackground(boxBackground);

        int initPadV = (int) (14 * dm.density);
        int initPadH = (int) (24 * dm.density);
        overlayContainer.setPadding(initPadH, initPadV, initPadH, initPadV);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText("معاينة حية لمظهر وموقع الترجمة");
        subtitleTextView.setTextColor(Color.WHITE);
        subtitleTextView.setAlpha(1.0f);
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        subtitleTextView.setTypeface(Typeface.DEFAULT_BOLD);
        subtitleTextView.setGravity(Gravity.CENTER);
        subtitleTextView.setIncludeFontPadding(false);

        // تعزيز حدة ووضوح النص على شاشات التلفاز (منع بهتان الخط تماماً)
        subtitleTextView.setPaintFlags(subtitleTextView.getPaintFlags()
                | Paint.ANTI_ALIAS_FLAG
                | Paint.SUBPIXEL_TEXT_FLAG
                | Paint.LINEAR_TEXT_FLAG);
        subtitleTextView.getPaint().setFakeBoldText(true);
        subtitleTextView.setShadowLayer(4.5f, 0f, 2f, Color.argb(235, 0, 0, 0));

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
        windowParams.y = (int) (dm.heightPixels * 0.82f);

        windowManager.addView(overlayContainer, windowParams);
    }

    private void handlePayloadOnUiThread(String jsonStr) {
        if (subtitleTextView == null) return;
        subtitleTextView.post(() -> {
            try {
                JSONObject obj = new JSONObject(jsonStr);
                String type = obj.optString("type", "");

                if ("UNPAIRED".equals(type)) {
                    sendBroadcast(new Intent("com.siyaq.tv.ACTION_UNPAIRED"));
                    stopSelf();
                    return;
                }

                if ("SUB_CLEAR".equals(type)) {
                    subtitleTextView.setText("");
                    overlayContainer.setVisibility(View.INVISIBLE);
                    return;
                }

                if ("PAIRED".equals(type) || "SUB_UPDATE".equals(type)) {
                    String text = obj.optString("text", "").replace("✓", "").trim();
                    if (!text.isEmpty()) {
                        overlayContainer.setVisibility(View.VISIBLE);
                        subtitleTextView.setText(text);
                    }
                    JSONObject style = obj.optJSONObject("style");
                    if (style != null) {
                        lastStyleJson = style;
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

            // 1. تطبيق نوع الخط الحقيقي المختار من الجوال (Tajawal / Cairo / Changa / Almarai) وعرض الخط
            String fontFamilyRaw = style.optString("fontFamily", "Tajawal");
            boolean isBold = style.optBoolean("isBold", true);
            Typeface resolvedTf = resolveRequestedArabicTypeface(fontFamilyRaw, isBold);
            subtitleTextView.setTypeface(resolvedTf);
            subtitleTextView.getPaint().setFakeBoldText(isBold);

            // 2. حجم الخط بوضوح فائق لشاشة التلفاز
            int fontSize = style.optInt("fontSize", 24);
            subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize * 1.25f);

            // 3. لون النص + شفافية النص الكاملة بدون أي خفوت
            String textColorStr = style.optString("textColor", "#FFFFFF");
            int textOpacity = style.optInt("textOpacity", 100);
            int parsedTextCol = Color.parseColor(textColorStr);
            int textAlpha = textOpacity >= 99 ? 255 : Math.max(40, Math.min(255, Math.round(textOpacity * 2.55f)));
            subtitleTextView.setAlpha(1.0f);
            subtitleTextView.setTextColor(Color.argb(
                    textAlpha,
                    Color.red(parsedTextCol),
                    Color.green(parsedTextCol),
                    Color.blue(parsedTextCol)
            ));

            // 4. خلفية الشريط: سوداء صافية (#000000 افتراضياً) بحدود مربعة حادة (0f) مثل ترجمة Red Dead مع إمكانية تخفيفها
            String bgColorStr = style.optString("bgColor", "#000000");
            if ("#12121A".equalsIgnoreCase(bgColorStr) || "#0C0C10".equalsIgnoreCase(bgColorStr)) {
                bgColorStr = "#000000";
            }
            int bgOpacity = style.optInt("bgOpacity", 85);
            int parsedBgCol = Color.parseColor(bgColorStr);
            int bgAlpha = Math.max(0, Math.min(255, Math.round(bgOpacity * 2.55f)));
            boxBackground.setCornerRadius(0f);
            boxBackground.setStroke(0, Color.TRANSPARENT);
            boxBackground.setColor(Color.argb(
                    bgAlpha,
                    Color.red(parsedBgCol),
                    Color.green(parsedBgCol),
                    Color.blue(parsedBgCol)
            ));

            // 5. عرض الشريط (bgWidth) وارتفاع الشريط (bgHeight)
            int bgWidthPct = Math.max(35, Math.min(100, style.optInt("bgWidth", 85)));
            int bgHeightPx = Math.max(4, Math.min(48, style.optInt("bgHeight", 14)));
            int newWidthPx = (int) (dm.widthPixels * (bgWidthPct / 100f));
            windowParams.width = newWidthPx;

            int padV = (int) (bgHeightPx * dm.density);
            int padH = (int) (24 * dm.density);
            overlayContainer.setPadding(padH, padV, padH, padV);

            // 6. الموقع الأفقي (posX) والموقع العمودي (posY) في الشاشة
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
        fontDownloadExecutor.shutdownNow();
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
