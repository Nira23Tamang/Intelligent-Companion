package com.assistant;

public class MatrixTestRunner {

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING MINI MATRIX STRATEGY TEST ===");
        
        Engine engine = new Engine();
        // Give time for Ollama to load model into memory if not active
        Thread.sleep(1000);

        String[] scenarios = {
            "Marcus just moved up our deadline for the Java project to tomorrow! I need to pull an all-nighter with someone from the team to fix the WebSocket latency. Who should I ask to help, and what drink should I buy for myself to stay awake?",
            "Tomorrow, I'm taking my sister and my best friend out for dinner to celebrate finishing the project. Where should we go, and is there anything I need to be careful about?"
        };

        for (int i = 0; i < scenarios.length; i++) {
            System.out.println("\n--------------------------------------------------");
            System.out.println("SCENARIO " + (i + 1) + ": " + scenarios[i]);
            System.out.println("--------------------------------------------------");
            
            final StringBuilder answer = new StringBuilder();
            engine.askOllamaStream(scenarios[i], 
                token -> {
                    System.out.print(token);
                    answer.append(token);
                },
                phrase -> {
                    // Do nothing for phrases in test
                }
            );
            System.out.println("\n");
        }
        
        System.out.println("=== MINI MATRIX STRATEGY TEST COMPLETE ===");
        System.exit(0);
    }
}
