package com.assistant;

import java.sql.Connection;
import java.sql.PreparedStatement;

public class ConversationIsolationTest {
    public static void main(String[] args) throws Exception {
        MemoryManager memory = new MemoryManager();
        int userA = memory.createNewUser();
        int userB = memory.createNewUser();
        int previousUser = Engine.currentUserId;
        try {
            int conversationA = memory.createConversation(userA, "A conversation");
            int conversationB = memory.createConversation(userB, "B conversation");
            memory.saveMessage(conversationA, "user", "A private question");
            memory.saveMessage(conversationA, "assistant", "A private answer");
            memory.saveMessage(conversationB, "user", "B private question");
            check(memory.conversationBelongsToUser(conversationA, userA), "A owns their history");
            check(!memory.conversationBelongsToUser(conversationA, userB), "B cannot load A history");
            check(memory.getConversationsForUser(userB).length() == 1, "B history contains only B conversation");
            check(memory.getConversationsForUser(userB).getJSONObject(0).getInt("conversation_id") == conversationB,
                    "B conversation lookup is scoped");
            Engine.currentUserId = userA;
            long sessionA = IntelligentAssistant.currentSession();
            IntelligentAssistant.queueSpeech(userA, sessionA, "A answer");
            check(IntelligentAssistant.ttsQueue.size() == 1, "A speech is queued");
            IntelligentAssistant.invalidateSessionWork();
            Engine.currentUserId = userB;
            check(IntelligentAssistant.ttsQueue.isEmpty(), "Logout removes queued speech");
            check(!IntelligentAssistant.isSessionCurrent(userA, sessionA), "A response is cancelled");
            IntelligentAssistant.queueSpeech(userA, sessionA, "Late A answer");
            check(IntelligentAssistant.ttsQueue.isEmpty(), "Late A speech is rejected");
            long sessionB = IntelligentAssistant.currentSession();
            IntelligentAssistant.queueSpeech(userB, sessionB, "B answer");
            check(IntelligentAssistant.ttsQueue.take().text().equals("B answer"), "B can speak normally");
            IntelligentAssistant.invalidateSessionWork();
            Engine.currentUserId = userA;
            check(!IntelligentAssistant.isSessionCurrent(userA, sessionA), "Relogin does not revive old A work");
            check(memory.getMessagesForConversation(conversationA).length() == 2, "Logout preserves A stored history");
            memory.saveUserProfile(userA, "Name", "Reset Test Account");
            memory.saveUserProfile(userA, "Email", "reset-test-" + userA + "@example.invalid");
            memory.saveUserProfile(userA, "Password", "temporary-test-password");
            memory.logConversation(userA, "User", "A legacy message", "neutral");
            memory.logConversation(userB, "User", "B legacy message", "neutral");
            check(memory.resetTargetedMemory(userA), "User-specific reset succeeds");
            MemoryManager reopened = new MemoryManager();
            check(reopened.getConversationsForUser(userA).isEmpty(), "A history stays deleted after reopening");
            check(reopened.getMessagesForConversation(conversationA).isEmpty(), "A messages are deleted");
            check(reopened.getMessagesForConversation(conversationB).length() == 1, "B messages are retained");
            check(reopened.getConversationsForUser(userB).length() == 1, "B conversations are retained");
            check("Reset Test Account".equals(reopened.getUserProfileValue(userA, "Name")), "Account profile survives reset");
            check(reopened.authenticateEmail("reset-test-" + userA + "@example.invalid", "temporary-test-password") == userA,
                    "Login still works after reset");
            try (Connection conn = reopened.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM conversation_logs WHERE user_id = ?")) {
                stmt.setInt(1, userA);
                try (java.sql.ResultSet rows = stmt.executeQuery()) {
                    rows.next();
                    check(rows.getInt(1) == 0, "A legacy logs are deleted");
                }
                stmt.setInt(1, userB);
                try (java.sql.ResultSet rows = stmt.executeQuery()) {
                    rows.next();
                    check(rows.getInt(1) == 1, "B legacy logs are retained");
                }
            }
            System.out.println("Conversation isolation tests passed.");
        } finally {
            Engine.currentUserId = previousUser;
            IntelligentAssistant.ttsQueue.clear();
            try (Connection conn = memory.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM users WHERE user_id IN (?, ?)")) {
                stmt.setInt(1, userA);
                stmt.setInt(2, userB);
                stmt.executeUpdate();
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
