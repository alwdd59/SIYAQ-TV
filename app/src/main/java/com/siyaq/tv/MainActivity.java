package com.siyaq.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.Random;

public class MainActivity extends AppCompatActivity implements OverlaySubtitleService.ServiceStateListener {

    private static final int REQ_OVERLAY_PERMISSION = 1001;
    private static final int REQ_MEDIA_PROJECTION_SCREEN = 1002;
    private static final int REQ_MEDIA_PROJECTION_HDMI_TEST = 1003;

    private String roomCode;

    private TextView statusBadgeText;
    private TextView subHintText;
    private ImageView qrImageView;
    private TextView codeTextView;
    private Button permOverlayBtn;
    private Button testHdmiBtn;
    private Button unpairBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("siyaq_tv_prefs", Context.MODE_PRIVATE);
        roomCode = prefs.getString("room_code", null);
        if (roomCode == null || roomCode.length() != 8) {
            roomCode = String.valueOf(10000000 + new Random().nextInt(90000000));
            prefs.edit().putString("room_code", roomCode).apply();
        }

        buildSiyaqTvLayout();
        OverlaySubtitleService.setStateListener(this);
        startOverlayServiceCommand(OverlaySubtitleService.ACTION_INIT);
    }

    private void buildSiyaqTvLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#050507"));
        root.setPadding(48, 32, 48, 32);

        // شعار SIYAQ TV
        TextView brandTitle = new TextView(this);
        brandTitle.setText("SIYAQ TV");
        brandTitle.setTextSize(30f);
        brandTitle.setTypeface(null, Typeface.BOLD);
        brandTitle.setTextColor(Color.parseColor("#F5A623"));
        brandTitle.setGravity(Gravity.CENTER);

        // شارة الحالة
        statusBadgeText = new TextView(this);
        statusBadgeText.setText("في انتظار الاقتران من الجوال...");
        statusBadgeText.setTextSize(15f);
        statusBadgeText.setTypeface(null, Typeface.BOLD);
        statusBadgeText.setTextColor(Color.parseColor("#94A3B8"));
        statusBadgeText.setGravity(Gravity.CENTER);
        statusBadgeText.setPadding(0, 8, 0, 20);

        // صندوق رمز QR
        qrImageView = new ImageView(this);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(310, 310);
        qrParams.gravity = Gravity.CENTER;
        qrImageView.setLayoutParams(qrParams);
        qrImageView.setBackgroundColor(Color.WHITE);
        qrImageView.setPadding(14, 14, 14, 14);
        Bitmap qrBitmap = createQrCode("SIYAQ:" + roomCode, 300);
        if (qrBitmap != null) {
            qrImageView.setImageBitmap(qrBitmap);
        }

        // رقم الكود ذو 8 خانات
        codeTextView = new TextView(this);
        codeTextView.setText(roomCode);
        codeTextView.setTextSize(30f);
        codeTextView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeTextView.setTextColor(Color.WHITE);
        codeTextView.setLetterSpacing(0.18f);
        codeTextView.setGravity(Gravity.CENTER);
        codeTextView.setPadding(0, 16, 0, 6);

        subHintText = new TextView(this);
        subHintText.setText("افتح موقع سياق في جوالك وامسح الرمز أو أدخل الرقم");
        subHintText.setTextSize(13f);
        subHintText.setTextColor(Color.parseColor("#CBD5E1"));
        subHintText.setGravity(Gravity.CENTER);
        subHintText.setPadding(0, 0, 0, 22);

        // صف الأزرار المتوافق مع ريموت التلفاز (D-Pad)
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER);

        permOverlayBtn = createTvRemoteButton("تفعيل صلاحية الشاشة", "#F5A623", "#050507");
        permOverlayBtn.setOnClickListener(v -> requestOverlayPermission());

        testHdmiBtn = createTvRemoteButton("اختبار HDMI", "#14141C", "#F5A623");
        testHdmiBtn.setOnClickListener(v -> startHdmiTestFlow());

        unpairBtn = createTvRemoteButton("إلغاء الاقتران", "#1C1014", "#FB7185");
        unpairBtn.setVisibility(View.GONE);
        unpairBtn.setOnClickListener(v -> startOverlayServiceCommand(OverlaySubtitleService.ACTION_UNPAIR));

        LinearLayout.LayoutParams btnMargin = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnMargin.setMargins(12, 0, 12, 0);

        actionsRow.addView(permOverlayBtn, btnMargin);
        actionsRow.addView(testHdmiBtn, btnMargin);
        actionsRow.addView(unpairBtn, btnMargin);

        root.addView(brandTitle);
        root.addView(statusBadgeText);
        root.addView(qrImageView);
        root.addView(codeTextView);
        root.addView(subHintText);
        root.addView(actionsRow);

        setContentView(root);
        refreshPermissionUi();
    }

    private Button createTvRemoteButton(String label, String bgHex, String textHex) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor(textHex));
        btn.setTextSize(14f);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setAllCaps(false);
        btn.setPadding(34, 16, 34, 16);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(false);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.parseColor(bgHex));
        normalBg.setCornerRadius(22f);
        normalBg.setStroke(2, Color.parseColor("#2A2A36"));

        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setColor(Color.parseColor(bgHex));
        focusedBg.setCornerRadius(22f);
        focusedBg.setStroke(5, Color.WHITE);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focusedBg);
        states.addState(new int[]{}, normalBg);
        btn.setBackground(states);

        return btn;
    }

    private Bitmap createQrCode(String data, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size);
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

    private void refreshPermissionUi() {
        boolean hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        permOverlayBtn.setVisibility(hasOverlay ? View.GONE : View.VISIBLE);
        if (!hasOverlay) {
            permOverlayBtn.requestFocus();
        } else {
            testHdmiBtn.requestFocus();
        }
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

    private void startHdmiTestFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل صلاحية الظهور فوق الشاشة أولاً", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }

        if (OverlaySubtitleService.hasActiveProjection()) {
            startOverlayServiceCommand(OverlaySubtitleService.ACTION_TEST_HDMI);
        } else {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm != null) {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION_HDMI_TEST);
            }
        }
    }

    private void startOverlayServiceCommand(String action) {
        Intent intent = new Intent(this, OverlaySubtitleService.class);
        intent.setAction(action);
        intent.putExtra(OverlaySubtitleService.EXTRA_ROOM_CODE, roomCode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        OverlaySubtitleService.setStateListener(this);
        refreshPermissionUi();
    }

    @Override
    protected void onDestroy() {
        OverlaySubtitleService.setStateListener(null);
        super.onDestroy();
    }

    @Override
    public void onRequestScreenCaptureForMode() {
        runOnUiThread(() -> {
            if (OverlaySubtitleService.hasActiveProjection()) {
                startOverlayServiceCommand(OverlaySubtitleService.ACTION_START_SCREEN_OCR);
            } else {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mpm != null) {
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION_SCREEN);
                }
            }
        });
    }

    @Override
    public void onServiceStatusChanged(boolean paired, String mode, String statusMsg) {
        runOnUiThread(() -> {
            if (paired) {
                unpairBtn.setVisibility(View.VISIBLE);
                if ("SCREEN_MODE".equals(mode)) {
                    statusBadgeText.setText("متصل • وضع الشاشة الكاملة (تلقائي)");
                    statusBadgeText.setTextColor(Color.parseColor("#34D399"));
                    subHintText.setText("يقرأ نصوص الشاشة ويرسلها للجوال ويعرض الترجمة فوقها");
                } else {
                    statusBadgeText.setText("متصل • وضع الكاميرا");
                    statusBadgeText.setTextColor(Color.parseColor("#F5A623"));
                    subHintText.setText("يعرض الترجمة المرسلة من كاميرا الجوال");
                }
            } else {
                unpairBtn.setVisibility(View.GONE);
                statusBadgeText.setText(statusMsg != null ? statusMsg : "في انتظار الاقتران من الجوال...");
                statusBadgeText.setTextColor(Color.parseColor("#94A3B8"));
                subHintText.setText("افتح موقع سياق في جوالك وامسح الرمز أو أدخل الرقم");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY_PERMISSION) {
            refreshPermissionUi();
        } else if (requestCode == REQ_MEDIA_PROJECTION_SCREEN) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent svc = new Intent(this, OverlaySubtitleService.class);
                svc.setAction(OverlaySubtitleService.ACTION_GRANT_PROJECTION_AND_RUN);
                svc.putExtra(OverlaySubtitleService.EXTRA_ROOM_CODE, roomCode);
                svc.putExtra(OverlaySubtitleService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(OverlaySubtitleService.EXTRA_RESULT_DATA, data);
                svc.putExtra(OverlaySubtitleService.EXTRA_RUN_HDMI_TEST, false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } else {
                Toast.makeText(this, "يجب السماح بالتقاط الشاشة لتشغيل وضع الشاشة", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_MEDIA_PROJECTION_HDMI_TEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent svc = new Intent(this, OverlaySubtitleService.class);
                svc.setAction(OverlaySubtitleService.ACTION_GRANT_PROJECTION_AND_RUN);
                svc.putExtra(OverlaySubtitleService.EXTRA_ROOM_CODE, roomCode);
                svc.putExtra(OverlaySubtitleService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(OverlaySubtitleService.EXTRA_RESULT_DATA, data);
                svc.putExtra(OverlaySubtitleService.EXTRA_RUN_HDMI_TEST, true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } else {
                Toast.makeText(this, "تم إلغاء اختبار HDMI", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
