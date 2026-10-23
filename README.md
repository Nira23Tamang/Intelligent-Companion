# Intelligent-Companion
An offline AI desktop companion built from scratch in Java 17. No cloud APIs everything runs locally using Ollama (Qwen 2.5), OpenCV face login, offline voice (Vosk &amp; Piper), ONNX emotion tracking, Graph-RAG memory, and an animated Canvas2D avatar.


This package contains the complete Intelligent Assistant application.

======================================================================
1. SYSTEM REQUIREMENTS
======================================================================
- Windows 10/11, 64-bit
- Java JDK 17 or newer installed and available in PATH (or Eclipse Adoptium)
- Ollama installed and running locally

Before launching for the first time, open PowerShell or Command Prompt 
and download the required language and embedding models:

    ollama pull qwen2.5:3b
    ollama pull nomic-embed-text

======================================================================
2. WHAT IS INCLUDED
======================================================================
- Java 17 backend with Maven runtime and launcher script
- Procedural Canvas2D animated avatar Web UI (HTML/CSS/JS)
- Bundled local speech assets (Piper Neural TTS, Vosk offline recognizer)
- Computer vision models (YuNet face detector, SFace recognizer, FERPlus emotion)
- Email/password authentication + 3-angle biometric face login
- Cognitive Personal Relationship Manager (PRM) & Graph-RAG memory

======================================================================
3. HOW TO LAUNCH AND RUN
======================================================================
1. Make sure Java JDK 17+ is installed.
2. Ensure Ollama is running and the models are pulled (see section 1).
3. Double-click "Run Intelligent Assistant.bat" in the root directory.
4. The system will start the backend services:
   - File Server at http://localhost:8080
   - WebSocket Sync Server on port 8081
   - Browser UI will automatically pop open at http://localhost:8080
5. On the first launch, click "REGISTER / SIGN UP" to create your new 
   local profile, configure your email settings (optional), and calibrate 
   your Face ID.

======================================================================
4. IMPORTANT SECURITY & PRIVACY NOTES
======================================================================
- This repository is kept clean of personal account credentials, 
  passwords, and personal biometric captures.
- The application automatically initializes a fresh local H2 database 
  (intelligent_assistant.mv.db) on the first run.
- Never commit or upload local database files (*.mv.db, *.trace.db) or 
  captured face images (biometric_data/faces/*.png) to public repositories.
- Face login uses the bundled YuNet detector and SFace recognition model.
  Face login requires one clear face and a confident match to an enrolled 
  account. Unknown or ambiguous faces are rejected.
- Microphone audio remains available for speech commands and wake-word 
  detection.
======================================================================

