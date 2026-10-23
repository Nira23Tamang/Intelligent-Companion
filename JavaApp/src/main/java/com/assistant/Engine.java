package com.assistant;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.Line;
import javax.sound.sampled.SourceDataLine;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

public class Engine {
    private String ollamaUrl = "http://localhost:11434/api/chat";
    private String ollamaGenerateUrl = "http://localhost:11434/api/generate";
    private String ollamaModel = "qwen2.5:3b";
    private HttpClient client;
    private MemoryManager memory;
    private SemanticMemoryStore semanticMemory;

    public String currentMood = "neutral";
    public float currentConfidence = 0.0f;
    public volatile boolean keepListening = false;

    public static volatile int currentUserId = -1;
    public static volatile int currentConversationId = -1;

    // ─── ONBOARDING STATE MACHINE ─────────────────────────────────────────────
    // Note: Chat-based onboarding has been superseded by the UI Setup Wizard.
    // The wizard collects Name, Email, Password, and Goals directly.
    
    // Persistent TTS Process
    private Process ttsProcess;
    private java.io.PrintWriter ttsWriter;
    private java.io.BufferedReader ttsReader;
    private volatile SourceDataLine currentSpeakerLine;
    
    // Persistent Audio Capture
    private TargetDataLine microphone;
    private Process sttProcess;
    
    // Speaker & Face Verification
    private FaceVerification faceVerifier;
    public volatile boolean isAuthenticatedUser = false;
    
    // Native Java AI
    private SpeechToText speechToText;
    private VocalEmotion vocalEmotion;
    private WebcamEmotion webcamEmotion;
    private AudioFilter audioFilter;
    private Thread handsFreeThread;
    private volatile boolean handsFreeRunning = true;
    public volatile long lastSpokenInteractionTime = 0;
    private ScheduledExecutorService reminderScheduler;
    
    public static volatile boolean isWaitingForBriefingAck = false;

    // ─── GUIDED BIOMETRIC SETUP & CALIBRATION ────────────────────────────────
    private java.util.List<float[]> setupFaceEmbeddings = new java.util.ArrayList<>();
    private int setupFaceUserId = -1;

    public Engine() {
        this.client = HttpClient.newHttpClient();
        this.memory = new MemoryManager();
        this.semanticMemory = new SemanticMemoryStore();
        this.faceVerifier = new FaceVerification();
        this.speechToText = new SpeechToText();
        this.vocalEmotion = new VocalEmotion();
        this.webcamEmotion = new WebcamEmotion();
        this.audioFilter = new AudioFilter();
        
        startPersistentTTS();
        startReminderDaemon();
        
        try {
            // Initialize persistent microphone - prioritizing Zoom H1n USB Mic
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            Mixer.Info selectedMicMixer = null;
            for (Mixer.Info mInfo : AudioSystem.getMixerInfo()) {
                String mName = mInfo.getName().toLowerCase();
                if (mName.contains("h series") || mName.contains("zoom") || mName.contains("h1n")) {
                    Mixer m = AudioSystem.getMixer(mInfo);
                    if (m.isLineSupported(info)) {
                        selectedMicMixer = mInfo;
                        System.out.println("[Audio] Dedicated Input Selected: Zoom H1n (" + mInfo.getName() + ")");
                        break;
                    }
                }
            }

            if (selectedMicMixer != null) {
                this.microphone = (TargetDataLine) AudioSystem.getMixer(selectedMicMixer).getLine(info);
            } else if (AudioSystem.isLineSupported(info)) {
                this.microphone = (TargetDataLine) AudioSystem.getLine(info);
                System.out.println("[Audio] Fallback to default microphone.");
            }

            if (this.microphone != null) {
                this.microphone.open(format);
                this.microphone.start();
                startHandsFreeDaemon();
            }
        } catch (Exception e) {
            System.err.println("Failed to initialize microphone: " + e.getMessage());
        }
    }

    private void startPersistentTTS() {
        try {
            // Path must point to extracted piper directory relative to execution
            String piperPath = new File("piper\\piper\\piper.exe").getAbsolutePath();
            String modelPath = new File("piper\\piper\\en_US-lessac-medium.onnx").getAbsolutePath();
            
            ProcessBuilder pb = new ProcessBuilder(
                    piperPath,
                    "--model", modelPath,
                    "--json-input",
                    "--output_dir", "."
            );
            
            pb.redirectError(ProcessBuilder.Redirect.DISCARD); // Throw away stderr to prevent pipe full deadlocks
            
            this.ttsProcess = pb.start();
            this.ttsWriter = new java.io.PrintWriter(this.ttsProcess.getOutputStream(), true);
            this.ttsReader = new java.io.BufferedReader(new java.io.InputStreamReader(this.ttsProcess.getInputStream()));
            
            System.out.println("Persistent Piper TTS Engine Initialized.");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startReminderDaemon() {
        if (reminderScheduler != null && !reminderScheduler.isShutdown()) return;
        reminderScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reminder-monitor");
            thread.setDaemon(true);
            return thread;
        });
        reminderScheduler.scheduleWithFixedDelay(this::checkDueReminders, 1, 1, TimeUnit.SECONDS);
    }

    private void checkDueReminders() {
        try {
            int activeUserId = Engine.currentUserId;
            long session = IntelligentAssistant.currentSession();
            if (activeUserId <= 0 || !isAuthenticatedUser) return;

            List<MemoryManager.DueReminder> dueReminders = memory.claimDueReminders(activeUserId);
            for (int index = 0; index < dueReminders.size(); index++) {
                if (!IntelligentAssistant.isSessionCurrent(activeUserId, session) || !isAuthenticatedUser) {
                    for (int remaining = index; remaining < dueReminders.size(); remaining++) {
                        memory.reopenClaimedReminder(activeUserId, dueReminders.get(remaining).getId());
                    }
                    return;
                }
                dispatchDueReminder(activeUserId, session, dueReminders.get(index));
            }
        } catch (Exception e) {
            System.err.println("[ReminderSystem] Background reminder monitor failed: " + e.getMessage());
        }
    }

