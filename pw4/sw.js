// ===== MedReminder TBMUH - Service Worker v4 =====
// v4: Thêm Web Push event handler (nhận push từ server qua FCM)
// - Cache-first: app chạy offline sau lần đầu
// - Push handler: nhận notification từ backend ngay cả khi app đóng hoàn toàn
// - Fallback: vẫn giữ setTimeout scheduler cho trường hợp offline

const CACHE_VERSION = 'medreminder-tbmuh-v4';

const CORE_ASSETS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  './apple-touch-icon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_VERSION).then((cache) => {
      return cache.addAll(CORE_ASSETS.map(url => new Request(url, { cache: 'reload' })));
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    Promise.all([
      caches.keys().then((keys) =>
        Promise.all(keys.map((key) => {
          if (key !== CACHE_VERSION) return caches.delete(key);
        }))
      ),
      self.clients.claim()
    ]).then(() => {
      scheduleNextReminder();
    })
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  if (!event.request.url.startsWith('http')) return;
  if (event.request.url.includes('/bvdhytb-admin-')) return;

  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) {
        fetch(event.request).then((res) => {
          if (res && res.ok) {
            caches.open(CACHE_VERSION).then((c) => c.put(event.request, res.clone()));
          }
        }).catch(() => {});
        return cached;
      }
      return fetch(event.request).then((res) => {
        if (res && res.ok && res.type === 'basic') {
          const copy = res.clone();
          caches.open(CACHE_VERSION).then((c) => c.put(event.request, copy));
        }
        return res;
      }).catch(() => {
        if (event.request.mode === 'navigate') {
          return caches.match('./index.html');
        }
      });
    })
  );
});

// ============================================================================
// WEB PUSH HANDLER — Đây là phần quan trọng nhất!
// Chrome nhận push từ FCM → đánh thức SW → chạy event này → hiện notification
// Hoạt động ngay cả khi app đóng hoàn toàn, màn hình tắt
// ============================================================================

self.addEventListener('push', (event) => {
  console.log('[SW] Push event received');

  let data;
  try {
    data = event.data ? event.data.json() : null;
  } catch (e) {
    console.warn('[SW] Failed to parse push data:', e);
    data = null;
  }

  // Nếu có payload từ server → dùng trực tiếp
  if (data && data.title) {
    const options = {
      body: data.body || '',
      icon: data.icon || './icon-192.png',
      badge: data.badge || './icon-192.png',
      tag: data.tag || 'medreminder-push',
      requireInteraction: data.requireInteraction !== false,
      vibrate: data.vibrate || [400, 200, 400, 200, 400, 200, 600],
      silent: false,
      renotify: data.renotify !== false,
      data: data.data || { url: './' }
      // Không có nút actions — bệnh nhân phải mở app để tích
    };

    event.waitUntil(
      self.registration.showNotification(data.title, options)
    );
    return;
  }

  // Nếu không có payload (empty push / tickle) → đọc từ IndexedDB
  event.waitUntil(
    getNextDueReminder().then(reminder => {
      if (reminder) {
        return self.registration.showNotification('🔔 Đến giờ dùng thuốc!', {
          body: `${reminder.medName}${reminder.medDose ? ' - ' + reminder.medDose : ''}${reminder.medNote ? '\n' + reminder.medNote : ''}\n\nBV Đại học Y Thái Bình`,
          icon: './icon-192.png',
          badge: './icon-192.png',
          tag: 'medreminder-' + (reminder.id || 'push'),
          requireInteraction: true,
          vibrate: [400, 200, 400, 200, 400, 200, 600],
          silent: false,
          renotify: true,
          data: { url: './', reminderId: reminder.id }
        });
      }
      // Fallback nếu không tìm được reminder cụ thể
      return self.registration.showNotification('🔔 Nhắc dùng thuốc - BVĐHYTB', {
        body: 'Vui lòng mở app để xem lịch uống thuốc\n\nBV Đại học Y Thái Bình',
        icon: './icon-192.png',
        badge: './icon-192.png',
        tag: 'medreminder-generic',
        requireInteraction: true,
        vibrate: [400, 200, 400, 200, 600],
        data: { url: './' }
      });
    })
  );
});

// ============ NOTIFICATION CLICK HANDLER ============

self.addEventListener('notificationclick', (event) => {
  const notificationData = event.notification.data || {};
  event.notification.close();

  // Bấm vào thông báo → mở app để bệnh nhân tích xác nhận đã uống
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
      // Nếu app đang mở → focus
      for (const client of clients) {
        if (client.url.includes(self.location.origin) && 'focus' in client) {
          return client.focus();
        }
      }
      // Nếu app chưa mở → mở mới
      if (self.clients.openWindow) {
        return self.clients.openWindow(notificationData.url || './');
      }
    })
  );
});

// ============ PUSH SUBSCRIPTION CHANGE ============
// Khi subscription bị thay đổi (ít gặp nhưng cần xử lý)

self.addEventListener('pushsubscriptionchange', (event) => {
  console.log('[SW] Push subscription changed');
  event.waitUntil(
    self.clients.matchAll({ type: 'window' }).then(clients => {
      for (const client of clients) {
        client.postMessage({ type: 'PUSH_SUBSCRIPTION_CHANGED' });
      }
    })
  );
});

