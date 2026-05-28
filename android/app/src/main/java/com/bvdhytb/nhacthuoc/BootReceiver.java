package com.bvdhytb.nhacthuoc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Sau khi reboot hoặc cập nhật app, alarm bị xóa.
            // Gửi broadcast để WebView tự đặt lại alarm khi mở app.
            // Không cần làm gì ở đây vì alarm sẽ được đặt lại
            // khi user mở app (syncAndroidAlarms trong index.html).
        }
    }
}
