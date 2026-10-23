package com.assistant;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.json.JSONObject;
import org.json.JSONArray;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.mindrot.jbcrypt.BCrypt;

public class SyncServer extends WebSocketServer {
    private Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private MemoryManager memory = new MemoryManager();
    private final java.util.Map<WebSocket, Integer> authenticatedUsers = new ConcurrentHashMap<>();
    private final java.util.Map<WebSocket, Integer> enrollmentUsers = new ConcurrentHashMap<>();

    public SyncServer(int port) {
        super(new InetSocketAddress(port));
    }

    public boolean isAuthenticatedConnection(WebSocket conn) {
        if (conn == null) return false;
        Integer user = authenticatedUsers.get(conn);
        Engine engine = IntelligentAssistant.getEngine();
        return user != null && user == Engine.currentUserId && engine != null && engine.isAuthenticatedUser;
    }

    @Override
    public void broadcast(String message) {
        broadcastForSession(Engine.currentUserId, IntelligentAssistant.currentSession(), message);
    }

    public void broadcastForSession(int userId, long session, String message) {
        synchronized (IntelligentAssistant.class) {
            if (!IntelligentAssistant.isSessionCurrent(userId, session)) return;
            JSONObject event = new JSONObject(message).put("user_id", userId).put("session_id", session);
            for (WebSocket conn : authenticatedUsers.keySet()) {
                if (isAuthenticatedConnection(conn) && conn.isOpen()) {
                    try {
                        conn.send(event.toString());
                    } catch (Exception error) {
                        System.err.println("[WebSocket] Could not deliver an authenticated update.");
                    }
                }
            }
        }
    }

