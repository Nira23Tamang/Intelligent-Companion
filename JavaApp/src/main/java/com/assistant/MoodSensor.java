package com.assistant;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.core.CvType;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.videoio.VideoCapture;

public class MoodSensor extends Thread {
    private boolean isRunning = false;
    private Engine engine;
    private VideoCapture capture;
    private CascadeClassifier faceCascade;
    private Net dnnModel;
    private String[] classes = {"angry", "disgust", "fear", "happy", "neutral", "sad", "surprise"};

    public MoodSensor(Engine engine) {
        this.engine = engine;
        
        try {
            // Load Haar Cascade
            String cascadePath = "haarcascade_frontalface_default.xml"; // Should be placed in root
            faceCascade = new CascadeClassifier(cascadePath);
            
            // Load ONNX Model
            String modelPath = "src/main/resources/best_model.onnx";
            dnnModel = Dnn.readNetFromONNX(modelPath);
            System.out.println("ONNX Emotion Model Loaded!");
        } catch (Exception e) {
            System.err.println("Failed to load MoodSensor models: " + e.getMessage());
        }
    }

    public void halt() {
        isRunning = false;
        if (capture != null) {
            capture.release();
        }
    }

    @Override
    public void run() {
        isRunning = true;
        capture = new VideoCapture(0);
        
        if (!capture.isOpened()) {
            System.out.println("Cannot open webcam for mood detection.");
            return;
        }
        
        Mat frame = new Mat();
        Mat grayFrame = new Mat();
        
        while (isRunning) {
            if (capture.read(frame)) {
                Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_BGR2GRAY);
                MatOfRect faces = new MatOfRect();
                faceCascade.detectMultiScale(grayFrame, faces, 1.3, 5);
                
                Rect[] facesArray = faces.toArray();
                if (facesArray.length > 0) {
                    Rect largestFace = facesArray[0];
                    for (Rect rect : facesArray) {
                        if (rect.area() > largestFace.area()) {
                            largestFace = rect;
                        }
                    }
                    
                    Mat faceRoi = new Mat(grayFrame, largestFace);
                    Mat resized = new Mat();
                    Imgproc.resize(faceRoi, resized, new Size(48, 48));
                    
                    resized.convertTo(resized, CvType.CV_32F, 1.0 / 255.0);
                    Core.subtract(resized, new org.opencv.core.Scalar(0.5), resized);
                    Core.divide(resized, new org.opencv.core.Scalar(0.5), resized);
                    
                    // Create 4D blob for DNN
                    Mat blob = Dnn.blobFromImage(resized);
                    dnnModel.setInput(blob);
                    Mat prob = dnnModel.forward();
                    
                    // Find max probability
                    float[] data = new float[7];
                    prob.get(0, 0, data);
                    
                    int maxIdx = 0;
                    float maxVal = data[0];
                    for (int i = 1; i < data.length; i++) {
                        if (data[i] > maxVal) {
                            maxVal = data[i];
                            maxIdx = i;
                        }
                    }
                    
                    String mood = classes[maxIdx];
                    engine.updateMood(mood, maxVal);
                } else {
                    engine.updateMood("neutral", 0.0f);
                }
            }
            
            try {
                Thread.sleep(500); // 2 FPS
            } catch (InterruptedException e) {
                break;
            }
        }
    }
}
