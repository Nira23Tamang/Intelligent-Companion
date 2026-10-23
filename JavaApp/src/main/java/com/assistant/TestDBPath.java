package com.assistant;
public class TestDBPath {
    public static void main(String[] args) {
        try {
            String rawPath = MemoryManager.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            String decodedPath = java.net.URLDecoder.decode(rawPath, "UTF-8");
            String base = new java.io.File(decodedPath).getParentFile().getParentFile().getAbsolutePath();
            System.out.println("Base: " + base);
            System.out.println("DBPath: jdbc:h2:" + base + java.io.File.separator + "intelligent_assistant;AUTO_SERVER=TRUE");
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
}
