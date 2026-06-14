package com.bvdhytb.nhacthuoc;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import java.util.ArrayList;

@CapacitorPlugin(
    name = "NativeSpeech",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone")
    }
)
public class SpeechPlugin extends Plugin {
    private SpeechRecognizer recognizer;
    private boolean isListening = false;

    @PluginMethod()
    public void start(PluginCall call) {
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
            call.reject("Speech recognition not available on this device");
            return;
        }

        String language = call.getString("language", "vi-VN");

        getActivity().runOnUiThread(() -> {
            try {
                if (recognizer != null) {
                    recognizer.destroy();
                }
                recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        JSObject ret = new JSObject();
                        ret.put("status", "ready");
                        notifyListeners("speechState", ret);
                    }

                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}

                    @Override
                    public void onEndOfSpeech() {
                        isListening = false;
                    }

                    @Override
                    public void onError(int error) {
                        isListening = false;
                        JSObject ret = new JSObject();
                        ret.put("error", error);
                        ret.put("message", getErrorMessage(error));
                        notifyListeners("speechError", ret);
                    }

                    @Override
                    public void onResults(Bundle results) {
                        isListening = false;
                        sendResults(results, true);
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        sendResults(partialResults, false);
                    }

                    @Override public void onEvent(int eventType, Bundle params) {}
                });

                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

                recognizer.startListening(intent);
                isListening = true;
                call.resolve();
            } catch (Exception e) {
                call.reject("Failed to start: " + e.getMessage());
            }
        });
    }

    @PluginMethod()
    public void stop(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (recognizer != null) {
                try { recognizer.stopListening(); } catch (Exception e) {}
                isListening = false;
            }
            call.resolve();
        });
    }

    @PluginMethod()
    public void available(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("available", SpeechRecognizer.isRecognitionAvailable(getContext()));
        call.resolve(ret);
    }

    @Override
    protected void handleOnDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.handleOnDestroy();
    }

    private void sendResults(Bundle bundle, boolean isFinal) {
        ArrayList<String> matches =
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            JSObject ret = new JSObject();
            ret.put("transcript", matches.get(0));
            ret.put("isFinal", isFinal);
            JSArray alts = new JSArray();
            for (String m : matches) { alts.put(m); }
            ret.put("alternatives", alts);
            notifyListeners("speechResult", ret);
        }
    }

    private String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "L\u1ed7i ghi \u00e2m";
            case SpeechRecognizer.ERROR_CLIENT: return "L\u1ed7i \u1ee9ng d\u1ee5ng";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Ch\u01b0a c\u1ea5p quy\u1ec1n micro";
            case SpeechRecognizer.ERROR_NETWORK: return "L\u1ed7i m\u1ea1ng";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "H\u1ebft th\u1eddi gian k\u1ebft n\u1ed1i";
            case SpeechRecognizer.ERROR_NO_MATCH: return "Kh\u00f4ng nh\u1eadn d\u1ea1ng \u0111\u01b0\u1ee3c";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "B\u1ed9 nh\u1eadn d\u1ea1ng \u0111ang b\u1eadn";
            case SpeechRecognizer.ERROR_SERVER: return "L\u1ed7i m\u00e1y ch\u1ee7";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "Kh\u00f4ng nghe th\u1ea5y gi\u1ecdng n\u00f3i";
            default: return "L\u1ed7i: " + error;
        }
    }
}
