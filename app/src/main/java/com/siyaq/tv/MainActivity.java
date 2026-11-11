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
    private boolean isArabic = true;
    private boolean isPhonePaired = false;

    private TextView statusBadge;
    private TextView codeView;
    private WebView qrAndMqttWebView;

    private Button langToggleBtn;
    private Button btnTestBar;
    private Button btnNewCode;
    private Button btnPerm;
    private Button btnUnpair;

    private LinearLayout centerClusterRow;
    private LinearLayout optionsColumn;
    private LinearLayout qrColumn;

    // طبقة شفافة ثابتة بحجم الشاشة يتحرك بداخلها شريط الترجمة بسرعة 60fps بدون أي تقطيع
    private WindowManager windowManager;
    private FrameLayout fullScreenOverlayStage;
    private FrameLayout subtitleBannerBox;
    private TextView subtitleTextView;
    private GradientDrawable boxBackground;
    private WindowManager.LayoutParams stageWindowParams;
    private PowerManager.WakeLock wakeLock;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable autoHideTestRunnable;

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

    // فيو مخصص يرسم شعار SIYAQ الجديد (مع حرف الـ I السماوي المتدرج) بأبعاده الهندسية المطابقة للصورة 100%
    private class SiyaqWordmarkLogoView extends View {
        private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cyanBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path sPath = new Path();
        private final Path yPath = new Path();
        private final Path aPath = new Path();
        private final Path qPath = new Path();
        private final RectF iRect = new RectF();

        public SiyaqWordmarkLogoView(Context context) {
            super(context);
            whitePaint.setColor(Color.WHITE);
            whitePaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            // أبعاد الشعار الأساسية: العرض 78 وحدة، الارتفاع 30 وحدة
            float scale = Math.min(w / 84f, h / 34f);
            float ox = (w - 100f * scale) / 2f;
            float oy = (h - 100f * scale) / 2f;

            // حرف S الهندسي
            sPath.reset();
            sPath.moveTo(ox + 11.1f * scale, oy + 35.1f * scale);
            sPath.lineTo(ox + 24.5f * scale, oy + 35.1f * scale);
            sPath.lineTo(ox + 24.5f * scale, oy + 39.2f * scale);
            sPath.lineTo(ox + 14.2f * scale, oy + 39.2f * scale);
            sPath.lineTo(ox + 14.2f * scale, oy + 46.3f * scale);
            sPath.lineTo(ox + 25.5f * scale, oy + 46.3f * scale);
            sPath.lineTo(ox + 25.5f * scale, oy + 64.6f * scale);
            sPath.lineTo(ox + 11.0f * scale, oy + 64.6f * scale);
            sPath.lineTo(ox + 11.0f * scale, oy + 59.2f * scale);
            sPath.lineTo(ox + 21.9f * scale, oy + 59.2f * scale);
            sPath.lineTo(ox + 21.9f * scale, oy + 51.4f * scale);
            sPath.lineTo(ox + 11.1f * scale, oy + 51.4f * scale);
            sPath.close();
            c.drawPath(sPath, whitePaint);

            // حرف I السماوي المتدرج (#00F2FE -> #1E78C8)
            cyanBarPaint.setShader(new LinearGradient(
                    0, oy + 35.4f * scale, 0, oy + 64.9f * scale,
                    Color.parseColor("#00F2FE"),
                    Color.parseColor("#1E78C8"),
                    Shader.TileMode.CLAMP));
            iRect.set(ox + 29.5f * scale, oy + 35.4f * scale, ox + 34.1f * scale, oy + 64.9f * scale);
            c.drawRect(iRect, cyanBarPaint);

            // حرف Y الهندسي
            yPath.reset();
            yPath.moveTo(ox + 36.3f * scale, oy + 35.4f * scale);
            yPath.lineTo(ox + 41.3f * scale, oy + 35.4f * scale);
            yPath.lineTo(ox + 44.3f * scale, oy + 42.7f * scale);
            yPath.lineTo(ox + 47.4f * scale, oy + 35.4f * scale);
            yPath.lineTo(ox + 52.9f * scale, oy + 35.4f * scale);
            yPath.lineTo(ox + 47.3f * scale, oy + 46.4f * scale);
            yPath.lineTo(ox + 47.3f * scale, oy + 64.9f * scale);
            yPath.lineTo(ox + 41.4f * scale, oy + 64.9f * scale);
            yPath.lineTo(ox + 41.4f * scale, oy + 46.4f * scale);
            yPath.close();
            c.drawPath(yPath, whitePaint);

            // حرف A الهندسي مع الفتحة الداخلية
            aPath.reset();
            aPath.setFillType(Path.FillType.EVEN_ODD);
            aPath.moveTo(ox + 54.9f * scale, oy + 35.5f * scale);
            aPath.lineTo(ox + 68.9f * scale, oy + 35.5f * scale);
            aPath.lineTo(ox + 68.9f * scale, oy + 64.9f * scale);
            aPath.lineTo(ox + 64.5f * scale, oy + 64.9f * scale);
            aPath.lineTo(ox + 64.5f * scale, oy + 55.9f * scale);
            aPath.lineTo(ox + 59.3f * scale, oy + 55.9f * scale);
            aPath.lineTo(ox + 59.3f * scale, oy + 64.9f * scale);
            aPath.lineTo(ox + 54.9f * scale, oy + 64.9f * scale);
            aPath.close();
            aPath.moveTo(ox + 59.5f * scale, oy + 40.7f * scale);
            aPath.lineTo(ox + 64.6f * scale, oy + 40.7f * scale);
            aPath.lineTo(ox + 64.6f * scale, oy + 51.2f * scale);
            aPath.lineTo(ox + 59.5f * scale, oy + 51.2f * scale);
            aPath.close();
            c.drawPath(aPath, whitePaint);

            // حرف Q الهندسي مع الفتحة الداخلية والذيل المائل
            qPath.reset();
            qPath.setFillType(Path.FillType.EVEN_ODD);
            qPath.moveTo(ox + 71.7f * scale, oy + 35.5f * scale);
            qPath.lineTo(ox + 85.0f * scale, oy + 35.5f * scale);
            qPath.lineTo(ox + 85.0f * scale, oy + 59.8f * scale);
            qPath.lineTo(ox + 89.0f * scale, oy + 59.8f * scale);
            qPath.lineTo(ox + 86.1f * scale, oy + 64.9f * scale);
            qPath.lineTo(ox + 71.7f * scale, oy + 64.9f * scale);
            qPath.close();
            qPath.moveTo(ox + 74.8f * scale, oy + 39.2f * scale);
            qPath.lineTo(ox + 80.9f * scale, oy + 39.2f * scale);
            qPath.lineTo(ox + 80.9f * scale, oy + 59.8f * scale);
            qPath.lineTo(ox + 74.8f * scale, oy + 59.8f * scale);
            qPath.close();
            c.drawPath(qPath, whitePaint);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "siyaq:tvsync");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

        roomCode = generateRandomRoomCode();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.parseColor("#090913"));
        int padH = (int) (screenW * 0.05f);
        int padV = (int) (screenH * 0.04f);
        rootFrame.setPadding(padH, padV, padH, padV);

        // 1. زر تغيير اللغة (فوق يسار) بحروف واضحة بدون خلفية
        langToggleBtn = new Button(this);
        langToggleBtn.setText("EN / ع");
        langToggleBtn.setFocusable(true);
        langToggleBtn.setFocusableInTouchMode(true);
        langToggleBtn.setBackgroundColor(Color.TRANSPARENT);
        langToggleBtn.setTextColor(Color.parseColor("#94A3B8"));
        langToggleBtn.setTextSize(15);
        langToggleBtn.setTypeface(Typeface.DEFAULT_BOLD);
        langToggleBtn.setPadding(20, 10, 20, 10);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            langToggleBtn.setStateListAnimator(null);
            langToggleBtn.setElevation(0f);
        }
        langToggleBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                langToggleBtn.setTextColor(hasFocus ? Color.parseColor("#00F2FE") : Color.parseColor("#94A3B8"));
                langToggleBtn.setTextSize(hasFocus ? 17 : 15);
            }
        });
        langToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isArabic = !isArabic;
                applyLanguageAndLayoutDirection();
            }
        });

        FrameLayout.LayoutParams langLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        rootFrame.addView(langToggleBtn, langLp);

        // 2. الشعار الجديد (SIYAQ) فوق في المنتصف + حالة الاتصال تحته
        LinearLayout topLogoContainer = new LinearLayout(this);
        topLogoContainer.setOrientation(LinearLayout.VERTICAL);
        topLogoContainer.setGravity(Gravity.CENTER);

        int logoW = Math.max(150, (int) (screenW * 0.16f));
        int logoH = Math.max(54, (int) (screenH * 0.085f));
        SiyaqWordmarkLogoView wordmarkView = new SiyaqWordmarkLogoView(this);
        topLogoContainer.addView(wordmarkView, new LinearLayout.LayoutParams(logoW, logoH));

        statusBadge = new TextView(this);
        statusBadge.setText("غير متصل");
        statusBadge.setTextColor(Color.parseColor("#94A3B8"));
        statusBadge.setTextSize(12);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(0, 4, 0, 0);
        topLogoContainer.addView(statusBadge);

        FrameLayout.LayoutParams topLogoLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rootFrame.addView(topLogoContainer, topLogoLp);

        // 3. الحاوية الوسطى المتقاربة والموزونة في منتصف الشاشة تماماً
        centerClusterRow = new LinearLayout(this);
        centerClusterRow.setOrientation(LinearLayout.HORIZONTAL);
        centerClusterRow.setGravity(Gravity.CENTER);

        // --- عمود الباركود والكود ---
        qrColumn = new LinearLayout(this);
        qrColumn.setOrientation(LinearLayout.VERTICAL);
        qrColumn.setGravity(Gravity.CENTER);
        qrColumn.setPadding(32, 12, 32, 12);

        int qrBoxSize = Math.min(215, (int) (screenH * 0.38f));
        qrAndMqttWebView = new WebView(this) {
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
        qrAndMqttWebView.addJavascriptInterface(new JsBridge(), "SiyaqTvWaitBridge");

        qrColumn.addView(qrAndMqttWebView, new LinearLayout.LayoutParams(qrBoxSize, qrBoxSize));

        codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#00F2FE"));
        codeView.setTextSize(24);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 10, 0, 0);
        qrColumn.addView(codeView);

        // --- عمود الخيارات (نصوص صافية بدون خلفية، موزونة بجانب الكود) ---
        optionsColumn = new LinearLayout(this);
        optionsColumn.setOrientation(LinearLayout.VERTICAL);
        optionsColumn.setGravity(Gravity.CENTER_VERTICAL);
        optionsColumn.setPadding(32, 12, 32, 12);

        btnTestBar = createCleanTextButton("اختبار شريط الترجمة", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerTestSubtitleBanner();
            }
        });
        optionsColumn.addView(btnTestBar);

        btnNewCode = createCleanTextButton("تجديد رمز الاقتران", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rotateToFreshRoomCode();
                Toast.makeText(MainActivity.this, isArabic ? "تم تجديد الرمز ✓" : "Code refreshed ✓", Toast.LENGTH_SHORT).show();
            }
        });
        optionsColumn.addView(btnNewCode);

        btnPerm = createCleanTextButton("تفعيل صلاحية الشاشة", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        });
        optionsColumn.addView(btnPerm);

        btnUnpair = createCleanTextButton("إلغاء الاقتران", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performCleanUnpairAndRotateCode();
            }
        });
        optionsColumn.addView(btnUnpair);

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        centerLp.topMargin = (int) (screenH * 0.04f);
        rootFrame.addView(centerClusterRow, centerLp);

        setContentView(rootFrame);

        // تطبيق اللغة العربية افتراضياً (الخيارات يمين والكود يسار، قريبين من بعض في المنتصف)
        applyLanguageAndLayoutDirection();
        btnTestBar.requestFocus();
        loadQrAndWaitBridge();
        uiHandler.post(keepAliveRunnable);
    }

    private String generateRandomRoomCode() {
        int num = 10000000 + new Random().nextInt(90000000);
        return String.valueOf(num);
    }

    // تحديث نصوص اللغة وعكس ترتيب الكود والخيارات (عربي: الخيارات يمين والكود يسار | إنجليزي: الخيارات يسار والكود يمين)
    private void applyLanguageAndLayoutDirection() {
        centerClusterRow.removeAllViews();

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.setMargins(28, 0, 28, 0);

        if (isArabic) {
            // في العربي: الكود يسار والخيارات يمين
            centerClusterRow.addView(qrColumn, colLp);
            centerClusterRow.addView(optionsColumn, colLp);

            btnTestBar.setText("اختبار شريط الترجمة");
            btnNewCode.setText("تجديد رمز الاقتران");
            btnPerm.setText("تفعيل صلاحية الشاشة");
            btnUnpair.setText("إلغاء الاقتران");

            int rightGrav = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
            btnTestBar.setGravity(rightGrav);
            btnNewCode.setGravity(rightGrav);
            btnPerm.setGravity(rightGrav);
            btnUnpair.setGravity(rightGrav);
        } else {
            // في الإنجليزي: الخيارات يسار والكود يمين
            centerClusterRow.addView(optionsColumn, colLp);
            centerClusterRow.addView(qrColumn, colLp);

            btnTestBar.setText("Test Subtitle Bar");
            btnNewCode.setText("Refresh Pair Code");
            btnPerm.setText("Screen Permission");
            btnUnpair.setText("Unpair Device");

            int leftGrav = Gravity.LEFT | Gravity.CENTER_VERTICAL;
            btnTestBar.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            btnTestBar.setGravity(leftGrav);
            btnNewCode.setGravity(leftGrav);
            btnPerm.setGravity(leftGrav);
            btnUnpair.setGravity(leftGrav);
        }

        updateUiForPairingState(isPhonePaired);
    }

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
        btn.setTextColor(Color.parseColor("#E2E8F0"));
        btn.setTextSize(18);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(16, 14, 16, 14);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btn.setStateListAnimator(null);
            btn.setElevation(0f);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4, 0, 4);
        btn.setLayoutParams(lp);

        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setTextColor(Color.parseColor("#00F2FE"));
                    btn.setTextSize(20);
                } else {
                    btn.setTextColor(Color.parseColor("#E2E8F0"));
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
        ensureOverlayStageCreated();
        if (subtitleBannerBox != null && subtitleTextView != null) {
            subtitleTextView.setText(isArabic
                    ? "اختبار شريط الترجمة — جاهز للعرض فوق اللعبة ✓"
                    : "Subtitle Bar Test — Ready over game ✓");
            subtitleBannerBox.setVisibility(View.VISIBLE);
            if (autoHideTestRunnable != null) {
                uiHandler.removeCallbacks(autoHideTestRunnable);
            }
            autoHideTestRunnable = new Runnable() {
                @Override
                public void run() {
                    if (subtitleBannerBox != null && !isPhonePaired) {
                        subtitleBannerBox.setVisibility(View.GONE);
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
            Toast.makeText(this, isArabic ? "الصلاحية مفعلة ✓" : "Permission Active ✓", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadQrAndWaitBridge() {
        String qrData = "SIYAQ-TV-" + roomCode;
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&ecc=M&margin=2&data=" + qrData;

        String html = "<!DOCTYPE html><html><head>" +
                "<style>" +
                "*{margin:0;padding:0;box-sizing:border-box;}" +
                "html,body{width:100%;height:100%;background:transparent;display:flex;align-items:center;justify-content:center;overflow:hidden;}" +
                ".card{width:92%;height:92%;background:#FFFFFF;border-radius:20px;display:flex;align-items:center;justify-content:center;padding:11%;}" +
                "img{width:100%;height:100%;object-fit:contain;display:block;image-rendering:pixelated;}" +
                "</style>" +
                "<script src='https://unpkg.com/mqtt/dist/mqtt.min.js'></script>" +
                "</head><body>" +
                "<div class='card'><img src='" + qrUrl + "' alt='QR' /></div>" +
                "<script>" +
                "window.mqttClient = null;" +
                "try {" +
                "  window.mqttClient = mqtt.connect('wss://broker.emqx.io:8084/mqtt', {" +
                "    keepalive: 15," +
                "    reconnectPeriod: 800," +
                "    clean: true" +
                "  });" +
                "  window.mqttClient.on('connect', () => { window.mqttClient.subscribe('siyaq/tv/sec/" + roomCode + "'); });" +
                "  window.mqttClient.on('message', (t, p) => { SiyaqTvWaitBridge.onSyncEvent(p.toString()); });" +
                "} catch(e) {}" +
                "</script></body></html>";
        qrAndMqttWebView.loadDataWithBaseURL("https://siyaq.app", html, "text/html", "UTF-8", null);
    }

    private void handleIncomingPhoneMessage(String jsonStr) {
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String type = obj.optString("type", "");

            // عند إلغاء الاقتران من الجوال، نلغي الاقتران ونولد كود جديد فوراً حتى لا يعيد الاقتران تلقائياً أبداً
            if ("UNPAIRED".equals(type)) {
                hideOverlayStage();
                isPhonePaired = false;
                updateUiForPairingState(false);
                rotateToFreshRoomCode();
                Toast.makeText(this, isArabic ? "تم إلغاء الاقتران ✓" : "Unpaired ✓", Toast.LENGTH_SHORT).show();
                return;
            }

            if ("SUB_CLEAR".equals(type)) {
                if (subtitleBannerBox != null) {
                    subtitleTextView.setText("");
                    subtitleBannerBox.setVisibility(View.GONE);
                }
                return;
            }

            // لا يبدأ الاقتران إلا برسالة PAIRED صريحة، ولا تعيد رسائل SUB_UPDATE المتأخرة فتح الاقتران بعد إلغائه
            if ("PAIRED".equals(type)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, isArabic ? "يرجى تفعيل صلاحية الشاشة أولاً" : "Please enable screen permission first", Toast.LENGTH_LONG).show();
                    return;
                }

                isPhonePaired = true;
                ensureOverlayStageCreated();
                updateUiForPairingState(true);

                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    applySmoothHardwareSubtitleStyle(style);
                }

                // إظهار الشريط في التلفزيون فور الاقتران كما طلب المستخدم
                subtitleTextView.setText(isArabic ? "متصل بالجوال — جاهز للترجمة الفورية ✓" : "Connected to Phone — Ready for Live Translation ✓");
                subtitleBannerBox.setVisibility(View.VISIBLE);

                Toast.makeText(this, isArabic ? "تم الاقتران بالجوال ✓" : "Paired with Phone ✓", Toast.LENGTH_SHORT).show();
                moveTaskToBack(true);
                return;
            }

            if ("SUB_UPDATE".equals(type)) {
                if (!isPhonePaired) {
                    // تجاهل أي رسالة تحديث قديمة إذا كان الجهاز غير مقترن لمنع إعادة الاقتران التلقائي
                    return;
                }

                ensureOverlayStageCreated();

                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    applySmoothHardwareSubtitleStyle(style);
                }

                String text = obj.optString("text", "").trim();
                if (text.isEmpty()
                        || "في انتظار الترجمات".equals(text)
                        || "الترجمة متوقفة".equals(text)
                        || "Waiting for subtitles".equals(text)
                        || "Translation Paused".equals(text)) {
                    subtitleTextView.setText("");
                    subtitleBannerBox.setVisibility(View.GONE);
                } else {
                    subtitleTextView.setText(text);
                    subtitleBannerBox.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception ignored) {}
    }

    // إنشاء مسرح شفاف ثابت بحجم الشاشة ليتحرك بداخله شريط الترجمة بنعومة 60 إطار/ثانية بدون أي تقطيع
    private void ensureOverlayStageCreated() {
        if (fullScreenOverlayStage != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        fullScreenOverlayStage = new FrameLayout(this);
        fullScreenOverlayStage.setBackgroundColor(Color.TRANSPARENT);

        subtitleBannerBox = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        boxBackground.setColor(Color.argb(225, 18, 18, 26));
        boxBackground.setCornerRadius(24f);
        subtitleBannerBox.setBackground(boxBackground);
        subtitleBannerBox.setPadding(36, 16, 36, 16);
        subtitleBannerBox.setVisibility(View.GONE);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText("");
        subtitleTextView.setTextColor(Color.WHITE);
        subtitleTextView.setTextSize(24);
        subtitleTextView.setTypeface(Typeface.DEFAULT_BOLD);
        subtitleTextView.setGravity(Gravity.CENTER);

        subtitleBannerBox.addView(subtitleTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        int initialWidth = (int) (dm.widthPixels * 0.85f);
        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                initialWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        fullScreenOverlayStage.addView(subtitleBannerBox, bannerLp);

        subtitleBannerBox.setTranslationX((dm.widthPixels - initialWidth) / 2f);
        subtitleBannerBox.setTranslationY(dm.heightPixels * 0.78f);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        stageWindowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        stageWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        windowManager.addView(fullScreenOverlayStage, stageWindowParams);
    }

    // تحريك وتحديث فوري على كرت الشاشة (GPU Translation) بدون استدعاء WindowManager الثقيل نهائياً
    private void applySmoothHardwareSubtitleStyle(JSONObject style) {
        if (fullScreenOverlayStage == null || subtitleBannerBox == null || subtitleTextView == null) return;
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

            FrameLayout.LayoutParams bannerLp = (FrameLayout.LayoutParams) subtitleBannerBox.getLayoutParams();
            if (bannerLp.width != newWidthPx) {
                bannerLp.width = newWidthPx;
                subtitleBannerBox.setLayoutParams(bannerLp);
            }

            int padV = (int) (bgHeightPx * dm.density);
            int padH = (int) (24 * dm.density);
            subtitleBannerBox.setPadding(padH, padV, padH, padV);

            // تحريك فوري فائق النعومة باستخدام GPU TranslationX / TranslationY
            int posX = Math.max(10, Math.min(90, style.optInt("posX", 50)));
            int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));

            float centerX = dm.widthPixels * (posX / 100f);
            float targetX = Math.max(0f, Math.min(dm.widthPixels - newWidthPx, centerX - (newWidthPx / 2f)));

            float estBoxHeight = subtitleBannerBox.getHeight() > 0 ? subtitleBannerBox.getHeight() : (72f * dm.density);
            float centerY = dm.heightPixels * (posY / 100f);
            float targetY = Math.max(0f, Math.min(dm.heightPixels - estBoxHeight, centerY - (estBoxHeight / 2f)));

            subtitleBannerBox.setTranslationX(targetX);
            subtitleBannerBox.setTranslationY(targetY);
        } catch (Exception ignored) {}
    }

    private void rotateToFreshRoomCode() {
        roomCode = generateRandomRoomCode();
        if (codeView != null) {
            codeView.setText(roomCode);
        }
        loadQrAndWaitBridge();
    }

    // إلغاء الاقتران من التلفزيون مع إشعار الجوال وتغيير الكود فوراً لمنع إعادة الاقتران التلقائي
    private void performCleanUnpairAndRotateCode() {
        isPhonePaired = false;
        if (qrAndMqttWebView != null) {
            qrAndMqttWebView.evaluateJavascript(
                    "if(window.mqttClient && window.mqttClient.connected){ window.mqttClient.publish('siyaq/tv/sec/" + roomCode + "', JSON.stringify({type:'UNPAIRED'})); }",
                    null);
        }
        hideOverlayStage();
        updateUiForPairingState(false);
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                rotateToFreshRoomCode();
            }
        }, 180);
        Toast.makeText(this, isArabic ? "تم إلغاء الاقتران ✓" : "Unpaired ✓", Toast.LENGTH_SHORT).show();
    }

    private void hideOverlayStage() {
        if (fullScreenOverlayStage != null && windowManager != null) {
            try {
                windowManager.removeView(fullScreenOverlayStage);
            } catch (Exception ignored) {}
            fullScreenOverlayStage = null;
            subtitleBannerBox = null;
            subtitleTextView = null;
        }
    }

    private void updateUiForPairingState(boolean paired) {
        if (paired) {
            statusBadge.setText(isArabic ? "● متصل بالجوال" : "● Phone Connected");
            statusBadge.setTextColor(Color.parseColor("#00F2FE"));
        } else {
            statusBadge.setText(isArabic ? "غير متصل" : "Not Connected");
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
        hideOverlayStage();
        if (qrAndMqttWebView != null) {
            qrAndMqttWebView.destroy();
        }
    }
}
