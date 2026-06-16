// fix-ai-tts-bridge.js — Chạy từ C:\pw4: node fix-ai-tts-bridge.js
// Update speakAIReply() để ưu tiên AndroidSpeech.speak() native bridge (cho APK Huawei)
// Fallback Web Speech API cho PWA web

const fs = require('fs');

const file = 'pwa/index.html';
let html = fs.readFileSync(file, 'utf8');
const original = html;

// ──────────────────────────────────────────────────────────
// Tìm và thay thế function speakAIReply hiện tại
// ──────────────────────────────────────────────────────────
const fnStartMarker = 'function speakAIReply(text) {';
const startIdx = html.indexOf(fnStartMarker);

if (startIdx === -1) {
  console.log('❌ Không tìm thấy function speakAIReply');
  console.log('   Bạn cần chạy fix-ai-tts-final.js trước để tạo function này');
  process.exit(1);
}

// Tìm closing bracket bằng depth counter
const bodyStart = html.indexOf('{', startIdx);
let depth = 0, endIdx = bodyStart;
for (let i = bodyStart; i < html.length; i++) {
  if (html[i] === '{') depth++;
  else if (html[i] === '}') {
    depth--;
    if (depth === 0) { endIdx = i; break; }
  }
}

const oldFn = html.slice(startIdx, endIdx + 1);
console.log('Tìm thấy speakAIReply, length:', oldFn.length, 'chars');

// ──────────────────────────────────────────────────────────
// Function mới — ưu tiên native bridge
// ──────────────────────────────────────────────────────────
const NEW_FN = `function speakAIReply(text) {
  if (!text) return;

  // ─── ƯU TIÊN: Native Android TTS qua SpeechBridge (cho APK) ───
  if (window.AndroidSpeech && typeof window.AndroidSpeech.speak === 'function') {
    try {
      console.log('[AI-TTS] using native AndroidSpeech.speak()');
      window.AndroidSpeech.speak(text);
      return;
    } catch(e) {
      console.warn('[AI-TTS] AndroidSpeech.speak failed:', e);
      // Fall through to Web Speech API
    }
  }

  // ─── FALLBACK: Web Speech API (cho PWA web) ───
  if (!('speechSynthesis' in window)) {
    console.warn('[AI-TTS] no speechSynthesis available');
    return;
  }
  try {
    window.speechSynthesis.cancel();

    // Prime audio context bằng tone ngắn (Android WebView cần)
    if (typeof audioCtx !== 'undefined' && audioCtx) {
      try {
        const now = audioCtx.currentTime;
        const osc = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        osc.type = 'sine';
        osc.frequency.value = 800;
        gain.gain.setValueAtTime(0.05, now);
        gain.gain.linearRampToValueAtTime(0, now + 0.1);
        osc.connect(gain);
        gain.connect(audioCtx.destination);
        osc.start(now);
        osc.stop(now + 0.12);
      } catch(e) {}
    }

    setTimeout(() => {
      try {
        const u = new SpeechSynthesisUtterance(text);
        if (typeof _vnVoiceCache !== 'undefined' && _vnVoiceCache) {
          u.voice = _vnVoiceCache;
        } else {
          const voices = window.speechSynthesis.getVoices();
          const vn = voices.find(v => v.lang && v.lang.startsWith('vi'));
          if (vn) {
            u.voice = vn;
            if (typeof _vnVoiceCache !== 'undefined') _vnVoiceCache = vn;
          }
        }
        u.lang = 'vi-VN';
        u.rate = 0.85;
        u.pitch = 1.1;
        u.volume = 1.0;
        u.onstart = () => console.log('[AI-TTS-Web] onstart');
        u.onend = () => console.log('[AI-TTS-Web] onend');
        u.onerror = (e) => console.warn('[AI-TTS-Web] onerror:', e.error);
        window.speechSynthesis.speak(u);
        console.log('[AI-TTS-Web] speak() called, length:', text.length);
      } catch(e) {
        console.error('[AI-TTS-Web] inner error:', e);
      }
    }, 250);
  } catch(e) {
    console.error('[AI-TTS-Web] outer error:', e);
  }
}`;

html = html.slice(0, startIdx) + NEW_FN + html.slice(endIdx + 1);

if (html === original) {
  console.log('⚠️  File không thay đổi');
  process.exit(1);
}

fs.writeFileSync(file + '.bak-pre-bridge', original, 'utf8');
fs.writeFileSync(file, html, 'utf8');

console.log('\n✅ Đã update speakAIReply()');
console.log('   - Ưu tiên: window.AndroidSpeech.speak() (native)');
console.log('   - Fallback: Web Speech API (PWA web)');
console.log('💾 Backup: pwa/index.html.bak-pre-bridge');
console.log('\n👉 Deploy PWA:');
console.log('   npx wrangler deploy --config wrangler-pwa.jsonc');
