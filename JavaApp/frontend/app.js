// ============================================================
// CUSTOM CANVAS2D ANIMATED CARTOON AVATAR
// No external dependencies. Pure JavaScript.
// ============================================================

window.onerror = function(msg, url, lineNo, columnNo, error) {
    const statusText = document.getElementById("status-text");
    if (statusText) statusText.innerText = "JS Error: " + msg + " at " + lineNo;
    return false;
};

const canvas = document.getElementById('avatar-canvas');
const ctx = canvas.getContext('2d');
const W = 400, H = 400;

// Animation state
let isSpeaking = false;
let isListening = false;    // NEW: blocks status resets while capturing voice
let mouthOpen = 0;       // 0 = closed, 1 = fully open
let mouthTarget = 0;
let blinkProgress = 1;   // 1 = eyes fully open, 0 = fully closed
let blinkTimer = 0;
let isBlinking = false;
let eyeOffsetX = 0;     // subtle eye tracking
let eyeOffsetY = 0;
let headBobY = 0;       // subtle idle head bob
let headBobAngle = 0;
let currentMood = 'neutral';
let expressionTimer = 0;

// ─── AVATAR THEME SYSTEM ──────────────────────────────────────────────────────
const THEMES = {
    innocent: {
        name: 'Innocent ✨',
        SKIN:           '#FFD6B0',
        SKIN_SHADOW:    '#F0B888',
        HAIR:           '#4A2C8A',
        HAIR_HIGHLIGHT: '#7B52CC',
        EYE_WHITE:      '#FFFFFF',
        IRIS:           '#5BC8FF',
        PUPIL:          '#1A1A2E',
        CHEEK:          'rgba(255,150,150,0.4)',
        MOUTH_COLOR:    '#C0566A',
        TEETH:          '#FFFFFF',
        BG_GLOW:        'rgba(91, 200, 255, 0.08)',
        NECK:           '#FFD6B0',
        OUTFIT:         '#3B1F6E',
        BG_TOP:         '#1A0A2E',
        BG_BOT:         '#2D1B4E',
        EYELID:         '#2A1A3A',
        EYEBROW:        '#2A1A3A',
        SMIRK:          false,
    },
    badgirl: {
        name: 'Bad Girl 🖤',
        SKIN:           '#F5C8A0',
        SKIN_SHADOW:    '#D4956A',
        HAIR:           '#1A0A1A',       // Dark purple-black (visible against dark bg)
        HAIR_HIGHLIGHT: '#FF1744',       // Vivid red streak - clearly visible
        EYE_WHITE:      '#F0E8E8',
        IRIS:           '#CC1414',
        PUPIL:          '#0A0A0A',
        CHEEK:          'rgba(180,30,30,0.18)',
        MOUTH_COLOR:    '#8B0000',
        TEETH:          '#F5F0F0',
        BG_GLOW:        'rgba(180, 20, 20, 0.10)',
        NECK:           '#F5C8A0',
        OUTFIT:         '#2A0A0A',       // Dark crimson outfit (not pure black)
        BG_TOP:         '#2E2E2E',       // Medium grey top
        BG_BOT:         '#3A3A3A',       // Slightly lighter grey bottom
        EYELID:         '#1A0A1A',
        EYEBROW:        '#1A0A1A',
        SMIRK:          true,
    }
};

let currentTheme = THEMES.innocent;

function switchTheme(themeName) {
    currentTheme = THEMES[themeName];
    // Update button states
    document.querySelectorAll('.avatar-theme-btn').forEach(b => b.classList.remove('active'));
    const btn = document.getElementById('theme-btn-' + themeName);
    if (btn) btn.classList.add('active');
    // Show quick toast
    const status = document.getElementById('status-text');
    if (status) { status.innerText = 'Avatar: ' + currentTheme.name; setTimeout(() => { if (!isSpeaking) status.innerText = 'System: Ready.'; }, 2000); }
}

// Shorthand accessors so existing draw code works unchanged
let SKIN, SKIN_SHADOW, HAIR, HAIR_HIGHLIGHT, EYE_WHITE, IRIS, PUPIL, CHEEK, MOUTH_COLOR, TEETH, BG_GLOW, NECK, OUTFIT;

function syncColors() {
    SKIN = currentTheme.SKIN; SKIN_SHADOW = currentTheme.SKIN_SHADOW;
    HAIR = currentTheme.HAIR; HAIR_HIGHLIGHT = currentTheme.HAIR_HIGHLIGHT;
    EYE_WHITE = currentTheme.EYE_WHITE; IRIS = currentTheme.IRIS; PUPIL = currentTheme.PUPIL;
    CHEEK = currentTheme.CHEEK; MOUTH_COLOR = currentTheme.MOUTH_COLOR; TEETH = currentTheme.TEETH;
    BG_GLOW = currentTheme.BG_GLOW; NECK = currentTheme.NECK; OUTFIT = currentTheme.OUTFIT;
}

// ---- Draw helpers ----
function drawRoundRect(x, y, w, h, r, fill, stroke) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
    if (fill) { ctx.fillStyle = fill; ctx.fill(); }
    if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = 2; ctx.stroke(); }
}

function drawEye(cx, cy, isOpen, offsetX, offsetY, mood) {
    const eyeW = 36;
    const eyeH = isOpen * 28;

    // Eye white
    ctx.save();
    ctx.beginPath();
    ctx.ellipse(cx + offsetX, cy + offsetY, eyeW / 2, Math.max(eyeH / 2, 1), 0, 0, Math.PI * 2);
    ctx.fillStyle = EYE_WHITE;
    ctx.fill();

    if (isOpen > 0.1) {
        // Iris
        const irisR = 11;
        ctx.beginPath();
        ctx.ellipse(cx + offsetX, cy + offsetY + 2, irisR, irisR * isOpen, 0, 0, Math.PI * 2);
        ctx.fillStyle = IRIS;
        ctx.fill();

        // Pupil
        ctx.beginPath();
        ctx.ellipse(cx + offsetX, cy + offsetY + 2, 5.5, 5.5 * isOpen, 0, 0, Math.PI * 2);
        ctx.fillStyle = PUPIL;
        ctx.fill();

        // Eye shine
        ctx.beginPath();
        ctx.ellipse(cx + offsetX + 4, cy + offsetY - 2, 3, 3 * isOpen, 0, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255,255,255,0.9)';
        ctx.fill();
    }

    // Upper eyelid line
    ctx.beginPath();
    ctx.ellipse(cx + offsetX, cy + offsetY, eyeW / 2, Math.max(eyeH / 2, 1), 0, Math.PI, Math.PI * 2);
    ctx.strokeStyle = '#2A1A3A';
    ctx.lineWidth = 2.5;
    ctx.stroke();

    // Happy mood - curved eyelid
    if (mood === 'happy' && isOpen > 0.3) {
        ctx.beginPath();
        ctx.arc(cx + offsetX, cy + offsetY + 8, eyeW / 2, Math.PI, Math.PI * 2);
        ctx.strokeStyle = '#2A1A3A';
        ctx.lineWidth = 2;
        ctx.stroke();
    }

    // Eyelashes
    for (let i = -1; i <= 1; i++) {
        ctx.beginPath();
        ctx.moveTo(cx + offsetX + (i * 12), cy + offsetY - eyeH / 2);
        ctx.lineTo(cx + offsetX + (i * 14), cy + offsetY - eyeH / 2 - 5);
        ctx.strokeStyle = '#2A1A3A';
        ctx.lineWidth = 1.5;
        ctx.stroke();
    }

    ctx.restore();
}

function drawMouth(cx, cy, openAmount, mood) {
    ctx.save();
    ctx.lineWidth = 2.5;

    if (mood === 'happy') {
        // Big smile
        ctx.beginPath();
        ctx.moveTo(cx - 22, cy);
        ctx.quadraticCurveTo(cx, cy + 18 + openAmount * 10, cx + 22, cy);
        ctx.strokeStyle = MOUTH_COLOR;
        ctx.stroke();

        if (openAmount > 0.3) {
            ctx.beginPath();
            ctx.moveTo(cx - 20, cy + 2);
            ctx.quadraticCurveTo(cx, cy + 18 + openAmount * 10, cx + 20, cy + 2);
            ctx.fillStyle = MOUTH_COLOR;
            ctx.fill();
            ctx.beginPath();
            ctx.rect(cx - 14, cy + 2, 28, 8 * openAmount);
            ctx.fillStyle = TEETH;
            ctx.fill();
        }
    } else if (mood === 'sad') {
        ctx.beginPath();
        ctx.moveTo(cx - 20, cy + 8);
        ctx.quadraticCurveTo(cx, cy - 6, cx + 20, cy + 8);
        ctx.strokeStyle = MOUTH_COLOR;
        ctx.stroke();
    } else {
        // Neutral / speaking — or smirk for bad girl
        const h = openAmount * 22;
        if (currentTheme.SMIRK && h <= 2) {
            // Confident smirk (asymmetric)
            ctx.beginPath();
            ctx.moveTo(cx - 14, cy + 4);
            ctx.quadraticCurveTo(cx, cy + 2, cx + 18, cy - 4);
            ctx.strokeStyle = MOUTH_COLOR;
            ctx.lineWidth = 2.5;
            ctx.stroke();
        } else if (h > 2) {
            ctx.beginPath();
            ctx.ellipse(cx, cy + h / 2, 18, h / 2, 0, 0, Math.PI * 2);
            ctx.fillStyle = MOUTH_COLOR;
            ctx.fill();
            if (h > 8) {
                ctx.beginPath();
                ctx.ellipse(cx, cy + 4, 14, 4, 0, 0, Math.PI);
                ctx.fillStyle = TEETH;
                ctx.fill();
            }
        } else {
            ctx.beginPath();
            ctx.moveTo(cx - 16, cy);
            ctx.quadraticCurveTo(cx, cy + 5, cx + 16, cy);
            ctx.strokeStyle = MOUTH_COLOR;
            ctx.stroke();
        }
    }
    ctx.restore();
}

