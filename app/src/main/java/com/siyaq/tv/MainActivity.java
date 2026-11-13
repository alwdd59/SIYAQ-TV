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
import android.util.TypedValue;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ===== هوية SIYAQ: البرتقالي الذهبي والخلفية السوداء المطفية =====
    private static final String COLOR_ACCENT = "#F5A623";
    private static final String COLOR_ACCENT_LIGHT = "#FFD066";
    private static final String COLOR_BG = "#050507";
    private static final String COLOR_TEXT = "#FFFFFF";
    private static final String COLOR_TEXT_SOFT = "#C5CCD8";

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

    // طبقة شفافة ثابتة بحجم الشاشة يتحرك بداخلها شريط الترجمة بسرعة 60fps
    private WindowManager windowManager;
    private FrameLayout fullScreenOverlayStage;
    private FrameLayout subtitleBannerBox;
    private TextView subtitleTextView;
    private GradientDrawable boxBackground;
    private WindowManager.LayoutParams stageWindowParams;
    private PowerManager.WakeLock wakeLock;

    private JSONObject lastStyleJson = null;
    private JSONObject lastRoiJson = null;
    private boolean lastOverlayOverText = false;

    // ذاكرة تحميل وحفظ خطوط الألعاب الخمسة الحقيقية لتعمل على التلفاز مثل الجوال 100%
    private final Map<String, Typeface> customFontCache = new HashMap<>();
    private final ExecutorService fontDownloadExecutor = Executors.newSingleThreadExecutor();

    // {مفتاح الكاش, اسم الخط في Google Fonts CSS, رابط احتياطي مباشر}
    private static final String[][] GOOGLE_ARABIC_FONTS = new String[][]{
            {"IBMPlexSansArabic-Regular", "IBM+Plex+Sans+Arabic", "https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsansarabic/IBMPlexSansArabic-Regular.ttf"},
            {"IBMPlexSansArabic-Bold", "IBM+Plex+Sans+Arabic", "https://raw.githubusercontent.com/google/fonts/main/ofl/ibmplexsansarabic/IBMPlexSansArabic-Bold.ttf"},
            {"ReadexPro-Regular", "Readex+Pro", "https://raw.githubusercontent.com/google/fonts/main/ofl/readexpro/ReadexPro%5BHEXP%2Cwght%5D.ttf"},
            {"ReadexPro-Bold", "Readex+Pro", "https://raw.githubusercontent.com/google/fonts/main/ofl/readexpro/ReadexPro%5BHEXP%2Cwght%5D.ttf"},
            {"Tajawal-Regular", "Tajawal", "https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal/Tajawal-Regular.ttf"},
            {"Tajawal-Bold", "Tajawal", "https://raw.githubusercontent.com/google/fonts/main/ofl/tajawal/Tajawal-Bold.ttf"},
            {"Cairo-Regular", "Cairo", "https://raw.githubusercontent.com/google/fonts/main/ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf"},
            {"Cairo-Bold", "Cairo", "https://raw.githubusercontent.com/google/fonts/main/ofl/cairo/Cairo%5Bslnt%2Cwght%5D.ttf"},
            {"Changa-Regular", "Changa", "https://raw.githubusercontent.com/google/fonts/main/ofl/changa/Changa%5Bwght%5D.ttf"},
            {"Changa-Bold", "Changa", "https://raw.githubusercontent.com/google/fonts/main/ofl/changa/Changa%5Bwght%5D.ttf"}
    };

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable autoHideTestRunnable;

    private final Runnable keepAliveRunnable = new Runnable() {
        @Override
        public void run() {
            if (qrAndMqttWebView != null) {
                qrAndMqttWebView.resumeTimers();
            }
            uiHandler.postDelayed(this, 1500);
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

    private Typeface strongFont() {
        return Typeface.create("sans-serif-medium", Typeface.BOLD);
    }

    // تحميل وتخزين خطوط الألعاب الخمسة (IBM Plex, Readex Pro, Tajawal, Cairo, Changa) بصيغة TTF ثابتة للتلفاز
    private void preloadArabicGoogleFontsAsync() {
        fontDownloadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                File fontDir = new File(getFilesDir(), "siyaq_fonts_v2");
                if (!fontDir.exists()) {
                    fontDir.mkdirs();
                }
                boolean anyNewLoaded = false;
                for (String[] entry : GOOGLE_ARABIC_FONTS) {
                    String key = entry[0];
                    String cssFamily = entry[1];
                    String fallbackUrl = entry[2];
                    synchronized (customFontCache) {
                        if (customFontCache.containsKey(key)) continue;
                    }

                    File outFile = new File(fontDir, key + ".ttf");
                    if (!outFile.exists() || outFile.length() < 1000) {
                        try {
                            String weight = key.endsWith("Bold") ? "700" : "400";
                            String cssUrl = "https://fonts.googleapis.com/css?family=" + cssFamily + ":" + weight;
                            HttpURLConnection cssConn = (HttpURLConnection) new URL(cssUrl).openConnection();
                            cssConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; U; Android 4.4.2; en-us)");
                            cssConn.setConnectTimeout(4000);
                            cssConn.setReadTimeout(5000);
                            String directTtfUrl = fallbackUrl;
                            if (cssConn.getResponseCode() == 200) {
                                InputStream cis = cssConn.getInputStream();
                                byte[] cbuf = new byte[4096];
                                int cn = cis.read(cbuf);
                                cis.close();
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
                            cssConn.disconnect();

                            URL url = new URL(directTtfUrl);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(7000);
                            conn.connect();
                            if (conn.getResponseCode() == 200) {
                                InputStream in = conn.getInputStream();
                                FileOutputStream out = new FileOutputStream(outFile);
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                }
                                out.close();
                                in.close();
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

                if (anyNewLoaded && lastStyleJson != null) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            applySmoothHardwareSubtitleStyle(lastStyleJson);
                        }
                    });
                }
            }
        });
    }

    private Typeface resolveRequestedArabicTypeface(String fontFamilyRaw, boolean isBold) {
        String familyBase = "IBMPlexSansArabic";
        if (fontFamilyRaw != null) {
            if (fontFamilyRaw.contains("IBM") || fontFamilyRaw.contains("Plex")) familyBase = "IBMPlexSansArabic";
            else if (fontFamilyRaw.contains("Readex")) familyBase = "ReadexPro";
            else if (fontFamilyRaw.contains("Tajawal")) familyBase = "Tajawal";
            else if (fontFamilyRaw.contains("Cairo")) familyBase = "Cairo";
            else if (fontFamilyRaw.contains("Changa")) familyBase = "Changa";
        }
        String key = familyBase + (isBold ? "-Bold" : "-Regular");
        synchronized (customFontCache) {
            Typeface cached = customFontCache.get(key);
            if (cached != null) {
                return Typeface.create(cached, isBold ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        return Typeface.create("sans-serif-medium", isBold ? Typeface.BOLD : Typeface.NORMAL);
    }

    // فيو مخصص يرسم شعار SIYAQ الهندسي
    private class SiyaqWordmarkLogoView extends View {
        private final Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
            float scale = Math.min(w / 84f, h / 34f);
            float ox = (w - 100f * scale) / 2f;
            float oy = (h - 100f * scale) / 2f;

            // حرف S
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

            // حرف I البرتقالي الذهبي المتدرج
            accentBarPaint.setShader(new LinearGradient(
                    0, oy + 35.4f * scale, 0, oy + 64.9f * scale,
                    Color.parseColor(COLOR_ACCENT_LIGHT),
                    Color.parseColor(COLOR_ACCENT),
                    Shader.TileMode.CLAMP));
            iRect.set(ox + 29.5f * scale, oy + 35.4f * scale, ox + 34.1f * scale, oy + 64.9f * scale);
            c.drawRect(iRect, accentBarPaint);

            // حرف Y
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

            // حرف A
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

            // حرف Q
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

        preloadArabicGoogleFontsAsync();
        roomCode = generateRandomRoomCode();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.parseColor(COLOR_BG));
        int padH = (int) (screenW * 0.05f);
        int padV = (int) (screenH * 0.04f);
        rootFrame.setPadding(padH, padV, padH, padV);

        // 1. زر تغيير اللغة (فوق يسار)
        langToggleBtn = new Button(this);
        langToggleBtn.setText("EN / ع");
        langToggleBtn.setFocusable(true);
        langToggleBtn.setFocusableInTouchMode(true);
        langToggleBtn.setBackgroundColor(Color.TRANSPARENT);
        langToggleBtn.setTextColor(Color.parseColor(COLOR_TEXT_SOFT));
        langToggleBtn.setTextSize(16);
        langToggleBtn.setTypeface(strongFont());
        langToggleBtn.setPadding(20, 10, 20, 10);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            langToggleBtn.setStateListAnimator(null);
            langToggleBtn.setElevation(0f);
        }
        langToggleBtn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                langToggleBtn.setTextColor(hasFocus ? Color.parseColor(COLOR_ACCENT) : Color.parseColor(COLOR_TEXT_SOFT));
                langToggleBtn.setTextSize(hasFocus ? 18 : 16);
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

        // 2. الشعار فوق في المنتصف + حالة الاتصال تحته
        LinearLayout topLogoContainer = new LinearLayout(this);
        topLogoContainer.setOrientation(LinearLayout.VERTICAL);
        topLogoContainer.setGravity(Gravity.CENTER);

        int logoW = Math.max(150, (int) (screenW * 0.16f));
        int logoH = Math.max(54, (int) (screenH * 0.085f));
        SiyaqWordmarkLogoView wordmarkView = new SiyaqWordmarkLogoView(this);
        topLogoContainer.addView(wordmarkView, new LinearLayout.LayoutParams(logoW, logoH));

        statusBadge = new TextView(this);
        statusBadge.setText("غير متصل");
        statusBadge.setTextColor(Color.parseColor(COLOR_TEXT_SOFT));
        statusBadge.setTextSize(14);
        statusBadge.setTypeface(strongFont());
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(0, 6, 0, 0);
        topLogoContainer.addView(statusBadge);

        FrameLayout.LayoutParams topLogoLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rootFrame.addView(topLogoContainer, topLogoLp);

        // 3. الحاوية الوسطى
        centerClusterRow = new LinearLayout(this);
        centerClusterRow.setOrientation(LinearLayout.HORIZONTAL);
        centerClusterRow.setGravity(Gravity.CENTER);

        // --- عمود الباركود والكود ---
        qrColumn = new LinearLayout(this);
        qrColumn.setOrientation(LinearLayout.VERTICAL);
        qrColumn.setGravity(Gravity.CENTER);
        qrColumn.setPadding(36, 16, 36, 16);

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
        codeView.setTextColor(Color.parseColor(COLOR_ACCENT));
        codeView.setTextSize(26);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, 22, 0, 0);
        qrColumn.addView(codeView);

        // --- عمود الخيارات ---
        optionsColumn = new LinearLayout(this);
        optionsColumn.setOrientation(LinearLayout.VERTICAL);
        optionsColumn.setGravity(Gravity.CENTER_VERTICAL);
        optionsColumn.setPadding(36, 16, 36, 16);

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
                Toast.makeText(MainActivity.this, isArabic ? "تم تجديد الرمز" : "Code refreshed", Toast.LENGTH_SHORT).show();
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

        applyLanguageAndLayoutDirection();
        btnTestBar.requestFocus();
        loadQrAndWaitBridge();
        uiHandler.post(keepAliveRunnable);
    }

    private String generateRandomRoomCode() {
        int num = 10000000 + new Random().nextInt(90000000);
        return String.valueOf(num);
    }

    private void applyLanguageAndLayoutDirection() {
        centerClusterRow.removeAllViews();

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.setMargins(96, 0, 96, 0);

        if (isArabic) {
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
        btn.setTextColor(Color.parseColor(COLOR_TEXT));
        btn.setTextSize(19);
        btn.setTypeface(strongFont());
        btn.setPadding(20, 18, 20, 18);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            btn.setStateListAnimator(null);
            btn.setElevation(0f);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 14, 0, 14);
        btn.setLayoutParams(lp);

        btn.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    btn.setTextColor(Color.parseColor(COLOR_ACCENT));
                    btn.setTextSize(21);
                } else {
                    btn.setTextColor(Color.parseColor(COLOR_TEXT));
                    btn.setTextSize(19);
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
                    ? "اختبار شريط الترجمة — جاهز للعرض فوق اللعبة"
                    : "Subtitle Bar Test — Ready over game");
            subtitleBannerBox.setVisibility(View.VISIBLE);
            positionBannerAccurately(0.50f, 0.82f);

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
            Toast.makeText(this, isArabic ? "الصلاحية مفعلة" : "Permission Active", Toast.LENGTH_SHORT).show();
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

            if ("UNPAIRED".equals(type)) {
                hideOverlayStage();
                isPhonePaired = false;
                updateUiForPairingState(false);
                rotateToFreshRoomCode();
                Toast.makeText(this, isArabic ? "تم إلغاء الاقتران" : "Unpaired", Toast.LENGTH_SHORT).show();
                return;
            }

            if ("SUB_CLEAR".equals(type)) {
                if (subtitleBannerBox != null) {
                    subtitleTextView.setText("");
                    subtitleBannerBox.setVisibility(View.GONE);
                }
                return;
            }

            if ("ROI_UPDATE".equals(type)) {
                lastOverlayOverText = obj.optBoolean("overlayOverText", lastOverlayOverText);
                JSONObject roi = obj.optJSONObject("roi");
                if (roi != null) {
                    lastRoiJson = roi;
                }
                if (lastStyleJson != null) {
                    applySmoothHardwareSubtitleStyle(lastStyleJson);
                }
                return;
            }

            if ("PAIRED".equals(type)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, isArabic ? "يرجى تفعيل صلاحية الشاشة أولاً" : "Please enable screen permission first", Toast.LENGTH_LONG).show();
                    return;
                }

                isPhonePaired = true;
                ensureOverlayStageCreated();
                updateUiForPairingState(true);

                lastOverlayOverText = obj.optBoolean("overlayOverText", false);
                JSONObject roi = obj.optJSONObject("roi");
                if (roi != null) lastRoiJson = roi;

                subtitleTextView.setText(isArabic ? "متصل بالجوال — جاهز للترجمة الفورية" : "Connected to Phone — Ready for Live Translation");
                subtitleBannerBox.setVisibility(View.VISIBLE);

                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    lastStyleJson = style;
                    applySmoothHardwareSubtitleStyle(style);
                } else {
                    positionBannerAccurately(0.50f, 0.82f);
                }

                Toast.makeText(this, isArabic ? "تم الاقتران بالجوال" : "Paired with Phone", Toast.LENGTH_SHORT).show();
                moveTaskToBack(true);
                return;
            }

            if ("SUB_UPDATE".equals(type)) {
                if (!isPhonePaired) {
                    isPhonePaired = true;
                    updateUiForPairingState(true);
                }

                ensureOverlayStageCreated();

                if (obj.has("overlayOverText")) {
                    lastOverlayOverText = obj.optBoolean("overlayOverText", false);
                }
                JSONObject roi = obj.optJSONObject("roi");
                if (roi != null) {
                    lastRoiJson = roi;
                }

                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    lastStyleJson = style;
                }

                String text = obj.optString("text", "").replace("✓", "").trim();
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
                    if (lastStyleJson != null) {
                        applySmoothHardwareSubtitleStyle(lastStyleJson);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void ensureOverlayStageCreated() {
        if (fullScreenOverlayStage != null) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        fullScreenOverlayStage = new FrameLayout(this);
        fullScreenOverlayStage.setBackgroundColor(Color.TRANSPARENT);

        subtitleBannerBox = new FrameLayout(this);
        boxBackground = new GradientDrawable();
        boxBackground.setShape(GradientDrawable.RECTANGLE);
        boxBackground.setColor(Color.argb(217, 0, 0, 0));
        boxBackground.setCornerRadius(0f);
        boxBackground.setStroke(0, Color.TRANSPARENT);
        subtitleBannerBox.setBackground(boxBackground);
        subtitleBannerBox.setPadding(24, 12, 24, 12);
        subtitleBannerBox.setVisibility(View.GONE);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText("");
        subtitleTextView.setTextColor(Color.WHITE);
        subtitleTextView.setAlpha(1.0f);
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        subtitleTextView.setTypeface(resolveRequestedArabicTypeface("IBM Plex Sans Arabic", true));
        subtitleTextView.setGravity(Gravity.CENTER);
        subtitleTextView.setIncludeFontPadding(false);
        subtitleTextView.setMaxWidth((int) (dm.widthPixels * 0.85f));

        subtitleTextView.setPaintFlags(subtitleTextView.getPaintFlags()
                | Paint.ANTI_ALIAS_FLAG
                | Paint.SUBPIXEL_TEXT_FLAG
                | Paint.LINEAR_TEXT_FLAG);
        subtitleTextView.getPaint().setFakeBoldText(true);
        subtitleTextView.setShadowLayer(4.5f, 0f, 2f, Color.argb(240, 0, 0, 0));

        // WRAP_CONTENT لكي يلتف الصندوق الأسود حول طول النص الفعلي بدقة (مثل Red Dead و GTA)
        subtitleBannerBox.addView(subtitleTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        fullScreenOverlayStage.addView(subtitleBannerBox, bannerLp);

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

    private void positionBannerAccurately(float normCenterX, float normCenterY) {
        if (subtitleBannerBox == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        int specW = View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST);
        int specH = View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST);
        subtitleBannerBox.measure(specW, specH);

        int boxW = subtitleBannerBox.getMeasuredWidth();
        int boxH = subtitleBannerBox.getMeasuredHeight();

        float centerX = dm.widthPixels * normCenterX;
        float centerY = dm.heightPixels * normCenterY;

        float targetX = Math.max(0f, Math.min(dm.widthPixels - boxW, centerX - (boxW / 2f)));
        float targetY = Math.max(0f, Math.min(dm.heightPixels - boxH, centerY - (boxH / 2f)));

        subtitleBannerBox.setTranslationX(targetX);
        subtitleBannerBox.setTranslationY(targetY);
    }

    private void applySmoothHardwareSubtitleStyle(JSONObject style) {
        if (fullScreenOverlayStage == null || subtitleBannerBox == null || subtitleTextView == null) return;
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();

            // 1. تطبيق الخط المختار من الجوال (IBM Plex, Readex Pro, Tajawal, Cairo, Changa)
            String fontFamilyRaw = style.optString("fontFamily", "IBM Plex Sans Arabic");
            boolean isBold = style.optBoolean("isBold", true);
            Typeface resolvedTf = resolveRequestedArabicTypeface(fontFamilyRaw, isBold);
            subtitleTextView.setTypeface(resolvedTf);
            subtitleTextView.getPaint().setFakeBoldText(isBold);

            // 2. حجم الخط بوضوح كامل على شاشة التلفاز
            int fontSize = style.optInt("fontSize", 24);
            subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize * 1.22f);

            // 3. لون النص وشفافيته
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

            // 4. خلفية متوازنة تلتف حول حدود النص + تغطية كاملة عند تفعيل وضع «فوق النص الأصلي»
            String bgColorStr = style.optString("bgColor", "#000000");
            if ("#12121A".equalsIgnoreCase(bgColorStr) || "#0C0C10".equalsIgnoreCase(bgColorStr)) {
                bgColorStr = "#000000";
            }
            int bgOpacity = style.optInt("bgOpacity", 85);
            if (lastOverlayOverText && bgOpacity < 92) {
                bgOpacity = 92; // تغطية محكمة للنص الإنجليزي الأصلي تحته
            }
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

            // 5. أقصى عرض للشريط والحواف المتناسقة
            int bgWidthPct = Math.max(25, Math.min(100, style.optInt("bgWidth", 85)));
            int bgHeightPx = Math.max(2, Math.min(32, style.optInt("bgHeight", 10)));
            int maxWidthPx = (int) (dm.widthPixels * (bgWidthPct / 100f));
            subtitleTextView.setMaxWidth(maxWidthPx);

            int padV = (int) (bgHeightPx * dm.density);
            int padH = (int) (Math.max(8, Math.round(bgHeightPx * 1.45f)) * dm.density);
            subtitleBannerBox.setPadding(padH, padV, padH, padV);

            // 6. الموقع: تلقائي فوق النص الأصلي بناءً على مربع الجوال، أو يدوي في الوضع الطبيعي
            float normCenterX;
            float normCenterY;

            if (lastOverlayOverText && lastRoiJson != null) {
                double rLeft = lastRoiJson.optDouble("left", 0.08);
                double rTop = lastRoiJson.optDouble("top", 0.72);
                double rWidth = lastRoiJson.optDouble("width", 0.84);
                double rHeight = lastRoiJson.optDouble("height", 0.16);

                normCenterX = (float) Math.max(0.10, Math.min(0.90, rLeft + (rWidth / 2.0)));
                normCenterY = (float) Math.max(0.08, Math.min(0.94, rTop + (rHeight / 2.0)));
            } else {
                int posX = Math.max(10, Math.min(90, style.optInt("posX", 50)));
                int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));
                normCenterX = posX / 100f;
                normCenterY = posY / 100f;
            }

            positionBannerAccurately(normCenterX, normCenterY);
        } catch (Exception ignored) {}
    }

    private void rotateToFreshRoomCode() {
        roomCode = generateRandomRoomCode();
        if (codeView != null) {
            codeView.setText(roomCode);
        }
        loadQrAndWaitBridge();
    }

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
        Toast.makeText(this, isArabic ? "تم إلغاء الاقتران" : "Unpaired", Toast.LENGTH_SHORT).show();
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
            statusBadge.setTextColor(Color.parseColor(COLOR_ACCENT));
        } else {
            statusBadge.setText(isArabic ? "غير متصل" : "Not Connected");
            statusBadge.setTextColor(Color.parseColor(COLOR_TEXT_SOFT));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fontDownloadExecutor.shutdownNow();
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
