package com.assistant;

import java.io.File;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.opencv.videoio.VideoCapture;

public class FaceVerification {
    public static final int EMBEDDING_DIM = 128;
    public static final String EMBEDDING_VERSION = "sface-128-v1";
    private static final float MATCH_THRESHOLD = 0.70f;
    private static final float MATCH_MARGIN = 0.08f;
    private FaceDetectorYN detector;
    private FaceRecognizerSF recognizer;
    private boolean ready;

    public FaceVerification() {
        try {
            File detectionModel = new File("models/face_detection_yunet_2023mar.onnx");
            File recognitionModel = new File("models/face_recognition_sface_2021dec.onnx");
            if (!detectionModel.isFile() || !recognitionModel.isFile()) {
                throw new IllegalStateException("Face recognition models are missing.");
            }
            detector = FaceDetectorYN.create(detectionModel.getAbsolutePath(), "",
                    new Size(320, 320), 0.90f, 0.3f, 5000);
            recognizer = FaceRecognizerSF.create(recognitionModel.getAbsolutePath(), "");
            ready = true;
            System.out.println("[FaceVerification] YuNet and SFace recognition loaded.");
        } catch (Throwable error) {
            ready = false;
            System.err.println("[FaceVerification] Face login disabled: " + error.getMessage());
        }
    }

    public boolean isReady() {
        return ready;
    }

    public float[] extractFaceprintFromBase64(String image) {
        if (!ready || image == null || image.isBlank()) return null;
        MatOfByte bytes = null;
        Mat frame = null;
        try {
            if (image.startsWith("data:")) image = image.substring(image.indexOf(',') + 1);
            bytes = new MatOfByte(Base64.getDecoder().decode(image));
            frame = Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR);
            return extractFaceprintFromMat(frame);
        } catch (Exception error) {
            System.err.println("[FaceVerification] Invalid face capture: " + error.getMessage());
            return null;
        } finally {
            if (frame != null) frame.release();
            if (bytes != null) bytes.release();
        }
    }

    // OpenCV DNN instances have mutable input state; serialize enrollment and login inference.
    public synchronized float[] extractFaceprintFromMat(Mat frame) {
        if (!ready || frame == null || frame.empty() || frame.channels() != 3) return null;
        Mat faces = new Mat();
        Mat face = null;
        Mat aligned = new Mat();
        Mat features = new Mat();
        try {
            detector.setInputSize(new Size(frame.cols(), frame.rows()));
            detector.detect(frame, faces);
            if (faces.rows() != 1) return null;
            if (faces.get(0, 2)[0] < 80 || faces.get(0, 3)[0] < 80) return null;
            face = faces.row(0);
            recognizer.alignCrop(frame, face, aligned);
            recognizer.feature(aligned, features);
            if (features.total() * features.channels() != EMBEDDING_DIM) return null;
            float[] embedding = new float[EMBEDDING_DIM];
            features.get(0, 0, embedding);
            return normalize(embedding);
        } catch (Throwable error) {
            System.err.println("[FaceVerification] Recognition failed: " + error.getMessage());
            return null;
        } finally {
            features.release();
            aligned.release();
            if (face != null) face.release();
            faces.release();
        }
    }

    public float[] captureFaceprint() {
        if (!ready) return null;
        VideoCapture camera = new VideoCapture(0);
        Mat frame = new Mat();
        try {
            if (!camera.isOpened()) return null;
            for (int i = 0; i < 6; i++) {
                if (camera.read(frame)) {
                    float[] embedding = extractFaceprintFromMat(frame);
                    if (embedding != null) return embedding;
                }
            }
            return null;
        } finally {
            frame.release();
            camera.release();
        }
    }

    public static boolean isValidEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != EMBEDDING_DIM) return false;
        double norm = 0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) return false;
            norm += (double) value * value;
        }
        return norm > 0.99 && norm < 1.01;
    }

    private static float[] normalize(float[] embedding) {
        double norm = 0;
        for (float value : embedding) {
            if (!Float.isFinite(value)) return null;
            norm += (double) value * value;
        }
        if (norm < 1e-12) return null;
        norm = Math.sqrt(norm);
        for (int i = 0; i < embedding.length; i++) embedding[i] /= norm;
        return isValidEmbedding(embedding) ? embedding : null;
    }

    public static float[] fuseEmbeddings(List<float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) return null;
        float[] fused = new float[EMBEDDING_DIM];
        for (float[] embedding : embeddings) {
            if (!isValidEmbedding(embedding)) return null;
            for (int i = 0; i < EMBEDDING_DIM; i++) fused[i] += embedding[i];
        }
        return normalize(fused);
    }

    public static float cosineSimilarity(float[] a, float[] b) {
        if (!isValidEmbedding(a) || !isValidEmbedding(b)) return -1;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return (float) (dot / Math.sqrt(normA * normB));
    }

    public static int findMatchingUser(float[] query, Map<Integer, float[]> enrolled) {
        if (!isValidEmbedding(query) || enrolled == null) return -1;
        int bestUser = -1;
        float best = -1, second = -1;
        for (Map.Entry<Integer, float[]> entry : enrolled.entrySet()) {
            if (entry.getKey() <= 0 || !isValidEmbedding(entry.getValue())) continue;
            float score = cosineSimilarity(query, entry.getValue());
            if (score > best) {
                second = best;
                best = score;
                bestUser = entry.getKey();
            } else if (score > second) {
                second = score;
            }
        }
        return best >= MATCH_THRESHOLD && best - second >= MATCH_MARGIN ? bestUser : -1;
    }
}
