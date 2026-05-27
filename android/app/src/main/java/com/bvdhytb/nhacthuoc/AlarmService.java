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
    List<String> ttsList = new ArrayList<>();
    int ttsIndex = 0;
    int ttsRound = 0;
    static final int MAX_ROUNDS = 3;
    boolean alarmStopped = false;
    android.os.Handler mainHandler;

    public static void stopAlarmFromApp() {
        if (instance != null) instance.stopAlarm();
    }
    public static boolean isAlarmPlaying() {
        return instance != null && !instance.alarmStopped;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new android.os.Handler(getMainLooper());
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
                    if (alarmStopped) return;
                    ttsIndex++;
                    if (ttsIndex < ttsList.size()) {
                        mainHandler.postDelayed(() -> {
                            if (!alarmStopped) speak(ttsList.get(ttsIndex));
                        }, 800);
                    } else {
                        ttsRound++;
                        if (ttsRound < MAX_ROUNDS && !alarmStopped) {
                            ttsIndex = 0;
                            mainHandler.postDelayed(() -> {
                                if (!alarmStopped) speak(ttsList.get(0));
                            }, 2000);
                        }
                    }
                }
                @Override public void onError(String id) {}
            });
            ttsReady = true;
            if (!ttsList.isEmpty() && !alarmStopped) {
                setMaxVolume();
                speak(ttsList.get(0));
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopAlarm();
            return START_NOT_STICKY;
        }

        // Nếu alarm đang chạy rồi → bỏ qua, không tạo thêm
        if (instance != null && !instance.alarmStopped) {
            return START_NOT_STICKY;
        }

        instance = this;
        alarmStopped = false;

        String medName = intent != null ? intent.getStringExtra("medName") : "Thuốc";
        String medDose = intent != null ? intent.getStringExtra("medDose") : "";
        String medNote = intent != null ? intent.getStringExtra("medNote") : "";
        if (medName == null) medName = "Thuốc";
        if (medDose == null) medDose = "";
        if (medNote == null) medNote = "";

        // WakeLock
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MedReminder::WakeLock");
            wakeLock.acquire(5 * 60 * 1000L);
        }
        setMaxVolume();

        // Tạo notification body
        StringBuilder bodyBuilder = new StringBuilder();
        if (medName.contains("|")) {
            String[] names = medName.split("\\|");
            String[] doses = medDose.split("\\|");
            for (int i = 0; i < names.length; i++) {
                bodyBuilder.append("• ").append(names[i].trim());
                if (i < doses.length && !doses[i].trim().isEmpty())
                    bodyBuilder.append(" - ").append(doses[i].trim());
                bodyBuilder.append("\n");
            }
        } else {
            bodyBuilder.append(medName);
            if (!medDose.isEmpty()) bodyBuilder.append(" - ").append(medDose);
        }
        bodyBuilder.append("\nBV Đại học Y Thái Bình");
        String body = bodyBuilder.toString();

        // Notification: bấm vào → mở app
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tapIntent.putExtra("fromAlarm", true);
        PendingIntent tapPi = PendingIntent.getActivity(this, 0, tapIntent,
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
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(tapPi, true)
            .build();
        startForeground(NOTIF_ID, notif);

        // Rung
        startVibration();

        // Xây TTS danh sách
        ttsList.clear();
        ttsIndex = 0;
        ttsRound = 0;
        ttsList.add("Đã đến giờ dùng thuốc.");
        if (medName.contains("|")) {
            String[] names = medName.split("\\|");
            String[] doses = medDose.split("\\|");
            for (int i = 0; i < names.length; i++) {
                String s = names[i].trim();
                if (i < doses.length && !doses[i].trim().isEmpty())
                    s += ". " + doses[i].trim();
                ttsList.add(s);
            }
        } else {
            String s = medName;
            if (!medDose.isEmpty()) s += ". " + medDose;
            ttsList.add(s);
        }
        ttsList.add("Bệnh viện Đại học Y Thái Bình.");

        if (ttsReady && !alarmStopped) speak(ttsList.get(0));

        // Chuông sau 4 giây
        mainHandler.postDelayed(() -> { if (!alarmStopped) startChuong(); }, 4000);

        // Tự tắt sau 3 phút
        mainHandler.postDelayed(this::stopAlarm, 3 * 60 * 1000);

        return START_NOT_STICKY;
    }

    void setMaxVolume() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            // Đặt âm lượng 80% thay vì max
            int alarmMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, (int)(alarmMax * 0.8), 0);
            int musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (int)(musicMax * 0.8), 0);
        }
    }

    void speak(String text) {
        if (tts != null && ttsReady && !alarmStopped)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alarm_" + ttsIndex);
    }

    void startChuong() {
        if (mediaPlayer != null || alarmStopped) return;
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
            long[] p = {0, 500, 300, 500, 300, 500, 300, 800, 500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(p, 0));
            else
                vibrator.vibrate(p, 0);
        }
    }

    void stopAlarm() {
        alarmStopped = true;
        if (tts != null) tts.stop();
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception e) {}
            mediaPlayer = null;
        }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        mainHandler.removeCallbacksAndMessages(null);
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
