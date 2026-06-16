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

    private static final String TAG = "SpeechBridge";
    private final Activity activity;
    private final WebView webView;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    public SpeechBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        initTTS();
    }

    private void initTTS() {
        tts = new TextToSpeech(activity, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    Locale vnLocale = new Locale("vi", "VN");
                    int result = tts.setLanguage(vnLocale);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Vietnamese TTS not available, trying vi only");
                        tts.setLanguage(new Locale("vi"));
                    }
                    tts.setSpeechRate(0.85f);
                    tts.setPitch(1.1f);
                    ttsReady = true;

                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            callJS("if(typeof _onNativeTTSStart==='function') _onNativeTTSStart();");
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            callJS("if(typeof _onNativeTTSDone==='function') _onNativeTTSDone();");
                        }

                        @Override
                        public void onError(String utteranceId) {
                            callJS("if(typeof _onNativeTTSError==='function') _onNativeTTSError();");
                        }
                    });

                    Log.i(TAG, "TTS initialized successfully");
                } else {
                    Log.e(TAG, "TTS initialization failed with status: " + status);
                    ttsReady = false;
                }
            }
        });
    }

    @JavascriptInterface
    public void speak(String text) {
        if (tts != null && ttsReady) {
            tts.stop();
            Bundle params = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "medreminder_tts");
        } else {
            Log.w(TAG, "TTS not ready, cannot speak");
            callJS("if(typeof _onNativeTTSError==='function') _onNativeTTSError();");
        }
    }

    @JavascriptInterface
    public void stopSpeaking() {
        if (tts != null) {
            tts.stop();
        }
    }

    @JavascriptInterface
    public boolean isTTSAvailable() {
        return ttsReady;
    }

    @JavascriptInterface
    public void startListening() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        callJS("if(typeof _onSpeechReady==='function') _onSpeechReady();");
                    }

                    @Override
                    public void onBeginningOfSpeech() {}

                    @Override
                    public void onRmsChanged(float rmsdB) {}

                    @Override
                    public void onBufferReceived(byte[] buffer) {}

                    @Override
                    public void onEndOfSpeech() {}

                    @Override
                    public void onError(int error) {
                        callJS("if(typeof _onSpeechError==='function') _onSpeechError(" + error + ");");
                    }

                    @Override
                    public void onResults(Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String text = matches.get(0).replace("'", "\\'").replace("\\", "\\\\");
                            callJS("if(typeof _onSpeechResult==='function') _onSpeechResult('" + text + "');");
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {}

                    @Override
                    public void onEvent(int eventType, Bundle params) {}
                });

                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                speechRecognizer.startListening(intent);
            }
        });
    }

    @JavascriptInterface
    public void stopListening() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (speechRecognizer != null) {
                    speechRecognizer.stopListening();
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
            }
        });
    }

    private void callJS(final String js) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl("javascript:" + js);
            }
        });
    }

    public void destroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
