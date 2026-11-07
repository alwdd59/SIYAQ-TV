package com.siyaq.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Random;

public class MainActivity extends Activity {

    private String roomCode;
    private TextView statusLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // توليد رقم كود الغرفة المكون من 8 أرقام للربط مع موقع SIYAQ في جوالك
        int num = 10000000 + new Random().nextInt(90000000);
        roomCode = String.valueOf(num);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#060A14"));
        root.setPadding(60, 48, 60, 48);
        root.setGravity(Gravity.CENTER);

        // الشعار العلوي SIYAQ TV
        TextView brand = new TextView(this);
        brand.setText("SIYAQ TV");
        brand.setTextColor(Color.parseColor("#00F2FE"));
        brand.setTextSize(34);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setGravity(Gravity.END);
        root.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // الحاوية الوسطى: رمز QR + القائمة الزرقاء (مثل TranslaCat TV تماماً)
        LinearLayout centerRow = new LinearLayout(this);
        centerRow.setOrientation(LinearLayout.HORIZONTAL);
        centerRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(centerRow, rowParams);

        // القسم الأيسر: رمز QR ورقم الكود المكون من 8 أرقام
        LinearLayout qrBox = new LinearLayout(this);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER);
        qrBox.setPadding(32, 32, 32, 32);

        WebView qrWeb = new WebView(this);
        qrWeb.getSettings().setJavaScriptEnabled(false);
        String qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=SIYAQ-TV-" + roomCode;
        qrWeb.loadDataWithBaseURL(null,
                "<html><body style='margin:0;background:#fff;display:flex;align-items:center;justify-content:center;'>" +
                "<img src='" + qrUrl + "' width='210' height='210'/></body></html>",
                "text/html", "UTF-8", null);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(240, 240);
        qrBox.addView(qrWeb, qrParams);

        TextView codeView = new TextView(this);
        codeView.setText(roomCode);
        codeView.setTextColor(Color.parseColor("#00F2FE"));
        codeView.setTextSize(26);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setPadding(0, 20, 0, 6);
        codeView.setGravity(Gravity.CENTER);
        qrBox.addView(codeView);

        statusLabel = new TextView(this);
        statusLabel.setText("بانتظار ترجمة جديدة — امسح الرمز من موقع SIYAQ في جوالك");
        statusLabel.setTextColor(Color.parseColor("#94A3B8"));
        statusLabel.setTextSize(14);
        statusLabel.setGravity(Gravity.CENTER);
        qrBox.addView(statusLabel);

        centerRow.addView(qrBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // القسم الأيمن: قائمة التحكم بالتلفاز (اختبار HDMI / تفعيل الطبقة الشفافة / الاتصال)
        LinearLayout menuBox = new LinearLayout(this);
        menuBox.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(Color.parseColor("#0D1627"));
        menuBg.setCornerRadius(24f);
        menuBg.setStroke(2, Color.parseColor("#00F2FE"));
        menuBox.setBackground(menuBg);
        menuBox.setPadding(24, 24, 24, 24);

        menuBox.addView(createTvMenuButton("اختبار HDMI والشريط الشفاف (بدء الطبقة العائمة)", v -> startTransparentOverlayService(true)));
        menuBox.addView(createTvMenuButton("تفعيل صلاحية الظهور فوق الألعاب (Display Over Apps)", v -> requestOverlayPermission()));
        menuBox.addView(createTvMenuButton("إيقاف الشريط الشفاف", v -> stopService(new Intent(this, OverlaySubtitleService.class))));

        centerRow.addView(menuBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));

        setContentView(root);

        // بدء خدمة الطبقة الشفافة تلقائياً إذا كانت الصلاحية ممنوحة
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startTransparentOverlayService(false);
        }
    }

    private Button createTvMenuButton(String title, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextColor(Color.parseColor("#00F2FE"));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextSize(16);
        b.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        b.setPadding(24, 22, 24, 22);
        b.setOnClickListener(listener);
        return b;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 101);
        } else {
            Toast.makeText(this, "صلاحية الطبقة الشفافة مفعلة بالفعل ✓", Toast.LENGTH_SHORT).show();
            startTransparentOverlayService(true);
        }
    }

    private void startTransparentOverlayService(boolean minimizeToGame) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        Intent svc = new Intent(this, OverlaySubtitleService.class);
        svc.putExtra("ROOM_CODE", roomCode);
        startService(svc);
        Toast.makeText(this, "شريط SIYAQ الشفاف نشط الآن فوق التلفاز ✓", Toast.LENGTH_SHORT).show();
        if (minimizeToGame) {
            // يخرج للخلفية ويبقى شريط الترجمة الشفاف عائماً فوق شاشة التلفاز أو منفذ HDMI!
            moveTaskToBack(true);
        }
    }
}
