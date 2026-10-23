package com.assistant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;

public class FaceVerificationIntegrationTest {
    public static void main(String[] args) throws Exception {
        verifyMatching();
        nu.pattern.OpenCV.loadLocally();
        verifyModels();
        verifyStorage();
        verifyConnectionGuard();
        System.out.println("Face verification integration tests passed.");
    }

    private static float[] vector(float x, float y) {
        float[] result = new float[FaceVerification.EMBEDDING_DIM];
        result[0] = x;
        result[1] = y;
        return result;
    }

    private static void verifyMatching() {
        float[] query = vector(1, 0);
        Map<Integer, float[]> enrolled = new LinkedHashMap<>();
        check(FaceVerification.findMatchingUser(query, enrolled) == -1, "No account must reject");
        enrolled.put(1, vector(0, 1));
        check(FaceVerification.findMatchingUser(query, enrolled) == -1, "Unknown face must reject");
        enrolled.put(2, query);
        check(FaceVerification.findMatchingUser(query, enrolled) == 2, "Select strongest match");
        enrolled.put(3, vector(0.98f, (float) Math.sqrt(1 - 0.98 * 0.98)));
        check(FaceVerification.findMatchingUser(query, enrolled) == -1, "Ambiguous matches must reject");
        check(FaceVerification.findMatchingUser(new float[256], enrolled) == -1, "Legacy query must reject");
        check(FaceVerification.findMatchingUser(query, Map.of(4, new float[256])) == -1, "Legacy stored face must reject");
        check(FaceVerification.findMatchingUser(vector(Float.NaN, 0), enrolled) == -1, "Invalid data must reject");
        check(FaceVerification.findMatchingUser(vector(0.69f, (float) Math.sqrt(1 - 0.69 * 0.69)),
                Map.of(2, query)) == -1, "Weak match must reject");
    }

    private static void verifyModels() {
        FaceVerification verifier = new FaceVerification();
        check(verifier.isReady(), "Recognition models must load");
        Mat blank = new Mat(320, 320, CvType.CV_8UC3, new Scalar(0, 0, 0));
        Mat faces = new Mat();
        Mat feature = new Mat();
        Mat aligned = new Mat(112, 112, CvType.CV_8UC3, new Scalar(0, 0, 0));
        try {
            // Exercise native inference directly so model compatibility errors cannot be swallowed.
            FaceDetectorYN detector = FaceDetectorYN.create(
                    "models/face_detection_yunet_2023mar.onnx", "", new Size(320, 320));
            detector.detect(blank, faces);
            check(faces.rows() == 0, "Blank image must have no face");
            FaceRecognizerSF recognizer = FaceRecognizerSF.create(
                    "models/face_recognition_sface_2021dec.onnx", "");
            recognizer.feature(aligned, feature);
            check(feature.total() == 128, "Recognition model must return facial embeddings");
            check(verifier.extractFaceprintFromMat(blank) == null, "No face must reject");
            check(verifier.extractFaceprintFromBase64("invalid") == null, "Invalid image must reject");
        } finally {
            blank.release();
            faces.release();
            feature.release();
            aligned.release();
        }
    }

    private static void verifyStorage() throws Exception {
        MemoryManager memory = new MemoryManager();
        int user = memory.createNewUser();
        check(user > 0, "Create disposable account");
        try {
            check(!memory.saveUserFaceprint(user, new float[256]), "Reject legacy enrollment");
            check(memory.saveUserFaceprint(user, vector(1, 0)), "Save real enrollment");
            check(!memory.getAllUserFaceprints().containsKey(user), "Incomplete account must not authenticate");
            memory.markRegistrationComplete(user);
            check(memory.getAllUserFaceprints().containsKey(user), "Completed enrollment must be available");
            try (Connection conn = memory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE faceprints SET embedding_version = 'legacy-pixel-v1' WHERE user_id = ?")) {
                stmt.setInt(1, user);
                stmt.executeUpdate();
            }
            check(!memory.getAllUserFaceprints().containsKey(user), "Legacy model version must not authenticate");
            check(memory.getUserFaceprint(user) == null, "Legacy data requires reenrollment");
        } finally {
            try (Connection conn = memory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM users WHERE user_id = ?")) {
                stmt.setInt(1, user);
                stmt.executeUpdate();
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyConnectionGuard() {
        SyncServer server = new SyncServer(0);
        java.util.List<String> responses = new java.util.ArrayList<>();
        org.java_websocket.WebSocket unknown = (org.java_websocket.WebSocket)
                java.lang.reflect.Proxy.newProxyInstance(
                    org.java_websocket.WebSocket.class.getClassLoader(),
                    new Class<?>[] { org.java_websocket.WebSocket.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            case "isOpen": return true;
                            case "send":
                                if (args[0] instanceof String) responses.add((String) args[0]);
                                return null;
                            default: return null;
                        }
                    });
        int previousUser = Engine.currentUserId;
        try {
            Engine.currentUserId = 123;
            check(!server.isAuthenticatedConnection(unknown), "A shared user ID must not authorize another connection");
            server.onMessage(unknown, new org.json.JSONObject().put("action", "send_chat").put("text", "hello").toString());
            check(new org.json.JSONObject(responses.remove(0)).optString("type").equals("auth_required"),
                    "Unregistered connection must not submit messages");
            server.onMessage(unknown, new org.json.JSONObject().put("action", "setup_capture_face").toString());
            check(new org.json.JSONObject(responses.remove(0)).optString("type").equals("auth_required"),
                    "Unregistered connection must not replace an enrolled face");
            server.broadcast(new org.json.JSONObject().put("type", "chat").put("text", "Private conversation").toString());
            check(responses.isEmpty(), "Unauthenticated connection must not receive conversation broadcasts");
        } finally {
            Engine.currentUserId = previousUser;
        }
    }
}
