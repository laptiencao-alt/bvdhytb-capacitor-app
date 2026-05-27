package com.bvdhytb.nhacthuoc;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Calendar;

public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String name = intent.getStringExtra("medName");
        String dose = intent.getStringExtra("medDose");
        String note = intent.getStringExtra("medNote");
        int hour = intent.getIntExtra("hour", 0);
        int minute = intent.getIntExtra("minute", 0);
        if (name == null) name = "Thuốc";
        if (dose == null) dose = "";
        if (note == null) note = "";

        Intent si = new Intent(ctx, AlarmService.class);
        si.putExtra("medName", name);
        si.putExtra("medDose", dose);
        si.putExtra("medNote", note);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(si);
        else
            ctx.startService(si);

        // Đặt lại alarm cho ngày mai (chỉ thuốc hàng ngày, không lặp lịch hẹn/snooze)
        if (hour >= 0 && minute >= 0) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent ri = new Intent(ctx, AlarmReceiver.class);
        ri.putExtras(intent);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, hour*100+minute, ri,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0); // Fix lệch giờ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        else
            am.setExact(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } // end if hour >= 0
    }
}
