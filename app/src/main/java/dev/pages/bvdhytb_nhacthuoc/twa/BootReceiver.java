package dev.pages.bvdhytb_nhacthuoc.twa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Sau khi điện thoại khởi động lại, tất cả alarm bị xóa.
 * BootReceiver sẽ mở app để web app tự đặt lại alarm qua AndroidAlarm bridge.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Mở app im lặng để web app load và đặt lại alarm
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
        }
    }
}
