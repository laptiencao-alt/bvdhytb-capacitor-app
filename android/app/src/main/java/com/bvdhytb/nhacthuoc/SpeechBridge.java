package com.bvdhytb.nhacthuoc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public class SpeechBridge {

    private final Activity activity;
    private final WebView webView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    public SpeechBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(activity);
    }

    @JavascriptInterface
    public void startListening(String language) {
        activity.runOnUiThread(() -> {
            if (recognizer != null) {
                recognizer.destroy();
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { callJS("if(typeof _onSpeechReady==='function') _onSpeechReady();"); }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onError(int error) { sendError(String.valueOf(error)); }
                @Override public void onResults(Bundle results) { sendResults(results, false); }
                @Override public void onPartialResults(Bundle partial) { sendResults(partial, true); }
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            String lang = (language != null && !language.isEmpty()) ? language : "vi-VN";
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(intent);
        });
    }

    @JavascriptInterface
    public void stopListening() {
        activity.runOnUiThread(() -> {
            if (recognizer != null) {
                recognizer.stopListening();
                recognizer.destroy();
                recognizer = null;
            }
        });
    }

    void sendResults(Bundle results, boolean partial) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            String text = matches.get(0).replace("\\", "\\\\").replace("'", "\\'");
            if (partial) {
                callJS("if(typeof _onSpeechPartial==='function') _onSpeechPartial('" + text + "');");
            } else {
                callJS("if(typeof _onSpeechResult==='function') _onSpeechResult('" + text + "');");
            }
        }
    }

    void sendError(String error) {
        callJS("if(typeof _onSpeechError==='function') _onSpeechError(" + error + ");");
    }

    void callJS(String js) {
        activity.runOnUiThread(() -> webView.loadUrl("javascript:" + js));
    }

    public void destroy() {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
    }

    // ==================== TTS (new) ====================

    @JavascriptInterface
    public void speak(String text) {
        if (tts == null) {
            tts = new TextToSpeech(activity, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("vi", "VN"));
                    tts.setSpeechRate(0.85f);
                    tts.setPitch(1.1f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String uid) { callJS("if(typeof _onNativeTTSStart==='function') _onNativeTTSStart();"); }
                        @Override public void onDone(String uid) { callJS("if(typeof _onNativeTTSDone==='function') _onNativeTTSDone();"); }
                        @Override public void onError(String uid) { callJS("if(typeof _onNativeTTSError==='function') _onNativeTTSError();"); }
                    });
                    ttsReady = true;
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "mr_tts");
                }
            });
        } else if (ttsReady) {
            tts.stop();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "mr_tts");
        }
    }

    @JavascriptInterface
    public void stopSpeaking() { if (tts != null) tts.stop(); }

    @JavascriptInterface
    public boolean isTTSAvailable() { return ttsReady; }
}
