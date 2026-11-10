package com.siyaq.tv;

import android.app.Activity;
import android.content.Context;
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
import android.os.PowerManager;
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
    private PowerManager.WakeLock wakeLock;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable autoHideTestRunnable;

    // نبض خلفي يمنع نظام التلفزيون من تجميد استقبال الترجمة والإعدادات أثناء اللعب
    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (qrAndMqttWebView != null) {
                qrAndMqttWebView.resumeTimers();
            }
            uiHandler.postDelayed(this, 800);
        }
    };

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

    // فيو يرسم شعارك الجديد بالمقاسات الحقيقية المطابقة للصورة 100% (بطن حرف الياء/السين واسع ومتناسق مع هوامش مريحة للزوايا)
    private class SiyaqLogoView extends View {
        private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path topPath = new Path();
        private final Path botPath = new Path();
        private final RectF barRect = new RectF();

        public SiyaqLogoView(Context context) {
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

            // 1. القطعة العلوية البيضاء بالمقاس الأصلي
            topPath.reset();
            topPath.moveTo(ox + 31.4f * s, oy + 13.1f * s);
            topPath.lineTo(ox + 54.3f * s, oy + 13.1f * s);
            topPath.lineTo(ox + 54.3f * s, oy + 20.4f * s);
            topPath.lineTo(ox + 31.4f * s, oy + 20.4f * s);
            topPath.lineTo(ox + 31.4f * s, oy + 36.0f * s);
            topPath.lineTo(ox + 54.3f * s, oy + 36.0f * s);
            topPath.lineTo(ox + 54.3f * s, oy + 49.1f * s);
            topPath.lineTo(ox + 47.1f * s, oy + 49.1f * s);
            topPath.lineTo(ox + 47.1f * s, oy + 43.2f * s);
            topPath.lineTo(ox + 30.5f * s, oy + 43.2f * s);
            topPath.lineTo(ox + 24.1f * s, oy + 36.0f * s);
            topPath.lineTo(ox + 24.1f * s, oy + 20.4f * s);
            topPath.close();
            c.drawPath(topPath, whitePaint);

            // 2. الشريط الأوسط المتدرج بالأزرق السماوي
            barPaint.setShader(new LinearGradient(
                    ox + 24.2f * s, oy + 57f * s, ox + 75.7f * s, oy + 57f * s,
                    Color.parseColor("#1E88E5"),
                    Color.parseColor("#00F2FE"),
                    Shader.TileMode.CLAMP));
            barRect.set(ox + 24.2f * s, oy + 54.7f * s, ox + 75.7f * s, oy + 59.3f * s);
            c.drawRoundRect(barRect, 1.2f * s, 1.2f * s, barPaint);

            // 3. القطعة السفلية البيضاء ببطن واسع وعميق مطابق للصورة المرفقة تماماً
            botPath.reset();
            botPath.moveTo(ox + 24.1f * s, oy + 65.1f * s);
            botPath.lineTo(ox + 31.4f * s, oy + 65.1f * s);
            botPath.lineTo(ox + 31.4f * s, oy + 79.6f * s);
            botPath.lineTo(ox + 47.0f * s, oy + 79.6f * s);
            botPath.lineTo(ox + 47.0f * s, oy + 65.1f * s);
            botPath.lineTo(ox + 75.7f * s, oy + 65.1f * s);
            botPath.lineTo(ox + 75.7f * s, oy + 72.3f * s);
            botPath.lineTo(ox + 54.3f * s, oy + 72.3f * s);
            botPath.lineTo(ox + 54.3f * s, oy + 79.8f * s);
            botPath.lineTo(ox + 47.3f * s, oy + 86.8f * s);
            botPath.lineTo(ox + 30.4f * s, oy + 86.8f * s);
            botPath.lineTo(ox + 24.1f * s, oy + 79.8f * s);
            botPath.close();
            c.drawPath(botPath, whitePaint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // إبقاء شاشة وتراسل التلفزيون نشطاً بأعلى أولوية
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "siyaq:tvsync");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

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

        // 1. الشعار فوق في المنتصف بحجم أصغر شوي ومتناسق مع الزوايا
        LinearLayout topLogoBox = new LinearLayout(this);
        topLogoBox.setOrientation(LinearLayout.VERTICAL);
        topLogoBox.setGravity(Gravity.CENTER);

        int logoPx = Math.max(58, (int) (screenH * 0.105f));
        SiyaqLogoView logoView = new SiyaqLogoView(this);
        topLogoBox.addView(logoView, new LinearLayout.LayoutParams(logoPx, logoPx));

        statusBadge = new TextView(this);
        statusBadge.setText("غير متصل");
        statusBadge.setTextColor(Color.parseColor("#94A3B8"));
        statusBadge.setTextSize(12);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(0, 4, 0, 0);
        topLogoBox.addView(statusBadge);

        root.addView(topLogoBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. الوسط: يسار الخيارات (بدون خلفية) + يمين الكود والباركود
        LinearLayout centerRow = new LinearLayout(this);
        centerRow.setOrientation(LinearLayout.HORIZONTAL);
        centerRow.setGravity(Gravity.CENTER);

        // --- القسم الأيسر: الخيارات بدون خلفية ---
        LinearLayout leftOptionsCol = new LinearLayout(this);
        leftOptionsCol.setOrientation(LinearLayout.VERTICAL);
        leftOptionsCol.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        leftOptionsCol.setPadding(24, 0, 48, 0);

        Button btnTestBar = createCleanTextButton("اختبار شريط الترجمة", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerTestSubtitleBanner();
            }
        });
        leftOptionsCol.addView(btnTestBar);

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

        // --- القسم الأيمن: الباركود الكامل + رقم الكود ---
        LinearLayout rightQrCol = new LinearLayout(this);
        rightQrCol.setOrientation(LinearLayout.VERTICAL);
        rightQrCol.setGravity(Gravity.CENTER);

        int qrBoxSize = Math.min(220, (int) (screenH * 0.40f));
        qrAndMqttWebView = new WebView(this) {
            // منع التلفزيون من إيقاف معالج الجافا سكريبت عند الخروج للعبة (HDMI)
            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(View.VISIBLE);
            }
        };
        qrAndMqttWebView.setBackgroundColor(Color.TRANSPARENT);
        qrAndMqttWebView.setVerticalScrollBarEnabled(false);
        qrAndMqttWebView.setHorizontalScrollBarEnabled(false);
        WebSettings ws = qrAndMqttWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        qrAndMqttWebView.addJavascriptInterface(new JsBridge(), "SiyaqTvWaitBridge");

        rightQrCol.addView(qrAndMqttWebView, new LinearLayout.LayoutParams(qrBoxSize, qrBoxSize));

        codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#00F2FE"));
        codeView.setTextSize(25);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 12, 0, 0);
        rightQrCol.addView(codeView);

        centerRow.addView(rightQrCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));

        root.addView(centerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        btnTestBar.requestFocus();
        loadQrAndWaitBridge();
        uiHandler.post(keepAliveRunnable);
    }

    // إبقاء WebView نشطاً بالكامل حتى عندما يكون التطبيق في الخلفية أثناء اللعب
    @Override
    protected void onPause() {
        super.onPause();
        if (qrAndMqttWebView != null) {
            qrAndMqttWebView.onResume();
            qrAndMqttWebView.resumeTimers();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (qrAndMqttWebView != null) {
            qrAndMqttWebView.onResume();
            qrAndMqttWebView.resumeTimers();
        }
    }

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

    private void triggerTestSubtitleBanner() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        ensureOverlayCreated();
        if (overlayContainer != null && subtitleTextView != null) {
            subtitleTextView.setText("اختبار شريط الترجمة — جاهز للعرض فوق اللعبة ✓");
            overlayContainer.setVisibility(View.VISIBLE);
            if (autoHideTestRunnable != null) {
                uiHandler.removeCallbacks(autoHideTestRunnable);
            }
            autoHideTestRunnable = new Runnable() {
                @Override
                public void run() {
                    if (overlayContainer != null && !isPhonePaired) {
                        overlayContainer.setVisibility(View.GONE);
                    }
                }
            };
            uiHandler.postDelayed(autoHideTestRunnable, 4000);
        }
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

    private void loadQrAndWaitBridge() {
        String qrData = "SIYAQ-TV-" + roomCode;
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&ecc=M&margin=2&data=" + qrData;

        String html = "<!DOCTYPE html><html><head>" +
                "<style>" +
                "*{margin:0;padding:0;box-sizing:border-box;}" +
                "html,body{width:100%;height:100%;background:transparent;display:flex;align-items:center;justify-content:center;overflow:hidden;}" +
                ".card{width:90%;height:90%;background:#FFFFFF;border-radius:20px;display:flex;align-items:center;justify-content:center;padding:12%;}" +
                "img{width:100%;height:100%;object-fit:contain;display:block;image-rendering:pixelated;}" +
                "</style>" +
                "<script src='https://unpkg.com/mqtt/dist/mqtt.min.js'></script>" +
                "</head><body>" +
                "<div class='card'><img src='" + qrUrl + "' alt='QR' /></div>" +
                "<script>" +
                "try {" +
                "  const client = mqtt.connect('wss://broker.emqx.io:8084/mqtt', {" +
                "    keepalive: 15," +
                "    reconnectPeriod: 800," +
                "    clean: true" +
                "  });" +
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

            // عند إلغاء الاقتران أو حذف الصفحة من الجوال
            if ("UNPAIRED".equals(type)) {
                hideOverlayAndUnpair();
                return;
            }

            // عند توقف الترجمة في الجوال أو انتهاء جملة الحوار يختفي الشريط من التلفزيون فوراً ولا يعلق
            if ("SUB_CLEAR".equals(type)) {
                if (overlayContainer != null) {
                    subtitleTextView.setText("");
                    overlayContainer.setVisibility(View.GONE);
                }
                return;
            }

            if ("PAIRED".equals(type) || "SUB_UPDATE".equals(type)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "يرجى الضغط على تفعيل صلاحية الشاشة أولاً", Toast.LENGTH_LONG).show();
                    return;
                }

                ensureOverlayCreated();

                if (!isPhonePaired) {
                    isPhonePaired = true;
                    updateUiForPairingState(true);
                    Toast.makeText(this, "تم الاقتران بالجوال ✓", Toast.LENGTH_SHORT).show();
                    moveTaskToBack(true);
                }

                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    applyFullSubtitleStyle(style);
                }

                if ("SUB_UPDATE".equals(type)) {
                    String text = obj.optString("text", "").trim();
                    if (text.isEmpty()
                            || "في انتظار الترجمات".equals(text)
                            || "الترجمة متوقفة".equals(text)
                            || "Waiting for subtitles".equals(text)
                            || "Translation Paused".equals(text)) {
                        subtitleTextView.setText("");
                        overlayContainer.setVisibility(View.GONE);
                    } else {
                        subtitleTextView.setText(text);
                        overlayContainer.setVisibility(View.VISIBLE);
                    }
                } else if ("PAIRED".equals(type)) {
                    // عند الاقتران فقط يبقى الشريط مخفياً حتى يبدأ الجوال بالترجمة الفعلية
                    overlayContainer.setVisibility(View.GONE);
                }
            }
        } catch (Exception ignored) {}
    }

    private void ensureOverlayCreated() {
        if (overlayContainer != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        overlayContainer = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        boxBackground.setColor(Color.argb(225, 18, 18, 26));
        boxBackground.setCornerRadius(24f);
        overlayContainer.setBackground(boxBackground);
        overlayContainer.setPadding(36, 16, 36, 16);
        overlayContainer.setVisibility(View.GONE);

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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
        uiHandler.removeCallbacks(keepAliveRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }
        hideOverlayAndUnpair();
        if (qrAndMqttWebView != null) {
            qrAndMqttWebView.destroy();
        }
    }
}
