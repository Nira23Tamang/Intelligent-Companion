package com.assistant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class MegaSimulationRunner {

    public static void main(String[] args) {
        if (args.length == 0 || !"--allow-destructive-simulation".equals(args[0])) {
            System.err.println("Mega simulation is disabled. Run with --allow-destructive-simulation only against disposable data.");
            return;
        }

        System.out.println("==============================================================================");
        System.out.println("  6-MONTH LIVING WORLD MEGA-SIMULATION (1,250+ CONVERSATIONS)");
        System.out.println("  Protagonist: Aryan | Setting: Apex Tech & Silicon Hills (Sep 1 -> Feb 28)");
        System.out.println("==============================================================================\n");

        MemoryManager memory = new MemoryManager();
        Engine engine = new Engine();

        // PHASE 1: GENERATE & INGEST 6 MONTHS OF CHRONOLOGICAL EVENTS (180 DAYS)
        System.out.println("[Phase 1] Initializing 6-Month Chronological Lifelog Ingestion...");
        long startTime = System.currentTimeMillis();

        LocalDate startDate = LocalDate.of(2025, 9, 1);
        int totalConversations = 0;

        List<SimulationEvent> events = buildSimulationChronology(startDate);
        System.out.println("[Phase 1] Ingesting " + events.size() + " structured events + daily lifelogs across 6 months...");

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

                    if (totalConversations % 300 == 0) {
                        System.out.println("  * Ingested " + totalConversations + " / " + events.size() + " conversations (Simulated: " + ev.date + ")...");
                    }
                }

                stmtLog.executeBatch();
                stmtRag.executeBatch();
                conn.commit();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        long ingestionTime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n[Phase 1 Complete] Ingested " + totalConversations + " conversations in " + ingestionTime + "s!\n");

        // PHASE 2: AUDIT DATABASE KNOWLEDGE RETENTION
        System.out.println("==============================================================================");
        System.out.println("  PHASE 2: DATABASE & KNOWLEDGE GRAPH RETENTION AUDIT");
        System.out.println("==============================================================================");

        try (Connection conn = memory.getConnection()) {
            int nodeCount = countTable(conn, "knowledge_nodes");
            int edgeCount = countTable(conn, "knowledge_edges");
            int contactCount = countTable(conn, "contacts");
            int attrCount = countTable(conn, "contact_attributes");
            int logCount = countTable(conn, "conversation_logs");
            int ragCount = countTable(conn, "semantic_rag_memory");

            System.out.println("  * Knowledge Graph Nodes:       " + nodeCount + " distinct concept & person entities");
            System.out.println("  * Knowledge Graph Edges:       " + edgeCount + " directed semantic relationships");
            System.out.println("  * PRM Tracked Contacts:        " + contactCount + " core individuals");
            System.out.println("  * PRM Behavioral Attributes:   " + attrCount + " psychological/personal notes");
            System.out.println("  * Total Conversation Logs:     " + logCount + " turns stored");
            System.out.println("  * Total RAG Memories:          " + ragCount + " entries stored");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // PHASE 3: 5 HIGH-STAKES MULTI-HOP STRATEGIC STRESS TESTS
        System.out.println("\n==============================================================================");
        System.out.println("  PHASE 3: MULTI-HOP STRATEGIC & PSYCHOLOGICAL STRESS TESTS");
        System.out.println("==============================================================================\n");

        String[] testQuestions = {
            "I'm taking Nina Chen, Rohan, and Dr. Thorne out to celebrate our milestone. What restaurant type should we choose, what should I strictly avoid, and what opening topic will impress Dr. Thorne?",
            "Ethan Wright is gaming loudly at 2:30 AM before my 8:00 AM Google interview. What is the best psychological leverage and exact framing to get him to stop immediately without a fight?",
            "Rohan still hasn't repaid the $50 from the hackathon because of his car transmission repair, but I need his graphic design help for my thesis slides. How can I propose a fair trade without making him feel guilty?",
            "Vikram hogged the lab GPU cluster, delaying my neural latency benchmarks. How should I email Dr. Thorne to request a 3-day thesis extension without sounding like I'm making excuses?",
            "Maya Sharma is coming for the apartment inspection tomorrow. What can I mention to stay on her good side and secure that $100 rent credit?"
        };

        for (int i = 0; i < testQuestions.length; i++) {
            String q = testQuestions[i];
            System.out.println("------------------------------------------------------------------------------");
            System.out.println("TEST CHALLENGE #" + (i + 1) + ":");
            System.out.println("Query: \"" + q + "\"\n");

            String graphCtx = engine.getSemanticGraphContext(q);
            System.out.println("[Graph-RAG Subgraph Traversed]:");
            if (!graphCtx.isEmpty()) {
                System.out.println(graphCtx.trim());
            } else {
                System.out.println("  (No direct graph match)");
            }

            System.out.println("\n[AI Strategic Response Synthesis]:");
            StringBuilder responseBuffer = new StringBuilder();
            engine.askOllamaStream(q, token -> {
                System.out.print(token);
                responseBuffer.append(token);
            }, phrase -> {});

            System.out.println("\n");
        }

        System.out.println("==============================================================================");
        System.out.println("  6-MONTH LIVING WORLD SIMULATION COMPLETE!");
        System.out.println("==============================================================================");
    }

    private static int countTable(Connection conn, String tableName) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS c FROM " + tableName);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) return rs.getInt("c");
        } catch (Exception e) {}
        return 0;
    }

    private static List<SimulationEvent> buildSimulationChronology(LocalDate startDate) {
        List<SimulationEvent> list = new ArrayList<>();

        String[] noiseTemplates = {
            "Reviewed lecture notes on distributed systems and vector clocks.",
            "Cooked pasta for lunch, need to buy groceries this weekend.",
            "Spent 2 hours debugging null pointer exceptions in the data pipeline.",
            "Went for a 30-minute run around campus lake, weather was great.",
            "Read a paper on KV-cache optimization in modern transformer architectures.",
            "Grabbed a quick sandwich from the student union cafe.",
            "Cleaned my study desk and organized my USB-C cables.",
            "Did some leetcode practice on dynamic programming graphs.",
            "Attended the weekly department seminar on reinforcement learning.",
            "Submitted homework assignment 4 for computer architecture on time."
        };

        for (int day = 0; day < 180; day++) {
            LocalDate currentDate = startDate.plusDays(day);
            int month = currentDate.getMonthValue();

            for (int n = 0; n < 6; n++) {
                String text = noiseTemplates[(day + n) % noiseTemplates.length] + " (Day " + (day + 1) + ")";
                list.add(new SimulationEvent(currentDate, text, null));
            }

            if (month == 9) {
                if (day == 2) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Dr. Thorne", "Person", "Neural Latency Thesis", "Project", "supervises", "Master supervisor"),
                        new Triplet("Dr. Thorne", "Person", "Leo", "Family", "has_son", "10 years old"),
                        new Triplet("Leo", "Family", "Lego Robotics", "Concept", "participates_in", "State league")
                    );
                    list.add(new SimulationEvent(currentDate, 
                        "Had my first thesis meeting with Dr. Aris Thorne. He is strict about empirical benchmarks. He casually mentioned his 10-year-old son Leo is obsessed with Lego robotics tournaments.", triplets));
                } else if (day == 6) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Nina Chen", "Person", "Leica M3 Camera", "Preference", "loves", "35mm film photography"),
                        new Triplet("Nina Chen", "Person", "Seafood", "Trigger", "allergic_to", "Severe anaphylactic allergy"),
                        new Triplet("Nina Chen", "Person", "Boba", "Family", "owns_dog", "Golden retriever")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Met Nina at the campus cafe today. She showed me photos from her Leica M3 vintage 35mm film camera. She warned me she has a severe life-threatening seafood allergy and never eats at seafood places. She loves her golden retriever dog named Boba.", triplets));
                } else if (day == 12) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Ethan Wright", "Person", "FPS Gaming", "Preference", "plays_night", "Loud at 2-3 AM"),
                        new Triplet("Ethan Wright", "Person", "Direct Confrontation", "Trigger", "resents", "Gets extremely defensive")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "My roommate Ethan was yelling on Discord while playing FPS games until 3 AM again. When I told him directly he got defensive.", triplets));
                } else if (day == 18) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Rohan", "Person", "Hackathon Team", "Project", "partners_with", "Frontend & UI partner"),
                        new Triplet("Rohan", "Person", "Graphic Design", "Preference", "talented_in", "Slide decks and Figma")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Formed a hackathon team with Rohan. He's super creative and talented with slide graphic design, but he always leaves things to the last minute.", triplets));
                }
            } else if (month == 10) {
                if (day == 35) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Rohan", "Person", "$50 Loan", "Goal", "owes_to_Aryan", "Hackathon Uber surge pricing")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "At the Silicon Valley Hackathon, Rohan didn't have cash for the $50 Uber surge ride so I paid for him. He promised to pay me back the $50 next month.", triplets));
                } else if (day == 42) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Maya Sharma", "Person", "Apartment 4B", "Project", "landlady_of", "Strict rent policy"),
                        new Triplet("Maya Sharma", "Person", "Late Night Noise", "Trigger", "dislikes", "Issued warning for apartment 4B")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Landlady Maya Sharma stopped by and issued a noise complaint warning because Ethan was shouting at 2 AM. She hates disturbance.", triplets));
                } else if (day == 50) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Vikram", "Person", "Lab GPU Cluster", "Project", "monopolizes", "Blocks batch jobs"),
                        new Triplet("Aryan", "Person", "Vikram", "Person", "competes_with", "Lab research rival")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Vikram queued up 10 massive jobs on the lab GPU cluster, starving my neural latency benchmarks. He's always trying to get ahead.", triplets));
                }
            } else if (month == 11) {
                if (day == 68) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Leo", "Family", "State Robotics Championship", "Goal", "won_1st_place", "Lego Mindstorms trophy"),
                        new Triplet("Dr. Thorne", "Person", "Leo Robotics Victory", "Preference", "proud_of", "Huge soft spot")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Dr. Thorne arrived at lab beaming with pride today! His son Leo won 1st place in the State Lego Robotics Championship tournament over the weekend.", triplets));
                } else if (day == 74) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Ethan Wright", "Person", "Database Systems Midterm", "Trigger", "failing_grade", "Stressed about exams"),
                        new Triplet("Ethan Wright", "Person", "11 PM Quiet Hours Pact", "Goal", "agreed_with_Aryan", "In exchange for database tutoring")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Ethan was panicking about failing his Database Systems midterm. I offered to tutor him for 4 hours on SQL and indexing, and in exchange he formally promised to keep quiet hours after 11 PM every night.", triplets));
                } else if (day == 82) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Nina Chen", "Person", "Figma Design Internship", "Goal", "applied_to", "Summer 2026"),
                        new Triplet("Nina Chen", "Person", "Matcha Latte", "Preference", "drinks_celebration", "Iced matcha with oat milk")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Nina submitted her Figma summer product design internship portfolio. We had iced oat matcha lattes to celebrate. She loves matcha.", triplets));
                }
            } else if (month == 12) {
                if (day == 98) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Rohan", "Person", "Car Transmission Repair", "Trigger", "costs_$800", "Broke down on highway"),
                        new Triplet("Rohan", "Person", "$50 Loan Repayment", "Goal", "delayed_due_to_car", "Apologized to Aryan")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Rohan called in distress: his car transmission completely blew up on the highway and the repair shop quoted $800. He apologized that he can't repay my $50 right now because he is broke.", triplets));
                } else if (day == 106) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Maya Sharma", "Person", "Smart Home Locks", "Project", "needs_setup", "Offers $100 rent credit"),
                        new Triplet("Maya Sharma", "Person", "Organic Green Tea", "Preference", "loves", "Himalayan loose leaf tea")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Landlady Maya saw me fixing the Wi-Fi router and mentioned she bought smart door locks for the building. She offered a $100 rent credit if I help her configure the mobile app and installation. She also mentioned she loves organic green tea.", triplets));
                } else if (day == 115) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Aryan", "Person", "Boba", "Family", "dog_sat", "Looked after Boba for 3 days"),
                        new Triplet("Nina Chen", "Person", "Aryan", "Person", "deeply_grateful_to", "For caring for Boba")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Nina went to visit her family in Portland for 3 days and I dog-sat Boba at my place. Boba was super sweet. Nina was deeply grateful and said she owes me a huge favor.", triplets));
                }
            } else if (month == 1) {
                if (day == 130) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Nina Chen", "Person", "Figma Final Round Interview", "Goal", "scheduled_for", "Product Design Team")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Nina got selected for the final round Figma product design interview! She is super excited.", triplets));
                } else if (day == 142) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Vikram", "Person", "Lab GPU Cluster Lockout", "Trigger", "crashed_node_04", "Blocked Aryan's thesis run")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "Vikram ran an unoptimized distributed training job that locked up GPU node 04 for 48 hours, blocking my final neural latency benchmarks for Dr. Thorne.", triplets));
                }
            } else if (month == 2) {
                if (day == 160) {
                    List<Triplet> triplets = List.of(
                        new Triplet("Neural Latency Thesis", "Project", "Final Defense", "Goal", "deadline_approaching", "Dr. Thorne approval required")
                    );
                    list.add(new SimulationEvent(currentDate,
                        "The final thesis deadline is coming up. I need to make sure my benchmarks and slide deck look impeccable for Dr. Thorne's committee.", triplets));
                }
            }
        }

        return list;
    }

    private static class SimulationEvent {
        LocalDate date;
        String text;
        List<Triplet> triplets;

        SimulationEvent(LocalDate date, String text, List<Triplet> triplets) {
            this.date = date;
            this.text = text;
            this.triplets = triplets;
        }
    }

    private static class Triplet {
        String subject, subjectType, object, objectType, relation, context;

        Triplet(String s, String st, String o, String ot, String r, String c) {
            this.subject = s;
            this.subjectType = st;
            this.object = o;
            this.objectType = ot;
            this.relation = r;
            this.context = c;
        }
    }
}
