package com.bvdhytb.nhacthuoc;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.util.Calendar;

public class MainActivity extends AppCompatActivity {

    WebView webView;
    static final String CHANNEL_ID = "medreminder_alarm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.CAMERA
                }, 1);
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.CAMERA}, 1);
        }
        requestBatteryExemption();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);

        webView.addJavascriptInterface(new AlarmBridge(this), "AndroidAlarm");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://") || url.startsWith("https://bvdhytb")) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Kiểm tra và cấp quyền camera cho WebView
                boolean hasCameraPermission = ContextCompat.checkSelfPermission(
                    MainActivity.this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
                
                if (hasCameraPermission) {
                    runOnUiThread(() -> request.grant(request.getResources()));
                } else {
                    ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.CAMERA}, 2);
                    // Cấp tạm để không bị block
                    runOnUiThread(() -> request.grant(request.getResources()));
                }
            }
        });

        // Load từ assets (file đóng gói trong APK)
        webView.loadUrl("file:///android_asset/public/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Nhắc dùng thuốc", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("BV Đại học Y Thái Bình");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0,500,200,500,200,500,200,800});
            ch.setBypassDnd(true);
            ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound != null) {
                ch.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {}
            }
        }
    }

    // ===== JAVASCRIPT BRIDGE =====
    public static class AlarmBridge {
        final Context ctx;
        AlarmBridge(Context ctx) { this.ctx = ctx; }

        @JavascriptInterface
        public void scheduleAlarmAt(double timestampMs, int requestCode, String title, String body) {
            // Đặt alarm tại thời điểm cụ thể (timestamp milliseconds)
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long triggerAt = (long) timestampMs;
            if (triggerAt <= System.currentTimeMillis()) return; // Đã qua

            Intent intent = new Intent(ctx, AlarmReceiver.class);
            intent.putExtra("medName", title != null ? title : "Nhắc hẹn");
            intent.putExtra("medDose", "");
            intent.putExtra("medNote", body != null ? body : "");
            intent.putExtra("hour", -1); // Không lặp lại hàng ngày
            intent.putExtra("minute", -1);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            else
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }

        @JavascriptInterface
        public boolean isAvailable() { return true; }

        @JavascriptInterface
        public void scheduleAlarm(int hour, int minute, String name, String dose, String note) {
            // name/dose có thể chứa nhiều thuốc ngăn cách bởi | (pipe)
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(ctx, AlarmReceiver.class);
            intent.putExtra("medName", name != null ? name : "Thuốc");
            intent.putExtra("medDose", dose != null ? dose : "");
            intent.putExtra("medNote", note != null ? note : "");
            intent.putExtra("hour", hour);
            intent.putExtra("minute", minute);
            int rc = hour * 100 + minute;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, rc, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis())
                cal.add(Calendar.DAY_OF_YEAR, 1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            else
                am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }

        @JavascriptInterface
        public void cancelAlarm(int hour, int minute) {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(ctx, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, hour*100+minute, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        }

        @JavascriptInterface
        public void cancelAllAlarms() {
            for (int h = 0; h < 24; h++)
                for (int m = 0; m < 60; m++)
                    cancelAlarm(h, m);
        }

        @JavascriptInterface
        public void stopAlarm() { AlarmService.stopAlarmFromApp(); }

        @JavascriptInterface
        public boolean isAlarmPlaying() { return AlarmService.isAlarmPlaying(); }
    }
}
