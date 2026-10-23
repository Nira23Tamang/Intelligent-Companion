package com.assistant;
import java.sql.*;

public class CheckRemindersNow {
    public static void main(String[] args) throws Exception {
        Class.forName("org.h2.Driver");
        String url = MemoryManager.getDbPath();
        Connection c = DriverManager.getConnection(url);
        Statement s = c.createStatement();
        // Print summary of user_ids only
        ResultSet r = s.executeQuery("SELECT user_id, COUNT(*) as cnt FROM reminders WHERE is_completed=0 GROUP BY user_id");
        System.out.println("=== PENDING REMINDERS BY USER_ID ===");
        while(r.next()) {
            System.out.println("user_id=" + r.getInt(1) + "  count=" + r.getInt(2));
        }
        r.close();
        // Also show what currentUserId Engine would use
        ResultSet r2 = s.executeQuery("SELECT id, user_id, is_completed, LEFT(task_description,40) as task FROM reminders ORDER BY id");
        System.out.println("=== ALL ROWS ===");
        while(r2.next()) {
            System.out.println("id="+r2.getInt(1)+" uid="+r2.getInt(2)+" done="+r2.getInt(3)+" task="+r2.getString(4));
        }
        r2.close();
        c.close();
    }
}