package com.assistant;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.videoio.VideoCapture;

import java.io.File;
import java.util.Collections;

public class WebcamEmotion {
    private OrtEnvironment env;
    private OrtSession session;
    private CascadeClassifier faceDetector;
    private boolean isReady = false;

    // FERPlus Labels
    private final String[] EMOTION_LABELS = {
            "Neutral", "Happy", "Surprise", "Sad", "Angry", "Disgust", "Fear", "Contempt"
    };

    public WebcamEmotion() {
        try {
            // Load OpenCV Native Library globally in main()
            System.out.println("[WebcamEmotion] OpenCV Loaded Successfully.");

            // Load Haar Cascade for face detection
            File cascadeFile = new File("haarcascade_frontalface_default.xml");
            if (cascadeFile.exists()) {
                faceDetector = new CascadeClassifier(cascadeFile.getAbsolutePath());
            } else {
                System.err.println("[WebcamEmotion] haarcascade_frontalface_default.xml not found! Webcam emotion disabled.");
                return;
            }

            // Load ONNX Model
            File modelFile = new File("models/emotion-ferplus-8.onnx");
            if (modelFile.exists()) {
                env = OrtEnvironment.getEnvironment();
                session = env.createSession(modelFile.getAbsolutePath(), new OrtSession.SessionOptions());
                isReady = true;
                System.out.println("[WebcamEmotion] ONNX Model loaded successfully.");
            } else {
                System.err.println("[WebcamEmotion] models/emotion-ferplus-8.onnx not found! Webcam emotion disabled.");
            }
        } catch (Exception e) {
            System.err.println("[WebcamEmotion] Initialization failed: " + e.getMessage());
        }
    }

    public String predict() {
        if (!isReady) return "Neutral";

        VideoCapture camera = new VideoCapture(0, org.opencv.videoio.Videoio.CAP_DSHOW);
        if (!camera.isOpened()) {
            System.err.println("[WebcamEmotion] Could not open webcam.");
            return "Neutral";
        }

        try {
            // A short warm-up provides a fresh frame without holding up the reply for too long.
            Thread.sleep(250);
            
            Mat frame = new Mat();
            // Read a few times to clear old buffer frame and get a fresh, well-exposed frame
            for (int i = 0; i < 3; i++) {
                camera.read(frame);
            }
            
            if (frame.empty()) {
                System.err.println("[WebcamEmotion] Captured empty frame.");
                return "Neutral";
            }

            // Convert to grayscale
            Mat grayFrame = new Mat();
            Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_BGR2GRAY);

            // Detect faces
            MatOfRect faceDetections = new MatOfRect();
            faceDetector.detectMultiScale(grayFrame, faceDetections);

            if (faceDetections.toArray().length == 0) {
                System.out.println("[WebcamEmotion] No face detected.");
                return "Neutral"; // Default if no face
            }

            // Pick the largest face
            Rect largestFace = null;
            int maxArea = 0;
            for (Rect rect : faceDetections.toArray()) {
                if (rect.width * rect.height > maxArea) {
                    largestFace = rect;
                    maxArea = rect.width * rect.height;
                }
            }

            // Crop and resize to 64x64
            Mat croppedFace = new Mat(grayFrame, largestFace);
            Mat resizedFace = new Mat();
            Imgproc.resize(croppedFace, resizedFace, new Size(64, 64));

            // Convert Mat to Float Tensor array [1, 1, 64, 64]
            float[][][][] tensorData = new float[1][1][64][64];
            for (int r = 0; r < 64; r++) {
                for (int c = 0; c < 64; c++) {
                    tensorData[0][0][r][c] = (float) resizedFace.get(r, c)[0];
                }
            }

            // Run ONNX Inference
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, tensorData);
            OrtSession.Result result = session.run(Collections.singletonMap("Input3", inputTensor)); // FERPlus input node is usually "Input3"

            float[][] output = (float[][]) result.get(0).getValue();
            
            // Find max index
            int maxIndex = 0;
            float maxVal = -1000f;
            for (int i = 0; i < output[0].length; i++) {
                if (output[0][i] > maxVal) {
                    maxVal = output[0][i];
                    maxIndex = i;
                }
            }
            
            String mood = EMOTION_LABELS[maxIndex];
            
            // Cleanup memory
            inputTensor.close();
            result.close();
            frame.release();
            grayFrame.release();
            croppedFace.release();
            resizedFace.release();
            
            // Normalize slightly to fit UI expectations
            if (mood.equals("Happiness")) mood = "Happy";
            if (mood.equals("Sadness")) mood = "Sad";
            if (mood.equals("Anger")) mood = "Angry";
            
            return mood;

        } catch (Exception e) {
            System.err.println("[WebcamEmotion] Prediction failed: " + e.getMessage());
            return "Neutral";
        } finally {
            camera.release(); // Important to release webcam lock
        }
    }
}
