# BVĐHYTB - Ứng dụng nhắc dùng thuốc

## Kiến trúc
- **Vỏ (APK):** Android app với WebView + AlarmManager + Foreground Service
- **Hồn (Web):** Load từ `https://bvdhytb-nhacthuoc.pages.dev`
- **Push:** Cloudflare Workers cron + Web Push
- **Báo thức:** Android AlarmManager + Foreground Service (rung + kêu liên tục)

## Cách web app gọi báo thức Android
Trong file `index.html`, khi bệnh nhân thêm thuốc:
```javascript
// Kiểm tra đang chạy trong APK
if (window.AndroidAlarm && window.AndroidAlarm.isAvailable()) {
    // Đặt báo thức cho 7:00 sáng
    window.AndroidAlarm.scheduleAlarm(7, 0, "Amlordipin 5mg", "1 viên", "Uống sau ăn");
}
```

## Setup GitHub Secrets (làm 1 lần)
1. Chuyển keystore sang base64:
   ```
   certutil -encode android.keystore android_keystore_base64.txt
   ```
2. Vào GitHub repo → Settings → Secrets and variables → Actions → New repository secret:
   - `SIGNING_KEY`: nội dung file base64 (bỏ dòng BEGIN/END)
   - `KEY_ALIAS`: `android`
   - `KEY_STORE_PASSWORD`: mật khẩu keystore
   - `KEY_PASSWORD`: mật khẩu key

## Build APK
Push code lên GitHub → Actions tự build → tải APK từ Releases.
