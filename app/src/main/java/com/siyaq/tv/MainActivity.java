package com.siyaq.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Random;

public class MainActivity extends Activity {

    private String roomCode;
    private TextView statusBadge;
    private TextView codeView;
    private WebView qrAndMqttWebView;
    private boolean isPhonePaired = false;

    private WindowManager windowManager;
    private FrameLayout overlayContainer;
    private TextView subtitleTextView;
    private GradientDrawable boxBackground;
    private WindowManager.LayoutParams windowParams;

    // منظم سرعة التحديث لمنع تعليق التلفزيون عند تحريك مكان الترجمة بسرعة من الجوال
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private JSONObject pendingStyleJson = null;
    private boolean styleUpdateScheduled = false;

    public class JsBridge {
        @JavascriptInterface
        public void onSyncEvent(final String jsonStr) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleIncomingPhoneMessage(jsonStr);
                }
            });
        }
    }

    // فيو مخصص يرسم شعار SIYAQ الجديد بدقة عالية فوق في المنتصف
    private class SiyaqLogoView extends View {
        private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path topPath = new Path();
        private final Path botPath = new Path();
        private final RectF barRect = new RectF();

        public SiyaqLogoView(Activity context) {
            super(context);
            whitePaint.setColor(Color.WHITE);
            whitePaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h) / 100f;
            float ox = (w - 100f * s) / 2f;
            float oy = (h - 100f * s) / 2f;

            // الجزء العلوي الأبيض من الشعار
            topPath.reset();
            topPath.moveTo(ox + 27 * s, oy + 10 * s);
            topPath.lineTo(ox + 59 * s, oy + 10 * s);
            topPath.lineTo(ox + 59 * s, oy + 20 * s);
            topPath.lineTo(ox + 27 * s, oy + 20 * s);
            topPath.lineTo(ox + 27 * s, oy + 40 * s);
            topPath.lineTo(ox + 59 * s, oy + 40 * s);
            topPath.lineTo(ox + 59 * s, oy + 56 * s);
            topPath.lineTo(ox + 49 * s, oy + 56 * s);
            topPath.lineTo(ox + 49 * s, oy + 49 * s);
            topPath.lineTo(ox + 26 * s, oy + 49 * s);
            topPath.lineTo(ox + 17 * s, oy + 40 * s);
            topPath.lineTo(ox + 17 * s, oy + 20 * s);
            topPath.close();
            c.drawPath(topPath, whitePaint);

            // الخط الأوسط السماوي المتدرج (#1E88E5 -> #00F2FE)
            barPaint.setShader(new LinearGradient(
                    ox + 17 * s, oy + 64 * s, ox + 89 * s, oy + 64 * s,
                    Color.parseColor("#1E88E5"),
                    Color.parseColor("#00F2FE"),
                    Shader.TileMode.CLAMP));
            barRect.set(ox + 17 * s, oy + 60.5f * s, ox + 89 * s, oy + 68.5f * s);
            c.drawRoundRect(barRect, 2 * s, 2 * s, barPaint);

            // الجزء السفلي الأبيض من الشعار
            botPath.reset();
            botPath.moveTo(ox + 17 * s, oy + 75 * s);
            botPath.lineTo(ox + 27 * s, oy + 75 * s);
            botPath.lineTo(ox + 27 * s, oy + 85 * s);
            botPath.lineTo(ox + 49 * s, oy + 85 * s);
            botPath.lineTo(ox + 49 * s, oy + 75 * s);
            botPath.lineTo(ox + 89 * s, oy + 75 * s);
            botPath.lineTo(ox + 89 * s, oy + 84 * s);
            botPath.lineTo(ox + 59 * s, oy + 84 * s);
            botPath.lineTo(ox + 59 * s, oy + 87 * s);
            botPath.lineTo(ox + 51 * s, oy + 94 * s);
            botPath.lineTo(ox + 25 * s, oy + 94 * s);
            botPath.lineTo(ox + 17 * s, oy + 86 * s);
            botPath.close();
            c.drawPath(botPath, whitePaint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // forcing LTR layout direction so Right is explicitly Right and Left is explicitly Left
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        int num = 10000000 + new Random().nextInt(90000000);
        roomCode = String.valueOf(num);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#090913"));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = (int) (screenW * 0.06f);
        int padV = (int) (screenH * 0.04f);
        root.setPadding(padH, padV, padH, padV);

        // 1. الشعار الجديد فقط فوق في المنتصف (بدون اسم التطبيق)
        LinearLayout topLogoBox = new LinearLayout(this);
        topLogoBox.setOrientation(LinearLayout.VERTICAL);
        topLogoBox.setGravity(Gravity.CENTER);

        int logoPx = Math.max(70, (int) (screenH * 0.13f));
        SiyaqLogoView logoView = new SiyaqLogoView(this);
        topLogoBox.addView(logoView, new LinearLayout.LayoutParams(logoPx, logoPx));

        statusBadge = new TextView(this);
        statusBadge.setText("غير متصل");
        statusBadge.setTextColor(Color.parseColor("#94A3B8"));
        statusBadge.setTextSize(13);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(0, 6, 0, 0);
        topLogoBox.addView(statusBadge);

        root.addView(topLogoBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. الوسط: يسار الخيارات (بدون خلفية) + يمين الكود والباركود الكامل
        LinearLayout centerRow = new LinearLayout(this);
        centerRow.setOrientation(LinearLayout.HORIZONTAL);
        centerRow.setGravity(Gravity.CENTER);

        // --- القسم الأيسر: الخيارات نصية صافية بدون خلفية نفس فكرة تطبيق الجوال ---
        LinearLayout leftOptionsCol = new LinearLayout(this);
        leftOptionsCol.setOrientation(LinearLayout.VERTICAL);
        leftOptionsCol.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        leftOptionsCol.setPadding(24, 0, 48, 0);

        Button btnStart = createCleanTextButton("بدء الترجمة والعودة للعبة", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    requestOverlayPermission();
                } else {
                    showTransparentOverlayWindow();
                    moveTaskToBack(true);
                }
            }
        });
        leftOptionsCol.addView(btnStart);

        Button btnNewCode = createCleanTextButton("تجديد رمز الاقتران", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int n = 10000000 + new Random().nextInt(90000000);
                roomCode = String.valueOf(n);
                codeView.setText(roomCode);
                loadQrAndWaitBridge();
                Toast.makeText(MainActivity.this, "تم تجديد الرمز ✓", Toast.LENGTH_SHORT).show();
            }
        });
        leftOptionsCol.addView(btnNewCode);

        Button btnPerm = createCleanTextButton("تفعيل صلاحية الشاشة", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        });
        leftOptionsCol.addView(btnPerm);

        Button btnStop = createCleanTextButton("إيقاف وإلغاء الاقتران", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideOverlayAndUnpair();
            }
        });
        leftOptionsCol.addView(btnStop);

        centerRow.addView(leftOptionsCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));

        // --- القسم الأيمن: الباركود الكامل 100% + رقم الكود ---
        LinearLayout rightQrCol = new LinearLayout(this);
        rightQrCol.setOrientation(LinearLayout.VERTICAL);
        rightQrCol.setGravity(Gravity.CENTER);

        int qrBoxSize = Math.min(230, (int) (screenH * 0.42f));
        qrAndMqttWebView = new WebView(this);
        qrAndMqttWebView.setBackgroundColor(Color.TRANSPARENT);
        qrAndMqttWebView.setVerticalScrollBarEnabled(false);
        qrAndMqttWebView.setHorizontalScrollBarEnabled(false);
        WebSettings ws = qrAndMqttWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        qrAndMqttWebView.addJavascriptInterface(new JsBridge(), "SiyaqTvWaitBridge");

        rightQrCol.addView(qrAndMqttWebView, new LinearLayout.LayoutParams(qrBoxSize, qrBoxSize));

        codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#00F2FE"));
        codeView.setTextSize(26);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 14, 0, 0);
        rightQrCol.addView(codeView);

        centerRow.addView(rightQrCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));

        root.addView(centerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        btnStart.requestFocus();
        loadQrAndWaitBridge();
    }

    // زر نصي صافي بدون أي خلفية نهائياً (يضيء بالأزرق السماوي #00F2FE عند الوقوف عليه بالريموت)
    private Button createCleanTextButton(String label, View.OnClickListener listener) {
        final Button btn = new Button(this);
        btn.setText(label);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setTextColor(Color.parseColor("#94A3B8"));
        btn.setTextSize(18);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        btn.setPadding(16, 16, 16, 16);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btn.setStateListAnimator(null);
            btn.setElevation(0f);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 6, 0, 6);
        btn.setLayoutParams(lp);

        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setTextColor(Color.parseColor("#00F2FE"));
                    btn.setTextSize(21);
                } else {
                    btn.setTextColor(Color.parseColor("#94A3B8"));
                    btn.setTextSize(18);
                }
            }
        });

        btn.setOnClickListener(listener);
        return btn;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 101);
        } else {
            Toast.makeText(this, "الصلاحية مفعلة ✓", Toast.LENGTH_SHORT).show();
        }
    }

    // رسم الباركود بحجم 76% داخل مربع أبيض كامل بحيث لا ينقص منه ولا ملي واحد أبداً
    private void loadQrAndWaitBridge() {
        String qrData = "SIYAQ-TV-" + roomCode;
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&ecc=M&margin=2&data=" + qrData;

        String html = "<!DOCTYPE html><html><head>" +
                "<style>" +
                "*{margin:0;padding:0;box-sizing:border-box;}" +
                "html,body{width:100%;height:100%;background:transparent;display:flex;align-items:center;justify-content:center;overflow:hidden;}" +
                ".card{width:92%;height:92%;background:#FFFFFF;border-radius:22px;display:flex;align-items:center;justify-content:center;padding:12%;}" +
                "img{width:100%;height:100%;object-fit:contain;display:block;image-rendering:pixelated;}" +
                "</style>" +
                "<script src='https://unpkg.com/mqtt/dist/mqtt.min.js'></script>" +
                "</head><body>" +
                "<div class='card'><img src='" + qrUrl + "' alt='QR' /></div>" +
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

            if ("UNPAIRED".equals(type)) {
                hideOverlayAndUnpair();
                return;
            }

            if ("PAIRED".equals(type) || "SUB_UPDATE".equals(type)) {
                if (!isPhonePaired) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "يرجى الضغط على تفعيل صلاحية الشاشة أولاً", Toast.LENGTH_LONG).show();
                        return;
                    }
                    isPhonePaired = true;
                    showTransparentOverlayWindow();
                    updateUiForPairingState(true);
                    moveTaskToBack(true);
                }

                String text = obj.optString("text", "");
                if (!text.isEmpty() && subtitleTextView != null) {
                    subtitleTextView.setText(text);
                }
                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    scheduleSmoothStyleUpdate(style);
                }
            }
        } catch (Exception ignored) {}
    }

    // تحديث سلس للموقع والمظهر بدون تعليق أو تراكم رسائل
    private void scheduleSmoothStyleUpdate(JSONObject style) {
        pendingStyleJson = style;
        if (styleUpdateScheduled) return;
        styleUpdateScheduled = true;
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                styleUpdateScheduled = false;
                if (pendingStyleJson != null) {
                    applyFullSubtitleStyle(pendingStyleJson);
                }
            }
        }, 25);
    }

    private void showTransparentOverlayWindow() {
        if (overlayContainer != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        overlayContainer = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        boxBackground.setColor(Color.argb(225, 18, 18, 26));
        boxBackground.setCornerRadius(24f);
        overlayContainer.setBackground(boxBackground);
        overlayContainer.setPadding(36, 16, 36, 16);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText("");
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

            // 1. الخط وعرضه
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

            // 2. حجم الخط
            int fontSize = style.optInt("fontSize", 22);
            subtitleTextView.setTextSize(fontSize * 1.15f);

            // 3. لون النص وشفافيته
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

            // 5. عرض وارتفاع الشريط
            int bgWidthPct = Math.max(35, Math.min(100, style.optInt("bgWidth", 85)));
            int bgHeightPx = Math.max(4, Math.min(48, style.optInt("bgHeight", 14)));
            int newWidthPx = (int) (dm.widthPixels * (bgWidthPct / 100f));
            windowParams.width = newWidthPx;

            int padV = (int) (bgHeightPx * dm.density);
            int padH = (int) (24 * dm.density);
            overlayContainer.setPadding(padH, padV, padH, padV);

            // 6. الموقع الأفقي والعمودي في الشاشة بسلاسة فورية
            int posX = Math.max(10, Math.min(90, style.optInt("posX", 50)));
            int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));

            int centerX = (int) (dm.widthPixels * (posX / 100f));
            int targetX = Math.max(0, Math.min(dm.widthPixels - newWidthPx, centerX - (newWidthPx / 2)));

            int estBoxHeight = overlayContainer.getHeight() > 0 ? overlayContainer.getHeight() : (int) (72 * dm.density);
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
        Toast.makeText(this, "تم إلغاء الاقتران ✓", Toast.LENGTH_SHORT).show();
    }

    private void updateUiForPairingState(boolean paired) {
        if (paired) {
            statusBadge.setText("● متصل بالجوال");
            statusBadge.setTextColor(Color.parseColor("#00F2FE"));
        } else {
            statusBadge.setText("غير متصل");
            statusBadge.setTextColor(Color.parseColor("#94A3B8"));
        }
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
