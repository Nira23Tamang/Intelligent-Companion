package com.assistant;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.net.URI;
import java.awt.Desktop;
import java.io.OutputStream;
import java.io.IOException;
import org.java_websocket.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class IntelligentAssistant {
    private static Engine engine;
    private static MoodSensor moodSensor;
    public static SyncServer syncServer;
    private static HttpServer fileServer;
    private static Process ollamaProcess;
    private static boolean spawnedOllamaLocally = false;
    public static volatile boolean isSpeaking = false;
    public record SpeechItem(int userId, long session, String text) {}
    public static BlockingQueue<SpeechItem> ttsQueue = new LinkedBlockingQueue<>();
    private static long sessionGeneration;

    public static synchronized long currentSession() {
        return sessionGeneration;
    }

    public static synchronized boolean isSessionCurrent(int userId, long session) {
        return userId > 0 && userId == Engine.currentUserId && session == sessionGeneration;
    }

    public static synchronized void invalidateSessionWork() {
        sessionGeneration++;
        isInterrupted = true;
        ttsQueue.clear();
        isSpeaking = false;
        if (engine != null) {
            engine.stopListening();
            engine.interruptTTS();
            engine.updateMood("neutral", 0);
        }
    }

    public static void queueSpeech(String text) {
        queueSpeech(Engine.currentUserId, currentSession(), text);
    }

    public static synchronized void queueSpeech(int userId, long session, String text) {
        if (isSessionCurrent(userId, session)) ttsQueue.add(new SpeechItem(userId, session, text));
    }
    public static volatile boolean isInterrupted = false;
    private static volatile boolean isShuttingDown = false;

    public static void interrupt() {
        isInterrupted = true;
        ttsQueue.clear();
        queueSpeech("[[END]]"); // unblock the TTS thread
        engine.interruptTTS(); // kill SAPI
        isSpeaking = false;
        if (syncServer != null) {
            syncServer.broadcastState(engine.currentMood, false);
            syncServer.broadcastReset();
        }
    }

    public static Engine getEngine() {
        return engine;
    }

    public static void resetMemory() {
        if (engine != null) {
            engine.resetMemory();
        }
        if (syncServer != null) {
            syncServer.broadcastReset();
        }
    }

    public static boolean resetTargetedMemory() {
        if (engine != null) {
            return engine.resetTargetedMemory();
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Initializing Intelligent Assistant Backend...");
        nu.pattern.OpenCV.loadLocally(); // Load OpenCV globally once
        
        // Register JVM Shutdown Hook for clean resource release on exit/kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownSystem();
        }));

        // Setup DB
        // DB is setup implicitly by MemoryManager inside Engine
        
        // Auto-cleanup ports before starting (prevents Address already in use errors)
        killPortIfBusy(8080);
        killPortIfBusy(8081);
        Thread.sleep(500); // Brief pause after killing processes

        // Start Ollama in the background with active health-check
        startOllamaDaemon();

        // Initialize Core Engines
        engine = new Engine();
        moodSensor = new MoodSensor(engine);
        // moodSensor.start(); // Disabled to prevent camera lock; FaceVerification & WebcamEmotion access camera on-demand per turn

        // Start WebSocket Sync Server on 8081
        syncServer = new SyncServer(8081) {
            @Override
            public void onMessage(WebSocket conn, String message) {
                super.onMessage(conn, message);
                if (!isAuthenticatedConnection(conn)) return;
                String action;
                try {
                    action = new org.json.JSONObject(message).optString("action");
                } catch (RuntimeException invalidMessage) {
                    return;
                }
                if ("listen".equals(action)) {
                    startVoiceInteraction(conn);
                } else if ("stop_listen".equals(action)) {
                    if (engine != null) {
                        engine.stopListening();
                    }
                }
            }
        };
        syncServer.start();

        // Start HTTP File Server on 8080 to serve the Live2D Web UI
        Path frontendPath = Paths.get("frontend").toAbsolutePath();
        fileServer = HttpServer.create(new InetSocketAddress(8080), 0);
        fileServer.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String uriPath = exchange.getRequestURI().getPath();
                if (uriPath.equals("/")) {
                    uriPath = "/index.html";
                }
                Path filePath = frontendPath.resolve(uriPath.substring(1)).normalize();
                
                if (!filePath.startsWith(frontendPath) || !Files.exists(filePath) || Files.isDirectory(filePath)) {
                    String response = "404 Not Found";
                    exchange.sendResponseHeaders(404, response.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                } else {
                    byte[] fileBytes = Files.readAllBytes(filePath);
                    String mimeType = "application/octet-stream";
                    if (uriPath.endsWith(".html")) mimeType = "text/html";
                    else if (uriPath.endsWith(".css")) mimeType = "text/css";
                    else if (uriPath.endsWith(".js")) mimeType = "application/javascript";
                    else if (uriPath.endsWith(".png")) mimeType = "image/png";
                    
                    exchange.getResponseHeaders().set("Content-Type", mimeType);
                    exchange.sendResponseHeaders(200, fileBytes.length);
                    OutputStream os = exchange.getResponseBody();
                    os.write(fileBytes);
                    os.close();
                }
            }
        });
        fileServer.setExecutor(null);
        fileServer.start();

        System.out.println("==================================================");
        System.out.println("SYSTEM READY!");
        System.out.println("Launching Web UI...");
        System.out.println("==================================================");

        // Automatically pop open the browser to the Web UI
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:8080"));
            }
        } catch (Exception e) {
            System.out.println("Could not auto-launch browser. Please open http://localhost:8080 manually.");
        }

        // Background thread to poll engine mood and broadcast to UI
        new Thread(() -> {
            while (true) {
                try {
                    syncServer.broadcastState(engine.currentMood, isSpeaking);
                    Thread.sleep(500);
                } catch (Exception e) {}
            }
        }).start();

        // Global TTS processing thread
        new Thread(() -> {
            try {
                while (true) {
                    SpeechItem item = ttsQueue.take();
                    String phrase = item.text();
                    synchronized (IntelligentAssistant.class) {
                        if (!isSessionCurrent(item.userId(), item.session())) continue;
                        if (phrase.equals("[[END]]")) {
                            isSpeaking = false;
                            syncServer.broadcastForSession(item.userId(), item.session(),
                                    new org.json.JSONObject().put("type", "state").put("mood", engine.currentMood).put("speaking", false).toString());
                            syncServer.broadcastForSession(item.userId(), item.session(),
                                    new org.json.JSONObject().put("type", "reset").toString());
                            System.out.println("Done speaking. Ready for next command.");
                            continue;
                        }
                        
                        isSpeaking = true;
                        syncServer.broadcastForSession(item.userId(), item.session(),
                                new org.json.JSONObject().put("type", "state").put("mood", engine.currentMood).put("speaking", true).toString());
                    }
                    engine.speak(phrase, () -> isSessionCurrent(item.userId(), item.session()));
                }
            } catch (Exception e) {}
        }).start();

        // First-run setup is now handled entirely by the frontend UI wizard
        // via the setup_complete WebSocket event in SyncServer.java

        // Prevent JVM from exiting
        while (true) {
            try {
                Thread.sleep(10000);
            } catch (Exception e) {}
        }
    }

    public static void processUserCommand(String userText) {
        processUserCommand(userText, Engine.currentUserId, Engine.currentConversationId, currentSession());
    }

    public static void processUserCommand(String userText, int userId, int conversationId, long session) {
        startResponse(userText, userId, conversationId, session, true);
    }

    private static void startResponse(String text, int userId, int conversationId, long session, boolean showUser) {
        new Thread(() -> {
            if (!isSessionCurrent(userId, session)) return;
            if (text == null || text.isBlank()) {
                sessionEvent(userId, session, new org.json.JSONObject().put("type", "reset"));
                return;
            }
            if (showUser) {
                sessionEvent(userId, session, new org.json.JSONObject()
                        .put("type", "chat").put("sender", "You").put("text", text));
            }
            sessionEvent(userId, session, new org.json.JSONObject().put("type", "chat_start").put("sender", "AI"));
            synchronized (IntelligentAssistant.class) {
                if (!isSessionCurrent(userId, session)) return;
                isInterrupted = false;
            }
            try {
                engine.askOllamaStream(text,
                        token -> {
                            if (isInterrupted || !isSessionCurrent(userId, session)) {
                                throw new java.util.concurrent.CancellationException("Session ended");
                            }
                            sessionEvent(userId, session, new org.json.JSONObject().put("type", "chat_token").put("text", token));
                        },
                        phrase -> {
                            if (isInterrupted || !isSessionCurrent(userId, session)) {
                                throw new java.util.concurrent.CancellationException("Session ended");
                            }
                            if (!phrase.isBlank()) queueSpeech(userId, session, phrase.trim());
                        }, userId, conversationId, session);
            } catch (java.util.concurrent.CancellationException cancelled) {
                // Account changes cancel outstanding replies.
            } finally {
                queueSpeech(userId, session, "[[END]]");
            }
        }, "assistant-response").start();
    }

    private static void sessionEvent(int userId, long session, org.json.JSONObject event) {
        if (syncServer != null) syncServer.broadcastForSession(userId, session, event.toString());
    }

    private static void startVoiceInteraction(WebSocket conn) {
        final int userId = Engine.currentUserId;
        final long session = currentSession();
        new Thread(() -> {
            if (!isSessionCurrent(userId, session)) return;
            String text = engine.listenForVoice();
            if (!isSessionCurrent(userId, session)) return;
            if (text == null || text.isBlank()) {
                sessionEvent(userId, session, new org.json.JSONObject().put("type", "reset"));
            } else if (syncServer != null) {
                syncServer.submitChat(conn, text);
            }
        }, "voice-interaction").start();
    }

    public static void triggerAutonomousPrompt(String prompt) {
        triggerAutonomousPrompt(prompt, Engine.currentUserId, Engine.currentConversationId, currentSession());
    }

    public static void triggerAutonomousPrompt(String prompt, int userId, int conversationId, long session) {
        startResponse(prompt, userId, conversationId, session, false);
    }

    private static void killPortIfBusy(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                "Get-NetTCPConnection -LocalPort " + port + " -ErrorAction SilentlyContinue | " +
                "Select-Object -ExpandProperty OwningProcess | Sort-Object -Unique | " +
                "ForEach-Object { if ($_ -ne 0) { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue } }");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            System.out.println("Port " + port + " cleared.");
        } catch (Exception e) {
            System.out.println("Could not clear port " + port + ": " + e.getMessage());
        }
    }

    private static void startOllamaDaemon() {
        System.out.println("[Lifecycle] Checking Ollama LLM Engine status...");
        if (isOllamaHealthy()) {
            System.out.println("[Lifecycle] Ollama LLM Engine is already running.");
            prewarmOllama();
            return;
        }

        try {
            System.out.println("[Lifecycle] Spawning local Ollama daemon (ollama serve)...");
            ProcessBuilder pb = new ProcessBuilder("ollama", "serve");
            pb.redirectErrorStream(true);
            ollamaProcess = pb.start();
            spawnedOllamaLocally = true;

            // Stream log reader
            new Thread(() -> {
                try (java.util.Scanner s = new java.util.Scanner(ollamaProcess.getInputStream())) {
                    while (s.hasNextLine()) {
                        String line = s.nextLine();
                        if (line.contains("Listening on") || line.contains("error:")) {
                            System.out.println("[Ollama Log] " + line);
                        }
                    }
                } catch (Exception ignored) {}
            }).start();

            // Active health-check polling loop up to 15 seconds
            long start = System.currentTimeMillis();
            boolean ready = false;
            while (System.currentTimeMillis() - start < 15000) {
                if (isOllamaHealthy()) {
                    ready = true;
                    break;
                }
                Thread.sleep(500);
            }

            if (ready) {
                System.out.println("[Lifecycle] Ollama LLM Engine online and healthy!");
                prewarmOllama();
            } else {
                System.err.println("[Lifecycle] WARNING: Ollama startup timeout after 15s.");
            }

        } catch (Exception e) {
            System.err.println("[Lifecycle] Failed to start Ollama automatically: " + e.getMessage());
        }
    }

    private static boolean isOllamaHealthy() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(800))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/tags"))
                    .timeout(java.time.Duration.ofMillis(800))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void prewarmOllama() {
        new Thread(() -> {
            try {
                System.out.println("[Lifecycle] Pre-warming LLM model into memory...");
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                String payload = "{\"model\": \"qwen2.5:3b\", \"prompt\": \"hi\", \"stream\": false, \"keep_alive\": \"30m\"}";
                java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:11434/api/generate"))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                System.out.println("[Lifecycle] LLM model primed and ready for instant response.");
            } catch (Exception e) {
                System.out.println("[Lifecycle] LLM pre-warm note: " + e.getMessage());
            }
        }).start();
    }

    public static synchronized void shutdownSystem() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        
        System.out.println("==================================================");
        System.out.println("[Lifecycle] SHUTDOWN TRIGGERED: Releasing all resources...");
        System.out.println("==================================================");

        // 1. Release Engine hardware & software resources (Mic, Piper TTS, Audio Clips, DB)
        try {
            if (engine != null) {
                engine.closeAllResources();
            }
        } catch (Exception e) {
            System.err.println("[Lifecycle] Engine release error: " + e.getMessage());
        }

        // 2. Stop WebSocket Server (port 8081)
        try {
            if (syncServer != null) {
                syncServer.stop(1000);
                System.out.println("[Lifecycle] WebSocket server (port 8081) stopped.");
            }
        } catch (Exception e) {
            System.err.println("[Lifecycle] WebSocket stop error: " + e.getMessage());
        }

        // 3. Stop HTTP File Server (port 8080)
        try {
            if (fileServer != null) {
                fileServer.stop(0);
                System.out.println("[Lifecycle] HTTP web server (port 8080) stopped.");
            }
        } catch (Exception e) {
            System.err.println("[Lifecycle] HTTP server stop error: " + e.getMessage());
        }

        // 4. Terminate local Ollama process if spawned by this application instance
        try {
            if (spawnedOllamaLocally && ollamaProcess != null && ollamaProcess.isAlive()) {
                ollamaProcess.destroyForcibly();
                System.out.println("[Lifecycle] Local Ollama daemon terminated.");
            }
        } catch (Exception e) {
            System.err.println("[Lifecycle] Ollama process termination error: " + e.getMessage());
        }

        System.out.println("[Lifecycle] All 6 resources released cleanly. Exiting.");
        System.exit(0);
    }
}