function render(timestamp) {
    syncColors(); // Apply current theme colors every frame
    ctx.clearRect(0, 0, W, H);

    // Themed background gradient
    const bgGrad = ctx.createLinearGradient(0, 0, 0, H);
    bgGrad.addColorStop(0, currentTheme.BG_TOP);
    bgGrad.addColorStop(1, currentTheme.BG_BOT);
    ctx.fillStyle = bgGrad;
    ctx.fillRect(0, 0, W, H);


    // Idle head bob
    headBobAngle += 0.02;
    headBobY = Math.sin(headBobAngle) * 4;

    // Blink logic
    blinkTimer -= 16;
    if (blinkTimer <= 0 && !isBlinking) {
        isBlinking = true;
        blinkTimer = 150;
    }
    if (isBlinking) {
        blinkProgress -= 0.15;
        if (blinkProgress <= 0) {
            blinkProgress = 0;
            isBlinking = false;
            blinkTimer = 3000 + Math.random() * 2000;
        }
    } else {
        blinkProgress = Math.min(1, blinkProgress + 0.2);
    }

    // Mouth animation
    if (isSpeaking) {
        if (Math.random() < 0.15) mouthTarget = Math.random();
    } else {
        mouthTarget = 0;
    }
    mouthOpen += (mouthTarget - mouthOpen) * 0.3;

    const cx = W / 2;
    const cy = H / 2 + headBobY;

    // --- DRAW HAIR (back layer) ---
    ctx.save();
    // Long back hair
    ctx.beginPath();
    ctx.ellipse(cx, cy + 10, 105, 130, 0, 0, Math.PI * 2);
    ctx.fillStyle = HAIR;
    ctx.fill();

    // Side hair strands
    ctx.beginPath();
    ctx.moveTo(cx - 90, cy - 20);
    ctx.bezierCurveTo(cx - 130, cy + 60, cx - 110, cy + 160, cx - 70, cy + 180);
    ctx.bezierCurveTo(cx - 50, cy + 190, cx - 80, cy + 80, cx - 80, cy + 10);
    ctx.fillStyle = HAIR;
    ctx.fill();

    ctx.beginPath();
    ctx.moveTo(cx + 90, cy - 20);
    ctx.bezierCurveTo(cx + 130, cy + 60, cx + 110, cy + 160, cx + 70, cy + 180);
    ctx.bezierCurveTo(cx + 50, cy + 190, cx + 80, cy + 80, cx + 80, cy + 10);
    ctx.fillStyle = HAIR;
    ctx.fill();
    ctx.restore();

    // --- NECK ---
    ctx.beginPath();
    ctx.rect(cx - 22, cy + 75, 44, 55);
    ctx.fillStyle = NECK;
    ctx.fill();

    // --- OUTFIT (shoulders) ---
    ctx.beginPath();
    ctx.moveTo(cx - 80, cy + 160);
    ctx.lineTo(cx - 40, cy + 110);
    ctx.lineTo(cx + 40, cy + 110);
    ctx.lineTo(cx + 80, cy + 160);
    ctx.closePath();
    ctx.fillStyle = OUTFIT;
    ctx.fill();
    // Collar
    ctx.beginPath();
    ctx.moveTo(cx - 25, cy + 108);
    ctx.lineTo(cx, cy + 130);
    ctx.lineTo(cx + 25, cy + 108);
    ctx.strokeStyle = '#7B52CC';
    ctx.lineWidth = 3;
    ctx.stroke();

    // --- FACE ---
    ctx.save();
    ctx.beginPath();
    ctx.ellipse(cx, cy, 90, 100, 0, 0, Math.PI * 2);
    ctx.fillStyle = SKIN;
    ctx.fill();

    // Face shadow (bottom)
    const faceShadow = ctx.createLinearGradient(cx, cy, cx, cy + 100);
    faceShadow.addColorStop(0, 'rgba(0,0,0,0)');
    faceShadow.addColorStop(1, 'rgba(0,0,0,0.08)');
    ctx.fillStyle = faceShadow;
    ctx.fill();
    ctx.restore();

    // --- HAIR (front/top) ---
    ctx.save();
    // Top hair
    ctx.beginPath();
    ctx.moveTo(cx - 95, cy - 10);
    ctx.bezierCurveTo(cx - 95, cy - 110, cx + 95, cy - 110, cx + 95, cy - 10);
    ctx.bezierCurveTo(cx + 60, cy - 40, cx - 60, cy - 40, cx - 95, cy - 10);
    ctx.fillStyle = HAIR;
    ctx.fill();

    // Hair highlight
    ctx.beginPath();
    ctx.ellipse(cx + 20, cy - 80, 25, 10, -0.4, 0, Math.PI * 2);
    ctx.fillStyle = HAIR_HIGHLIGHT;
    ctx.fill();

    // Fringe / bangs
    for (let i = 0; i < 5; i++) {
        const bx = cx - 70 + i * 34;
        ctx.beginPath();
        ctx.moveTo(bx - 20, cy - 60);
        ctx.bezierCurveTo(bx - 15, cy - 20, bx + 15, cy - 20, bx + 20, cy - 60);
        ctx.fillStyle = i % 2 === 0 ? HAIR : HAIR_HIGHLIGHT;
        ctx.fill();
    }
    ctx.restore();

    // --- EYEBROWS ---
    ctx.save();
    ctx.lineWidth = 3;
    ctx.lineCap = 'round';
    ctx.strokeStyle = currentTheme.EYEBROW;
    const browRaise = currentTheme.SMIRK ? -8 : (currentMood === 'happy' ? -5 : currentMood === 'sad' ? 5 : 0);
    const browAngle = currentTheme.SMIRK ? 6 : 0; // Bad girl brows are angled inward

    // Left brow (angled sharply inward for bad girl)
    ctx.beginPath();
    ctx.moveTo(cx - 52, cy - 30 + browRaise + browAngle);
    ctx.quadraticCurveTo(cx - 36, cy - 40 + browRaise, cx - 18, cy - 32 + browRaise - browAngle);
    ctx.stroke();

    // Right brow (mirror)
    ctx.beginPath();
    ctx.moveTo(cx + 52, cy - 30 + browRaise + browAngle);
    ctx.quadraticCurveTo(cx + 36, cy - 40 + browRaise, cx + 18, cy - 32 + browRaise - browAngle);
    ctx.stroke();
    ctx.restore();

    // --- EYES ---
    drawEye(cx - 34, cy - 15, blinkProgress, eyeOffsetX, eyeOffsetY, currentMood);
    drawEye(cx + 34, cy - 15, blinkProgress, eyeOffsetX, eyeOffsetY, currentMood);

    // --- CHEEKS ---
    ctx.beginPath();
    ctx.ellipse(cx - 68, cy + 15, 18, 10, 0, 0, Math.PI * 2);
    ctx.fillStyle = CHEEK;
    ctx.fill();
    ctx.beginPath();
    ctx.ellipse(cx + 68, cy + 15, 18, 10, 0, 0, Math.PI * 2);
    ctx.fillStyle = CHEEK;
    ctx.fill();

    // --- NOSE (subtle) ---
    ctx.beginPath();
    ctx.moveTo(cx - 5, cy + 20);
    ctx.quadraticCurveTo(cx - 8, cy + 28, cx - 4, cy + 30);
    ctx.moveTo(cx + 5, cy + 20);
    ctx.quadraticCurveTo(cx + 8, cy + 28, cx + 4, cy + 30);
    ctx.strokeStyle = SKIN_SHADOW;
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // --- MOUTH ---
    drawMouth(cx, cy + 48, mouthOpen, currentMood);

    // --- EAR DECORATIONS (earrings) ---
    ctx.beginPath();
    ctx.arc(cx - 92, cy + 5, 5, 0, Math.PI * 2);
    ctx.fillStyle = '#FFD700';
    ctx.fill();
    ctx.beginPath();
    ctx.arc(cx + 92, cy + 5, 5, 0, Math.PI * 2);
    ctx.fillStyle = '#FFD700';
    ctx.fill();

    requestAnimationFrame(render);
}

// ============================================================
// WEBSOCKET & STATE MANAGEMENT (replaces Live2D functions)
// ============================================================

let ws = null;
let currentModel = null; // kept for compatibility
let loginWebcamStream = null;
let faceLoginPending = false;
let textSubmissionPending = false;
let conversationInProgress = false;
let activeUserId = null;
let activeSessionId = null;
let historyLoading = false;
let pendingHistoryConversation = null;

function clearAccountConversation(userId = null, sessionId = null) {
    activeUserId = userId;
    activeSessionId = sessionId;
    historyLoading = false;
    pendingHistoryConversation = null;
    document.getElementById("chat-box").innerHTML = "";
    document.getElementById("text-chat-input").value = "";
    isListening = false;
    isSpeaking = false;
    currentMood = "neutral";
    mouthTarget = 0;
    textSubmissionPending = false;
    conversationInProgress = false;
    const talk = document.getElementById("talk-btn");
    talk.disabled = false;
    talk.innerText = "TALK";
    talk.style.background = "#10b981";
    document.getElementById("status-text").innerText = "System: Ready.";
    resizeTextChatInput();
    updateTextChatComposer();
}

function isWebSocketConnected() {
    return ws && ws.readyState === WebSocket.OPEN;
}