// ============ INDEXEDDB: MEDICATION STORAGE FOR SW ============

const DB_NAME = 'medreminder-sw';
const DB_VERSION = 2; // Bump version for new stores
const REMINDERS_STORE = 'reminders';
const MEDICATIONS_STORE = 'medications';

function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (e) => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains(REMINDERS_STORE)) {
        db.createObjectStore(REMINDERS_STORE, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(MEDICATIONS_STORE)) {
        db.createObjectStore(MEDICATIONS_STORE, { keyPath: 'id' });
      }
    };
    req.onsuccess = (e) => resolve(e.target.result);
    req.onerror = (e) => reject(e.target.error);
  });
}

async function saveReminders(reminders) {
  try {
    const db = await openDB();
    const tx = db.transaction(REMINDERS_STORE, 'readwrite');
    const store = tx.objectStore(REMINDERS_STORE);
    store.clear();
    reminders.forEach(r => store.put(r));
    return new Promise(resolve => {
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); resolve(); };
    });
  } catch(e) { console.warn('saveReminders fail:', e); }
}

async function getAllReminders() {
  try {
    const db = await openDB();
    return new Promise(resolve => {
      const tx = db.transaction(REMINDERS_STORE, 'readonly');
      const store = tx.objectStore(REMINDERS_STORE);
      const req = store.getAll();
      req.onsuccess = () => { db.close(); resolve(req.result || []); };
      req.onerror = () => { db.close(); resolve([]); };
    });
  } catch(e) { return []; }
}

async function removeReminder(id) {
  try {
    const db = await openDB();
    const tx = db.transaction(REMINDERS_STORE, 'readwrite');
    tx.objectStore(REMINDERS_STORE).delete(id);
    return new Promise(resolve => {
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); resolve(); };
    });
  } catch(e) {}
}

// Lấy medication gần nhất cần nhắc (cho trường hợp empty push)
async function getNextDueReminder() {
  const reminders = await getAllReminders();
  const now = Date.now();
  // Tìm reminder trong khoảng ±5 phút
  const windowMs = 5 * 60 * 1000;
  const due = reminders
    .filter(r => Math.abs(r.time - now) < windowMs)
    .sort((a, b) => Math.abs(a.time - now) - Math.abs(b.time - now));
  return due[0] || null;
}

// ============ FALLBACK: setTimeout SCHEDULER ============
// Giữ lại như backup cho trường hợp offline (không có internet → server ko gửi push được)
// Khi có internet, server push là nguồn chính, setTimeout chỉ là lớp dự phòng

let _swSettings = { notification: true };
let _nextTimerId = null;

self.addEventListener('message', (event) => {
  const data = event.data;
  if (!data) return;

  if (data === 'SKIP_WAITING') {
    self.skipWaiting();
    return;
  }

  if (data.type === 'SCHEDULE_REMINDERS') {
    if (data.settings) _swSettings = data.settings;
    saveReminders(data.reminders || []).then(() => {
      scheduleNextReminder();
    });
  }

  // Nhận danh sách thuốc đầy đủ từ app (để SW luôn có data mới nhất)
  if (data.type === 'SYNC_MEDICATIONS') {
    saveMedications(data.medications || []);
  }
});

async function saveMedications(medications) {
  try {
    const db = await openDB();
    const tx = db.transaction(MEDICATIONS_STORE, 'readwrite');
    const store = tx.objectStore(MEDICATIONS_STORE);
    store.clear();
    medications.forEach(m => store.put(m));
    return new Promise(resolve => {
      tx.oncomplete = () => { db.close(); resolve(); };
      tx.onerror = () => { db.close(); resolve(); };
    });
  } catch(e) { console.warn('saveMedications fail:', e); }
}

async function scheduleNextReminder() {
  if (_nextTimerId) {
    clearTimeout(_nextTimerId);
    _nextTimerId = null;
  }

  const reminders = await getAllReminders();
  const now = Date.now();
  const future = reminders.filter(r => r.time > now).sort((a,b) => a.time - b.time);
  if (future.length === 0) return;

  const next = future[0];
  const delay = next.time - now;
  const cappedDelay = Math.min(delay, 12 * 60 * 60 * 1000);

  _nextTimerId = setTimeout(() => {
    showFallbackNotification(next).then(() => {
      removeReminder(next.id).then(() => scheduleNextReminder());
    });
  }, cappedDelay);
}

async function showFallbackNotification(reminder) {
  if (!_swSettings.notification) return;
  if (!self.registration || !self.registration.showNotification) return;

  try {
    await self.registration.showNotification('🔔 Đến giờ dùng thuốc!', {
      body: `${reminder.medName}${reminder.medDose ? ' - ' + reminder.medDose : ''}${reminder.medNote ? '\n' + reminder.medNote : ''}\n\nBV Đại học Y Thái Bình`,
      icon: './icon-192.png',
      badge: './icon-192.png',
      tag: 'medreminder-' + reminder.id,
      requireInteraction: true,
      vibrate: [400, 200, 400, 200, 400, 200, 600],
      silent: false,
      renotify: true,
      data: { url: './', reminderId: reminder.id }
    });
  } catch(e) {
    console.warn('SW fallback notification error:', e);
  }
}
