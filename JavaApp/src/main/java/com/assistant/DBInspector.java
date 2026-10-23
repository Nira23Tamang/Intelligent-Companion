package com.assistant;
import java.sql.*;

public class DBInspector {
    public static void main(String[] args) {
        String url = MemoryManager.getDbPath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            
            System.out.println("=== USER PROFILE DATA ===");
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM user_profile")) {
                while (rs.next()) {
                    System.out.println("Profile -> id: " + rs.getInt("id") + ", user_id: " + rs.getInt("user_id") + 
                                       ", key: " + rs.getString("attribute_key") + ", val: " + rs.getString("attribute_value"));
                }
            } catch (Exception e) {
                System.out.println("user_profile error: " + e.getMessage());
            }
            
            System.out.println("\n=== USERS TABLE ===");
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
                while (rs.next()) {
                    System.out.println("User -> id: " + rs.getInt("user_id") + ", name: " + rs.getString("name") + ", email: " + rs.getString("email"));
                }
            } catch (Exception e) {
                System.out.println("users error: " + e.getMessage());
            }

            System.out.println("\n=== ALL TABLES AND ROW COUNTS ===");
            ResultSet rsTables = stmt.executeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'");
            java.util.List<String> tables = new java.util.ArrayList<>();
            while (rsTables.next()) tables.add(rsTables.getString(1));
            for (String t : tables) {
                try (ResultSet rc = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + t)) {
                    if (rc.next()) System.out.println("  " + t + ": " + rc.getInt(1) + " rows");
                } catch (Exception e) {}
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}