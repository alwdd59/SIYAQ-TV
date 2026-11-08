package com.siyaq.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
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
import android.widget.ImageView;
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

    // عناصر الطبقة الشفافة العائمة المدمجة بالكامل في نفس الملف
    private static WindowManager sWindowManager;
    private static FrameLayout sOverlayContainer;
    private static TextView sSubtitleTextView;
    private static GradientDrawable sBoxBackground;
    private static WindowManager.LayoutParams sWindowParams;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // إيقاف أي خدمة قديمة إن وجدت لأن كل شيء أصبح مدمجاً هنا
        try {
            stopService(new Intent(this, OverlaySubtitleService.class));
        } catch (Exception ignored) {}

        int num = 10000000 + new Random().nextInt(90000000);
        roomCode = String.valueOf(num);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#090913"));
        root.setGravity(Gravity.CENTER);
        int padH = (int) (dm.widthPixels * 0.06f);
        int padV = (int) (screenH * 0.04f);
        root.setPadding(padH, padV, padH, padV);

        // 1. الشريط العلوي بشعار SIYAQ الجديد وهوية الهاتف
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 18);

        ImageView logoImg = new ImageView(this);
        logoImg.setImageBitmap(createSiyaqLogoBitmap(120));
        int logoSize = Math.max(44, (int) (screenH * 0.065f));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(logoSize, logoSize);
        logoLp.setMargins(0, 0, 16, 0);
        header.addView(logoImg, logoLp);

        TextView brandTitle = new TextView(this);
        brandTitle.setText("SIYAQ");
        brandTitle.setTextColor(Color.parseColor("#00F2FE"));
        brandTitle.setTextSize(26);
        brandTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(brandTitle);

        TextView tvSubTag = new TextView(this);
        tvSubTag.setText("  TV");
        tvSubTag.setTextColor(Color.parseColor("#94A3B8"));
        tvSubTag.setTextSize(16);
        tvSubTag.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvSubTag, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusBadge = new TextView(this);
        statusBadge.setText("بانتظار اقتران الجوال...");
        statusBadge.setTextColor(Color.parseColor("#00F2FE"));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#131B2E"));
        badgeBg.setCornerRadius(40f);
        badgeBg.setStroke(2, Color.parseColor("#1E293B"));
        statusBadge.setBackground(badgeBg);
        statusBadge.setPadding(24, 10, 24, 10);
        statusBadge.setTextSize(13);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(statusBadge);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. بطاقة الاقتران الوسطى البسيطة
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#111726"));
        cardBg.setCornerRadius(32f);
        cardBg.setStroke(2, Color.parseColor("#1E293B"));
        card.setBackground(cardBg);
        card.setPadding(36, 24, 36, 24);

        // إطار الباركود الأبيض المتناسق بحيث يظهر الكود كاملاً 100%
        int qrBoxSize = Math.min(220, (int) (screenH * 0.36f));
        LinearLayout qrFrame = new LinearLayout(this);
        qrFrame.setGravity(Gravity.CENTER);
        GradientDrawable qrWhiteBg = new GradientDrawable();
        qrWhiteBg.setColor(Color.WHITE);
        qrWhiteBg.setCornerRadius(22f);
        qrFrame.setBackground(qrWhiteBg);
        qrFrame.setPadding(12, 12, 12, 12);

        qrAndMqttWebView = new WebView(this);
        qrAndMqttWebView.setVerticalScrollBarEnabled(false);
        qrAndMqttWebView.setHorizontalScrollBarEnabled(false);
        WebSettings ws = qrAndMqttWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);

        qrAndMqttWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onSyncEvent(final String jsonStr) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleIncomingPhoneMessage(jsonStr);
                    }
                });
            }
        }, "SiyaqTvWaitBridge");

        int innerQrSize = qrBoxSize - 24;
        qrFrame.addView(qrAndMqttWebView, new LinearLayout.LayoutParams(innerQrSize, innerQrSize));
        card.addView(qrFrame, new LinearLayout.LayoutParams(qrBoxSize, qrBoxSize));

        codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#00F2FE"));
        codeView.setTextSize(25);
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
        actionBtn.setPadding(36, 12, 36, 12);
        card.addView(actionBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int cardWidth = Math.min(740, (int) (dm.widthPixels * 0.78f));
        root.addView(card, new LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        updateActionButtonState();
        actionBtn.requestFocus();
        loadQrAndWaitBridge();
    }

    private Bitmap createSiyaqLogoBitmap(int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float s = size / 100f;

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.parseColor("#0B0F19"));
        c.drawRoundRect(new RectF(0, 0, size, size), 22 * s, 22 * s, bgPaint);

        Paint whitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        whitePaint.setColor(Color.WHITE);
        whitePaint.setStyle(Paint.Style.FILL);

        Path topPath = new Path();
        topPath.moveTo(27 * s, 10 * s);
        topPath.lineTo(59 * s, 10 * s);
        topPath.lineTo(59 * s, 20 * s);
        topPath.lineTo(27 * s, 20 * s);
        topPath.lineTo(27 * s, 40 * s);
        topPath.lineTo(59 * s, 40 * s);
        topPath.lineTo(59 * s, 56 * s);
        topPath.lineTo(49 * s, 56 * s);
        topPath.lineTo(49 * s, 49 * s);
        topPath.lineTo(26 * s, 49 * s);
        topPath.lineTo(17 * s, 40 * s);
        topPath.lineTo(17 * s, 20 * s);
        topPath.close();
        c.drawPath(topPath, whitePaint);

        Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setShader(new LinearGradient(
                17 * s, 64 * s, 89 * s, 64 * s,
                Color.parseColor("#1E88E5"),
                Color.parseColor("#00F2FE"),
                Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(17 * s, 61 * s, 89 * s, 68.5f * s), 2 * s, 2 * s, barPaint);

        Path botPath = new Path();
        botPath.moveTo(17 * s, 75 * s);
        botPath.lineTo(27 * s, 75 * s);
        botPath.lineTo(27 * s, 85 * s);
        botPath.lineTo(49 * s, 85 * s);
        botPath.lineTo(49 * s, 75 * s);
        botPath.lineTo(89 * s, 75 * s);
        botPath.lineTo(89 * s, 84 * s);
        botPath.lineTo(59 * s, 84 * s);
        botPath.lineTo(59 * s, 87 * s);
        botPath.lineTo(51 * s, 94 * s);
        botPath.lineTo(25 * s, 94 * s);
        botPath.lineTo(17 * s, 86 * s);
        botPath.close();
        c.drawPath(botPath, whitePaint);

        return bmp;
    }

    private void updateActionButtonState() {
        boolean hasOverlayPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        if (!hasOverlayPerm) {
            actionBtn.setText("تفعيل صلاحية الظهور فوق الألعاب (لمرة واحدة)");
            actionBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, 101);
                }
            });
        } else if (isPhonePaired) {
            actionBtn.setText("إيقاف الطبقة الشفافة وإلغاء الاقتران");
            actionBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideOverlayAndUnpair();
                }
            });
        } else {
            actionBtn.setText("تجديد رمز الاقتران");
            actionBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int num = 10000000 + new Random().nextInt(90000000);
                    roomCode = String.valueOf(num);
                    codeView.setText(roomCode);
                    loadQrAndWaitBridge();
                    Toast.makeText(MainActivity.this, "تم تجديد الرمز ✓", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void loadQrAndWaitBridge() {
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=260x260&margin=10&data=SIYAQ-TV-" + roomCode;
        String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'>" +
                "<style>" +
                "html,body{margin:0;padding:0;width:100%;height:100%;background:#FFFFFF;display:flex;align-items:center;justify-content:center;overflow:hidden;}" +
                "img{width:88%;height:88%;object-fit:contain;display:block;}" +
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

            // عند حذف الصفحة من الجوال أو إلغاء الاقتران تختفي الطبقة الشفافة فوراً
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
                if (!text.isEmpty() && sSubtitleTextView != null) {
                    sSubtitleTextView.setText(text);
                }
                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    applyFullSubtitleStyle(style);
                }
            }
        } catch (Exception ignored) {}
    }

    private void showTransparentOverlayWindow() {
        if (sOverlayContainer != null) return;

        sWindowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();

        sOverlayContainer = new FrameLayout(getApplicationContext());
        sBoxBackground = new GradientDrawable();
        sBoxBackground.setColor(Color.argb(225, 18, 18, 26));
        sBoxBackground.setCornerRadius(24f);
        sBoxBackground.setStroke(2, Color.argb(90, 0, 242, 254));
        sOverlayContainer.setBackground(sBoxBackground);

        int initPadV = (int) (14 * dm.density);
        int initPadH = (int) (24 * dm.density);
        sOverlayContainer.setPadding(initPadH, initPadV, initPadH, initPadV);

        sSubtitleTextView = new TextView(getApplicationContext());
        sSubtitleTextView.setText("SIYAQ TV — متصل بالجوال وجاهز للترجمة الفورية");
        sSubtitleTextView.setTextColor(Color.WHITE);
        sSubtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        sSubtitleTextView.setTypeface(Typeface.DEFAULT_BOLD);
        sSubtitleTextView.setGravity(Gravity.CENTER);

        sOverlayContainer.addView(sSubtitleTextView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int initialWidth = (int) (dm.widthPixels * 0.85f);
        sWindowParams = new WindowManager.LayoutParams(
                initialWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        sWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        sWindowParams.x = (dm.widthPixels - initialWidth) / 2;
        sWindowParams.y = (int) (dm.heightPixels * 0.78f);

        sWindowManager.addView(sOverlayContainer, sWindowParams);
    }

    private void applyFullSubtitleStyle(JSONObject style) {
        if (sWindowParams == null || sOverlayContainer == null || sSubtitleTextView == null) return;
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();

            // 1. نوع الخط وعرضه
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
            sSubtitleTextView.setTypeface(Typeface.create(androidFamily, tfStyle));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                sSubtitleTextView.setLetterSpacing(letterSpacing);
            }

            // 2. حجم الخط
            int fontSize = style.optInt("fontSize", 22);
            sSubtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize * 1.15f);

            // 3. لون النص وشفافيته
            String textColorStr = style.optString("textColor", "#FFFFFF");
            int textOpacity = style.optInt("textOpacity", 100);
            int parsedTextCol = Color.parseColor(textColorStr);
            int textAlpha = Math.max(30, Math.min(255, (int) (textOpacity * 2.55f)));
            sSubtitleTextView.setTextColor(Color.argb(
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
            sBoxBackground.setColor(Color.argb(
                    bgAlpha,
                    Color.red(parsedBgCol),
                    Color.green(parsedBgCol),
                    Color.blue(parsedBgCol)
            ));
            sBoxBackground.setStroke(bgAlpha > 15 ? 2 : 0, Color.argb(Math.min(110, bgAlpha), 0, 242, 254));

            // 5. عرض وارتفاع الشريط
            int bgWidthPct = Math.max(35, Math.min(100, style.optInt("bgWidth", 85)));
            int bgHeightPx = Math.max(4, Math.min(48, style.optInt("bgHeight", 14)));
            int newWidthPx = (int) (dm.widthPixels * (bgWidthPct / 100f));
            sWindowParams.width = newWidthPx;

            int padV = (int) (bgHeightPx * dm.density);
            int padH = (int) (24 * dm.density);
            sOverlayContainer.setPadding(padH, padV, padH, padV);

            // 6. تحريك الترجمة أفقياً وعمودياً في الشاشة
            int posX = Math.max(10, Math.min(90, style.optInt("posX", 50)));
            int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));

            int centerX = (int) (dm.widthPixels * (posX / 100f));
            int targetX = Math.max(0, Math.min(dm.widthPixels - newWidthPx, centerX - (newWidthPx / 2)));

            int estBoxHeight = sOverlayContainer.getHeight() > 0 ? sOverlayContainer.getHeight() : (int) (72 * dm.density);
            int centerY = (int) (dm.heightPixels * (posY / 100f));
            int targetY = Math.max(0, Math.min(dm.heightPixels - estBoxHeight, centerY - (estBoxHeight / 2)));

            sWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
            sWindowParams.x = targetX;
            sWindowParams.y = targetY;

            sWindowManager.updateViewLayout(sOverlayContainer, sWindowParams);
        } catch (Exception ignored) {}
    }

    private void hideOverlayAndUnpair() {
        isPhonePaired = false;
        if (sOverlayContainer != null && sWindowManager != null) {
            try {
                sWindowManager.removeView(sOverlayContainer);
            } catch (Exception ignored) {}
            sOverlayContainer = null;
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
