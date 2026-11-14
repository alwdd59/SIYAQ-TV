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
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public enum TvOperatingMode {
        IDLE,        // في انتظار الاقتران
        CAMERA_PRO,  // وضع الكاميرا: يعرض الترجمة المرسلة من الجوال فقط (بدون OCR)
        TV_ULTRA     // وضع ألترا: يقرأ الشاشة ويرسل النص للجوال ويعرض الترجمة فوقه
    }

    private static final int REQ_OVERLAY_PERMISSION = 1001;
    private static final int REQ_MEDIA_PROJECTION = 1002;
    private static final String MQTT_HOST = "broker.emqx.io";
    private static final int MQTT_PORT = 1883;

    public static Intent activeProjectionData = null;
    public static int activeProjectionResultCode = Activity.RESULT_CANCELED;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String roomCode;

    private volatile TvOperatingMode currentMode = TvOperatingMode.IDLE;
    private String activeTier = "free";
    private float ultraTopRatio = 0.72f;
    private float ultraHeightRatio = 0.20f;

    private TextView statusTextView;
    private TextView modeBadgeView;
    private ImageView qrImageView;
    private TextView codeTextView;
    private Button permOverlayBtn;
    private Button unpairBtn;

    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private TextView subtitleTextView;
    private boolean isOverlayAttached = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer textRecognizer;

    private volatile boolean isUltraCapturing = false;
    private volatile boolean isOcrBusy = false;
    private String lastUltraSentText = "";
    private long lastOcrTimestamp = 0L;

    private SimpleMqttTcpClient mqttClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        SharedPreferences prefs = getSharedPreferences("siyaq_tv_prefs", Context.MODE_PRIVATE);
        roomCode = prefs.getString("room_code", null);
        if (roomCode == null) {
            roomCode = String.valueOf(10000000 + new Random().nextInt(90000000));
            prefs.edit().putString("room_code", roomCode).apply();
        }

        buildTvPairingUI();
        checkOverlayPermissionState();
        startMqttSync();
    }

    private void buildTvPairingUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#050507"));
        root.setPadding(48, 36, 48, 36);

        TextView titleView = new TextView(this);
        titleView.setText("SIYAQ TV");
        titleView.setTextSize(30f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.parseColor("#F5A623"));
        titleView.setGravity(Gravity.CENTER);

        modeBadgeView = new TextView(this);
        modeBadgeView.setText("في انتظار الاقتران من الجوال...");
        modeBadgeView.setTextSize(15f);
        modeBadgeView.setTextColor(Color.parseColor("#94A3B8"));
        modeBadgeView.setGravity(Gravity.CENTER);
        modeBadgeView.setPadding(0, 8, 0, 24);

        qrImageView = new ImageView(this);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(340, 340);
        qrParams.gravity = Gravity.CENTER;
        qrImageView.setLayoutParams(qrParams);
        qrImageView.setBackgroundColor(Color.WHITE);
        qrImageView.setPadding(16, 16, 16, 16);
        Bitmap qrBmp = generateQrBitmap("SIYAQ:" + roomCode, 320);
        if (qrBmp != null) {
            qrImageView.setImageBitmap(qrBmp);
        }

        codeTextView = new TextView(this);
        codeTextView.setText(roomCode);
        codeTextView.setTextSize(32f);
        codeTextView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeTextView.setTextColor(Color.WHITE);
        codeTextView.setLetterSpacing(0.18f);
        codeTextView.setGravity(Gravity.CENTER);
        codeTextView.setPadding(0, 20, 0, 8);

        statusTextView = new TextView(this);
        statusTextView.setText("افتح تطبيق سياق في جوالك وامسح الرمز أو أدخل الرقم");
        statusTextView.setTextSize(14f);
        statusTextView.setTextColor(Color.parseColor("#CBD5E1"));
        statusTextView.setGravity(Gravity.CENTER);
        statusTextView.setPadding(0, 4, 0, 24);

        permOverlayBtn = new Button(this);
        permOverlayBtn.setText("تفعيل صلاحية الظهور فوق الألعاب");
        permOverlayBtn.setTextColor(Color.BLACK);
        permOverlayBtn.setBackgroundColor(Color.parseColor("#F5A623"));
        permOverlayBtn.setPadding(36, 16, 36, 16);
        permOverlayBtn.setOnClickListener(v -> requestOverlayPermission());

        unpairBtn = new Button(this);
        unpairBtn.setText("إلغاء الاقتران الحالي");
        unpairBtn.setTextColor(Color.parseColor("#FB7185"));
        unpairBtn.setBackgroundColor(Color.parseColor("#18181F"));
        unpairBtn.setVisibility(View.GONE);
        unpairBtn.setOnClickListener(v -> {
            if (mqttClient != null) {
                mqttClient.publish("siyaq/tv/sec/" + roomCode, "{\"type\":\"UNPAIRED\"}");
            }
            resetToIdleState("تم إلغاء الاقتران");
        });

        root.addView(titleView);
        root.addView(modeBadgeView);
        root.addView(qrImageView);
        root.addView(codeTextView);
        root.addView(statusTextView);
        root.addView(permOverlayBtn);
        root.addView(unpairBtn);

        setContentView(root);
    }

    private Bitmap generateQrBitmap(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void checkOverlayPermissionState() {
        boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        permOverlayBtn.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkOverlayPermissionState();
    }

    private void startMqttSync() {
        mqttClient = new SimpleMqttTcpClient(
                MQTT_HOST,
                MQTT_PORT,
                "siyaq_tv_" + roomCode,
                "siyaq/tv/sec/" + roomCode,
                () -> mainHandler.post(() -> {
                    if (currentMode == TvOperatingMode.IDLE) {
                        statusTextView.setText("جاهز للاقتران • امسح الرمز من جوالك");
                    }
                }),
                (topic, payload) -> mainHandler.post(() -> handlePhoneMessage(payload))
        );
        mqttClient.connect();
    }

    private void handlePhoneMessage(String rawPayload) {
        try {
            JSONObject data = new JSONObject(rawPayload);
            String type = data.optString("type");

            if ("PAIRED".equals(type) || "MODE_SWITCH".equals(type)) {
                String requestedMode = data.optString("mode", "IDLE");
                String tier = data.optString("tier", "free");
                String token = data.optString("sessionToken", "");
                JSONObject style = data.optJSONObject("style");
                JSONObject roi = data.optJSONObject("roi");

                if (!isTokenValid(token, tier)) {
                    resetToIdleState("جلسة اقتران غير صالحة");
                    return;
                }

                if ("CAMERA_PRO".equals(requestedMode)) {
                    if (!"pro".equals(tier) && !"ultra".equals(tier)) {
                        resetToIdleState("هذا الوضع يتطلب باقة Pro");
                        return;
                    }
                    stopUltraScreenCapture();
                    currentMode = TvOperatingMode.CAMERA_PRO;
                    activeTier = tier;
                    modeBadgeView.setText("متصل • وضع التلفاز بالكاميرا (Pro)");
                    modeBadgeView.setTextColor(Color.parseColor("#F5A623"));
                    statusTextView.setText("يعرض الترجمة المرسلة من كاميرا الجوال فقط");
                    unpairBtn.setVisibility(View.VISIBLE);
                    ensureOverlayCreated();
                    applyOverlayStyle(style);

                } else if ("TV_ULTRA".equals(requestedMode)) {
                    if (!"ultra".equals(tier)) {
                        if (mqttClient != null) {
                            mqttClient.publish("siyaq/tv/sec/" + roomCode, "{\"type\":\"ULTRA_DENIED\"}");
                        }
                        resetToIdleState("وضع التلفاز ألترا حصري لباقة Ultra");
                        return;
                    }
                    currentMode = TvOperatingMode.TV_ULTRA;
                    activeTier = "ultra";
                    if (roi != null) {
                        ultraTopRatio = (float) roi.optDouble("top", 0.72);
                        ultraHeightRatio = (float) roi.optDouble("height", 0.20);
                    }
                    modeBadgeView.setText("متصل • وضع التلفاز ألترا (بدون كاميرا)");
                    modeBadgeView.setTextColor(Color.parseColor("#34D399"));
                    statusTextView.setText("يقرأ الشاشة ويرسل النص للجوال تلقائياً");
                    unpairBtn.setVisibility(View.VISIBLE);
                    ensureOverlayCreated();
                    applyOverlayStyle(style);
                    requestAndStartUltraCapture();

                } else {
                    resetToIdleState(null);
                }

            } else if ("ULTRA_CONTROL".equals(type)) {
                if (currentMode != TvOperatingMode.TV_ULTRA || !"ultra".equals(activeTier)) return;
                String action = data.optString("action");
                JSONObject roi = data.optJSONObject("roi");
                if (roi != null) {
                    ultraTopRatio = (float) roi.optDouble("top", 0.72);
                    ultraHeightRatio = (float) roi.optDouble("height", 0.20);
                }
                if ("START".equals(action) || "SET_ROI".equals(action)) {
                    requestAndStartUltraCapture();
                } else if ("STOP".equals(action)) {
                    stopUltraScreenCapture();
                    hideSubtitleText();
                }

            } else if ("SUB_UPDATE".equals(type)) {
                if (currentMode == TvOperatingMode.IDLE) return;
                String msgMode = data.optString("mode");
                if (!msgMode.isEmpty() && !msgMode.equals(currentMode.name())) return;

                String arabicText = data.optString("text");
                JSONObject style = data.optJSONObject("style");
                if (!arabicText.isEmpty()) {
                    ensureOverlayCreated();
                    applyOverlayStyle(style);
                    showSubtitleText(arabicText);
                }

            } else if ("SUB_CLEAR".equals(type)) {
                hideSubtitleText();

            } else if ("UNPAIRED".equals(type)) {
                resetToIdleState("تم إلغاء الاقتران من الجوال");
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isTokenValid(String token, String expectedTier) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            String decoded = new String(Base64.decode(token, Base64.DEFAULT), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            return parts.length == 4
                    && "SIYAQ".equals(parts[0])
                    && expectedTier.equals(parts[1])
                    && roomCode.equals(parts[2]);
        } catch (Exception e) {
            return false;
        }
    }

    private void resetToIdleState(String msg) {
        currentMode = TvOperatingMode.IDLE;
        activeTier = "free";
        stopUltraScreenCapture();
        hideSubtitleText();
        unpairBtn.setVisibility(View.GONE);
        modeBadgeView.setText("في انتظار الاقتران من الجوال...");
        modeBadgeView.setTextColor(Color.parseColor("#94A3B8"));
        statusTextView.setText(msg != null ? msg : "افتح تطبيق سياق في جوالك وامسح الرمز أو أدخل الرقم");
    }

    private void requestAndStartUltraCapture() {
        if (isUltraCapturing) return;
        if (activeProjectionData != null && activeProjectionResultCode == Activity.RESULT_OK) {
            startUltraProjectionInternal();
        } else {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            checkOverlayPermissionState();
        } else if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                activeProjectionResultCode = resultCode;
                activeProjectionData = data;
                if (currentMode == TvOperatingMode.TV_ULTRA) {
                    startUltraProjectionInternal();
                }
            } else {
                Toast.makeText(this, "يجب السماح بالتقاط الشاشة لتشغيل وضع ألترا", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startUltraProjectionInternal() {
        if (isUltraCapturing || activeProjectionData == null) return;

        Intent serviceIntent = new Intent(this, UltraCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        mainHandler.postDelayed(() -> {
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpm.getMediaProjection(activeProjectionResultCode, activeProjectionData);
                if (mediaProjection == null) return;

                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        mainHandler.post(() -> stopUltraScreenCapture());
                    }
                }, mainHandler);

                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                final int width = Math.max(640, metrics.widthPixels / 2);
                final int height = Math.max(360, metrics.heightPixels / 2);
                int density = metrics.densityDpi;

                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
                virtualDisplay = mediaProjection.createVirtualDisplay(
                        "SiyaqUltraCapture",
                        width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.getSurface(), null, mainHandler
                );

                isUltraCapturing = true;
                lastUltraSentText = "";

                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = reader.acquireLatestImage();
                    if (image == null) return;
                    try {
                        long now = System.currentTimeMillis();
                        if (currentMode != TvOperatingMode.TV_ULTRA || isOcrBusy || (now - lastOcrTimestamp) < 220L) {
                            return;
                        }
                        lastOcrTimestamp = now;
                        isOcrBusy = true;

                        Image.Plane plane = image.getPlanes()[0];
                        ByteBuffer buffer = plane.getBuffer();
                        int pixelStride = plane.getPixelStride();
                        int rowStride = plane.getRowStride();
                        int rowPadding = rowStride - pixelStride * width;

                        Bitmap fullBitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                        );
                        fullBitmap.copyPixelsFromBuffer(buffer);

                        int cropY = Math.max(0, Math.min(height - 24, (int) (height * ultraTopRatio)));
                        int cropH = Math.max(24, Math.min(height - cropY, (int) (height * ultraHeightRatio)));
                        final Bitmap stripBitmap = Bitmap.createBitmap(fullBitmap, 0, cropY, width, cropH);
                        fullBitmap.recycle();

                        InputImage inputImage = InputImage.fromBitmap(stripBitmap, 0);
                        textRecognizer.process(inputImage)
                                .addOnSuccessListener(visionText -> {
                                    String cleanText = visionText.getText()
                                            .replace("\n", " ")
                                            .replaceAll("\\s+", " ")
                                            .trim();

                                    if (!cleanText.equals(lastUltraSentText) && currentMode == TvOperatingMode.TV_ULTRA) {
                                        lastUltraSentText = cleanText;
                                        try {
                                            JSONObject json = new JSONObject();
                                            json.put("type", "TV_OCR_TEXT");
                                            json.put("text", cleanText);
                                            if (mqttClient != null) {
                                                mqttClient.publish("siyaq/tv/ultra/" + roomCode, json.toString());
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }
                                })
                                .addOnCompleteListener(task -> {
                                    stripBitmap.recycle();
                                    isOcrBusy = false;
                                });
                    } catch (Exception e) {
                        isOcrBusy = false;
                    } finally {
                        image.close();
                    }
                }, mainHandler);
            } catch (Exception e) {
                isUltraCapturing = false;
            }
        }, 180);
    }

    private void stopUltraScreenCapture() {
        isUltraCapturing = false;
        isOcrBusy = false;
        lastUltraSentText = "";
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        try { if (mediaProjection != null) mediaProjection.stop(); } catch (Exception ignored) {}
        virtualDisplay = null;
        imageReader = null;
        mediaProjection = null;
        try { stopService(new Intent(this, UltraCaptureService.class)); } catch (Exception ignored) {}
    }

    private void ensureOverlayCreated() {
        if (isOverlayAttached) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;

        overlayRoot = new FrameLayout(this);
        subtitleTextView = new TextView(this);
        subtitleTextView.setText("");
        subtitleTextView.setVisibility(View.GONE);
        subtitleTextView.setGravity(Gravity.CENTER);
        subtitleTextView.setTextColor(Color.WHITE);
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        subtitleTextView.setTypeface(null, Typeface.BOLD);
        subtitleTextView.setPadding(28, 14, 28, 14);

        FrameLayout.LayoutParams childParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        childParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        childParams.bottomMargin = 80;
        overlayRoot.addView(subtitleTextView, childParams);

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
            windowManager.addView(overlayRoot, params);
            isOverlayAttached = true;
        } catch (Exception ignored) {
        }
    }

    private void applyOverlayStyle(JSONObject style) {
        if (subtitleTextView == null || style == null) return;

        float fontSize = (float) style.optInt("fontSize", 24);
        boolean isBold = style.optBoolean("isBold", true);
        String textColorHex = style.optString("textColor", "#FFFFFF");
        int textOpacity = Math.max(10, Math.min(100, style.optInt("textOpacity", 100)));
        String bgColorHex = style.optString("bgColor", "#000000");
        int bgOpacity = Math.max(0, Math.min(100, style.optInt("bgOpacity", 85)));
        int bgHeight = style.optInt("bgHeight", 10);
        int posY = Math.max(10, Math.min(92, style.optInt("posY", 82)));

        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        subtitleTextView.setTypeface(null, isBold ? Typeface.BOLD : Typeface.NORMAL);

        try {
            int baseTextColor = Color.parseColor(textColorHex);
            int alphaText = (textOpacity * 255) / 100;
            subtitleTextView.setTextColor(Color.argb(
                    alphaText,
                    Color.red(baseTextColor),
                    Color.green(baseTextColor),
                    Color.blue(baseTextColor)
            ));
        } catch (Exception ignored) {
        }

        try {
            int baseBgColor = Color.parseColor(bgColorHex);
            int alphaBg = (bgOpacity * 255) / 100;
            GradientDrawable bgDrawable = new GradientDrawable();
            bgDrawable.setColor(Color.argb(
                    alphaBg,
                    Color.red(baseBgColor),
                    Color.green(baseBgColor),
                    Color.blue(baseBgColor)
            ));
            bgDrawable.setCornerRadius(0f);
            subtitleTextView.setBackground(bgDrawable);
        } catch (Exception ignored) {
        }

        int padH = Math.max(12, (int) (bgHeight * 1.5));
        subtitleTextView.setPadding(padH, bgHeight, padH, bgHeight);

        int screenH = getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp = (subtitleTextView.getLayoutParams() instanceof FrameLayout.LayoutParams)
                ? (FrameLayout.LayoutParams) subtitleTextView.getLayoutParams()
                : new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Math.max(20, Math.min(screenH - 120, (int) ((screenH * posY) / 100f)));
        subtitleTextView.setLayoutParams(lp);
    }

    private void showSubtitleText(String text) {
        if (subtitleTextView != null) {
            subtitleTextView.setText(text);
            subtitleTextView.setVisibility(View.VISIBLE);
        }
    }

    private void hideSubtitleText() {
        if (subtitleTextView != null) {
            subtitleTextView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        stopUltraScreenCapture();
        if (mqttClient != null) mqttClient.disconnect();
        if (isOverlayAttached && overlayRoot != null) {
            try { windowManager.removeView(overlayRoot); } catch (Exception ignored) {}
            isOverlayAttached = false;
        }
        super.onDestroy();
    }

    public static class UltraCaptureService extends Service {
        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onCreate() {
            super.onCreate();
            String channelId = "siyaq_ultra_channel";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId,
                        "SIYAQ Ultra Screen Reader",
                        NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) nm.createNotificationChannel(channel);
            }
            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("SIYAQ TV Ultra")
                    .setContentText("قراءة الترجمة الفورية نشطة")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(9091, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(9091, notification);
            }
        }
    }

    private static class SimpleMqttTcpClient {
        interface OnConnectedListener { void onConnected(); }
        interface OnMessageListener { void onMessage(String topic, String payload); }

        private final String host;
        private final int port;
        private final String clientId;
        private final String topicToSubscribe;
        private final OnConnectedListener onConnected;
        private final OnMessageListener onMessageReceived;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        private volatile boolean running = false;
        private volatile Socket socket;
        private volatile OutputStream outStream;

        SimpleMqttTcpClient(String host, int port, String clientId, String topicToSubscribe,
                            OnConnectedListener onConnected, OnMessageListener onMessageReceived) {
            this.host = host;
            this.port = port;
            this.clientId = clientId;
            this.topicToSubscribe = topicToSubscribe;
            this.onConnected = onConnected;
            this.onMessageReceived = onMessageReceived;
        }

        void connect() {
            running = true;
            executor.execute(() -> {
                while (running) {
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(host, port), 5000);
                        s.setSoTimeout(35000);
                        socket = s;
                        OutputStream out = s.getOutputStream();
                        InputStream input = s.getInputStream();
                        outStream = out;

                        sendConnectPacket(out, clientId);
                        int firstByte = input.read();
                        if (firstByte == 0x20) {
                            readRemainingLength(input);
                            input.read();
                            input.read();
                            sendSubscribePacket(out, topicToSubscribe);
                            if (onConnected != null) onConnected.onConnected();

                            long lastPing = System.currentTimeMillis();
                            while (running && !s.isClosed()) {
                                if (System.currentTimeMillis() - lastPing > 18000L) {
                                    synchronized (out) {
                                        out.write(new byte[]{(byte) 0xC0, 0x00});
                                        out.flush();
                                    }
                                    lastPing = System.currentTimeMillis();
                                }
                                if (input.available() > 0) {
                                    int pktType = input.read();
                                    if (pktType == -1) break;
                                    int remLen = readRemainingLength(input);
                                    if (remLen < 0) break;
                                    byte[] body = new byte[remLen];
                                    int readTotal = 0;
                                    while (readTotal < remLen) {
                                        int r = input.read(body, readTotal, remLen - readTotal);
                                        if (r <= 0) break;
                                        readTotal += r;
                                    }
                                    if ((pktType & 0xF0) == 0x30 && body.length >= 2) {
                                        int topicLen = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                                        if (2 + topicLen <= body.length) {
                                            String topic = new String(body, 2, topicLen, StandardCharsets.UTF_8);
                                            int qos = (pktType >> 1) & 0x03;
                                            int offset = 2 + topicLen + (qos > 0 ? 2 : 0);
                                            if (offset <= body.length) {
                                                String msg = new String(body, offset, body.length - offset, StandardCharsets.UTF_8);
                                                if (onMessageReceived != null) onMessageReceived.onMessage(topic, msg);
                                            }
                                        }
                                    }
                                } else {
                                    Thread.sleep(25);
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
            final OutputStream out = outStream;
            if (out == null) return;
            new Thread(() -> {
                try {
                    byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
                    byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
                    ByteArrayOutputStream varHeaderAndPayload = new ByteArrayOutputStream();
                    varHeaderAndPayload.write((topicBytes.length >> 8) & 0xFF);
                    varHeaderAndPayload.write(topicBytes.length & 0xFF);
                    varHeaderAndPayload.write(topicBytes);
                    varHeaderAndPayload.write(msgBytes);

                    byte[] body = varHeaderAndPayload.toByteArray();
                    ByteArrayOutputStream packet = new ByteArrayOutputStream();
                    packet.write(0x30);
                    packet.write(encodeRemainingLength(body.length));
                    packet.write(body);

                    synchronized (out) {
                        out.write(packet.toByteArray());
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            }).start();
        }

        void disconnect() {
            running = false;
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }

        private void sendConnectPacket(OutputStream out, String cid) throws Exception {
            byte[] cidBytes = cid.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(new byte[]{0x00, 0x04, 'M', 'Q', 'T', 'T', 0x04, 0x02, 0x00, 0x1E});
            payload.write((cidBytes.length >> 8) & 0xFF);
            payload.write(cidBytes.length & 0xFF);
            payload.write(cidBytes);

            byte[] payloadArr = payload.toByteArray();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x10);
            pkt.write(encodeRemainingLength(payloadArr.length));
            pkt.write(payloadArr);

            synchronized (out) {
                out.write(pkt.toByteArray());
                out.flush();
            }
        }

        private void sendSubscribePacket(OutputStream out, String topic) throws Exception {
            byte[] tBytes = topic.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(new byte[]{0x00, 0x01});
            payload.write((tBytes.length >> 8) & 0xFF);
            payload.write(tBytes.length & 0xFF);
            payload.write(tBytes);
            payload.write(0x00);

            byte[] payloadArr = payload.toByteArray();
            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x82);
            pkt.write(encodeRemainingLength(payloadArr.length));
            pkt.write(payloadArr);

            synchronized (out) {
                out.write(pkt.toByteArray());
                out.flush();
            }
        }

        private byte[] encodeRemainingLength(int len) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int x = len;
            do {
                int encodedByte = x % 128;
                x /= 128;
                if (x > 0) encodedByte |= 128;
                out.write(encodedByte);
            } while (x > 0);
            return out.toByteArray();
        }

        private int readRemainingLength(InputStream input) throws Exception {
            int multiplier = 1;
            int value = 0;
            int encodedByte;
            do {
                encodedByte = input.read();
                if (encodedByte == -1) return -1;
                value += (encodedByte & 127) * multiplier;
                multiplier *= 128;
            } while ((encodedByte & 128) != 0);
            return value;
        }
    }
}
