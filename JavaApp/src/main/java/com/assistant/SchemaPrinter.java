package com.assistant;
import java.sql.*;

public class SchemaPrinter {
    public static void main(String[] args) {
        String url = MemoryManager.getDbPath();
        try (Connection conn = DriverManager.getConnection(url)) {
            String[] tables = {"CONVERSATION_LOGS", "REMINDERS", "SEMANTIC_RAG_MEMORY", "KNOWLEDGE_NODES", "KNOWLEDGE_EDGES", "CONTACTS"};
            for (String t : tables) {
                System.out.println("\nTABLE: " + t);
                try (ResultSet rs = conn.getMetaData().getColumns(null, null, t, null)) {
                    while (rs.next()) {
                        System.out.println("  " + rs.getString("COLUMN_NAME") + " (" + rs.getString("TYPE_NAME") + ")");
                    }
                } catch (Exception e) {
                    System.out.println("  error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}