function resizeTextChatInput() {
    const input = document.getElementById("text-chat-input");
    if (!input) return;

    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, 112)}px`;
    input.style.overflowY = input.scrollHeight > 112 ? "auto" : "hidden";
}

function updateTextChatComposer() {
    const form = document.getElementById("text-chat-form");
    const input = document.getElementById("text-chat-input");
    const sendButton = document.getElementById("send-text-btn");
    if (!form || !input || !sendButton) return;

    const isBusy = historyLoading || textSubmissionPending || conversationInProgress || isListening || isSpeaking;
    const isOnline = isWebSocketConnected();
    input.disabled = isBusy;
    sendButton.disabled = isBusy || !input.value.trim() || !isOnline;
    form.classList.toggle("is-busy", isBusy);
    form.classList.toggle("is-offline", !isOnline);
}

function initializeTextChatComposer() {
    const input = document.getElementById("text-chat-input");
    if (!input) return;

    input.addEventListener("input", () => {
        resizeTextChatInput();
        updateTextChatComposer();
    });

    input.addEventListener("keydown", (event) => {
        if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            submitTextMessage();
        }
    });

    resizeTextChatInput();
    updateTextChatComposer();
}

function submitTextMessage(event) {
    if (event) event.preventDefault();

    const input = document.getElementById("text-chat-input");
    const statusText = document.getElementById("status-text");
    if (!input) return;

    const userText = input.value.trim();
    if (!activeUserId || !userText || historyLoading || textSubmissionPending || conversationInProgress || isListening || isSpeaking) {
        updateTextChatComposer();
        return;
    }

    if (!isWebSocketConnected()) {
        showToast("The assistant is reconnecting. Please try again in a moment.", "error");
        updateTextChatComposer();
        return;
    }

    textSubmissionPending = true;
    conversationInProgress = true;
    input.value = "";
    resizeTextChatInput();
    updateTextChatComposer();

    if (statusText) {
        statusText.innerHTML = '<div class="thinking-indicator"><div class="thinking-dot"></div><div class="thinking-dot"></div><div class="thinking-dot"></div></div> <span style="color:#f59e0b; margin-left:5px;">Thinking...</span>';
    }

    try {
        // Omitting conversation_id lets the server use the active shared voice/text conversation.
        ws.send(JSON.stringify({ action: "send_chat", text: userText }));
    } catch (error) {
        textSubmissionPending = false;
        conversationInProgress = false;
        input.value = userText;
        resizeTextChatInput();
        updateTextChatComposer();
        showToast("Your message could not be sent. Please try again.", "error");
    }
}

function connectWebSocket() {
    ws = new WebSocket("ws://localhost:8081");

    ws.onopen = () => {
        console.log("Connected to Java AI Core.");
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: "check_setup_status" }));
        }
        if (!isListening) {
            document.getElementById("status-text").innerText = "System: Ready.";
        }
        updateTextChatComposer();
    };

    ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        const sessionEvent = ["chat", "chat_start", "chat_token", "state", "reset", "wake_state",
            "conversations_list", "messages_list", "conversation_created"].includes(data.type);
        if (sessionEvent && (activeUserId === null || data.user_id !== activeUserId
                || data.session_id !== activeSessionId)) return;

        if (data.type === "login_result") {
            stopLoginWebcam();
            
            if (data.success) {
                clearAccountConversation(data.user_id, data.session_id);
                historyLoading = true;
                updateTextChatComposer();
                ws.send(JSON.stringify({ action: "get_conversations" }));
                requestAnimationFrame(render);
                showToast("Login successful!", "success");
                
                // Jump to the main app view directly after successful login
                document.getElementById("login-view").classList.remove("active");
                document.getElementById("setup-wizard-view").classList.remove("active");
                document.getElementById("app-view").classList.add("active");
            } else {
                clearAccountConversation();
                const errorEl = document.getElementById("login-error");
                errorEl.innerText = data.message || "Login failed. Check your credentials or try face login.";
                errorEl.style.color = "#ef4444";
                showToast(data.message || "Login failed.", "error");
            }
        } else if (data.type === "setup_status") {
            clearAccountConversation();
            document.getElementById("app-view").classList.remove("active");
            document.getElementById("login-view").classList.remove("active");
            document.getElementById("setup-wizard-view").classList.remove("active");
            if (data.isFirstRun) {
                startGuidedSetupWizard();
            } else {
                document.getElementById("login-view").classList.add("active");
            }
        } else if (data.type === "setup_started") {
            if (!data.success) {
                showToast('I could not start a new profile. Please try again.', 'error');
                document.getElementById("setup-wizard-view").classList.remove("active");
                document.getElementById("login-view").classList.add("active");
            }
        } else if (data.type === "face_reenrollment_started") {
            if (data.success) {
                faceReenrollment = true;
                currentFaceAngleIdx = 0;
                document.getElementById("app-view").classList.remove("active");
                document.getElementById("setup-wizard-view").classList.add("active");
                document.getElementById("btn-skip-face").innerText = "Cancel";
                switchSetupStep(1);
                updateFaceAngleUI();
                startWebcamPreview();
            } else {
                showToast("Please log in before updating Face ID.", "error");
            }
        } else if (data.type === "setup_complete_ack") {
            clearAccountConversation(data.user_id, data.session_id);
            const chatBox = document.getElementById('chat-box');
            if (chatBox && data.welcomeMsg) {
                const message = document.createElement('div');
                message.className = 'chat-msg ai';
                message.innerText = data.welcomeMsg;
                chatBox.appendChild(message);
                chatBox.scrollTop = chatBox.scrollHeight;
            }
        } else if (data.type === "setup_error") {
            showToast(data.message || 'Setup could not be completed.', 'error');
        } else if (data.type === "auth_required") {
            clearAccountConversation();
            textSubmissionPending = false;
            conversationInProgress = false;
            document.getElementById("app-view").classList.remove("active");
            document.getElementById("setup-wizard-view").classList.remove("active");
            document.getElementById("login-view").classList.add("active");
            document.getElementById("login-error").innerText = data.message || "Please log in to continue.";
            updateTextChatComposer();
        } else if (data.type === "logout_result") {
            clearAccountConversation();
        } else if (data.type === "memory_reset_result") {
            if (data.user_id !== activeUserId || data.previous_session_id !== activeSessionId) return;
            if (data.success) {
                clearAccountConversation(data.user_id, data.session_id);
            } else {
                activeSessionId = data.session_id;
                historyLoading = false;
                pendingHistoryConversation = null;
                isListening = false;
                isSpeaking = false;
                textSubmissionPending = false;
                conversationInProgress = false;
                updateTextChatComposer();
            }
            showToast(data.message, data.success ? "success" : "error");
        } else if (data.type === "conversations_list") {
            if (!historyLoading) return;
            const latest = data.conversations?.[0];
            if (latest) {
                pendingHistoryConversation = latest.conversation_id;
                ws.send(JSON.stringify({ action: "get_messages", conversation_id: pendingHistoryConversation }));
            } else {
                historyLoading = false;
                updateTextChatComposer();
            }
        } else if (data.type === "messages_list") {
            if (!historyLoading || data.conversation_id !== pendingHistoryConversation) return;
            const chatBox = document.getElementById("chat-box");
            for (const message of data.messages || []) {
                const row = document.createElement("div");
                row.className = "chat-msg " + (message.role === "user" ? "you" : "ai");
                row.innerText = message.content;
                chatBox.appendChild(row);
            }
            chatBox.scrollTop = chatBox.scrollHeight;
            historyLoading = false;
            pendingHistoryConversation = null;
            updateTextChatComposer();
        } else if (data.type === "chat_error") {
            textSubmissionPending = false;
            conversationInProgress = false;
            updateTextChatComposer();
            showToast(data.message || "Your message could not be sent. Please try again.", "error");
        } else if (data.type === "chat") {
            const chatBox = document.getElementById("chat-box");
            const msgDiv = document.createElement("div");
            // Remove active-typing from any previous AI messages
            document.querySelectorAll('.active-typing').forEach(el => el.classList.remove('active-typing'));
            
            msgDiv.className = "chat-msg " + (data.sender === "You" ? "you" : "ai");
            msgDiv.innerText = data.text;
            chatBox.appendChild(msgDiv);
            chatBox.scrollTop = chatBox.scrollHeight;
        } else if (data.type === "chat_start") {
            textSubmissionPending = false;
            conversationInProgress = true;
            updateTextChatComposer();
            const chatBox = document.getElementById("chat-box");
            const msgDiv = document.createElement("div");
            
            // Remove active-typing from any previous AI messages
            document.querySelectorAll('.active-typing').forEach(el => el.classList.remove('active-typing'));
            
            // Revert status text to normal now that processing is done
            const statusText = document.getElementById("status-text");
            if (statusText.innerHTML.includes('thinking-indicator')) {
                statusText.innerHTML = "🔊 AI Speaking...";
            }

            msgDiv.className = "chat-msg ai active-typing";
            msgDiv.innerText = "";
            chatBox.appendChild(msgDiv);
            chatBox.scrollTop = chatBox.scrollHeight;
        } else if (data.type === "chat_token") {
            const chatBox = document.getElementById("chat-box");
            const activeMsg = chatBox.querySelector(".active-typing");
            if (activeMsg) {
                activeMsg.innerText += data.text;
                chatBox.scrollTop = chatBox.scrollHeight;
            }
        } else if (data.type === "reset") {
            isListening = false;
            isSpeaking = false;
            textSubmissionPending = false;
            conversationInProgress = false;
            const statusText = document.getElementById("status-text");
            const btn = document.getElementById("talk-btn");
            statusText.innerText = "🟢 Hands-Free: Say \"Hey Assistant\" or click TALK";
            statusText.style.color = "#94a3b8";
            btn.disabled = false;
            btn.style.background = "#10b981";
            btn.innerText = "TALK";
            updateTextChatComposer();
                } else if (data.type === "wake_state") {
            const statusText = document.getElementById("status-text");
            const btn = document.getElementById("talk-btn");
            if (data.state === "wake_triggered") {
                isListening = true;
                if (statusText) {
                    statusText.innerText = "🎙️ 'Hey Assistant' heard — Listening (2s silence auto-stops)...";
                    statusText.style.color = "#38bdf8";
                }
                if (btn) {
                    btn.innerText = "LISTENING...";
                    btn.style.background = "#38bdf8";
                }
                updateTextChatComposer();
            }
        } else if (data.type === "state") {
            handleAIState(data);
        } else if (data.type === "reminders_list") {
            const list = document.getElementById("rp-list");
            const status = document.getElementById("rp-status");
            if (!list) return;
            
            if (!data.items || data.items.length === 0) {
                list.innerHTML = '<div class="ep-empty">📭 No active reminders.</div>';
                if (status) status.innerText = '0 reminders';
            } else {
                list.innerHTML = "";
                if (status) status.innerText = `${data.items.length} reminder${data.items.length !== 1 ? 's' : ''}`;
                
                data.items.forEach(item => {
                    const card = document.createElement("div");
                    card.className = "ep-email-card";
                    // Using the email card CSS classes for consistency
                    card.innerHTML = `
                        <div class="ep-email-avatar" style="background: linear-gradient(135deg, #f59e0b, #ea580c); font-size:16px;">${item.done ? '✅' : '⏰'}</div>
                        <div class="ep-email-content">
                            <div class="ep-email-from" style="white-space:normal; overflow:visible;">${item.task}</div>
                            <div class="ep-email-subject">${item.time}</div>
                        </div>
                    `;
                    list.appendChild(card);
                });
            }
        } else if (data.type === "alert") {
            showToast(data.text, "info");
        } else if (data.type === "setup_face_result") {
            handleSetupFaceResult(data.data);
                } else if (data.type === "prm_contacts_list") {
            handlePRMContactsList(data.contacts || []);
                } else if (data.type === "knowledge_graph_data") {
            handleKnowledgeGraphData(data.graph);
} else if (data.type === "prm_strategy_result") {
            handlePRMStrategyResult(data.data);
        } else if (data.type === "email_list" || data.type === "email_sent") {
            handleEmailMessages(data);
        }
    };

    ws.onerror = () => {
        if (textSubmissionPending || conversationInProgress) {
            textSubmissionPending = false;
            conversationInProgress = false;
            updateTextChatComposer();
            showToast("The connection failed before the response was confirmed.", "error");
        }
    };

    ws.onclose = () => {
        clearAccountConversation();
        stopLoginWebcam();
        stopWebcamPreview();
        const hadActiveMessage = textSubmissionPending || conversationInProgress;
        textSubmissionPending = false;
        conversationInProgress = false;
        updateTextChatComposer();
        if (hadActiveMessage && document.visibilityState !== "hidden") {
            showToast("The connection was lost before the response was confirmed.", "error");
        }
        console.log("Disconnected. Reconnecting in 2s...");
        setTimeout(connectWebSocket, 2000);
    };
}

function attemptLogin() {
    const email = document.getElementById("email").value;
    const password = document.getElementById("password").value;
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "login", email, password }));
    }
}

async function attemptFaceLogin() {
    const errorEl = document.getElementById("login-error");
    if (faceLoginPending) return;
    if (!isWebSocketConnected()) {
        errorEl.innerText = "Connect to the assistant before logging in.";
        errorEl.style.color = "#ef4444";
        return;
    }
    faceLoginPending = true;
    errorEl.innerText = "Scanning face... Please look at the camera.";
    errorEl.style.color = "#3b82f6";
    
    // Show and start webcam preview
    const video = document.getElementById("login-webcam-preview");
    if (video) video.style.display = "block";
    
    try {
        let exactDeviceId = null;
        try {
            const devices = await navigator.mediaDevices.enumerateDevices();
            const videoDevices = devices.filter(d => d.kind === 'videoinput');
            for (let device of videoDevices) {
                const label = device.label.toLowerCase();
                if (!label.includes('phone') && !label.includes('virtual') && !label.includes('obs') && !label.includes('droidcam') && !label.includes('continuity')) {
                    exactDeviceId = device.deviceId;
                    break;
                }
            }
        } catch (e) {
            console.log("Could not enumerate devices", e);
        }
        
        const constraints = exactDeviceId 
            ? { video: { deviceId: { exact: exactDeviceId } }, audio: false }
            : { video: true, audio: false };

        loginWebcamStream = await navigator.mediaDevices.getUserMedia(constraints);
        if (video) {
            video.srcObject = loginWebcamStream;
            await video.play();
            await new Promise(resolve => setTimeout(resolve, 300));
            if (video.readyState < 2 || !video.videoWidth) {
                throw new Error("Camera preview is not ready.");
            }
        }
    } catch (e) {
        console.error("Camera access failed:", e);
        errorEl.innerText = "Camera access denied. Please allow camera permissions.";
        errorEl.style.color = "#ef4444";
        stopLoginWebcam();
        return; // Don't send request if camera fails
    }

    // Capture frame and send via WebSocket
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth || 640;
    canvas.height = video.videoHeight || 480;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    const base64Image = canvas.toDataURL("image/jpeg", 0.9);

    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "login_face", image_data: base64Image }));
    } else {
        errorEl.innerText = "Connection lost. Please try again.";
        errorEl.style.color = "#ef4444";
        stopLoginWebcam();
    }
}

function stopLoginWebcam() {
    faceLoginPending = false;
    if (loginWebcamStream) {
        loginWebcamStream.getTracks().forEach(t => t.stop());
        loginWebcamStream = null;
    }
    const video = document.getElementById("login-webcam-preview");
    if (video) {
        video.srcObject = null;
        video.style.display = "none";
    }
}

function toggleMenu() {
    const dropdown = document.getElementById("dropdown");
    const isOpen = dropdown.classList.contains("show");
    dropdown.classList.toggle("show");
    if (isOpen) goBackToMain(false); // reset to main panel on close
}

function goBackToMain() {
    document.getElementById("menu-slider").style.transform = "translateX(0)";
}

function openAvatarPanel() {
    document.getElementById("menu-slider").style.transform = "translateX(-25%)";
}

function closeAvatarPanel() {
    goBackToMain();
}

function openEmailSubPanel() {
    document.getElementById("menu-slider").style.transform = "translateX(-50%)";
    document.getElementById("dropdown").classList.remove("show");
    document.getElementById("dropdown").classList.add("show");
    fetchEmailsPanel(); // auto-load inbox
}

function openRemindersPanel() {
    document.getElementById("menu-slider").style.transform = "translateX(-75%)";
    document.getElementById("dropdown").classList.remove("show");
    document.getElementById("dropdown").classList.add("show");
    fetchRemindersPanel();
}

function fetchRemindersPanel() {
    const list = document.getElementById('rp-list');
    const status = document.getElementById('rp-status');
    if (!list) return;
    list.innerHTML = '<div class="ep-loading"><div class="ep-spinner"></div><p>Loading reminders...</p></div>';
    if (status) status.innerText = 'Loading...';
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({action: "get_reminders"}));
    }
}

function logout() {
    clearAccountConversation();
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "logout" }));
    }
    document.getElementById("app-view").classList.remove("active");
    document.getElementById("login-view").classList.add("active");
    document.getElementById("dropdown").classList.remove("show");
    document.getElementById("password").value = "";
    document.getElementById("login-error").innerText = "";
    textSubmissionPending = false;
    conversationInProgress = false;
    updateTextChatComposer();
}

function confirmReset() {
    if (confirm("Reset memory? This permanently deletes your saved conversations, messages, reminders, and learned personal and relationship memories. Your account, login details, and other users' data will be kept.")) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: "reset_memory" }));
            toggleMenu();
        } else {
            alert("Unable to reset: not connected to server.");
        }
    }
}

function exitApp() {
    if (confirm("Are you sure you want to close and shut down the Intelligent Assistant backend?")) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: "shutdown" }));
        }
        document.body.innerHTML = '<div style="display:flex;height:100vh;align-items:center;justify-content:center;color:#94a3b8;font-family:sans-serif;font-size:20px;">Intelligent Assistant has been shut down. You may close this tab.</div>';
    }
}

// Intercept tab close (X icon) to cleanly release all backend hardware/software resources
window.addEventListener('beforeunload', () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "shutdown" }));
    }
});

window.addEventListener('pagehide', () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "shutdown" }));
    }
});

function triggerTalk() {
    const statusText = document.getElementById("status-text");
    const btn = document.getElementById("talk-btn");
    
    if (isSpeaking) {
        // Intercept click while speaking to interrupt the AI
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: "interrupt" }));
        }
        return;
    }

    if (conversationInProgress && !isListening) {
        showToast("Please wait for the current response before starting another conversation turn.", "info");
        return;
    }
    
    if (!isListening) {
        // Start recording
        isListening = true;
        statusText.innerText = "🎤 Recording... (Press STOP when done)";
        statusText.style.color = "#ef4444";
        
        const visualizer = document.getElementById("audio-visualizer");
        if (visualizer) visualizer.classList.remove("hidden");
        
        btn.disabled = false;
        btn.style.background = "#ef4444";
        btn.innerText = "STOP";
        
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: "listen" }));
        }
        updateTextChatComposer();
    } else {
        // Stop recording
        statusText.innerHTML = '<div class="thinking-indicator"><div class="thinking-dot"></div><div class="thinking-dot"></div><div class="thinking-dot"></div></div> <span style="color:#f59e0b; margin-left:5px;">Processing...</span>';
        
        const visualizer = document.getElementById("audio-visualizer");
        if (visualizer) visualizer.classList.add("hidden");
        
        btn.disabled = true;
        btn.style.background = "#6b7280";
        btn.innerText = "PROCESSING...";
        
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: "stop_listen" }));
        }
        updateTextChatComposer();
    }
}

function handleAIState(data) {
    const statusText = document.getElementById("status-text");
    const btn = document.getElementById("talk-btn");
    isSpeaking = data.speaking;
    currentMood = data.mood || 'neutral';

    // Apply mood class
    const container = document.getElementById("canvas-container");
    if (container) {
        container.className = "canvas-container mood-" + currentMood.toLowerCase();
    }
    
    // Toggle audio visualizer based on speaking
    const visualizer = document.getElementById("audio-visualizer");
    if (visualizer) {
        if (isSpeaking || isListening) visualizer.classList.remove("hidden");
        else visualizer.classList.add("hidden");
    }

    if (data.speaking) {
        // AI just started speaking — clear the listening flag
        isListening = false;
        statusText.innerText = "🔊 AI Speaking...";
        statusText.style.color = "#38bdf8";
        btn.disabled = false;
        btn.style.background = "#ef4444";
        btn.innerText = "STOP AI";
    } else if (!isListening) {
        // Only reset to Ready if we are NOT currently listening
        if (!statusText.innerHTML.includes('thinking-indicator')) {
            statusText.innerText = "System: Ready.";
            statusText.style.color = "#94a3b8";
        }
        btn.disabled = false;
        btn.style.background = "#10b981";
        btn.innerText = "TALK";
    }

    updateTextChatComposer();
}

function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;
    
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    
    let icon = 'ℹ️';
    if (type === 'success') icon = '✅';
    if (type === 'error') icon = '❌';
    if (type === 'warning') icon = '⚠️';
    
    toast.innerHTML = `<span class="toast-icon">${icon}</span> <span>${message}</span>`;
    container.appendChild(toast);
    
    // Trigger reflow for animation
    void toast.offsetWidth;
    toast.classList.add('show');
    
    setTimeout(() => {
        toast.classList.remove('show');
        toast.classList.add('hiding');
        setTimeout(() => toast.remove(), 400); // Wait for transition
    }, 4000);
}

// Start avatar animation immediately on page load
requestAnimationFrame(render);

// Keep typed messages in the same live conversation as the voice controls.
initializeTextChatComposer();

// Start WebSocket connection
connectWebSocket();

// ============================================================
// EMAIL FLY-OUT PANEL
// ============================================================

function switchEPTab(tab) {
    document.querySelectorAll('.ep-tab').forEach(t => t.classList.remove('active'));
    document.getElementById('ep-tab-' + tab).classList.add('active');
    document.getElementById('ep-inbox').style.display = tab === 'inbox' ? 'flex' : 'none';
}

function fetchEmailsPanel() {
    const list = document.getElementById('ep-email-list');
    const status = document.getElementById('ep-inbox-status');
    if (!list) return;
    list.innerHTML = '<div class="ep-loading"><div class="ep-spinner"></div><p>Loading inbox...</p></div>';
    if (status) status.innerText = 'Loading...';
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: 'get_emails' }));
    }
}

function handleEmailMessages(data) {
    if (data.type === 'email_list') {
        const list = document.getElementById('ep-email-list');
        const status = document.getElementById('ep-inbox-status');
        if (!list) return;

        if (data.error) {
            list.innerHTML = `<div class="ep-error">❌ Failed to load.<br><small>${data.message || ''}</small></div>`;
            if (status) status.innerText = 'Error';
            return;
        }
        const emails = data.emails || [];
        if (status) status.innerText = `${emails.length} email${emails.length !== 1 ? 's' : ''}`;

        if (emails.length === 0) {
            list.innerHTML = '<div class="ep-empty">📭 Inbox is empty.</div>';
            return;
        }
        list.innerHTML = '';
        emails.forEach(email => {
            const card = document.createElement('div');
            card.className = 'ep-email-card';
            const initials = (email.from_name || '?').charAt(0).toUpperCase();
            card.innerHTML = `
                <div class="ep-email-avatar">${initials}</div>
                <div class="ep-email-content">
                    <div class="ep-email-from">${email.from_name || email.from_email}</div>
                    <div class="ep-email-subject">${email.subject}</div>
                </div>
            `;
            list.appendChild(card);
        });
    }
}



// ═══════════════════════════════════════════════════════════════════════════
// ═══════════════════════════════════════════════════════════════════════════
// GUIDED BIOMETRIC CALIBRATION WIZARD (ONE PHASE AT A TIME)
// ═══════════════════════════════════════════════════════════════════════════

const SETUP_FACE_ANGLES = [
    { key: 'front', label: 'Frontal View', icon: '👤', instruction: 'Look directly straight into the camera.' },
    { key: 'left', label: 'Left Profile', icon: '👈', instruction: 'Turn your head slightly to the left.' },
    { key: 'right', label: 'Right Profile', icon: '👉', instruction: 'Turn your head slightly to the right.' }
];

let currentFaceAngleIdx = 0;
let webcamStream = null;
let onboardingName = '';

async function startWebcamPreview() {
    const video = document.getElementById('setup-webcam-preview');
    const statusMsg = document.getElementById('face-status-msg');
    if (!video) return;

    video.muted = true;
    video.playsInline = true;
    video.autoplay = true;

    try {
        if (statusMsg) statusMsg.innerText = "Requesting live camera preview...";
        if (webcamStream) {
            webcamStream.getTracks().forEach(t => t.stop());
        }

        // Fix for Windows connecting to phones: filter out virtual/phone cameras
        let exactDeviceId = null;
        try {
            const devices = await navigator.mediaDevices.enumerateDevices();
            const videoDevices = devices.filter(d => d.kind === 'videoinput');
            for (let device of videoDevices) {
                const label = device.label.toLowerCase();
                // Avoid phone, virtual, continuity, droidcam
                if (!label.includes('phone') && !label.includes('virtual') && !label.includes('obs') && !label.includes('droidcam') && !label.includes('continuity')) {
                    exactDeviceId = device.deviceId;
                    break;
                }
            }
        } catch (e) {
            console.log("Could not enumerate devices", e);
        }

        // Try high quality first, fallback to basic constraints
        try {
            const constraints = exactDeviceId 
                ? { video: { deviceId: { exact: exactDeviceId }, width: { ideal: 640 }, height: { ideal: 480 } }, audio: false }
                : { video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' }, audio: false };
            webcamStream = await navigator.mediaDevices.getUserMedia(constraints);
        } catch (e1) {
            console.log("Ideal constraints failed, trying basic video:true...", e1);
            webcamStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
        }

        video.srcObject = webcamStream;
        video.onloadedmetadata = () => {
            video.play().then(() => {
                console.log("[Webcam] Live video stream playing successfully.");
                if (statusMsg) statusMsg.innerText = "Align your face in the circle and click 'Capture Angle'.";
            }).catch(e => {
                console.warn("[Webcam] Autoplay prevented, forcing play:", e);
                video.play();
            });
        };
    } catch (err) {
        console.error("Browser webcam error:", err);
        if (statusMsg) {
            statusMsg.innerHTML = `<span style="color:#ef4444;">⚠️ Camera access prompt: please click <strong>Allow</strong> in your browser address bar.</span>`;
        }
        showToast("⚠️ Please allow camera access in browser", "error");
    }
}

function stopWebcamPreview() {
    if (webcamStream) {
        webcamStream.getTracks().forEach(t => t.stop());
        webcamStream = null;
    }
    const video = document.getElementById('setup-webcam-preview');
    if (video) video.srcObject = null;
}

let faceReenrollment = false;

function startFaceReenrollment() {
    if (ws && ws.readyState === WebSocket.OPEN) {
        toggleMenu();
        ws.send(JSON.stringify({ action: "begin_face_reenrollment" }));
    } else {
        showToast("Connect to the assistant before updating Face ID.", "error");
    }
}

function finishFaceReenrollment() {
    stopWebcamPreview();
    faceReenrollment = false;
    document.getElementById("setup-wizard-view").classList.remove("active");
    document.getElementById("app-view").classList.add("active");
}

function startGuidedSetupWizard() {
    clearAccountConversation();
    faceReenrollment = false;
    document.getElementById("btn-skip-face").innerText = "Skip Face Login";
    document.getElementById("login-view").classList.remove("active");
    document.getElementById("app-view").classList.remove("active");
    document.getElementById("setup-wizard-view").classList.add("active");
    
    currentFaceAngleIdx = 0;
    onboardingName = '';
    const nameInput = document.getElementById('setup-input-name');
    if (nameInput) nameInput.value = '';
    
    switchSetupStep(0);
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: 'setup_begin' }));
    }
    setTimeout(() => nameInput?.focus(), 100);
}

function switchSetupStep(stepNum) {
    document.getElementById('setup-card-welcome').classList.toggle('active', stepNum === 0);
    document.getElementById('setup-card-face').classList.toggle('active', stepNum === 1);
    document.getElementById('setup-card-interview').classList.toggle('active', stepNum === 2);
    document.getElementById('setup-card-complete').classList.toggle('active', stepNum === 3);

    if (stepNum !== 1) {
        stopWebcamPreview();
    }
}

function continueWelcomeSetup() {
    const input = document.getElementById('setup-input-name');
    const name = input?.value.trim().replace(/\s+/g, ' ') || '';
    if (!name) {
        showToast('Please tell me the name you would like me to use.', 'warning');
        input?.focus();
        return;
    }

    onboardingName = name;
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: 'setup_save_interview', key: 'Name', value: name }));
    }
    switchSetupStep(1);
    updateFaceAngleUI();
    startWebcamPreview();
}

// ─── PHASE 1: FACE ID ───────────────────────────────────────────────────────
function updateFaceAngleUI() {
    if (currentFaceAngleIdx >= SETUP_FACE_ANGLES.length) {
        if (faceReenrollment) {
            finishFaceReenrollment();
            showToast("Face ID updated.", "success");
            return;
        }
        showToast("✅ Face ID calibration complete!", "success");
        stopWebcamPreview();
        setTimeout(() => {
            switchSetupStep(2);
        }, 800);
        return;
    }

    const angle = SETUP_FACE_ANGLES[currentFaceAngleIdx];
    const instructions = document.getElementById('face-instructions');
    const statusMsg = document.getElementById('face-status-msg');

    if (instructions) instructions.innerHTML = `<strong>Step ${currentFaceAngleIdx + 1}/3:</strong> ${angle.instruction}`;
    if (statusMsg) statusMsg.innerText = `Align your face inside the circle and click 'Capture Angle'.`;

    const badgeFront = document.getElementById('badge-front');
    const badgeLeft = document.getElementById('badge-left');
    const badgeRight = document.getElementById('badge-right');

    if (badgeFront) badgeFront.className = `angle-badge ${currentFaceAngleIdx === 0 ? 'active' : currentFaceAngleIdx > 0 ? 'done' : ''}`;
    if (badgeLeft) badgeLeft.className = `angle-badge ${currentFaceAngleIdx === 1 ? 'active' : currentFaceAngleIdx > 1 ? 'done' : ''}`;
    if (badgeRight) badgeRight.className = `angle-badge ${currentFaceAngleIdx === 2 ? 'active' : currentFaceAngleIdx > 2 ? 'done' : ''}`;
}

function handleCaptureFaceAngle() {
    if (currentFaceAngleIdx >= SETUP_FACE_ANGLES.length) return;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        showToast("Connection unavailable. Please try again.", "error");
        return;
    }
    const preview = document.getElementById('setup-webcam-preview');
    if (!preview || preview.readyState < 2 || !preview.videoWidth) {
        showToast("Wait for the camera preview before capturing.", "error");
        return;
    }
    const wrapper = document.getElementById('face-camera-wrapper');
    const statusMsg = document.getElementById('face-status-msg');
    const btn = document.getElementById('btn-capture-face');

    if (wrapper) wrapper.classList.add('scanning');
    if (btn) btn.disabled = true;
    if (statusMsg) statusMsg.innerText = "Analyzing facial geometry and embedding...";

    const angle = SETUP_FACE_ANGLES[currentFaceAngleIdx].key;
    
    // Capture frame and send to backend
    const video = document.getElementById('setup-webcam-preview');
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth || 640;
    canvas.height = video.videoHeight || 480;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    const base64Image = canvas.toDataURL("image/jpeg", 0.9);

    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "setup_capture_face", angle: angle, image_data: base64Image }));
    }
}

function handleSetupFaceResult(data) {
    const wrapper = document.getElementById('face-camera-wrapper');
    const statusMsg = document.getElementById('face-status-msg');
    const btn = document.getElementById('btn-capture-face');

    if (btn) btn.disabled = false;
    if (wrapper) wrapper.classList.remove('scanning');

    if (data && data.success) {
        if (wrapper) {
            wrapper.classList.add('success');
            setTimeout(() => wrapper.classList.remove('success'), 600);
        }
        if (statusMsg) statusMsg.innerText = `Angle ${currentFaceAngleIdx + 1}/3 captured successfully!`;
        currentFaceAngleIdx++;
        setTimeout(() => {
            updateFaceAngleUI();
            if (currentFaceAngleIdx < SETUP_FACE_ANGLES.length) {
                startWebcamPreview();
            }
        }, 600);
    } else {
        if (statusMsg) statusMsg.innerText = (data && data.error) ? data.error : "No face detected. Please center your face.";
        showToast("⚠️ " + ((data && data.error) ? data.error : "Face capture failed"), "error");
    }
}

function skipFaceSetup() {
    if (document.getElementById('btn-capture-face').disabled) return;
    if (faceReenrollment) {
        finishFaceReenrollment();
        return;
    }
    stopWebcamPreview();
    switchSetupStep(2);
}

// ─── PHASE 2: PERSONAL & EMAIL SETUP ───────────────────────────────────────
function toggleAppPasswordVisibility() {
    const input = document.getElementById('setup-input-app-pass');
    if (input) {
        input.type = (input.type === 'password') ? 'text' : 'password';
    }
}

function handleSaveProfileForm() {
    const email = document.getElementById('setup-input-email')?.value.trim() || '';
    const password = document.getElementById('setup-input-password')?.value.trim() || '';
    const appPass = document.getElementById('setup-input-app-pass')?.value.trim() || '';
    const goals = document.getElementById('setup-input-goals')?.value.trim() || '';

    if (ws && ws.readyState === WebSocket.OPEN) {
        if (email) {
            ws.send(JSON.stringify({ action: "setup_save_interview", key: "Gmail_Address", value: email }));
        }
        if (password) {
            ws.send(JSON.stringify({ action: "setup_save_interview", key: "Password", value: password }));
        }
        if (appPass) {
            ws.send(JSON.stringify({ action: "setup_save_interview", key: "Gmail_App_Password", value: appPass }));
        }
        if (goals) {
            ws.send(JSON.stringify({ action: "setup_save_interview", key: "Goals", value: goals }));
        }
    }

    showToast("💾 Profile & Email credentials saved!", "success");
    switchSetupStep(3);
}

function handleSkipEmailSetup() {
    switchSetupStep(3);
}

// ─── PHASE 4: COMPLETE & LAUNCH ────────────────────────────────────────────
function launchMainDashboard() {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "setup_complete" }));
    }
    document.getElementById('setup-wizard-view').classList.remove('active');
    document.getElementById('app-view').classList.add('active');
    showToast("✨ Welcome to your Intelligent Assistant!", "success");
}


// ═══════════════════════════════════════════════════════════════════════════
// COGNITIVE SOCIAL INTEL & STRATEGY HUB CONTROLLER (PRM)
// ═══════════════════════════════════════════════════════════════════════════

let cachedPRMContacts = [];
let selectedPRMContact = null;

function openPRMPanel() {
    const modal = document.getElementById('prm-modal');
    if (modal) modal.classList.remove('hidden');
    fetchPRMContacts();
}

function closePRMPanel() {
    const modal = document.getElementById('prm-modal');
    if (modal) modal.classList.add('hidden');
}

function togglePRMPanel() {
    const modal = document.getElementById('prm-modal');
    if (modal && modal.classList.contains('hidden')) {
        openPRMPanel();
    } else {
        closePRMPanel();
    }
}

function fetchPRMContacts() {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "get_prm_contacts" }));
    }
}

function handlePRMContactsList(contacts) {
    cachedPRMContacts = contacts;
    renderPRMContacts(contacts);

    if (selectedPRMContact) {
        const updated = contacts.find(c => c.contact_id === selectedPRMContact.contact_id);
        if (updated) {
            selectPRMContact(updated.contact_id);
        }
    }
}

function renderPRMContacts(contacts) {
    const list = document.getElementById('prm-contacts-list');
    if (!list) return;

    if (!contacts || contacts.length === 0) {
        list.innerHTML = `<div style="text-align:center; color:#64748b; padding:20px; font-size:12px;">No social intelligence recorded yet.<br>Click '+ Add Intel' or chat naturally!</div>`;
        return;
    }

    list.innerHTML = "";
    contacts.forEach(c => {
        const card = document.createElement('div');
        card.className = `prm-contact-card ${selectedPRMContact && selectedPRMContact.contact_id === c.contact_id ? 'active' : ''}`;
        card.onclick = () => selectPRMContact(c.contact_id);

        const initial = (c.name || "U")[0].toUpperCase();
        const attrCount = (c.attributes || []).length;

        card.innerHTML = `
            <div class="prm-avatar-pill">${initial}</div>
            <div class="prm-card-info">
                <div class="prm-card-name">${escapeHTML(c.name)}</div>
                <div class="prm-card-meta">${escapeHTML(c.relationship || "Acquaintance")} • ${attrCount} note${attrCount !== 1 ? 's' : ''}</div>
            </div>
        `;
        list.appendChild(card);
    });
}

function filterPRMContacts() {
    const query = (document.getElementById('prm-search-input')?.value || "").toLowerCase().trim();
    if (!query) {
        renderPRMContacts(cachedPRMContacts);
        return;
    }

    const filtered = cachedPRMContacts.filter(c => {
        if (c.name.toLowerCase().includes(query)) return true;
        if ((c.relationship || "").toLowerCase().includes(query)) return true;
        return (c.attributes || []).some(a => 
            a.category.toLowerCase().includes(query) || a.description.toLowerCase().includes(query)
        );
    });

    renderPRMContacts(filtered);
}

function selectPRMContact(contactId) {
    const contact = cachedPRMContacts.find(c => c.contact_id === contactId);
    if (!contact) return;

    selectedPRMContact = contact;

    // Update active class in sidebar
    renderPRMContacts(cachedPRMContacts);

    document.getElementById('prm-empty-state').classList.add('hidden');
    const dossierView = document.getElementById('prm-dossier-view');
    dossierView.classList.remove('hidden');

    document.getElementById('prm-dossier-name').innerText = contact.name;
    document.getElementById('prm-dossier-rel').innerText = contact.relationship || "Acquaintance";
    document.getElementById('prm-dossier-avatar').innerText = (contact.name || "👤")[0].toUpperCase();

    // Reset strategy output
    const simOutput = document.getElementById('prm-sim-output');
    if (simOutput) simOutput.classList.add('hidden');

    renderPRMMatrix(contact.attributes || []);
}

function renderPRMMatrix(attributes) {
    const triggersList = document.getElementById('prm-list-triggers');
    const leverageList = document.getElementById('prm-list-leverage');
    const familyList = document.getElementById('prm-list-family');
    const personalityList = document.getElementById('prm-list-personality');
    const commitmentsList = document.getElementById('prm-list-commitments');

    if (!triggersList) return;

    triggersList.innerHTML = "";
    leverageList.innerHTML = "";
    familyList.innerHTML = "";
    personalityList.innerHTML = "";
    commitmentsList.innerHTML = "";

    const createTag = (attr) => {
        const item = document.createElement('div');
        item.className = 'prm-tag-item';
        item.innerHTML = `
            <span>${escapeHTML(attr.description)}</span>
            <button class="prm-tag-delete" title="Delete attribute" onclick="handleDeleteAttribute(${attr.attribute_id})">✕</button>
        `;
        return item;
    };

    let counts = { triggers: 0, leverage: 0, family: 0, personality: 0, commitments: 0 };

    attributes.forEach(a => {
        const cat = (a.category || "").toLowerCase();
        if (cat.includes('trigger') || cat.includes('annoy') || cat.includes('weakness')) {
            triggersList.appendChild(createTag(a));
            counts.triggers++;
        } else if (cat.includes('leverage') || cat.includes('motivat') || cat.includes('care')) {
            leverageList.appendChild(createTag(a));
            counts.leverage++;
        } else if (cat.includes('family') || cat.includes('spouse') || cat.includes('kid') || cat.includes('backgr')) {
            familyList.appendChild(createTag(a));
            counts.family++;
        } else if (cat.includes('commit')) {
            commitmentsList.appendChild(createTag(a));
            counts.commitments++;
        } else {
            personalityList.appendChild(createTag(a));
            counts.personality++;
        }
    });

    if (counts.triggers === 0) triggersList.innerHTML = `<span style="font-size:11px; color:#64748b; font-style:italic;">None recorded</span>`;
    if (counts.leverage === 0) leverageList.innerHTML = `<span style="font-size:11px; color:#64748b; font-style:italic;">None recorded</span>`;
    if (counts.family === 0) familyList.innerHTML = `<span style="font-size:11px; color:#64748b; font-style:italic;">None recorded</span>`;
    if (counts.personality === 0) personalityList.innerHTML = `<span style="font-size:11px; color:#64748b; font-style:italic;">None recorded</span>`;
    if (counts.commitments === 0) commitmentsList.innerHTML = `<span style="font-size:11px; color:#64748b; font-style:italic;">No active promises or commitments</span>`;
}

function handleDeleteAttribute(attributeId) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "delete_prm_attribute", attribute_id: attributeId }));
        showToast("Attribute removed", "info");
    }
}

// ─── TACTICAL STRATEGY SIMULATOR ───────────────────────────────────────────
function handleRunStrategySimulation() {
    if (!selectedPRMContact) {
        showToast("Please select a person first.", "error");
        return;
    }

    const goal = document.getElementById('prm-sim-goal')?.value.trim();
    if (!goal) {
        showToast("Please enter your goal or scenario for the simulation.", "info");
        return;
    }

    const btn = document.getElementById('btn-run-sim');
    const output = document.getElementById('prm-sim-output');

    if (btn) {
        btn.disabled = true;
        btn.innerText = "⚡ Formulating Playbook...";
    }
    if (output) {
        output.classList.remove('hidden');
        output.innerHTML = `<div style="color:#38bdf8; font-size:13px;">🧠 Synthesizing psychological traits, triggers, and leverage points with local LLM...</div>`;
    }

    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            action: "prm_generate_strategy",
            name: selectedPRMContact.name,
            goal: goal
        }));
    }
}

function handlePRMStrategyResult(data) {
    const btn = document.getElementById('btn-run-sim');
    const output = document.getElementById('prm-sim-output');

    if (btn) {
        btn.disabled = false;
        btn.innerText = "Generate Game Plan";
    }

    if (!data || !data.success || !data.strategy) {
        if (output) {
            output.innerHTML = `<div style="color:#ef4444; font-size:13px;">⚠️ ${(data && data.error) ? data.error : "Strategy generation failed."}</div>`;
        }
        return;
    }

    const s = data.strategy;
    if (output) {
        output.classList.remove('hidden');
        output.innerHTML = `
            <div class="strat-card">
                <span class="strat-label reading">🎯 Psychological Reading & Mindset</span>
                <div>${escapeHTML(s.psychological_reading || "No reading available")}</div>
            </div>
            <div class="strat-card">
                <span class="strat-label hook">🤝 Recommended Opening Hook / Icebreaker</span>
                <div>${escapeHTML(s.opening_hook || "Open with a friendly warm check-in")}</div>
            </div>
            <div class="strat-card">
                <span class="strat-label frame">♟️ Persuasive Framing & Tactical Leverage</span>
                <div>${escapeHTML(s.persuasive_framing || "Frame the proposition around mutual value")}</div>
            </div>
            <div class="strat-card">
                <span class="strat-label avoid">🚫 Critical Pitfalls (What to Avoid)</span>
                <div>${escapeHTML(s.what_to_avoid || "Avoid putting direct pressure")}</div>
            </div>
        `;
    }
}

// ─── QUICK ADD INTEL MODAL ─────────────────────────────────────────────────
function openAddIntelModal() {
    document.getElementById('prm-add-modal').classList.remove('hidden');
    if (selectedPRMContact) {
        document.getElementById('add-intel-name').value = selectedPRMContact.name;
        document.getElementById('add-intel-rel').value = selectedPRMContact.relationship || "";
    } else {
        document.getElementById('add-intel-name').value = "";
        document.getElementById('add-intel-rel').value = "";
    }
    document.getElementById('add-intel-desc').value = "";
}

function closeAddIntelModal() {
    document.getElementById('prm-add-modal').classList.add('hidden');
}

function submitAddIntel() {
    const name = document.getElementById('add-intel-name')?.value.trim();
    const rel = document.getElementById('add-intel-rel')?.value.trim() || "Acquaintance";
    const cat = document.getElementById('add-intel-cat')?.value;
    const desc = document.getElementById('add-intel-desc')?.value.trim();

    if (!name || !desc) {
        showToast("Please enter both a name and an intel note.", "error");
        return;
    }

    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            action: "save_prm_attribute",
            name: name,
            relationship: rel,
            category: cat,
            description: desc
        }));
    }

    showToast(`Saved intel note for ${name}!`, "success");
    closeAddIntelModal();
}

function escapeHTML(str) {
    if (!str) return '';
    return str.replace(/[&<>'"]/g, 
        tag => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[tag] || tag)
    );
}


// ═══════════════════════════════════════════════════════════════════════════
// INTERACTIVE FORCE-DIRECTED KNOWLEDGE GRAPH PHYSICS ENGINE
// ═══════════════════════════════════════════════════════════════════════════

let currentPRMView = 'dossier';
let kgNodes = [];
let kgEdges = [];
let kgCanvas = null;
let kgCtx = null;
let kgAnimId = null;

let kgPanX = 0;
let kgPanY = 0;
let kgZoom = 1.0;
let kgDraggedNode = null;
let kgHoveredNode = null;
let kgSelectedNode = null;
let kgIsPanning = false;
let kgLastMouseX = 0;
let kgLastMouseY = 0;

const NODE_COLORS = {
    'Person': { fill: '#0284c7', stroke: '#38bdf8', glow: 'rgba(56, 189, 248, 0.4)', icon: '👤' },
    'Project': { fill: '#7e22ce', stroke: '#a855f7', glow: 'rgba(168, 85, 247, 0.4)', icon: '💼' },
    'Trigger': { fill: '#b91c1c', stroke: '#f87171', glow: 'rgba(248, 113, 113, 0.4)', icon: '⚡' },
    'Family': { fill: '#d97706', stroke: '#fbbf24', glow: 'rgba(251, 191, 36, 0.4)', icon: '👨‍👩‍👧' },
    'Preference': { fill: '#047857', stroke: '#34d399', glow: 'rgba(52, 211, 153, 0.4)', icon: '✨' },
    'Goal': { fill: '#c2410c', stroke: '#fb923c', glow: 'rgba(251, 146, 60, 0.4)', icon: '🎯' },
    'Concept': { fill: '#334155', stroke: '#94a3b8', glow: 'rgba(148, 163, 184, 0.3)', icon: '💡' }
};

function switchPRMView(view) {
    currentPRMView = view;
    document.getElementById('btn-tab-dossier')?.classList.toggle('active', view === 'dossier');
    document.getElementById('btn-tab-graph')?.classList.toggle('active', view === 'graph');

    const dossierLayout = document.getElementById('prm-dossier-layout');
    const graphLayout = document.getElementById('prm-graph-layout');

    if (view === 'dossier') {
        if (dossierLayout) dossierLayout.classList.remove('hidden');
        if (graphLayout) graphLayout.classList.add('hidden');
    } else {
        if (dossierLayout) dossierLayout.classList.add('hidden');
        if (graphLayout) graphLayout.classList.remove('hidden');
        initKnowledgeGraphCanvas();
        fetchKnowledgeGraph();
    }
}

function fetchKnowledgeGraph() {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ action: "get_knowledge_graph" }));
    }
}

function handleKnowledgeGraphData(graphData) {
    if (!graphData) return;

    const rawNodes = graphData.nodes || [];
    const rawEdges = graphData.edges || [];

    const countEl = document.getElementById('graph-node-count');
    if (countEl) countEl.innerText = `${rawNodes.length} Nodes • ${rawEdges.length} Edges`;

    // Map into simulation node objects while preserving existing coordinates if already laid out
    const nodeMap = new Map();
    kgNodes.forEach(n => nodeMap.set(n.id, { x: n.x, y: n.y, vx: n.vx, vy: n.vy }));

    const canvas = document.getElementById('knowledge-graph-canvas');
    const cx = canvas ? canvas.width / 2 : 400;
    const cy = canvas ? canvas.height / 2 : 300;

    kgNodes = rawNodes.map((rn, idx) => {
        const existing = nodeMap.get(rn.id);
        const angle = (idx / rawNodes.length) * Math.PI * 2;
        const dist = 120 + Math.random() * 80;
        return {
            id: rn.id,
            name: rn.name,
            type: rn.type || 'Concept',
            description: rn.description || '',
            x: existing ? existing.x : cx + Math.cos(angle) * dist,
            y: existing ? existing.y : cy + Math.sin(angle) * dist,
            vx: existing ? existing.vx : 0,
            vy: existing ? existing.vy : 0,
            radius: 20
        };
    });

    kgEdges = rawEdges.map(re => ({
        id: re.id,
        source: re.source,
        target: re.target,
        relation: re.relation,
        context: re.context,
        source_name: re.source_name,
        target_name: re.target_name
    }));

    if (!kgAnimId) {
        runKnowledgeGraphSimulation();
    }
}

function initKnowledgeGraphCanvas() {
    kgCanvas = document.getElementById('knowledge-graph-canvas');
    if (!kgCanvas) return;

    const container = document.getElementById('graph-canvas-container');
    if (container) {
        kgCanvas.width = container.clientWidth || 800;
        kgCanvas.height = container.clientHeight || 550;
    }

    kgCtx = kgCanvas.getContext('2d');

    // Attach mouse listeners once
    if (!kgCanvas.hasGraphListeners) {
        kgCanvas.hasGraphListeners = true;
        kgCanvas.addEventListener('mousedown', onGraphMouseDown);
        kgCanvas.addEventListener('mousemove', onGraphMouseMove);
        window.addEventListener('mouseup', onGraphMouseUp);
        kgCanvas.addEventListener('wheel', onGraphWheel, { passive: false });
    }
}

function runKnowledgeGraphSimulation() {
    function step() {
        if (currentPRMView === 'graph') {
            updateGraphPhysics();
            renderKnowledgeGraph();
        }
        kgAnimId = requestAnimationFrame(step);
    }
    kgAnimId = requestAnimationFrame(step);
}

function updateGraphPhysics() {
    if (kgNodes.length === 0) return;

    const kRepel = 2400; // Coulomb repulsion
    const kSpring = 0.04; // Hooke spring
    const springLength = 130;
    const damping = 0.82;
    const centerGravity = 0.015;

    const cx = kgCanvas ? kgCanvas.width / 2 : 400;
    const cy = kgCanvas ? kgCanvas.height / 2 : 300;

    // 1. Node-Node Repulsion
    for (let i = 0; i < kgNodes.length; i++) {
        for (let j = i + 1; j < kgNodes.length; j++) {
            const n1 = kgNodes[i];
            const n2 = kgNodes[j];
            const dx = n2.x - n1.x;
            const dy = n2.y - n1.y;
            const dist = Math.sqrt(dx * dx + dy * dy) || 1;

            if (dist < 450) {
                const force = kRepel / (dist * dist);
                const fx = (dx / dist) * force;
                const fy = (dy / dist) * force;

                n1.vx -= fx;
                n1.vy -= fy;
                n2.vx += fx;
                n2.vy += fy;
            }
        }
    }

    // 2. Edge Spring Attraction
    const nodeLookup = new Map(kgNodes.map(n => [n.id, n]));
    for (let edge of kgEdges) {
        const src = nodeLookup.get(edge.source);
        const tgt = nodeLookup.get(edge.target);
        if (src && tgt) {
            const dx = tgt.x - src.x;
            const dy = tgt.y - src.y;
            const dist = Math.sqrt(dx * dx + dy * dy) || 1;
            const force = (dist - springLength) * kSpring;
            const fx = (dx / dist) * force;
            const fy = (dy / dist) * force;

            src.vx += fx;
            src.vy += fy;
            tgt.vx -= fx;
            tgt.vy -= fy;
        }
    }

    // 3. Center Gravity & Velocity Update
    for (let node of kgNodes) {
        if (node === kgDraggedNode) continue; // Dragged node stays under mouse

        node.vx += (cx - node.x) * centerGravity;
        node.vy += (cy - node.y) * centerGravity;

        node.vx *= damping;
        node.vy *= damping;

        node.x += node.vx;
        node.y += node.vy;
    }
}

function renderKnowledgeGraph() {
    if (!kgCtx || !kgCanvas) return;

    kgCtx.clearRect(0, 0, kgCanvas.width, kgCanvas.height);

    kgCtx.save();
    kgCtx.translate(kgPanX, kgPanY);
    kgCtx.scale(kgZoom, kgZoom);

    // Draw background grid dots
    drawGraphGrid();

    const nodeLookup = new Map(kgNodes.map(n => [n.id, n]));

    // 1. Draw Edges
    for (let edge of kgEdges) {
        const src = nodeLookup.get(edge.source);
        const tgt = nodeLookup.get(edge.target);
        if (!src || !tgt) continue;

        const isHighlighted = (kgSelectedNode && (kgSelectedNode.id === src.id || kgSelectedNode.id === tgt.id)) ||
                              (kgHoveredNode && (kgHoveredNode.id === src.id || kgHoveredNode.id === tgt.id));

        drawDirectedEdge(src, tgt, edge.relation, isHighlighted);
    }

    // 2. Draw Nodes
    for (let node of kgNodes) {
        const isSelected = kgSelectedNode && kgSelectedNode.id === node.id;
        const isHovered = kgHoveredNode && kgHoveredNode.id === node.id;
        drawGraphNode(node, isSelected, isHovered);
    }

    kgCtx.restore();
}

function drawGraphGrid() {
    const spacing = 40;
    kgCtx.fillStyle = 'rgba(255, 255, 255, 0.03)';
    const startX = -kgPanX / kgZoom - 200;
    const endX = startX + (kgCanvas.width / kgZoom) + 400;
    const startY = -kgPanY / kgZoom - 200;
    const endY = startY + (kgCanvas.height / kgZoom) + 400;

    for (let x = Math.floor(startX / spacing) * spacing; x < endX; x += spacing) {
        for (let y = Math.floor(startY / spacing) * spacing; y < endY; y += spacing) {
            kgCtx.fillRect(x, y, 1.5, 1.5);
        }
    }
}

function drawDirectedEdge(src, tgt, label, isHighlighted) {
    const dx = tgt.x - src.x;
    const dy = tgt.y - src.y;
    const dist = Math.sqrt(dx * dx + dy * dy) || 1;

    // Offset from node circles
    const startX = src.x + (dx / dist) * src.radius;
    const startY = src.y + (dy / dist) * src.radius;
    const endX = tgt.x - (dx / dist) * (tgt.radius + 6);
    const endY = tgt.y - (dy / dist) * (tgt.radius + 6);

    kgCtx.strokeStyle = isHighlighted ? '#38bdf8' : 'rgba(148, 163, 184, 0.25)';
    kgCtx.lineWidth = isHighlighted ? 2.5 : 1.2;

    kgCtx.beginPath();
    kgCtx.moveTo(startX, startY);
    kgCtx.lineTo(endX, endY);
    kgCtx.stroke();

    // Draw Arrow Head
    const arrowAngle = Math.atan2(dy, dx);
    const arrowLength = isHighlighted ? 10 : 8;
    kgCtx.fillStyle = isHighlighted ? '#38bdf8' : 'rgba(148, 163, 184, 0.4)';
    kgCtx.beginPath();
    kgCtx.moveTo(endX, endY);
    kgCtx.lineTo(endX - arrowLength * Math.cos(arrowAngle - Math.PI / 6), endY - arrowLength * Math.sin(arrowAngle - Math.PI / 6));
    kgCtx.lineTo(endX - arrowLength * Math.cos(arrowAngle + Math.PI / 6), endY - arrowLength * Math.sin(arrowAngle + Math.PI / 6));
    kgCtx.closePath();
    kgCtx.fill();

    // Draw Edge Label pill
    if (label) {
        const midX = (startX + endX) / 2;
        const midY = (startY + endY) / 2;
        const cleanLabel = label.replace(/_/g, ' ');

        kgCtx.font = '9px Inter, sans-serif';
        const textWidth = kgCtx.measureText(cleanLabel).width;

        kgCtx.fillStyle = 'rgba(2, 6, 23, 0.85)';
        kgCtx.fillRect(midX - textWidth / 2 - 4, midY - 6, textWidth + 8, 12);
        kgCtx.strokeStyle = isHighlighted ? 'rgba(56, 189, 248, 0.5)' : 'rgba(255, 255, 255, 0.08)';
        kgCtx.strokeRect(midX - textWidth / 2 - 4, midY - 6, textWidth + 8, 12);

        kgCtx.fillStyle = isHighlighted ? '#38bdf8' : '#94a3b8';
        kgCtx.textAlign = 'center';
        kgCtx.textBaseline = 'middle';
        kgCtx.fillText(cleanLabel, midX, midY);
    }
}

function drawGraphNode(node, isSelected, isHovered) {
    const style = NODE_COLORS[node.type] || NODE_COLORS['Concept'];

    // Outer Glow Ring
    if (isSelected || isHovered) {
        kgCtx.beginPath();
        kgCtx.arc(node.x, node.y, node.radius + 8, 0, Math.PI * 2);
        kgCtx.fillStyle = style.glow;
        kgCtx.fill();
    }

    // Node Circle
    kgCtx.beginPath();
    kgCtx.arc(node.x, node.y, node.radius, 0, Math.PI * 2);
    kgCtx.fillStyle = style.fill;
    kgCtx.fill();
    kgCtx.strokeStyle = (isSelected || isHovered) ? '#ffffff' : style.stroke;
    kgCtx.lineWidth = isSelected ? 3 : 2;
    kgCtx.stroke();

    // Node Icon
    kgCtx.font = '12px sans-serif';
    kgCtx.textAlign = 'center';
    kgCtx.textBaseline = 'middle';
    kgCtx.fillText(style.icon, node.x, node.y);

    // Node Label Text Below
    kgCtx.font = `${isSelected ? 'bold ' : ''}11px Inter, sans-serif`;
    kgCtx.fillStyle = isSelected ? '#38bdf8' : '#f8fafc';
    kgCtx.textAlign = 'center';
    kgCtx.textBaseline = 'top';

    // Text background shadow for clarity
    kgCtx.shadowColor = 'rgba(0, 0, 0, 0.8)';
    kgCtx.shadowBlur = 4;
    kgCtx.fillText(node.name, node.x, node.y + node.radius + 4);
    kgCtx.shadowBlur = 0;
}

// ─── MOUSE & INTERACTION EVENTS ───────────────────────────────────────────
function getTransformedMousePos(e) {
    const rect = kgCanvas.getBoundingClientRect();
    const clientX = e.clientX - rect.left;
    const clientY = e.clientY - rect.top;
    return {
        x: (clientX - kgPanX) / kgZoom,
        y: (clientY - kgPanY) / kgZoom,
        rawX: clientX,
        rawY: clientY
    };
}

function findNodeAtPos(pos) {
    for (let i = kgNodes.length - 1; i >= 0; i--) {
        const node = kgNodes[i];
        const dx = pos.x - node.x;
        const dy = pos.y - node.y;
        if (Math.sqrt(dx * dx + dy * dy) <= node.radius + 4) {
            return node;
        }
    }
    return null;
}

function onGraphMouseDown(e) {
    const pos = getTransformedMousePos(e);
    const clickedNode = findNodeAtPos(pos);

    if (clickedNode) {
        kgDraggedNode = clickedNode;
        kgSelectedNode = clickedNode;
        openGraphInspector(clickedNode);
    } else {
        kgIsPanning = true;
        kgLastMouseX = e.clientX;
        kgLastMouseY = e.clientY;
    }
}

function onGraphMouseMove(e) {
    const pos = getTransformedMousePos(e);

    if (kgDraggedNode) {
        kgDraggedNode.x = pos.x;
        kgDraggedNode.y = pos.y;
        kgDraggedNode.vx = 0;
        kgDraggedNode.vy = 0;
    } else if (kgIsPanning) {
        const dx = e.clientX - kgLastMouseX;
        const dy = e.clientY - kgLastMouseY;
        kgPanX += dx;
        kgPanY += dy;
        kgLastMouseX = e.clientX;
        kgLastMouseY = e.clientY;
    } else {
        kgHoveredNode = findNodeAtPos(pos);
        if (kgCanvas) kgCanvas.style.cursor = kgHoveredNode ? 'pointer' : 'grab';
    }
}

function onGraphMouseUp() {
    kgDraggedNode = null;
    kgIsPanning = false;
}

function onGraphWheel(e) {
    e.preventDefault();
    const rect = kgCanvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    const zoomFactor = e.deltaY < 0 ? 1.12 : 0.88;
    const newZoom = Math.max(0.3, Math.min(3.0, kgZoom * zoomFactor));

    kgPanX = mouseX - (mouseX - kgPanX) * (newZoom / kgZoom);
    kgPanY = mouseY - (mouseY - kgPanY) * (newZoom / kgZoom);
    kgZoom = newZoom;
}

function graphZoom(factor) {
    if (!kgCanvas) return;
    const cx = kgCanvas.width / 2;
    const cy = kgCanvas.height / 2;
    const newZoom = Math.max(0.3, Math.min(3.0, kgZoom * factor));

    kgPanX = cx - (cx - kgPanX) * (newZoom / kgZoom);
    kgPanY = cy - (cy - kgPanY) * (newZoom / kgZoom);
    kgZoom = newZoom;
}

function graphResetView() {
    kgPanX = 0;
    kgPanY = 0;
    kgZoom = 1.0;
}

function openGraphInspector(node) {
    const inspector = document.getElementById('graph-inspector');
    if (!inspector) return;

    inspector.classList.remove('hidden');
    document.getElementById('insp-name').innerText = node.name;
    const typeBadge = document.getElementById('insp-type');
    if (typeBadge) {
        typeBadge.innerText = node.type;
        const color = (NODE_COLORS[node.type] || NODE_COLORS['Concept']).stroke;
        typeBadge.style.color = color;
        typeBadge.style.borderColor = color;
    }

    const linksList = document.getElementById('insp-links');
    if (!linksList) return;

    linksList.innerHTML = "";

    const connectedEdges = kgEdges.filter(e => e.source === node.id || e.target === node.id);
    if (connectedEdges.length === 0) {
        linksList.innerHTML = `<div style="font-size:11px; color:#64748b; font-style:italic;">No connected edges yet.</div>`;
        return;
    }

    connectedEdges.forEach(e => {
        const isOutgoing = e.source === node.id;
        const otherName = isOutgoing ? e.target_name : e.source_name;
        const relClean = (e.relation || 'related to').replace(/_/g, ' ');

        const item = document.createElement('div');
        item.className = 'inspector-link-item';
        item.innerHTML = isOutgoing
            ? `──[ <strong>${escapeHTML(relClean)}</strong> ]──► ${escapeHTML(otherName)}`
            : `◄──[ <strong>${escapeHTML(relClean)}</strong> ]── ${escapeHTML(otherName)}`;
        linksList.appendChild(item);
    });
}

function closeGraphInspector() {
    const inspector = document.getElementById('graph-inspector');
    if (inspector) inspector.classList.add('hidden');
    kgSelectedNode = null;
}
