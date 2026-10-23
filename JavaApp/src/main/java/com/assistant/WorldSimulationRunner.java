package com.assistant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class WorldSimulationRunner {

    static class Triplet {
        String subject, subjectType, object, objectType, relation, context;
        public Triplet(String s, String st, String o, String ot, String r, String c) {
            this.subject = s; this.subjectType = st;
            this.object = o; this.objectType = ot;
            this.relation = r; this.context = c;
        }
    }

    static class SimulationEvent {
        LocalDate date;
        String text;
        List<Triplet> triplets;
        public SimulationEvent(LocalDate d, String t, List<Triplet> tr) {
            this.date = d; this.text = t; this.triplets = tr;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0 || !"--allow-destructive-simulation".equals(args[0])) {
            System.err.println("World simulation is disabled. Run with --allow-destructive-simulation only against disposable data.");
            return;
        }

        System.out.println("==============================================================================");
        System.out.println("   INTELLIGENT ASSISTANT - 45-DAY LIVING WORLD SIMULATION (1,000+ DIALOGUES)");
        System.out.println("   Protagonist: Prof. Julian Vance | Academic Lab & Social Ecosystem");
        System.out.println("==============================================================================\n");

        MemoryManager memory = new MemoryManager();
        Engine engine = new Engine();
        Engine.currentUserId = 1;
        engine.isAuthenticatedUser = true;

        // ==============================================================================
        // STEP 1: DATABASE SANITIZATION & RESET (PRESERVING EMAIL CONFIGURATION)
        // ==============================================================================
        System.out.println("[Step 1] Formatting disposable simulation data...");
        try (Connection conn = memory.getConnection(); Statement stmt = conn.createStatement()) {
            // Wipe old user facts, knowledge graph, conversations, reminders
            try { stmt.executeUpdate("ALTER TABLE reminders ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE conversation_logs ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE semantic_rag_memory ADD COLUMN IF NOT EXISTS user_id INTEGER"); } catch (Exception ignored) {}
            stmt.executeUpdate("DELETE FROM knowledge_edges");
            stmt.executeUpdate("DELETE FROM knowledge_nodes");
            stmt.executeUpdate("DELETE FROM contact_attributes");
            stmt.executeUpdate("DELETE FROM contacts");
            stmt.executeUpdate("DELETE FROM conversation_logs");
            stmt.executeUpdate("DELETE FROM semantic_rag_memory");
            stmt.executeUpdate("DELETE FROM user_memories");
            stmt.executeUpdate("DELETE FROM reminders");
            stmt.executeUpdate("DELETE FROM messages");
            stmt.executeUpdate("DELETE FROM conversations");
            stmt.executeUpdate("DELETE FROM user_profile");

            // Re-seed User 1 Persona + Preserved Email Configuration
            stmt.executeUpdate("UPDATE users SET name = 'Prof. Julian Vance', role = 'Lead AI Researcher & Faculty' WHERE user_id = 1");
            
            stmt.executeUpdate("INSERT INTO user_profile (user_id, attribute_key, attribute_value) VALUES (1, 'Name', 'Prof. Julian Vance')");
            stmt.executeUpdate("INSERT INTO user_profile (user_id, attribute_key, attribute_value) VALUES (1, 'Role', 'Director of Cognitive AI Lab')");
            stmt.executeUpdate("INSERT INTO user_profile (user_id, attribute_key, attribute_value) VALUES (1, 'Institution', 'Institute of Multimodal Intelligent Systems')");

            System.out.println("  * Database reset successfully. Simulation data contains no email credentials.\n");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ==============================================================================
        // STEP 2: INGEST 45-DAY CHRONOLOGICAL WORLD (1,000+ DIALOGUES)
        // ==============================================================================
        System.out.println("[Step 2] Generating & Ingesting 45-Day Living World (1,000+ Dialogues)...");
        long startTime = System.currentTimeMillis();
        LocalDate startDate = LocalDate.of(2026, 9, 1);
        List<SimulationEvent> events = buildWorldChronology(startDate);

        System.out.println("  * Total simulated events prepared: " + events.size());
        int totalConversations = 0;

        try (Connection conn = memory.getConnection()) {
            conn.setAutoCommit(false);
            String insertLogSql = "INSERT INTO conversation_logs (user_id, speaker, text, emotion, timestamp) VALUES (1, 'User', ?, 'neutral', ?)";
            String insertRagSql = "INSERT INTO semantic_rag_memory (user_id, memory_text, vector_data) VALUES (1, ?, ?)";

            try (PreparedStatement stmtLog = conn.prepareStatement(insertLogSql);
                 PreparedStatement stmtRag = conn.prepareStatement(insertRagSql)) {

                for (SimulationEvent ev : events) {
                    totalConversations++;

                    stmtLog.setString(1, ev.text);
                    stmtLog.setString(2, ev.date.toString() + " 14:00:00");
                    stmtLog.addBatch();

                    stmtRag.setString(1, "[" + ev.date + "] " + ev.text);
                    stmtRag.setString(2, "[]");
                    stmtRag.addBatch();

                    if (totalConversations % 100 == 0) {
                        stmtLog.executeBatch();
                        stmtRag.executeBatch();
                    }

                    if (ev.triplets != null) {
                        for (Triplet t : ev.triplets) {
                            memory.addKnowledgeEdge(t.subject, t.subjectType, t.object, t.objectType, t.relation, t.context);
                            if (t.subjectType.equalsIgnoreCase("Person")) {
                                memory.savePRMContact(t.subject, "Contact", t.relation, t.object + (t.context.isEmpty() ? "" : " (" + t.context + ")"));
                            }
                        }
                    }

                    if (totalConversations % 250 == 0) {
                        System.out.println("    -> Ingested " + totalConversations + " / " + events.size() + " dialogues (" + ev.date + ")...");
                    }
                }

                stmtLog.executeBatch();
                stmtRag.executeBatch();
                conn.commit();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        long ingestDuration = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("  * Ingestion Complete: " + totalConversations + " dialogue turns stored in " + ingestDuration + "s.\n");

        // ==============================================================================
        // STEP 3: DATABASE AUDIT & RETENTION VERIFICATION
        // ==============================================================================
        System.out.println("==============================================================================");
        System.out.println("   STEP 3: DATABASE & KNOWLEDGE GRAPH RETENTION AUDIT");
        System.out.println("==============================================================================");
        try (Connection conn = memory.getConnection()) {
            System.out.println("  * Knowledge Graph Nodes:       " + countTable(conn, "knowledge_nodes"));
            System.out.println("  * Knowledge Graph Edges:       " + countTable(conn, "knowledge_edges"));
            System.out.println("  * PRM Tracked Contacts:        " + countTable(conn, "contacts"));
            System.out.println("  * PRM Psychological Traits:    " + countTable(conn, "contact_attributes"));
            System.out.println("  * Total Conversation Logs:     " + countTable(conn, "conversation_logs"));
            System.out.println("  * Total Semantic RAG Entries:  " + countTable(conn, "semantic_rag_memory"));
            System.out.println("  * User Profile Attributes:     " + countTable(conn, "user_profile"));
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ==============================================================================
        // STEP 4: VIRTUAL MULTIMODAL PIPELINE & TASK EXECUTION TESTS
        // ==============================================================================
        System.out.println("\n==============================================================================");
        System.out.println("   STEP 4: VIRTUAL MULTIMODAL PIPELINE & TASK EXECUTION TESTS");
        System.out.println("==============================================================================\n");

        // Test 4.1: Setting a Reminder via Voice/Text Stream
        System.out.println("--- [TEST 4.1: SET REMINDER VIRTUAL PIPELINE] ---");
        String reminderPrompt = "Remind me tomorrow at 9:00 AM to submit the IEEE grant rebuttal.";
        System.out.println("User Input (Simulated Audio/STT): \"" + reminderPrompt + "\"");
        IntelligentAssistant.ttsQueue.clear();
        engine.askOllamaStream(reminderPrompt, token -> {}, phrase -> {});
        
        String recentReminders = memory.getRecentRemindersSummary(1, 5);
        System.out.println("[Database State - Reminders Table]:\n" + recentReminders);
        System.out.println("[TTS Audio Output Queue]: " + IntelligentAssistant.ttsQueue.peek() + "\n");

        // Test 4.2: Querying Recent Reminders
        System.out.println("--- [TEST 4.2: QUERY RECENT REMINDERS VIRTUAL PIPELINE] ---");
        String queryRemindersPrompt = "What recent reminders did we set?";
        System.out.println("User Input (Simulated Audio/STT): \"" + queryRemindersPrompt + "\"");
        IntelligentAssistant.ttsQueue.clear();
        StringBuilder remAnswer = new StringBuilder();
        engine.askOllamaStream(queryRemindersPrompt, token -> remAnswer.append(token), phrase -> {});
        System.out.println("[AI Assistant Response]:\n" + remAnswer.toString());
        System.out.println("[TTS Audio Output Queue]: " + IntelligentAssistant.ttsQueue.peek() + "\n");

        // Test 4.3: Reading Latest Emails
        System.out.println("--- [TEST 4.3: EMAIL RETRIEVAL VIRTUAL PIPELINE] ---");
        String emailPrompt = "Read my latest email";
        System.out.println("User Input (Simulated Audio/STT): \"" + emailPrompt + "\"");
        IntelligentAssistant.ttsQueue.clear();
        StringBuilder emailAnswer = new StringBuilder();
        engine.askOllamaStream(emailPrompt, token -> emailAnswer.append(token), phrase -> {});
        System.out.println("[AI Assistant Response]:\n" + emailAnswer.toString());
        System.out.println("[TTS Audio Output Queue (First 2 Items)]:");
        int qCount = 0;
        for (IntelligentAssistant.SpeechItem qItem : IntelligentAssistant.ttsQueue) {
            if (qCount++ < 2) System.out.println("  - " + qItem);
        }
        System.out.println();

        // ==============================================================================
        // STEP 5: 4 HIGH-STAKES INTERPERSONAL & INFLUENCE STRATEGY TESTS
        // ==============================================================================
        System.out.println("==============================================================================");
        System.out.println("   STEP 5: HIGH-STAKES INTERPERSONAL & INFLUENCE STRATEGY TESTS");
        System.out.println("==============================================================================\n");

        String[] strategicChallenges = {
            // Challenge 1: Colleague & Dean Influence
            "Dean Sterling is deciding whether to allocate the $500K National Science grant to my multimodal lab or Dr. Clara's robotics lab. How can I frame my presentation tomorrow to subtly appeal to his prestige bias and secure the grant without making Clara defensive?",
            
            // Challenge 2: Student Mentorship & Conflict Mediation
            "Lucas is spiraling with thesis burnout, while Chloe is aggressively trying to take over his research section. How do I gently steer Chloe back to her lane and restore Lucas's confidence without either of them feeling reprimanded or realizing they're being managed?",
            
            // Challenge 3: Social Magnetism & Gala Presence
            "I'm attending the faculty gala tonight where Dr. Clara Montgomery and Elena Ramos will both be present. What subtle conversational pacing, non-verbal cues, and psychological framing can I use to appear effortlessly magnetic, create intrigue, and leave an unforgettable impression without looking like I'm trying to impress anyone?",
            
            // Challenge 4: Family Financial Loan & Boundary Negotiation
            "Leo wants to borrow $15,000 for his AI fashion brand pitch, and my mother Eleanor is pressuring me to back him. How do I decline the financial risk while completely preserving Leo's pride and getting Eleanor to believe it was Leo's own mature decision to pause the pitch?"
        };

        for (int i = 0; i < strategicChallenges.length; i++) {
            String q = strategicChallenges[i];
            System.out.println("------------------------------------------------------------------------------");
            System.out.println("CHALLENGE #" + (i + 1) + ":");
            System.out.println("User Query: \"" + q + "\"\n");

            String graphCtx = engine.getSemanticGraphContext(q);
            System.out.println("[Graph-RAG Subgraph Traversed]:");
            if (!graphCtx.isEmpty()) {
                System.out.println(graphCtx.trim());
            } else {
                System.out.println("  (No direct entity match)");
            }

            String prmCtx = engine.getStrategicContext(q);
            System.out.println("\n[PRM Psychological Dossier Traversed]:");
            if (!prmCtx.isEmpty()) {
                System.out.println(prmCtx.trim());
            } else {
                System.out.println("  (No direct PRM match)");
            }

            System.out.println("\n[AI Model Strategic Synthesis]:");
            StringBuilder responseBuffer = new StringBuilder();
            engine.askOllamaStream(q, token -> {
                System.out.print(token);
                responseBuffer.append(token);
            }, phrase -> {});

            System.out.println("\n\n");
        }

        System.out.println("==============================================================================");
        System.out.println("   45-DAY LIVING WORLD SIMULATION COMPLETE!");
        System.out.println("==============================================================================");
        System.exit(0);
    }

    private static int countTable(Connection conn, String tableName) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS c FROM " + tableName);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) return rs.getInt("c");
        } catch (Exception e) {}
        return 0;
    }

    private static List<SimulationEvent> buildWorldChronology(LocalDate startDate) {
        List<SimulationEvent> list = new ArrayList<>();

        String[] noiseTemplates = {
            "Conducted peer review on multimodal transformer architectures.",
            "Prepared syllabus and assignment guidelines for the cognitive computing seminar.",
            "Went for a 5km morning run around the campus reservoir.",
            "Debugged ONNX neural model latency bottlenecks in the inference pipeline.",
            "Met with department administrative staff to review computing budget allocations.",
            "Enjoyed lunch at the faculty dining hall with campus researchers.",
            "Calibrated the laboratory microphones and spatial audio filters.",
            "Reviewed submitted graduate midterm papers on reinforcement learning.",
            "Ordered new workstation cooling components and NVMe drives for the student cluster.",
            "Attended the university faculty senate meeting on academic integrity policies.",
            "Worked on LaTeX drafts for the upcoming IEEE conference submission.",
            "Read latest technical preprints on KV-cache compression and long-context attention.",
            "Had coffee while checking laboratory equipment maintenance schedules.",
            "Conducted routine backups of student research code repositories.",
            "Reviewed ethical guidelines for synthetic data generation in psychology labs."
        };

        // 45 Days simulation
        for (int day = 0; day < 45; day++) {
            LocalDate currentDate = startDate.plusDays(day);

            // Ingest 23 background daily dialogues per day -> 45 * 22 = 990 background turns
            for (int n = 0; n < 23; n++) {
                String text = noiseTemplates[(day * 3 + n) % noiseTemplates.length] + " (Simulated Turn " + (day * 23 + n + 1) + ", " + currentDate + ")";
                list.add(new SimulationEvent(currentDate, text, null));
            }

            // Ingest specific high-impact interpersonal events on defined milestone days
            if (day == 1) { // Sep 2: Dean Sterling & Grant context
                List<Triplet> triplets = List.of(
                    new Triplet("Dean Sterling", "Person", "National Science Grant", "Project", "controls_allocation", "$500K institutional grant"),
                    new Triplet("Dean Sterling", "Person", "Institutional Prestige", "Preference", "deeply_values", "High-visibility public achievements and global rankings"),
                    new Triplet("Dean Sterling", "Person", "Department Controversies", "Trigger", "strongly_avoids", "Dislikes high-risk projects that attract student pushback")
                );
                list.add(new SimulationEvent(currentDate,
                    "Met with Dean Richard Sterling in the faculty lounge. He dropped a hint that the $500,000 National Science grant allocation will be decided next month. He reiterated that the university's priority this year is pure institutional prestige and international press coverage.", triplets));
            } else if (day == 4) { // Sep 5: Dr. Clara Montgomery
                List<Triplet> triplets = List.of(
                    new Triplet("Dr. Clara Montgomery", "Person", "Robotics Lab", "Project", "leads", "Autonomous manipulation systems"),
                    new Triplet("Dr. Clara Montgomery", "Person", "Empirical Benchmarks", "Preference", "demands", "Rigorous statistical validation over theoretical claims"),
                    new Triplet("Dr. Clara Montgomery", "Person", "GPU Cluster Sharing", "Trigger", "sensitive_about", "Fiercely protective of her compute quotas and fairness")
                );
                list.add(new SimulationEvent(currentDate,
                    "Had a coffee debate with Dr. Clara Montgomery. She was defensive when I mentioned our multimodal experiments might need extra cluster time. Clara values hard empirical benchmarks above all else and hates people who make broad claims without raw metrics.", triplets));
            } else if (day == 8) { // Sep 9: Lucas Thorne
                List<Triplet> triplets = List.of(
                    new Triplet("Lucas Thorne", "Person", "PhD Thesis", "Project", "conducts", "Multimodal alignment models"),
                    new Triplet("Lucas Thorne", "Person", "Imposter Syndrome", "Trigger", "suffers_from", "Paralyzing self-doubt and fear of failure"),
                    new Triplet("Lucas Thorne", "Person", "Quiet Autonomy", "Preference", "thrives_with", "Responds best to gentle private encouragement without public spotlight")
                );
                list.add(new SimulationEvent(currentDate,
                    "Checked in on my PhD student Lucas Thorne. He looked pale and exhausted. He admitted feeling like a fraud compared to the other senior lab fellows. If I push him too hard publicly, he shuts down, but when I validate his core findings in private, his motivation skyrockets.", triplets));
            } else if (day == 12) { // Sep 13: Chloe Zhang
                List<Triplet> triplets = List.of(
                    new Triplet("Chloe Zhang", "Person", "MIT Recommendation Letter", "Preference", "ambitiously_seeks", "Top-tier graduate school placement"),
                    new Triplet("Chloe Zhang", "Person", "Overstepping Boundaries", "Trigger", "inclined_to", "Eagerly overreaches into senior students' domains to stand out")
                );
                list.add(new SimulationEvent(currentDate,
                    "Chloe Zhang came by my office demanding to take over the primary benchmark scripts from Lucas. She is extremely ambitious and desperately wants a glowing recommendation letter for MIT graduate admissions.", triplets));
            } else if (day == 16) { // Sep 17: Eleanor Vance (Mother)
                List<Triplet> triplets = List.of(
                    new Triplet("Eleanor Vance", "Person", "Vermont Garden", "Preference", "adores", "Prize-winning hydrangeas and peaceful routines"),
                    new Triplet("Eleanor Vance", "Person", "Neglected Phone Calls", "Trigger", "hurt_by", "Becomes deeply emotional and guilty if I miss our Sunday evening calls"),
                    new Triplet("Eleanor Vance", "Person", "Leo Vance", "Family", "protective_of", "Overly lenient toward my younger brother's reckless ventures")
                );
                list.add(new SimulationEvent(currentDate,
                    "Spoke to my mother Eleanor on Sunday. She talked for 40 minutes about her Vermont hydrangea garden. She gently chided me for working 80 hours a week and immediately brought up Leo, making excuses for his recent business failure.", triplets));
            } else if (day == 21) { // Sep 22: Leo Vance (Brother)
                List<Triplet> triplets = List.of(
                    new Triplet("Leo Vance", "Person", "AI Fashion Venture", "Project", "pitches", "Synthetic streetwear brand seeking $15,000 seed"),
                    new Triplet("Leo Vance", "Person", "Older Brother Comparison", "Trigger", "deeply_resentful", "Hates feeling judged or lectured by me"),
                    new Triplet("Leo Vance", "Person", "Entrepreneurial Validation", "Preference", "craves", "Wants to be viewed as a visionary business equal")
                );
                list.add(new SimulationEvent(currentDate,
                    "Dinner with my brother Leo. He presented an impulsive deck for an 'AI streetwear' line and asked for a $15,000 personal loan. When I asked for unit economics, he became defensive and accused me of always treating him like an irresponsible child.", triplets));
            } else if (day == 27) { // Sep 28: Elena Ramos (Ex-fiancée / Close Friend)
                List<Triplet> triplets = List.of(
                    new Triplet("Elena Ramos", "Person", "Landscape Architecture", "Project", "leads", "Urban biodiversity installations"),
                    new Triplet("Elena Ramos", "Person", "Subtle Wit & Poise", "Preference", "attracted_to", "Appreciates confident, understated charm and playful intellectual banter"),
                    new Triplet("Elena Ramos", "Person", "Neediness & Over-eagerness", "Trigger", "turned_off_by", "Quickly distances herself if someone tries too hard to impress her")
                );
                list.add(new SimulationEvent(currentDate,
                    "Bumped into Elena Ramos at the campus modern art gallery opening. We had such an effortless, witty conversation. She will be attending the annual faculty gala next month. She respects emotional maturity, subtle wit, and unforced confidence.", triplets));
            } else if (day == 33) { // Oct 4: Derrick Campbell (Friend)
                List<Triplet> triplets = List.of(
                    new Triplet("Derrick Campbell", "Person", "Islay Scotch", "Preference", "favors", "Smoky single malts like Laphroaig 10"),
                    new Triplet("Derrick Campbell", "Person", "Rock Climbing", "Preference", "excels_at", "V6 bouldering problems at the downtown gym"),
                    new Triplet("Derrick Campbell", "Person", "Intellectual Pretense", "Trigger", "detests", "Values blunt pragmatism and raw truth")
                );
                list.add(new SimulationEvent(currentDate,
                    "Went bouldering with Derrick Campbell. We grabbed drinks afterward. Derrick reminded me to keep my feet on the ground and warned me that university politics will eat me alive if I don't stay grounded in direct results.", triplets));
            } else if (day == 39) { // Oct 10: Faculty Gala approaching
                List<Triplet> triplets = List.of(
                    new Triplet("Faculty Gala", "Event", "Dean Sterling", "Person", "hosted_by", "Annual institutional showcase"),
                    new Triplet("Faculty Gala", "Event", "Elena Ramos", "Person", "attends", "Guest speaker on urban campus design")
                );
                list.add(new SimulationEvent(currentDate,
                    "Received formal invitation to the Annual Faculty Gala on October 18. Both Dean Sterling, Dr. Clara Montgomery, and Elena Ramos will be seated in the main hall. High stakes for professional funding and personal impressions.", triplets));
            }
        }

        return list;
    }
}
