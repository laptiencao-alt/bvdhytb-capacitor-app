package dev.pages.bvdhytb_nhacthuoc.twa;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String medName = intent.getStringExtra("medName");
        String medDose = intent.getStringExtra("medDose");
        String medNote = intent.getStringExtra("medNote");
        int hour = intent.getIntExtra("hour", 0);
        int minute = intent.getIntExtra("minute", 0);

        if (medName == null) medName = "Thuốc";
        if (medDose == null) medDose = "";
        if (medNote == null) medNote = "";

        // Khởi động Foreground Service để phát âm thanh báo thức + rung
        Intent serviceIntent = new Intent(context, AlarmService.class);
        serviceIntent.putExtra("medName", medName);
        serviceIntent.putExtra("medDose", medDose);
        serviceIntent.putExtra("medNote", medNote);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        // Đặt lại alarm cho ngày mai (vì setExact không lặp lại)
        rescheduleForTomorrow(context, intent, hour, minute);
    }

    private void rescheduleForTomorrow(Context context, Intent originalIntent, int hour, int minute) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtras(originalIntent); // copy extras

        int requestCode = hour * 100 + minute;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
        }
    }
}