    private void dispatchDueReminder(int userId, long session, MemoryManager.DueReminder reminder) {
        synchronized (IntelligentAssistant.class) {
            if (!IntelligentAssistant.isSessionCurrent(userId, session)) {
                memory.reopenClaimedReminder(userId, reminder.getId());
                return;
            }
            String task = normalizeReminderTask(reminder.getTask());
            String reminderPhrase = formatReminderPhrase(task);

            IntelligentAssistant.queueSpeech(userId, session, reminderPhrase);
            IntelligentAssistant.queueSpeech(userId, session, "[[END]]");

            if (IntelligentAssistant.syncServer != null) {
                IntelligentAssistant.syncServer.broadcastChat("AI", reminderPhrase);
                JSONObject alert = new JSONObject();
                alert.put("type", "alert");
                alert.put("text", "REMINDER: " + task);
                IntelligentAssistant.syncServer.broadcast(alert.toString());
            }

            int conversationId = Engine.currentConversationId;
            if (conversationId > 0 && memory.conversationBelongsToUser(conversationId, userId)) {
                memory.saveMessage(conversationId, "assistant", reminderPhrase);
                memory.logConversation(userId, "AI", reminderPhrase, currentMood);
            }
            System.out.println("[ReminderSystem] Triggered reminder " + reminder.getId() + ": " + task);
        }
    }

    static String formatReminderPhrase(String task) {
        return "You had a reminder to " + normalizeReminderTask(task) + ".";
    }

    private static String normalizeReminderTask(String task) {
        String cleanTask = task == null ? "your task" : task.trim();
        if (cleanTask.toLowerCase(java.util.Locale.ROOT).startsWith("to ")) {
            cleanTask = cleanTask.substring(3).trim();
        }
        cleanTask = cleanTask.replaceAll("[.!?]+$", "").trim();
        return cleanTask.isEmpty() ? "your task" : cleanTask;
    }

    public void interruptTTS() {
        if (ttsProcess != null && ttsProcess.isAlive()) {
            ttsProcess.destroyForcibly();
        }
        
        // Stop the active speaker stream immediately.
        if (this.currentSpeakerLine != null) {
            try {
                this.currentSpeakerLine.stop();
                this.currentSpeakerLine.flush();
                this.currentSpeakerLine.close();
                this.currentSpeakerLine = null;
            } catch (Exception e) {}
        }
        
        // Immediately start a fresh server so it's ready for the next prompt
        startPersistentTTS();
    }

    public void updateMood(String mood, float confidence) {
        this.currentMood = mood;
        this.currentConfidence = confidence;
    }

    public synchronized int beginNewUserRegistration() {
        setupFaceEmbeddings.clear();
        Engine.currentConversationId = -1;
        Engine.currentUserId = memory.createNewUser();
        setupFaceUserId = Engine.currentUserId;
        isAuthenticatedUser = false;
        isWaitingForBriefingAck = false;
        System.out.println("[Setup] Started registration for user profile " + Engine.currentUserId);
        return Engine.currentUserId;
    }

    private boolean needsIntentRouting(String text) {
        String normalized = text == null ? "" : text.toLowerCase();
        return normalized.matches(".*\\b(remind|reminder|email|emails|inbox)\\b.*");
    }

    public synchronized boolean beginFaceReenrollment() {
        if (currentUserId <= 0 || !isAuthenticatedUser) return false;
        setupFaceEmbeddings.clear();
        setupFaceUserId = currentUserId;
        return true;
    }

    private String getMoodCompanionGuidance() {
        String mood = currentMood == null ? "neutral" : currentMood.trim().toLowerCase();

        switch (mood) {
            case "sad":
            case "fear":
            case "anxious":
                return "A recent mood cue suggests the user may be feeling low, worried, or overwhelmed. Start with a gentle, tentative check-in such as noticing they seem a little down, validate the feeling, and ask one open question about what has been weighing on them. Do not assume the cause or force optimism.";
            case "angry":
            case "disgust":
            case "contempt":
            case "frustrated":
                return "A recent mood cue suggests frustration or anger. Acknowledge that something may have rubbed them the wrong way, stay steady and on their side, and ask one open question about what happened before jumping to solutions.";
            case "happy":
            case "surprise":
            case "excited":
                return "A recent mood cue suggests the user may be upbeat or pleasantly surprised. Match that warmth, show genuine curiosity, and ask one open question about what is going well or what sparked the feeling.";
            default:
                return "No strong mood cue is available. Do not invent an emotion; respond naturally to the user's words.";
        }
    }

    public String getStrategicContext(String userPrompt) {
        return getStrategicContext(Engine.currentUserId, userPrompt);
    }

    public String getStrategicContext(int userId, String userPrompt) {
        if (userId <= 0 || userPrompt == null || userPrompt.trim().isEmpty()) return "";
        StringBuilder context = new StringBuilder("=== SOCIAL & STRATEGIC COGNITIVE INTELLIGENCE DOSSIER ===\n");
        try {
            JSONArray contacts = memory.getAllPRMContacts(userId);
            boolean foundRelevant = false;

            for (int i = 0; i < contacts.length(); i++) {
                JSONObject c = contacts.getJSONObject(i);
                String name = c.getString("name");
                if (userPrompt.toLowerCase().contains(name.toLowerCase())) {
                    foundRelevant = true;
                    context.append("PERSON OF INTEREST: ").append(name)
                           .append(" (Relationship: ").append(c.optString("relationship", "Unknown")).append(")\n");
                    
                    JSONArray attrs = c.getJSONArray("attributes");
                    for (int j = 0; j < attrs.length(); j++) {
                        JSONObject a = attrs.getJSONObject(j);
                        context.append("  • [").append(a.getString("category")).append("]: ")
                               .append(a.getString("description")).append("\n");
                    }
                    context.append("\n");
                }
            }

            if (foundRelevant) {
                context.append("TACTICAL INSTRUCTION: Use this deep psychological profile, emotional triggers, family ties, and leverage points to provide strategic, highly persuasive, and actionable real-world advice to give the user a distinct interpersonal advantage.\n");
                return context.toString();
            }
        } catch (Exception e) {
            System.err.println("[PRM] Strategic context fetch failed: " + e.getMessage());
        }
        return "";
    }

