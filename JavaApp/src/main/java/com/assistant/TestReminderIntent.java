package com.assistant;

import org.json.JSONObject;

public class TestReminderIntent {
    public static void main(String[] args) {
        MemoryManager memory = new MemoryManager();
        String q = "What recent reminders did we set?";
        JSONObject res = memory.analyzeIntent(q);
        System.out.println("Intent for '" + q + "': " + res.toString());

        String q2 = "Remind me tomorrow at 9am to submit grant";
        JSONObject res2 = memory.analyzeIntent(q2);
        System.out.println("Intent for '" + q2 + "': " + res2.toString());

        String q3 = "Read my latest email";
        JSONObject res3 = memory.analyzeIntent(q3);
        System.out.println("Intent for '" + q3 + "': " + res3.toString());
    }
}