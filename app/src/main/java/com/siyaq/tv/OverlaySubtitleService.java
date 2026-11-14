package com.siyaq.tv;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlaySubtitleService extends Service {

    public interface ServiceStateListener {
        void onRequestScreenCaptureForMode();
        void onServiceStatusChanged(boolean paired, String mode, String statusMsg);
    }

    public static final String ACTION_INIT = "com.siyaq.tv.ACTION_INIT";
    public static final String ACTION_GRANT_PROJECTION_AND_RUN = "com.siyaq.tv.ACTION_GRANT_PROJECTION_AND_RUN";
    public static final String ACTION_START_SCREEN_OCR = "com.siyaq.tv.ACTION_START_SCREEN_OCR";
    public static final String ACTION_TEST_HDMI = "com.siyaq.tv.ACTION_TEST_HDMI";
    public static final String ACTION_UNPAIR = "com.siyaq.tv.ACTION_UNPAIR";

    public static final String EXTRA_ROOM_CODE = "room_code";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_RUN_HDMI_TEST = "run_hdmi_test";

    private static final String MQTT_HOST = "broker.emqx.io";
    private static final int MQTT_PORT = 1883;
    private static final String CHANNEL_ID = "siyaq_tv_overlay_channel";
    private static final int NOTIF_ID = 7071;

    private static volatile ServiceStateListener stateListener;
    private static volatile MediaProjection sharedMediaProjection;

    public static void setStateListener(ServiceStateListener listener) {
        stateListener = listener;
    }

    public static boolean hasActiveProjection() {
        return sharedMediaProjection != null;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private TextRecognizer textRecognizer;

    // طبقة العرض العائمة (تحتوي على شريط وضع الكاميرا + طبقة مربعات وضع الشاشة الكاملة)
    private FrameLayout rootOverlayContainer;
    private TextView singleSubtitleView;
    private FrameLayout screenBoxesContainer;
    private TextView hdmiTestBannerView;
    private boolean isOverlayAttached = false;

    // التقاط الشاشة
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private volatile boolean isScreenCapturing = false;
    private volatile boolean isOcrBusy = false;
    private volatile boolean isScreenModePaused = false;
    private volatile boolean pendingSingleHdmiTest = false;
    private long lastOcrTimeMs = 0L;
    private String lastSentBlocksSignature = "";
    private long lastBlocksSentTimeMs = 0L;

    // حالة الاقتران والمزامنة
    private String roomCode = "";
    private volatile boolean isPaired = false;
    private volatile String activeMode = "CAMERA_MODE"; // CAMERA_MODE | SCREEN_MODE
    private JSONObject currentStyleJson = new JSONObject();

    private TvMqttTcpClient mqttClient;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        SharedPreferences prefs = getSharedPreferences("siyaq_tv_prefs", Context.MODE_PRIVATE);
        roomCode = prefs.getString("room_code", "");

        promoteToForegroundService();
        ensureOverlayWindowAttached();
    }

    private void promoteToForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SIYAQ TV Live Translation",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SIYAQ TV")
                .setContentText("خدمة الترجمة الفورية جاهزة")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } catch (Exception e) {
                startForeground(NOTIF_ID, notification);
            }
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForegroundService();

        if (intent != null) {
            String passedCode = intent.getStringExtra(EXTRA_ROOM_CODE);
            if (passedCode != null && !passedCode.isEmpty()) {
                roomCode = passedCode;
            }
        }

        ensureMqttConnected();
        ensureOverlayWindowAttached();

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();

            if (ACTION_GRANT_PROJECTION_AND_RUN.equals(action)) {
                int resCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                Intent resData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                boolean runHdmiTest = intent.getBooleanExtra(EXTRA_RUN_HDMI_TEST, false);

                if (resCode == Activity.RESULT_OK && resData != null) {
                    if (sharedMediaProjection == null) {
                        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                        if (mpm != null) {
                            sharedMediaProjection = mpm.getMediaProjection(resCode, resData);
                            if (sharedMediaProjection != null) {
                                sharedMediaProjection.registerCallback(new MediaProjection.Callback() {
                                    @Override
                                    public void onStop() {
                                        mainHandler.post(() -> {
                                            sharedMediaProjection = null;
                                            stopScreenCaptureLoop();
                                        });
                                    }
                                }, mainHandler);
                            }
                        }
                    }

                    if (runHdmiTest) {
                        pendingSingleHdmiTest = true;
                    }
                    startScreenCaptureLoop();
                }

            } else if (ACTION_START_SCREEN_OCR.equals(action)) {
                isScreenModePaused = false;
                startScreenCaptureLoop();

            } else if (ACTION_TEST_HDMI.equals(action)) {
                pendingSingleHdmiTest = true;
                startScreenCaptureLoop();

            } else if (ACTION_UNPAIR.equals(action)) {
                if (mqttClient != null && !roomCode.isEmpty()) {
                    mqttClient.publish("siyaq/tv/sec/" + roomCode, "{\"type\":\"UNPAIRED\"}");
                }
                resetPairingState("تم إلغاء الاقتران");
            }
        }

        return START_STICKY;
    }

    private void ensureMqttConnected() {
        if (roomCode == null || roomCode.isEmpty()) return;
        if (mqttClient != null && mqttClient.isForRoom(roomCode)) return;

        if (mqttClient != null) {
            mqttClient.disconnect();
        }

        mqttClient = new TvMqttTcpClient(
                MQTT_HOST,
                MQTT_PORT,
                "siyaq_tv_" + roomCode,
                "siyaq/tv/sec/" + roomCode,
                () -> mainHandler.post(() -> {
                    if (!isPaired && stateListener != null) {
                        stateListener.onServiceStatusChanged(false, activeMode, "في انتظار الاقتران من الجوال...");
                    }
                }),
                (topic, payload) -> mainHandler.post(() -> handleIncomingPhoneJson(payload))
        );
        mqttClient.connect();
    }

    private void handleIncomingPhoneJson(String rawPayload) {
        try {
            JSONObject obj = new JSONObject(rawPayload);
            String type = obj.optString("type");

            if ("PAIRED".equals(type) || "MODE_SWITCH".equals(type)) {
                String mode = obj.optString("mode", "CAMERA_MODE");
                JSONObject style = obj.optJSONObject("style");
                if (style != null) currentStyleJson = style;

                isPaired = true;
                activeMode = mode;
                isScreenModePaused = false;
                ensureOverlayWindowAttached();

                if ("SCREEN_MODE".equals(mode)) {
                    hideSingleSubtitle();
                    clearMultiBlockOverlays();
                    if (stateListener != null) {
                        stateListener.onServiceStatusChanged(true, "SCREEN_MODE", null);
                    }
                    if (sharedMediaProjection != null) {
                        startScreenCaptureLoop();
                    } else if (stateListener != null) {
                        stateListener.onRequestScreenCaptureForMode();
                    }
                } else {
                    stopScreenCaptureLoop();
                    clearMultiBlockOverlays();
                    applySingleSubtitleStyle(currentStyleJson);
                    if (stateListener != null) {
                        stateListener.onServiceStatusChanged(true, "CAMERA_MODE", null);
                    }
                }

            } else if ("SUB_UPDATE".equals(type)) {
                if (!isPaired || !"CAMERA_MODE".equals(activeMode)) {
                    isPaired = true;
                    activeMode = "CAMERA_MODE";
                    stopScreenCaptureLoop();
                    clearMultiBlockOverlays();
                    if (stateListener != null) {
                        stateListener.onServiceStatusChanged(true, "CAMERA_MODE", null);
                    }
                }
                String text = obj.optString("text");
                JSONObject style = obj.optJSONObject("style");
                if (style != null) currentStyleJson = style;

                if (!text.isEmpty()) {
                    ensureOverlayWindowAttached();
                    applySingleSubtitleStyle(currentStyleJson);
                    showSingleSubtitle(text);
                }

            } else if ("SUB_CLEAR".equals(type)) {
                hideSingleSubtitle();

            } else if ("SCREEN_OVERLAY_UPDATE".equals(type)) {
                if (!isPaired || !"SCREEN_MODE".equals(activeMode)) {
                    isPaired = true;
                    activeMode = "SCREEN_MODE";
                    hideSingleSubtitle();
                    if (stateListener != null) {
                        stateListener.onServiceStatusChanged(true, "SCREEN_MODE", null);
                    }
                }
                JSONObject style = obj.optJSONObject("style");
                if (style != null) currentStyleJson = style;

                JSONArray overlays = obj.optJSONArray("overlays");
                ensureOverlayWindowAttached();
                renderScreenOverlays(overlays, currentStyleJson);

            } else if ("SCREEN_OVERLAY_CLEAR".equals(type)) {
                clearMultiBlockOverlays();

            } else if ("SCREEN_CONTROL".equals(type)) {
                String action = obj.optString("action");
                if ("PAUSE".equals(action)) {
                    isScreenModePaused = true;
                    clearMultiBlockOverlays();
                } else if ("RESUME".equals(action)) {
                    isScreenModePaused = false;
                    lastSentBlocksSignature = "";
                    if (sharedMediaProjection != null) {
                        startScreenCaptureLoop();
                    } else if (stateListener != null) {
                        stateListener.onRequestScreenCaptureForMode();
                    }
                }

            } else if ("STYLE_UPDATE".equals(type)) {
                JSONObject style = obj.optJSONObject("style");
                if (style != null) {
                    currentStyleJson = style;
                    if ("CAMERA_MODE".equals(activeMode)) {
                        applySingleSubtitleStyle(currentStyleJson);
                    }
                }

            } else if ("UNPAIRED".equals(type)) {
                resetPairingState("تم إلغاء الاقتران من الجوال");
            }
        } catch (Exception ignored) {
        }
    }

    private void resetPairingState(String reason) {
        isPaired = false;
        activeMode = "CAMERA_MODE";
        isScreenModePaused = false;
        stopScreenCaptureLoop();
        hideSingleSubtitle();
        clearMultiBlockOverlays();
        if (stateListener != null) {
            stateListener.onServiceStatusChanged(false, activeMode, reason);
        }
    }

    // ================= التقاط شاشة التلفاز كاملة وقراءة النصوص مع إحداثياتها =================
    private void startScreenCaptureLoop() {
        if (sharedMediaProjection == null) return;
        if (isScreenCapturing) return;

        try {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);

            // دقة متوازنة (960x540 أو نصف دقة الشاشة) لقراءة حادة للنصوص الصغيرة بأسرع وقت
            final int capWidth = Math.max(854, Math.min(1280, metrics.widthPixels / 2));
            final int capHeight = Math.max(480, Math.min(720, metrics.heightPixels / 2));
            final int density = metrics.densityDpi;

            imageReader = ImageReader.newInstance(capWidth, capHeight, PixelFormat.RGBA_8888, 2);
            virtualDisplay = sharedMediaProjection.createVirtualDisplay(
                    "SiyaqTvScreenReader",
                    capWidth, capHeight, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, mainHandler
            );

            isScreenCapturing = true;
            lastSentBlocksSignature = "";

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                } catch (Exception ignored) {}
                if (image == null) return;

                try {
                    long now = System.currentTimeMillis();
                    boolean runTestNow = pendingSingleHdmiTest;

                    if (!runTestNow) {
                        if (!isPaired || !"SCREEN_MODE".equals(activeMode) || isScreenModePaused) {
                            return;
                        }
                        if (isOcrBusy || (now - lastOcrTimeMs) < 240L) {
                            return;
                        }
                    } else {
                        if (isOcrBusy) return;
                        pendingSingleHdmiTest = false;
                    }

                    lastOcrTimeMs = now;
                    isOcrBusy = true;

                    Image.Plane plane = image.getPlanes()[0];
                    ByteBuffer buffer = plane.getBuffer();
                    int pixelStride = plane.getPixelStride();
                    int rowStride = plane.getRowStride();
                    int rowPadding = rowStride - pixelStride * capWidth;

                    Bitmap paddedBitmap = Bitmap.createBitmap(
                            capWidth + rowPadding / pixelStride,
                            capHeight,
                            Bitmap.Config.ARGB_8888
                    );
                    paddedBitmap.copyPixelsFromBuffer(buffer);

                    final Bitmap frameBitmap;
                    if (rowPadding > 0) {
                        frameBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, capWidth, capHeight);
                        paddedBitmap.recycle();
                    } else {
                        frameBitmap = paddedBitmap;
                    }

                    if (runTestNow) {
                        executeHdmiDiagnosticOnBitmap(frameBitmap);
                    } else {
                        processFullScreenOcrFrame(frameBitmap, capWidth, capHeight);
                    }
                } catch (Exception e) {
                    isOcrBusy = false;
                } finally {
                    image.close();
                }
            }, mainHandler);

        } catch (Exception e) {
            isScreenCapturing = false;
        }
    }

    private void stopScreenCaptureLoop() {
        isScreenCapturing = false;
        isOcrBusy = false;
        lastSentBlocksSignature = "";
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        virtualDisplay = null;
        imageReader = null;
    }

    private void executeHdmiDiagnosticOnBitmap(final Bitmap bmp) {
        // فحص هل الإطار أسود بالكامل (منفذ HDMI مغلق من شريحة الشاشة) أم يحتوي صورة فعلية
        long lumaSum = 0;
        int samples = 0;
        int stepX = Math.max(1, bmp.getWidth() / 32);
        int stepY = Math.max(1, bmp.getHeight() / 18);
        for (int y = 0; y < bmp.getHeight(); y += stepY) {
            for (int x = 0; x < bmp.getWidth(); x += stepX) {
                int px = bmp.getPixel(x, y);
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                lumaSum += (r + g + b) / 3;
                samples++;
            }
        }
        final long avgLuma = samples > 0 ? (lumaSum / samples) : 0;

        InputImage inputImage = InputImage.fromBitmap(bmp, 0);
        textRecognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    String detected = visionText.getText()
                            .replace("\n", " ")
                            .replaceAll("[\\u0600-\\u06FF]+", "")
                            .replaceAll("\\s+", " ")
                            .trim();

                    String msg;
                    boolean success;
                    if (avgLuma <= 2 && detected.isEmpty()) {
                        success = false;
                        msg = "⚠ اختبار HDMI: الشاشة تظهر سوداء للنظام (أوقف HDCP من السوني أو استخدم وضع الكاميرا)";
                    } else if (!detected.isEmpty()) {
                        success = true;
                        String shortSample = detected.length() > 42 ? detected.substring(0, 42) + "..." : detected;
                        msg = "✓ اختبار HDMI ناجح! تم التقاط الشاشة والنص: \"" + shortSample + "\"";
                    } else {
                        success = true;
                        msg = "✓ اختبار HDMI ناجح! الشاشة مقروءة وجاهزة لترجمة النصوص فوق الألعاب";
                    }
                    showHdmiTestBanner(msg, success);
                })
                .addOnFailureListener(e -> showHdmiTestBanner("⚠ تعذر قراءة الإطار: " + e.getMessage(), false))
                .addOnCompleteListener(task -> {
                    bmp.recycle();
                    isOcrBusy = false;
                    if (!isPaired || !"SCREEN_MODE".equals(activeMode)) {
                        stopScreenCaptureLoop();
                    }
                });
    }

    private void processFullScreenOcrFrame(final Bitmap bmp, final int frameW, final int frameH) {
        InputImage inputImage = InputImage.fromBitmap(bmp, 0);
        textRecognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    if (!isPaired || !"SCREEN_MODE".equals(activeMode) || isScreenModePaused) return;

                    JSONArray blocksArray = new JSONArray();
                    StringBuilder signatureBuilder = new StringBuilder();
                    int idx = 0;

                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            Rect rect = line.getBoundingBox();
                            if (rect == null) continue;

                            String rawLine = line.getText()
                                    .replace("\n", " ")
                                    .replaceAll("\\s+", " ")
                                    .trim();

                            // تجاهل النصوص العربية (الترجمة المعروضة) والرموز الفارغة
                            if (rawLine.length() < 2) continue;
                            if (rawLine.matches(".*[\\u0600-\\u06FF]+.*")) continue;
                            String lettersOnly = rawLine.replaceAll("[^a-zA-Z]", "");
                            if (lettersOnly.length() < 2) continue;

                            float nx = Math.max(0f, Math.min(0.98f, (float) rect.left / frameW));
                            float ny = Math.max(0f, Math.min(0.98f, (float) rect.top / frameH));
                            float nw = Math.max(0.03f, Math.min(1f - nx, (float) rect.width() / frameW));
                            float nh = Math.max(0.025f, Math.min(1f - ny, (float) rect.height() / frameH));

                            try {
                                JSONObject item = new JSONObject();
                                item.put("id", String.valueOf(idx));
                                item.put("text", rawLine);
                                item.put("x", Math.round(nx * 1000f) / 1000f);
                                item.put("y", Math.round(ny * 1000f) / 1000f);
                                item.put("w", Math.round(nw * 1000f) / 1000f);
                                item.put("h", Math.round(nh * 1000f) / 1000f);
                                blocksArray.put(item);

                                signatureBuilder.append(rawLine)
                                        .append('@')
                                        .append(Math.round(nx * 20))
                                        .append(',')
                                        .append(Math.round(ny * 20))
                                        .append(';');
                                idx++;
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    String sig = signatureBuilder.toString();
                    long now = System.currentTimeMillis();

                    if (sig.isEmpty()) {
                        if (!lastSentBlocksSignature.isEmpty()) {
                            lastSentBlocksSignature = "";
                            lastBlocksSentTimeMs = now;
                            clearMultiBlockOverlays();
                            publishScreenBlocksPacket(new JSONArray());
                        }
                        return;
                    }

                    if (!sig.equals(lastSentBlocksSignature) || (now - lastBlocksSentTimeMs) > 2500L) {
                        lastSentBlocksSignature = sig;
                        lastBlocksSentTimeMs = now;
                        publishScreenBlocksPacket(blocksArray);
                    }
                })
                .addOnCompleteListener(task -> {
                    bmp.recycle();
                    isOcrBusy = false;
                });
    }

    private void publishScreenBlocksPacket(JSONArray blocks) {
        if (mqttClient == null || roomCode.isEmpty()) return;
        try {
            JSONObject packet = new JSONObject();
            packet.put("type", "TV_SCREEN_BLOCKS");
            packet.put("frameId", System.currentTimeMillis());
            packet.put("blocks", blocks);
            mqttClient.publish("siyaq/tv/ocr/" + roomCode, packet.toString());
        } catch (Exception ignored) {
        }
    }

    // ================= إدارة طبقة العرض العائمة فوق الألعاب =================
    private void ensureOverlayWindowAttached() {
        if (isOverlayAttached) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;

        rootOverlayContainer = new FrameLayout(this);

        // 1. حاوية الترجمات المتعددة فوق أماكن النصوص (لوضع الشاشة)
        screenBoxesContainer = new FrameLayout(this);
        rootOverlayContainer.addView(screenBoxesContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // 2. شريط الترجمة الموحد (لوضع الكاميرا)
        singleSubtitleView = new TextView(this);
        singleSubtitleView.setVisibility(View.GONE);
        singleSubtitleView.setGravity(Gravity.CENTER);
        singleSubtitleView.setTextColor(Color.WHITE);
        singleSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        singleSubtitleView.setTypeface(null, Typeface.BOLD);
        singleSubtitleView.setPadding(24, 12, 24, 12);

        FrameLayout.LayoutParams singleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        singleLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        singleLp.topMargin = 520;
        rootOverlayContainer.addView(singleSubtitleView, singleLp);

        // 3. شريط نتيجة اختبار HDMI
        hdmiTestBannerView = new TextView(this);
        hdmiTestBannerView.setVisibility(View.GONE);
        hdmiTestBannerView.setGravity(Gravity.CENTER);
        hdmiTestBannerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        hdmiTestBannerView.setTypeface(null, Typeface.BOLD);
        hdmiTestBannerView.setPadding(32, 18, 32, 18);

        FrameLayout.LayoutParams testLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        testLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        testLp.topMargin = 48;
        rootOverlayContainer.addView(hdmiTestBannerView, testLp);

        int windowType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        try {
            windowManager.addView(rootOverlayContainer, params);
            isOverlayAttached = true;
        } catch (Exception ignored) {
        }
    }

    private void showHdmiTestBanner(String message, boolean success) {
        mainHandler.post(() -> {
            ensureOverlayWindowAttached();
            if (hdmiTestBannerView == null) return;

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#EE0C0C10"));
            bg.setCornerRadius(24f);
            bg.setStroke(3, Color.parseColor(success ? "#34D399" : "#FB7185"));

            hdmiTestBannerView.setBackground(bg);
            hdmiTestBannerView.setTextColor(Color.parseColor(success ? "#34D399" : "#FB7185"));
            hdmiTestBannerView.setText(message);
            hdmiTestBannerView.setVisibility(View.VISIBLE);

            mainHandler.removeCallbacks(hideHdmiBannerRunnable);
            mainHandler.postDelayed(hideHdmiBannerRunnable, 5500L);
        });
    }

    private final Runnable hideHdmiBannerRunnable = () -> {
        if (hdmiTestBannerView != null) {
            hdmiTestBannerView.setVisibility(View.GONE);
        }
    };

    // رسم الترجمة العربية فوق كل نص أصلي في إحداثياته الدقيقة (x, y, w, h)
    private void renderScreenOverlays(JSONArray overlays, JSONObject style) {
        if (screenBoxesContainer == null) return;
        screenBoxesContainer.removeAllViews();
        if (overlays == null || overlays.length() == 0) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        boolean isBold = style == null || style.optBoolean("isBold", true);
        String textColorHex = style != null ? style.optString("textColor", "#FFFFFF") : "#FFFFFF";
        int textOpacity = style != null ? Math.max(20, Math.min(100, style.optInt("textOpacity", 100))) : 100;
        String bgColorHex = style != null ? style.optString("bgColor", "#000000") : "#000000";
        // جعل الخلفية غامقة افتراضياً (92% أو حسب اختيار المستخدم) لتغطية النص الأصلي بوضوح
        int rawBgOp = style != null ? style.optInt("bgOpacity", 92) : 92;
        int bgOpacity = Math.max(75, Math.min(100, rawBgOp));

        int resolvedTextColor = parseColorWithOpacity(textColorHex, textOpacity, Color.WHITE);
        int resolvedBgColor = parseColorWithOpacity(bgColorHex, bgOpacity, Color.argb(235, 0, 0, 0));

        List<View> viewsToAdd = new ArrayList<>();
        for (int i = 0; i < overlays.length(); i++) {
            JSONObject item = overlays.optJSONObject(i);
            if (item == null) continue;
            String arText = item.optString("ar", "").trim();
            if (arText.isEmpty()) continue;

            float nx = (float) item.optDouble("x", 0.1);
            float ny = (float) item.optDouble("y", 0.8);
            float nw = (float) item.optDouble("w", 0.2);
            float nh = (float) item.optDouble("h", 0.05);

            int leftPx = Math.max(8, Math.round(nx * screenW) - 6);
            int topPx = Math.max(8, Math.round(ny * screenH) - 4);
            int boxW = Math.max(60, Math.round(nw * screenW) + 12);
            int boxH = Math.max(28, Math.round(nh * screenH) + 8);

            // حساب حجم الخط المناسب لارتفاع مربع النص الأصلي
            float spSize = Math.max(13f, Math.min(34f, (boxH / dm.scaledDensity) * 0.68f));

            TextView tv = new TextView(this);
            tv.setText(arText);
            tv.setTextColor(resolvedTextColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, spSize);
            tv.setTypeface(null, isBold ? Typeface.BOLD : Typeface.NORMAL);
            tv.setGravity(Gravity.CENTER);
            tv.setIncludeFontPadding(false);
            tv.setPadding(12, 4, 12, 4);
            tv.setMinWidth(boxW);
            tv.setMinHeight(boxH);
            tv.setMaxWidth(Math.max(boxW, (int) (screenW * 0.88f)));

            GradientDrawable boxBg = new GradientDrawable();
            boxBg.setColor(resolvedBgColor);
            boxBg.setCornerRadius(4f);
            tv.setBackground(boxBg);

            // توسيط الصندوق العربي فوق مركز النص الأصلي
            int centerX = leftPx + (boxW / 2);
            int adjustedLeft = Math.max(8, Math.min(screenW - boxW - 8, centerX - (boxW / 2)));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            );
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = adjustedLeft;
            lp.topMargin = Math.min(screenH - boxH - 8, topPx);

            tv.setLayoutParams(lp);
            viewsToAdd.add(tv);
        }

        for (View v : viewsToAdd) {
            screenBoxesContainer.addView(v);
        }
    }

    private void clearMultiBlockOverlays() {
        if (screenBoxesContainer != null) {
            screenBoxesContainer.removeAllViews();
        }
    }

    private void applySingleSubtitleStyle(JSONObject style) {
        if (singleSubtitleView == null || style == null) return;

        float fontSize = (float) style.optInt("fontSize", 24);
        boolean isBold = style.optBoolean("isBold", true);
        String textColorHex = style.optString("textColor", "#FFFFFF");
        int textOpacity = Math.max(20, Math.min(100, style.optInt("textOpacity", 100)));
        String bgColorHex = style.optString("bgColor", "#000000");
        int bgOpacity = Math.max(0, Math.min(100, style.optInt("bgOpacity", 85)));
        int bgWidth = Math.max(25, Math.min(100, style.optInt("bgWidth", 85)));
        int bgHeight = Math.max(2, Math.min(32, style.optInt("bgHeight", 10)));
        int posX = Math.max(15, Math.min(85, style.optInt("posX", 50)));
        int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        singleSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        singleSubtitleView.setTypeface(null, isBold ? Typeface.BOLD : Typeface.NORMAL);
        singleSubtitleView.setTextColor(parseColorWithOpacity(textColorHex, textOpacity, Color.WHITE));
        singleSubtitleView.setMaxWidth((screenW * bgWidth) / 100);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(parseColorWithOpacity(bgColorHex, bgOpacity, Color.argb(217, 0, 0, 0)));
        bg.setCornerRadius(0f);
        singleSubtitleView.setBackground(bg);

        int padH = Math.max(10, Math.round(bgHeight * 1.45f));
        singleSubtitleView.setPadding(padH, bgHeight, padH, bgHeight);

        FrameLayout.LayoutParams lp = (singleSubtitleView.getLayoutParams() instanceof FrameLayout.LayoutParams)
                ? (FrameLayout.LayoutParams) singleSubtitleView.getLayoutParams()
                : new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);

        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Math.max(16, Math.min(screenH - 100, (screenH * posY) / 100));
        int offsetX = ((posX - 50) * screenW) / 100;
        if (offsetX >= 0) {
            lp.leftMargin = offsetX;
            lp.rightMargin = 0;
        } else {
            lp.leftMargin = 0;
            lp.rightMargin = -offsetX;
        }
        singleSubtitleView.setLayoutParams(lp);
    }

    private void showSingleSubtitle(String text) {
        if (singleSubtitleView != null) {
            singleSubtitleView.setText(text);
            singleSubtitleView.setVisibility(View.VISIBLE);
        }
    }

    private void hideSingleSubtitle() {
        if (singleSubtitleView != null) {
            singleSubtitleView.setVisibility(View.GONE);
        }
    }

    private int parseColorWithOpacity(String hex, int opacityPct, int fallback) {
        try {
            int base = Color.parseColor(hex);
            int alpha = (opacityPct * 255) / 100;
            return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base));
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public void onDestroy() {
        stopScreenCaptureLoop();
        if (mqttClient != null) mqttClient.disconnect();
        if (isOverlayAttached && rootOverlayContainer != null) {
            try { windowManager.removeView(rootOverlayContainer); } catch (Exception ignored) {}
            isOverlayAttached = false;
        }
        super.onDestroy();
    }

    // ================= عميل MQTT TCP خفيف ومستقر في الخلفية =================
    private static class TvMqttTcpClient {
        interface OnConnected { void run(); }
        interface OnMessage { void onMessage(String topic, String payload); }

        private final String host;
        private final int port;
        private final String clientId;
        private final String subTopic;
        private final OnConnected onConnected;
        private final OnMessage onMessage;
        private final ExecutorService loopExecutor = Executors.newSingleThreadExecutor();
        private final ExecutorService pubExecutor = Executors.newSingleThreadExecutor();

        private volatile boolean running = false;
        private volatile Socket socket;
        private volatile OutputStream outStream;

        TvMqttTcpClient(String host, int port, String clientId, String subTopic,
                        OnConnected onConnected, OnMessage onMessage) {
            this.host = host;
            this.port = port;
            this.clientId = clientId;
            this.subTopic = subTopic;
            this.onConnected = onConnected;
            this.onMessage = onMessage;
        }

        boolean isForRoom(String code) {
            return running && subTopic.endsWith("/" + code);
        }

        void connect() {
            running = true;
            loopExecutor.execute(() -> {
                while (running) {
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(host, port), 5000);
                        s.setSoTimeout(30000);
                        socket = s;

                        OutputStream out = s.getOutputStream();
                        InputStream in = s.getInputStream();
                        outStream = out;

                        writeConnect(out, clientId);
                        int connAck = in.read();
                        if (connAck == 0x20) {
                            readRemainingLength(in);
                            in.read();
                            in.read();
                            writeSubscribe(out, subTopic);
                            if (onConnected != null) onConnected.run();

                            long lastPing = System.currentTimeMillis();
                            while (running && !s.isClosed()) {
                                if (System.currentTimeMillis() - lastPing > 15000L) {
                                    synchronized (out) {
                                        out.write(new byte[]{(byte) 0xC0, 0x00});
                                        out.flush();
                                    }
                                    lastPing = System.currentTimeMillis();
                                }

                                if (in.available() > 0) {
                                    int header = in.read();
                                    if (header == -1) break;
                                    int remLen = readRemainingLength(in);
                                    if (remLen < 0) break;

                                    byte[] payload = new byte[remLen];
                                    int readBytes = 0;
                                    while (readBytes < remLen) {
                                        int r = in.read(payload, readBytes, remLen - readBytes);
                                        if (r <= 0) break;
                                        readBytes += r;
                                    }

                                    if ((header & 0xF0) == 0x30 && payload.length >= 2) {
                                        int tLen = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                                        if (2 + tLen <= payload.length) {
                                            String topic = new String(payload, 2, tLen, StandardCharsets.UTF_8);
                                            int qos = (header >> 1) & 0x03;
                                            int offset = 2 + tLen + (qos > 0 ? 2 : 0);
                                            if (offset <= payload.length) {
                                                String msg = new String(payload, offset, payload.length - offset, StandardCharsets.UTF_8);
                                                if (onMessage != null) onMessage.onMessage(topic, msg);
                                            }
                                        }
                                    }
                                } else {
                                    Thread.sleep(20);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                        socket = null;
                        outStream = null;
                    }

                    if (running) {
                        try { Thread.sleep(2000); } catch (Exception ignored) {}
                    }
                }
            });
        }

        void publish(String topic, String message) {
            pubExecutor.execute(() -> {
                OutputStream out = outStream;
                if (out == null) return;
                try {
                    byte[] tBytes = topic.getBytes(StandardCharsets.UTF_8);
                    byte[] mBytes = message.getBytes(StandardCharsets.UTF_8);

                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.write((tBytes.length >> 8) & 0xFF);
                    body.write(tBytes.length & 0xFF);
                    body.write(tBytes);
                    body.write(mBytes);

                    byte[] bodyArr = body.toByteArray();
                    ByteArrayOutputStream pkt = new ByteArrayOutputStream();
                    pkt.write(0x30);
                    pkt.write(encodeRemainingLength(bodyArr.length));
                    pkt.write(bodyArr);

                    synchronized (out) {
                        out.write(pkt.toByteArray());
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            });
        }

        void disconnect() {
            running = false;
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            loopExecutor.shutdownNow();
            pubExecutor.shutdownNow();
        }

        private void writeConnect(OutputStream out, String cid) throws Exception {
            byte[] cBytes = cid.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(new byte[]{0x00, 0x04, 'M', 'Q', 'T', 'T', 0x04, 0x02, 0x00, 0x1E});
            body.write((cBytes.length >> 8) & 0xFF);
            body.write(cBytes.length & 0xFF);
            body.write(cBytes);

            byte[] arr = body.toByteArray();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x10);
            pkt.write(encodeRemainingLength(arr.length));
            pkt.write(arr);

            synchronized (out) {
                out.write(pkt.toByteArray());
                out.flush();
            }
        }

        private void writeSubscribe(OutputStream out, String topic) throws Exception {
            byte[] tBytes = topic.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(new byte[]{0x00, 0x01});
            body.write((tBytes.length >> 8) & 0xFF);
            body.write(tBytes.length & 0xFF);
            body.write(tBytes);
            body.write(0x00);

            byte[] arr = body.toByteArray();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x82);
            pkt.write(encodeRemainingLength(arr.length));
            pkt.write(arr);

            synchronized (out) {
                out.write(pkt.toByteArray());
                out.flush();
            }
        }

        private byte[] encodeRemainingLength(int len) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int x = len;
            do {
                int b = x % 128;
                x /= 128;
                if (x > 0) b |= 128;
                out.write(b);
            } while (x > 0);
            return out.toByteArray();
        }

        private int readRemainingLength(InputStream in) throws Exception {
            int multiplier = 1;
            int value = 0;
            int b;
            do {
                b = in.read();
                if (b == -1) return -1;
                value += (b & 127) * multiplier;
                multiplier *= 128;
            } while ((b & 128) != 0);
            return value;
        }
    }
}
