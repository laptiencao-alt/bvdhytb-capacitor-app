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

public class AlarmService extends Service implements TextToSpeech.OnInitListener {

    static final String CHANNEL_ID = "medreminder_alarm";
    static final int NOTIF_ID = 9999;
    static AlarmService instance;
    MediaPlayer mediaPlayer;
    Vibrator vibrator;
    PowerManager.WakeLock wakeLock;
    TextToSpeech tts;
    boolean ttsReady = false;
    String ttsPendingText = "";
    int ttsCount = 0;
    static final int TTS_MAX = 4;

    public static void stopAlarmFromApp() {
        if (instance != null) instance.stopAlarm();
    }
    public static boolean isAlarmPlaying() {
        return instance != null && instance.mediaPlayer != null;
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
                @Override public void onDone(String id) {
                    ttsCount++;
                    if (ttsCount < TTS_MAX && instance != null) {
                        try { Thread.sleep(2000); } catch (Exception e) {}
                        if (instance != null) speak(ttsPendingText);
                    }
                }
                @Override public void onError(String id) {}
            });
            ttsReady = true;
            if (!ttsPendingText.isEmpty()) { setMaxVolume(); speak(ttsPendingText); }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopAlarm(); return START_NOT_STICKY; }
        instance = this;
        String name = intent != null ? intent.getStringExtra("medName") : "Thuốc";
        String dose = intent != null ? intent.getStringExtra("medDose") : "";
        String note = intent != null ? intent.getStringExtra("medNote") : "";
        if (name == null) name = "Thuốc";
        if (dose == null) dose = "";
        if (note == null) note = "";

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "MedReminder::WakeLock");
            wakeLock.acquire(5 * 60 * 1000L);
        }

        setMaxVolume();

        String body = name + (dose.isEmpty() ? "" : " - " + dose) + (note.isEmpty() ? "" : "\n" + note) + "\n\nBV Đại học Y Thái Bình";
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tap.putExtra("fromAlarm", true);
        PendingIntent tapPi = PendingIntent.getActivity(this, 0, tap, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, AlarmService.class); stop.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("\uD83D\uDD14 Đến giờ dùng thuốc!")
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPi)
            .addAction(android.R.drawable.ic_media_pause, "Tắt chuông", stopPi)
            .setOngoing(true).setAutoCancel(false)
            .setFullScreenIntent(tapPi, true)
            .build();
        startForeground(NOTIF_ID, notif);

        startVibration();

        // TTS: "Đã đến giờ dùng thuốc. [Tên thuốc]. [Liều]."
        ttsPendingText = "Đã đến giờ dùng thuốc. " + name + ". " + (dose.isEmpty() ? "" : dose + ". ") + "Bệnh viện Đại học Y Thái Bình.";
        ttsCount = 0;
        if (ttsReady) speak(ttsPendingText);

        // Phát chuông sau 5 giây
        new android.os.Handler(getMainLooper()).postDelayed(this::startChuong, 5000);

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
        if (mediaPlayer != null) { try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception e) {} mediaPlayer = null; }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        instance = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { stopAlarm(); if (tts != null) { tts.shutdown(); tts = null; } super.onDestroy(); }
}
