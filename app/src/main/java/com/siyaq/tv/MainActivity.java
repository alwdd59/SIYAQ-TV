package com.siyaq.tv;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY_PERMISSION = 1001;
    private static final int REQ_MEDIA_PROJECTION = 1002;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private PowerManager.WakeLock wakeLock;

    // عناصر واجهة التلفاز الرئيسية
    private String roomCode = "";
    private TextView tvStatusBadge;
    private Button btnOverlayPerm;
    private Button btnScreenCapturePerm;
    private Button btnTestHdmi;
    private Button btnResetCode;
    private ImageView qrImageView;

    // طبقة العرض العائمة فوق HDMI وجميع الألعاب
    private static FrameLayout rootOverlayContainer;
    private static TextView singleSubtitleBox;
    private static SpatialOverlayView spatialOverlayView;

    // حالة الربط مع الجوال
    private volatile boolean isServiceRunning = true;
    private volatile boolean isPairedWithPhone = false;
    private volatile String activeTvMode = "camera"; // "camera" | "screen_ocr"
    private volatile boolean isScreenOcrActive = false;

    // إعدادات مظهر الترجمة المستلمة من الجوال
    private final SubtitleStyle currentStyle = new SubtitleStyle();

    // متغيرات التقاط الشاشة وتحديد أماكن النصوص (MediaProjection)
    private static MediaProjection mediaProjection;
    private static VirtualDisplay virtualDisplay;
    private static ImageReader imageReader;
    private volatile boolean isCapturingFrame = false;
    private int[] lastScreenSignature = null;

    // متغيرات اتصال MQTT المباشر
    private Socket mqttSocket;
    private OutputStream mqttOut;
    private final Object mqttWriteLock = new Object();

    // هيكل إعدادات المظهر
    private static class SubtitleStyle {
        int fontSize = 24;
        boolean isBold = true;
        String textColor = "#FFFFFF";
        int textOpacity = 100;
        String bgColor = "#000000";
        int bgOpacity = 85;
        int bgWidthPct = 85;
        int bgPaddingPx = 10;
        int posX = 50;
        int posY = 82;
    }

    // هيكل النص المكاني المترجم فوق موقعه الأصلي
    private static class SpatialItem {
        String id;
        String ar;
        float x, y, w, h;

        SpatialItem(String id, String ar, float x, float y, float w, float h) {
            this.id = id;
            this.ar = ar;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    // هيكل مستطيل النص المكتشف على شاشة التلفاز
    private static class DetectedBox {
        int x0, y0, x1, y1;
        DetectedBox(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        windowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
        acquireWakeLock();

        SharedPreferences prefs = getSharedPreferences("siyaq_tv_prefs", Context.MODE_PRIVATE);
        roomCode = prefs.getString("room_code", "");
        if (roomCode == null || roomCode.length() != 8) {
            roomCode = generateRandom8DigitCode();
            prefs.edit().putString("room_code", roomCode).apply();
        }

        setContentView(buildTvMainDashboard());
        ensureOverlayAttached();
        loadQrCodeImageAsync();
        startMqttEngineThread();
        startScreenScannerLoopThread();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshButtonsState();
    }

    // ================= 1. بناء واجهة التلفاز الاحترافية (متوافقة مع ريموت التلفاز) =================
    private View buildTvMainDashboard() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.parseColor("#050507"));
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(40), dp(26), dp(40), dp(26));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }

        // العمود الأيمن: العنوان + الحالة + الأزرار الأربعة
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        rightLp.setMarginEnd(dp(30));
        rightCol.setLayoutParams(rightLp);

        TextView titleView = new TextView(this);
        titleView.setText("SIYAQ TV | سياق للتلفاز");
        titleView.setTextColor(Color.parseColor("#F5A623"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        titleView.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));

        TextView descView = new TextView(this);
        descView.setText("مترجم الألعاب الفوري فوق منافذ HDMI وكامل الشاشة (الحوارات والقوائم)");
        descView.setTextColor(Color.parseColor("#94A3B8"));
        descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        descView.setPadding(0, dp(4), 0, dp(14));

        tvStatusBadge = new TextView(this);
        tvStatusBadge.setText("حالة الاتصال: جاري الاتصال بالسيرفر وبانتظار الجوال...");
        tvStatusBadge.setTextColor(Color.parseColor("#F8FAFC"));
        tvStatusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvStatusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        tvStatusBadge.setBackground(createRoundedDrawable("#0C0C10", "#1C1C24", dp(14), dp(1)));
        tvStatusBadge.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout buttonsBox = new LinearLayout(this);
        buttonsBox.setOrientation(LinearLayout.VERTICAL);
        buttonsBox.setPadding(0, dp(16), 0, 0);

        // الزر 1: صلاحية الظهور فوق الشاشة و HDMI
        btnOverlayPerm = createRemoteButton("1. تفعيل صلاحية الظهور فوق الشاشة (مطلوب لـ HDMI)", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        });

        // الزر 2: صلاحية قراءة الشاشة الكاملة (وضع الشاشة)
        btnScreenCapturePerm = createRemoteButton("2. تفعيل قراءة الشاشة الكاملة (لوضع الشاشة والترجمة المكانية)", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestMediaProjectionPermission();
            }
        });

        // الزر 3: اختبار إظهار الترجمة فوق HDMI (بدلاً من اختبار شريط الترجمة)
        btnTestHdmi = createRemoteButton("اختبار إظهار الترجمة فوق HDMI", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                triggerHdmiOverlayTest();
            }
        });

        // الزر 4: إلغاء الاقتران / توليد رمز جديد
        btnResetCode = createRemoteButton("إلغاء الاقتران / توليد رمز جديد", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetRoomCodeAndUnpair();
            }
        });

        buttonsBox.addView(btnOverlayPerm);
        buttonsBox.addView(createSpace(dp(9)));
        buttonsBox.addView(btnScreenCapturePerm);
        buttonsBox.addView(createSpace(dp(9)));
        buttonsBox.addView(btnTestHdmi);
        buttonsBox.addView(createSpace(dp(9)));
        buttonsBox.addView(btnResetCode);

        rightCol.addView(titleView);
        rightCol.addView(descView);
        rightCol.addView(tvStatusBadge);
        rightCol.addView(buttonsBox);

        // العمود الأيسر: بطاقة الباركود QR ورقم الغرفة
        LinearLayout leftCard = new LinearLayout(this);
        leftCard.setOrientation(LinearLayout.VERTICAL);
        leftCard.setGravity(Gravity.CENTER);
        leftCard.setBackground(createRoundedDrawable("#0C0C10", "#F5A623", dp(24), dp(2)));
        leftCard.setPadding(dp(24), dp(20), dp(24), dp(20));
        leftCard.setLayoutParams(new LinearLayout.LayoutParams(dp(290), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView qrTitle = new TextView(this);
        qrTitle.setText("امسح الرمز من تطبيق الجوال");
        qrTitle.setTextColor(Color.WHITE);
        qrTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        qrTitle.setTypeface(Typeface.DEFAULT_BOLD);
        qrTitle.setGravity(Gravity.CENTER);
        qrTitle.setPadding(0, 0, 0, dp(10));

        qrImageView = new ImageView(this);
        qrImageView.setLayoutParams(new LinearLayout.LayoutParams(dp(180), dp(180)));
        qrImageView.setBackground(createRoundedDrawable("#FFFFFF", "#FFFFFF", dp(14), dp(1)));
        qrImageView.setPadding(dp(8), dp(8), dp(8), dp(8));
        qrImageView.setImageBitmap(createFallbackCodeBitmap(roomCode));

        TextView manualTitle = new TextView(this);
        manualTitle.setText("أو أدخل رقم الكود في الجوال:");
        manualTitle.setTextColor(Color.parseColor("#94A3B8"));
        manualTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        manualTitle.setGravity(Gravity.CENTER);
        manualTitle.setPadding(0, dp(12), 0, dp(4));

        TextView codeText = new TextView(this);
        codeText.setText(roomCode.substring(0, 4) + " " + roomCode.substring(4));
        codeText.setTextColor(Color.parseColor("#F5A623"));
        codeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f);
        codeText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeText.setGravity(Gravity.CENTER);

        leftCard.addView(qrTitle);
        leftCard.addView(qrImageView);
        leftCard.addView(manualTitle);
        leftCard.addView(codeText);

        root.addView(rightCol);
        root.addView(leftCard);

        btnTestHdmi.requestFocus();
        return root;
    }

    private Button createRemoteButton(String text, final boolean isPrimary, View.OnClickListener listener) {
        final Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        final String bgNorm = isPrimary ? "#1E160A" : "#0C0C10";
        final String strokeNorm = isPrimary ? "#F5A623" : "#262632";
        b.setBackground(createRoundedDrawable(bgNorm, strokeNorm, dp(14), dp(1)));
        b.setTextColor(isPrimary ? Color.parseColor("#F5A623") : Color.WHITE);

        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    b.setBackground(createRoundedDrawable("#F5A623", "#FFFFFF", dp(14), dp(2)));
                    b.setTextColor(Color.parseColor("#050507"));
                    b.setScaleX(1.02f);
                    b.setScaleY(1.02f);
                } else {
                    b.setBackground(createRoundedDrawable(bgNorm, strokeNorm, dp(14), dp(1)));
                    b.setTextColor(isPrimary ? Color.parseColor("#F5A623") : Color.WHITE);
                    b.setScaleX(1.0f);
                    b.setScaleY(1.0f);
                }
            }
        });
        b.setOnClickListener(listener);
        return b;
    }

    private void refreshButtonsState() {
        boolean hasOverlay = canDrawOverlaysCompat();
        if (btnOverlayPerm != null) {
            btnOverlayPerm.setText(hasOverlay
                    ? "1. صلاحية الظهور فوق الشاشة و HDMI: مفعّلة وجاهزة ✓"
                    : "1. تفعيل صلاحية الظهور فوق الشاشة (مطلوب لـ HDMI)");
        }
        if (btnScreenCapturePerm != null) {
            btnScreenCapturePerm.setText(mediaProjection != null
                    ? "2. قراءة الشاشة الكاملة (وضع الشاشة): مفعّلة وجاهزة ✓"
                    : "2. تفعيل قراءة الشاشة الكاملة (لوضع الشاشة والترجمة المكانية)");
        }
    }

    // ================= 2. طبقة العرض العائمة فوق HDMI وجميع التطبيقات =================
    private void ensureOverlayAttached() {
        if (!canDrawOverlaysCompat()) return;
        if (rootOverlayContainer != null) return;

        int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        rootOverlayContainer = new FrameLayout(getApplicationContext());
        rootOverlayContainer.setBackgroundColor(Color.TRANSPARENT);

        spatialOverlayView = new SpatialOverlayView(getApplicationContext());
        spatialOverlayView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        spatialOverlayView.setVisibility(View.GONE);

        singleSubtitleBox = new TextView(getApplicationContext());
        singleSubtitleBox.setVisibility(View.GONE);
        singleSubtitleBox.setGravity(Gravity.CENTER);
        singleSubtitleBox.setTextColor(Color.WHITE);
        singleSubtitleBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        singleSubtitleBox.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        rootOverlayContainer.addView(spatialOverlayView);
        rootOverlayContainer.addView(singleSubtitleBox);

        try {
            windowManager.addView(rootOverlayContainer, params);
        } catch (Exception ignored) {}
    }

    // اختبار إظهار الترجمة فوق HDMI لمدة 25 ثانية (يعرض شريط حوار + ترجمة قوائم مكانية)
    private void triggerHdmiOverlayTest() {
        if (!canDrawOverlaysCompat()) {
            Toast.makeText(this, "يرجى الضغط على الزر الأول لتفعيل صلاحية الظهور فوق الشاشة أولاً", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        ensureOverlayAttached();

        renderSingleSubtitleBox(
                "اختبار SIYAQ فوق HDMI: حوّل الآن بالريموت إلى منفذ اللعبة (HDMI) للتأكد من بقاء الترجمة فوق الشاشة ✓",
                currentStyle
        );

        List<SpatialItem> sampleItems = new ArrayList<>();
        sampleItems.add(new SpatialItem("m1", "المهمة الرئيسية: استكشاف المنطقة", 0.05f, 0.08f, 0.28f, 0.055f));
        sampleItems.add(new SpatialItem("m2", "الحقيبة والمعدات", 0.05f, 0.16f, 0.16f, 0.048f));
        sampleItems.add(new SpatialItem("m3", "الخريطة", 0.05f, 0.23f, 0.12f, 0.048f));

        if (spatialOverlayView != null) {
            spatialOverlayView.setItems(sampleItems, currentStyle);
            spatialOverlayView.setVisibility(View.VISIBLE);
        }

        Toast.makeText(this, "تم إظهار الترجمة فوق النظام! انتقل الآن إلى منفذ HDMI لمشاهدتها فوق اللعبة", Toast.LENGTH_LONG).show();

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isPairedWithPhone) {
                    clearAllOverlays();
                }
            }
        }, 25000);
    }

    private void renderSingleSubtitleBox(String text, SubtitleStyle style) {
        ensureOverlayAttached();
        if (singleSubtitleBox == null) return;

        if (text == null || text.trim().isEmpty()) {
            singleSubtitleBox.setVisibility(View.GONE);
            return;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = dm.widthPixels;
        int sh = dm.heightPixels;

        int maxBoxW = (int) (sw * (clamp(style.bgWidthPct, 25, 100) / 100f));
        int padV = dp(clamp(style.bgPaddingPx, 2, 36));
        int padH = dp(clamp((int) (style.bgPaddingPx * 1.45f), 8, 48));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(0f);
        bg.setColor(applyAlpha(style.bgColor, style.bgOpacity));

        singleSubtitleBox.setMaxWidth(maxBoxW);
        singleSubtitleBox.setPadding(padH, padV, padH, padV);
        singleSubtitleBox.setBackground(bg);
        singleSubtitleBox.setTextColor(applyAlpha(style.textColor, style.textOpacity));
        singleSubtitleBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize);
        singleSubtitleBox.setTypeface(Typeface.create("sans-serif", style.isBold ? Typeface.BOLD : Typeface.NORMAL));

        if (style.bgOpacity < 20) {
            singleSubtitleBox.setShadowLayer(6f, 0f, 2f, Color.BLACK);
        } else {
            singleSubtitleBox.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }

        singleSubtitleBox.setText(text);
        singleSubtitleBox.setVisibility(View.VISIBLE);

        singleSubtitleBox.measure(
                View.MeasureSpec.makeMeasureSpec(maxBoxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
        );
        int mw = singleSubtitleBox.getMeasuredWidth();
        int mh = singleSubtitleBox.getMeasuredHeight();

        int cx = (int) (sw * (clamp(style.posX, 10, 90) / 100f));
        int cy = (int) (sh * (clamp(style.posY, 10, 92) / 100f));

        int left = clamp(cx - mw / 2, dp(10), Math.max(dp(10), sw - mw - dp(10)));
        int top = clamp(cy - mh / 2, dp(10), Math.max(dp(10), sh - mh - dp(10)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        singleSubtitleBox.setLayoutParams(lp);
    }

    private void clearAllOverlays() {
        if (singleSubtitleBox != null) singleSubtitleBox.setVisibility(View.GONE);
        if (spatialOverlayView != null) {
            spatialOverlayView.clear();
            spatialOverlayView.setVisibility(View.GONE);
        }
    }

    // ================= 3. كاشف أماكن النصوص على الشاشة الكاملة وإرسالها للجوال =================
    private void startScreenScannerLoopThread() {
        Thread scannerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isServiceRunning) {
                    try {
                        if (isPairedWithPhone && "screen_ocr".equals(activeTvMode) && isScreenOcrActive && imageReader != null && !isCapturingFrame) {
                            captureAndSendScreenTextRegionsToPhone();
                        }
                        Thread.sleep(320);
                    } catch (Exception ignored) {}
                }
            }
        });
        scannerThread.setDaemon(true);
        scannerThread.start();
    }

    private void captureAndSendScreenTextRegionsToPhone() {
        if (imageReader == null) return;
        isCapturingFrame = true;

        try {
            // إخفاء الترجمة العائمة لـ 20ms فقط أثناء التقاط الصورة حتى لا يقرأ التلفاز الترجمة العربية
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (spatialOverlayView != null) spatialOverlayView.setAlpha(0f);
                }
            });
            Thread.sleep(22);

            Image image = imageReader.acquireLatestImage();
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (spatialOverlayView != null) spatialOverlayView.setAlpha(1f);
                }
            });

            if (image == null) {
                isCapturingFrame = false;
                return;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap rawBmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
            );
            rawBmp.copyPixelsFromBuffer(buffer);
            image.close();

            Bitmap screenBmp = (rawBmp.getWidth() != width)
                    ? Bitmap.createBitmap(rawBmp, 0, 0, width, height)
                    : rawBmp;
            if (screenBmp != rawBmp) rawBmp.recycle();

            // فحص ثبات الشاشة: إذا لم تتغير الشاشة لا نرهق الجوال ولا نعيد الإرسال
            if (!hasScreenChanged(screenBmp)) {
                screenBmp.recycle();
                isCapturingFrame = false;
                return;
            }

            // تحديد أماكن النصوص (الحوارات + القوائم) عبر مسح حواف التباين عالية الدقة
            List<DetectedBox> boxes = detectTextRegionsOnScreen(screenBmp);
            JSONArray cropsArray = new JSONArray();

            for (int i = 0; i < boxes.size(); i++) {
                DetectedBox b = boxes.get(i);
                String base64Jpeg = cropAndPrepareForPhoneOcr(screenBmp, b);
                if (base64Jpeg == null) continue;

                float normX = clampFloat((float) b.x0 / width, 0f, 0.98f);
                float normY = clampFloat((float) b.y0 / height, 0f, 0.98f);
                float normW = clampFloat((float) (b.x1 - b.x0) / width, 0.04f, 1f - normX);
                float normH = clampFloat((float) (b.y1 - b.y0) / height, 0.03f, 1f - normY);

                JSONObject cropObj = new JSONObject();
                cropObj.put("id", "crop_" + i);
                cropObj.put("imageBase64", "data:image/jpeg;base64," + base64Jpeg);
                cropObj.put("x", normX);
                cropObj.put("y", normY);
                cropObj.put("w", normW);
                cropObj.put("h", normH);
                cropsArray.put(cropObj);
            }

            screenBmp.recycle();

            JSONObject packet = new JSONObject();
            packet.put("type", "SCREEN_CROPS_BATCH");
            packet.put("frameId", System.currentTimeMillis());
            packet.put("screenWidth", width);
            packet.put("screenHeight", height);
            packet.put("crops", cropsArray);

            // إرسال قصاصات النصوص مع إحداثياتها للجوال ليقوم الجوال بالـ OCR والترجمة وإعادتها
            publishMqttJson("siyaq/tv/ocr/" + roomCode, packet);
        } catch (Exception ignored) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (spatialOverlayView != null) spatialOverlayView.setAlpha(1f);
                }
            });
        }
        isCapturingFrame = false;
    }

    // خوارزمية تحديد أماكن النصوص والقوائم والحوارات على الشاشة الكاملة
    private List<DetectedBox> detectTextRegionsOnScreen(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int gridCols = 32;
        int gridRows = 18;
        int cellW = w / gridCols;
        int cellH = h / gridRows;

        boolean[][] textCells = new boolean[gridRows][gridCols];
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        for (int r = 1; r < gridRows - 1; r++) {
            int yStart = r * cellH;
            int yEnd = yStart + cellH;
            for (int c = 1; c < gridCols - 1; c++) {
                int xStart = c * cellW;
                int xEnd = xStart + cellW;
                int sharpTransitions = 0;

                for (int y = yStart; y < yEnd; y += 3) {
                    int rowOff = y * w;
                    for (int x = xStart + 2; x < xEnd - 2; x += 2) {
                        int p1 = pixels[rowOff + x - 2];
                        int p2 = pixels[rowOff + x];
                        int lum1 = (((p1 >> 16) & 0xFF) * 77 + ((p1 >> 8) & 0xFF) * 150 + (p1 & 0xFF) * 29) >> 8;
                        int lum2 = (((p2 >> 16) & 0xFF) * 77 + ((p2 >> 8) & 0xFF) * 150 + (p2 & 0xFF) * 29) >> 8;
                        if ((lum2 > 145 && lum2 - lum1 > 45) || (lum1 > 145 && lum1 - lum2 > 45)) {
                            sharpTransitions++;
                        }
                    }
                }
                if (sharpTransitions >= 6) {
                    textCells[r][c] = true;
                }
            }
        }

        // دمج الخلايا المتجاورة أفقياً لتكوين مستطيلات الجمل والقوائم
        List<DetectedBox> results = new ArrayList<>();
        boolean[][] visited = new boolean[gridRows][gridCols];

        for (int r = 1; r < gridRows - 1; r++) {
            int c = 1;
            while (c < gridCols - 1) {
                if (textCells[r][c] && !visited[r][c]) {
                    int minC = c, maxC = c;
                    int minR = r, maxR = r;
                    visited[r][c] = true;

                    while (maxC + 1 < gridCols - 1 && (textCells[r][maxC + 1] || (maxC + 2 < gridCols - 1 && textCells[r][maxC + 2]))) {
                        maxC++;
                        visited[r][maxC] = true;
                    }

                    // دمج السطر السفلي إذا كان تابعاً لنفس الحوار
                    if (r + 1 < gridRows - 1) {
                        boolean hasBelow = false;
                        for (int k = minC; k <= maxC; k++) {
                            if (textCells[r + 1][k]) {
                                hasBelow = true;
                                visited[r + 1][k] = true;
                            }
                        }
                        if (hasBelow) maxR = r + 1;
                    }

                    int x0 = Math.max(0, minC * cellW - 12);
                    int y0 = Math.max(0, minR * cellH - 8);
                    int x1 = Math.min(w, (maxC + 1) * cellW + 12);
                    int y1 = Math.min(h, (maxR + 1) * cellH + 8);

                    if ((x1 - x0) >= cellW && (y1 - y0) >= cellH / 2) {
                        results.add(new DetectedBox(x0, y0, x1, y1));
                        if (results.size() >= 7) return results;
                    }
                    c = maxC + 1;
                } else {
                    c++;
                }
            }
        }
        return results;
    }

    // قص وتجهيز مستطيل النص بتباين نظيف (حروف داكنة على خلفية بيضاء) ليقرأه الجوال بدقة وسرعة قصوى
    private String cropAndPrepareForPhoneOcr(Bitmap src, DetectedBox box) {
        try {
            int cw = box.x1 - box.x0;
            int ch = box.y1 - box.y0;
            if (cw <= 16 || ch <= 12) return null;

            Bitmap sub = Bitmap.createBitmap(src, box.x0, box.y0, cw, ch);
            int targetH = Math.min(76, Math.max(36, ch));
            int targetW = Math.max(60, Math.round(cw * ((float) targetH / ch)));

            Bitmap scaled = Bitmap.createScaledBitmap(sub, targetW, targetH, true);
            if (scaled != sub) sub.recycle();

            int pad = 8;
            int outW = targetW + pad * 2;
            int outH = targetH + pad * 2;
            Bitmap cleanOut = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);

            int[] inPx = new int[targetW * targetH];
            scaled.getPixels(inPx, 0, targetW, 0, 0, targetW, targetH);
            scaled.recycle();

            int[] outPx = new int[outW * outH];
            for (int i = 0; i < outPx.length; i++) outPx[i] = Color.WHITE;

            for (int y = 0; y < targetH; y++) {
                int srcRow = y * targetW;
                int dstRow = (y + pad) * outW + pad;
                for (int x = 0; x < targetW; x++) {
                    int p = inPx[srcRow + x];
                    int lum = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
                    int inv = lum > 140 ? Math.max(0, 255 - lum) : 255;
                    outPx[dstRow + x] = Color.rgb(inv, inv, inv);
                }
            }
            cleanOut.setPixels(outPx, 0, outW, 0, 0, outW, outH);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cleanOut.compress(Bitmap.CompressFormat.JPEG, 78, baos);
            cleanOut.recycle();
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasScreenChanged(Bitmap bmp) {
        int cols = 20, rows = 12;
        int[] sig = new int[cols * rows];
        int stepX = bmp.getWidth() / cols;
        int stepY = bmp.getHeight() / rows;
        int k = 0;
        for (int r = 0; r < rows; r++) {
            int y = r * stepY + stepY / 2;
            for (int c = 0; c < cols; c++) {
                int x = c * stepX + stepX / 2;
                int p = bmp.getPixel(x, y);
                sig[k++] = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
            }
        }
        if (lastScreenSignature == null) {
            lastScreenSignature = sig;
            return true;
        }
        int totalDiff = 0, maxDiff = 0;
        for (int i = 0; i < sig.length; i++) {
            int d = Math.abs(sig[i] - lastScreenSignature[i]);
            totalDiff += d;
            if (d > maxDiff) maxDiff = d;
        }
        if ((totalDiff / (float) sig.length) >= 2.3f || maxDiff >= 30) {
            lastScreenSignature = sig;
            return true;
        }
        return false;
    }

    // ================= 4. طبقة الرسم المكاني فوق النصوص الأصلية (SpatialOverlayView) =================
    private class SpatialOverlayView extends View {
        private List<SpatialItem> items = new ArrayList<>();
        private SubtitleStyle style = new SubtitleStyle();
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        SpatialOverlayView(Context context) {
            super(context);
            bgPaint.setStyle(Paint.Style.FILL);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setColor(Color.parseColor("#66F5A623"));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setItems(List<SpatialItem> newItems, SubtitleStyle newStyle) {
            this.items = newItems;
            this.style = newStyle;
            invalidate();
        }

        void clear() {
            this.items.clear();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (items == null || items.isEmpty()) return;

            float sw = getWidth();
            float sh = getHeight();

            bgPaint.setColor(applyAlpha(style.bgColor, Math.max(style.bgOpacity, 84)));
            textPaint.setColor(applyAlpha(style.textColor, style.textOpacity));
            textPaint.setTypeface(Typeface.create("sans-serif", style.isBold ? Typeface.BOLD : Typeface.NORMAL));

            for (int i = 0; i < items.size(); i++) {
                SpatialItem it = items.get(i);
                float left = it.x * sw;
                float top = it.y * sh;
                float origW = Math.max(dp(50), it.w * sw);
                float origH = Math.max(dp(26), it.h * sh);

                float fontSizePx = clampFloat(origH * 0.55f, dp(13), dp(30));
                textPaint.setTextSize(fontSizePx);

                float measuredTextW = textPaint.measureText(it.ar);
                float boxW = Math.max(origW, Math.min(sw * 0.88f, measuredTextW + dp(20)));
                int innerTextW = Math.max(40, (int) (boxW - dp(16)));

                StaticLayout layout = new StaticLayout(
                        it.ar,
                        textPaint,
                        innerTextW,
                        Layout.Alignment.ALIGN_NORMAL,
                        1.0f,
                        0.0f,
                        false
                );

                float boxH = Math.max(origH, layout.getHeight() + dp(10));
                float centerX = left + origW / 2f;
                float finalLeft = clampFloat(centerX - boxW / 2f, dp(6), Math.max(dp(6), sw - boxW - dp(6)));
                float finalTop = clampFloat(top, dp(6), Math.max(dp(6), sh - boxH - dp(6)));

                RectF rect = new RectF(finalLeft, finalTop, finalLeft + boxW, finalTop + boxH);
                canvas.drawRoundRect(rect, dp(4), dp(4), bgPaint);
                canvas.drawRoundRect(rect, dp(4), dp(4), borderPaint);

                canvas.save();
                float textY = finalTop + (boxH - layout.getHeight()) / 2f;
                canvas.translate(finalLeft + boxW / 2f, textY);
                layout.draw(canvas);
                canvas.restore();
            }
        }
    }

    // ================= 5. محرك MQTT المباشر (بدون أي مكتبات خارجية) =================
    private void startMqttEngineThread() {
        Thread mqttThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isServiceRunning) {
                    try {
                        Socket socket = new Socket();
                        socket.connect(new InetSocketAddress("broker.emqx.io", 1883), 7000);
                        socket.setSoTimeout(45000);
                        mqttSocket = socket;
                        mqttOut = socket.getOutputStream();
                        DataInputStream in = new DataInputStream(socket.getInputStream());

                        String clientId = "siyaq_tv_" + roomCode + "_" + new Random().nextInt(9000);
                        sendMqttRawPacket(buildConnectPacket(clientId));

                        // قراءة CONNACK
                        int h1 = in.readUnsignedByte();
                        int len1 = readRemainingLength(in);
                        byte[] ackBuf = new byte[len1];
                        in.readFully(ackBuf);

                        if ((h1 >> 4) == 2) {
                            sendMqttRawPacket(buildSubscribePacket("siyaq/tv/sec/" + roomCode, 1));
                            updateStatusTextOnUi(isPairedWithPhone, "متصل بالسيرفر وجاهز للاقتران ✓");
                        }

                        long lastPing = System.currentTimeMillis();

                        while (isServiceRunning && !socket.isClosed()) {
                            if (System.currentTimeMillis() - lastPing > 18000) {
                                sendMqttRawPacket(new byte[]{(byte) 0xC0, 0x00});
                                lastPing = System.currentTimeMillis();
                            }

                            if (in.available() > 0) {
                                int header = in.readUnsignedByte();
                                int remLen = readRemainingLength(in);
                                byte[] payload = new byte[remLen];
                                in.readFully(payload);

                                int packetType = (header & 0xF0) >> 4;
                                if (packetType == 3) {
                                    String jsonStr = extractPublishPayload(header, payload);
                                    if (jsonStr != null) {
                                        handleIncomingPhoneMessage(jsonStr);
                                    }
                                }
                            } else {
                                Thread.sleep(25);
                            }
                        }
                    } catch (Exception e) {
                        try {
                            Thread.sleep(2200);
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        });
        mqttThread.setDaemon(true);
        mqttThread.start();
    }

    private void handleIncomingPhoneMessage(final String jsonStr) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = new JSONObject(jsonStr);
                    String type = obj.optString("type", "");

                    if (obj.has("style")) {
                        parseIncomingStyle(obj.getJSONObject("style"));
                    }

                    if ("PAIRED".equals(type)) {
                        isPairedWithPhone = true;
                        activeTvMode = obj.optString("tvSubMode", "camera");
                        updateStatusTextOnUi(true, "مقترن بالجوال بنجاح ✓");
                    } else if ("UNPAIRED".equals(type)) {
                        isPairedWithPhone = false;
                        isScreenOcrActive = false;
                        clearAllOverlays();
                        updateStatusTextOnUi(false, "بانتظار اقتران الجوال...");
                    } else if ("TV_MODE_SWITCH".equals(type)) {
                        isPairedWithPhone = true;
                        String mode = obj.optString("mode", "camera");
                        activeTvMode = "screen_ocr".equals(mode) ? "screen_ocr" : "camera";
                        isScreenOcrActive = obj.optBoolean("active", false);
                        lastScreenSignature = null;
                        clearAllOverlays();
                        updateStatusTextOnUi(true, "screen_ocr".equals(activeTvMode)
                                ? "وضع الشاشة الكاملة نشط ✓"
                                : "وضع الكاميرا نشط ✓");
                    } else if ("SUB_UPDATE".equals(type)) {
                        isPairedWithPhone = true;
                        if (spatialOverlayView != null) spatialOverlayView.setVisibility(View.GONE);
                        renderSingleSubtitleBox(obj.optString("text", ""), currentStyle);
                    } else if ("SCREEN_OVERLAY_BATCH".equals(type)) {
                        isPairedWithPhone = true;
                        if (singleSubtitleBox != null) singleSubtitleBox.setVisibility(View.GONE);
                        JSONArray arr = obj.optJSONArray("items");
                        List<SpatialItem> list = new ArrayList<>();
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject it = arr.getJSONObject(i);
                                String ar = it.optString("ar", "");
                                if (!ar.isEmpty()) {
                                    list.add(new SpatialItem(
                                            it.optString("id", "i_" + i),
                                            ar,
                                            (float) it.optDouble("x", 0.1),
                                            (float) it.optDouble("y", 0.1),
                                            (float) it.optDouble("w", 0.2),
                                            (float) it.optDouble("h", 0.06)
                                    ));
                                }
                            }
                        }
                        ensureOverlayAttached();
                        if (spatialOverlayView != null) {
                            spatialOverlayView.setItems(list, currentStyle);
                            spatialOverlayView.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                        }
                    } else if ("SUB_CLEAR".equals(type)) {
                        clearAllOverlays();
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void parseIncomingStyle(JSONObject s) {
        currentStyle.fontSize = s.optInt("fontSize", currentStyle.fontSize);
        currentStyle.isBold = s.optBoolean("isBold", currentStyle.isBold);
        currentStyle.textColor = s.optString("textColor", currentStyle.textColor);
        currentStyle.textOpacity = s.optInt("textOpacity", currentStyle.textOpacity);
        currentStyle.bgColor = s.optString("bgColor", currentStyle.bgColor);
        currentStyle.bgOpacity = s.optInt("bgOpacity", currentStyle.bgOpacity);
        currentStyle.bgWidthPct = s.optInt("bgWidth", currentStyle.bgWidthPct);
        currentStyle.bgPaddingPx = s.optInt("bgHeight", currentStyle.bgPaddingPx);
        currentStyle.posX = s.optInt("posX", currentStyle.posX);
        currentStyle.posY = s.optInt("posY", currentStyle.posY);
    }

    private void publishMqttJson(final String topic, final JSONObject json) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sendMqttRawPacket(buildPublishPacket(topic, json.toString()));
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void sendMqttRawPacket(byte[] packet) throws Exception {
        synchronized (mqttWriteLock) {
            if (mqttOut != null) {
                mqttOut.write(packet);
                mqttOut.flush();
            }
        }
    }

    private int readRemainingLength(DataInputStream in) throws Exception {
        int multiplier = 1;
        int value = 0;
        int encodedByte;
        do {
            encodedByte = in.readUnsignedByte();
            value += (encodedByte & 127) * multiplier;
            multiplier *= 128;
        } while ((encodedByte & 128) != 0);
        return value;
    }

    private String extractPublishPayload(int header, byte[] body) {
        if (body.length < 2) return null;
        int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
        int idx = 2 + topicLen;
        int qos = (header & 0x06) >> 1;
        if (qos > 0) idx += 2;
        if (idx >= body.length) return null;
        return new String(body, idx, body.length - idx, StandardCharsets.UTF_8);
    }

    private byte[] buildConnectPacket(String clientId) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[]{0x00, 0x04, 'M', 'Q', 'T', 'T', 0x04, 0x02, 0x00, 0x3C});
        byte[] idBytes = clientId.getBytes(StandardCharsets.UTF_8);
        body.write((idBytes.length >> 8) & 0xFF);
        body.write(idBytes.length & 0xFF);
        body.write(idBytes);
        return wrapFixedHeader(0x10, body.toByteArray());
    }

    private byte[] buildSubscribePacket(String topic, int packetId) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((packetId >> 8) & 0xFF);
        body.write(packetId & 0xFF);
        byte[] tBytes = topic.getBytes(StandardCharsets.UTF_8);
        body.write((tBytes.length >> 8) & 0xFF);
        body.write(tBytes.length & 0xFF);
        body.write(tBytes);
        body.write(0x00);
        return wrapFixedHeader(0x82, body.toByteArray());
    }

    private byte[] buildPublishPacket(String topic, String message) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] tBytes = topic.getBytes(StandardCharsets.UTF_8);
        byte[] mBytes = message.getBytes(StandardCharsets.UTF_8);
        body.write((tBytes.length >> 8) & 0xFF);
        body.write(tBytes.length & 0xFF);
        body.write(tBytes);
        body.write(mBytes);
        return wrapFixedHeader(0x30, body.toByteArray());
    }

    private byte[] wrapFixedHeader(int headerByte, byte[] bodyBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(headerByte);
        int remLen = bodyBytes.length;
        do {
            int encoded = remLen % 128;
            remLen /= 128;
            if (remLen > 0) encoded |= 128;
            out.write(encoded);
        } while (remLen > 0);
        out.write(bodyBytes, 0, bodyBytes.length);
        return out.toByteArray();
    }

    // ================= 6. إدارة الصلاحيات والباركود والأدوات المساعدة =================
    private void requestOverlayPermission() {
        if (!canDrawOverlaysCompat()) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
            } catch (Exception e) {
                Toast.makeText(this, "يرجى تفعيل الظهور فوق التطبيقات من إعدادات التلفاز", Toast.LENGTH_LONG).show();
            }
        } else {
            ensureOverlayAttached();
            Toast.makeText(this, "صلاحية الظهور فوق الشاشة مفعّلة وجاهزة ✓", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestMediaProjectionPermission() {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر فتح نافذة التقاط الشاشة", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            ensureOverlayAttached();
            refreshButtonsState();
        } else if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                setupMediaProjection(resultCode, data);
                refreshButtonsState();
                Toast.makeText(this, "تم تفعيل قراءة الشاشة الكاملة بنجاح ✓", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupMediaProjection(int resultCode, Intent data) {
        try {
            if (virtualDisplay != null) virtualDisplay.release();
            if (imageReader != null) imageReader.close();
            if (mediaProjection != null) mediaProjection.stop();

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, data);

            int capW = 1280;
            int capH = 720;
            int density = getResources().getDisplayMetrics().densityDpi;

            imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "SiyaqTvScreenCapture",
                    capW,
                    capH,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );
        } catch (Exception ignored) {}
    }

    private void resetRoomCodeAndUnpair() {
        try {
            publishMqttJson("siyaq/tv/sec/" + roomCode, new JSONObject().put("type", "UNPAIRED"));
        } catch (Exception ignored) {}

        isPairedWithPhone = false;
        isScreenOcrActive = false;
        clearAllOverlays();

        roomCode = generateRandom8DigitCode();
        getSharedPreferences("siyaq_tv_prefs", Context.MODE_PRIVATE)
                .edit().putString("room_code", roomCode).apply();

        try {
            if (mqttSocket != null) mqttSocket.close();
        } catch (Exception ignored) {}

        recreate();
    }

    private void loadQrCodeImageAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String urlStr = "https://api.qrserver.com/v1/create-qr-code/?size=360x360&data=SIYAQ:" + roomCode;
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    InputStream is = conn.getInputStream();
                    final Bitmap bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bmp != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (qrImageView != null) qrImageView.setImageBitmap(bmp);
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private Bitmap createFallbackCodeBitmap(String code) {
        Bitmap bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(42f);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("SIYAQ TV", 150, 120, p);
        p.setColor(Color.parseColor("#D98200"));
        p.setTextSize(48f);
        c.drawText(code, 150, 185, p);
        return bmp;
    }

    private void updateStatusTextOnUi(final boolean paired, final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (tvStatusBadge != null) {
                    tvStatusBadge.setText("حالة الاتصال: " + msg);
                    tvStatusBadge.setTextColor(paired ? Color.parseColor("#34D399") : Color.parseColor("#F8FAFC"));
                }
            }
        });
    }

    private boolean canDrawOverlaysCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SIYAQ::TvKeepAliveLock");
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    private GradientDrawable createRoundedDrawable(String fillHex, String strokeHex, int radiusPx, int strokeW) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusPx);
        gd.setColor(Color.parseColor(fillHex));
        gd.setStroke(strokeW, Color.parseColor(strokeHex));
        return gd;
    }

    private View createSpace(int heightPx) {
        Space s = new Space(this);
        s.setMinimumHeight(heightPx);
        return s;
    }

    private int applyAlpha(String hex, int opacityPct) {
        try {
            int c = Color.parseColor(hex);
            int a = (int) ((clamp(opacityPct, 0, 100) / 100f) * 255);
            return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c));
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    private String generateRandom8DigitCode() {
        int n = 10000000 + new Random().nextInt(90000000);
        return String.valueOf(n);
    }

    private int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private float clampFloat(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }
}
