package com.assistant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.json.JSONObject;

public class ReminderSchedulerIntegrationTest {
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        MemoryManager memory = new MemoryManager();
        int userId = memory.createNewUser();
        if (userId <= 0) throw new AssertionError("Could not create a disposable reminder test user.");

        try {
            verifyRelativeIntent(memory);
            verifyReminderPhrase();
            verifyOneMinuteReminder(memory, userId);
            verifyMultipleAndDuplicatePrevention(memory, userId);
            verifyMissedReminder(memory, userId);
            System.out.println("Reminder scheduler integration tests passed.");
        } finally {
            deleteTestUser(memory, userId);
        }
    }

    private static void verifyRelativeIntent(MemoryManager memory) {
        JSONObject intent = memory.analyzeIntent("Remind me to drink water in 1 minute.");
        assertEquals("SET_REMINDER", intent.optString("intent"), "relative reminder intent");
        assertEquals("drink water", intent.optString("task"), "relative reminder task");
        assertEquals("1 minute from now", intent.optString("time_description"), "relative reminder description");
    }

    private static void verifyReminderPhrase() {
        assertEquals("You had a reminder to drink water.",
                Engine.formatReminderPhrase("drink water"),
                "natural reminder phrase");
        assertEquals("You had a reminder to call John.",
                Engine.formatReminderPhrase("to call John."),
                "normalized reminder phrase");
    }

    private static void verifyOneMinuteReminder(MemoryManager memory, int userId) throws Exception {
        String task = "drink water";
        LocalDateTime dueAt = LocalDateTime.now().plusMinutes(1);
        assertTrue(memory.scheduleReminder(userId, task, dueAt.format(DATE_TIME_FORMAT)), "one-minute reminder should save");

        List<MemoryManager.DueReminder> due = waitForDueReminders(memory, userId, 70_000);
        assertEquals(1, due.size(), "one-minute reminder count");
        assertEquals(task, due.get(0).getTask(), "one-minute reminder task");
        assertEquals(0, memory.claimDueReminders(userId).size(), "one-minute reminder must not trigger twice");
    }

    private static void verifyMultipleAndDuplicatePrevention(MemoryManager memory, int userId) throws Exception {
        LocalDateTime dueAt = LocalDateTime.now().plusSeconds(2);
        assertTrue(memory.scheduleReminder(userId, "call John", dueAt.format(DATE_TIME_FORMAT)), "first multiple reminder should save");
        assertTrue(memory.scheduleReminder(userId, "submit my assignment", dueAt.format(DATE_TIME_FORMAT)), "second multiple reminder should save");

        List<MemoryManager.DueReminder> due = waitForDueReminders(memory, userId, 10_000);
        assertEquals(2, due.size(), "multiple due reminder count");
        assertTrue(containsTask(due, "call John"), "call John reminder should be returned");
        assertTrue(containsTask(due, "submit my assignment"), "assignment reminder should be returned");
        assertEquals(0, memory.claimDueReminders(userId).size(), "multiple reminders must not trigger twice");
    }

    private static void verifyMissedReminder(MemoryManager memory, int userId) {
        assertTrue(memory.scheduleReminder(
                userId,
                "buy a book",
                LocalDateTime.now().minusMinutes(2).format(DATE_TIME_FORMAT)),
                "missed reminder should save");

        List<MemoryManager.DueReminder> due = memory.claimDueReminders(userId);
        assertEquals(1, due.size(), "missed reminder count");
        assertEquals("buy a book", due.get(0).getTask(), "missed reminder task");
    }

    private static List<MemoryManager.DueReminder> waitForDueReminders(
            MemoryManager memory, int userId, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<MemoryManager.DueReminder> due = memory.claimDueReminders(userId);
            if (!due.isEmpty()) return due;
            Thread.sleep(250);
        }
        throw new AssertionError("Reminder did not become due before the test timeout.");
    }

    private static boolean containsTask(List<MemoryManager.DueReminder> reminders, String expectedTask) {
        for (MemoryManager.DueReminder reminder : reminders) {
            if (expectedTask.equals(reminder.getTask())) return true;
        }
        return false;
    }

    private static void deleteTestUser(MemoryManager memory, int userId) {
        try (Connection conn = memory.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM users WHERE user_id = ?")) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Could not clean up the disposable reminder test user.", e);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
