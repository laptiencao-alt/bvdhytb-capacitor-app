package com.bvdhytb.nhacthuoc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class SpeechBridge {
    private final Activity activity;
    private final WebView webView;
    private SpeechRecognizer recognizer;

    public SpeechBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(activity);
    }

    @JavascriptInterface
    public void startListening(String lang) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            sendError("Thi\u1ebft b\u1ecb kh\u00f4ng h\u1ed7 tr\u1ee3 nh\u1eadn d\u1ea1ng gi\u1ecdng n\u00f3i");
            return;
        }

        // Check permission
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.RECORD_AUDIO}, 100);
            sendError("C\u1ea7n c\u1ea5p quy\u1ec1n micro");
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                if (recognizer != null) {
                    recognizer.destroy();
                }
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        callJS("window._onNativeSpeechState('ready')");
                    }

                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onEvent(int eventType, Bundle params) {}

                    @Override
                    public void onError(int error) {
                        String msg;
                        switch (error) {
                            case SpeechRecognizer.ERROR_AUDIO: msg = "L\u1ed7i ghi \u00e2m"; break;
                            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "Ch\u01b0a c\u1ea5p quy\u1ec1n micro"; break;
                            case SpeechRecognizer.ERROR_NETWORK: msg = "L\u1ed7i m\u1ea1ng"; break;
                            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "H\u1ebft th\u1eddi gian k\u1ebft n\u1ed1i"; break;
                            case SpeechRecognizer.ERROR_NO_MATCH: msg = "Kh\u00f4ng nh\u1eadn d\u1ea1ng \u0111\u01b0\u1ee3c"; break;
                            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg = "Kh\u00f4ng nghe th\u1ea5y gi\u1ecdng n\u00f3i"; break;
                            default: msg = "L\u1ed7i: " + error;
                        }
                        sendError(msg);
                    }

                    @Override
                    public void onResults(Bundle results) {
                        sendResults(results, true);
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        sendResults(partialResults, false);
                    }
                });

                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

                recognizer.startListening(intent);
            } catch (Exception e) {
                sendError("Kh\u00f4ng kh\u1edfi \u0111\u1ed9ng \u0111\u01b0\u1ee3c: " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void stopListening() {
        activity.runOnUiThread(() -> {
            if (recognizer != null) {
                try { recognizer.stopListening(); } catch (Exception e) {}
            }
        });
    }

    public void destroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private void sendResults(Bundle bundle, boolean isFinal) {
        ArrayList<String> matches =
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            // Escape quotes in transcript
            String transcript = matches.get(0).replace("\\", "\\\\").replace("'", "\\'");
            callJS("window._onNativeSpeechResult('" + transcript + "', " + isFinal + ")");
        }
    }

    private void sendError(String msg) {
        String escaped = msg.replace("\\", "\\\\").replace("'", "\\'");
        callJS("window._onNativeSpeechError('" + escaped + "')");
    }

    private void callJS(String js) {
        activity.runOnUiThread(() -> {
            webView.loadUrl("javascript:" + js);
        });
    }
}
