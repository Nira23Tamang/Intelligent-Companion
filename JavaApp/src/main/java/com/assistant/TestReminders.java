package com.assistant;

import org.json.JSONObject;

public class TestReminders {
    public static void main(String[] args) throws Exception {
        MemoryManager memory = new MemoryManager();
        
        // Reset DB to clean state
        memory.resetMemory();
        
        System.out.println("\n--- TEST 1: SETTING REMINDER ---");
        JSONObject intent1 = memory.analyzeIntent("Remind me at 4pm to buy a book.");
        System.out.println("Intent 1: " + intent1);
        if (intent1.optString("intent").equals("SET_REMINDER") && intent1.optBoolean("has_time", false)) {
            memory.scheduleReminder(1, intent1.getString("task"), intent1.getString("datetime"));
        }
        
        System.out.println("\n--- TEST 2: GETTING REMINDER ---");
        JSONObject intent2 = memory.analyzeIntent("At what time did I set the reminder?");
        System.out.println("Intent 2: " + intent2);
        if (intent2.optString("intent").equals("GET_REMINDERS") || intent2.optString("intent").equals("GET_REMINDER_DETAILS")) {
            System.out.println("Active Reminders Formatted: \n" + memory.getTodaysRemindersSummary(1));
        }

        System.out.println("\n--- TEST 3: MULTIPLE REMINDERS ---");
        JSONObject intent3 = memory.analyzeIntent("Remind me tomorrow at 10am to submit my assignment.");
        System.out.println("Intent 3: " + intent3);
        if (intent3.optString("intent").equals("SET_REMINDER") && intent3.optBoolean("has_time", false)) {
            memory.scheduleReminder(1, intent3.getString("task"), intent3.getString("datetime"));
        }
        
        System.out.println("\n--- TEST 4: CHECK ALL REMINDERS ---");
        JSONObject intent4 = memory.analyzeIntent("What reminders do I have?");
        System.out.println("Intent 4: " + intent4);
        if (intent4.optString("intent").equals("GET_REMINDERS")) {
            System.out.println("Active Reminders Formatted: \n" + memory.getTodaysRemindersSummary(1));
        }

        System.out.println("\n--- TEST 5: COMPLETE REMINDER ---");
        JSONObject intent5 = memory.analyzeIntent("I already bought the book.");
        System.out.println("Intent 5: " + intent5);

        System.out.println("\n--- TEST 6: CHECK REMAINING REMINDERS ---");
        System.out.println("Active Reminders Formatted: \n" + memory.getTodaysRemindersSummary(1));
        
        System.exit(0);
    }
}
