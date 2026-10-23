package com.assistant;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class SemanticMemoryStore {

    private static class MemoryItem {
        final String text;
        final float[] vector;

        MemoryItem(String text, float[] vector) {
            this.text = text;
            this.vector = vector;
        }
    }

    private final Map<Integer, List<MemoryItem>> memoriesByUser = new ConcurrentHashMap<>();
    private final HttpClient client = HttpClient.newHttpClient();
    private final String ollamaUrl = "http://localhost:11434/api/embeddings";
    private final String embeddingModel = "nomic-embed-text";
    private final String dbUrl = MemoryManager.getDbPath();

    public SemanticMemoryStore() {
        setupDatabase();
    }

    private Connection getConnection() throws Exception {
        return DriverManager.getConnection(dbUrl);
    }

    private void setupDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS semantic_rag_memory (" +
                    "id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
                    "user_id INTEGER, " +
                    "memory_text TEXT, " +
                    "vector_data TEXT, " +
                    "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");
            try {
                stmt.executeUpdate("ALTER TABLE semantic_rag_memory ADD COLUMN IF NOT EXISTS user_id INTEGER");
            } catch (Exception ignored) {
            }
            // Older versions embedded onboarding email settings in semantic memory.
            // Remove those credential-adjacent records rather than ever feeding them to the model.
            try {
                stmt.executeUpdate("DELETE FROM semantic_rag_memory WHERE " +
                        "LOWER(memory_text) LIKE 'user profile %password:%' OR " +
                        "LOWER(memory_text) LIKE 'user profile %email:%' OR " +
                        "LOWER(memory_text) LIKE 'user profile %token:%' OR " +
                        "LOWER(memory_text) LIKE 'user profile %gmail%'");
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            System.err.println("Failed to create semantic RAG table: " + e.getMessage());
        }
    }

    private List<MemoryItem> getMemoriesForUser(int userId) {
        return memoriesByUser.computeIfAbsent(userId, this::loadMemoriesForUser);
    }

    private List<MemoryItem> loadMemoriesForUser(int userId) {
        List<MemoryItem> memories = new CopyOnWriteArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT memory_text, vector_data FROM semantic_rag_memory WHERE user_id = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    String vectorData = rows.getString("vector_data");
                    if (vectorData == null || vectorData.isEmpty()) continue;
                    JSONArray values = new JSONArray(vectorData);
                    float[] vector = new float[values.length()];
                    for (int i = 0; i < values.length(); i++) {
                        vector[i] = (float) values.getDouble(i);
                    }
                    memories.add(new MemoryItem(rows.getString("memory_text"), vector));
                }
            }
            System.out.println("Loaded " + memories.size() + " semantic memories for user " + userId + ".");
        } catch (Exception e) {
            System.err.println("Failed to load semantic memories for user " + userId + ": " + e.getMessage());
        }
        return memories;
    }

    public void addMemory(int userId, String text) {
        addMemory(userId, text, IntelligentAssistant.currentSession());
    }

    public void addMemory(int userId, String text, long session) {
        if (!IntelligentAssistant.isSessionCurrent(userId, session)) return;
        if (userId <= 0 || text == null || text.trim().isEmpty()) return;

        float[] vector = getEmbedding(text);
        if (vector == null) return;

        synchronized (IntelligentAssistant.class) {
            if (!IntelligentAssistant.isSessionCurrent(userId, session)) return;
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "INSERT INTO semantic_rag_memory (user_id, memory_text, vector_data) VALUES (?, ?, ?)")) {
                stmt.setInt(1, userId);
                stmt.setString(2, text);
                stmt.setString(3, new JSONArray(vector).toString());
                stmt.executeUpdate();
                getMemoriesForUser(userId).add(new MemoryItem(text, vector));
                System.out.println("Stored semantic memory for user " + userId + ".");
            } catch (Exception e) {
                System.err.println("Failed to save semantic memory for user " + userId + ": " + e.getMessage());
            }
        }
    }

    public void addMemory(String text) {
        addMemory(Engine.currentUserId, text);
    }

    public String searchMemory(int userId, String query, int topK) {
        if (userId <= 0 || query == null || query.trim().isEmpty()) return "";
        List<MemoryItem> memories = getMemoriesForUser(userId);
        if (memories.isEmpty()) return "";

        float[] queryVector = getEmbedding(query);
        if (queryVector == null) return "";

        class ScoredMemory {
            final String text;
            final double score;

            ScoredMemory(String text, double score) {
                this.text = text;
                this.score = score;
            }
        }

        List<ScoredMemory> scored = new java.util.ArrayList<>();
        for (MemoryItem memory : memories) {
            scored.add(new ScoredMemory(memory.text, cosineSimilarity(queryVector, memory.vector)));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        StringBuilder results = new StringBuilder();
        int count = 0;
        for (ScoredMemory memory : scored) {
            if (memory.score > 0.4) {
                results.append("- ").append(memory.text).append("\n");
                count++;
            }
            if (count >= topK) break;
        }
        return results.toString().trim();
    }

    public String searchMemory(String query, int topK) {
        return searchMemory(Engine.currentUserId, query, topK);
    }

    private float[] getEmbedding(String text) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("model", embeddingModel);
            payload.put("prompt", text);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(response.body());
            if (!json.has("embedding")) return null;

            JSONArray values = json.getJSONArray("embedding");
            float[] vector = new float[values.length()];
            for (int i = 0; i < values.length(); i++) {
                vector[i] = (float) values.getDouble(i);
            }
            return vector;
        } catch (Exception e) {
            System.err.println("Failed to get embedding from Ollama: " + e.getMessage());
            return null;
        }
    }

    private double cosineSimilarity(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null || vectorA.length == 0 || vectorA.length != vectorB.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += Math.pow(vectorA[i], 2);
            normB += Math.pow(vectorB[i], 2);
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public boolean resetMemory(int userId) {
        if (userId <= 0) return false;
        memoriesByUser.remove(userId);
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM semantic_rag_memory WHERE user_id = ?")) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
            System.out.println("Cleared semantic RAG memory for user " + userId + ".");
            return true;
        } catch (Exception e) {
            System.err.println("Failed to clear semantic RAG memory for user " + userId + ": " + e.getMessage());
            return false;
        }
    }

    public void resetMemory() {
        resetMemory(Engine.currentUserId);
    }
}
