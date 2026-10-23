package com.assistant;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;
import java.io.File;

public class SpeechToText {
    private Model model;
    private Recognizer recognizer;
    private boolean isReady = false;

    public SpeechToText() {
        try {
            org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
            File modelDir = new File("models/vosk-model");
            if (modelDir.exists() && modelDir.isDirectory()) {
                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, 16000.0f);
                isReady = true;
                System.out.println("[SpeechToText] Vosk Model loaded successfully.");
            } else {
                System.err.println("[SpeechToText] WARNING: Vosk model directory not found at 'models/vosk-model'. Running in simulation mode.");
            }
        } catch (Exception e) {
            System.err.println("[SpeechToText] Failed to initialize Vosk: " + e.getMessage());
        }
    }

    public synchronized String transcribe(byte[] pcmData) {
        if (!isReady || recognizer == null) {
            return "Simulated speech transcription.";
        }

        try {
            recognizer.reset();
            recognizer.acceptWaveForm(pcmData, pcmData.length);
            String resultStr = recognizer.getFinalResult();
            JSONObject json = new JSONObject(resultStr);
            return json.optString("text", "").trim();
        } catch (Exception e) {
            System.err.println("[SpeechToText] Transcription error: " + e.getMessage());
            return "";
        }
    }

    public synchronized boolean acceptChunk(byte[] chunk, int len) {
        if (!isReady || recognizer == null) return false;
        try {
            return recognizer.acceptWaveForm(chunk, len);
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized String getPartialResult() {
        if (!isReady || recognizer == null) return "";
        try {
            String res = recognizer.getPartialResult();
            JSONObject json = new JSONObject(res);
            return json.optString("partial", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized String getFinalResult() {
        if (!isReady || recognizer == null) return "";
        try {
            String res = recognizer.getFinalResult();
            JSONObject json = new JSONObject(res);
            return json.optString("text", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    public synchronized void reset() {
        if (recognizer != null) {
            try {
                recognizer.reset();
            } catch (Exception ignored) {}
        }
    }

    public boolean isReady() {
        return isReady;
    }
}