    public JSONObject generateTacticalStrategy(String contactName, String scenarioGoal) {
        return generateTacticalStrategy(Engine.currentUserId, contactName, scenarioGoal);
    }

    public JSONObject generateTacticalStrategy(int userId, String contactName, String scenarioGoal) {
        JSONObject result = new JSONObject();
        result.put("contact", contactName);
        result.put("goal", scenarioGoal);
        if (userId <= 0) {
            result.put("success", false);
            result.put("error", "Please log in before requesting a strategy.");
            return result;
        }

        try {
            JSONObject dossier = memory.getContactDossier(userId, contactName);
            StringBuilder dossierText = new StringBuilder();
            if (dossier.has("attributes")) {
                JSONArray attrs = dossier.getJSONArray("attributes");
                for (int i = 0; i < attrs.length(); i++) {
                    JSONObject a = attrs.getJSONObject(i);
                    dossierText.append("- ").append(a.getString("category")).append(": ").append(a.getString("description")).append("\n");
                }
            }

            String prompt = "You are a Master Strategic & Psychological Advisor.\n" +
                            "Target Individual: " + contactName + " (" + dossier.optString("relationship", "Associate") + ")\n" +
                            "User's Goal / Scenario: \"" + scenarioGoal + "\"\n\n" +
                            "Target's Psychological & Social Dossier:\n" +
                            (dossierText.length() > 0 ? dossierText.toString() : "- No previous behavioral notes recorded.\n") + "\n" +
                            "Generate a high-impact, tactical interpersonal game plan with these 4 clear sections:\n" +
                            "1. PSYCHOLOGICAL PROFILE & CURRENT STATE (How they think, what pressures/motivations they have)\n" +
                            "2. OPENING HOOK & ICEBREAKER (A warm, disarming lead-in using their interests or personal ties)\n" +
                            "3. PERSUASIVE FRAMING & LEVERAGE (Exact angle to pitch your goal so they feel it is a win for them)\n" +
                            "4. WHAT TO AVOID (Specific triggers, words, or pressure tactics that will backfire)\n\n" +
                            "Output JSON in this format:\n" +
                            "{\"psychological_reading\": \"...\", \"opening_hook\": \"...\", \"persuasive_framing\": \"...\", \"what_to_avoid\": \"...\", \"tactical_summary\": \"...\"}";

            JSONObject payload = new JSONObject();
            payload.put("model", ollamaModel);
            payload.put("prompt", prompt);
            payload.put("stream", false);
            payload.put("format", "json");
            JSONObject options = new JSONObject();
            options.put("temperature", 0.3);
            payload.put("options", options);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaGenerateUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject resJson = new JSONObject(response.body());
            String text = resJson.optString("response", "{}");

            if (text.contains("{") && text.contains("}")) {
                text = text.substring(text.indexOf("{"), text.lastIndexOf("}") + 1);
                JSONObject strategyJson = new JSONObject(text);
                result.put("strategy", strategyJson);
                result.put("success", true);
            } else {
                result.put("success", false);
                result.put("error", "Failed to parse strategy JSON");
            }
        } catch (Exception e) {
            System.err.println("[StrategyEngine] Failed to generate strategy: " + e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }



    public String getSemanticGraphContext(String userPrompt) {
        return getSemanticGraphContext(Engine.currentUserId, userPrompt);
    }

    public String getSemanticGraphContext(int userId, String userPrompt) {
        if (userId <= 0 || userPrompt == null || userPrompt.trim().isEmpty()) return "";
        StringBuilder graphContext = new StringBuilder();
        try {
            JSONObject fullGraph = memory.getCompleteKnowledgeGraph(userId);
            if (fullGraph.has("nodes")) {
                JSONArray nodes = fullGraph.getJSONArray("nodes");
                for (int i = 0; i < nodes.length(); i++) {
                    JSONObject node = nodes.getJSONObject(i);
                    String name = node.getString("name");
                    if (userPrompt.toLowerCase().contains(name.toLowerCase())) {
                        String neighborhood = memory.getGraphNeighborhood(userId, name, 2);
                        if (!neighborhood.isEmpty()) {
                            graphContext.append("• Concept Node [").append(name).append(" (").append(node.optString("type", "Concept")).append(")]:\n")
                                        .append(neighborhood).append("\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GraphRAG] Failed to get graph context: " + e.getMessage());
        }
        return graphContext.toString();
    }

    public void askOllamaStream(String userText, java.util.function.Consumer<String> uiTokenCallback, java.util.function.Consumer<String> ttsPhraseCallback) {
        askOllamaStream(userText, uiTokenCallback, ttsPhraseCallback,
                currentUserId, currentConversationId, IntelligentAssistant.currentSession());
    }

    public void askOllamaStream(String userText, java.util.function.Consumer<String> uiTokenCallback,
            java.util.function.Consumer<String> ttsPhraseCallback, int requestUserId, int requestConversationId, long session) {
        ensureCurrentSession(requestUserId, session);
        final boolean requestAuthenticated = this.isAuthenticatedUser && requestUserId > 0;

        if (isWaitingForBriefingAck) {
            String lowerText = userText.toLowerCase();
            if (lowerText.matches(".*(okay|got it|thanks|understood|clear|yes|fine|acknowledged|done|alright).*")) {
                // Acknowledging the daily briefing must not cancel future reminders.
                isWaitingForBriefingAck = false;
                String msg = "Thanks. What would you like to do next?";
                uiTokenCallback.accept(msg);
                ttsPhraseCallback.accept(msg);
                ttsPhraseCallback.accept("[[END]]");
            } else {
                String msg = "Please acknowledge your pending tasks first.";
                uiTokenCallback.accept(msg);
                ttsPhraseCallback.accept(msg);
                ttsPhraseCallback.accept("[[END]]");
            }
            return;
        }

        // ─── UNIFIED INTENT ROUTER ──────────────────────────────────────────────
        org.json.JSONObject intentObj = needsIntentRouting(userText)
            ? memory.analyzeIntent(userText)
            : new org.json.JSONObject().put("intent", "CHAT");
        ensureCurrentSession(requestUserId, session);
        String intent = intentObj.optString("intent", "CHAT");
        
        if (intent.equals("SET_REMINDER")) {
            if (!requestAuthenticated) {
                System.out.println("[Security] Blocked SET_REMINDER task (Guest detected).");
                return;
            }
            boolean hasTime = intentObj.optBoolean("has_time", false);
            if (hasTime) {
                // Time was specified, we can schedule it!
                String task = intentObj.getString("task");
                String datetime = intentObj.getString("datetime");
                if (!memory.scheduleReminder(requestUserId, task, datetime)) {
                    String failure = "I could not save that reminder. Please tell me the time again.";
                    uiTokenCallback.accept(failure);
                    ttsPhraseCallback.accept(failure);
                    return;
                }
                
                if (IntelligentAssistant.syncServer != null) {
                    JSONObject alertJson = new JSONObject();
                    alertJson.put("type", "alert");
                    alertJson.put("text", "⏰ Reminder Scheduled:\n" + task + "\n" + datetime);
                    IntelligentAssistant.syncServer.broadcastForSession(requestUserId, session, alertJson.toString());
                }
                
                String timeDescription = intentObj.optString("time_description", "").trim();
                String msg = timeDescription.isEmpty()
                        ? "Reminder set for " + datetime + "."
                        : "Reminder set for " + timeDescription + ".";
                uiTokenCallback.accept(msg);
                ttsPhraseCallback.accept(msg);
                return;
            } else {
                // Time was MISSING! Fall through to CHAT so the AI can ask the user what time.
                System.out.println("[IntentRouter] Reminder intent detected, but no time specified. Falling back to CHAT.");
                // We'll append a hidden instruction to the system prompt to explicitly ask for the time.
                userText = "(System Note: The user wants to set a reminder but didn't specify a time. Ask them what time to set it for.)\n" + userText;
            }
        } else if (intent.equals("GET_REMINDERS")) {
            if (!requestAuthenticated) {
                System.out.println("[Security] Blocked GET_REMINDERS task (Guest detected).");
                return;
            }
            String remSummary = memory.getRecentRemindersSummary(requestUserId, 5);
            uiTokenCallback.accept(remSummary);
            ttsPhraseCallback.accept(remSummary);
            return;
        } else if (intent.equals("DELETE_REMINDERS")) {
            if (!requestAuthenticated) {
                System.out.println("[Security] Blocked DELETE_REMINDERS task (Guest detected).");
                return;
            }
            String scope = intentObj.optString("scope", "all");
            int deleted;
            String msg;
            if (scope.equals("specific")) {
                String keyword = intentObj.optString("task", "");
                deleted = memory.deleteReminderByKeyword(requestUserId, keyword);
                msg = (deleted > 0)
                    ? "Done. I deleted " + deleted + " reminder(s) matching \"" + keyword + "\"."
                    : "I couldn't find any pending reminder matching \"" + keyword + "\". Nothing was deleted.";
            } else {
                deleted = memory.deleteAllReminders(requestUserId);
                msg = (deleted > 0)
                    ? "Done. All " + deleted + " pending reminder(s) have been deleted."
                    : "You don't have any pending reminders to delete.";
            }
            uiTokenCallback.accept(msg);
            ttsPhraseCallback.accept(msg);
            ttsPhraseCallback.accept("[[END]]");
            return;
        } else if (intent.equals("READ_EMAIL")) {
            if (!requestAuthenticated) {
                System.out.println("[Security] Blocked READ_EMAIL task (Guest detected).");
                return;
            }
            uiTokenCallback.accept("Checking your inbox for the latest emails...");
            ttsPhraseCallback.accept("Checking your inbox for the latest 3 emails.");
            String result = com.assistant.EmailService.readLatestEmails(requestUserId, 3);
            ttsPhraseCallback.accept(result);
            uiTokenCallback.accept("\n\n" + result);
            return;
        }
        
        // Semantic Vector Memory (RAG)
        String pastContext = userText.length() >= 30 ? semanticMemory.searchMemory(requestUserId, userText, 2) : "";
        
        // Fetch PRM Dossier if a contact is mentioned
        String prmContext = getStrategicContext(requestUserId, userText);
        String graphContext = getSemanticGraphContext(requestUserId, userText);
        
        // ─── USER LONG-TERM MEMORY (Cross-Conversation) ─────────────────────
        String userMemories = memory.getUserMemoriesAsContext(requestUserId);
        
        // ─── CONVERSATION HISTORY (Last 12 messages) ────────────────────────
        JSONArray conversationMessages = new JSONArray();
        if (requestConversationId > 0 && memory.conversationBelongsToUser(requestConversationId, requestUserId)) {
            JSONArray recentMsgs = memory.getRecentMessages(requestConversationId, 12);
            if (recentMsgs.length() > 0) {
                for (int i = 0; i < recentMsgs.length(); i++) {
                    JSONObject msg = recentMsgs.getJSONObject(i);
                    String role = msg.getString("role");
                    String content = msg.getString("content");
                    if ("user".equals(role) || "assistant".equals(role)) {
                        // submitChat persists the current turn before inference, so avoid adding it twice.
                        boolean isCurrentTurn = i == recentMsgs.length() - 1
                                && "user".equals(role)
                                && userText.equals(content);
                        if (isCurrentTurn) {
                            continue;
                        }
                        conversationMessages.put(new JSONObject().put("role", role).put("content", content));
                    }
                }
            }
        }
        
        // Use Ollama's chat endpoint so Qwen receives its native instruction format.
        String userProfile = memory.getUserProfileSummary(requestUserId);
        String systemContent = "You are a warm, perceptive AI companion. Be present like a thoughtful friend while staying honest about being an AI. " +
                               "Answer the user's actual question or feeling first, then offer a useful next thought or gentle question when it fits. " +
                               "For emotional or personal conversations, give a considered 3 to 6 sentence response (roughly 60 to 130 words), not a one-line reply. " +
                               "For simple factual requests, be concise but still complete. Never roleplay as the user and never prefix replies with 'User:' or 'AI:'.\n\n" +
                               "The current detected mood is " + this.currentMood + ". This is a soft cue, not certainty. When it is relevant, phrase it tentatively, for example 'You seem...' or 'It sounds like...', and give the user room to correct you. " +
                               getMoodCompanionGuidance() + " " +
                               "Do not use a generic 'How can I help?' as a substitute for listening. Ask at most one meaningful follow-up question, make it specific to what the user said, and do not repeat the same mood observation on every turn.";
        if (!userProfile.isEmpty()) {
            systemContent += "\n\nABOUT THE USER:\n" + userProfile;
        }
        if (!userMemories.isEmpty()) {
            systemContent += "\n\nUSER'S LONG-TERM MEMORIES (things you permanently remember about this user):\n" + userMemories;
        }
        if (!pastContext.isEmpty()) {
            systemContent += "\n\nRELEVANT PAST MEMORIES:\n" + pastContext;
        }
        if (!prmContext.isEmpty()) {
            systemContent += "\n\n" + prmContext;
        }
        if (!graphContext.isEmpty()) {
            systemContent += "\n\n=== SEMANTIC KNOWLEDGE NETWORK (GRAPH-RAG) ===\n" + graphContext +
                             "Use these interconnected semantic relationships to perform multi-hop reasoning, connect indirect facts, and deduce logical conclusions.\n";
        }

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", systemContent));
        for (int i = 0; i < conversationMessages.length(); i++) {
            messages.put(conversationMessages.getJSONObject(i));
        }
        messages.put(new JSONObject().put("role", "user").put("content", userText));

        // Track the full AI response for DB persistence
        StringBuilder fullAiResponse = new StringBuilder();

        try {
            JSONObject payload = new JSONObject();
            payload.put("model", ollamaModel);
            payload.put("messages", messages);
            payload.put("stream", true); // Enable streaming!
            payload.put("keep_alive", "30m");
            JSONObject options = new JSONObject();
            options.put("temperature", 0.75);
            options.put("top_p", 0.92);
            options.put("repeat_penalty", 1.1);
            options.put("num_predict", 360);
            options.put("num_ctx", 4096);
            payload.put("options", options);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            ensureCurrentSession(requestUserId, session);
            HttpResponse<java.util.stream.Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            
            try (java.util.stream.Stream<String> lines = response.body()) {
                lines.forEach(line -> {
                    ensureCurrentSession(requestUserId, session);
                    if (line.trim().isEmpty()) return;
                    try {
                        JSONObject resJson = new JSONObject(line);
                        String token = resJson.has("message")
                            ? resJson.getJSONObject("message").optString("content", "")
                            : resJson.optString("response", "");
                        if (!token.isEmpty()) {
                            // Send instantly to UI
                            uiTokenCallback.accept(token);
                            
                            // Accumulate full response
                            fullAiResponse.append(token);
                            
                        }
                    } catch (java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    } catch (Exception ex) {}
                });
            }
            ensureCurrentSession(requestUserId, session);

            // ─── PERSIST TO DATABASE ────────────────────────────────────────────
            String aiText = fullAiResponse.toString().trim();
            // Keep the visual response streaming, but synthesize one continuous audio reply.
            if (!aiText.isEmpty()) {
                ttsPhraseCallback.accept(aiText);
            }
            if (requestConversationId > 0 && !aiText.isEmpty()) {
                synchronized (IntelligentAssistant.class) {
                    ensureCurrentSession(requestUserId, session);
                    memory.saveMessage(requestConversationId, "assistant", aiText);
                    // Also log to legacy table
                    memory.logConversation(requestUserId, "AI", aiText, this.currentMood);
                }
            }
            
            // ─── EXTRACT LONG-TERM MEMORIES ASYNC ───────────────────────────────
            if (requestAuthenticated && !aiText.isEmpty()) {
                memory.extractAndSaveUserMemories(requestUserId, userText, aiText, session);
            }

            final String memoryCandidate = userText;
            if (memoryCandidate.length() >= 12) {
                new Thread(() -> semanticMemory.addMemory(requestUserId, memoryCandidate, session), "semantic-memory-save").start();
            }
            if (requestAuthenticated && memoryCandidate.split("\\s+").length >= 3) {
                new Thread(() -> memory.analyzeAndExtractPRM(requestUserId, memoryCandidate, session), "social-memory-save").start();
            }
            
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            ensureCurrentSession(requestUserId, session);
            uiTokenCallback.accept("Sorry, I could not connect to my brain.");
            ttsPhraseCallback.accept("Sorry, I could not connect to my brain.");
        }
    }

    private static void ensureCurrentSession(int userId, long session) {
        if (!IntelligentAssistant.isSessionCurrent(userId, session)) {
            throw new java.util.concurrent.CancellationException("Session ended");
        }
    }

    public void speak(String text) {
        speak(text, () -> true);
    }

    public void speak(String text, java.util.function.BooleanSupplier currentSession) {
        try {
            if (!currentSession.getAsBoolean()) return;
            // Strip special characters that break the TTS engine, but KEEP single quotes
            // since they are valid in speech (e.g. "don't", "can't").
            // We just need to make sure we properly escape them for JSON if needed.
            String cleanText = text.replaceAll("<[^>]+>", "")
                                   .replaceAll("[*_~`#\"\\r\\n$]", " ")
                                   .replace("\\", "\\\\") // Escape backslashes
                                   .replace("\"", "\\\"") // Escape double quotes just in case
                                   .trim();
            
            if (cleanText.isEmpty()) return;

            // Auto-restart Piper if it crashed
            if (ttsProcess == null || !ttsProcess.isAlive()) {
                startPersistentTTS();
            }

            // Generate a unique filename to prevent Windows file locking issues across phrases
            String filename = "tts_" + System.nanoTime() + ".wav";
            java.io.File audioFile = new java.io.File(filename);

            // Send the text to the persistent Piper engine as a JSON payload
            JSONObject json = new JSONObject();
            json.put("text", cleanText);
            json.put("output_file", filename);
            ttsWriter.println(json.toString());
            
            // Wait for Piper to finish generating the file.
            // Piper prints the filename to stdout exactly when it finishes writing the WAV.
            String responseFile = ttsReader.readLine();
            if (responseFile == null || !responseFile.trim().equals(filename)) {
                System.err.println("TTS Error: Piper stdout mismatch or crash. Expected: " + filename + " Got: " + responseFile);
                return;
            }

            // Stream the entire WAV to one speaker line. Clip playback can introduce
            // regular buffer stalls on some Windows audio drivers.
            Mixer.Info selectedSpeakerMixer = null;
            for (Mixer.Info mInfo : AudioSystem.getMixerInfo()) {
                String mName = mInfo.getName().toLowerCase();
                if ((mName.contains("realtek") || mName.contains("speakers")) && !mName.contains("h series") && !mName.contains("usb") && !mName.contains("port")) {
                    Mixer m = AudioSystem.getMixer(mInfo);
                    if (m.getSourceLineInfo().length > 0) {
                        selectedSpeakerMixer = mInfo;
                        break;
                    }
                }
            }

            try (javax.sound.sampled.AudioInputStream audioStream = javax.sound.sampled.AudioSystem.getAudioInputStream(audioFile)) {
                AudioFormat audioFormat = audioStream.getFormat();
                DataLine.Info speakerLineInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
                SourceDataLine speakerLine;
                if (selectedSpeakerMixer != null && AudioSystem.getMixer(selectedSpeakerMixer).isLineSupported(speakerLineInfo)) {
                    speakerLine = (SourceDataLine) AudioSystem.getMixer(selectedSpeakerMixer).getLine(speakerLineInfo);
                } else {
                    speakerLine = (SourceDataLine) AudioSystem.getLine(speakerLineInfo);
                }

                if (!currentSession.getAsBoolean()) return;
                this.currentSpeakerLine = speakerLine;
                speakerLine.open(audioFormat);
                speakerLine.start();

                byte[] audioBuffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = audioStream.read(audioBuffer, 0, audioBuffer.length)) != -1) {
                    if (!currentSession.getAsBoolean() || IntelligentAssistant.isInterrupted || !speakerLine.isOpen()) break;
                    speakerLine.write(audioBuffer, 0, bytesRead);
                }
                if (currentSession.getAsBoolean()) speakerLine.drain();
                else speakerLine.flush();
                speakerLine.stop();
                speakerLine.close();
                this.currentSpeakerLine = null;
            }
            
            // Clean up the temporary file after playback
            if (audioFile.exists()) {
                audioFile.delete();
            }
            
        } catch (Exception e) {
            System.err.println("TTS Error: " + e.getMessage());
        }
    }

    public void stopListening() {
        this.keepListening = false;
    }

    private double calculateRMS(byte[] audioData, int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 2) {
            int sample = (audioData[i+1] << 8) | (audioData[i] & 0xFF);
            sum += sample * sample;
        }
        return Math.sqrt(sum / (double) (length / 2));
    }

        public String listenForVoice() {
        if (this.microphone == null) {
            System.out.println("Microphone not supported or failed to initialize.");
            return "";
        }

        try {
            System.out.println("[STT] Recording started. Speak now (Auto-stops on 2.0s silence)...");
            microphone.start();
            microphone.flush();

            byte[] data = new byte[4096];
            long startTime = System.currentTimeMillis();
            java.io.ByteArrayOutputStream audioBuffer = new java.io.ByteArrayOutputStream();

            this.keepListening = true;
            boolean hasSpeechStarted = false;
            int silenceFrames = 0;
            int maxSilenceFramesFor2Sec = 16; // 16 * ~128ms = 2.048s

            while (this.keepListening && (System.currentTimeMillis() - startTime < 30000)) {
                int numBytesRead = microphone.read(data, 0, data.length);
                if (numBytesRead > 0) {
                    double rms = AudioFilter.calculateRMS(data, numBytesRead);
                    boolean speechActive = (audioFilter != null) ? audioFilter.isSpeechActive(rms) : (rms > 200);

                    if (speechActive) {
                        hasSpeechStarted = true;
                        silenceFrames = 0;
                        audioBuffer.write(data, 0, numBytesRead);
                    } else {
                        if (audioFilter != null) {
                            audioFilter.updateAmbientNoiseFloor(rms);
                        }
                        if (hasSpeechStarted) {
                            audioBuffer.write(data, 0, numBytesRead);
                            silenceFrames++;
                            if (silenceFrames >= maxSilenceFramesFor2Sec) {
                                System.out.println("[STT] 2.0s silence detected! Auto-finalizing utterance.");
                                break;
                            }
                        } else {
                            if (audioBuffer.size() > 8192) {
                                audioBuffer.reset();
                            }
                            audioBuffer.write(data, 0, numBytesRead);
                        }
                    }
                }
            }

            byte[] rawAudio = audioBuffer.toByteArray();
            System.out.println("[STT] Captured " + rawAudio.length + " bytes of audio.");

            if (rawAudio.length < 9600) {
                System.out.println("[STT] Audio too short or only silence, ignoring.");
                return "";
            }

            // Layer 2: Apply Bandpass Filter (80Hz - 3400Hz)
            byte[] filteredAudio = (audioFilter != null) ? audioFilter.applyBandpassFilter(rawAudio) : rawAudio;

            // Layer 3: Native Java Speech-To-Text (Vosk)
            System.out.println("[STT] Running Vosk natively...");
            String transcribedText = this.speechToText.transcribe(filteredAudio);

            if (transcribedText.isEmpty()) {
                System.out.println("[STT] No intelligible speech detected.");
                return "";
            }

            System.out.println("[STT] Final text: " + transcribedText);

            // Update the mood before the message reaches the language model.
            analyzeMultimodalEmotion(filteredAudio, filteredAudio.length);

            return transcribedText;

        } catch (Exception e) {
            System.err.println("[STT] Voice capture failed: " + e.getMessage());
            return "";
        }
    }

    public void startHandsFreeDaemon() {
        handsFreeThread = new Thread(() -> {
            System.out.println("[HandsFree] Background 'Hey Assistant' wake-word daemon started.");
            byte[] buffer = new byte[3200]; // 100ms at 16kHz 16-bit mono

            while (handsFreeRunning) {
                try {
                    if (keepListening || IntelligentAssistant.isSpeaking || microphone == null || Engine.currentUserId == -1) {
                        Thread.sleep(150);
                        continue;
                    }

                    microphone.start();
                    int read = microphone.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        double rms = AudioFilter.calculateRMS(buffer, read);
                        if (audioFilter != null && audioFilter.isSpeechActive(rms)) {
                            speechToText.acceptChunk(buffer, read);
                            String partial = speechToText.getPartialResult().toLowerCase();

                            boolean inFollowUp = (System.currentTimeMillis() - lastSpokenInteractionTime < 9000) && (lastSpokenInteractionTime > 0);

                            if (partial.contains("hey assistant") || partial.contains("assistant") || (inFollowUp && !partial.isEmpty())) {
                                System.out.println("[HandsFree] Wake trigger activated! Text: " + partial);
                                speechToText.reset();
                                if (IntelligentAssistant.syncServer != null) {
                                    IntelligentAssistant.syncServer.broadcastWakeState("wake_triggered");
                                }

                                String userSpeech = listenForVoice();
                                if (!userSpeech.isEmpty()) {
                                    String cleanSpeech = userSpeech.replaceAll("(?i)\\b(hey\\s+assistant|assistant)\\b", "").trim();
                                    if (cleanSpeech.isEmpty()) {
                                        IntelligentAssistant.queueSpeech("Yes, I'm listening!");
                                        IntelligentAssistant.queueSpeech("[[END]]");
                                        lastSpokenInteractionTime = System.currentTimeMillis();
                                    } else {
                                        lastSpokenInteractionTime = System.currentTimeMillis();
                                        IntelligentAssistant.processUserCommand(cleanSpeech);
                                    }
                                }
                            }
                        } else if (audioFilter != null) {
                            audioFilter.updateAmbientNoiseFloor(rms);
                        }
                    }
                } catch (Exception e) {
                    try { Thread.sleep(250); } catch (Exception ignored) {}
                }
            }
        });
        handsFreeThread.setDaemon(true);
        handsFreeThread.start();
    }

    private void analyzeMultimodalEmotion(byte[] pcmData, int length) {
        try {
            // --- Native Java Vocal Emotion (ONNX) ---
            String voiceMood = this.vocalEmotion.predict(pcmData);
            System.out.println("Vocal Emotion Detected: " + voiceMood);
            
            // --- Native Java Webcam Emotion (ONNX + OpenCV) ---
            String faceMood = this.webcamEmotion.predict();
            System.out.println("Facial Emotion Detected: " + faceMood);
            
            // A clear facial expression is the best cue for the companion's check-in;
            // voice is the fallback when the camera sees a neutral expression or no face.
            boolean faceIsExpressive = faceMood != null && !faceMood.equalsIgnoreCase("Neutral");
            boolean voiceIsExpressive = voiceMood != null && !voiceMood.equalsIgnoreCase("Neutral");
            String finalMood = faceIsExpressive ? faceMood : (voiceIsExpressive ? voiceMood : "Neutral");

            this.currentMood = finalMood;
            this.currentConfidence = faceIsExpressive ? 85.0f : (voiceIsExpressive ? 65.0f : 20.0f);
            System.out.println("[EmotionFusion] Final mood for companion reply: " + finalMood);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized JSONObject captureSetupFaceAngle(int userId, String angle, String base64Image) {
        JSONObject res = new JSONObject();
        if (userId <= 0 || currentUserId != userId || setupFaceUserId != userId) {
            return res.put("success", false).put("error", "Please start Face ID setup for your account again.");
        }
        int index = setupFaceEmbeddings.size();
        if (index >= 3 || !java.util.List.of("front", "left", "right").get(index).equals(angle)) {
            return res.put("success", false).put("error", "Please capture each angle once, in order.");
        }
        float[] embedding = faceVerifier.extractFaceprintFromBase64(base64Image);
        if (currentUserId != userId) {
            return res.put("success", false).put("error", "Your account changed. Please start Face ID setup again.");
        }
        if (embedding != null) {
            if (!setupFaceEmbeddings.isEmpty()
                    && FaceVerification.cosineSimilarity(embedding, setupFaceEmbeddings.get(0)) < 0.60f) {
                return res.put("success", false).put("error",
                        "This capture does not match the first face. Use the same person and turn only slightly.");
            }
            setupFaceEmbeddings.add(embedding);
            int count = setupFaceEmbeddings.size();
            res.put("success", true);
            res.put("angle", angle);
            res.put("count", count);
            res.put("progress", Math.min(100, (int)((count / 3.0) * 100)));
            System.out.println("[Setup:Face] Captured angle '" + angle + "' (" + count + "/3)");
            
            // ─── SAVE RAW FACE IMAGE TO DISK ───────────────────────────────────
            String savedPath = memory.saveUserFaceImage(userId, base64Image, angle);
            if (savedPath != null) {
                res.put("face_image_saved", savedPath);
            }
            
            if (count >= 3) {
                float[] masterFace = FaceVerification.fuseEmbeddings(setupFaceEmbeddings);
                if (masterFace != null && memory.saveUserFaceprint(userId, masterFace)) {
                    setupFaceUserId = -1;
                    System.out.println("[Setup:Face] Fused master faceprint registered into H2 database!");
                    res.put("completed", true);
                } else {
                    setupFaceEmbeddings.remove(setupFaceEmbeddings.size() - 1);
                    res.put("success", false);
                    res.put("error", "Face ID could not be saved. Please capture this angle again.");
                }
            } else {
                res.put("completed", false);
            }
        } else {
            res.put("success", false);
            res.put("error", "No face detected. Please look directly at the camera and ensure good lighting.");
        }
        return res;
    }

    public JSONObject saveOnboardingAnswer(String key, String value) {
        if (Engine.currentUserId == -1) {
            Engine.currentUserId = memory.createNewUser();
            System.out.println("[Setup] Created new user profile with ID: " + Engine.currentUserId);
        }
        JSONObject res = new JSONObject();
        try {
            if ("Name".equalsIgnoreCase(key)) {
                value = value == null ? "" : value.trim().replaceAll("\\s+", " ");
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("Please enter the name you would like your companion to use.");
                }
                memory.updateUserName(Engine.currentUserId, value);
            }
            memory.saveUserProfile(Engine.currentUserId, key, value);
            String normalizedKey = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
            boolean privateProfileField = normalizedKey.contains("password") || normalizedKey.contains("email") ||
                    normalizedKey.contains("gmail") || normalizedKey.contains("token");
            if (!privateProfileField) {
                semanticMemory.addMemory(Engine.currentUserId, "User profile " + key + ": " + value);
            }
            res.put("success", true);
            res.put("key", key);
            System.out.println("[Setup:Interview] Saved " + key + " = " + value);
        } catch (Exception e) {
            res.put("success", false);
            res.put("error", e.getMessage());
        }
        return res;
    }

    public void resetMemory() {
        int userId = Engine.currentUserId;
        memory.resetTargetedMemory(userId);
        semanticMemory.resetMemory(userId);
        this.isAuthenticatedUser = false;
        isWaitingForBriefingAck = false;
        Engine.currentUserId = -1;
        System.out.println("[Engine] Memory reset requested and completed.");
    }

    public boolean resetTargetedMemory() {
        int userId = Engine.currentUserId;
        if (userId <= 0 || !isAuthenticatedUser) return false;
        IntelligentAssistant.invalidateSessionWork();
        Engine.currentConversationId = -1;
        isWaitingForBriefingAck = false;
        if (!memory.resetTargetedMemory(userId)) return false;
        if (!semanticMemory.resetMemory(userId)) return false;
        System.out.println("[Engine] Targeted memory reset requested and completed.");
        return true;
    }

    public void closeAllResources() {
        System.out.println("[Engine] Releasing all hardware and software resources...");
        
        // 1. Close Microphone Line
        try {
            if (microphone != null) {
                microphone.stop();
                microphone.close();
                microphone = null;
                System.out.println("[Engine] Microphone line closed.");
            }
        } catch (Exception e) {
            System.err.println("[Engine] Microphone close error: " + e.getMessage());
        }

        // 2. Terminate Piper TTS Process
        try {
            if (ttsWriter != null) {
                ttsWriter.close();
            }
            if (ttsReader != null) {
                ttsReader.close();
            }
            if (ttsProcess != null) {
                ttsProcess.destroyForcibly();
                ttsProcess = null;
                System.out.println("[Engine] Piper TTS process terminated.");
            }
        } catch (Exception e) {
            System.err.println("[Engine] TTS process destroy error: " + e.getMessage());
        }

        // 3. Stop and close the active speaker stream
        try {
            if (currentSpeakerLine != null) {
                currentSpeakerLine.stop();
                currentSpeakerLine.close();
                currentSpeakerLine = null;
            }
        } catch (Exception e) {}

        // 4. Stop the reminder monitor before closing its database connection.
        try {
            if (reminderScheduler != null) {
                reminderScheduler.shutdownNow();
                reminderScheduler.awaitTermination(2, TimeUnit.SECONDS);
                reminderScheduler = null;
                System.out.println("[Engine] Reminder monitor stopped.");
            }
        } catch (Exception e) {
            System.err.println("[Engine] Reminder monitor shutdown error: " + e.getMessage());
        }

        // 5. Clean up temporary audio files
        try {
            File currentDir = new File(".");
            File[] wavFiles = currentDir.listFiles((dir, name) -> name.startsWith("tts_") && name.endsWith(".wav"));
            if (wavFiles != null) {
                for (File f : wavFiles) {
                    try { f.delete(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // 6. Close H2 Database
        try {
            if (memory != null) {
                memory.closeDatabase();
            }
        } catch (Exception e) {
            System.err.println("[Engine] Database close error: " + e.getMessage());
        }
        
        System.out.println("[Engine] All resources released successfully.");
    }
    
    public FaceVerification getFaceVerifier() {
        return this.faceVerifier;
    }
    
    public TargetDataLine getMicrophone() {
        return this.microphone;
    }
}
