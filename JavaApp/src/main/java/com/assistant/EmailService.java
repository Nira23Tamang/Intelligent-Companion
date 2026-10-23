package com.assistant;

import java.util.Properties;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;

public class EmailService {

    public static String getSenderEmail() {
        return getSenderEmail(Engine.currentUserId);
    }

    public static String getSenderEmail(int userId) {
        if (userId <= 0) return "";
        try {
            MemoryManager memory = new MemoryManager();
            String email = memory.getUserProfileValue(userId, "Gmail_Address");
            if (email == null || email.trim().isEmpty()) {
                email = memory.getUserProfileValue(userId, "Email");
            }
            return email == null ? "" : email.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String getSenderPassword() {
        return getSenderPassword(Engine.currentUserId);
    }

    public static String getSenderPassword(int userId) {
        if (userId <= 0) return "";
        try {
            String password = new MemoryManager().getUserProfileValue(userId, "Gmail_App_Password");
            if (password == null || password.trim().isEmpty()) return "";
            String normalized = password.replaceAll("\\s+", "").trim();
            return normalized.length() == 16 ? normalized : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String readLatestEmails(int count) {
        return readLatestEmails(Engine.currentUserId, count);
    }

    public static String readLatestEmails(int userId, int count) {
        if (userId <= 0) return "Please log in before checking email.";

        String senderEmail = getSenderEmail(userId);
        String senderPassword = getSenderPassword(userId);
        if (senderEmail.isEmpty() || senderPassword.isEmpty()) {
            return "Failed to read emails. Gmail address or app password is not configured for this user.";
        }

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", "imap.gmail.com");
        props.put("mail.imaps.port", "993");
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.ssl.trust", "imap.gmail.com");
        props.put("mail.imaps.auth.mechanisms", "PLAIN LOGIN");

        try {
            Session session = Session.getInstance(props, null);
            Store store = session.getStore("imaps");
            store.connect(senderEmail, senderPassword);

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message[] messages = inbox.getMessages();
            int limit = Math.min(Math.max(0, count), messages.length);
            if (limit == 0) {
                inbox.close(false);
                store.close();
                return "You have no emails.";
            }

            StringBuilder response = new StringBuilder("Here are your latest emails. ");
            for (int i = messages.length - 1; i >= messages.length - limit; i--) {
                Message message = messages[i];
                String from = InternetAddress.toString(message.getFrom());
                if (from.contains("<")) {
                    from = from.substring(0, from.indexOf("<")).trim().replace("\"", "");
                }
                response.append("Email from ").append(from)
                        .append(" about ").append(message.getSubject()).append(". ");
            }

            inbox.close(false);
            store.close();
            return response.toString();
        } catch (Exception e) {
            System.err.println("[Email] Could not read email for user " + userId + ": " + e.getMessage());
            return "Failed to read emails. Please check the Gmail address and app password for this user.";
        }
    }
}
