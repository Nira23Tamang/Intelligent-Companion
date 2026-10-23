package com.assistant;

public class TestSaveProfile {
    public static void main(String[] args) {
        MemoryManager memory = new MemoryManager();
        int userId = memory.createNewUser();
        System.out.println("Created user: " + userId);
        memory.saveUserProfile(userId, "Name", "Test");
        System.out.println("isFirstRun: " + memory.isFirstRun());
    }
}
