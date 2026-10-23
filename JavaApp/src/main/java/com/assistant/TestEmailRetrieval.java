package com.assistant;

public class TestEmailRetrieval {
    public static void main(String[] args) {
        Engine.currentUserId = 1;
        String email = EmailService.getSenderEmail();
        String password = EmailService.getSenderPassword();
        System.out.println("Testing EmailService with currentUserId = 1...");
        System.out.println("Sender Email: " + email);
        System.out.println("App Password Configured: " + (!password.isEmpty()));
        System.out.println("App Password Length: " + password.length());
        try {
            String result = EmailService.readLatestEmails(2);
            System.out.println("Result: " + result);
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
        }
    }
}