    private boolean isAllowedBeforeLogin(String action) {
        switch (action) {
            case "login":
            case "login_face":
            case "check_setup_status":
            case "logout":
            case "setup_begin":
            case "setup_capture_face":
            case "setup_save_interview":
            case "setup_complete":
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        synchronized (IntelligentAssistant.class) {
            clients.add(conn);
            IntelligentAssistant.invalidateSessionWork();
            authenticatedUsers.clear();
            enrollmentUsers.clear();
            Engine.currentUserId = -1;
            Engine.currentConversationId = -1;
            IntelligentAssistant.getEngine().isAuthenticatedUser = false;
            System.out.println("New Live2D Client Connected: " + conn.getRemoteSocketAddress() + " (authentication required)");
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        synchronized (IntelligentAssistant.class) {
            clients.remove(conn);
            authenticatedUsers.remove(conn);
            enrollmentUsers.remove(conn);
            if (clients.isEmpty()) {
                IntelligentAssistant.invalidateSessionWork();
                Engine.currentUserId = -1;
                Engine.currentConversationId = -1;
                IntelligentAssistant.getEngine().isAuthenticatedUser = false;
            }
            System.out.println("Live2D Client Disconnected");
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        synchronized (IntelligentAssistant.class) {
            try {
                JSONObject json = new JSONObject(message);
                String action = json.optString("action");
                final int actionUserId = Engine.currentUserId;
                final long actionSession = IntelligentAssistant.currentSession();

                boolean authenticated = isAuthenticatedConnection(conn);
                Integer enrollmentUser = enrollmentUsers.get(conn);
                boolean ownsEnrollment = enrollmentUser != null && enrollmentUser == Engine.currentUserId;
                boolean enrollmentAction = "setup_capture_face".equals(action)
                        || "setup_save_interview".equals(action) || "setup_complete".equals(action);
                if ((!isAllowedBeforeLogin(action) && !authenticated)
                        || (enrollmentAction && (!ownsEnrollment
                            || (!"setup_capture_face".equals(action) && authenticated)))) {
                    JSONObject res = new JSONObject();
                    res.put("type", "auth_required");
                    res.put("message", "Please log in to continue.");
                    conn.send(res.toString());
                    return;
                }
                
                if ("login".equals(action)) {
                    IntelligentAssistant.invalidateSessionWork();
                    authenticatedUsers.clear();
                    enrollmentUsers.clear();
                    String email = json.optString("email");
                    String password = json.optString("password");
                    int uid = memory.authenticateEmail(email, password);
                    boolean success = (uid != -1);
                    
                    JSONObject res = new JSONObject();
                    res.put("type", "login_result");
                    res.put("success", success);
                    IntelligentAssistant.getEngine().isAuthenticatedUser = success;
                    if (success) {
                        Engine.currentUserId = uid;
                        authenticatedUsers.put(conn, uid);
                        Engine.currentConversationId = -1;
                        String sessionToken = memory.createSession(uid);
                        res.put("userName", memory.getUserProfileValue(uid, "Name"));
                        res.put("session_token", sessionToken);
                        res.put("user_id", uid);
                        res.put("session_id", IntelligentAssistant.currentSession());
                    }
                    conn.send(res.toString());
                    
                    Engine.isWaitingForBriefingAck = false;
                    if (success && memory.isFirstLoginToday(uid)) {
                        final long loginSession = IntelligentAssistant.currentSession();
                        memory.updateLastLoginToday(uid);
                        Engine.isWaitingForBriefingAck = true;
                        new Thread(() -> {
                            try {
                                Thread.sleep(3000);
                                if (!IntelligentAssistant.isSessionCurrent(uid, loginSession)) return;
                                String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"));
                                String summary = memory.getTodaysRemindersSummary(uid);
                                String emailsStr = EmailService.readLatestEmails(uid, 2);
                                
                                String prompt = "SYSTEM AUTO-PROMPT: The user just launched the application for the first time today. Current time is " + time + ".\n" +
                                                "Here is their schedule for today:\n" + summary + "\n\n" +
                                                "Here are their latest emails:\n" + emailsStr + "\n\n" +
                                                "Please give a brief, cheerful proactive greeting, summarize their day and emails, and inform them they MUST say 'okay' or acknowledge these tasks before you can help them with anything else today. Keep it natural.";
                                IntelligentAssistant.triggerAutonomousPrompt(prompt, uid, Engine.currentConversationId, loginSession);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }).start();
                    }
                } else if ("login_face".equals(action)) {
                    IntelligentAssistant.invalidateSessionWork();
                    authenticatedUsers.clear();
                    enrollmentUsers.clear();
                    final long loginSession = IntelligentAssistant.currentSession();
                    String base64Image = json.optString("image_data");
                    new Thread(() -> {
                        try {
                            float[] currentFace = IntelligentAssistant.getEngine().getFaceVerifier().extractFaceprintFromBase64(base64Image);
                            java.util.Map<Integer, float[]> allFaces = memory.getAllUserFaceprints();
                            int matchedUser = FaceVerification.findMatchingUser(currentFace, allFaces);
                            synchronized (IntelligentAssistant.class) {
                            if (loginSession != IntelligentAssistant.currentSession() || !conn.isOpen()) return;
                            boolean success = matchedUser > 0;
                            JSONObject res = new JSONObject();
                            res.put("type", "login_result");
                            res.put("success", success);
                            IntelligentAssistant.getEngine().isAuthenticatedUser = success;
                            if (success) {
                                Engine.currentUserId = matchedUser;
                                authenticatedUsers.put(conn, matchedUser);
                                Engine.currentConversationId = -1;
                                Engine.isWaitingForBriefingAck = false;
                                String sessionToken = memory.createSession(Engine.currentUserId);
                                res.put("userName", memory.getUserProfileValue(matchedUser, "Name"));
                                res.put("session_token", sessionToken);
                                res.put("user_id", Engine.currentUserId);
                                res.put("session_id", loginSession);
                            } else {
                                Engine.currentUserId = -1;
                                Engine.currentConversationId = -1;
                                Engine.isWaitingForBriefingAck = false;
                                String reason = !IntelligentAssistant.getEngine().getFaceVerifier().isReady()
                                        ? "Face recognition is unavailable. Please use email login."
                                        : currentFace == null
                                        ? "Please show one clear face, look at the camera, and use good lighting."
                                        : allFaces.isEmpty()
                                        ? "Face ID needs enrollment. Log in with email and select Update Face ID."
                                        : "Face not recognized. Please use email login or register your own account.";
                                res.put("message", reason);
                            }
                            conn.send(res.toString());
                            }
                        } catch (Throwable e) {
                            synchronized (IntelligentAssistant.class) {
                            if (loginSession != IntelligentAssistant.currentSession()) return;
                            Engine.currentUserId = -1;
                            Engine.currentConversationId = -1;
                            IntelligentAssistant.getEngine().isAuthenticatedUser = false;
                            conn.send(new JSONObject().put("type", "login_result").put("success", false)
                                    .put("message", "Face verification failed. Please try email login.").toString());
                            }
                        }
                    }).start();
                } else if ("begin_face_reenrollment".equals(action)) {
                    boolean success = IntelligentAssistant.getEngine().beginFaceReenrollment();
                    if (success) enrollmentUsers.put(conn, Engine.currentUserId);
                    conn.send(new JSONObject().put("type", "face_reenrollment_started")
                            .put("success", success).toString());
                } else if ("check_setup_status".equals(action)) {
                    boolean firstRun = memory.isFirstRun();
                    JSONObject res = new JSONObject();
                    res.put("type", "setup_status");
                    res.put("isFirstRun", firstRun);
                    conn.send(res.toString());
                } else if ("setup_begin".equals(action)) {
                    IntelligentAssistant.invalidateSessionWork();
                    authenticatedUsers.clear();
                    enrollmentUsers.clear();
                    int userId = IntelligentAssistant.getEngine().beginNewUserRegistration();
                    if (userId > 0) enrollmentUsers.put(conn, userId);
                    JSONObject res = new JSONObject();
                    res.put("type", "setup_started");
                    res.put("success", userId > 0);
                    res.put("user_id", userId);
                    conn.send(res.toString());
                } else if ("setup_capture_face".equals(action)) {
                    String angle = json.optString("angle", "front");
                    String base64Image = json.optString("image_data");
                    new Thread(() -> {
                        try {
                            JSONObject result = IntelligentAssistant.getEngine().captureSetupFaceAngle(enrollmentUser, angle, base64Image);
                            JSONObject res = new JSONObject();
                            res.put("type", "setup_face_result");
                            res.put("data", result);
                            conn.send(res.toString());
                        } catch (Throwable ex) {
                            JSONObject err = new JSONObject();
                            err.put("type", "setup_face_result");
                            err.put("data", new JSONObject().put("success", false).put("error", ex.toString()));
                            conn.send(err.toString());
                        }
                    }).start();
                } else if ("setup_save_interview".equals(action)) {
                    String key = json.optString("key");
                    String value = json.optString("value");
                    JSONObject result = IntelligentAssistant.getEngine().saveOnboardingAnswer(key, value);
                    JSONObject res = new JSONObject();
                    res.put("type", "setup_interview_result");
                    res.put("data", result);
                    conn.send(res.toString());
                } else if ("setup_complete".equals(action)) {
                    String name = memory.getUserProfileValue(Engine.currentUserId, "Name");
                    if (name == null || name.trim().isEmpty()) {
                        JSONObject res = new JSONObject();
                        res.put("type", "setup_error");
                        res.put("message", "Please provide a name before completing setup.");
                        conn.send(res.toString());
                        return;
                    }
                    String welcomeMsg = "Setup complete! It's wonderful to meet you, " + name + ". Your account is ready. How can I help you today?";
                    IntelligentAssistant.queueSpeech(welcomeMsg);
                    IntelligentAssistant.queueSpeech("[[END]]");
                    
                    // Mark registration as complete and create persistent session
                    memory.markRegistrationComplete(Engine.currentUserId);
                    String sessionToken = memory.createSession(Engine.currentUserId);
                    IntelligentAssistant.getEngine().isAuthenticatedUser = true;
                    authenticatedUsers.put(conn, Engine.currentUserId);
                    enrollmentUsers.remove(conn);
                    
                    JSONObject res = new JSONObject();
                    res.put("type", "setup_complete_ack");
                    res.put("welcomeMsg", welcomeMsg);
                    res.put("userName", name);
                    res.put("session_token", sessionToken);
                    res.put("user_id", Engine.currentUserId);
                    res.put("session_id", IntelligentAssistant.currentSession());
                    conn.send(res.toString());
                } else if ("reset_memory".equals(action)) {
                    boolean success = IntelligentAssistant.resetTargetedMemory();
                    JSONObject res = new JSONObject();
                    res.put("type", "memory_reset_result");
                    res.put("success", success);
                    res.put("user_id", actionUserId);
                    res.put("previous_session_id", actionSession);
                    res.put("session_id", IntelligentAssistant.currentSession());
                    res.put("message", success
                            ? "Your memory and conversation history have been cleared."
                            : "Memory reset could not be fully completed. Please try again.");
                    conn.send(res.toString());
                } else if ("shutdown".equals(action)) {
                    System.out.println("[WebSocket] Shutdown request received from client tab.");
                    new Thread(() -> {
                        try { Thread.sleep(200); } catch (Exception ignored) {}
                        IntelligentAssistant.shutdownSystem();
                    }).start();
                } else if ("check_session".equals(action)) {
                    // Security: do not silently revive a previous session without a fresh credentialed login.
                    // A valid session may exist only after a successful login event, and it must be explicitly
                    // re-established after the user exits or refreshes the app.
                    String token = json.optString("session_token", "");
                    int uid = memory.getUserBySession(token);
                    JSONObject res = new JSONObject();
                    res.put("type", "session_result");
                    if (uid > 0 && token != null && !token.trim().isEmpty()) {
                        Engine.currentUserId = uid;
                        Engine.currentConversationId = -1;
                        Engine.isWaitingForBriefingAck = false;
                        IntelligentAssistant.getEngine().isAuthenticatedUser = true;
                        String userName = memory.getUserProfileValue(uid, "Name");
                        res.put("valid", true);
                        res.put("user_id", uid);
                        res.put("userName", userName != null ? userName : "User");
                        System.out.println("[Session] Restored session for user " + uid + " (" + userName + ")");
                    } else {
                        IntelligentAssistant.getEngine().isAuthenticatedUser = false;
                        res.put("valid", false);
                        res.put("message", "Please log in again to continue.");
                    }
                    conn.send(res.toString());
                } else if ("logout".equals(action)) {
                    IntelligentAssistant.invalidateSessionWork();
                    authenticatedUsers.clear();
                    enrollmentUsers.clear();
                    // ─── LOGOUT: Invalidate session and reset state ───
                    if (Engine.currentUserId > 0) {
                        memory.invalidateSession(Engine.currentUserId);
                    }
                    Engine.currentUserId = -1;
                    Engine.currentConversationId = -1;
                    Engine.isWaitingForBriefingAck = false;
                    IntelligentAssistant.getEngine().isAuthenticatedUser = false;
                    JSONObject res = new JSONObject();
                    res.put("type", "logout_result");
                    res.put("success", true);
                    conn.send(res.toString());
                } else if ("get_conversations".equals(action)) {
                    // ─── LIST CONVERSATIONS for the current user ───
                    if (Engine.currentUserId <= 0) {
                        JSONObject res = new JSONObject();
                        res.put("type", "conversations_list");
                        res.put("conversations", new JSONArray());
                        conn.send(res.toString());
                        return;
                    }
                    JSONArray conversations = memory.getConversationsForUser(actionUserId);
                    JSONObject res = new JSONObject();
                    res.put("type", "conversations_list");
                    res.put("conversations", conversations);
                    res.put("user_id", actionUserId);
                    res.put("session_id", actionSession);
                    conn.send(res.toString());
                } else if ("get_messages".equals(action)) {
                    // ─── LOAD MESSAGES for a specific conversation ───
                    int convId = json.optInt("conversation_id", -1);
                    if (convId > 0 && memory.conversationBelongsToUser(convId, actionUserId)
                            && IntelligentAssistant.isSessionCurrent(actionUserId, actionSession)) {
                        Engine.currentConversationId = convId;
                        JSONArray messages = memory.getMessagesForConversation(convId);
                        JSONObject res2 = new JSONObject();
                        res2.put("type", "messages_list");
                        res2.put("conversation_id", convId);
                        res2.put("messages", messages);
                        res2.put("user_id", actionUserId);
                        res2.put("session_id", actionSession);
                        conn.send(res2.toString());
                    }
                } else if ("new_conversation".equals(action)) {
                    // ─── CREATE NEW CONVERSATION ───
                    if (Engine.currentUserId > 0) {
                        String title = json.optString("title", "New Conversation");
                        int convId = memory.createConversation(Engine.currentUserId, title);
                        Engine.currentConversationId = convId;
                        JSONObject res2 = new JSONObject();
                        res2.put("type", "conversation_created");
                        res2.put("conversation_id", convId);
                        res2.put("title", title);
                        conn.send(res2.toString());
                    }
                } else if ("send_chat".equals(action)) {
                    submitChat(conn, json.optString("text", ""));
                } else if ("interrupt".equals(action)) {
                    IntelligentAssistant.interrupt();
                } else if ("get_prm_contacts".equals(action)) {
                    final int userId = Engine.currentUserId;
                    new Thread(() -> {
                        try {
                            JSONArray contacts = memory.getAllPRMContacts(userId);
                            JSONObject res = new JSONObject();
                            res.put("type", "prm_contacts_list");
                            res.put("contacts", contacts);
                            conn.send(res.toString());
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }).start();
                } else if ("save_prm_attribute".equals(action)) {
                    int userId = Engine.currentUserId;
                    String name = json.optString("name");
                    String relationship = json.optString("relationship", "Acquaintance");
                    String category = json.optString("category", "General");
                    String description = json.optString("description");
                    memory.savePRMContact(userId, name, relationship, category, description);

                    JSONArray contacts = memory.getAllPRMContacts(userId);
                    JSONObject res = new JSONObject();
                    res.put("type", "prm_contacts_list");
                    res.put("contacts", contacts);
                    conn.send(res.toString());
                } else if ("delete_prm_attribute".equals(action)) {
                    int userId = Engine.currentUserId;
                    int attrId = json.optInt("attribute_id", -1);
                    if (attrId != -1) {
                        memory.deletePRMAttribute(userId, attrId);
                    }
                    JSONArray contacts = memory.getAllPRMContacts(userId);
                    JSONObject res = new JSONObject();
                    res.put("type", "prm_contacts_list");
                    res.put("contacts", contacts);
                    conn.send(res.toString());
                } else if ("prm_generate_strategy".equals(action)) {
                    final int userId = Engine.currentUserId;
                    String contactName = json.optString("name");
                    String goal = json.optString("goal");
                    new Thread(() -> {
                        try {
                            JSONObject strategyResult = IntelligentAssistant.getEngine().generateTacticalStrategy(userId, contactName, goal);
                            JSONObject res = new JSONObject();
                            res.put("type", "prm_strategy_result");
                            res.put("data", strategyResult);
                            conn.send(res.toString());
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }).start();
                } else if ("get_knowledge_graph".equals(action)) {
                    final int userId = Engine.currentUserId;
                    new Thread(() -> {
                        try {
                            JSONObject kg = memory.getCompleteKnowledgeGraph(userId);
                            JSONObject res = new JSONObject();
                            res.put("type", "knowledge_graph_data");
                            res.put("graph", kg);
                            conn.send(res.toString());
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }).start();
                } else if ("save_knowledge_edge".equals(action)) {
                    int userId = Engine.currentUserId;
                    String src = json.optString("source");
                    String srcType = json.optString("source_type", "Concept");
                    String tgt = json.optString("target");
                    String tgtType = json.optString("target_type", "Concept");
                    String rel = json.optString("relation", "related_to");
                    String ctx = json.optString("context", "");
                    memory.addKnowledgeEdge(userId, src, srcType, tgt, tgtType, rel, ctx);

                    JSONObject kg = memory.getCompleteKnowledgeGraph(userId);
                    JSONObject res = new JSONObject();
                    res.put("type", "knowledge_graph_data");
                    res.put("graph", kg);
                    conn.send(res.toString());
                } else if ("delete_knowledge_node".equals(action)) {
                    int userId = Engine.currentUserId;
                    int nodeId = json.optInt("node_id", -1);
                    if (nodeId != -1) {
                        memory.deleteKnowledgeNode(userId, nodeId);
                    }
                    JSONObject kg = memory.getCompleteKnowledgeGraph(userId);
                    JSONObject res = new JSONObject();
                    res.put("type", "knowledge_graph_data");
                    res.put("graph", kg);
                    conn.send(res.toString());
                } else if ("get_reminders".equals(action)) {
                    int userId = Engine.currentUserId;
                    JSONObject res = new JSONObject();
                    res.put("type", "reminders_list");
                    org.json.JSONArray items = new org.json.JSONArray();
                    try (java.sql.Connection c = java.sql.DriverManager.getConnection(com.assistant.MemoryManager.getDbPath());
                         java.sql.PreparedStatement st = c.prepareStatement(
                             "SELECT scheduled_time, task_description, is_completed FROM reminders WHERE user_id = ? ORDER BY scheduled_time DESC")) {
                        st.setInt(1, userId);
                        try (java.sql.ResultSet rs = st.executeQuery()) {
                            while (rs.next()) {
                                JSONObject item = new JSONObject();
                                item.put("time", rs.getString("scheduled_time"));
                                boolean done = rs.getInt("is_completed") == 1;
                                String label = (done ? "✅ " : "⏰ ") + rs.getString("task_description");
                                item.put("task", label);
                                item.put("done", done);
                                items.put(item);
                            }
                        }
                    } catch (Exception ex) { ex.printStackTrace(); }
                    res.put("items", items);
                    conn.send(res.toString());
                } else if ("get_emails".equals(action)) {
                    // Fetch emails in a background thread so we don't block the WebSocket
                    final int userId = Engine.currentUserId;
                    new Thread(() -> {
                        try {
                            JSONObject res = new JSONObject();
                            res.put("type", "email_list");
                            org.json.JSONArray emails = new org.json.JSONArray();

                            java.util.Properties props = new java.util.Properties();
                            props.put("mail.store.protocol", "imaps");
                            props.put("mail.imaps.host", "imap.gmail.com");
                            props.put("mail.imaps.port", "993");
                            props.put("mail.imaps.ssl.enable", "true");
                            props.put("mail.imaps.auth.mechanisms", "PLAIN LOGIN");

                            javax.mail.Session session = javax.mail.Session.getInstance(props, null);
                            javax.mail.Store store = session.getStore("imaps");
                            String email = EmailService.getSenderEmail(userId);
                            String appPassword = EmailService.getSenderPassword(userId);
                            if (email.isEmpty() || appPassword.isEmpty()) {
                                throw new IllegalStateException("Gmail address or app password is not configured for this user.");
                            }
                            store.connect(email, appPassword);

                            javax.mail.Folder inbox = store.getFolder("INBOX");
                            inbox.open(javax.mail.Folder.READ_ONLY);

                            javax.mail.Message[] messages = inbox.getMessages();
                            int limit = Math.min(10, messages.length);
                            for (int i = messages.length - 1; i >= messages.length - limit; i--) {
                                javax.mail.Message msg = messages[i];
                                JSONObject emailObj = new JSONObject();

                                String from = javax.mail.internet.InternetAddress.toString(msg.getFrom());
                                String senderName = from;
                                String senderEmail = from;
                                if (from.contains("<")) {
                                    senderName = from.substring(0, from.indexOf("<")).trim().replace("\"", "");
                                    senderEmail = from.substring(from.indexOf("<") + 1, from.indexOf(">")).trim();
                                }
                                if (senderName.isEmpty()) senderName = senderEmail;

                                emailObj.put("from_name", senderName);
                                emailObj.put("from_email", senderEmail);
                                emailObj.put("subject", msg.getSubject() != null ? msg.getSubject() : "(No Subject)");
                                emailObj.put("date", msg.getSentDate() != null ? msg.getSentDate().toString() : "");
                                emails.put(emailObj);
                            }
                            inbox.close(false);
                            store.close();

                            res.put("emails", emails);
                            res.put("error", false);
                            conn.send(res.toString());
                        } catch (Exception ex) {
                            try {
                                JSONObject err = new JSONObject();
                                err.put("type", "email_list");
                                err.put("error", true);
                                err.put("message", ex.getMessage());
                                conn.send(err.toString());
                            } catch (Exception ignored) {}
                        }
                    }).start();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Stores and dispatches a user turn for both typed messages and recognized speech.
     */
    public boolean submitChat(WebSocket conn, String rawUserText) {
        synchronized (IntelligentAssistant.class) {
            String userText = rawUserText == null ? "" : rawUserText.trim();
            if (userText.isEmpty()) {
                return false;
            }

            Engine assistantEngine = IntelligentAssistant.getEngine();
            if (Engine.currentUserId <= 0 || assistantEngine == null || !isAuthenticatedConnection(conn)) {
                sendChatError(conn, "Please log in before sending a message.");
                return false;
            }

            try {
                int conversationId = Engine.currentConversationId;
                if (conversationId > 0 && !memory.conversationBelongsToUser(conversationId, Engine.currentUserId)) {
                    conversationId = -1;
                }

                if (conversationId <= 0) {
                    conversationId = memory.createConversation(Engine.currentUserId, "New Conversation");
                    if (conversationId <= 0) {
                        sendChatError(conn, "I could not start a conversation. Please try again.");
                        return false;
                    }

                    Engine.currentConversationId = conversationId;
                    JSONObject conversationCreated = new JSONObject();
                    conversationCreated.put("type", "conversation_created");
                    conversationCreated.put("conversation_id", conversationId);
                    conversationCreated.put("title", "New Conversation");
                    conversationCreated.put("user_id", Engine.currentUserId);
                    conversationCreated.put("session_id", IntelligentAssistant.currentSession());
                    if (conn != null && conn.isOpen()) {
                        conn.send(conversationCreated.toString());
                    }
                }

                memory.saveMessage(conversationId, "user", userText);
                memory.updateConversationTitle(conversationId, userText);
                memory.logConversation(Engine.currentUserId, "User", userText, "unknown");
                Engine.currentConversationId = conversationId;

                IntelligentAssistant.processUserCommand(userText, Engine.currentUserId, conversationId, IntelligentAssistant.currentSession());
                return true;
            } catch (Exception e) {
                System.err.println("[Chat] Unable to submit message: " + e.getMessage());
                sendChatError(conn, "Your message could not be sent. Please try again.");
                return false;
            }
        }
    }

    private void sendChatError(WebSocket conn, String message) {
        if (conn == null || !conn.isOpen()) {
            return;
        }

        try {
            JSONObject error = new JSONObject();
            error.put("type", "chat_error");
            error.put("message", message);
            conn.send(error.toString());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("Live2D WebSocket Sync Server Started on port: " + getPort());
    }

    public void broadcastState(String mood, boolean speaking) {
        try {
            JSONObject state = new JSONObject();
            state.put("type", "state");  // Tag it so frontend can identify it cleanly
            state.put("mood", mood);
            state.put("speaking", speaking);
            broadcast(state.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public void broadcastChat(String sender, String text) {
        try {
            JSONObject chatMsg = new JSONObject();
            chatMsg.put("type", "chat");
            chatMsg.put("sender", sender);
            chatMsg.put("text", text);
            broadcast(chatMsg.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Tells the frontend to open a new empty AI message bubble
    public void broadcastChatStart() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "chat_start");
            msg.put("sender", "AI");
            broadcast(msg.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Appends a chunk of text to the currently open AI bubble
    public void broadcastChatToken(String token) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "chat_token");
            msg.put("text", token);
            broadcast(msg.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Explicitly forces the UI to reset back to READY
    public void broadcastReset() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "reset");
            broadcast(msg.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

        public void broadcastWakeState(String state) {
        JSONObject json = new JSONObject();
        json.put("type", "wake_state");
        json.put("state", state);
        broadcast(json.toString());
    }

}
