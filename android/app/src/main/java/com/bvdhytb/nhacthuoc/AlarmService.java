package com.bvdhytb.nhacthuoc;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import androidx.core.app.NotificationCompat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class AlarmService extends Service implements TextToSpeech.OnInitListener {

    static final String CHANNEL_ID = "medreminder_alarm";
    static final int NOTIF_ID = 9999;
    static AlarmService instance;
    MediaPlayer mediaPlayer;
    Vibrator vibrator;
    PowerManager.WakeLock wakeLock;
    TextToSpeech tts;
    boolean ttsReady = false;
    List<String> ttsMedList = new ArrayList<>(); // Danh sách thuốc cần đọc
    int ttsCurrentIndex = 0;
    int ttsRepeatCount = 0;
    static final int TTS_MAX_REPEAT = 3; // Lặp lại 3 vòng

    public static void stopAlarmFromApp() {
        if (instance != null) instance.stopAlarm();
    }
    public static boolean isAlarmPlaying() {
        return instance != null && (instance.mediaPlayer != null || instance.ttsReady);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int r = tts.setLanguage(new Locale("vi", "VN"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                tts.setLanguage(Locale.US);
            tts.setSpeechRate(0.85f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override
                public void onDone(String id) {
                    if (instance == null) return;
                    // Chuyển sang thuốc tiếp theo
                    ttsCurrentIndex++;
                    if (ttsCurrentIndex < ttsMedList.size()) {
                        // Còn thuốc trong danh sách → đọc tiếp
                        try { Thread.sleep(1000); } catch (Exception e) {}
                        speak(ttsMedList.get(ttsCurrentIndex));
                    } else {
                        // Đọc xong 1 vòng → lặp lại nếu chưa đủ số lần
                        ttsRepeatCount++;
                        if (ttsRepeatCount < TTS_MAX_REPEAT && instance != null) {
                            ttsCurrentIndex = 0;
                            try { Thread.sleep(2000); } catch (Exception e) {}
                            if (instance != null) speak(ttsMedList.get(0));
                        }
                    }
                }
                @Override public void onError(String id) {}
            });
            ttsReady = true;
            if (!ttsMedList.isEmpty()) {
                setMaxVolume();
                speak(ttsMedList.get(0));
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopAlarm();
            return START_NOT_STICKY;
        }
        // Action OPEN_APP: mở app rồi tắt chuông khi bấm nút trên notification
        if (intent != null && "OPEN_APP".equals(intent.getAction())) {
            // Mở MainActivity
            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            openIntent.putExtra("fromAlarm", true);
            startActivity(openIntent);
            // KHÔNG tắt chuông - chỉ tắt khi bấm "Đã dùng" trong app
            return START_NOT_STICKY;
        }

        instance = this;

        // Lấy danh sách thuốc (nhiều thuốc cùng giờ được gửi dưới dạng JSON array)
        String medsJson = intent != null ? intent.getStringExtra("medsJson") : null;
        String medName = intent != null ? intent.getStringExtra("medName") : "Thuốc";
        String medDose = intent != null ? intent.getStringExtra("medDose") : "";
        String medNote = intent != null ? intent.getStringExtra("medNote") : "";

        if (medName == null) medName = "Thuốc";
        if (medDose == null) medDose = "";
        if (medNote == null) medNote = "";

        // Giữ CPU thức
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MedReminder::WakeLock");
            wakeLock.acquire(5 * 60 * 1000L);
        }
        setMaxVolume();

        // Tạo nội dung notification
        String body = medName;
        if (!medDose.isEmpty()) body += " - " + medDose;
        if (!medNote.isEmpty()) body += "\n" + medNote;
        body += "\n\nBV Đại học Y Thái Bình\nMở app để xác nhận đã dùng thuốc";

        // Nút "Mở app" → bắt buộc vào app bấm "Đã dùng" mới tắt chuông
        Intent openAppIntent = new Intent(this, AlarmService.class);
        openAppIntent.setAction("OPEN_APP");
        PendingIntent openAppPi = PendingIntent.getService(
            this, 2, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Bấm vào notification cũng mở app
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tapIntent.putExtra("fromAlarm", true);
        PendingIntent tapPi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("\uD83D\uDD14 Đến giờ dùng thuốc!")
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPi)
            // Nút chỉ mở app, KHÔNG tắt chuông
            .addAction(android.R.drawable.ic_menu_view, "Mở app xác nhận", openAppPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(tapPi, true)
            .build();
        startForeground(NOTIF_ID, notif);

        startVibration();

        // Xây dựng danh sách TTS cho từng thuốc
        ttsMedList.clear();
        ttsCurrentIndex = 0;
        ttsRepeatCount = 0;

        // Câu mở đầu
        ttsMedList.add("Đã đến giờ dùng thuốc.");

        // Đọc từng thuốc (medName có thể chứa nhiều thuốc ngăn cách bởi |)
        if (medName.contains("|")) {
            String[] names = medName.split("\\|");
            String[] doses = medDose.split("\\|");
            for (int i = 0; i < names.length; i++) {
                String n = names[i].trim();
                String d = (i < doses.length) ? doses[i].trim() : "";
                String speech = n + (d.isEmpty() ? "" : ". " + d);
                ttsMedList.add(speech);
            }
        } else {
            String speech = medName + (medDose.isEmpty() ? "" : ". " + medDose);
            ttsMedList.add(speech);
        }

        ttsMedList.add("Bệnh viện Đại học Y Thái Bình.");

        if (ttsReady) {
            speak(ttsMedList.get(0));
        }

        // Phát chuông sau 3 giây
        new android.os.Handler(getMainLooper()).postDelayed(this::startChuong, 3000);

        // Tự tắt sau 3 phút
        new android.os.Handler(getMainLooper()).postDelayed(this::stopAlarm, 3 * 60 * 1000);

        return START_NOT_STICKY;
    }

    void setMaxVolume() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
        }
    }

    void speak(String text) {
        if (tts != null && ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alarm");
    }

    void startChuong() {
        if (mediaPlayer != null) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    void startVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] p = {0,500,300,500,300,500,300,800,500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(p, 0));
            else
                vibrator.vibrate(p, 0);
        }
    }

    void stopAlarm() {
        if (tts != null) tts.stop();
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception e) {}
            mediaPlayer = null;
        }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        instance = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        stopAlarm();
        if (tts != null) { tts.shutdown(); tts = null; }
        super.onDestroy();
    }
}
