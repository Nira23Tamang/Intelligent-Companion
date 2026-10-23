package com.assistant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONObject;
import org.json.JSONArray;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import org.mindrot.jbcrypt.BCrypt;

public class MemoryManager {

    private String ollamaUrl = "http://localhost:11434/api/generate";
    private String model = "qwen2.5:3b";
    private HttpClient client;
    private static final DateTimeFormatter REMINDER_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MemoryManager() {
        this.client = HttpClient.newHttpClient();
        setupDatabase();
    }
    
    private void setupDatabase() {
        try (Connection conn = getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            // Users (Core Entity)
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                               "user_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "name TEXT, " +
                               "role TEXT)");

            // PRM Contacts
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS contacts (" +
                               "contact_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "name TEXT UNIQUE, " +
                               "relationship_type TEXT)");
                               
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS contact_attributes (" +
                               "attribute_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "contact_id INTEGER, " +
                               "category TEXT, " +
                               "description TEXT, " +
                               "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (contact_id) REFERENCES contacts(contact_id) ON DELETE CASCADE)");
                               
            // Conversation Logs
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS conversation_logs (" +
                               "id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER, " +
                               "speaker TEXT, " +
                               "text TEXT, " +
                               "emotion TEXT, " +
                               "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");
                               
            // Reminders
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS reminders (" +
                               "id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER, " +
                               "contact_id INTEGER, " +
                               "task_description TEXT, " +
                               "scheduled_time DATETIME, " +
                               "is_completed BOOLEAN DEFAULT 0, " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE, " +
                               "FOREIGN KEY (contact_id) REFERENCES contacts(contact_id) ON DELETE SET NULL)");
                               
            // App State (Key-Value - Global)
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS app_state (" +
                               "key_name TEXT PRIMARY KEY, " +
                               "key_value TEXT)");
                               
            // Remove voice-login storage left by older versions.
            stmt.executeUpdate("DROP TABLE IF EXISTS voiceprints");

            // Faceprints
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS faceprints (" +
                               "id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER UNIQUE, " +
                               "embedding TEXT, " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");
                               
            // User Profile (for onboarding calibration)
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_profile (" +
                               "id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER, " +
                               "attribute_key TEXT, " +
                               "attribute_value TEXT, " +
                               "UNIQUE(user_id, attribute_key), " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            // User-scoped social intelligence. The older contacts tables are retained for
            // backwards-compatible reminders, but are never used for companion memory.
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_contacts (" +
                               "contact_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER NOT NULL, " +
                               "name TEXT NOT NULL, " +
                               "relationship_type TEXT, " +
                               "UNIQUE(user_id, name), " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_contact_attributes (" +
                               "attribute_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "contact_id INTEGER NOT NULL, " +
                               "category TEXT, " +
                               "description TEXT, " +
                               "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (contact_id) REFERENCES user_contacts(contact_id) ON DELETE CASCADE)");
            
            // ─── KNOWLEDGE GRAPH NODES & EDGES (SEMANTIC NETWORK) ───────────
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS knowledge_nodes (" +
                               "node_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "name VARCHAR(255) UNIQUE NOT NULL, " +
                               "node_type VARCHAR(50) NOT NULL, " +
                               "description TEXT, " +
                               "created_at DATETIME DEFAULT CURRENT_TIMESTAMP)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS knowledge_edges (" +
                               "edge_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "source_node_id INTEGER NOT NULL, " +
                               "target_node_id INTEGER NOT NULL, " +
                               "relation_type VARCHAR(100) NOT NULL, " +
                               "context TEXT, " +
                               "weight FLOAT DEFAULT 1.0, " +
                               "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (source_node_id) REFERENCES knowledge_nodes(node_id) ON DELETE CASCADE, " +
                               "FOREIGN KEY (target_node_id) REFERENCES knowledge_nodes(node_id) ON DELETE CASCADE, " +
                               "UNIQUE(source_node_id, target_node_id, relation_type))");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_knowledge_nodes (" +
                               "node_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER NOT NULL, " +
                               "name VARCHAR(255) NOT NULL, " +
                               "node_type VARCHAR(50) NOT NULL, " +
                               "description TEXT, " +
                               "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "UNIQUE(user_id, name), " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_knowledge_edges (" +
                               "edge_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER NOT NULL, " +
                               "source_node_id INTEGER NOT NULL, " +
                               "target_node_id INTEGER NOT NULL, " +
                               "relation_type VARCHAR(100) NOT NULL, " +
                               "context TEXT, " +
                               "weight FLOAT DEFAULT 1.0, " +
                               "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE, " +
                               "FOREIGN KEY (source_node_id) REFERENCES user_knowledge_nodes(node_id) ON DELETE CASCADE, " +
                               "FOREIGN KEY (target_node_id) REFERENCES user_knowledge_nodes(node_id) ON DELETE CASCADE, " +
                               "UNIQUE(user_id, source_node_id, target_node_id, relation_type))");

            // No default account is seeded here. Authentication must be based on an actual stored user
            // and a fresh login, not on a hard-coded user_id = 1 fallback.

            // ─── SCHEMA MIGRATION: Add new columns to users table ─────────────
            try { stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS email TEXT"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash TEXT"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS face_image_path TEXT"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE users DROP COLUMN IF EXISTS voice_recording_path"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS registration_complete BOOLEAN DEFAULT FALSE"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at DATETIME DEFAULT CURRENT_TIMESTAMP"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login DATETIME"); } catch (Exception ignored) {}

            // ─── SCHEMA MIGRATION: Fix missing columns from old versions ─────────────
            try { stmt.executeUpdate("ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE faceprints ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}
            stmt.executeUpdate("ALTER TABLE faceprints ADD COLUMN IF NOT EXISTS embedding_version VARCHAR(32) DEFAULT 'legacy-pixel-v1'");
            try { stmt.executeUpdate("ALTER TABLE reminders ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE semantic_rag_memory ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}

            // ─── NEW TABLE: Conversations ──────────────────────────────────────
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS conversations (" +
                               "conversation_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER NOT NULL, " +
                               "title TEXT DEFAULT 'New Conversation', " +
                               "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            // ─── NEW TABLE: Messages ───────────────────────────────────────────
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS messages (" +
                               "message_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "conversation_id INTEGER NOT NULL, " +
                               "role TEXT NOT NULL, " +
                               "content TEXT NOT NULL, " +
                               "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id) ON DELETE CASCADE)");

            // ─── NEW TABLE: User Long-Term Memory ─────────────────────────────
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_memories (" +
                               "memory_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER NOT NULL, " +
                               "memory_key TEXT, " +
                               "memory_value TEXT NOT NULL, " +
                               "source TEXT, " +
                               "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            // ─── NEW TABLE: Persistent Sessions ───────────────────────────────
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_sessions (" +
                               "session_id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                               "user_id INTEGER NOT NULL UNIQUE, " +
                               "session_token TEXT NOT NULL, " +
                               "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                               "expires_at DATETIME, " +
                               "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");

            // Do not seed a global social graph: relationship knowledge must belong to one user.
        } catch (Exception e) {
            System.err.println("[DB] Failed to create tables: " + e.getMessage());
        }
        removeLegacyVoiceEnrollmentRecordings();
    }

    public static String getDbPath() {
        // Always resolve the DB relative to the JavaApp folder, regardless of working directory
        try {
            String rawPath = MemoryManager.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            String decodedPath = java.net.URLDecoder.decode(rawPath, "UTF-8");
            String base = new java.io.File(decodedPath).getParentFile().getParentFile().getAbsolutePath();
            return "jdbc:h2:" + base + java.io.File.separator + "intelligent_assistant;AUTO_SERVER=TRUE";
        } catch (Exception e) {
            return "jdbc:h2:./intelligent_assistant;AUTO_SERVER=TRUE"; // Fallback
        }
    }

    public Connection getConnection() throws Exception {
        return DriverManager.getConnection(getDbPath());
    }

    // ─── USER PROFILE (Onboarding) ────────────────────────────────────────────

    public boolean isFirstRun() {
        try (Connection conn = getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM user_profile");
            if (rs.next()) return rs.getInt(1) == 0;
        } catch (Exception e) {
            System.err.println("[Profile] isFirstRun check failed: " + e.getMessage());
        }
        return true;
    }

    public String getUserProfileValue(int userId, String key) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT attribute_value FROM user_profile WHERE user_id = ? AND attribute_key = ?");
            stmt.setInt(1, userId);
            stmt.setString(2, key);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getString("attribute_value");
        } catch (Exception e) {
            System.err.println("[Profile] Get key failed: " + e.getMessage());
        }
        return "";
    }

    public void saveUserProfile(int userId, String key, String value) {
        try (Connection conn = getConnection()) {
            String valToSave = "Password".equals(key) ? BCrypt.hashpw(value, BCrypt.gensalt()) : value;
            String normalizedKey = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);

            // Try to update first
            PreparedStatement updateStmt = conn.prepareStatement(
                "UPDATE user_profile SET attribute_value = ? WHERE user_id = ? AND attribute_key = ?"
            );
            updateStmt.setString(1, valToSave);
            updateStmt.setInt(2, userId);
            updateStmt.setString(3, key);
            int updated = updateStmt.executeUpdate();

            if (updated == 0) {
                // If not updated, insert
                PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO user_profile (user_id, attribute_key, attribute_value) VALUES (?, ?, ?)"
                );
                insertStmt.setInt(1, userId);
                insertStmt.setString(2, key);
                insertStmt.setString(3, valToSave);
                insertStmt.executeUpdate();
            }
            boolean sensitive = normalizedKey.contains("password") || normalizedKey.contains("email") ||
                    normalizedKey.contains("gmail") || normalizedKey.contains("token");
            System.out.println("[Profile] Saved: " + key + " = " + (sensitive ? "[private]" : value));
        } catch (Exception e) {
            System.err.println("[Profile] Save failed: " + e.getMessage());
        }
    }

    public String getUserProfileSummary(int userId) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT attribute_key, attribute_value FROM user_profile WHERE user_id = ?");
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String k = rs.getString("attribute_key");
                String normalizedKey = k == null ? "" : k.toLowerCase(java.util.Locale.ROOT);
                if (normalizedKey.contains("password") || normalizedKey.contains("email") ||
                        normalizedKey.contains("gmail") || normalizedKey.contains("token")) continue;
                sb.append("- ").append(k)
                  .append(": ").append(rs.getString("attribute_value")).append("\n");
            }
        } catch (Exception e) {
            System.err.println("[Profile] Load failed: " + e.getMessage());
        }
        return sb.toString();
    }

    public void updateUserName(int userId, String name) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("UPDATE users SET name = ? WHERE user_id = ?");
            stmt.setString(1, name);
            stmt.setInt(2, userId);
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Profile] Could not update user name: " + e.getMessage());
        }
    }

    public boolean isFirstLoginToday(int userId) {
        String lastLogin = getUserProfileValue(userId, "last_login_date");
        String today = java.time.LocalDate.now().toString();
        return !today.equals(lastLogin);
    }

    public void updateLastLoginToday(int userId) {
        String today = java.time.LocalDate.now().toString();
        saveUserProfile(userId, "last_login_date", today);
    }

    public void logConversation(int userId, String speaker, String text, String emotion) {
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO conversation_logs (user_id, speaker, text, emotion) VALUES (?, ?, ?, ?)";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setInt(1, userId);
            stmt.setString(2, speaker);
            stmt.setString(3, text);
            stmt.setString(4, emotion);
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[Memory] DB Logging failed: " + e.getMessage());
        }
    }

    public void analyzeAndExtractPRM(String userText) {
        if (userText == null || userText.split(" ").length < 3) return;

        try {
            String prompt = "You are an advanced Cognitive Knowledge Graph & Semantic Network extractor.\n" +
                            "Analyze this user conversation utterance: \"" + userText + "\"\n\n" +
                            "1. Identify people, projects, concepts, triggers, family members, habits, or goals.\n" +
                            "2. Extract directed Semantic Triplets in format: (Subject, SubjectType, Relation, Object, ObjectType, Context)\n" +
                            "Types can be: Person, Project, Concept, Trigger, Family, Preference, Goal.\n" +
                            "Relations can be: works_with, has_child, married_to, stressed_by, prefers, promised_to, owes, advises, threatens, manages, likes.\n\n" +
                            "If no clear relationships or entities exist, output: {\"detected\": false}\n\n" +
                            "If relationships exist, output JSON in this exact structure:\n" +
                            "{\"detected\": true, \"triplets\": [{\"subject\": \"Alex\", \"subject_type\": \"Person\", \"relation\": \"has_daughter\", \"object\": \"Maya\", \"object_type\": \"Family\", \"context\": \"applying to college\"}]}";

            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("prompt", prompt);
            payload.put("stream", false);
            payload.put("format", "json");
            JSONObject options = new JSONObject();
            options.put("temperature", 0.1);
            payload.put("options", options);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(response.body());
            String extracted = json.optString("response", "{}");

            if (extracted.contains("{") && extracted.contains("}")) {
                extracted = extracted.substring(extracted.indexOf("{"), extracted.lastIndexOf("}") + 1);
                JSONObject prmData = new JSONObject(extracted);
                if (prmData.optBoolean("detected", false) && prmData.has("triplets")) {
                    JSONArray triplets = prmData.getJSONArray("triplets");
                    for (int i = 0; i < triplets.length(); i++) {
                        JSONObject t = triplets.getJSONObject(i);
                        String sub = t.optString("subject", "").trim();
                        String subType = t.optString("subject_type", "Concept").trim();
                        String rel = t.optString("relation", "related_to").trim();
                        String obj = t.optString("object", "").trim();
                        String objType = t.optString("object_type", "Concept").trim();
                        String ctx = t.optString("context", "").trim();

                        if (!sub.isEmpty() && !obj.isEmpty()) {
                            addKnowledgeEdge(sub, subType, obj, objType, rel, ctx);

                            // Also mirror into legacy contact attributes for backward compatibility
                            if (subType.equalsIgnoreCase("Person")) {
                                savePRMContact(sub, "Contact", rel, obj + (ctx.isEmpty() ? "" : " (" + ctx + ")"));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PRM] Triplet extraction failed: " + e.getMessage());
        }
    }

    public void savePRMContact(String name, String relationship, String category, String description) {
        if (name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty()) return;
        name = name.trim();
        relationship = (relationship == null || relationship.trim().isEmpty()) ? "Acquaintance" : relationship.trim();
        category = (category == null || category.trim().isEmpty()) ? "General" : category.trim();
        description = description.trim();

        try (Connection conn = getConnection()) {
            String updateContact = "UPDATE contacts SET relationship_type = ? WHERE name = ?";
            try (PreparedStatement updateStmt = conn.prepareStatement(updateContact)) {
                updateStmt.setString(1, relationship);
                updateStmt.setString(2, name);
                int updated = updateStmt.executeUpdate();
                if (updated == 0) {
                    String insertContact = "INSERT INTO contacts (name, relationship_type) VALUES (?, ?)";
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertContact)) {
                        insertStmt.setString(1, name);
                        insertStmt.setString(2, relationship);
                        insertStmt.executeUpdate();
                    }
                }
            }

            String sqlGetId = "SELECT contact_id FROM contacts WHERE name = ?";
            int contactId = -1;
            try (PreparedStatement stmtGetId = conn.prepareStatement(sqlGetId)) {
                stmtGetId.setString(1, name);
                try (ResultSet rs = stmtGetId.executeQuery()) {
                    if (rs.next()) {
                        contactId = rs.getInt("contact_id");
                    }
                }
            }

            if (contactId != -1) {
                // Prevent duplicate attributes for the same contact
                String checkSql = "SELECT COUNT(*) FROM contact_attributes WHERE contact_id = ? AND category = ? AND description = ?";
                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setInt(1, contactId);
                    checkStmt.setString(2, category);
                    checkStmt.setString(3, description);
                    try (ResultSet rsCheck = checkStmt.executeQuery()) {
                        if (rsCheck.next() && rsCheck.getInt(1) > 0) {
                            return; // Already exists
                        }
                    }
                }

                String sqlAttr = "INSERT INTO contact_attributes (contact_id, category, description) VALUES (?, ?, ?)";
                try (PreparedStatement stmtAttr = conn.prepareStatement(sqlAttr)) {
                    stmtAttr.setInt(1, contactId);
                    stmtAttr.setString(2, category);
                    stmtAttr.setString(3, description);
                    stmtAttr.executeUpdate();
                }
                System.out.println("[PRM] Stored social intelligence attribute for " + name + " [" + category + "]: " + description);
            }
        } catch (Exception e) {
            System.err.println("[PRM] Database save failed: " + e.getMessage());
        }
    }

    public JSONArray getAllPRMContacts() {
        JSONArray result = new JSONArray();
        try (Connection conn = getConnection()) {
            String sql = "SELECT contact_id, name, relationship_type FROM contacts ORDER BY name ASC";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int contactId = rs.getInt("contact_id");
                    String name = rs.getString("name");
                    String rel = rs.getString("relationship_type");

                    JSONObject contactObj = new JSONObject();
                    contactObj.put("contact_id", contactId);
                    contactObj.put("name", name);
                    contactObj.put("relationship", rel);

                    JSONArray attrsArr = new JSONArray();
                    String attrSql = "SELECT attribute_id, category, description, timestamp FROM contact_attributes WHERE contact_id = ? ORDER BY attribute_id DESC";
                    try (PreparedStatement attrStmt = conn.prepareStatement(attrSql)) {
                        attrStmt.setInt(1, contactId);
                        try (ResultSet attrRs = attrStmt.executeQuery()) {
                            while (attrRs.next()) {
                                JSONObject a = new JSONObject();
                                a.put("attribute_id", attrRs.getInt("attribute_id"));
                                a.put("category", attrRs.getString("category"));
                                a.put("description", attrRs.getString("description"));
                                a.put("timestamp", attrRs.getString("timestamp"));
                                attrsArr.put(a);
                            }
                        }
                    }
                    contactObj.put("attributes", attrsArr);
                    result.put(contactObj);
                }
            }
        } catch (Exception e) {
            System.err.println("[PRM] getAllPRMContacts failed: " + e.getMessage());
        }
        return result;
    }

    public JSONObject getContactDossier(String name) {
        JSONObject dossier = new JSONObject();
        if (name == null || name.trim().isEmpty()) return dossier;

        try (Connection conn = getConnection()) {
            String sql = "SELECT contact_id, name, relationship_type FROM contacts WHERE LOWER(name) = LOWER(?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, name.trim());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int contactId = rs.getInt("contact_id");
                        dossier.put("contact_id", contactId);
                        dossier.put("name", rs.getString("name"));
                        dossier.put("relationship", rs.getString("relationship_type"));

                        JSONArray attrs = new JSONArray();
                        String attrSql = "SELECT attribute_id, category, description, timestamp FROM contact_attributes WHERE contact_id = ? ORDER BY attribute_id ASC";
                        try (PreparedStatement attrStmt = conn.prepareStatement(attrSql)) {
                            attrStmt.setInt(1, contactId);
                            try (ResultSet attrRs = attrStmt.executeQuery()) {
                                while (attrRs.next()) {
                                    JSONObject a = new JSONObject();
                                    a.put("attribute_id", attrRs.getInt("attribute_id"));
                                    a.put("category", attrRs.getString("category"));
                                    a.put("description", attrRs.getString("description"));
                                    a.put("timestamp", attrRs.getString("timestamp"));
                                    attrs.put(a);
                                }
                            }
                        }
                        dossier.put("attributes", attrs);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PRM] getContactDossier failed: " + e.getMessage());
        }
        return dossier;
    }

    public boolean deletePRMAttribute(int attributeId) {
        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM contact_attributes WHERE attribute_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, attributeId);
                return stmt.executeUpdate() > 0;
            }
        } catch (Exception e) {
            System.err.println("[PRM] deletePRMAttribute failed: " + e.getMessage());
            return false;
        }
    }

    public void analyzeAndExtractPRM(int userId, String userText) {
        analyzeAndExtractPRM(userId, userText, IntelligentAssistant.currentSession());
    }

    public void analyzeAndExtractPRM(int userId, String userText, long session) {
        if (!IntelligentAssistant.isSessionCurrent(userId, session)) return;
        if (userId <= 0 || userText == null || userText.trim().split("\\s+").length < 3) return;

        try {
            String prompt = "You are an advanced Cognitive Knowledge Graph & Semantic Network extractor.\n" +
                            "Analyze this user conversation utterance: \"" + userText + "\"\n\n" +
                            "1. Identify people, projects, concepts, triggers, family members, habits, or goals.\n" +
                            "2. Extract directed Semantic Triplets in format: (Subject, SubjectType, Relation, Object, ObjectType, Context)\n" +
                            "Types can be: Person, Project, Concept, Trigger, Family, Preference, Goal.\n" +
                            "Relations can be: works_with, has_child, married_to, stressed_by, prefers, promised_to, owes, advises, threatens, manages, likes.\n\n" +
                            "If no clear relationships or entities exist, output: {\"detected\": false}\n\n" +
                            "If relationships exist, output JSON in this exact structure:\n" +
                            "{\"detected\": true, \"triplets\": [{\"subject\": \"Alex\", \"subject_type\": \"Person\", \"relation\": \"has_daughter\", \"object\": \"Maya\", \"object_type\": \"Family\", \"context\": \"applying to college\"}]}";

            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("prompt", prompt);
            payload.put("stream", false);
            payload.put("format", "json");
            JSONObject options = new JSONObject();
            options.put("temperature", 0.1);
            payload.put("options", options);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String extracted = new JSONObject(response.body()).optString("response", "{}");
            if (!extracted.contains("{") || !extracted.contains("}")) return;

            extracted = extracted.substring(extracted.indexOf("{"), extracted.lastIndexOf("}") + 1);
            JSONObject prmData = new JSONObject(extracted);
            if (!prmData.optBoolean("detected", false) || !prmData.has("triplets")) return;

            JSONArray triplets = prmData.getJSONArray("triplets");
            for (int i = 0; i < triplets.length(); i++) {
                JSONObject t = triplets.getJSONObject(i);
                String subject = t.optString("subject", "").trim();
                String subjectType = t.optString("subject_type", "Concept").trim();
                String relation = t.optString("relation", "related_to").trim();
                String object = t.optString("object", "").trim();
                String objectType = t.optString("object_type", "Concept").trim();
                String context = t.optString("context", "").trim();

                if (!subject.isEmpty() && !object.isEmpty()) {
                    synchronized (IntelligentAssistant.class) {
                        if (!IntelligentAssistant.isSessionCurrent(userId, session)) return;
                        addKnowledgeEdge(userId, subject, subjectType, object, objectType, relation, context);
                        if (subjectType.equalsIgnoreCase("Person")) {
                            savePRMContact(userId, subject, "Contact", relation, object +
                                    (context.isEmpty() ? "" : " (" + context + ")"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PRM] User-scoped triplet extraction failed: " + e.getMessage());
        }
    }

    public void savePRMContact(int userId, String name, String relationship, String category, String description) {
        if (userId <= 0 || name == null || name.trim().isEmpty() || description == null || description.trim().isEmpty()) return;
        name = name.trim();
        relationship = (relationship == null || relationship.trim().isEmpty()) ? "Acquaintance" : relationship.trim();
        category = (category == null || category.trim().isEmpty()) ? "General" : category.trim();
        description = description.trim();

        try (Connection conn = getConnection()) {
            int contactId = -1;
            String findSql = "SELECT contact_id FROM user_contacts WHERE user_id = ? AND LOWER(name) = LOWER(?)";
            try (PreparedStatement findStmt = conn.prepareStatement(findSql)) {
                findStmt.setInt(1, userId);
                findStmt.setString(2, name);
                try (ResultSet rs = findStmt.executeQuery()) {
                    if (rs.next()) contactId = rs.getInt("contact_id");
                }
            }

            if (contactId > 0) {
                try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE user_contacts SET relationship_type = ? WHERE contact_id = ? AND user_id = ?")) {
                    updateStmt.setString(1, relationship);
                    updateStmt.setInt(2, contactId);
                    updateStmt.setInt(3, userId);
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO user_contacts (user_id, name, relationship_type) VALUES (?, ?, ?)")) {
                    insertStmt.setInt(1, userId);
                    insertStmt.setString(2, name);
                    insertStmt.setString(3, relationship);
                    insertStmt.executeUpdate();
                }
                try (PreparedStatement findStmt = conn.prepareStatement(findSql)) {
                    findStmt.setInt(1, userId);
                    findStmt.setString(2, name);
                    try (ResultSet rs = findStmt.executeQuery()) {
                        if (rs.next()) contactId = rs.getInt("contact_id");
                    }
                }
            }

            if (contactId <= 0) return;
            String duplicateSql = "SELECT COUNT(*) FROM user_contact_attributes WHERE contact_id = ? AND category = ? AND description = ?";
            try (PreparedStatement duplicateStmt = conn.prepareStatement(duplicateSql)) {
                duplicateStmt.setInt(1, contactId);
                duplicateStmt.setString(2, category);
                duplicateStmt.setString(3, description);
                try (ResultSet rs = duplicateStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) return;
                }
            }

            try (PreparedStatement attributeStmt = conn.prepareStatement(
                    "INSERT INTO user_contact_attributes (contact_id, category, description) VALUES (?, ?, ?)")) {
                attributeStmt.setInt(1, contactId);
                attributeStmt.setString(2, category);
                attributeStmt.setString(3, description);
                attributeStmt.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[PRM] User-scoped contact save failed: " + e.getMessage());
        }
    }

    public JSONArray getAllPRMContacts(int userId) {
        JSONArray result = new JSONArray();
        if (userId <= 0) return result;

        try (Connection conn = getConnection();
             PreparedStatement contactsStmt = conn.prepareStatement(
                     "SELECT contact_id, name, relationship_type FROM user_contacts WHERE user_id = ? ORDER BY name ASC")) {
            contactsStmt.setInt(1, userId);
            try (ResultSet contacts = contactsStmt.executeQuery()) {
                while (contacts.next()) {
                    int contactId = contacts.getInt("contact_id");
                    JSONObject contact = new JSONObject();
                    contact.put("contact_id", contactId);
                    contact.put("name", contacts.getString("name"));
                    contact.put("relationship", contacts.getString("relationship_type"));

                    JSONArray attributes = new JSONArray();
                    try (PreparedStatement attributesStmt = conn.prepareStatement(
                            "SELECT attribute_id, category, description, timestamp FROM user_contact_attributes WHERE contact_id = ? ORDER BY attribute_id DESC")) {
                        attributesStmt.setInt(1, contactId);
                        try (ResultSet attributeRows = attributesStmt.executeQuery()) {
                            while (attributeRows.next()) {
                                JSONObject attribute = new JSONObject();
                                attribute.put("attribute_id", attributeRows.getInt("attribute_id"));
                                attribute.put("category", attributeRows.getString("category"));
                                attribute.put("description", attributeRows.getString("description"));
                                attribute.put("timestamp", attributeRows.getString("timestamp"));
                                attributes.put(attribute);
                            }
                        }
                    }
                    contact.put("attributes", attributes);
                    result.put(contact);
                }
            }
        } catch (Exception e) {
            System.err.println("[PRM] User-scoped contact lookup failed: " + e.getMessage());
        }
        return result;
    }

    public JSONObject getContactDossier(int userId, String name) {
        JSONObject dossier = new JSONObject();
        if (userId <= 0 || name == null || name.trim().isEmpty()) return dossier;

        try (Connection conn = getConnection();
             PreparedStatement contactStmt = conn.prepareStatement(
                     "SELECT contact_id, name, relationship_type FROM user_contacts WHERE user_id = ? AND LOWER(name) = LOWER(?)")) {
            contactStmt.setInt(1, userId);
            contactStmt.setString(2, name.trim());
            try (ResultSet contacts = contactStmt.executeQuery()) {
                if (!contacts.next()) return dossier;

                int contactId = contacts.getInt("contact_id");
                dossier.put("contact_id", contactId);
                dossier.put("name", contacts.getString("name"));
                dossier.put("relationship", contacts.getString("relationship_type"));
                JSONArray attributes = new JSONArray();
                try (PreparedStatement attributesStmt = conn.prepareStatement(
                        "SELECT attribute_id, category, description, timestamp FROM user_contact_attributes WHERE contact_id = ? ORDER BY attribute_id ASC")) {
                    attributesStmt.setInt(1, contactId);
                    try (ResultSet attributeRows = attributesStmt.executeQuery()) {
                        while (attributeRows.next()) {
                            JSONObject attribute = new JSONObject();
                            attribute.put("attribute_id", attributeRows.getInt("attribute_id"));
                            attribute.put("category", attributeRows.getString("category"));
                            attribute.put("description", attributeRows.getString("description"));
                            attribute.put("timestamp", attributeRows.getString("timestamp"));
                            attributes.put(attribute);
                        }
                    }
                }
                dossier.put("attributes", attributes);
            }
        } catch (Exception e) {
            System.err.println("[PRM] User-scoped dossier lookup failed: " + e.getMessage());
        }
        return dossier;
    }

    public boolean deletePRMAttribute(int userId, int attributeId) {
        if (userId <= 0 || attributeId <= 0) return false;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM user_contact_attributes WHERE attribute_id = ? AND contact_id IN " +
                     "(SELECT contact_id FROM user_contacts WHERE user_id = ?)")) {
            stmt.setInt(1, attributeId);
            stmt.setInt(2, userId);
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[PRM] User-scoped attribute delete failed: " + e.getMessage());
            return false;
        }
    }

    // =======================================================================
    // KNOWLEDGE GRAPH & SEMANTIC NETWORK ENGINE
    // =======================================================================

    public int getOrCreateKnowledgeNode(String name, String nodeType, String description) {
        if (name == null || name.trim().isEmpty()) return -1;
        name = name.trim();
        nodeType = (nodeType == null || nodeType.trim().isEmpty()) ? "Concept" : nodeType.trim();
        description = (description == null) ? "" : description.trim();

        try (Connection conn = getConnection()) {
            String updateSql = "UPDATE knowledge_nodes SET node_type = ?, description = ? WHERE name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, nodeType);
                stmt.setString(2, description);
                stmt.setString(3, name);
                int updated = stmt.executeUpdate();
                if (updated == 0) {
                    String insertSql = "INSERT INTO knowledge_nodes (name, node_type, description) VALUES (?, ?, ?)";
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, name);
                        insertStmt.setString(2, nodeType);
                        insertStmt.setString(3, description);
                        insertStmt.executeUpdate();
                    }
                }
            }

            String selectSql = "SELECT node_id FROM knowledge_nodes WHERE name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(selectSql)) {
                stmt.setString(1, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getInt("node_id");
                }
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] getOrCreateNode failed for '" + name + "': " + e.getMessage());
        }
        return -1;
    }

    public boolean addKnowledgeEdge(String sourceName, String sourceType, String targetName, String targetType, String relationType, String context) {
        if (sourceName == null || targetName == null || relationType == null) return false;
        sourceName = sourceName.trim();
        targetName = targetName.trim();
        relationType = relationType.trim().toLowerCase().replace(" ", "_");
        context = (context == null) ? "" : context.trim();

        if (sourceName.equalsIgnoreCase(targetName) || sourceName.isEmpty() || targetName.isEmpty()) return false;

        int sourceId = getOrCreateKnowledgeNode(sourceName, sourceType, "");
        int targetId = getOrCreateKnowledgeNode(targetName, targetType, "");

        if (sourceId == -1 || targetId == -1) return false;

        try (Connection conn = getConnection()) {
            String updateSql = "UPDATE knowledge_edges SET context = ?, weight = 1.0 WHERE source_node_id = ? AND target_node_id = ? AND relation_type = ?";
            try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                stmt.setString(1, context);
                stmt.setInt(2, sourceId);
                stmt.setInt(3, targetId);
                stmt.setString(4, relationType);
                int updated = stmt.executeUpdate();
                if (updated == 0) {
                    String insertSql = "INSERT INTO knowledge_edges (source_node_id, target_node_id, relation_type, context, weight) VALUES (?, ?, ?, ?, 1.0)";
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setInt(1, sourceId);
                        insertStmt.setInt(2, targetId);
                        insertStmt.setString(3, relationType);
                        insertStmt.setString(4, context);
                        insertStmt.executeUpdate();
                    }
                }
            }
            System.out.println("[KnowledgeGraph] Added edge: (" + sourceName + ") --[" + relationType + "]--> (" + targetName + ")");
            return true;
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] addKnowledgeEdge failed: " + e.getMessage());
            return false;
        }
    }

    public JSONObject getCompleteKnowledgeGraph() {
        JSONObject result = new JSONObject();
        JSONArray nodes = new JSONArray();
        JSONArray edges = new JSONArray();

        try (Connection conn = getConnection()) {
            String nodeSql = "SELECT node_id, name, node_type, description FROM knowledge_nodes ORDER BY node_id ASC";
            try (PreparedStatement stmt = conn.prepareStatement(nodeSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject n = new JSONObject();
                    n.put("id", rs.getInt("node_id"));
                    n.put("name", rs.getString("name"));
                    n.put("type", rs.getString("node_type"));
                    n.put("description", rs.getString("description"));
                    nodes.put(n);
                }
            }

            String edgeSql = "SELECT e.edge_id, e.source_node_id, e.target_node_id, e.relation_type, e.context, " +
                             "s.name AS source_name, s.node_type AS source_type, " +
                             "t.name AS target_name, t.node_type AS target_type " +
                             "FROM knowledge_edges e " +
                             "JOIN knowledge_nodes s ON e.source_node_id = s.node_id " +
                             "JOIN knowledge_nodes t ON e.target_node_id = t.node_id ORDER BY e.edge_id ASC";
            try (PreparedStatement stmt = conn.prepareStatement(edgeSql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject edge = new JSONObject();
                    edge.put("id", rs.getInt("edge_id"));
                    edge.put("source", rs.getInt("source_node_id"));
                    edge.put("target", rs.getInt("target_node_id"));
                    edge.put("relation", rs.getString("relation_type"));
                    edge.put("context", rs.getString("context"));
                    edge.put("source_name", rs.getString("source_name"));
                    edge.put("target_name", rs.getString("target_name"));
                    edges.put(edge);
                }
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] getCompleteKnowledgeGraph failed: " + e.getMessage());
        }

        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    public String getGraphNeighborhood(String entityName, int maxHops) {
        if (entityName == null || entityName.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();

        try (Connection conn = getConnection()) {
            String sql1 = "SELECT s.name AS source_name, e.relation_type, t.name AS target_name, e.context " +
                          "FROM knowledge_edges e " +
                          "JOIN knowledge_nodes s ON e.source_node_id = s.node_id " +
                          "JOIN knowledge_nodes t ON e.target_node_id = t.node_id " +
                          "WHERE LOWER(s.name) = LOWER(?) OR LOWER(t.name) = LOWER(?)";

            try (PreparedStatement stmt = conn.prepareStatement(sql1)) {
                stmt.setString(1, entityName.trim());
                stmt.setString(2, entityName.trim());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String src = rs.getString("source_name");
                        String rel = rs.getString("relation_type");
                        String tgt = rs.getString("target_name");
                        String ctx = rs.getString("context");
                        sb.append("• (").append(src).append(") ──[").append(rel).append("]──► (").append(tgt).append(")");
                        if (ctx != null && !ctx.isEmpty()) {
                            sb.append(" (Context: \"").append(ctx).append("\")");
                        }
                        sb.append("\n");
                    }
                }
            }

            if (maxHops > 1) {
                String sql2 = "SELECT n1.name AS origin, e1.relation_type AS rel1, n2.name AS intermediary, e2.relation_type AS rel2, n3.name AS destination " +
                              "FROM knowledge_nodes n1 " +
                              "JOIN knowledge_edges e1 ON n1.node_id = e1.source_node_id " +
                              "JOIN knowledge_nodes n2 ON e1.target_node_id = n2.node_id " +
                              "JOIN knowledge_edges e2 ON n2.node_id = e2.source_node_id " +
                              "JOIN knowledge_nodes n3 ON e2.target_node_id = n3.node_id " +
                              "WHERE LOWER(n1.name) = LOWER(?) AND n3.node_id != n1.node_id LIMIT 10";
                try (PreparedStatement stmt2 = conn.prepareStatement(sql2)) {
                    stmt2.setString(1, entityName.trim());
                    try (ResultSet rs2 = stmt2.executeQuery()) {
                        while (rs2.next()) {
                            sb.append("  ↳ Extended Path: (").append(rs2.getString("origin")).append(") ──[")
                              .append(rs2.getString("rel1")).append("]──► (").append(rs2.getString("intermediary"))
                              .append(") ──[").append(rs2.getString("rel2")).append("]──► (")
                              .append(rs2.getString("destination")).append(")\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] getGraphNeighborhood failed: " + e.getMessage());
        }

        return sb.toString();
    }

    public boolean deleteKnowledgeNode(int nodeId) {
        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM knowledge_nodes WHERE node_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, nodeId);
                return stmt.executeUpdate() > 0;
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] deleteKnowledgeNode failed: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteKnowledgeEdge(int edgeId) {
        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM knowledge_edges WHERE edge_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, edgeId);
                return stmt.executeUpdate() > 0;
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] deleteKnowledgeEdge failed: " + e.getMessage());
            return false;
        }
    }

    public int getOrCreateKnowledgeNode(int userId, String name, String nodeType, String description) {
        if (userId <= 0 || name == null || name.trim().isEmpty()) return -1;
        name = name.trim();
        nodeType = (nodeType == null || nodeType.trim().isEmpty()) ? "Concept" : nodeType.trim();
        description = (description == null) ? "" : description.trim();

        try (Connection conn = getConnection()) {
            int nodeId = -1;
            String findSql = "SELECT node_id FROM user_knowledge_nodes WHERE user_id = ? AND LOWER(name) = LOWER(?)";
            try (PreparedStatement findStmt = conn.prepareStatement(findSql)) {
                findStmt.setInt(1, userId);
                findStmt.setString(2, name);
                try (ResultSet rs = findStmt.executeQuery()) {
                    if (rs.next()) nodeId = rs.getInt("node_id");
                }
            }

            if (nodeId > 0) {
                try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE user_knowledge_nodes SET node_type = ?, description = ? WHERE node_id = ? AND user_id = ?")) {
                    updateStmt.setString(1, nodeType);
                    updateStmt.setString(2, description);
                    updateStmt.setInt(3, nodeId);
                    updateStmt.setInt(4, userId);
                    updateStmt.executeUpdate();
                }
                return nodeId;
            }

            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO user_knowledge_nodes (user_id, name, node_type, description) VALUES (?, ?, ?, ?)")) {
                insertStmt.setInt(1, userId);
                insertStmt.setString(2, name);
                insertStmt.setString(3, nodeType);
                insertStmt.setString(4, description);
                insertStmt.executeUpdate();
            }

            try (PreparedStatement findStmt = conn.prepareStatement(findSql)) {
                findStmt.setInt(1, userId);
                findStmt.setString(2, name);
                try (ResultSet rs = findStmt.executeQuery()) {
                    if (rs.next()) return rs.getInt("node_id");
                }
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] User-scoped node save failed for '" + name + "': " + e.getMessage());
        }
        return -1;
    }

    public boolean addKnowledgeEdge(int userId, String sourceName, String sourceType, String targetName,
                                    String targetType, String relationType, String context) {
        if (userId <= 0 || sourceName == null || targetName == null || relationType == null) return false;
        sourceName = sourceName.trim();
        targetName = targetName.trim();
        relationType = relationType.trim().toLowerCase().replace(" ", "_");
        context = (context == null) ? "" : context.trim();

        if (sourceName.isEmpty() || targetName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) return false;
        int sourceId = getOrCreateKnowledgeNode(userId, sourceName, sourceType, "");
        int targetId = getOrCreateKnowledgeNode(userId, targetName, targetType, "");
        if (sourceId <= 0 || targetId <= 0) return false;

        try (Connection conn = getConnection()) {
            String updateSql = "UPDATE user_knowledge_edges SET context = ?, weight = 1.0 " +
                    "WHERE user_id = ? AND source_node_id = ? AND target_node_id = ? AND relation_type = ?";
            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setString(1, context);
                updateStmt.setInt(2, userId);
                updateStmt.setInt(3, sourceId);
                updateStmt.setInt(4, targetId);
                updateStmt.setString(5, relationType);
                if (updateStmt.executeUpdate() > 0) return true;
            }

            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO user_knowledge_edges (user_id, source_node_id, target_node_id, relation_type, context, weight) " +
                    "VALUES (?, ?, ?, ?, ?, 1.0)")) {
                insertStmt.setInt(1, userId);
                insertStmt.setInt(2, sourceId);
                insertStmt.setInt(3, targetId);
                insertStmt.setString(4, relationType);
                insertStmt.setString(5, context);
                insertStmt.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] User-scoped edge save failed: " + e.getMessage());
            return false;
        }
    }

    public JSONObject getCompleteKnowledgeGraph(int userId) {
        JSONObject result = new JSONObject();
        JSONArray nodes = new JSONArray();
        JSONArray edges = new JSONArray();
        result.put("nodes", nodes);
        result.put("edges", edges);
        if (userId <= 0) return result;

        try (Connection conn = getConnection()) {
            try (PreparedStatement nodeStmt = conn.prepareStatement(
                    "SELECT node_id, name, node_type, description FROM user_knowledge_nodes WHERE user_id = ? ORDER BY node_id ASC")) {
                nodeStmt.setInt(1, userId);
                try (ResultSet nodeRows = nodeStmt.executeQuery()) {
                    while (nodeRows.next()) {
                        JSONObject node = new JSONObject();
                        node.put("id", nodeRows.getInt("node_id"));
                        node.put("name", nodeRows.getString("name"));
                        node.put("type", nodeRows.getString("node_type"));
                        node.put("description", nodeRows.getString("description"));
                        nodes.put(node);
                    }
                }
            }

            String edgeSql = "SELECT e.edge_id, e.source_node_id, e.target_node_id, e.relation_type, e.context, " +
                    "s.name AS source_name, t.name AS target_name " +
                    "FROM user_knowledge_edges e " +
                    "JOIN user_knowledge_nodes s ON e.source_node_id = s.node_id " +
                    "JOIN user_knowledge_nodes t ON e.target_node_id = t.node_id " +
                    "WHERE e.user_id = ? AND s.user_id = ? AND t.user_id = ? ORDER BY e.edge_id ASC";
            try (PreparedStatement edgeStmt = conn.prepareStatement(edgeSql)) {
                edgeStmt.setInt(1, userId);
                edgeStmt.setInt(2, userId);
                edgeStmt.setInt(3, userId);
                try (ResultSet edgeRows = edgeStmt.executeQuery()) {
                    while (edgeRows.next()) {
                        JSONObject edge = new JSONObject();
                        edge.put("id", edgeRows.getInt("edge_id"));
                        edge.put("source", edgeRows.getInt("source_node_id"));
                        edge.put("target", edgeRows.getInt("target_node_id"));
                        edge.put("relation", edgeRows.getString("relation_type"));
                        edge.put("context", edgeRows.getString("context"));
                        edge.put("source_name", edgeRows.getString("source_name"));
                        edge.put("target_name", edgeRows.getString("target_name"));
                        edges.put(edge);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] User-scoped graph lookup failed: " + e.getMessage());
        }
        return result;
    }

    public String getGraphNeighborhood(int userId, String entityName, int maxHops) {
        if (userId <= 0 || entityName == null || entityName.trim().isEmpty()) return "";
        StringBuilder context = new StringBuilder();

        try (Connection conn = getConnection()) {
            String directSql = "SELECT s.name AS source_name, e.relation_type, t.name AS target_name, e.context " +
                    "FROM user_knowledge_edges e " +
                    "JOIN user_knowledge_nodes s ON e.source_node_id = s.node_id " +
                    "JOIN user_knowledge_nodes t ON e.target_node_id = t.node_id " +
                    "WHERE e.user_id = ? AND s.user_id = ? AND t.user_id = ? " +
                    "AND (LOWER(s.name) = LOWER(?) OR LOWER(t.name) = LOWER(?))";
            try (PreparedStatement directStmt = conn.prepareStatement(directSql)) {
                directStmt.setInt(1, userId);
                directStmt.setInt(2, userId);
                directStmt.setInt(3, userId);
                directStmt.setString(4, entityName.trim());
                directStmt.setString(5, entityName.trim());
                try (ResultSet rows = directStmt.executeQuery()) {
                    while (rows.next()) {
                        context.append("(").append(rows.getString("source_name")).append(") -[")
                                .append(rows.getString("relation_type")).append("]-> (")
                                .append(rows.getString("target_name")).append(")");
                        String edgeContext = rows.getString("context");
                        if (edgeContext != null && !edgeContext.isEmpty()) {
                            context.append(" (context: ").append(edgeContext).append(")");
                        }
                        context.append("\n");
                    }
                }
            }

            if (maxHops > 1) {
                String extendedSql = "SELECT n1.name AS origin, e1.relation_type AS rel1, n2.name AS intermediary, " +
                        "e2.relation_type AS rel2, n3.name AS destination " +
                        "FROM user_knowledge_nodes n1 " +
                        "JOIN user_knowledge_edges e1 ON n1.node_id = e1.source_node_id " +
                        "JOIN user_knowledge_nodes n2 ON e1.target_node_id = n2.node_id " +
                        "JOIN user_knowledge_edges e2 ON n2.node_id = e2.source_node_id " +
                        "JOIN user_knowledge_nodes n3 ON e2.target_node_id = n3.node_id " +
                        "WHERE n1.user_id = ? AND n2.user_id = ? AND n3.user_id = ? " +
                        "AND e1.user_id = ? AND e2.user_id = ? AND LOWER(n1.name) = LOWER(?) " +
                        "AND n3.node_id != n1.node_id LIMIT 10";
                try (PreparedStatement extendedStmt = conn.prepareStatement(extendedSql)) {
                    extendedStmt.setInt(1, userId);
                    extendedStmt.setInt(2, userId);
                    extendedStmt.setInt(3, userId);
                    extendedStmt.setInt(4, userId);
                    extendedStmt.setInt(5, userId);
                    extendedStmt.setString(6, entityName.trim());
                    try (ResultSet rows = extendedStmt.executeQuery()) {
                        while (rows.next()) {
                            context.append("Path: (").append(rows.getString("origin")).append(") -[")
                                    .append(rows.getString("rel1")).append("]-> (")
                                    .append(rows.getString("intermediary")).append(") -[")
                                    .append(rows.getString("rel2")).append("]-> (")
                                    .append(rows.getString("destination")).append(")\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] User-scoped neighborhood lookup failed: " + e.getMessage());
        }
        return context.toString();
    }

    public boolean deleteKnowledgeNode(int userId, int nodeId) {
        if (userId <= 0 || nodeId <= 0) return false;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM user_knowledge_nodes WHERE node_id = ? AND user_id = ?")) {
            stmt.setInt(1, nodeId);
            stmt.setInt(2, userId);
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] User-scoped node delete failed: " + e.getMessage());
            return false;
        }
    }

    public boolean deleteKnowledgeEdge(int userId, int edgeId) {
        if (userId <= 0 || edgeId <= 0) return false;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM user_knowledge_edges WHERE edge_id = ? AND user_id = ?")) {
            stmt.setInt(1, edgeId);
            stmt.setInt(2, userId);
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[KnowledgeGraph] User-scoped edge delete failed: " + e.getMessage());
            return false;
        }
    }

    private JSONObject parseRelativeReminderIntent(String userText) {
        if (userText == null || userText.trim().isEmpty()) return null;

        String task = null;
        String amountText = null;
        String unit = null;
        java.util.regex.Matcher taskFirst = java.util.regex.Pattern.compile(
                "(?i)^\\s*remind\\s+me\\s+(?:to\\s+)?(.+?)\\s+(?:in|after)\\s+(\\d+)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\s*[.!?]*\\s*$")
                .matcher(userText);
        if (taskFirst.matches()) {
            task = taskFirst.group(1);
            amountText = taskFirst.group(2);
            unit = taskFirst.group(3);
        } else {
            java.util.regex.Matcher timeFirst = java.util.regex.Pattern.compile(
                    "(?i)^\\s*remind\\s+me\\s+(?:in|after)\\s+(\\d+)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\s+(?:to\\s+)?(.+?)\\s*[.!?]*\\s*$")
                    .matcher(userText);
            if (!timeFirst.matches()) return null;
            amountText = timeFirst.group(1);
            unit = timeFirst.group(2);
            task = timeFirst.group(3);
        }

        long amount;
        try {
            amount = Long.parseLong(amountText);
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount <= 0 || task == null || task.trim().isEmpty()) return null;

        String normalizedUnit = unit.toLowerCase(java.util.Locale.ROOT);
        LocalDateTime scheduledAt = LocalDateTime.now();
        String displayUnit;
        if (normalizedUnit.startsWith("s")) {
            scheduledAt = scheduledAt.plusSeconds(amount);
            displayUnit = amount == 1 ? "second" : "seconds";
        } else if (normalizedUnit.startsWith("m")) {
            scheduledAt = scheduledAt.plusMinutes(amount);
            displayUnit = amount == 1 ? "minute" : "minutes";
        } else {
            scheduledAt = scheduledAt.plusHours(amount);
            displayUnit = amount == 1 ? "hour" : "hours";
        }

        String cleanTask = task.trim().replaceAll("[.!?]+$", "").trim();
        if (cleanTask.toLowerCase(java.util.Locale.ROOT).startsWith("to ")) {
            cleanTask = cleanTask.substring(3).trim();
        }
        if (cleanTask.isEmpty()) return null;

        JSONObject result = new JSONObject();
        result.put("intent", "SET_REMINDER");
        result.put("has_time", true);
        result.put("task", cleanTask);
        result.put("datetime", scheduledAt.format(REMINDER_DATE_TIME_FORMAT));
        result.put("time_description", amount + " " + displayUnit + " from now");
        return result;
    }

        // UNIFIED INTENT ANALYZER
    public JSONObject analyzeIntent(String userText) {
        JSONObject relativeReminder = parseRelativeReminderIntent(userText);
        if (relativeReminder != null) return relativeReminder;

        try {
            String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:00"));
            
            String prompt = "You are an intelligent Intent Router for an AI assistant. The current exact date and time is: " + currentTime + ".\n" +
                            "The user said: \"" + userText + "\".\n\n" +
                            "Determine the intent of the user from these options: CHAT, SET_REMINDER, GET_REMINDERS, DELETE_REMINDERS, READ_EMAIL.\n" +
                            "- If GET_REMINDERS: they want to check, list, view, or ask what reminders were set or exist.\n" +
                            "- If CHAT: they are just talking normally.\n" +
                            "- If READ_EMAIL: they want to check their latest emails.\n" +
                            "- If SET_REMINDER: they want to be reminded of something. Extract the task. VERY IMPORTANT: Did they explicitly specify a TIME? If they did not specify a time (e.g. they just said 'remind me to call someone'), set has_time to false. If they did specify a time, set has_time to true and calculate the exact scheduled DATETIME in YYYY-MM-DD HH:MM:00 format.\n\n" +
                            "Output ONLY a valid JSON object in ONE of these formats based on intent:\n" +
                            "{\"intent\": \"CHAT\"}\n" +
                            "{\"intent\": \"READ_EMAIL\"}\n" +
                            "{\"intent\": \"SET_REMINDER\", \"has_time\": true/false, \"task\": \"task description\", \"datetime\": \"YYYY-MM-DD HH:MM:00\" (if has_time is true)}";

            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("prompt", prompt);
            payload.put("stream", false);
            payload.put("format", "json");
            JSONObject options = new JSONObject();
            options.put("temperature", 0.1);
            payload.put("options", options);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject resJson = new JSONObject(response.body());
            String data = resJson.optString("response", "{}");
            
            System.out.println("[IntentRouter] Raw: " + data);
            if (data.contains("{") && data.contains("}")) {
                data = data.substring(data.indexOf("{"), data.lastIndexOf("}") + 1);
                return new JSONObject(data);
            }
        } catch (Exception e) {
            System.err.println("[IntentRouter] Error: " + e.getMessage());
        }
        
        // Fallback to chat if extraction fails
        JSONObject fallback = new JSONObject();
        fallback.put("intent", "CHAT");
        return fallback;
    }
    
    public int deleteAllReminders(int userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM reminders WHERE user_id = ? AND is_completed = FALSE");
            stmt.setInt(1, userId);
            int rows = stmt.executeUpdate();
            System.out.println("[ReminderSystem] Deleted " + rows + " pending reminder(s) for user " + userId);
            return rows;
        } catch (Exception e) {
            System.err.println("[Memory] Failed to delete reminders: " + e.getMessage());
            return 0;
        }
    }

    public int deleteReminderByKeyword(int userId, String keyword) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM reminders WHERE user_id = ? AND is_completed = FALSE AND LOWER(task_description) LIKE LOWER(?)");
            stmt.setInt(1, userId);
            stmt.setString(2, "%" + keyword + "%");
            int rows = stmt.executeUpdate();
            System.out.println("[ReminderSystem] Deleted " + rows + " reminder(s) matching '" + keyword + "'");
            return rows;
        } catch (Exception e) {
            System.err.println("[Memory] Failed to delete reminder by keyword: " + e.getMessage());
            return 0;
        }
    }

    public boolean scheduleReminder(int userId, String task, String datetime) {
        if (userId <= 0 || task == null || task.trim().isEmpty() || datetime == null || datetime.trim().isEmpty()) {
            return false;
        }

        try (Connection conn = getConnection()) {
            String normalizedDateTime = datetime.trim().replace('T', ' ');
            if (normalizedDateTime.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
                normalizedDateTime += ":00";
            }
            LocalDateTime scheduledAt = LocalDateTime.parse(normalizedDateTime, REMINDER_DATE_TIME_FORMAT);
            String sql = "INSERT INTO reminders (user_id, task_description, scheduled_time, is_completed) VALUES (?, ?, ?, FALSE)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, userId);
                stmt.setString(2, task.trim());
                stmt.setTimestamp(3, java.sql.Timestamp.valueOf(scheduledAt));
                stmt.executeUpdate();
            }
            System.out.println("[ReminderSystem] Successfully scheduled: " + task + " for " + normalizedDateTime);
            return true;
        } catch (Exception e) {
            System.err.println("[ReminderSystem] Failed to schedule reminder: " + e.getMessage());
            return false;
        }
    }

    public static final class DueReminder {
        private final int id;
        private final String task;
        private final LocalDateTime scheduledAt;

        private DueReminder(int id, String task, LocalDateTime scheduledAt) {
            this.id = id;
            this.task = task;
            this.scheduledAt = scheduledAt;
        }

        public int getId() {
            return id;
        }

        public String getTask() {
            return task;
        }

        public LocalDateTime getScheduledAt() {
            return scheduledAt;
        }
    }

    /**
     * Atomically marks each due reminder as completed and returns only the rows
     * this application instance successfully claimed for notification.
     */
    public List<DueReminder> claimDueReminders(int userId) {
        List<DueReminder> candidates = new ArrayList<>();
        List<DueReminder> claimed = new ArrayList<>();
        if (userId <= 0) return claimed;

        try (Connection conn = getConnection()) {
            String dueSql = "SELECT id, task_description, scheduled_time FROM reminders " +
                    "WHERE user_id = ? AND is_completed = FALSE AND scheduled_time <= ? " +
                    "ORDER BY scheduled_time ASC, id ASC";
            try (PreparedStatement dueStmt = conn.prepareStatement(dueSql)) {
                dueStmt.setInt(1, userId);
                dueStmt.setTimestamp(2, java.sql.Timestamp.valueOf(LocalDateTime.now()));
                try (ResultSet rows = dueStmt.executeQuery()) {
                    while (rows.next()) {
                        java.sql.Timestamp scheduled = rows.getTimestamp("scheduled_time");
                        candidates.add(new DueReminder(
                                rows.getInt("id"),
                                rows.getString("task_description"),
                                scheduled == null ? null : scheduled.toLocalDateTime()));
                    }
                }
            }

            String claimSql = "UPDATE reminders SET is_completed = TRUE " +
                    "WHERE id = ? AND user_id = ? AND is_completed = FALSE";
            try (PreparedStatement claimStmt = conn.prepareStatement(claimSql)) {
                for (DueReminder reminder : candidates) {
                    claimStmt.setInt(1, reminder.getId());
                    claimStmt.setInt(2, userId);
                    if (claimStmt.executeUpdate() == 1) {
                        claimed.add(reminder);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ReminderSystem] Due reminder check failed: " + e.getMessage());
        }
        return claimed;
    }

    public void reopenClaimedReminder(int userId, int reminderId) {
        if (userId <= 0 || reminderId <= 0) return;
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE reminders SET is_completed = FALSE WHERE id = ? AND user_id = ?")) {
            stmt.setInt(1, reminderId);
            stmt.setInt(2, userId);
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[ReminderSystem] Could not return claimed reminder to pending state: " + e.getMessage());
        }
    }
    
    public boolean isFirstLoginToday() {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT key_value FROM app_state WHERE key_name = 'last_login_date'");
            ResultSet rs = stmt.executeQuery();
            String todayDate = java.time.LocalDate.now().toString();
            if (rs.next()) {
                String lastLogin = rs.getString("key_value");
                return !todayDate.equals(lastLogin);
            } else {
                return true; // No record exists
            }
        } catch (Exception e) {
            System.err.println("[Memory] isFirstLoginToday failed: " + e.getMessage());
            return false;
        }
    }
    
    public void updateLastLoginToday() {
        try (Connection conn = getConnection()) {
            String todayDate = java.time.LocalDate.now().toString();
            PreparedStatement updateStmt = conn.prepareStatement(
                "UPDATE app_state SET key_value = ? WHERE key_name = 'last_login_date'"
            );
            updateStmt.setString(1, todayDate);
            int updated = updateStmt.executeUpdate();
            if (updated == 0) {
                PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO app_state (key_name, key_value) VALUES ('last_login_date', ?)"
                );
                insertStmt.setString(1, todayDate);
                insertStmt.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[Memory] updateLastLoginToday failed: " + e.getMessage());
        }
    }
    
    
    public String getRecentRemindersSummary(int userId, int limit) {
        StringBuilder summary = new StringBuilder();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT scheduled_time, task_description, is_completed FROM reminders WHERE user_id = ? ORDER BY id DESC LIMIT ?"
            );
            stmt.setInt(1, userId);
            stmt.setInt(2, limit);
            ResultSet rs = stmt.executeQuery();
            int count = 0;
            while (rs.next()) {
                count++;
                String time = rs.getString("scheduled_time");
                String task = rs.getString("task_description");
                boolean done = rs.getBoolean("is_completed");
                summary.append(count).append(". ").append(task).append(" (Scheduled: ").append(time).append(done ? ", Completed" : ", Pending").append(")\n");
            }
            if (count == 0) return "You have no active or recent reminders recorded.";
            return "Here are your recent reminders:\n" + summary.toString().trim();
        } catch (Exception e) {
            return "Unable to fetch recent reminders: " + e.getMessage();
        }
    }

    public String getTodaysRemindersSummary(int userId) {
        StringBuilder summary = new StringBuilder();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT scheduled_time, task_description FROM reminders WHERE user_id = ? AND CAST(scheduled_time AS DATE) = CURRENT_DATE() AND is_completed = FALSE ORDER BY scheduled_time ASC"
            );
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            int count = 0;
            while (rs.next()) {
                String time = rs.getString("scheduled_time");
                String task = rs.getString("task_description");
                if (time.length() > 10) time = time.substring(11, 16); 
                summary.append("- At ").append(time).append(": ").append(task).append("\n");
                count++;
            }
            if (count == 0) return "No reminders scheduled for today.";
            return summary.toString();
        } catch (Exception e) {
            System.err.println("[Memory] getTodaysRemindersSummary failed: " + e.getMessage());
            return "Unable to fetch reminders.";
        }
    }
    
    public void markTodaysRemindersCompleted(int userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE reminders SET is_completed = TRUE WHERE user_id = ? AND CAST(scheduled_time AS DATE) = CURRENT_DATE() AND is_completed = FALSE"
            );
            stmt.setInt(1, userId);
            int updated = stmt.executeUpdate();
            System.out.println("[Memory] Marked " + updated + " reminders as completed for today.");
        } catch (Exception e) {
            System.err.println("[Memory] markTodaysRemindersCompleted failed: " + e.getMessage());
        }
    }
    
    public boolean saveUserFaceprint(int userId, float[] embedding) {
        if (userId <= 0 || !FaceVerification.isValidEmbedding(embedding)) return false;
        try (Connection conn = getConnection()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < embedding.length; i++) {
                sb.append(embedding[i]);
                if (i < embedding.length - 1) sb.append(",");
            }
            String embeddingStr = sb.toString();

            PreparedStatement updateStmt = conn.prepareStatement(
                "UPDATE faceprints SET embedding = ?, embedding_version = ? WHERE user_id = ?"
            );
            updateStmt.setString(1, embeddingStr);
            updateStmt.setString(2, FaceVerification.EMBEDDING_VERSION);
            updateStmt.setInt(3, userId);
            int updated = updateStmt.executeUpdate();

            if (updated == 0) {
                PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO faceprints (user_id, embedding, embedding_version) VALUES (?, ?, ?)"
                );
                insertStmt.setInt(1, userId);
                insertStmt.setString(2, embeddingStr);
                insertStmt.setString(3, FaceVerification.EMBEDDING_VERSION);
                insertStmt.executeUpdate();
            }
            System.out.println("[Memory] User faceprint saved.");
            return true;
        } catch (Exception e) {
            System.err.println("[Memory] saveUserFaceprint failed: " + e.getMessage());
        }
        return false;
    }

    public float[] getUserFaceprint(int userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT embedding FROM faceprints WHERE user_id = ? AND embedding_version = ?");
            stmt.setInt(1, userId);
            stmt.setString(2, FaceVerification.EMBEDDING_VERSION);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String[] parts = rs.getString("embedding").split(",");
                float[] embedding = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    embedding[i] = Float.parseFloat(parts[i]);
                }
                return FaceVerification.isValidEmbedding(embedding) ? embedding : null;
            }
        } catch (Exception e) {
            System.err.println("[Memory] getUserFaceprint failed: " + e.getMessage());
        }
        return null; // No faceprint registered yet
    }

    public int createNewUser() {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (name, role) VALUES ('New User', 'Owner')", java.sql.Statement.RETURN_GENERATED_KEYS);
            stmt.executeUpdate();
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch(Exception e) {
            System.err.println("[Memory] Error creating user: " + e.getMessage());
        }
        return -1;
    }

    public Map<Integer, float[]> getAllUserFaceprints() {
        Map<Integer, float[]> map = new HashMap<>();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT f.user_id, f.embedding FROM faceprints f JOIN users u ON u.user_id = f.user_id " +
                "WHERE u.registration_complete = TRUE AND f.embedding_version = ?");
            stmt.setString(1, FaceVerification.EMBEDDING_VERSION);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                int uid = rs.getInt("user_id");
                try {
                    String[] parts = rs.getString("embedding").split(",");
                    float[] embedding = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        embedding[i] = Float.parseFloat(parts[i]);
                    }
                    if (FaceVerification.isValidEmbedding(embedding)) map.put(uid, embedding);
                } catch (RuntimeException invalid) {
                    System.err.println("[Memory] Ignoring invalid faceprint for user " + uid);
                }
            }
        } catch (Exception e) {
            System.err.println("[Memory] getAllUserFaceprints failed: " + e.getMessage());
        }
        return map;
    }

    public int authenticateEmail(String email, String password) {
        try (Connection conn = getConnection()) {
            // Setup stores the account address as Gmail_Address; older profiles may use Email.
            PreparedStatement stmt1 = conn.prepareStatement(
                "SELECT user_id FROM user_profile WHERE attribute_key IN ('Email', 'Gmail_Address') AND attribute_value = ?"
            );
            stmt1.setString(1, email);
            ResultSet rs1 = stmt1.executeQuery();
            while (rs1.next()) {
                int uid = rs1.getInt("user_id");
                // Check password for this user
                PreparedStatement stmt2 = conn.prepareStatement("SELECT attribute_value FROM user_profile WHERE user_id = ? AND attribute_key = 'Password'");
                stmt2.setInt(1, uid);
                ResultSet rs2 = stmt2.executeQuery();
                if (rs2.next()) {
                    String savedPassword = rs2.getString("attribute_value");
                    if (BCrypt.checkpw(password, savedPassword)) {
                        return uid;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Memory] authenticateEmail failed: " + e.getMessage());
        }
        return -1;
    }

    public void resetMemory() {
        resetTargetedMemory(Engine.currentUserId);
    }

    public boolean resetTargetedMemory(int userId) {
        if (userId <= 0) return false;
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                String[] deletes = {
                    "DELETE FROM user_contact_attributes WHERE contact_id IN (SELECT contact_id FROM user_contacts WHERE user_id = ?)",
                    "DELETE FROM user_contacts WHERE user_id = ?",
                    "DELETE FROM user_knowledge_edges WHERE user_id = ?",
                    "DELETE FROM user_knowledge_nodes WHERE user_id = ?",
                    "DELETE FROM reminders WHERE user_id = ?",
                    "DELETE FROM user_memories WHERE user_id = ?",
                    "DELETE FROM messages WHERE conversation_id IN (SELECT conversation_id FROM conversations WHERE user_id = ?)",
                    "DELETE FROM conversations WHERE user_id = ?",
                    "DELETE FROM conversation_logs WHERE user_id = ?"
                };
                for (String sql : deletes) {
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, userId);
                        stmt.executeUpdate();
                    }
                }
                conn.commit();
                System.out.println("[Memory] Memory and conversations cleared for user " + userId + ".");
                return true;
            } catch (Exception error) {
                conn.rollback();
                throw error;
            }
        } catch (Exception e) {
            System.err.println("[Memory] Targeted reset failed: " + e.getMessage());
            return false;
        }
    }

    public void closeDatabase() {
        try (Connection conn = getConnection(); java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("SHUTDOWN");
            System.out.println("[Memory] H2 Database shutdown cleanly.");
        } catch (Exception e) {
            System.err.println("[Memory] Database shutdown error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BIOMETRIC FILE STORAGE
    // ═══════════════════════════════════════════════════════════════════════════

    public static String getBiometricBasePath() {
        try {
            String rawPath = MemoryManager.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            String decodedPath = java.net.URLDecoder.decode(rawPath, "UTF-8");
            String base = new File(decodedPath).getParentFile().getParentFile().getAbsolutePath();
            return base + File.separator + "biometric_data";
        } catch (Exception e) {
            return "biometric_data";
        }
    }

    private void removeLegacyVoiceEnrollmentRecordings() {
        Path voicesDir = Paths.get(getBiometricBasePath(), "voices");
        if (!Files.isDirectory(voicesDir)) return;
        try (java.nio.file.DirectoryStream<Path> recordings = Files.newDirectoryStream(voicesDir, "user_*_voice_*.wav")) {
            for (Path recording : recordings) Files.deleteIfExists(recording);
        } catch (IOException e) {
            System.err.println("[Memory] Could not remove old voice-login enrollment recordings: " + e.getMessage());
        }
    }

    /**
     * Saves a Base64-encoded face image to disk and records the path in the users table.
     * Returns the file path on success, null on failure.
     */
    public String saveUserFaceImage(int userId, String base64Image, String angleLabel) {
        if (base64Image == null || base64Image.isEmpty()) return null;
        try {
            // Strip data URI prefix if present
            String rawBase64 = base64Image;
            if (rawBase64.contains(",")) {
                rawBase64 = rawBase64.split(",")[1];
            }
            byte[] imageBytes = Base64.getDecoder().decode(rawBase64);
            
            String facesDir = getBiometricBasePath() + File.separator + "faces";
            new File(facesDir).mkdirs();
            
            String filename = "user_" + userId + "_face_" + angleLabel + ".png";
            String filePath = facesDir + File.separator + filename;
            
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(imageBytes);
            }
            
            // Update the master face image path in users table (use front as primary)
            if ("front".equals(angleLabel) || "master".equals(angleLabel)) {
                try (Connection conn = getConnection()) {
                    PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE users SET face_image_path = ? WHERE user_id = ?");
                    stmt.setString(1, filePath);
                    stmt.setInt(2, userId);
                    stmt.executeUpdate();
                }
            }
            
            System.out.println("[Biometric] Face image saved: " + filePath + " (" + imageBytes.length + " bytes)");
            return filePath;
        } catch (Exception e) {
            System.err.println("[Biometric] Failed to save face image: " + e.getMessage());
            return null;
        }
    }

    public void markRegistrationComplete(int userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE users SET registration_complete = TRUE WHERE user_id = ?");
            stmt.setInt(1, userId);
            stmt.executeUpdate();
            System.out.println("[DB] User " + userId + " registration marked complete.");
        } catch (Exception e) {
            System.err.println("[DB] markRegistrationComplete failed: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONVERSATION & MESSAGE PERSISTENCE (ChatGPT-like)
    // ═══════════════════════════════════════════════════════════════════════════

    public int createConversation(int userId, String title) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO conversations (user_id, title) VALUES (?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            stmt.setInt(1, userId);
            stmt.setString(2, title != null && !title.isEmpty() ? title : "New Conversation");
            stmt.executeUpdate();
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                int convId = rs.getInt(1);
                System.out.println("[DB] Created conversation " + convId + " for user " + userId);
                return convId;
            }
        } catch (Exception e) {
            System.err.println("[DB] createConversation failed: " + e.getMessage());
        }
        return -1;
    }

    public JSONArray getConversationsForUser(int userId) {
        JSONArray result = new JSONArray();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT conversation_id, title, created_at, updated_at FROM conversations " +
                "WHERE user_id = ? ORDER BY updated_at DESC");
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JSONObject conv = new JSONObject();
                conv.put("conversation_id", rs.getInt("conversation_id"));
                conv.put("title", rs.getString("title"));
                conv.put("created_at", rs.getString("created_at"));
                conv.put("updated_at", rs.getString("updated_at"));
                result.put(conv);
            }
        } catch (Exception e) {
            System.err.println("[DB] getConversationsForUser failed: " + e.getMessage());
        }
        return result;
    }

    public void saveMessage(int conversationId, String role, String content) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO messages (conversation_id, role, content) VALUES (?, ?, ?)");
            stmt.setInt(1, conversationId);
            stmt.setString(2, role);
            stmt.setString(3, content);
            stmt.executeUpdate();
            
            // Update conversation's updated_at timestamp
            PreparedStatement updateConv = conn.prepareStatement(
                "UPDATE conversations SET updated_at = CURRENT_TIMESTAMP WHERE conversation_id = ?");
            updateConv.setInt(1, conversationId);
            updateConv.executeUpdate();
        } catch (Exception e) {
            System.err.println("[DB] saveMessage failed: " + e.getMessage());
        }
    }

    public JSONArray getMessagesForConversation(int conversationId) {
        JSONArray result = new JSONArray();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT message_id, role, content, timestamp FROM messages " +
                "WHERE conversation_id = ? ORDER BY timestamp ASC");
            stmt.setInt(1, conversationId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JSONObject msg = new JSONObject();
                msg.put("message_id", rs.getInt("message_id"));
                msg.put("role", rs.getString("role"));
                msg.put("content", rs.getString("content"));
                msg.put("timestamp", rs.getString("timestamp"));
                result.put(msg);
            }
        } catch (Exception e) {
            System.err.println("[DB] getMessagesForConversation failed: " + e.getMessage());
        }
        return result;
    }

    /** Gets the last N messages from a conversation for LLM context. */
    public JSONArray getRecentMessages(int conversationId, int limit) {
        JSONArray result = new JSONArray();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT role, content FROM (" +
                "  SELECT role, content, timestamp FROM messages " +
                "  WHERE conversation_id = ? ORDER BY timestamp DESC LIMIT ?" +
                ") sub ORDER BY timestamp ASC");
            stmt.setInt(1, conversationId);
            stmt.setInt(2, limit);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JSONObject msg = new JSONObject();
                msg.put("role", rs.getString("role"));
                msg.put("content", rs.getString("content"));
                result.put(msg);
            }
        } catch (Exception e) {
            System.err.println("[DB] getRecentMessages failed: " + e.getMessage());
        }
        return result;
    }

    /** Auto-generates a conversation title from the first user message. */
    public void updateConversationTitle(int conversationId, String firstMessage) {
        String title = firstMessage;
        if (title.length() > 60) {
            title = title.substring(0, 57) + "...";
        }
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE conversations SET title = ? WHERE conversation_id = ? AND title = 'New Conversation'");
            stmt.setString(1, title);
            stmt.setInt(2, conversationId);
            stmt.executeUpdate();
        } catch (Exception e) {
            System.err.println("[DB] updateConversationTitle failed: " + e.getMessage());
        }
    }

    /** Check if a conversation belongs to a specific user. */
    public boolean conversationBelongsToUser(int conversationId, int userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM conversations WHERE conversation_id = ? AND user_id = ?");
            stmt.setInt(1, conversationId);
            stmt.setInt(2, userId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getInt(1) > 0;
        } catch (Exception e) {
            System.err.println("[DB] conversationBelongsToUser failed: " + e.getMessage());
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // USER LONG-TERM MEMORY (Cross-Conversation)
    // ═══════════════════════════════════════════════════════════════════════════

    public void saveUserMemory(int userId, String key, String value, String source) {
        if (value == null || value.trim().isEmpty()) return;
        try (Connection conn = getConnection()) {
            // Check for duplicate memory
            PreparedStatement check = conn.prepareStatement(
                "SELECT COUNT(*) FROM user_memories WHERE user_id = ? AND memory_value = ?");
            check.setInt(1, userId);
            check.setString(2, value.trim());
            ResultSet rs = check.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) return; // Already stored
            
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO user_memories (user_id, memory_key, memory_value, source) VALUES (?, ?, ?, ?)");
            stmt.setInt(1, userId);
            stmt.setString(2, key != null ? key.trim() : "general");
            stmt.setString(3, value.trim());
            stmt.setString(4, source != null ? source.trim() : "");
            stmt.executeUpdate();
            System.out.println("[Memory] Saved user memory: [" + key + "] " + value);
        } catch (Exception e) {
            System.err.println("[DB] saveUserMemory failed: " + e.getMessage());
        }
    }

    public JSONArray getUserMemories(int userId, int limit) {
        JSONArray result = new JSONArray();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT memory_key, memory_value FROM user_memories " +
                "WHERE user_id = ? ORDER BY created_at DESC LIMIT ?");
            stmt.setInt(1, userId);
            stmt.setInt(2, limit);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                JSONObject mem = new JSONObject();
                mem.put("key", rs.getString("memory_key"));
                mem.put("value", rs.getString("memory_value"));
                result.put(mem);
            }
        } catch (Exception e) {
            System.err.println("[DB] getUserMemories failed: " + e.getMessage());
        }
        return result;
    }

    /** Gets all user memories as a formatted string for the LLM system prompt. */
    public String getUserMemoriesAsContext(int userId) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT memory_key, memory_value FROM user_memories " +
                "WHERE user_id = ? ORDER BY created_at DESC LIMIT 30");
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                sb.append("- ").append(rs.getString("memory_key")).append(": ")
                  .append(rs.getString("memory_value")).append("\n");
            }
        } catch (Exception e) {
            System.err.println("[DB] getUserMemoriesAsContext failed: " + e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Uses the LLM to extract important factual memories from a conversation exchange.
     * Runs asynchronously to avoid blocking the chat response.
     */
    public void extractAndSaveUserMemories(int userId, String userText, String aiResponse) {
        extractAndSaveUserMemories(userId, userText, aiResponse, IntelligentAssistant.currentSession());
    }

    public void extractAndSaveUserMemories(int userId, String userText, String aiResponse, long session) {
        if (!IntelligentAssistant.isSessionCurrent(userId, session)) return;
        if (userText == null || userText.trim().length() < 5) return;
        
        new Thread(() -> {
            try {
                String prompt = "You are a memory extraction engine. Analyze this conversation exchange and extract important FACTS about the user that should be remembered permanently.\n\n" +
                    "USER said: \"" + userText + "\"\n" +
                    "AI replied: \"" + (aiResponse != null ? aiResponse : "") + "\"\n\n" +
                    "Extract only concrete, factual, personal information. Examples: name, age, occupation, preferences, family members, goals, habits, important dates.\n" +
                    "If there is nothing worth remembering, output: {\"memories\": []}\n" +
                    "If there are memories, output JSON: {\"memories\": [{\"key\": \"name\", \"value\": \"The user's name is [their actual name]\"}, ...]}\n" +
                    "Output ONLY valid JSON.";

                JSONObject payload = new JSONObject();
                payload.put("model", model);
                payload.put("prompt", prompt);
                payload.put("stream", false);
                payload.put("format", "json");
                JSONObject options = new JSONObject();
                options.put("temperature", 0.1);
                payload.put("options", options);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ollamaUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JSONObject json = new JSONObject(response.body());
                String extracted = json.optString("response", "{}");

                if (extracted.contains("{") && extracted.contains("}")) {
                    extracted = extracted.substring(extracted.indexOf("{"), extracted.lastIndexOf("}") + 1);
                    JSONObject memData = new JSONObject(extracted);
                    if (memData.has("memories")) {
                        JSONArray memories = memData.getJSONArray("memories");
                        for (int i = 0; i < memories.length(); i++) {
                            JSONObject mem = memories.getJSONObject(i);
                            String key = mem.optString("key", "general");
                            String value = mem.optString("value", "");
                            if (!value.isEmpty()) {
                                synchronized (IntelligentAssistant.class) {
                                    if (!IntelligentAssistant.isSessionCurrent(userId, session)) return;
                                    saveUserMemory(userId, key, value, "conversation");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Memory] Async memory extraction failed: " + e.getMessage());
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENT SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    public String createSession(int userId) {
        String token = UUID.randomUUID().toString();
        try (Connection conn = getConnection()) {
            // Delete old session for this user (one session per user)
            PreparedStatement del = conn.prepareStatement("DELETE FROM user_sessions WHERE user_id = ?");
            del.setInt(1, userId);
            del.executeUpdate();
            
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO user_sessions (user_id, session_token, expires_at) VALUES (?, ?, DATEADD('DAY', 30, CURRENT_TIMESTAMP))");
            stmt.setInt(1, userId);
            stmt.setString(2, token);
            stmt.executeUpdate();
            
            // Update last_login
            PreparedStatement updateLogin = conn.prepareStatement(
                "UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE user_id = ?");
            updateLogin.setInt(1, userId);
            updateLogin.executeUpdate();
            
            System.out.println("[Session] Created session for user " + userId);
            return token;
        } catch (Exception e) {
            System.err.println("[Session] createSession failed: " + e.getMessage());
            return null;
        }
    }

    /** Returns user_id if session is valid and not expired. -1 otherwise. */
    public int getUserBySession(String token) {
        if (token == null || token.isEmpty()) return -1;
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT user_id FROM user_sessions WHERE session_token = ? AND expires_at > CURRENT_TIMESTAMP");
            stmt.setString(1, token);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("user_id");
            }
        } catch (Exception e) {
            System.err.println("[Session] getUserBySession failed: " + e.getMessage());
        }
        return -1;
    }

    public void invalidateSession(int userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement stmt = conn.prepareStatement("DELETE FROM user_sessions WHERE user_id = ?");
            stmt.setInt(1, userId);
            stmt.executeUpdate();
            System.out.println("[Session] Session invalidated for user " + userId);
        } catch (Exception e) {
            System.err.println("[Session] invalidateSession failed: " + e.getMessage());
        }
    }
}
