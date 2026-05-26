package dev.pages.bvdhytb_nhacthuoc.twa;

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

    private static final String CHANNEL_ID = "medreminder_alarm";
    private static final int NOTIFICATION_ID = 9999;
    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingTtsText = "";
    private int ttsRepeatCount = 0;
    private static final int TTS_MAX_REPEATS = 5; // Nói tối đa 5 lần

    // Static reference để MainActivity tắt alarm
    private static AlarmService instance;

    public static void stopAlarmFromApp() {
        if (instance != null) {
            instance.stopAlarm();
        }
    }

    public static boolean isAlarmPlaying() {
        return instance != null && instance.mediaPlayer != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Khởi tạo TTS engine
        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Đặt giọng tiếng Việt
            Locale vietnamese = new Locale("vi", "VN");
            int result = tts.setLanguage(vietnamese);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback sang tiếng Anh nếu không có tiếng Việt
                tts.setLanguage(Locale.US);
            }

            // Đặt tốc độ nói chậm rõ ràng (người cao tuổi dễ nghe)
            tts.setSpeechRate(0.85f);
            tts.setPitch(1.0f);

            // Listener để biết khi nói xong → nói lại
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {}

                @Override
                public void onDone(String utteranceId) {
                    ttsRepeatCount++;
                    if (ttsRepeatCount < TTS_MAX_REPEATS && instance != null) {
                        // Chờ 2 giây rồi nói lại
                        try { Thread.sleep(2000); } catch (InterruptedException e) {}
                        speakText(pendingTtsText);
                    }
                }

                @Override
                public void onError(String utteranceId) {}
            });

            ttsReady = true;

            // Nếu có text đang chờ → phát ngay
            if (!pendingTtsText.isEmpty()) {
                // Tăng âm lượng media lên max trước khi nói
                setMaxVolume();
                speakText(pendingTtsText);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Kiểm tra nếu là yêu cầu tắt
        if (intent != null && "STOP_ALARM".equals(intent.getAction())) {
            stopAlarm();
            return START_NOT_STICKY;
        }

        instance = this;

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
                "MedReminder::AlarmWakeLock");
            wakeLock.acquire(5 * 60 * 1000L);
        }

        // ===== TĂNG ÂM LƯỢNG LÊN MAX =====
        setMaxVolume();

        // ===== TẠO NOTIFICATION =====
        String body = medName;
        if (!medDose.isEmpty()) body += " - " + medDose;
        if (!medNote.isEmpty()) body += "\n" + medNote;
        body += "\n\nBV Đại học Y Thái Bình\nBấm để mở app xác nhận đã dùng thuốc";

        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tapIntent.putExtra("fromAlarm", true);
        PendingIntent tapPending = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, AlarmService.class);
        stopIntent.setAction("STOP_ALARM");
        PendingIntent stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("\uD83D\uDD14 Đến giờ dùng thuốc!")
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tapPending)
            .addAction(android.R.drawable.ic_media_pause, "Tắt chuông", stopPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(tapPending, true)
            .build();

        startForeground(NOTIFICATION_ID, notification);

        // ===== RUNG DỒN DẬP =====
        startVibration();

        // ===== PHÁT GIỌNG NÓI =====
        // Tạo câu nói: "Đã đến giờ dùng thuốc. [Tên thuốc]. [Liều]. [Ghi chú]"
        StringBuilder speech = new StringBuilder();
        speech.append("Đã đến giờ dùng thuốc. ");
        speech.append(medName).append(". ");
        if (!medDose.isEmpty()) speech.append(medDose).append(". ");
        if (!medNote.isEmpty()) speech.append(medNote).append(". ");
        speech.append("Bệnh viện Đại học Y Thái Bình.");

        pendingTtsText = speech.toString();
        ttsRepeatCount = 0;

        if (ttsReady) {
            speakText(pendingTtsText);
        }
        // Nếu TTS chưa sẵn sàng → onInit() sẽ phát khi ready

        // ===== PHÁT CHUÔNG BÁO THỨC SAU KHI NÓI =====
        // Chờ 5 giây cho TTS nói xong lần đầu, rồi phát chuông nền
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            startAlarmSound();
        }, 5000);

        // Tự tắt sau 3 phút
        new android.os.Handler(getMainLooper()).postDelayed(this::stopAlarm, 3 * 60 * 1000);

        return START_NOT_STICKY;
    }

    private void setMaxVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            // Tăng âm lượng ALARM lên max
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0);

            // Tăng âm lượng MUSIC lên max (cho TTS)
            int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
        }
    }

    private void speakText(String text) {
        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "medreminder_alarm");
        }
    }

    private void startAlarmSound() {
        if (mediaPlayer != null) return; // Đã phát rồi
        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, alarmUri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 500, 300, 500, 300, 500, 300, 800, 500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private void stopAlarm() {
        // Tắt TTS
        if (tts != null) {
            tts.stop();
        }
        // Tắt nhạc chuông
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception e) { }
            mediaPlayer = null;
        }
        // Tắt rung
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        // Giải phóng WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        instance = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopAlarm();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
