// ================================================================
// MedReminder TBMUH — Push Notification Manager
// Handles Web Push subscription + backend sync
// ================================================================

(function() {
  'use strict';

  // ======================== CẤU HÌNH ========================
  // ⚠️ THAY ĐỔI SAU KHI DEPLOY BACKEND
  const PUSH_CONFIG = {
    // URL của Cloudflare Worker backend (thay bằng URL thật sau khi deploy)
    BACKEND_URL: 'https://medreminder-push.bvdhytb-nhacthuoc.workers.dev',

    // VAPID Public Key (đã tạo — giữ nguyên)
    VAPID_PUBLIC_KEY: 'BA4dBQWUobTKyr4tSCRJzv2uqWXM_iyOpe394Ciw0UrZZxtfuWEzPWs0kAAZd9dxKzi1zW78a0s3dWlnztMNZaM',

    // Đồng bộ lại lịch mỗi 6 giờ (phòng trường hợp mất data)
    RESYNC_INTERVAL_MS: 6 * 60 * 60 * 1000,
  };

  // ======================== HELPERS ========================

  // Convert VAPID public key từ base64url sang Uint8Array (cho pushManager.subscribe)
  function urlBase64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
    const rawData = atob(base64);
    const outputArray = new Uint8Array(rawData.length);
    for (let i = 0; i < rawData.length; i++) {
      outputArray[i] = rawData.charCodeAt(i);
    }
    return outputArray;
  }

  // Gọi API backend
  async function apiCall(endpoint, data) {
    try {
      const response = await fetch(`${PUSH_CONFIG.BACKEND_URL}${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      });
      return await response.json();
    } catch (err) {
      console.warn(`[PushManager] API call to ${endpoint} failed:`, err.message);
      return null;
    }
  }

  // ======================== PUSH SUBSCRIPTION ========================

  // Đăng ký push notification
  async function subscribeToPush() {
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
      console.warn('[PushManager] Push not supported');
      return null;
    }

    // Kiểm tra permission
    if (Notification.permission === 'denied') {
      console.warn('[PushManager] Notification permission denied');
      return null;
    }

    if (Notification.permission === 'default') {
      const result = await Notification.requestPermission();
      if (result !== 'granted') {
        console.warn('[PushManager] Notification permission not granted');
        return null;
      }
    }

    try {
      const registration = await navigator.serviceWorker.ready;

      // Kiểm tra subscription hiện tại
      let subscription = await registration.pushManager.getSubscription();

      if (!subscription) {
        // Tạo subscription mới
        subscription = await registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: urlBase64ToUint8Array(PUSH_CONFIG.VAPID_PUBLIC_KEY),
        });
        console.log('[PushManager] New push subscription created');
      } else {
        console.log('[PushManager] Existing push subscription found');
      }

      return subscription;
    } catch (err) {
      console.error('[PushManager] Subscribe failed:', err);
      return null;
    }
  }

  // ======================== SYNC MEDICATIONS TO BACKEND ========================

  // Gửi subscription + lịch thuốc lên backend
  async function syncToBackend() {
    // Kiểm tra BACKEND_URL đã được cấu hình chưa
    if (!PUSH_CONFIG.BACKEND_URL || PUSH_CONFIG.BACKEND_URL === 'YOUR_BACKEND_URL') {
      console.warn('[PushManager] Backend URL chưa được cấu hình! Bỏ qua sync.');
      return;
    }

    const subscription = await subscribeToPush();
    if (!subscription) return;

    // Lấy danh sách thuốc hiện tại (biến global từ index.html)
    // *** FIX: Chờ medications load xong (tối đa 3 giây) ***
    let meds = typeof medications !== 'undefined' ? medications : [];
    if (meds.length === 0) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      meds = typeof medications !== 'undefined' ? medications : [];
    }

    // *** FIX: Luôn gửi lên server kể cả khi meds rỗng để XÓA lịch cũ ***
    // Không return sớm — nếu bệnh nhân xóa hết thuốc, server phải biết để ngừng push

    // Gửi lên backend
    const result = await apiCall('/api/subscribe', {
      subscription: subscription.toJSON(),
      medications: meds.map(m => ({
        name: m.name,
        dose: m.dose || '',
        note: m.note || '',
        times: m.times || [],
      })),
    });

    if (result && result.ok) {
      console.log('[PushManager] ✅ Đồng bộ thành công, patientId:', result.patientId);
      localStorage.setItem('medreminder_push_synced', Date.now().toString());
      localStorage.setItem('medreminder_push_patient_id', result.patientId);
    } else {
      console.warn('[PushManager] ⚠️ Đồng bộ thất bại:', result);
    }

    // Đồng bộ medications xuống SW IndexedDB (để SW có data backup)
    try {
      const reg = await navigator.serviceWorker.ready;
      if (reg.active) {
        reg.active.postMessage({
          type: 'SYNC_MEDICATIONS',
          medications: meds,
        });
      }
    } catch (e) { /* ignore */ }
  }

  // Gửi push thử để kiểm tra
  async function testPush() {
    if (!PUSH_CONFIG.BACKEND_URL || PUSH_CONFIG.BACKEND_URL === 'YOUR_BACKEND_URL') {
      if (typeof showToast === 'function') {
        showToast('Chưa cấu hình backend URL!', 'danger');
      }
      return false;
    }

    const subscription = await subscribeToPush();
    if (!subscription) {
      if (typeof showToast === 'function') {
        showToast('Không thể đăng ký push notification', 'danger');
      }
      return false;
    }

    const result = await apiCall('/api/test-push', {
      subscription: subscription.toJSON(),
    });

    if (result && result.success) {
      console.log('[PushManager] ✅ Test push sent successfully');
      return true;
    } else {
      console.warn('[PushManager] ❌ Test push failed:', result);
      if (typeof showToast === 'function') {
        showToast('Test push thất bại. Kiểm tra backend.', 'danger');
      }
      return false;
    }
  }

  // ======================== AUTO-SYNC LOGIC ========================

  // Kiểm tra có cần sync lại không
  function shouldResync() {
    const lastSync = parseInt(localStorage.getItem('medreminder_push_synced') || '0');
    return (Date.now() - lastSync) > PUSH_CONFIG.RESYNC_INTERVAL_MS;
  }

  // Khởi động Push Manager
  async function initPushManager() {
    // Chờ SW ready
    if (!('serviceWorker' in navigator)) return;

    try {
      await navigator.serviceWorker.ready;
    } catch { return; }

    // Đăng ký push + sync ngay khi app mở
    await syncToBackend();

    // Lắng nghe message từ SW
    navigator.serviceWorker.addEventListener('message', (event) => {
      if (event.data?.type === 'PUSH_SUBSCRIPTION_CHANGED') {
        console.log('[PushManager] Subscription changed, re-syncing...');
        syncToBackend();
      }
      if (event.data?.type === 'DOSE_TAKEN_FROM_NOTIFICATION') {
        // User bấm "Đã uống" từ notification → log dose
        const time = event.data.time;
        if (typeof medications !== 'undefined' && typeof doseStatus !== 'undefined') {
          for (const med of medications) {
            if (med.times.includes(time)) {
              const key = `${med.id}-${time}`;
              if (doseStatus[key] !== 'taken') {
                doseStatus[key] = 'taken';
                if (typeof logDoseAction === 'function') logDoseAction(key, 'taken');
                if (typeof renderHomeDoses === 'function') renderHomeDoses();
              }
            }
          }
        }
      }
    });

    // Re-sync mỗi khi app focus lại (nếu quá thời gian)
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && shouldResync()) {
        syncToBackend();
      }
    });

    // Re-sync định kỳ (phòng app mở lâu)
    setInterval(() => {
      if (shouldResync()) syncToBackend();
    }, 30 * 60 * 1000); // Kiểm tra mỗi 30 phút
  }

  // ======================== EXPORTS (global) ========================

  // Expose cho index.html sử dụng
  window.MedPush = {
    init: initPushManager,
    sync: syncToBackend,
    testPush: testPush,
    getConfig: () => PUSH_CONFIG,
  };

  // Auto-init sau khi DOM ready + 3 giây (cho app load xong)
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
      setTimeout(initPushManager, 3000);
    });
  } else {
    setTimeout(initPushManager, 3000);
  }

})();
