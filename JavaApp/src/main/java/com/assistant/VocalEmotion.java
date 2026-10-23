package com.assistant;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.File;
import java.util.Collections;
import java.util.Map;

public class VocalEmotion {

    private OrtEnvironment env;
    private OrtSession session;
    // Map output indices to emotion labels
    private final String[] EMOTIONS = {"Neutral", "Happy", "Sad", "Angry"};

    public VocalEmotion() {
        try {
            env = OrtEnvironment.getEnvironment();
            File modelFile = new File("models/emotion_model.onnx");
            if (modelFile.exists()) {
                session = env.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                System.out.println("[VocalEmotion] ONNX model loaded successfully.");
            } else {
                System.err.println("[VocalEmotion] WARNING: ONNX Model file not found at 'models/emotion_model.onnx'. Running in simulation mode.");
            }
        } catch (Exception e) {
            System.err.println("[VocalEmotion] Failed to load ONNX model: " + e.getMessage());
        }
    }

    private float[] preprocessPCM(byte[] pcmData) {
        float[] floatData = new float[pcmData.length / 2];
        for (int i = 0; i < floatData.length; i++) {
            int sample = (pcmData[2 * i + 1] << 8) | (pcmData[2 * i] & 0xFF);
            floatData[i] = sample / 32768.0f;
        }
        return floatData;
    }

    public String predict(byte[] pcmData) {
        if (session == null) {
            // Simulated baseline if model is missing
            return "Neutral";
        }
        try {
            float[] floatData = preprocessPCM(pcmData);
            float[][] inputShape = new float[1][floatData.length];
            inputShape[0] = floatData;
            
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, inputShape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor);
            
            OrtSession.Result result = session.run(inputs);
            
            // Output is likely a probability distribution array of size 4 [Neutral, Happy, Sad, Angry]
            float[][] probabilities = (float[][]) result.get(0).getValue();
            
            int maxIdx = 0;
            float maxVal = -1f;
            for (int i = 0; i < probabilities[0].length; i++) {
                if (probabilities[0][i] > maxVal) {
                    maxVal = probabilities[0][i];
                    maxIdx = i;
                }
            }
            return EMOTIONS[maxIdx];
            
        } catch (Exception e) {
            System.err.println("[VocalEmotion] Inference failed: " + e.getMessage());
            return "Neutral";
        }
    }
}
